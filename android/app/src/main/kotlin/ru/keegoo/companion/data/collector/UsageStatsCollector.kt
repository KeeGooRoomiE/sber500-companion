package ru.keegoo.companion.data.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.keegoo.companion.domain.model.AppUsage
import ru.keegoo.companion.domain.model.UsageSnapshot
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Returns null without usage access: an empty event list is "no data", not "0 minutes". */
    fun collect(dayStartMs: Long, dayEndMs: Long): UsageSnapshot? {
        if (!hasPermission()) return null
        val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events = mgr.queryEvents(dayStartMs, dayEndMs) ?: return null
        val zone = ZoneId.systemDefault()

        var unlocks = 0
        // Hour-by-hour picture of the day: the server reads "busy morning", "work hours" from it
        val unlocksByHour = IntArray(24)
        val screenMsByHour = LongArray(24)
        var screenOnSince: Long? = null

        // Split a screen-on interval at hour boundaries (local time)
        fun addScreen(from: Long, to: Long) {
            var cur = from
            while (cur < to) {
                val local = Instant.ofEpochMilli(cur).atZone(zone)
                val nextHour = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val chunkEnd = minOf(to, nextHour)
                screenMsByHour[local.hour] += chunkEnd - cur
                cur = chunkEnd
            }
        }
        var firstUnlockMs = Long.MAX_VALUE
        var lastUnlockMs = Long.MIN_VALUE
        val appForegroundStart = mutableMapOf<String, Long>()
        val appTotalMs = mutableMapOf<String, Long>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    unlocks++
                    unlocksByHour[Instant.ofEpochMilli(event.timeStamp).atZone(zone).hour]++
                    if (event.timeStamp < firstUnlockMs) firstUnlockMs = event.timeStamp
                    if (event.timeStamp > lastUnlockMs) lastUnlockMs = event.timeStamp
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (screenOnSince == null) screenOnSince = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOnSince?.let { addScreen(it, event.timeStamp) }
                    screenOnSince = null
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    appForegroundStart[event.packageName] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val start = appForegroundStart.remove(event.packageName) ?: continue
                    appTotalMs[event.packageName] =
                        (appTotalMs[event.packageName] ?: 0L) + (event.timeStamp - start)
                }
            }
        }
        screenOnSince?.let { addScreen(it, dayEndMs) }
        val screenOnMs = screenMsByHour.sum()

        val topApps = appTotalMs.entries
            .filter { it.value >= 60_000 }
            .sortedByDescending { it.value }
            .take(10)
            .map { AppUsage(it.key, (it.value / 60_000).toInt()) }

        fun Long.toLocalTime(): LocalTime? = if (this == Long.MAX_VALUE || this == Long.MIN_VALUE) null
        else Instant.ofEpochMilli(this).atZone(zone).toLocalTime()

        return UsageSnapshot(
            screenMinutes = (screenOnMs / 60_000).toInt().coerceAtLeast(0),
            unlocks = unlocks,
            firstUnlock = firstUnlockMs.toLocalTime(),
            lastUnlock = lastUnlockMs.toLocalTime(),
            topApps = topApps,
            unlocksByHour = unlocksByHour.toList(),
            screenMinutesByHour = screenMsByHour.map { (it / 60_000).toInt() },
        )
    }

    fun hasPermission(): Boolean = context.hasUsageAccess()

    /**
     * Longest stretch with the screen off inside [fromMs, toMs] — a proxy for sleep when
     * Health Connect has nothing. Returns start/end epoch millis, or null without data.
     */
    fun longestScreenOff(fromMs: Long, toMs: Long): Pair<Long, Long>? {
        if (!hasPermission()) return null
        val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = mgr.queryEvents(fromMs, toMs) ?: return null
        val event = UsageEvents.Event()
        var offSince: Long? = null
        var best: Pair<Long, Long>? = null
        var sawAny = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { offSince = event.timeStamp; sawAny = true }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    sawAny = true
                    val start = offSince
                    if (start != null && (best == null || event.timeStamp - start > best.second - best.first)) {
                        best = start to event.timeStamp
                    }
                    offSince = null
                }
            }
        }
        return if (sawAny) best else null
    }
}

/** Usage access («Доступ к истории использования») is a special app-op, not a runtime permission. */
fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    return if (mode == AppOpsManager.MODE_DEFAULT) {
        checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else {
        mode == AppOpsManager.MODE_ALLOWED
    }
}

/** Time of the first unlock (keyguard dismissed) in [fromMs, toMs), or null. Needs usage access. */
fun Context.firstUnlockBetween(fromMs: Long, toMs: Long): Long? {
    val mgr = getSystemService(UsageStatsManager::class.java) ?: return null
    val events = mgr.queryEvents(fromMs, toMs) ?: return null
    val e = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) return e.timeStamp
    }
    return null
}
