package ru.keegoo.companion

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import ru.keegoo.companion.notifications.createNotificationChannels
import ru.keegoo.companion.work.DailyCollectWorker
import javax.inject.Inject

@HiltAndroidApp
class CompanionApp : Application(), Configuration.Provider {

    // DailyCollectWorker is a @HiltWorker: WorkManager's default factory can't build it.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        DailyCollectWorker.schedule(this)
        if (BuildConfig.DEBUG) {
            // Debug: collect once on launch so /data/passive can be checked without waiting 12 h.
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<DailyCollectWorker>().build())
        }
    }
}
