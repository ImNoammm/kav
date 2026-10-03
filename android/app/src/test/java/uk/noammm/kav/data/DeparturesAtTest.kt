package uk.noammm.kav.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.noammm.kav.data.Bundle.Companion.EVERY_DAY
import uk.noammm.kav.data.Bundle.Companion.MON
import uk.noammm.kav.data.Bundle.Companion.SAT
import uk.noammm.kav.data.Bundle.Companion.SUN
import uk.noammm.kav.data.Bundle.Companion.WED
import uk.noammm.kav.data.Bundle.Companion.hm
import uk.noammm.kav.data.Bundle.Companion.on

class DeparturesAtTest {
    private fun times(net: Net, stop: Int, now: Int, today: Int, limit: Int = 60) =
        net.departuresAt(stop, now, today, limit).map { it.second }

    // A night bus written as 24:10, two stops.
    private fun nightBus(days: Int): Pair<Net, Int> {
        val b = Bundle()
        val a = b.stop("A"); val z = b.stop("Z")
        b.trip(listOf(a, z), listOf(hm(24, 10), hm(24, 30)), days)
        return b.net() to a
    }

    @Test fun lastNightsRunStillToComeIsListedFirst() {
        val (net, a) = nightBus(EVERY_DAY)
        // Monday 00:05: Sunday's 24:10 leaves in five minutes, Monday's a day later.
        assertEquals(listOf(hm(0, 10), hm(24, 10)), times(net, a, hm(0, 5), MON))
    }

    @Test fun lastNightsRunWhenItDoesNotRunToday() {
        val (net, a) = nightBus(on(SUN))
        assertEquals(listOf(hm(0, 10)), times(net, a, hm(0, 5), MON))
    }

    @Test fun tonightsRunWhenItDidNotRunLastNight() {
        val (net, a) = nightBus(on(MON))
        assertEquals(listOf(hm(24, 10)), times(net, a, hm(0, 5), MON))
    }

    @Test fun lastNightsRunThatHasLeftIsGone() {
        val (net, a) = nightBus(EVERY_DAY)
        assertEquals(listOf(hm(24, 10)), times(net, a, hm(0, 20), MON))
    }

    @Test fun saturdayIsTheNightBeforeSunday() {
        val (net, a) = nightBus(on(SAT))
        assertEquals(listOf(hm(0, 10)), times(net, a, hm(0, 0), SUN))
    }

    @Test fun neitherNightRuns() {
        val (net, a) = nightBus(on(WED))
        assertTrue(times(net, a, hm(0, 5), MON).isEmpty())
    }

    @Test fun daytimeTripsAreUnchanged() {
        val b = Bundle()
        val a = b.stop("A"); val z = b.stop("Z")
        b.trip(listOf(a, z), listOf(hm(8, 0), hm(8, 20)))
        b.trip(listOf(a, z), listOf(hm(9, 0), hm(9, 20)), on(MON))
        val net = b.net()
        assertEquals(listOf(hm(8, 0), hm(9, 0)), times(net, a, hm(7, 0), MON))
        assertEquals(listOf(hm(8, 0)), times(net, a, hm(7, 0), SUN))
        assertEquals(listOf(hm(9, 0)), times(net, a, hm(8, 1), MON))
        // The last stop of a trip is where it ends, not a departure.
        assertTrue(times(net, z, hm(7, 0), MON).isEmpty())
    }

    @Test fun soonestFirstAndLimited() {
        val b = Bundle()
        val a = b.stop("A"); val z = b.stop("Z")
        for (k in 0 until 10) b.trip(listOf(a, z), listOf(hm(23, 50) + k * 600, hm(24, 50) + k * 600))
        val net = b.net()
        // Monday 00:30: last night's 00:30 .. 01:20 are still to come, then tonight's 23:50 on.
        val got = times(net, a, hm(0, 30), MON, limit = 7)
        assertEquals(listOf(hm(0, 30), hm(0, 40), hm(0, 50), hm(1, 0), hm(1, 10), hm(1, 20), hm(23, 50)), got)
    }
}
