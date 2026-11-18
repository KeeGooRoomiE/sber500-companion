package ru.keegoo.companion.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.keegoo.companion.notifications.CHANNEL_MORNING
import ru.rustore.sdk.pushclient.messaging.model.RemoteMessage
import ru.rustore.sdk.pushclient.messaging.service.RuStoreMessagingService

private const val TAG = "RuStorePush"

private const val PREFS = "push_debug"
private const val KEY_LOG = "log"

/** Remembers the last few push events so the debug dialog can show whether the service ran. */
internal fun Context.pushLogAdd(line: String) {
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val stamp = java.time.LocalTime.now().withNano(0)
    val lines = (listOf("$stamp $line") + (prefs.getString(KEY_LOG, "")!!.split("\n").filter { it.isNotBlank() })).take(6)
    prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
}

internal fun Context.pushLogRead(): String =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "").orEmpty()

/** Push-test: only proves delivery. Shows whatever arrives so the owner sees it on the phone. */
class MyRuStoreMessagingService : RuStoreMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "new token: $token")
        pushLogAdd("новый токен")
        PushTokenSync.onTokenRotated(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "message: data=${message.data} notification=${message.notification}")
        pushLogAdd("получено: data=${message.data} notification=${message.notification != null}")
        // «notification» messages are drawn by the SDK itself; posting them here too showed every
        // push twice. Only data-only messages need us to build the notification.
        if (message.notification != null) return
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
        pushLogAdd("ошибка: $errors")
    }
}
