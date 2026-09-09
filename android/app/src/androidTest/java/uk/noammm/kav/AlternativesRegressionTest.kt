package uk.noammm.kav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.TReader
import uk.noammm.kav.data.TType
import uk.noammm.kav.data.TWriter

/** Actual 2026-09-08 TripPlanner response: lines 11 and 74 at the same stop. */
@RunWith(AndroidJUnit4::class)
class AlternativesRegressionTest {
    private fun legs(): List<Moovit.Leg> {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets
            .open("moovit-two-lines.json").bufferedReader().use { it.readText() }
        val pair = JSONArray(raw)
        return (0 until pair.length()).map { Moovit.parseLeg(pair.getJSONObject(it)) }
    }

    /**
     * The leg's MVTime is still kept, so the plan's chosen departure survives a
     * futureDepartures list that omits it, but it is a timetable entry, not a
     * tracked one. Moovit builds the schedule it colours from futureDepartures alone,
     * and the server sets MVTime.isRealTime even on lines whose arrivals carry no
     * rtEtdUTC, which painted statistical times as live.
     */
    @Test
    fun testSelectedWaitDepartureIsKeptButIsNotTracked() {
        val wait = legs()[0]
        assertEquals(1788882235L, wait.nextDeps.first().timeUtc)
        assertEquals(0L, wait.nextDeps.first().rtUtc)
        assertEquals(Moovit.TimeState.STATIC, wait.nextDeps.first().state)
    }

    @Test
    fun testSelectedRideKeepsItsTripAtThePlansOwnTime() {
        val ride = legs()[1]
        assertTrue("The selected trip has its own departure", ride.nextDeps.isNotEmpty())
        val selected = ride.nextDeps.first()
        assertEquals(4087503675L, selected.tripId)
        assertEquals(1788882235L, selected.timeUtc)
        assertEquals(0L, selected.rtUtc)
    }

    /**
     * The reported miscolour: a line with no live tracking drawn in the live tint
     * where Moovit draws it plain. futureDepartures holds the real arrival, here a
     * statistical one, and it must win over the leg's MVTime.
     */
    @Test
    fun testStatisticalArrivalForTheChosenTripIsNotPaintedAsTracked() {
        val wait = Moovit.Leg(
            Moovit.LegKind.WAIT, lineId = 11,
            nextDeps = listOf(Moovit.Departure(100L, staticUtc = 1000L)),
        )
        val statistical = Moovit.Departure(100L, staticUtc = 1000L, statisticalUtc = 960L)
        val ride = ride().copy(nextDeps = listOf(Moovit.Departure(100L, staticUtc = 1000L)))
        val deps = Moovit.Resolved().departures(
            ride, wait.copy(nextDeps = listOf(statistical)),
        )
        assertEquals(960L, deps.first().timeUtc)
        assertEquals(Moovit.TimeState.STATISTICAL, deps.first().state)
    }
    @Test
    fun testBothServerAlternativesKeepTheirOwnTripsAndTimes() {
        val (wait, ride) = legs()
        assertEquals(listOf(5763348, 8301924), wait.options.map { it.lineId })
        assertEquals(listOf(5763348, 8301924), ride.options.map { it.lineId })
        assertEquals(5763348, ride.lineId)
        assertEquals(listOf(4087503675L, 4087534392L), ride.options.map { it.tripId })
        // each option keeps the plan's own departure time, as a timetable entry:
        // only an MVArrival can say a departure is tracked
        assertEquals(listOf(1788882235L, 1788882492L),
            ride.options.map { it.nextDeps.first().timeUtc })
        assertEquals(listOf(0L, 0L), ride.options.map { it.nextDeps.first().rtUtc })
        assertTrue(ride.options.all { it.fromStop == 44979543 && it.toStop == 19294 })
        assertTrue(ride.options.all { it.alternatives.isEmpty() })
    }

