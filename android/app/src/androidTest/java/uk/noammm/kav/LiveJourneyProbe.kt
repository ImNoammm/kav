package uk.noammm.kav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uk.noammm.kav.data.Moovit
import java.io.File

/** Explicit online smoke test: run with instrumentation argument liveProbe=true. */
@RunWith(AndroidJUnit4::class)
class LiveJourneyProbe {
    @Test
    fun planHydrateAndRefreshRealJourney() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        val session = Moovit.register(32.102, 34.944)
        val destination = Moovit.stopInfo(session, 19294)?.point
        assertNotNull("The destination stop resolves", destination)
        val plan = Moovit.planItineraries(session, 32.102 to 34.944, destination!!)
        val trips = plan.laidOut()
        assertTrue("The live planner returns transit journeys", trips.any { it.rides.isNotEmpty() })
        val resolved = Moovit.hydrate(session, trips)
        val rows = JSONArray()
        for (trip in trips) for ((index, leg) in trip.legs.withIndex()) {
            if (leg.kind != Moovit.LegKind.RIDE) continue
            val wait = trip.legs.getOrNull(index - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }
            for ((ride, boarding) in Moovit.boardingOptions(leg, wait)) {
                assertNotNull("Alternative line metadata resolves", resolved.line(ride.lineId))
                val departures = resolved.departures(ride, boarding)
                assertTrue("Each ride has a departure", departures.isNotEmpty())
                rows.put(JSONObject().put("line", resolved.line(ride.lineId)?.number)
                    .put("stop", ride.fromStop).put("destination", ride.toStop)
                    .put("alternatives", leg.options.size)
                    .put("departures", JSONArray(departures.take(3).map { d ->
                        JSONObject().put("trip", d.tripId).put("utc", d.timeUtc).put("state", d.state.name)
                    })))
            }
        }
        assertTrue("Arrival pattern entities resolve", resolved.patterns.isNotEmpty())
        Thread.sleep(resolved.pollSecs.coerceIn(15, 120) * 1000L)
        val refreshed = Moovit.refreshLive(session, trips, resolved)
        assertNotSame("A real second live response was received", resolved, refreshed)
        val report = JSONObject().put("checkedAtUtc", System.currentTimeMillis() / 1000)
            .put("itineraries", trips.size).put("arrivalCount", resolved.live.size)
            .put("refreshedArrivalCount", refreshed.live.size).put("patternCount", resolved.patterns.size)
            .put("routes", rows)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "live-journey-probe.json")
            .writeText(report.toString(2))
    }
}
