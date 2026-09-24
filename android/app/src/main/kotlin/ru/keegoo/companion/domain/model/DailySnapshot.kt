package ru.keegoo.companion.domain.model

import java.time.LocalDate
import java.time.LocalTime

data class DailySnapshot(
    val date: LocalDate,
    val usage: UsageSnapshot?,   // null without usage access — never send zeros as data
    val sleep: SleepSnapshot?,
    val battery: BatterySnapshot,
    val steps: Int? = null,
)

data class UsageSnapshot(
    val screenMinutes: Int,
    val unlocks: Int,
    val firstUnlock: LocalTime?,
    val lastUnlock: LocalTime?,
    val topApps: List<AppUsage>,
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
