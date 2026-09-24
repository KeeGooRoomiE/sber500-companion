package ru.keegoo.companion.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.appPrefs by preferencesDataStore(name = "companion_prefs")
private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")

suspend fun Context.isOnboarded(): Boolean = appPrefs.data.first()[KEY_ONBOARDED] ?: false

suspend fun Context.setOnboarded() {
    appPrefs.edit { it[KEY_ONBOARDED] = true }
}
