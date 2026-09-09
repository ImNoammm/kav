package uk.noammm.kav.ui

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.noammm.kav.data.Moovit

@RunWith(AndroidJUnit4::class)
class WhenSheetRegressionTest {
    private val now = 1_757_000_000_000L

    @Test
    fun testATimeAlreadyGoneBecomesDepartNow() {
        // an hour ago, asked for as a departure
        assertEquals(
            0L to Moovit.TIME_DEPARTURE,
            clampDepart(now - 3_600_000L, Moovit.TIME_DEPARTURE, now),
        )
        // and as an arrival: Kav collapses both, rather than asking the server to
        // get somewhere before it was asked
        assertEquals(
            0L to Moovit.TIME_DEPARTURE,
            clampDepart(now - 60_000L, Moovit.TIME_ARRIVAL, now),
        )
    }

    @Test
    fun testNowItselfIsNotAFutureTime() {
        assertEquals(0L to Moovit.TIME_DEPARTURE, clampDepart(now, Moovit.TIME_ARRIVAL, now))
    }

    @Test
    fun testAFutureTimeKeepsBothItsClockAndItsType() {
        assertEquals(
            (now + 60_000L) to Moovit.TIME_ARRIVAL,
            clampDepart(now + 60_000L, Moovit.TIME_ARRIVAL, now),
        )
        assertEquals(
            (now + 86_400_000L) to Moovit.TIME_DEPARTURE,
            clampDepart(now + 86_400_000L, Moovit.TIME_DEPARTURE, now),
        )
    }
}
