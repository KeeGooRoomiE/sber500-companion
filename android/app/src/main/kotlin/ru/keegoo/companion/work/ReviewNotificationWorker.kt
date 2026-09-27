package ru.keegoo.companion.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import ru.keegoo.companion.data.prefs.markReviewNotified
import ru.keegoo.companion.data.prefs.wasReviewNotifiedToday
import ru.keegoo.companion.notifications.CheckInEntryPoint
import ru.keegoo.companion.notifications.showReviewNotification
import java.time.LocalDate

/**
 * The «разбор дня» push, fired right after a check-in. Custdev's strongest signal: people want
 * «вот почему вчера был тяжёлый день», and the moment they've just rated the day is when that
 * question is already in their head.
 *
 * Server-side generation + FCM would be the robust path, but push is disabled (no
 * google-services.json), so this runs on the phone: it makes sure today's snapshot is on the
 * server, then asks for the review and shows it. Same reliability class as the morning local
 * reminder — good enough while FCM is off.
 */
class ReviewNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ctx.wasReviewNotifiedToday()) return Result.success()

        // The review is about today's data — without today's snapshot on the server it 404s. This
        // matters when the check-in came from the notification and the app was never opened.
        DailyCollectWorker.runNowAndWait(ctx, pastDays = 1, timeoutMs = 12_000)

        val repo = EntryPointAccessors
            .fromApplication(ctx, CheckInEntryPoint::class.java)
            .repository()

        val text = repo.dayReview(LocalDate.now()).getOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) {
            // Transient (no data yet, LLM hiccup): a couple of retries, then give up quietly —
            // a missed review notification is not worth hammering the server.
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }

        showReviewNotification(ctx, text)
        ctx.markReviewNotified()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "review_notif"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReviewNotificationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