    @Test
    fun testFallbackIncludesSelectedAndFutureTripsOnceForEachLine() {
        val (wait, ride) = legs()
        val departures = Moovit.boardingOptions(ride, wait).map { (r, w) ->
            Moovit.Resolved().departures(r, w)
        }
        assertEquals(listOf(4087503675L, 4087503676L, 4087503677L),
            departures[0].map { it.tripId })
        assertEquals(listOf(4087534392L, 4087534393L, 4087534394L, 4087534395L, 4087534396L),
            departures[1].map { it.tripId })
        // This capture is the reported miscolour. Line 1's chosen trip IS in its
        // futureDepartures, as a STATISTICAL arrival, no rtEtdUTC, nothing tracking
        // it, yet the leg's MVTime carries isRealTime, so preferring the MVTime drew
        // it in the live tint where Moovit draws it plain. The arrival decides now.
        // Line 0's chosen trip is absent from the list, so it falls back to the plan's
        // own time and reads as a timetable entry.
        assertEquals(1788882235L, departures[0].first().timeUtc)
        assertEquals(1788882492L, departures[1].first().timeUtc)
        assertEquals(listOf(0L, 0L), departures.map { it.first().rtUtc })
        assertEquals(Moovit.TimeState.STATIC, departures[0].first().state)
        assertEquals(Moovit.TimeState.STATISTICAL, departures[1].first().state)
        assertEquals(Moovit.TimeState.STATISTICAL, departures[1][1].state)
    }

    @Test
    fun testWaitAndRideMatchByLineWhenAlternativeOrderAndPrimaryDiffer() {
        val (wait, ride) = legs()
        val reversed = wait.options.last().copy(alternatives = wait.options.reversed())
        val pairs = Moovit.boardingOptions(ride, reversed)
        assertEquals(listOf(5763348, 8301924), pairs.map { it.first.lineId })
        assertEquals(listOf(5763348, 8301924), pairs.map { it.second!!.lineId })
        assertEquals(1788882235L, pairs[0].second!!.nextDeps.first().timeUtc)
        assertEquals(1788882492L, pairs[1].second!!.nextDeps.first().timeUtc)
        assertNull(Moovit.boardingOptions(ride.options[0], wait.options[1]).single().second)
        assertNull(Moovit.boardingOptions(ride.options[0], null).single().second)
    }

    private fun ride() = Moovit.Leg(
        kind = Moovit.LegKind.RIDE, lineId = 11, tripId = 100L,
        fromStop = 10, toStop = 30, dep = 1000L,
        nextDeps = listOf(Moovit.Departure(100L, 1000L, rtUtc = 1100L)),
    )

    private fun arrival(
        trip: Long = 100L, stop: Int = 10, line: Int = 11, pattern: Int = 7,
        static: Long = 1000L, rt: Long = 0L, dropped: Boolean = false,
    ) = Moovit.Arrival(
        stopId = stop, lineId = line, tripId = trip, staticUtc = static, rtUtc = rt,
        statisticalUtc = 0L, status = 0, certainty = 0, traffic = 0,
        frequency = false, rtDropped = dropped, tracked = false, patternId = pattern,
    )

    private fun resolved(vararg arrivals: Moovit.Arrival, patterns: Map<Int, List<Int>> = emptyMap()) =
        Moovit.Resolved(
            live = arrivals.associateBy { Moovit.ArrivalKey(it.stopId, it.tripId) },
            patterns = patterns,
        )

    @Test
    fun testLiveDestinationPatternAdmitsNewTripsAndExcludesUnrelatedArrivals() {
        val live = resolved(
            arrival(trip = 200L, static = 1200L, rt = 1250L),
            arrival(trip = 201L, static = 1400L),
            arrival(trip = 202L, line = 74),
            arrival(trip = 203L, stop = 20),
            arrival(trip = 204L, pattern = 8),
            arrival(trip = 205L, pattern = 999),
            patterns = mapOf(7 to listOf(10, 20, 30), 8 to listOf(10, 20, 40)),
        )
        val deps = live.departures(ride(), null)
        assertEquals(listOf(200L, 201L), deps.map { it.tripId })
        assertEquals(1250L, deps[0].timeUtc)
        assertEquals(Moovit.TimeState.REAL_TIME, deps[0].state)
        assertEquals(1400L, deps[1].timeUtc)
        assertEquals(Moovit.TimeState.STATIC, deps[1].state)
    }

