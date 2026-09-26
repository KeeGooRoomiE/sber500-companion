package ru.keegoo.companion.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
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
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

enum class ReminderKind { Morning, Evening }

/**
 * Daily local notifications: morning forecast and evening check-in.
 *
 * Morning, by default: «Когда возьму телефон» — the forecast arrives right after the first unlock
 * of the morning (a person's idea: the app sees sleep and unlocks anyway). A light worker checks
 * every 15 minutes whether the phone has been unlocked since 05:00; under Doze the check runs as
 * soon as the phone wakes up, so in practice it's the first minutes after picking the phone up.
 * A fixed time (07:40 etc.) is still an option, and the fallback without usage access.
 * WorkManager survives reboots.
 */
object NotificationScheduler {

    private fun workName(kind: ReminderKind) = "reminder_${kind.name.lowercase()}"
    private const val WAKE_WATCH = "morning_wake_watch"

    /** Called on app start: keeps existing schedules. */
    suspend fun ensureScheduled(context: Context) {
        val answers = context.profileAnswersNow()
        applyMorning(context, answers[ProfileIds.MORNING_TIME], ExistingPeriodicWorkPolicy.KEEP)
        schedule(context, ReminderKind.Evening, parseTime(answers[ProfileIds.EVENING_TIME]) ?: DefaultEveningTime, ExistingPeriodicWorkPolicy.KEEP)
    }

    /** Morning mode from the profile answer: at the first unlock (default) or at a fixed time. */
    fun applyMorning(context: Context, answer: String?, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE) {
        val wm = WorkManager.getInstance(context)
        val fixed = parseTime(answer)
        if (fixed != null) {
            wm.cancelUniqueWork(WAKE_WATCH)
            schedule(context, ReminderKind.Morning, fixed, policy)
        } else {
            wm.cancelUniqueWork(workName(ReminderKind.Morning))
            val watch = PeriodicWorkRequestBuilder<WakeWatchWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WAKE_WATCH, ExistingPeriodicWorkPolicy.KEEP, watch)
        }
    }

    /** Called when the person picks a new time in the profile. */
    fun reschedule(context: Context, kind: ReminderKind, time: LocalTime) {
        schedule(context, kind, time, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    private fun schedule(context: Context, kind: ReminderKind, time: LocalTime, policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(time).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_KIND to kind.name))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(workName(kind), policy, request)
    }

    private fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}

/** The morning forecast as a notification: the server text if ready, a local copy otherwise. */
suspend fun postMorningForecast(context: Context) {
    val repo = EntryPointAccessors.fromApplication(context, CheckInEntryPoint::class.java).repository()
    val llmText = repo.getMorning()
        .getOrNull()
        ?.takeIf { it.message.isNotBlank() }
        ?.let { NotificationCopy("Прогноз на сегодня", it.message) }
    showMorningNotification(context, llmText ?: MorningCopies.forToday())
    context.markMorningDelivered()
}

/**
 * «Когда возьму телефон»: posts the forecast once a day, after the first unlock since 05:00.
 * Skips the day if the person already saw the forecast in the app. Without usage access there
 * is no unlock to see — then it behaves like the default fixed time (07:40).
 */
class WakeWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ctx.isMorningDeliveredToday()) return Result.success()
        val now = LocalDateTime.now()
        if (now.toLocalTime() < WATCH_FROM || now.toLocalTime() > WATCH_UNTIL) return Result.success()

        val due = if (ctx.hasUsageAccess()) {
            val from = now.toLocalDate().atTime(WATCH_FROM).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ctx.firstUnlockBetween(from, System.currentTimeMillis()) != null
        } else {
            now.toLocalTime() >= DefaultMorningTime
        }
        if (due) postMorningForecast(ctx)
        return Result.success()
    }

    companion object {
        /** Earlier unlocks are night-time, not the start of the day. */
        val WATCH_FROM: LocalTime = LocalTime.of(5, 0)
        /** No unlock by 13:00 — the morning has passed, a forecast now would be odd. */
        val WATCH_UNTIL: LocalTime = LocalTime.of(13, 0)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        when (inputData.getString(KEY_KIND)?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }) {
            ReminderKind.Morning -> postMorningForecast(applicationContext)
            // Already answered today (in the app or earlier) — don't ask again
            ReminderKind.Evening -> if (applicationContext.todayCheckIn().first() == null) {
                showCheckinNotification(applicationContext)
            }
            null -> Unit
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
    }
}
