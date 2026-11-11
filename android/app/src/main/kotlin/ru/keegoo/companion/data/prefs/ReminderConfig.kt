package ru.keegoo.companion.data.prefs

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.keegoo.companion.data.repository.CompanionRepository
import java.time.LocalTime
import kotlin.random.Random

/**
 * Knobs for the daily reminders that the server can move without a new build.
 *
 * The values live in SharedPreferences under «remote_config» and are filled from GET /config
 * (plan 1.1.0, item 7) by [sync]. Every reader falls back to the defaults below, so an
 * unreachable or absent config changes nothing.
 */
object ReminderConfig {
    private const val PREFS = "remote_config"
    private const val KEY_MIDDAY_ENABLED = "midday_enabled"
    private const val KEY_MIDDAY_CENTER_MIN = "midday_center_min"
    private const val KEY_MIDDAY_DELTA_MIN = "midday_delta_min"
    private const val KEY_SALT = "install_salt"
    private const val KEY_SILENCE_DAYS = "silence_days"
    private const val KEY_FLAGS = "flags_json"
    private const val KEY_TEXTS = "texts_json"
    private const val KEY_SYNCED_AT = "synced_at"
    private const val SYNC_EVERY_MS = 60 * 60 * 1000L
    const val DEFAULT_SILENCE_DAYS = 3

    private val gson = Gson()

    /** The midday notification at 14:00 ± 1 h, a different moment each day within the window. */
    const val DEFAULT_MIDDAY_CENTER_MIN = 14 * 60
    const val DEFAULT_MIDDAY_DELTA_MIN = 60

    data class Midday(val enabled: Boolean, val centerMin: Int, val deltaMin: Int)

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun midday(context: Context): Midday {
        val p = context.prefs()
        return Midday(
            enabled = p.getBoolean(KEY_MIDDAY_ENABLED, true),
            centerMin = p.getInt(KEY_MIDDAY_CENTER_MIN, DEFAULT_MIDDAY_CENTER_MIN),
            deltaMin = p.getInt(KEY_MIDDAY_DELTA_MIN, DEFAULT_MIDDAY_DELTA_MIN),
        )
    }

    /** Stored once per install: spreads people over the window so nobody fires at the same second. */
    fun salt(context: Context): Int {
        val p = context.prefs()
        if (!p.contains(KEY_SALT)) p.edit().putInt(KEY_SALT, Random.nextInt()).apply()
        return p.getInt(KEY_SALT, 0)
    }

    /** Days without any activity mark after which a person counts as silent. */
    fun silenceDays(context: Context): Int = context.prefs().getInt(KEY_SILENCE_DAYS, DEFAULT_SILENCE_DAYS)

    /** A server-side switch for a mechanic that already exists in this build; [default] if unset. */
    fun flag(context: Context, name: String, default: Boolean): Boolean =
        readMap<Boolean>(context, KEY_FLAGS, object : TypeToken<Map<String, Boolean>>() {})[name] ?: default

    /** Fallback texts the server wants shown for [key]; empty means «use the built-in ones». */
    fun texts(context: Context, key: String): List<String> =
        readMap<List<String>>(context, KEY_TEXTS, object : TypeToken<Map<String, List<String>>>() {})[key]
            .orEmpty().filter { it.isNotBlank() }

    private fun <T> readMap(context: Context, key: String, type: TypeToken<Map<String, T>>): Map<String, T> =
        runCatching { gson.fromJson<Map<String, T>>(context.prefs().getString(key, null), type.type) }
            .getOrNull().orEmpty()

    /**
     * Pulls the server config, at most once an hour, and stores what it says. A missing key leaves
     * the stored value alone; a failed request leaves everything as it was. The next midday alarm
     * re-arm picks the new window up, no restart needed.
     */
    suspend fun sync(context: Context, repo: CompanionRepository, force: Boolean = false) {
        val p = context.prefs()
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(KEY_SYNCED_AT, 0) < SYNC_EVERY_MS) return
        val c = repo.remoteConfig().getOrNull() ?: return
        p.edit().apply {
            c.midday?.enabled?.let { putBoolean(KEY_MIDDAY_ENABLED, it) }
            c.midday?.centerMin?.takeIf { it in 0 until 24 * 60 }?.let { putInt(KEY_MIDDAY_CENTER_MIN, it) }
            c.midday?.deltaMin?.takeIf { it in 0..6 * 60 }?.let { putInt(KEY_MIDDAY_DELTA_MIN, it) }
            c.silence?.days?.takeIf { it in 1..60 }?.let { putInt(KEY_SILENCE_DAYS, it) }
            c.flags?.let { putString(KEY_FLAGS, gson.toJson(it)) }
            c.texts?.let { putString(KEY_TEXTS, gson.toJson(it)) }
            putLong(KEY_SYNCED_AT, now)
        }.apply()
    }
}