    @Test
    fun testUnknownPatternFallsBackToPlanWithoutAdmittingUnknownTrips() {
        val live = resolved(arrival(trip = 200L, rt = 900L))
        val wait = Moovit.Leg(Moovit.LegKind.WAIT, lineId = 11,
            nextDeps = listOf(Moovit.Departure(101L, 1500L)))
        val deps = live.departures(ride(), wait)
        assertEquals(listOf(100L, 101L), deps.map { it.tripId })
        assertEquals(1100L, deps.first().rtUtc)
    }

    @Test
    fun testResolvedLiveScheduleReplacesSelectedRealtimeWithoutPromotingStaticTime() {
        val deps = resolved(arrival(), patterns = mapOf(7 to listOf(10, 20, 30))).departures(ride(), null)
        assertEquals(0L, deps.single().rtUtc)
        assertEquals(Moovit.TimeState.STATIC, deps.single().state)
    }

    @Test
    fun testStatisticsStayStatisticalInTheAuthoritativeLiveSchedule() {
        val a = Moovit.Arrival(10, 11, 100L, 1000L, 0L, 1050L, 0, 0, 0,
            false, false, false, patternId = 7)
        val dep = resolved(a, patterns = mapOf(7 to listOf(10, 20, 30)))
            .departures(ride(), null).single()
        assertEquals(1050L, dep.timeUtc)
        assertEquals(Moovit.TimeState.STATISTICAL, dep.state)
    }

    @Test
    fun testExplicitLiveTrackingDropClearsSelectedRealtime() {
        val dep = resolved(arrival(dropped = true), patterns = mapOf(7 to listOf(10, 20, 30)))
            .departures(ride(), null).single()
        assertEquals(0L, dep.rtUtc)
        assertEquals(1000L, dep.timeUtc)
        assertEquals(Moovit.TimeState.REAL_TIME_DROPPED, dep.state)
    }

    /**
     * The reported miss: Moovit's card said the bus leaves in 4 minutes while Kav
     * skipped to the one 16 minutes out.
     *
     * com.moovit.data.tripplan.realtime.c.b (DefaultItineraryRealTimeRepository)
     * keeps EVERY live arrival at the leg's line + boarding stop whose pattern
     * contains the destination stop, sorts them and returns them; there is no floor
     * at the plan's own departure anywhere in it. Kav floored the live list at the
     * planned departure, which is exactly the bus Moovit was showing and Kav was not.
     */
    @Test
    fun testLiveArrivalBeforeThePlannedDepartureIsStillOffered() {
        val live = resolved(
            arrival(trip = 200L, static = 1240L, rt = 1240L),
            arrival(trip = 100L, static = 1960L, rt = 1960L),
            patterns = mapOf(7 to listOf(10, 20, 30)),
        )
        val plan = ride().copy(dep = 1960L, nextDeps = listOf(Moovit.Departure(100L, 1960L)))
        val wait = Moovit.Leg(Moovit.LegKind.WAIT, lineId = 11, dep = 1960L)
        val deps = live.departures(plan, wait)
        assertEquals(listOf(200L, 100L), deps.map { it.tripId })
        assertEquals(1240L, deps.first().timeUtc)
        assertEquals(Moovit.TimeState.REAL_TIME, deps.first().state)
    }

    /**
     * The same rule stated the other way round: a qualifying live schedule replaces
     * the plan's schedule outright, early times included. Moovit keeps a journey or a
     * transfer honest in its PRESENTATION layer instead, mb7 lists schedule.m(now)
     * and Kav's departLabels/TripDetail already drop anything before now, so the
     * repository itself never compares a live arrival against the plan.
     */
    @Test
    fun testLiveScheduleReplacesThePlanWithoutAPlanRelativeFloor() {
        val later = ride().copy(dep = 8200L,
            nextDeps = listOf(Moovit.Departure(100L, 8200L)))
        val live = resolved(arrival(trip = 200L, static = 1000L),
            patterns = mapOf(7 to listOf(10, 20, 30)))
        assertEquals(listOf(1000L), live.departures(later, null).map { it.timeUtc })
        val transfer = Moovit.Leg(Moovit.LegKind.WAIT, lineId = 11, dep = 8000L, arr = 8200L)
        assertEquals(listOf(1000L), live.departures(later, transfer).map { it.timeUtc })
        val reachable = resolved(arrival(trip = 201L, static = 8100L),
            patterns = mapOf(7 to listOf(10, 20, 30)))
        assertEquals(listOf(8100L), reachable.departures(later, transfer).map { it.timeUtc })
    }

