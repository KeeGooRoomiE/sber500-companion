package ru.keegoo.companion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class Day0Test {
    @Test fun `newcomer sees every line`() {
        assertEquals(Day0(noHealthSource = true, noCheckInsYet = true, acquaintance = true), day0(0, 0, true))
    }

    @Test fun `first mark brings the review back but acquaintance goes on`() {
        assertEquals(Day0(noCheckInsYet = false, acquaintance = true), day0(1, 1, false))
        assertEquals(Day0(acquaintance = false), day0(ACQUAINTANCE_CHECKINS, 3, false))
    }

    @Test fun `someone who updated with a week of forecasts is not on day 0`() {
        assertEquals(Day0(), day0(0, 7, false))
    }
}
