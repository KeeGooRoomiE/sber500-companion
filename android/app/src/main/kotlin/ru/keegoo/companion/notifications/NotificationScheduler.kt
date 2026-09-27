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
import ru.keegoo.companion.data.collector.firstUnlockBetween
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.prefs.isMorningDeliveredToday
import ru.keegoo.companion.data.prefs.markMorningDelivered
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.todayCheckIn
import ru.keegoo.companion.domain.profile.DefaultEveningTime
import ru.keegoo.companion.domain.profile.DefaultMorningTime
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.parseTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private const val TAG = "Reminders"

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

    /** Morning mode from the profile answer: at the first unlock (default) or at a fixed time. */
    fun applyMorning(context: Context, answer: String?) {
        val fixed = parseTime(answer)
        // In watch mode the first alarm of the day is at WATCH_FROM; the receiver re-arms itself
        // every WATCH_STEP_MIN until it sees an unlock.
        schedule(context, ReminderKind.Morning, fixed ?: WATCH_FROM)
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

    /**
     * Decides what the morning alarm should do right now and re-arms it.
     * Returns the moment of the next alarm.
     */
    internal suspend fun handleMorning(context: Context): LocalDateTime {
        val now = LocalDateTime.now()
        val answers = context.profileAnswersNow()
        val fixed = parseTime(answers[ProfileIds.MORNING_TIME])

        // Already seen today, in the app or from a push: nothing to do until tomorrow.
        if (context.isMorningDeliveredToday()) {
            return tomorrowAt(fixed ?: WATCH_FROM)
        }
        if (fixed != null) {
            postMorningForecast(context)
            return tomorrowAt(fixed)
        }

        // Watch mode. Without usage access there is no unlock to see, so behave like a fixed time.
        if (!context.hasUsageAccess()) {
            return if (now.toLocalTime() >= DefaultMorningTime) {
                postMorningForecast(context)
                tomorrowAt(WATCH_FROM)
            } else {
                now.toLocalDate().atTime(DefaultMorningTime)
            }
        }
        if (now.toLocalTime() < WATCH_FROM) return now.toLocalDate().atTime(WATCH_FROM)
        if (now.toLocalTime() > WATCH_UNTIL) return tomorrowAt(WATCH_FROM)

        val from = now.toLocalDate().atTime(WATCH_FROM).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val unlocked = context.firstUnlockBetween(from, System.currentTimeMillis()) != null
        if (unlocked) {
            postMorningForecast(context)
            return tomorrowAt(WATCH_FROM)
        }
        // Not up yet — look again shortly, unless that would run past the morning.
        val nextLook = now.plusMinutes(WATCH_STEP_MIN)
        return if (nextLook.toLocalTime() > WATCH_UNTIL) tomorrowAt(WATCH_FROM) else nextLook
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
    val llmText = repo.getMorning()
        .getOrNull()
        ?.takeIf { it.message.isNotBlank() }
        ?.let { NotificationCopy("Прогноз на сегодня", it.message) }
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
                val next = when (kind) {
                    ReminderKind.Morning -> NotificationScheduler.handleMorning(app)
                    ReminderKind.Evening -> {
                        // Already answered today, in the app or from the notification.
                        if (app.todayCheckIn().first() == null) showCheckinNotification(app)
                        NotificationScheduler.nextEvening(app)
                    }
                }
                NotificationScheduler.scheduleAt(app, kind, next)
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
