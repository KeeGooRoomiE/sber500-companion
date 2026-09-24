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

        var screenOnMs = 0L
        var unlocks = 0
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
                    if (event.timeStamp < firstUnlockMs) firstUnlockMs = event.timeStamp
                    if (event.timeStamp > lastUnlockMs) lastUnlockMs = event.timeStamp
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenOnMs -= event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnMs < 0) screenOnMs += event.timeStamp
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
        if (screenOnMs < 0) screenOnMs += dayEndMs

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
        )
    }

    fun hasPermission(): Boolean = context.hasUsageAccess()
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
