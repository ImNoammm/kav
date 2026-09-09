package uk.noammm.kav.ui

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Moovit

/**
 * A recent trip reopens the route it was taken by, not whatever today's plan ranks
 * first. These pin the two halves of that: recognising the route in a fresh plan, and
 * surviving the trip round trip through storage.
 */
@RunWith(AndroidJUnit4::class)
class RecentRouteRegressionTest {
    private fun ride(lineId: Int, vararg alternatives: Int) = Moovit.Leg(
        Moovit.LegKind.RIDE, lineId = lineId, alternativeLineIds = alternatives.toList(),
    )

    private fun itinerary(group: Int, vararg legs: Moovit.Leg) =
        Moovit.Itinerary("g", group, legs.toList(), 0L, 0L)

    private val walk = Moovit.Leg(Moovit.LegKind.WALK)

    private fun taken(group: Int, vararg lines: Int) = RecentTrip(
        null, Moovit.Place("Azrieli", "", 32.0, 34.8), 0L, lines.toList(), group,
    )

    @Test
    fun testTheRememberedRouteIsPreferredOverTheTopResult() {
        val top = itinerary(2, walk, ride(300), walk)
        val mine = itinerary(2, walk, ride(472), walk)
        val wanted = taken(2, 472)
        assertFalse(sameRoute(top, wanted))
        assertTrue(sameRoute(mine, wanted))
        assertSame(mine, listOf(top, mine).firstOrNull { sameRoute(it, wanted) })
    }

    @Test
    fun testALegOfferingAChoiceOfLinesMatchesOnEither() {
        // "472 / 473": the server can promote either one between plans
        val today = itinerary(2, walk, ride(473, 472), walk)
        assertTrue(sameRoute(today, taken(2, 472)))
        assertTrue(sameRoute(today, taken(2, 473)))
        assertFalse(sameRoute(today, taken(2, 66)))
    }

    @Test
    fun testOrderAndCountOfRidesBothMatter() {
        val twoLegs = itinerary(2, ride(1), ride(50))
        assertTrue(sameRoute(twoLegs, taken(2, 1, 50)))
        assertFalse(sameRoute(twoLegs, taken(2, 50, 1)))
        assertFalse(sameRoute(twoLegs, taken(2, 1)))
    }

    @Test
    fun testATaxiIsNotAWalkEvenThoughNeitherHasALine() {
        val taxi = itinerary(4, Moovit.Leg(Moovit.LegKind.TAXI))
        val onFoot = itinerary(3, walk)
        assertTrue(sameRoute(taxi, taken(4)))
        assertFalse(sameRoute(onFoot, taken(4)))
    }

    @Test
    fun testAnEntrySavedBeforeRoutesWereRecordedMatchesNothing() {
        val old = RecentTrip(null, Moovit.Place("Azrieli", "", 32.0, 34.8), 0L)
        assertFalse(sameRoute(itinerary(2, ride(472)), old))
        assertFalse(sameRoute(itinerary(2), old))
    }

    // The test app's own context, so the phone's real recent trips are left alone.
    private val ctx get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun testTheRouteSurvivesBeingWrittenAndReadBack() {
        val to = Moovit.Place("Azrieli Center", "", 32.0742, 34.7925)
        val trip = itinerary(2, walk, ride(472), walk)
        Prefs.rememberTrip(ctx, null, to, 1_757_000_000_000L, trip)
        val back = Prefs.trips(ctx).first()
        assertEquals(listOf(472), back.lines)
        assertEquals(2, back.group)
        assertTrue(sameRoute(trip, back))

        // taking the same trip by another route replaces the one remembered
        Prefs.rememberTrip(ctx, null, to, 1_757_000_100_000L, itinerary(2, ride(66)))
        assertEquals(listOf(66), Prefs.trips(ctx).first().lines)

        // and choosing a route from the list updates that entry without adding one
        val before = Prefs.trips(ctx).size
        Prefs.noteTripRoute(ctx, null, to, trip)
        assertEquals(before, Prefs.trips(ctx).size)
        assertEquals(listOf(472), Prefs.trips(ctx).first().lines)
    }

    @Test
    fun testAPlanNeverTakenDoesNotBecomeARecentTrip() {
        val elsewhere = Moovit.Place("Somewhere new", "", 31.5, 34.5)
        val before = Prefs.trips(ctx).size
        Prefs.noteTripRoute(ctx, null, elsewhere, itinerary(2, ride(9)))
        assertEquals(before, Prefs.trips(ctx).size)
        assertTrue(Prefs.trips(ctx).none { it.to.name == "Somewhere new" })
    }
}
