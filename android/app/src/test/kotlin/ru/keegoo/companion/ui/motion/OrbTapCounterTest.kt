package ru.keegoo.companion.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbTapCounterTest {
    @Test fun `every third tap of a run is a flurry`() {
        val c = OrbTapCounter()
        val got = (0 until 9).map { c.onTap(1_000L + it * 300) }
        assertEquals(
            listOf(
                OrbTap.MaybeSingle, OrbTap.MaybeSingle, OrbTap.Flurry,
                OrbTap.StillPoking, OrbTap.StillPoking, OrbTap.Flurry,
                OrbTap.StillPoking, OrbTap.StillPoking, OrbTap.Flurry,
            ),
            got,
        )
    }

    @Test fun `a tap long after a flurry is a single tap again`() {
        val c = OrbTapCounter()
        repeat(3) { c.onTap(1_000L + it * 100) }
        assertEquals(OrbTap.MaybeSingle, c.onTap(20_000L))
    }

    @Test fun `slow taps never add up to a flurry`() {
        val c = OrbTapCounter()
        assertEquals(List(4) { OrbTap.MaybeSingle }, (0 until 4).map { c.onTap(it * 6_000L) })
    }
}
