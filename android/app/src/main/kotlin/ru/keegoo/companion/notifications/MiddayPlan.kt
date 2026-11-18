package ru.keegoo.companion.notifications

import java.time.LocalDate
import java.time.LocalTime
import java.util.Random

/**
 * When the midday notification fires and whether it should — kept free of Android so it is
 * testable on the JVM.
 */
object MiddayPlan {

    /**
     * The moment for [date]: [centerMin] ± [deltaMin] minutes from midnight, picked the same way
     * every time for the same day and install (so re-arming the alarm on app start lands on the
     * same minute), and different from day to day and between people.
     */
    fun timeFor(date: LocalDate, centerMin: Int, deltaMin: Int, salt: Int): LocalTime {
        val delta = deltaMin.coerceIn(0, 12 * 60)
        val offset = if (delta == 0) 0 else Random(date.toEpochDay() * 31L + salt).nextInt(2 * delta + 1) - delta
        val minute = (centerMin + offset).coerceIn(0, 24 * 60 - 1)
        return LocalTime.of(minute / 60, minute % 60)
    }

    /** Why a midday notification is skipped; null means «post it». */
    enum class Skip { Disabled, NotOnboarded, AlreadyActive }

    fun skipReason(enabled: Boolean, onboarded: Boolean, activeToday: Boolean): Skip? = when {
        !enabled -> Skip.Disabled
        !onboarded -> Skip.NotOnboarded
        // The point of this notification is the person who has not looked at the app today.
        activeToday -> Skip.AlreadyActive
        else -> null
    }
}
