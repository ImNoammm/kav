package uk.noammm.kav

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.TReader
import uk.noammm.kav.data.TWriter

/** Pure geometry over recorded fixtures; no network. */
@RunWith(AndroidJUnit4::class)
class GeometryRegressionTest {
    private val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun testHttpFailureCannotMasqueradeAsEmptyLiveArrivals() {
        try {
            Moovit.parseStopArrivals(503, byteArrayOf())
            fail("A failed live request must reach refreshLive's retention path")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("503"))
        }
        val validEmpty = TWriter().i32Field(1, 42).i32Field(5, 30).stop().bytes()
        val (arrivals, poll) = Moovit.parseStopArrivals(200, validEmpty)
        assertTrue(arrivals.isEmpty())
        assertEquals(30, poll)
    }

    @Test
    fun testArrivalConfigurationRequestsTripShapeIds() {
        // StopsArrivals uses the default configuration. Field 5 must be true or
        // the server sends vehicle GPS without the route's tripShapeId.
        val conf = TReader(Moovit.arrivalsConf().stop().bytes()).readStruct()
        assertEquals(true, conf[5])
        assertEquals(true, conf[4])
    }

    @Test
    fun testStopMetadataCoordinatesUseMicrodegrees() {
        val fixture = TWriter().structField(5, TWriter()
            .i32Field(1, 42).strField(2, "Boarding stop").strField(4, "12345")
            .structField(3, TWriter().i32Field(1, 32_075_500).i32Field(2, 34_775_500)))
            .stop().bytes()
        val stop = Moovit.stopInfoOf(42, TReader(fixture).readStruct())!!
        assertEquals("Boarding stop", stop.name)
        assertEquals("12345", stop.code)
        assertEquals(32.0755, stop.point!!.first, 1e-9)
        assertEquals(34.7755, stop.point!!.second, 1e-9)
    }

    @Test
    fun testMissingOrInvalidStopCoordinatesStayUnknown() {
        val fixture = TWriter().structField(5, TWriter().i32Field(1, 42).strField(2, "Stop"))
            .stop().bytes()
        val stop = Moovit.stopInfoOf(42, TReader(fixture).readStruct())!!
        assertTrue(stop.lat.isNaN())
        assertTrue(stop.lon.isNaN())
        assertNull(stop.point)
        assertNull(Moovit.StopInfo(42, "Stop", "", Double.POSITIVE_INFINITY, 35.0).point)
        assertNull(Moovit.StopInfo(42, "Stop", "", 91.0, 35.0).point)
        assertNull(Moovit.StopInfo(42, "Stop", "", 32.0, -181.0).point)
        assertEquals(0.0 to 35.0, Moovit.StopInfo(42, "Stop", "", 0.0, 35.0).point)
    }

    @Test
    fun testFullRouteUsesTripShapeUnionAndEncodedShapeField() {
        val fixture = TWriter().structField(11, TWriter().i32Field(1, 77).strField(2, encoded))
            .stop().bytes()
        val entity = TReader(fixture).readStruct()
        val route = Moovit.tripShapeOf(77, entity)
        assertEquals(3, route.size)
        assertEquals(38.5, route.first().first, 1e-9)
        assertEquals(-120.2, route.first().second, 1e-9)
        assertEquals(43.252, route.last().first, 1e-9)
        assertEquals(-126.453, route.last().second, 1e-9)
        assertTrue(Moovit.tripShapeOf(78, entity).isEmpty())

        // A different entity's field 2 must never be mistaken for route geometry.
        val wrongUnion = TWriter().structField(5, TWriter().i32Field(1, 77).strField(2, encoded))
            .stop().bytes()
        assertTrue(Moovit.tripShapeOf(77, TReader(wrongUnion).readStruct()).isEmpty())
    }

    @Test
    fun testTaxiUsesItsJourneyEndpointsAndShape() {
        val taxi = Moovit.parseLeg(JSONObject("""
            {"5":{"rec":{
                "1":{"rec":{"1":{"i64":100000},"2":{"i64":400000}}},
                "2":{"rec":{
                    "1":{"rec":{"3":{"rec":{"1":{"i32":38500000},"2":{"i32":-120200000}}}}},
                    "2":{"rec":{"3":{"rec":{"1":{"i32":43252000},"2":{"i32":-126453000}}}}}
                }},
                "3":{"rec":{"1":{"dbl":1234.5},"2":{"str":"$encoded"}}}
            }}}
        """))
        assertEquals(Moovit.LegKind.TAXI, taxi.kind)
        assertEquals(100L, taxi.dep)
        assertEquals(400L, taxi.arr)
        assertEquals(1234, taxi.meters)
        assertEquals(38.5 to -120.2, taxi.taxiPickup)
        assertEquals(43.252 to -126.453, taxi.taxiDropoff)
        assertEquals(3, taxi.shape.size)
    }

    @Test
    fun testTaxiDoesNotInventAbsentEndpointCoordinates() {
        val taxi = Moovit.parseLeg(JSONObject("""
            {"5":{"rec":{"2":{"rec":{
                "1":{"rec":{"3":{"rec":{"1":{"i32":91000000},"2":{"i32":35000000}}}}},
                "2":{"rec":{"3":{"rec":{"1":{"i32":32000000}}}}}
            }}}}}
        """))
        assertNull(taxi.taxiPickup)
        assertNull(taxi.taxiDropoff)
        assertTrue(taxi.shape.isEmpty())
    }

    @Test
    fun testOnlyTrackedItineraryRidesRequestWholeRoutes() {
        fun arrival(trip: Long, shape: Int, tracked: Boolean = true) = Moovit.Arrival(
            stopId = 10, lineId = 20, tripId = trip,
            staticUtc = 0, rtUtc = 0, statisticalUtc = 0, status = 0, certainty = 0, traffic = 0,
            frequency = false, rtDropped = false,
            tracked = tracked, lat = 32.0, lon = 35.0, tripShapeId = shape,
        )
        val itinerary = Moovit.Itinerary(
            "test", 1,
            listOf(
                Moovit.Leg(Moovit.LegKind.RIDE, tripId = 11, fromStop = 10),
                Moovit.Leg(Moovit.LegKind.RIDE, tripId = 12, fromStop = 10),
                Moovit.Leg(Moovit.LegKind.RIDE, tripId = 13, fromStop = 10),
                Moovit.Leg(Moovit.LegKind.WAIT, tripId = 15),
            ),
            0, 0,
        )
        val live = mapOf(
            Moovit.ArrivalKey(10, 11L) to arrival(11, 100),
            Moovit.ArrivalKey(10, 12L) to arrival(12, 100),
            Moovit.ArrivalKey(10, 13L) to arrival(13, 300, tracked = false),
            Moovit.ArrivalKey(10, 14L) to arrival(14, 200),
            Moovit.ArrivalKey(10, 15L) to arrival(15, 500),
        )
        assertEquals(listOf(100), Moovit.trackedShapeIds(listOf(itinerary), live))
    }

    @Test
    fun testMapCameraFocalPaddingCentresTheAnchor() {
        // MapLibre centres the target inside the padded viewport. The overlay rotates
        // about the anchor, so the padded centre must be the anchor exactly, on
        // either side of the middle, and clamped at zero rather than negative.
        for ((ax, ay) in listOf(540f to 1584f, 200f to 300f, 1000f to 2000f, 540f to 1100f)) {
            val p = uk.noammm.kav.ui.focalPadding(androidx.compose.ui.geometry.Offset(ax, ay), 1080f, 2200f)
            p.forEach { assertTrue("padding must not be negative", it >= 0.0) }
            assertEquals(ax.toDouble(), p[0] + (1080.0 - p[0] - p[2]) / 2, 1e-3)
            assertEquals(ay.toDouble(), p[1] + (2200.0 - p[1] - p[3]) / 2, 1e-3)
        }
    }

    @Test
    fun testMercatorRoundTripAndZoomUnits() {
        // The overlay projection and MapLibre agree only if Geo is a true web
        // mercator (round-trips) and the 256px zoom is MapLibre's 512px zoom + 1.
        for ((lat, lon) in listOf(32.0955 to 34.9567, 29.55 to 34.95, 33.3 to 35.57)) {
            assertEquals(lat, uk.noammm.kav.ui.Geo.lat(uk.noammm.kav.ui.Geo.y(lat)), 1e-9)
            assertEquals(lon, uk.noammm.kav.ui.Geo.lon(uk.noammm.kav.ui.Geo.x(lon)), 1e-9)
        }
        // driveTo subtracts exactly 1 from the zoom; that is only right while the
        // app's zoom unit stays the 256px tile against MapLibre's 512px one.
        assertEquals(256, uk.noammm.kav.ui.Geo.SIZE)
    }

    @Test
    fun testAlongPathRoundTripsThroughPointAlong() {
        // alongPath's answer is handed straight to pointAlong. alongPath counted on a
        // flat plane and pointAlong walks in metres, and the two scales differ by about
        // a tenth of a percent, so the round trip drifted with the length of the line:
        // nothing across a stop, tens of metres along a whole ride.
        val path = (0..400).map { (32.0 + it * 0.0005) to (34.8 + it * 0.0004) }
        for (p in listOf(path[3], path[100], path[250], path[400])) {
            val back = uk.noammm.kav.ui.pointAlong(path, uk.noammm.kav.ui.alongPath(p.first, p.second, path))!!
            val drift = uk.noammm.kav.ui.metres(p.first, p.second, back.first, back.second)
            assertTrue("a point on the line came back $drift m away", drift < 1.0)
        }
    }

    @Test
    fun testATransfersTwoMarkersLandOnOneSpot() {
        // Moovit gives a same-stop transfer as one stop id shared by the ride that ends
        // there and the ride that starts there, plus two leg shapes that meet at it.
        // onRoute pulls the stop onto each leg's own line, and boardingMarkers merges
        // the pair into one ring on the strength of both arriving at the same place, so
        // that is what this holds: one stop, one point, reached down a 16 km leg and a
        // 1 km one. It is deliberately not a guard on the drift above — a stop at the
        // very end of a leg is the one place that drift cannot show, because overshoot
        // clamps to path.last() and lands on the junction anyway.
        val junction = 32.122110 to 34.794210
        val stop = 32.122120 to 34.794165
        val arriving = (0..420).map { i ->
            val t = i / 420.0
            (32.099640 + (junction.first - 32.099640) * t) to (34.964080 + (junction.second - 34.964080) * t)
        }
        val leaving = (0..25).map { i ->
            val t = i / 25.0
            (junction.first + (32.113880 - junction.first) * t) to (junction.second + (34.801490 - junction.second) * t)
        }
        val off = uk.noammm.kav.ui.onRoute(stop, arriving)
        val on = uk.noammm.kav.ui.onRoute(stop, leaving)
        val apart = uk.noammm.kav.ui.metres(off.first, off.second, on.first, on.second)
        assertTrue("one stop was drawn as two rings $apart m apart", apart < 2.0)
        // and neither may slide along its line away from the stop it names
        assertTrue(uk.noammm.kav.ui.metres(stop.first, stop.second, off.first, off.second) < 10.0)
        assertTrue(uk.noammm.kav.ui.metres(stop.first, stop.second, on.first, on.second) < 10.0)
    }

    private fun trackedVehicleAt(lat: Double, lon: Double) = Moovit.Arrival(
        stopId = 1, lineId = 1, tripId = 1L, staticUtc = 0L, rtUtc = 0L, statisticalUtc = 0L,
        status = 0, certainty = 0, traffic = 0, frequency = false, rtDropped = false,
        tracked = true, lat = lat, lon = lon, vehicleStatus = 1, nextStopIndex = 3, stopIndex = 0,
    )

    @Test
    fun testStopRailProgressGreysWithTheGroundNotOnArrival() {
        // a straight ~1 km ride north, stops at 0, ~249, ~498 and ~995 m along it
        val shape = listOf(32.0000 to 34.8000, 32.0090 to 34.8000)
        val ids = listOf(1, 2, 3, 4)
        val stops = mapOf(
            1 to Moovit.StopInfo(1, "a", "", 32.0000, 34.8000),
            2 to Moovit.StopInfo(2, "b", "", 32.00225, 34.8000),
            3 to Moovit.StopInfo(3, "c", "", 32.0045, 34.8000),
            4 to Moovit.StopInfo(4, "d", "", 32.0090, 34.8000),
        )
        val ride = Moovit.Leg(Moovit.LegKind.RIDE, stops = ids, shape = shape)
        // the phone, fresh and on the route, halfway between the second and third
        // stop: two whole stops behind plus half the road to the next
        val mid = uk.noammm.kav.ui.Fix(32.003375, 34.8000, at = 1_000L, speed = 8f)
        val p = uk.noammm.kav.ui.stopsProgress(ride, stops, null, mid, 1_001L)
        assertEquals(2.5f, p, 0.15f)
        // the tracked vehicle's own position outranks the phone
        val atThird = uk.noammm.kav.ui.stopsProgress(ride, stops, trackedVehicleAt(32.0045, 34.8000), mid, 1_001L)
        assertEquals(3.0f, atThird, 0.15f)
        // nobody reporting anything: nobody knows, nothing greys
        assertEquals(-1f, uk.noammm.kav.ui.stopsProgress(ride, stops, null, null, 1_001L), 0f)
    }

    @Test
    fun testWalkCameraEngagesFromTheAlightingKerbAndHoldsThroughScatter() {
        // a ~300 m walk east; the fixes that ended the ride sit ~55 m south of its
        // start, across the junction from the walk's own polyline
        val path = listOf(32.0090 to 34.8000, 32.0090 to 34.8032)
        assertTrue(uk.noammm.kav.ui.onWalkNow(32.0085, 34.8000, path, held = false))
        // the same 55 m bias mid-path only HOLDS a camera, it must not newly engage
        // one: reading the card while off the walk still frames the walk itself
        assertFalse(uk.noammm.kav.ui.onWalkNow(32.0085, 34.8016, path, held = false))
        assertTrue(uk.noammm.kav.ui.onWalkNow(32.0085, 34.8016, path, held = true))
        // and ~130 m off, even a held camera lets go
        assertFalse(uk.noammm.kav.ui.onWalkNow(32.0078, 34.8016, path, held = true))
    }

    @Test
    fun testTheWalkAfterARideIsReachedWhenTheStopMomentFellBetweenFixes() {
        // A ~1 km ride north, then a ~300 m walk east from the alighting stop.
        val rideShape = listOf(32.0000 to 34.8000, 32.0090 to 34.8000)
        val walkShape = listOf(32.0090 to 34.8000, 32.0090 to 34.8032)
        val ride = Moovit.Leg(Moovit.LegKind.RIDE, shape = rideShape)
        val walk = Moovit.Leg(Moovit.LegKind.WALK, shape = walkShape)
        val steps = listOf(
            uk.noammm.kav.ui.Step.Start("home", 0L, rideShape),
            uk.noammm.kav.ui.Step.Wait(ride, null, 0, rideShape),
            uk.noammm.kav.ui.Step.Ride(ride, null, 0, rideShape),
            uk.noammm.kav.ui.Step.Walk(walk, -1, null, walkShape),
            uk.noammm.kav.ui.Step.Arrive("work", 0L, walkShape),
        )
        // GPS slept through the stop itself: the first fresh fix lands 150 m down the
        // walk, far outside the 45 m circle that used to be the only way off the ride.
        val late = uk.noammm.kav.ui.Fix(32.0090, 34.80159, at = 1_000L, speed = 1.4f)
        val next = uk.noammm.kav.ui.journeyProgress(steps, 2, Moovit.Resolved(), emptyMap(), 1_001L, late)
        assertEquals(3, next)
    }

    @Test
    fun testARideStillUnderWayKeepsItsVetoOverTheTransferWalk() {
        // The transfer walk doubles back down the same corridor the bus drives, so the
        // rider sits right on top of it 400 m before their stop. The screen must not
        // jump to the walk while the bus is still carrying them.
        val rideShape = listOf(32.0000 to 34.8000, 32.0090 to 34.8000)
        val walkBack = listOf(32.0090 to 34.8000, 32.0050 to 34.8000)
        val ride = Moovit.Leg(Moovit.LegKind.RIDE, shape = rideShape)
        val walk = Moovit.Leg(Moovit.LegKind.WALK, shape = walkBack)
        val steps = listOf(
            uk.noammm.kav.ui.Step.Start("home", 0L, rideShape),
            uk.noammm.kav.ui.Step.Wait(ride, null, 0, rideShape),
            uk.noammm.kav.ui.Step.Ride(ride, null, 0, rideShape),
            uk.noammm.kav.ui.Step.Walk(walk, -1, null, walkBack),
            uk.noammm.kav.ui.Step.Arrive("work", 0L, walkBack),
        )
        val aboard = uk.noammm.kav.ui.Fix(32.0056, 34.8000, at = 1_000L, speed = 9f)
        val next = uk.noammm.kav.ui.journeyProgress(steps, 2, Moovit.Resolved(), emptyMap(), 1_001L, aboard)
        assertEquals(2, next)
    }
}
