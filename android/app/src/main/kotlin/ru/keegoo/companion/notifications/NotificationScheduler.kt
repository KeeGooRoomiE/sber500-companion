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
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.todayCheckIn
import ru.keegoo.companion.domain.profile.DefaultEveningTime
import ru.keegoo.companion.domain.profile.DefaultMorningTime
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.parseTime
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

enum class ReminderKind { Morning, Evening }

/**
 * Daily local notifications: morning forecast and evening check-in.
 * Times come from the profile questions (defaults 07:40 / 20:30).
 * WorkManager survives reboots; delivery is inexact by a few minutes under Doze, which is fine here.
 */
object NotificationScheduler {

    private fun workName(kind: ReminderKind) = "reminder_${kind.name.lowercase()}"

    /** Called on app start: keeps existing schedules. */
    suspend fun ensureScheduled(context: Context) {
        val answers = context.profileAnswersNow()
        schedule(context, ReminderKind.Morning, parseTime(answers[ProfileIds.MORNING_TIME]) ?: DefaultMorningTime, ExistingPeriodicWorkPolicy.KEEP)
        schedule(context, ReminderKind.Evening, parseTime(answers[ProfileIds.EVENING_TIME]) ?: DefaultEveningTime, ExistingPeriodicWorkPolicy.KEEP)
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

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        when (inputData.getString(KEY_KIND)?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }) {
            ReminderKind.Morning -> {
                val repo = EntryPointAccessors
                    .fromApplication(applicationContext, CheckInEntryPoint::class.java)
                    .repository()
                val llmText = repo.getMorning()
                    .getOrNull()
                    ?.takeIf { it.message.isNotBlank() }
                    ?.let { NotificationCopy("Прогноз на сегодня", it.message) }
                showMorningNotification(applicationContext, llmText ?: MorningCopies.forToday())
            }
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
