package ru.keegoo.companion.data.prefs

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * «Был ли человек сегодня на связи» — the on-device marker that the midday push and the
 * «come back» weekly summary decide on.
 *
 * Any of three things leaves a mark: the app was opened, a notification was swiped away, or a
 * button in a notification was pressed. A tap on the notification itself opens the app, so it is
 * covered by the first. Showing a notification is *not* activity — it is what we did, not what
 * the person did.
 *
 * Plain SharedPreferences, not DataStore: receivers write this synchronously, and the value is
 * two numbers that never need a flow.
 */
object ActivityMarker {
    private const val PREFS = "activity_marker"
    private const val KEY_LAST_MS = "last_ms"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_OPEN_MS = "last_open_ms"

    /** A new «session» of opening the app: a return after this long counts as a fresh open. */
    private const val SESSION_GAP_MS = 30 * 60 * 1000L

    enum class Source(val slug: String) { Open("open"), Dismiss("dismiss"), Action("action") }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(context: Context, source: Source, now: Long = System.currentTimeMillis()) {
        context.prefs().edit().putLong(KEY_LAST_MS, now).putString(KEY_LAST_SOURCE, source.slug).apply()
    }

    /**
     * Marks an app open and says whether it starts a new session (not one more return to a
     * screen the person never left). Analytics counts sessions, the marker counts every open.
     */
    fun markOpen(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.prefs()
        val newSession = now - prefs.getLong(KEY_OPEN_MS, 0L) > SESSION_GAP_MS
        prefs.edit().putLong(KEY_OPEN_MS, now).apply()
        mark(context, Source.Open, now)
        return newSession
    }

    /** Epoch millis of the last sign of life, or null if there has never been one. */
    fun lastActivityMs(context: Context): Long? =
        context.prefs().getLong(KEY_LAST_MS, 0L).takeIf { it > 0 }

    /** The person did something on [date] (local time). */
    fun wasActiveOn(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val last = lastActivityMs(context) ?: return false
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return last >= start
    }

    fun wasActiveToday(context: Context): Boolean = wasActiveOn(context, LocalDate.now())

    /** Whole days since the last sign of life; null if never. */
    fun silentDays(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val last = lastActivityMs(context) ?: return null
        val lastDay = java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(lastDay, today)
    }
}
