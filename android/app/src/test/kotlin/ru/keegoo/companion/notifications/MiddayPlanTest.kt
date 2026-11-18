package ru.keegoo.companion.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MiddayPlanTest {
    private val day = LocalDate.of(2026, 11, 11)

    @Test
    fun timeStaysInsideTheWindow() {
        val lo = LocalTime.of(13, 0)
        val hi = LocalTime.of(15, 0)
        for (offset in 0..400L) {
            for (salt in listOf(0, 1, -7, 123456)) {
                val t = MiddayPlan.timeFor(day.plusDays(offset), 14 * 60, 60, salt)
                assertTrue("$t out of window", t >= lo && t <= hi)
            }
        }
    }

    @Test
    fun sameDayAndInstallGiveTheSameMinute() {
        assertEquals(MiddayPlan.timeFor(day, 840, 60, 42), MiddayPlan.timeFor(day, 840, 60, 42))
    }

    @Test
    fun differentDaysAndPeopleSpreadOut() {
        val days = (0..30L).map { MiddayPlan.timeFor(day.plusDays(it), 840, 60, 42) }.toSet()
        val people = (0..30).map { MiddayPlan.timeFor(day, 840, 60, it) }.toSet()
        assertTrue("days did not vary", days.size > 5)
        assertTrue("people did not vary", people.size > 5)
    }

    @Test
    fun zeroDeltaIsExactlyTheCenter() {
        assertEquals(LocalTime.of(14, 0), MiddayPlan.timeFor(day, 840, 0, 99))
    }

    @Test
    fun windowIsClampedToTheDay() {
        val t = MiddayPlan.timeFor(day, 10, 12 * 60, 5)
        assertTrue(t >= LocalTime.MIDNIGHT && t <= LocalTime.of(23, 59))
    }

    @Test
    fun postsOnlyWhenNothingHappenedToday() {
        assertNull(MiddayPlan.skipReason(enabled = true, onboarded = true, activeToday = false))
        assertEquals(MiddayPlan.Skip.AlreadyActive, MiddayPlan.skipReason(true, true, true))
        assertEquals(MiddayPlan.Skip.Disabled, MiddayPlan.skipReason(false, true, false))
        assertEquals(MiddayPlan.Skip.NotOnboarded, MiddayPlan.skipReason(true, false, false))
        assertNotEquals(null, MiddayPlan.skipReason(false, false, true))
    }
}
