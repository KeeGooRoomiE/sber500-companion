package ru.keegoo.companion.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.keegoo.companion.data.collector.firstUnlockBetween
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.prefs.isMorningDeliveredToday
import ru.keegoo.companion.data.prefs.markMorningDelivered
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.todayCheckIn
import ru.keegoo.companion.work.DailyCollectWorker
import ru.keegoo.companion.domain.profile.DefaultEveningTime
import ru.keegoo.companion.domain.profile.DefaultMorningTime
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.parseTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private const val TAG = "Reminders"

/** How long the alarm path waits for the server before falling back to the local forecast. */
private const val SERVER_TEXT_BUDGET_MS = 7_000L

enum class ReminderKind { Morning, Evening }

/**
 * Daily reminders, on AlarmManager rather than WorkManager.
 *
 * WorkManager is the wrong tool for "fire at a particular time": it explicitly lets the system
 * defer work, and App Standby then defers a rarely-opened app to roughly one job a day at a
 * moment of the system's choosing. Measured on a real device: 16 hours, not a single background
 * execution — so neither the forecast nor the check-in ever arrived.
 *
 * AlarmManager's *AndAllowWhileIdle alarms are the documented exception to Doze, which is
 * exactly the guarantee this needs. Exact ones need permission from Android 12; without it the
 * inexact variant still pierces Doze, just with looser timing, so the feature degrades instead
 * of disappearing.
 *
 * Morning, by default: «Когда возьму телефон». Nothing subscribes to unlocks — that needs a
 * live process, i.e. a foreground service and a permanent notification. Instead the alarm wakes
 * up, reads the unlock history UsageStats already keeps, and either posts the forecast or
 * re-arms 15 minutes later. A fixed time (07:40 etc.) is still an option and the fallback
 * without usage access.
 *
 * Alarms do not survive a reboot the way WorkManager does, so [BootReceiver] re-arms them.
 */
object NotificationScheduler {

    /** Earlier unlocks are night-time, not the start of the day. */
    private val WATCH_FROM: LocalTime = LocalTime.of(5, 0)
    /** No unlock by 13:00 — the morning has passed, a forecast now would be odd. */
    private val WATCH_UNTIL: LocalTime = LocalTime.of(13, 0)
    /** How often to look for the first unlock. Matches Doze's own minimum for idle alarms. */
    private const val WATCH_STEP_MIN = 15L

    const val ACTION_REMIND = "ru.keegoo.companion.ACTION_REMIND"
    const val EXTRA_KIND = "kind"

    /** Called on app start and after boot: (re-)arms both reminders. */
    suspend fun ensureScheduled(context: Context) {
        val answers = context.profileAnswersNow()
        applyMorning(context, answers[ProfileIds.MORNING_TIME])
        schedule(context, ReminderKind.Evening, parseTime(answers[ProfileIds.EVENING_TIME]) ?: DefaultEveningTime)
    }

    /**
     * Morning mode from the profile answer: at the first unlock (default) or at a fixed time.
     *
     * In watch mode this deliberately does not jump to tomorrow when the morning is still
     * going: an alarm firing starts the process, so Application.onCreate — and this — race
     * with the receiver that is handling that very alarm. Arming "look again shortly" makes
     * both outcomes the same, instead of one of them cancelling today's watch.
     */
    fun applyMorning(context: Context, answer: String?) {
        parseTime(answer)?.let { return schedule(context, ReminderKind.Morning, it) }
        val now = LocalDateTime.now()
        val next = when {
            now.toLocalTime() < WATCH_FROM -> now.toLocalDate().atTime(WATCH_FROM)
            now.toLocalTime() <= WATCH_UNTIL -> now.plusMinutes(WATCH_STEP_MIN)
            else -> tomorrowAt(WATCH_FROM)
        }
        scheduleAt(context, ReminderKind.Morning, next)
    }

    /** Called when the person picks a new time in the profile. */
    fun reschedule(context: Context, kind: ReminderKind, time: LocalTime) {
        schedule(context, kind, time)
    }

    private fun pendingIntent(context: Context, kind: ReminderKind): PendingIntent =
        PendingIntent.getBroadcast(
            context, kind.ordinal,
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_REMIND)
                .setPackage(context.packageName)
                .putExtra(EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arms [kind] for the next occurrence of [time] (today if it is still ahead). */
    private fun schedule(context: Context, kind: ReminderKind, time: LocalTime) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        scheduleAt(context, kind, next)
    }

