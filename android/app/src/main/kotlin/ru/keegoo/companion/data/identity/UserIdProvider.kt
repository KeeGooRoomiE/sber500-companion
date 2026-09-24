package ru.keegoo.companion.data.identity

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // SHA-256(ANDROID_ID + static salt).
    // Salt keeps user IDs project-scoped and prevents cross-service correlation.
    private val salt = "sber500-companion-v1"

    val userId: String by lazy {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        sha256("$raw$salt")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
