package ru.keegoo.companion.push

import android.content.Context
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.notifications.CheckInEntryPoint
import ru.rustore.sdk.pushclient.RuStorePushClient

private const val TAG = "PushTokenSync"
private const val PREFS = "push_sync"
private const val KEY_SENT = "sent_token"

/**
 * Keeps the server's idea of this phone's push token current.
 *
 * Called whenever Home comes to the foreground (the person is registered by then, and the same
 * moment already pings the server) and when the push client rotates the token. The last token
 * the server accepted is remembered, so a repeat costs nothing: no request unless it changed.
 */
object PushTokenSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sync(context: Context) {
        if (BuildConfig.RUSTORE_PUSH_PROJECT_ID.isEmpty()) return
        val app = context.applicationContext
        runCatching {
            RuStorePushClient.getToken().addOnSuccessListener { token ->
                scope.launch { upload(app, token) }
            }.addOnFailureListener { Log.w(TAG, "no push token: $it") }
        }
    }

    /** The provider gave out a new token: forget the old upload so it is sent again. */
    fun onTokenRotated(context: Context, token: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SENT).apply()
        scope.launch { upload(app, token) }
    }

    private suspend fun upload(app: Context, token: String) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT, null) == token) return
        val repo = EntryPointAccessors.fromApplication(app, CheckInEntryPoint::class.java).repository()
        repo.registerPushToken(token)
            .onSuccess { prefs.edit().putString(KEY_SENT, token).apply() }
            .onFailure { Log.w(TAG, "token not registered, will retry: $it") }
    }
}
