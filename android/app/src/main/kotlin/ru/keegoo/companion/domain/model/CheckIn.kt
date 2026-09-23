package ru.keegoo.companion.domain.model

import java.time.LocalDate

enum class DayFeel { OK, MEH, HARD }

data class CheckIn(
    val date: LocalDate,
    val feel: DayFeel,
    val tags: List<String>,
    val noteText: String?,
)
