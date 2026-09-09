package uk.noammm.kav.ui

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import uk.noammm.kav.data.Moovit

@RunWith(AndroidJUnit4::class)
class NavigationRegressionTest {
    @Test
    fun testStopCoordinatesDoNotCreateExtraEndpointCircles() {
        val points = listOf(32.1 to 34.9, 32.11 to 34.91)
        val leg = Moovit.Leg(Moovit.LegKind.RIDE, fromStop = 1, toStop = 2,
            stops = listOf(1, 2), shape = listOf(32.10001 to 34.90001, 32.11001 to 34.91001))
        val stops = points.mapIndexed { i, p -> i + 1 to Moovit.StopInfo(i + 1, "Stop", "", p.first, p.second) }.toMap()
        assertEquals(points, rideStopPoints(listOf(leg), stops))
    }

    @Test
    fun testNextStopIndexIsRelativeToBoardingStop() {
        val leg = Moovit.Leg(Moovit.LegKind.RIDE, stops = listOf(101, 102, 103))
        fun arrival(next: Int, boarding: Int = 10) = Moovit.Arrival(101, 1, 1L, 100L, 100L, 0L, 0, 0, 0, false, false, true,
            nextStopIndex = next, stopIndex = boarding)
        assertEquals(102, nextStopOnLeg(leg, arrival(11)))
        assertNull(nextStopOnLeg(leg, arrival(9)))
        assertNull(nextStopOnLeg(leg, arrival(14)))
        assertNull(nextStopOnLeg(leg, arrival(11, -1)))
    }

    @Test
    fun testHomeWaitsForLiveDepartureInsteadOfAdvancingOnScheduledTime() {
        val trip = Moovit.Itinerary("delayed", 1, listOf(
            Moovit.Leg(Moovit.LegKind.WALK, dep = 50, arr = 100, meters = 200),
            Moovit.Leg(Moovit.LegKind.RIDE, lineId = 77, dep = 200, arr = 500,
                tripId = 1L, fromStop = 101, toStop = 102, stops = listOf(101, 102)),
        ), 50, 500)
        val steps = buildSteps(trip, "Home", "Destination")
        val live = Moovit.Arrival(101, 77, 1L, 200L, 400L, 0L, 0, 0, 0, false, false, true, patternId = 7)
        val resolved = Moovit.Resolved(live = mapOf(Moovit.ArrivalKey(101, 1L) to live),
            patterns = mapOf(7 to listOf(101, 102)))
        assertTrue(steps[journeyProgress(steps, 0, resolved, emptyMap(), 250, null)] is Step.Wait)
        assertTrue(steps[journeyProgress(steps, 0, resolved, emptyMap(), 450, null)] is Step.Ride)
    }

    @Test
    fun testIntermediateStopsArePlacedOnTheRoute() {
        val points = listOf(32.1 to 34.9, 32.11 to 34.91, 32.12 to 34.92)
        val leg = Moovit.Leg(Moovit.LegKind.RIDE, stops = listOf(1, 2, 3), shape = listOf(points.first(), points.last()))
        val stops = points.mapIndexed { i, p -> i + 1 to Moovit.StopInfo(i + 1, "Stop", "", p.first, p.second) }.toMap()
        assertEquals(points.toSet(), rideStopPoints(listOf(leg), stops).toSet())
        assertEquals(listOf(points.first(), points.last()), rideStopPoints(listOf(leg), emptyMap()))
    }

    @Test
    fun testTaxiHandoffEndsAtTheTaxiTransferNotTheFinalDestination() {
        val taxi = Moovit.Leg(Moovit.LegKind.TAXI,
            taxiPickup = 32.1 to 34.9, taxiDropoff = 32.12 to 34.92)
        val uri = gettUri(taxi)!!
        assertEquals("gett", uri.scheme)
        assertEquals("order", uri.host)
        assertEquals("32.1", uri.getQueryParameter("pickup_latitude"))
        assertEquals("34.92", uri.getQueryParameter("dropoff_longitude"))
        assertNull(uri.getQueryParameter("client_id"))
        assertNull(gettUri(Moovit.Leg(Moovit.LegKind.TAXI)))
        assertNull(gettUri(Moovit.Leg(Moovit.LegKind.WALK)))
        assertNull(gettUri(Moovit.Leg(Moovit.LegKind.TAXI,
            taxiPickup = Double.NaN to 34.9, taxiDropoff = 32.12 to 34.92)))
    }

    @Test
    fun testTaxiAndBikeStepsAreNotSkipped() {
        val trip = Moovit.Itinerary("multi-mode", 1, listOf(
            Moovit.Leg(Moovit.LegKind.TAXI, dep = 100, arr = 200),
            Moovit.Leg(Moovit.LegKind.RIDE, dep = 300, arr = 500),
            Moovit.Leg(Moovit.LegKind.BIKE, dep = 500, arr = 700),
        ), 100, 700)
        val steps = buildSteps(trip, "Home", "Destination")
        assertEquals(6, steps.size)
        assertTrue(steps[1] is Step.Taxi)
        assertTrue(steps[2] is Step.Wait)
        assertTrue(steps[3] is Step.Ride)
        assertTrue(steps[4] is Step.Cycle)
    }
}
