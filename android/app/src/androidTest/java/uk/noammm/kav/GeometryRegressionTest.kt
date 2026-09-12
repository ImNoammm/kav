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
}
