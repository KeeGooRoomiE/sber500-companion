package ru.keegoo.companion.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.prefs.markMorningDelivered

private const val TAG = "Push"

/**
 * Server-sent notifications.
 *
 * The local WorkManager reminders are the nice path — they fire at the first unlock, which the
 * server cannot see. They are also the unreliable path: App Standby buckets and OEM battery
 * managers can stop them entirely (measured on a real device: no background work at all in
 * 16 hours). Push is the backstop, so the morning forecast arrives even on a phone that never
 * lets the app run on its own.
 *
 * Without google-services.json BuildConfig.PUSH_ENABLED is false and every entry point here is
 * a no-op — the app then behaves exactly as it did before push existed.
 */
object Push {

    /**
     * Hands the current FCM token to the server. Safe to call on every app start: tokens rotate,
     * and the daily-snapshot call that used to carry the token needs background work, which is
     * the very thing that may never run.
     */
    fun syncToken(context: Context) {
        if (!BuildConfig.PUSH_ENABLED) return
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (!token.isNullOrBlank()) upload(context, token) }
                .addOnFailureListener { e -> Log.w(TAG, "token fetch failed: ${e.message}") }
        } catch (e: Exception) {
            // No Play Services, no Firebase config — push stays off, the app works on.
            Log.w(TAG, "token sync skipped: ${e.message}")
        }
    }

    internal fun upload(context: Context, token: String) {
        val repo = EntryPointAccessors
            .fromApplication(context.applicationContext, CheckInEntryPoint::class.java)
            .repository()
        CoroutineScope(Dispatchers.IO).launch { repo.setPushToken(token) }
    }
}

/**
 * Receives pushes. Messages carry a notification block too, so Android shows them from the
 * system tray even when this process is dead — that is the point of push here. This service
 * runs only when the app is alive, and then it decides what to do with the payload itself.
 */
class CompanionMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Push.upload(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body ?: return

        when (data["type"]) {
            // The forecast: reuse the morning channel and mark the day delivered, so the local
            // worker does not post a second copy later.
            "morning" -> {
                showMorningNotification(this, NotificationCopy(title, body))
                CoroutineScope(Dispatchers.IO).launch { markMorningDelivered() }
            }
            "update" -> showUpdateNotification(this, title, body)
            else -> showMorningNotification(this, NotificationCopy(title, body))
        }
    }
}
