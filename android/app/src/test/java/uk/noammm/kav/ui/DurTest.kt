package uk.noammm.kav.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DurTest {
    @After fun english() { T.lang = Lang.EN }

    @Test fun minutes() {
        assertEquals("0 min", dur(29))
        assertEquals("1 min", dur(30))
        assertEquals("1 min", dur(89))
        assertEquals("59 min", dur(59 * 60 + 29))
    }

    @Test fun roundsUpIntoTheNextHour() {
        assertEquals("1h 0m", dur(59 * 60 + 30))
        assertEquals("1h 0m", dur(3599))
        assertEquals("2h 0m", dur(7170))
    }

    @Test fun hoursAndMinutes() {
        assertEquals("1h 0m", dur(3600))
        assertEquals("1h 30m", dur(5400))
        assertEquals("1h 59m", dur(7140))
        assertEquals("3h 5m", dur(3 * 3600 + 5 * 60))
    }

    @Test fun hebrew() {
        T.lang = Lang.HE
        assertEquals("2 שע' 0 דק'", dur(7170))
        assertEquals("45 דק'", dur(45 * 60))
    }
}
