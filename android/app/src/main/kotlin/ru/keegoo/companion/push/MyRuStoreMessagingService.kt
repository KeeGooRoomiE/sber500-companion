package ru.keegoo.companion.push

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.keegoo.companion.notifications.CHANNEL_MORNING
import ru.rustore.sdk.pushclient.messaging.model.RemoteMessage
import ru.rustore.sdk.pushclient.messaging.service.RuStoreMessagingService

private const val TAG = "RuStorePush"

/** Push-test: only proves delivery. Shows whatever arrives so the owner sees it on the phone. */
class MyRuStoreMessagingService : RuStoreMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "new token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "message: data=${message.data} notification=${message.notification}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val title = message.notification?.title ?: message.data["title"] ?: "Push"
        val body = message.notification?.body ?: message.data["body"] ?: message.data.toString()
        val n = NotificationCompat.Builder(this, CHANNEL_MORNING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
    }

    override fun onError(errors: List<ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException>) {
        Log.w(TAG, "push error: $errors")
    }
}
