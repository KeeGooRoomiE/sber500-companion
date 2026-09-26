package ru.keegoo.companion.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.keegoo.companion.domain.model.DayFeel
import java.time.LocalDate

private val Context.appPrefs by preferencesDataStore(name = "companion_prefs")
private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
// History (last 7 days) was sent once with usage access — day-0 forecast has something to work with
private val KEY_BACKFILLED = booleanPreferencesKey("history_backfilled")

// Today's check-in, kept locally so Home knows it's done (also when answered from the notification)
private val KEY_CHECKIN_DATE = stringPreferencesKey("checkin_date")
private val KEY_CHECKIN_FEEL = stringPreferencesKey("checkin_feel")
private val KEY_CHECKIN_TAGS = stringSetPreferencesKey("checkin_tags")

private const val PROFILE_PREFIX = "profile_"

suspend fun Context.isOnboarded(): Boolean = appPrefs.data.first()[KEY_ONBOARDED] ?: false

suspend fun Context.isBackfilled(): Boolean = appPrefs.data.first()[KEY_BACKFILLED] ?: false

suspend fun Context.setBackfilled() {
    appPrefs.edit { it[KEY_BACKFILLED] = true }
}

suspend fun Context.setOnboarded() {
    appPrefs.edit { it[KEY_ONBOARDED] = true }
}

suspend fun Context.resetOnboarded() {
    appPrefs.edit { it[KEY_ONBOARDED] = false }
}

data class LocalCheckIn(val feel: DayFeel, val tags: Set<String>)

/** Today's check-in or null. Yesterday's answer doesn't count. */
fun Context.todayCheckIn(): Flow<LocalCheckIn?> = appPrefs.data.map { p ->
    if (p[KEY_CHECKIN_DATE] != LocalDate.now().toString()) return@map null
    val feel = p[KEY_CHECKIN_FEEL]?.let { runCatching { DayFeel.valueOf(it) }.getOrNull() } ?: return@map null
    LocalCheckIn(feel, p[KEY_CHECKIN_TAGS].orEmpty())
}

suspend fun Context.saveCheckIn(feel: DayFeel, tags: Set<String>) {
    appPrefs.edit {
        it[KEY_CHECKIN_DATE] = LocalDate.now().toString()
        it[KEY_CHECKIN_FEEL] = feel.name
        it[KEY_CHECKIN_TAGS] = tags
    }
}

suspend fun Context.clearCheckIn() {
    appPrefs.edit {
        it.remove(KEY_CHECKIN_DATE)
        it.remove(KEY_CHECKIN_FEEL)
        it.remove(KEY_CHECKIN_TAGS)
    }
}

/** Answers from «Расскажи о себе», keyed by question id. */
fun Context.profileAnswers(): Flow<Map<String, String>> = appPrefs.data.map { p -> p.profileMap() }

suspend fun Context.profileAnswersNow(): Map<String, String> = appPrefs.data.first().profileMap()

suspend fun Context.saveProfileAnswer(questionId: String, answer: String) {
    appPrefs.edit { it[stringPreferencesKey(PROFILE_PREFIX + questionId)] = answer }
}

private fun Preferences.profileMap(): Map<String, String> =
    asMap().entries
        .filter { it.key.name.startsWith(PROFILE_PREFIX) }
        .associate { it.key.name.removePrefix(PROFILE_PREFIX) to it.value.toString() }
