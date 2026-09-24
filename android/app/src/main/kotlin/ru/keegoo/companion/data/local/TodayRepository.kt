package ru.keegoo.companion.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.keegoo.companion.data.collector.HealthConnectCollector
import ru.keegoo.companion.data.collector.UsageStatsCollector
import ru.keegoo.companion.data.collector.appLabel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class SleepSource { HealthConnect, PhoneFree }

data class TopApp(val label: String, val minutes: Int, val usualMinutes: Int?)

/**
 * What Home shows today, read on the device (no backend, no LLM).
 * "Usual" values are averages over the previous days *up to the same clock time*,
 * so at 11:00 today is compared with 11:00 of other days, not with whole days.
 */
data class TodayData(
    val hasUsageAccess: Boolean,
    val screenMin: Int?,
    val unlocks: Int?,
    val usualScreenSoFar: Int?,
    val usualUnlocksSoFar: Int?,
    val topApp: TopApp?,
    val sleepMin: Int?,
    val sleepSource: SleepSource?,
    val usualSleep: Int?,
    /** 7 values, oldest first; the last one is today (so far) / last night. Null = no data that day. */
    val weekScreen: List<Int?>,
    val weekUnlocks: List<Int?>,
    val weekSleep: List<Int?>,
)

private const val PAST_DAYS = 6

@Singleton
class TodayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usage: UsageStatsCollector,
    private val health: HealthConnectCollector,
) {
    suspend fun load(): TodayData = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val sinceMidnight = Duration.between(today.atStartOfDay(), now)
        fun LocalDateTime.ms() = atZone(zone).toInstant().toEpochMilli()

        val hasAccess = usage.hasPermission()

        // Today so far
        val todayUsage = if (hasAccess) usage.collect(today.atStartOfDay().ms(), now.ms()) else null

        // Previous days: full day (for the week chart) and up to the same clock time (for "usual")
        val pastDays = (PAST_DAYS downTo 1).map { today.minusDays(it.toLong()) }
        val pastFull = pastDays.map { d ->
            if (hasAccess) usage.collect(d.atStartOfDay().ms(), d.plusDays(1).atStartOfDay().ms())?.takeIf { it.hasData() } else null
        }
        val pastSoFar = pastDays.map { d ->
            if (hasAccess) usage.collect(d.atStartOfDay().ms(), d.atStartOfDay().plus(sinceMidnight).ms())?.takeIf { it.hasData() } else null
        }

        val usualScreen = pastSoFar.mapNotNull { it?.screenMinutes }.averageOrNull()
        val usualUnlocks = pastSoFar.mapNotNull { it?.unlocks }.averageOrNull()

        val top = todayUsage?.topApps?.firstOrNull()?.let { app ->
            val usual = pastSoFar.mapNotNull { day -> day?.topApps?.firstOrNull { it.packageName == app.packageName }?.minutes ?: day?.let { 0 } }
                .averageOrNull()
            TopApp(context.appLabel(app.packageName), app.minutes, usual)
        }

        // Nights: last night is the night ending this morning
        val nights = (PAST_DAYS downTo 0).map { back -> today.minusDays(back.toLong()) }
        val sleepByNight = nights.map { day -> nightSleep(day, zone, hasAccess) }
        val lastNight = sleepByNight.last()
        // Compare like with like: only nights from the same source
        val usualSleep = sleepByNight.dropLast(1)
            .filter { it != null && it.second == lastNight?.second }
            .mapNotNull { it?.first }
            .averageOrNull()

        TodayData(
            hasUsageAccess = hasAccess,
            screenMin = todayUsage?.screenMinutes,
            unlocks = todayUsage?.unlocks,
            usualScreenSoFar = usualScreen,
            usualUnlocksSoFar = usualUnlocks,
            topApp = top,
            sleepMin = lastNight?.first,
            sleepSource = lastNight?.second,
            usualSleep = usualSleep,
            weekScreen = pastFull.map { it?.screenMinutes } + todayUsage?.screenMinutes,
            weekUnlocks = pastFull.map { it?.unlocks } + todayUsage?.unlocks,
            weekSleep = sleepByNight.map { it?.first },
        )
    }

    /** Sleep for the night that ends on [morningOf]: Health Connect first, else the longest screen-off stretch. */
    private suspend fun nightSleep(morningOf: LocalDate, zone: ZoneId, hasAccess: Boolean): Pair<Int, SleepSource>? {
        val from = morningOf.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val to = morningOf.atTime(12, 0).atZone(zone).toInstant()
        health.collectSleep(from, to)?.let { return it.durationMinutes to SleepSource.HealthConnect }
        if (!hasAccess) return null
        val end = minOf(to.toEpochMilli(), System.currentTimeMillis())
        val gap = usage.longestScreenOff(from.toEpochMilli(), end) ?: return null
        val minutes = ((gap.second - gap.first) / 60_000).toInt()
        // A 40-minute pause isn't a night; ignore short gaps
        return if (minutes >= 180) minutes to SleepSource.PhoneFree else null
    }
}

private fun ru.keegoo.companion.domain.model.UsageSnapshot.hasData() = screenMinutes > 0 || unlocks > 0

private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else average().toInt()
