package uk.noammm.kav

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.data.Moovit

/** The journey codec round-tripped through its own JSON; no disk, no network. */
@RunWith(AndroidJUnit4::class)
class JourneyFileRegressionTest {

    private fun journey(): ActiveJourney {
        val wait = Moovit.Leg(
            kind = Moovit.LegKind.WAIT, lineId = 15043, fromStop = 22773,
            nextDeps = listOf(
                Moovit.Departure(11L, 1_800_000_000L, rtUtc = 1_800_000_060L, certainty = 1),
                Moovit.Departure(12L, 1_800_000_900L, frequency = true, alert = 3),
            ),
        )
        val ride = Moovit.Leg(
            kind = Moovit.LegKind.RIDE, lineId = 15043, tripId = 77L,
            dep = 1_800_000_000L, arr = 1_800_001_800L,
            stops = listOf(22773, 22774, 22775), fromStop = 22773, toStop = 22775,
            meters = 4200, shortName = "480", fare = 1210, currency = "ILS",
            shape = listOf(32.0853 to 34.7818, 32.0861 to 34.7822, 32.0899 to 34.7841),
            alertCategory = 3, alertText = "Detour",
            alternatives = listOf(Moovit.Leg(kind = Moovit.LegKind.RIDE, lineId = 15044, tripId = 78L)),
            alternativeLineIds = listOf(15044, 15045),
        )
        val walk = Moovit.Leg(
            kind = Moovit.LegKind.WALK, meters = 350, pathway = true,
            shape = listOf(32.0899 to 34.7841, 32.0905 to 34.7850),
        )
        val taxi = Moovit.Leg(
            kind = Moovit.LegKind.TAXI,
            taxiPickup = 32.0853 to 34.7818, taxiDropoff = 32.1004 to 34.8021,
        )
        val trip = Moovit.Itinerary(
            guid = "g-1", group = 2, legs = listOf(wait, ride, walk, taxi),
            dep = 1_799_999_700L, arr = 1_800_002_400L, fare = 1210, currency = "ILS",
            co2g = 320, accessible = true, tags = listOf("RECOMMENDED"),
            section = "Transit", sectionId = 4,
        )
        val resolved = Moovit.Resolved(
            lines = mapOf(15043 to Moovit.LineInfo(901, "480", 3, "תל אביב", "ירושלים", "480 לירושלים")),
            stops = mapOf(
                22773 to Moovit.StopInfo(22773, "ת. מרכזית ת\"א", "20100", 32.0853, 34.7818),
                22775 to Moovit.StopInfo(22775, "No fix", ""),
            ),
            routeTypes = mapOf(3 to 3, 22 to 2),
            live = mapOf(
                Moovit.ArrivalKey(22773, 77L) to Moovit.Arrival(
                    22773, 15043, 77L, 1_800_000_000L, 1_800_000_060L, 0L,
                    0, 1, 0, false, false, true,
                ),
            ),
            shapes = mapOf(510 to listOf(32.08 to 34.78, 32.09 to 34.79)),
            patterns = mapOf(6001 to listOf(22773, 22774, 22775)),
        )
        return ActiveJourney(trip, resolved, "בית", "Work", chosen = mapOf(1 to 1))
    }

    @Test
    fun testJourneySurvivesItsOwnJson() {
        val before = journey()
        val text = JourneyFile.json(before, 3).toString()
        val (after, step) = JourneyFile.parse(JSONObject(text))!!

        assertEquals(3, step)
        assertEquals(before.fromLabel, after.fromLabel)
        assertEquals(before.toLabel, after.toLabel)
        assertEquals(before.chosen, after.chosen)

        // Leg and Departure are data classes: one equality covers every field of
        // every leg, the nested departures and alternatives and the taxi ends too.
        assertEquals(before.trip.legs, after.trip.legs)
        assertEquals(before.trip.guid, after.trip.guid)
        assertEquals(before.trip.group, after.trip.group)
        assertEquals(before.trip.dep, after.trip.dep)
        assertEquals(before.trip.arr, after.trip.arr)
        assertEquals(before.trip.fare, after.trip.fare)
        assertEquals(before.trip.currency, after.trip.currency)
        assertEquals(before.trip.co2g, after.trip.co2g)
        assertEquals(before.trip.accessible, after.trip.accessible)
        assertEquals(before.trip.tags, after.trip.tags)
        assertEquals(before.trip.section, after.trip.section)
        assertEquals(before.trip.sectionId, after.trip.sectionId)

        val line = after.resolved.lines.getValue(15043)
        assertEquals(901, line.groupId)
        assertEquals("480", line.number)
        assertEquals(3, line.agencyId)
        assertEquals("תל אביב", line.origin)
        assertEquals("ירושלים", line.destination)
        assertEquals("480 לירושלים", line.caption)

        val named = after.resolved.stops.getValue(22773)
        assertEquals("ת. מרכזית ת\"א", named.name)
        assertEquals("20100", named.code)
        assertEquals(32.0853 to 34.7818, named.point)
        // a stop with no position must come back with none, not with a broken one
        assertNull(after.resolved.stops.getValue(22775).point)

        assertEquals(before.resolved.routeTypes, after.resolved.routeTypes)
        assertEquals(before.resolved.shapes, after.resolved.shapes)
        // the live layer and its patterns are refetched, never restored
        assertTrue(after.resolved.live.isEmpty())
        assertTrue(after.resolved.patterns.isEmpty())
    }

    @Test
    fun testAFileFromAnotherKavIsAFreshStartNotACrash() {
        val o = JourneyFile.json(journey(), 0)
        o.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).put("kind", "GONDOLA")
        assertNull(JourneyFile.parse(o))
    }
}