    /** Arms [kind] for an exact moment. */
    fun scheduleAt(context: Context, kind: ReminderKind, at: LocalDateTime) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = pendingIntent(context, kind)
        // Exact needs SCHEDULE_EXACT_ALARM from Android 12 and the person can revoke it.
        // The inexact variant needs nothing and still fires in Doze, so it is a real fallback
        // rather than a silent failure.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }.onFailure {
            // SecurityException if the permission was revoked between the check and the call.
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent) }
        }
        Log.d(TAG, "armed $kind for $at (exact=$exact)")
    }

    /** What the morning alarm should do now: when to wake next, and whether to post. */
    internal data class MorningPlan(val next: LocalDateTime, val post: Boolean)

    /**
     * Decides the morning alarm's next move using only on-device data.
     *
     * Deliberately does no network: the caller arms [MorningPlan.next] before posting, so a slow
     * request cannot cost the chain its next alarm (see [ReminderReceiver]).
     */
    internal suspend fun planMorning(context: Context): MorningPlan {
        val now = LocalDateTime.now()
        val answers = context.profileAnswersNow()
        val fixed = parseTime(answers[ProfileIds.MORNING_TIME])

        // Already seen today, in the app or from a push: nothing to do until tomorrow.
        if (context.isMorningDeliveredToday()) {
            return MorningPlan(tomorrowAt(fixed ?: WATCH_FROM), post = false)
        }
        if (fixed != null) {
            return MorningPlan(tomorrowAt(fixed), post = true)
        }

        // Watch mode. Without usage access there is no unlock to see, so behave like a fixed time.
        if (!context.hasUsageAccess()) {
            return if (now.toLocalTime() >= DefaultMorningTime) {
                MorningPlan(tomorrowAt(WATCH_FROM), post = true)
            } else {
                MorningPlan(now.toLocalDate().atTime(DefaultMorningTime), post = false)
            }
        }
        if (now.toLocalTime() < WATCH_FROM) {
            return MorningPlan(now.toLocalDate().atTime(WATCH_FROM), post = false)
        }
        if (now.toLocalTime() > WATCH_UNTIL) {
            return MorningPlan(tomorrowAt(WATCH_FROM), post = false)
        }

        val from = now.toLocalDate().atTime(WATCH_FROM).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (context.firstUnlockBetween(from, System.currentTimeMillis()) != null) {
            return MorningPlan(tomorrowAt(WATCH_FROM), post = true)
        }
        // Not up yet — look again shortly, unless that would run past the morning.
        val nextLook = now.plusMinutes(WATCH_STEP_MIN)
        val next = if (nextLook.toLocalTime() > WATCH_UNTIL) tomorrowAt(WATCH_FROM) else nextLook
        return MorningPlan(next, post = false)
    }

    private fun tomorrowAt(time: LocalTime): LocalDateTime =
        LocalDate.now().plusDays(1).atTime(time)

    internal suspend fun nextEvening(context: Context): LocalDateTime {
        val answers = context.profileAnswersNow()
        return tomorrowAt(parseTime(answers[ProfileIds.EVENING_TIME]) ?: DefaultEveningTime)
    }
}

/** The morning forecast as a notification: the server text if ready, a local copy otherwise. */
suspend fun postMorningForecast(context: Context) {
    val repo = dagger.hilt.android.EntryPointAccessors
        .fromApplication(context.applicationContext, CheckInEntryPoint::class.java)
        .repository()
    // A BroadcastReceiver gets roughly ten seconds, while the API client allows 15 s to connect
    // and 30 s to read. Waiting the full time risks the process dying with nothing shown, so the
    // server text gets a short window and the local forecast goes out if it misses it.
    val llmText = withTimeoutOrNull(SERVER_TEXT_BUDGET_MS) {
        repo.getMorning()
            .getOrNull()
            ?.takeIf { it.message.isNotBlank() }
            ?.let { NotificationCopy("Прогноз на сегодня", it.message) }
    }
    showMorningNotification(context, llmText ?: MorningCopies.forToday())
    context.markMorningDelivered()
}

/** Fires the reminder and immediately arms the next one — an alarm is a one-shot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationScheduler.ACTION_REMIND) return
        val kind = intent.getStringExtra(NotificationScheduler.EXTRA_KIND)
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() } ?: return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Arm the next alarm BEFORE any network work. goAsync() buys a receiver only
                // about ten seconds, while fetching the forecast can take far longer than that
                // (15 s connect + 30 s read). If the process is killed mid-request, the chain
                // must already have its next link — otherwise the reminders stop for good until
                // the app is opened or the phone reboots.
                when (kind) {
                    ReminderKind.Morning -> {
                        val plan = NotificationScheduler.planMorning(app)
                        NotificationScheduler.scheduleAt(app, kind, plan.next)
                        if (plan.post) {
                            // Catch-up upload: only on the day's single posting alarm, not on
                            // every 15-minute watch step.
                            DailyCollectWorker.enqueueOnce(app)
                            postMorningForecast(app)
                        }
                    }
                    ReminderKind.Evening -> {
                        NotificationScheduler.scheduleAt(app, kind, NotificationScheduler.nextEvening(app))
                        // Today's data goes up now, so tomorrow's forecast has something to be
                        // built from — the periodic collector cannot be relied on for that.
                        DailyCollectWorker.enqueueOnce(app)
                        // Already answered today, in the app or from the notification.
                        if (app.todayCheckIn().first() == null) showCheckinNotification(app)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reminder $kind failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are dropped on reboot — WorkManager used to persist them, AlarmManager does not. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationScheduler.ensureScheduled(app)
            } finally {
                pending.finish()
            }
        }
    }
}
