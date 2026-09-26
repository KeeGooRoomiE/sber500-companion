package ru.keegoo.companion.domain.model

import java.time.LocalDate
import java.time.LocalTime

data class DailySnapshot(
    val date: LocalDate,
    val usage: UsageSnapshot?,   // null without usage access — never send zeros as data
    val sleep: SleepSnapshot?,
    val battery: BatterySnapshot?,  // only known for today's snapshot
    val steps: Int? = null,
)

data class UsageSnapshot(
    val screenMinutes: Int,
    val unlocks: Int,
    val firstUnlock: LocalTime?,
    val lastUnlock: LocalTime?,
    val topApps: List<AppUsage>,
    val unlocksByHour: List<Int> = emptyList(),       // 24 values, local hours
    val screenMinutesByHour: List<Int> = emptyList(), // 24 values, local hours
)

data class AppUsage(val packageName: String, val minutes: Int)

data class SleepSnapshot(
    val durationMinutes: Int,
    val bedtime: LocalTime?,
    val wakeup: LocalTime?,
)

data class BatterySnapshot(
    val levelPercent: Int,
)
