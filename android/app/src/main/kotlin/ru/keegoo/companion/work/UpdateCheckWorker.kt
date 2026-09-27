package ru.keegoo.companion.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.prefs.markUpdateNotified
import ru.keegoo.companion.data.prefs.wasUpdateNotified
import ru.keegoo.companion.notifications.CheckInEntryPoint
import ru.keegoo.companion.notifications.showUpdateNotification
import ru.keegoo.companion.ui.update.isNewerVersion
import java.util.concurrent.TimeUnit

/**
 * Asks the server twice a day whether a newer APK exists and, if so, says so once.
 *
 * The app is handed out as an APK link rather than through a store, so nothing updates it in
 * the background and nothing tells people a fix shipped. The in-app toast only appears while
 * the app is open, which is too late for someone whose build is broken enough that they
 * stopped opening it.
 *
 * Twice a day, not hourly: a release is a rare event and this competes for the same throttled
 * background budget as the forecast, which matters more.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = EntryPointAccessors
            .fromApplication(ctx, CheckInEntryPoint::class.java)
            .repository()

        val version = repo.latestVersion().getOrNull() ?: return Result.retry()
        if (!isNewerVersion(version.latest, BuildConfig.VERSION_NAME)) return Result.success()
        if (ctx.wasUpdateNotified(version.latest)) return Result.success()

        showUpdateNotification(
            ctx,
            title = "Меня можно обновить",
            body = "Вышла версия ${version.latest}. Нажми, чтобы скачать.",
            url = version.downloadUrl,
        )
        ctx.markUpdateNotified(version.latest)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
