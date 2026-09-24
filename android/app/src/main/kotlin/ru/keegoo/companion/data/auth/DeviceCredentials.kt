package ru.keegoo.companion.data.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.keegoo.companion.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private data class RegisterResponse(val user_id: String, val token: String)

/**
 * Server-issued identity: POST /api/v1/register returns a random user id and a secret token.
 * The token lives in app-private storage and goes out as "Authorization: Bearer …".
 * Nothing is derived from device ids anymore.
 */
@Singleton
class DeviceCredentials @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("device_auth", Context.MODE_PRIVATE)

    // Registration has its own client: no auth interceptor, short timeouts.
    private val bare = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val userId: String? get() = prefs.getString(KEY_USER, null)

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

    private fun register(): String? = try {
        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL + "api/v1/register")
            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
            .build()
        bare.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "register failed: ${resp.code}")
                return null
            }
            val body = Gson().fromJson(resp.body?.charStream(), RegisterResponse::class.java)
            prefs.edit().putString(KEY_USER, body.user_id).putString(KEY_TOKEN, body.token).apply()
            body.token
        }
    } catch (e: Exception) {
        Log.w(TAG, "register error", e)
        null
    }

    companion object {
        private const val TAG = "DeviceCredentials"
        private const val KEY_USER = "user_id"
        private const val KEY_TOKEN = "token"
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
