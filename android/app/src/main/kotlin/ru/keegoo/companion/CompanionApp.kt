package ru.keegoo.companion

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import ru.keegoo.companion.notifications.createNotificationChannels
import ru.keegoo.companion.work.DailyCollectWorker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.keegoo.companion.notifications.NotificationScheduler

@HiltAndroidApp
class CompanionApp : Application(), Configuration.Provider {

    // DailyCollectWorker is a @HiltWorker: WorkManager's default factory can't build it.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.APPMETRICA_KEY.isNotEmpty()) {
            AppMetrica.activate(this, AppMetricaConfig.newConfigBuilder(BuildConfig.APPMETRICA_KEY).build())
        }
        createNotificationChannels(this)
        DailyCollectWorker.schedule(this)
        appScope.launch { NotificationScheduler.ensureScheduled(this@CompanionApp) }
        if (BuildConfig.DEBUG) {
            // Debug: collect once on launch so /data/passive can be checked without waiting 12 h.
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<DailyCollectWorker>().build())
        }
    }
}
