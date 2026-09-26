package ru.keegoo.companion.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.keegoo.companion.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private data class RegisterRequest(@SerializedName("device_key") val device_key: String?)
private data class RegisterResponse(
    @SerializedName("user_id") val user_id: String,
    @SerializedName("token") val token: String,
    @SerializedName("returning") val returning: Boolean = false,
)

/**
 * Server-issued identity: POST /api/v1/register returns a random user id and a secret token.
 * The token lives in app-private storage and goes out as "Authorization: Bearer …".
 *
 * Reinstall: registration also sends a hash of ANDROID_ID. On Android 8+ it is unique per
 * device + app signing key and survives uninstalling, so a reinstalled app gets its old identity
 * back (history, forecasts, answers) instead of starting from zero. The raw id never leaves the phone.
 */
@Singleton
class DeviceCredentials @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("device_auth", Context.MODE_PRIVATE)

    // Registration has its own client: no auth interceptor, short timeouts.
    private val bare = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val userId: String? get() = prefs.getString(KEY_USER, null)

    /** The server recognised this phone after a reinstall and the answers are not restored yet. */
    val restorePending: Boolean get() = prefs.getBoolean(KEY_RESTORE, false)

    fun restoreDone() = prefs.edit().putBoolean(KEY_RESTORE, false).apply()

    /** Current token, registering first if there is none. Null if the server is unreachable. */
    @Synchronized
    fun token(): String? = prefs.getString(KEY_TOKEN, null) ?: register()

    /** Drop a token the server rejected (401), unless another thread already replaced it. */
    @Synchronized
    fun invalidate(rejected: String) {
        if (prefs.getString(KEY_TOKEN, null) == rejected) {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
        }
    }

    // After a failed registration, wait before trying again: many requests at once must not turn
    // one broken answer into dozens of new users on the server.
    @Volatile private var lastFailure = 0L

    private fun register(): String? {
        if (System.currentTimeMillis() - lastFailure < RETRY_AFTER_MS) return null
        return doRegister().also { if (it == null) lastFailure = System.currentTimeMillis() }
    }

    private fun doRegister(): String? = try {
        val body = Gson().toJson(RegisterRequest(deviceKey()))
        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL + "api/v1/register")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        bare.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "register failed: ${resp.code}")
                return null
            }
            val reg = Gson().fromJson(resp.body?.charStream(), RegisterResponse::class.java)
            // Gson ignores Kotlin nullability: an answer it could not map has null fields
            @Suppress("SENSELESS_COMPARISON")
            if (reg == null || reg.token.isNullOrBlank() || reg.user_id.isNullOrBlank()) {
                Log.w(TAG, "register: unreadable answer")
                return null
            }
            prefs.edit()
                .putString(KEY_USER, reg.user_id)
                .putString(KEY_TOKEN, reg.token)
                // Only a fresh install needs restoring; a 401 re-register keeps local answers
                .putBoolean(KEY_RESTORE, reg.returning && !prefs.getBoolean(KEY_HAD_TOKEN, false))
                .putBoolean(KEY_HAD_TOKEN, true)
                .apply()
            reg.token
        }
    } catch (e: Exception) {
        Log.w(TAG, "register error", e)
        null
    }

    // Hash of ANDROID_ID with an app-specific prefix; null on the rare device that has none.
    @SuppressLint("HardwareIds")
    private fun deviceKey(): String? {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // old emulator constant
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest("companion:$id".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DeviceCredentials"
        private const val KEY_USER = "user_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_RESTORE = "restore_pending"
        private const val KEY_HAD_TOKEN = "had_token"
        private const val RETRY_AFTER_MS = 30_000L
    }
}

/** Adds the bearer token; on 401 re-registers once and retries the request. */
class AuthInterceptor(private val credentials: DeviceCredentials) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = credentials.token() ?: return chain.proceed(original)
        val response = chain.proceed(original.withToken(token))
        if (response.code != 401) return response

        response.close()
        credentials.invalidate(token)
        val fresh = credentials.token() ?: return chain.proceed(original)
        return chain.proceed(original.withToken(fresh))
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()
}
