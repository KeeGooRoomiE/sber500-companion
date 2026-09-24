package ru.keegoo.companion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ru.keegoo.companion.notifications.createNotificationChannels
import ru.keegoo.companion.work.DailyCollectWorker

@HiltAndroidApp
class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        DailyCollectWorker.schedule(this)
    }
}