    @Test
    fun testSameTripAtTwoStopsSurvivesConcatenatedThriftResponses() {
        fun response(stop: Int, rtMillis: Long) = TWriter().i32Field(1, stop)
            .listField(3, TType.STRUCT, listOf(11)) { lineWriter, line ->
                lineWriter.i32Field(1, line)
                    .listField(2, TType.STRUCT, listOf(100L)) { writer, trip ->
                        writer.i32Field(1, 7).i64Field(2, trip)
                            .i64Field(3, 1000000L).i64Field(4, rtMillis).stop()
                    }.stop()
            }.i32Field(5, 30).stop().bytes()
        val (arrivals, poll) = Moovit.parseStopArrivals(200,
            response(10, 1100000L) + response(20, 1300000L))
        assertEquals(2, arrivals.size)
        assertEquals(30, poll)
        assertEquals(1100L, arrivals[Moovit.ArrivalKey(10, 100L)]!!.rtUtc)
        assertEquals(1300L, arrivals[Moovit.ArrivalKey(20, 100L)]!!.rtUtc)
        assertEquals(7, arrivals[Moovit.ArrivalKey(10, 100L)]!!.patternId)
        val live = Moovit.Resolved(live = arrivals, patterns = mapOf(7 to listOf(10, 20, 30)))
        assertEquals(1100L, live.arrival(ride())!!.rtUtc)
        assertEquals(1300L, live.arrival(ride().copy(fromStop = 20))!!.rtUtc)
        assertNull(live.arrival(ride().copy(fromStop = 99)))
        assertEquals(1100L, live.departures(ride(), null).single().rtUtc)
    }

    @Test
    fun testTripPatternUsesUnionIdentityAndOrderedStops() {
        val entity = TReader(TWriter().structField(9, TWriter()
            .i32Field(1, 7).i32ListField(2, listOf(10, 20, 30)))
            .stop().bytes()).readStruct()
        assertEquals(listOf(10, 20, 30), Moovit.tripPatternOf(7, entity))
        assertTrue(Moovit.tripPatternOf(8, entity).isEmpty())
        val wrongUnion = TReader(TWriter().structField(5, TWriter()
            .i32Field(1, 7).i32ListField(2, listOf(10, 20, 30)))
            .stop().bytes()).readStruct()
        assertTrue(Moovit.tripPatternOf(7, wrongUnion).isEmpty())
        assertTrue(Moovit.tripPatternOf(7, emptyMap()).isEmpty())
    }

    @Test
    fun testCertaintyControlsRealtimeStateWhileStatusAndTrafficDoNotInventIt() {
        val scheduled = Moovit.Departure(100L, 1000L, status = 2, traffic = 3)
        assertEquals(Moovit.TimeState.STATIC, scheduled.state)
        assertFalse(scheduled.live)
        assertTrue(scheduled.delayed)
        val realtime = scheduled.copy(rtUtc = 1100L)
        assertEquals(Moovit.TimeState.REAL_TIME, realtime.state)
        assertEquals(Moovit.TimeState.REAL_TIME_HIGH, realtime.copy(certainty = 1).state)
        assertEquals(Moovit.TimeState.REAL_TIME_MEDIUM, realtime.copy(certainty = 2).state)
        assertEquals(Moovit.TimeState.REAL_TIME_LOW, realtime.copy(certainty = 3).state)
        assertEquals(Moovit.TimeState.REAL_TIME, realtime.copy(status = 4).state)
        assertEquals(Moovit.TimeState.CANCELED, realtime.copy(status = 3).state)
        assertEquals(Moovit.TimeState.STATISTICAL,
            scheduled.copy(statisticalUtc = 1050L, certainty = 1).state)
    }

