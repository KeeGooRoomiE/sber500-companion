package ru.keegoo.companion.data.prefs

import android.content.Context
import java.time.LocalTime
import kotlin.random.Random

/**
 * Knobs for the daily reminders that the server can move without a new build.
 *
 * The values live in SharedPreferences under «remote_config» and are filled by the server config
 * once that exists (plan 1.1.0, item 7). Until then every reader falls back to the defaults
 * below, so an unreachable or absent config changes nothing.
 */
object ReminderConfig {
    private const val PREFS = "remote_config"
    private const val KEY_MIDDAY_ENABLED = "midday_enabled"
    private const val KEY_MIDDAY_CENTER_MIN = "midday_center_min"
    private const val KEY_MIDDAY_DELTA_MIN = "midday_delta_min"
    private const val KEY_SALT = "install_salt"

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
}