    /**
     * Train 679 was painted as a tracked departure while Kav's own Live screen said
     * "Line not departed yet". Moovit never presents a trip that has not left its
     * origin as tracked: both presentation sites test the vehicle status BEFORE they
     * consult the colour table (`m5e.c` swaps the marker for
     * mvf_clock_solid_16_surface_inverse_emphasis_medium, and
     * StopArrivalsActivity$StopArrivalsMetadataType.isNotDepartYetState drives the
     * DID_NOT_DEPART_YET row and suppresses the live sample time). The estimate still
     * supplies the time that is SHOWN, Moovit's Time.f() returns rtEtdUTC whenever it
     * is set, whatever the vehicle is doing, so only the state may change here.
     */
    @Test
    fun testTripThatHasNotLeftItsOriginIsNotPresentedAsTracked() {
        val started = Moovit.Departure(679L, 1000L, rtUtc = 1100L, certainty = 1)
        assertEquals(Moovit.TimeState.REAL_TIME_HIGH, started.state)
        assertTrue(started.live)

        val notStarted = started.copy(vehicleStatus = 3)
        assertEquals(Moovit.TimeState.STATIC, notStarted.state)
        assertFalse(notStarted.live)
        // the tracked estimate is still the time on the card, exactly as Time.f() has it
        assertEquals(1100L, notStarted.timeUtc)

        // a statistical estimate underneath still shows as statistical, not as static
        assertEquals(
            Moovit.TimeState.STATISTICAL,
            notStarted.copy(statisticalUtc = 1050L).state,
        )
        // and OUT_OF_SHAPE still wins, since Moovit tests it first
        assertEquals(Moovit.TimeState.OUT_OF_SHAPE, notStarted.copy(vehicleStatus = 2).state)
    }

    /**
     * MVRouteType, value for value. Kav used to fold cable, gondola, funicular and
     * monorail into TRAM and drop route type 8, the share-taxi network, into OTHER,
     * so four distinct vehicles drew one mark and a fifth drew a bare circle. Every
     * value here is one the Israeli MOT feed actually ships.
     */
    @Test
    fun testEveryRouteTypeInTheFeedGetsItsOwnVehicle() {
        assertEquals(uk.noammm.kav.ui.Mode.TRAM, uk.noammm.kav.ui.modeOf(0))
        assertEquals(uk.noammm.kav.ui.Mode.SUBWAY, uk.noammm.kav.ui.modeOf(1))
        assertEquals(uk.noammm.kav.ui.Mode.TRAIN, uk.noammm.kav.ui.modeOf(2))
        assertEquals(uk.noammm.kav.ui.Mode.BUS, uk.noammm.kav.ui.modeOf(3))
        assertEquals(uk.noammm.kav.ui.Mode.FERRY, uk.noammm.kav.ui.modeOf(4))
        assertEquals(uk.noammm.kav.ui.Mode.CABLE, uk.noammm.kav.ui.modeOf(5))
        assertEquals(uk.noammm.kav.ui.Mode.GONDOLA, uk.noammm.kav.ui.modeOf(6))
        assertEquals(uk.noammm.kav.ui.Mode.FUNICULAR, uk.noammm.kav.ui.modeOf(7))
        // the two the feed ships that Moovit has no vehicle type for
        assertEquals(uk.noammm.kav.ui.Mode.TAXI, uk.noammm.kav.ui.modeOf(8))
        assertEquals(uk.noammm.kav.ui.Mode.TAXI, uk.noammm.kav.ui.modeOf(715))
        // no two feed types collapse onto one another any more
        val feedTypes = listOf(0, 1, 2, 3, 4, 5, 6, 7)
        assertEquals(feedTypes.size, feedTypes.map { uk.noammm.kav.ui.modeOf(it) }.toSet().size)
    }
}
