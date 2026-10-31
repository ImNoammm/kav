package uk.noammm.kav.data

// What a trip is made of, for every screen: its stops and lines, the live arrivals at its stops, its legs and the plan
// that holds it, from Kav's planner and Kav's server.
object Transit {
    data class ArrivalKey(val stopId: Int, val tripId: Long)

    class Arrival(
        val stopId: Int,
        val lineId: Int,
        val tripId: Long,
        val staticUtc: Long,
        val rtUtc: Long,
        val statisticalUtc: Long,
        val status: Int,
        val certainty: Int,
        val traffic: Int,
        val frequency: Boolean,
        val rtDropped: Boolean,
        val tracked: Boolean,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val vehicleId: String = "",
        val sampleUtc: Long = 0,
        val vehicleStatus: Int = 0,
        val nextStopIndex: Int = -1,
        val stopIndex: Int = -1,
        val patternStops: Int = -1,
        val tripShapeId: Int = -1,
        val patternId: Int = -1,
        val platform: String = "",
        // Only from Kav's server: degrees from north and km/h at the last report, or -1.
        val bearing: Int = -1,
        val speed: Int = -1,
    ) {
        val key get() = ArrivalKey(stopId, tripId)
        fun departure(alert: Int = 0) = Departure(
            tripId, staticUtc, rtUtc, statisticalUtc, status, certainty, traffic,
            frequency, rtDropped, vehicleStatus, alert, platform,
        )
        val hasLocation get() = tracked && lat != 0.0 && lon != 0.0
        val stopsAway get() =
            if (nextStopIndex >= 0 && stopIndex >= nextStopIndex) stopIndex - nextStopIndex else -1
        fun movedTo(
            lat: Double, lon: Double, sampleUtc: Long, bearing: Int = this.bearing, speed: Int = this.speed, tracked: Boolean = this.tracked,
        ) = Arrival(
            stopId, lineId, tripId, staticUtc, rtUtc, statisticalUtc, status, certainty, traffic, frequency,
            rtDropped, tracked, lat, lon, vehicleId, sampleUtc, vehicleStatus, nextStopIndex, stopIndex,
            patternStops, tripShapeId, patternId, platform, bearing, speed,
        )
    }

    class Stop(val id: Int, val lat: Double, val lon: Double, val name: String)

    class LineInfo(
        val groupId: Int,
        val number: String,
        val agencyId: Int,
        val origin: String,
        val destination: String,
        val caption: String,
    )

    class StopInfo(
        val id: Int,
        val name: String,
        val code: String,
        val lat: Double = Double.NaN,
        val lon: Double = Double.NaN,
    ) {
        val point: Pair<Double, Double>? get() = validPoint(lat, lon)

        // Its name as shown (StopNames): Kav's server and the timetable name stops in Hebrew. A name Kav doesn't know
        // (a planner's "רכבת ..." for a train station, in either language) is the timetable's stop with the same code, as
        // named there.
        fun shown(): String {
            val shown = shownName(name)
            if (shown != name) return shown
            val net = uk.noammm.kav.Loaded.net ?: return shown
            return code.toIntOrNull()?.let { KavLive.stopOfCode(net, it) }?.takeIf { it >= 0 }?.let(net::stopName) ?: shown
        }
    }

    private fun validPoint(lat: Double, lon: Double): Pair<Double, Double>? =
        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null

    // mode is a stop's kind as a route type, a train station's 2, for its glyph; -1 when it isn't one of Kav's stops.
    // googleId is the Google place a Google suggestion names.
    // type is the kind of search result; a town is PLACE_TOWN, Google's towns too.
    class Place(
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        val type: Int = 5,
        val mode: Int = -1,
        val googleId: String? = null,
    )

    const val PLACE_TOWN = 4

    enum class LegKind { WALK, WAIT, RIDE, TAXI, BIKE, OTHER }

    enum class DayOff { SHABBAT, HOLIDAY }

    enum class TimeState { STATIC, STATISTICAL, REAL_TIME, REAL_TIME_HIGH, REAL_TIME_MEDIUM, REAL_TIME_LOW, REAL_TIME_DROPPED, CANCELED, OUT_OF_SHAPE, FREQUENCY }

    data class Departure(
        val tripId: Long,
        val staticUtc: Long,
        val rtUtc: Long = 0,
        val statisticalUtc: Long = 0,
        val status: Int = 0,
        val certainty: Int = 0,
        val traffic: Int = 0,
        val frequency: Boolean = false,
        val rtDropped: Boolean = false,
        val vehicleStatus: Int = 0,
        val alert: Int = 0,
        val platform: String = "",
    ) {
        val timeUtc get() = if (rtUtc > 0) rtUtc else if (statisticalUtc > 0) statisticalUtc else staticUtc

        val state: TimeState get() = when {
            status == 3 -> TimeState.CANCELED
            frequency -> TimeState.FREQUENCY
            vehicleStatus == 2 -> TimeState.OUT_OF_SHAPE
            rtUtc > 0 && vehicleStatus != 3 -> when (certainty) {
                1 -> TimeState.REAL_TIME_HIGH
                2 -> TimeState.REAL_TIME_MEDIUM
                3 -> TimeState.REAL_TIME_LOW
                else -> TimeState.REAL_TIME
            }
            rtDropped -> TimeState.REAL_TIME_DROPPED
            statisticalUtc > 0 -> TimeState.STATISTICAL
            else -> TimeState.STATIC
        }

        val live get() = state == TimeState.REAL_TIME || state == TimeState.REAL_TIME_HIGH ||
            state == TimeState.REAL_TIME_MEDIUM || state == TimeState.REAL_TIME_LOW
        val delayed get() = traffic == 2 || traffic == 3
    }

    data class Leg(
        val kind: LegKind,
        val lineId: Int = -1,
        val tripId: Long = 0,
        val dep: Long = 0,
        val arr: Long = 0,
        val stops: List<Int> = emptyList(),
        val fromStop: Int = -1,
        val toStop: Int = -1,
        val meters: Int = 0,
        val nextDeps: List<Departure> = emptyList(),
        val shortName: String = "",
        val pathway: Boolean = false,
        val fare: Int = -1,
        val currency: String = "",
        val shape: List<Pair<Double, Double>> = emptyList(),
        val alertCategory: Int = 0,
        val alertText: String = "",
        val taxiPickup: Pair<Double, Double>? = null,
        val taxiDropoff: Pair<Double, Double>? = null,
        val alternatives: List<Leg> = emptyList(),
        val alternativeLineIds: List<Int> = emptyList(),
        // A shared vehicle picked up in the street (a dockless leg): the vehicle type, 1 bike, 2 e-bike,
        // 3 e-scooter, 4 kick scooter, 5 moped or -1 car, and who runs it. 0 for every other leg.
        val shared: Int = 0,
        val sharedBy: String = "",
    ) {
        val options get() = alternatives.ifEmpty { listOf(this) }
        // Every line that can take the ride by when it leaves, as a route card lists them: the card's own first of those
        // leaving with it, and after one leaving a little before it.
        val lineChoices get() = (options.sortedBy { it.dep }.map { it.lineId } + alternativeLineIds).distinct()
        val minutes get() = (((arr - dep) / 60).toInt()).coerceAtLeast(0)
    }

    class Itinerary(
        val guid: String,
        val group: Int,
        val legs: List<Leg>,
        val dep: Long,
        val arr: Long,
        val fare: Int = -1,
        val currency: String = "",
        val co2g: Int = -1,
        val accessible: Boolean = false,
        val tags: List<String> = emptyList(),
        val section: String = "",
        val sectionId: Int = -1,
    ) {
        // The day off a route sets out after, when the search falls on one and nothing runs: the planner tags it.
        val after: DayOff? get() = when {
            "after_shabbat" in tags -> DayOff.SHABBAT
            "after_holiday" in tags -> DayOff.HOLIDAY
            else -> null
        }
        val durationMin get() = ((arr - dep) / 60).toInt()
        val rides get() = legs.filter { it.kind == LegKind.RIDE }
        val transfers get() = (rides.size - 1).coerceAtLeast(0)
    }

    fun boardingOptions(ride: Leg, wait: Leg?): List<Pair<Leg, Leg?>> = ride.options.map { option ->
        option to wait?.options?.firstOrNull { it.lineId == option.lineId }
    }

    class Section(
        val id: Int,
        val name: String,
        val maxItems: Int,
        val type: Int,
        val index: Int,
    )

    class Plan(
        val itineraries: List<Itinerary> = emptyList(),
        val sections: List<Section> = emptyList(),
    ) {
        private val byId = sections.associateBy { it.id }

        // NO_GROUPING sections hold a station-to-station timetable, not a way there: they show as a
        // "View schedules" card, never among the routes.
        private fun isSchedule(it: Itinerary) = byId[it.sectionId]?.type == SECTION_NO_GROUPING

        fun schedule(): Itinerary? = itineraries.firstOrNull(::isSchedule)

        // A taxi card goes with the taxi section, near the end.
        fun isTaxiCard(it: Itinerary) =
            it.legs.any { l -> l.kind == LegKind.TAXI } && it.legs.none { l -> l.kind == LegKind.RIDE }

        private fun sectionOf(it: Itinerary): Int = if (isTaxiCard(it)) SECTION_TAXI else it.sectionId

        fun laidOut(): List<Itinerary> {
            val itineraries = itineraries.filterNot(::isSchedule)
            if (sections.isEmpty()) return itineraries
            val seen = HashMap<Int, Int>()
            return itineraries
                .sortedBy { byId[sectionOf(it)]?.index ?: Int.MAX_VALUE }
                .filter {
                    val cap = byId[it.sectionId]?.maxItems ?: Int.MAX_VALUE
                    val n = (seen[it.sectionId] ?: 0) + 1
                    seen[it.sectionId] = n
                    n <= cap
                }
        }

        // A route's section heading as Kav is shown now: Kav names its own sections by id (an older Kav saved their
        // names, which give way); a section it doesn't name keeps the name it came with.
        fun heading(it: Itinerary): String = byId[sectionOf(it)]?.let { s -> KavPlan.sectionName(s.id) ?: s.name }.orEmpty()
    }

    // The suggested routes, the list itself, in Kav's server's plans.
    const val SECTION_MAIN = 1
    const val SECTION_NO_GROUPING = 15
    // The "Taxi & Ride Hailing" section, the same id in every plan.
    const val SECTION_TAXI = 1581
    const val TIME_ARRIVAL = 1
    const val TIME_DEPARTURE = 2
    const val TIME_LAST = 3

    class PlannerRefusal(
        val code: Int,
        val title: String,
        val detail: String,
    ) : RuntimeException("TripPlanner refused ($code): $title")

    const val PLAN_TOO_CLOSE = 10
    const val PLAN_NO_ROUTES = 11
    const val PLAN_TOO_FAR = 2
    const val PLAN_NO_COVERAGE = 1032

    class Resolved(
        val lines: Map<Int, LineInfo> = emptyMap(),
        val stops: Map<Int, StopInfo> = emptyMap(),
        val routeTypes: Map<Int, Int> = emptyMap(),
        val live: Map<ArrivalKey, Arrival> = emptyMap(),
        val shapes: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
        val pollSecs: Int = 20,
        val patterns: Map<Int, List<Int>> = emptyMap(),
        // How far each ride runs from the plan, in seconds, by the last stop with a live time for it.
        val delays: Map<Long, Long> = emptyMap(),
    ) {
        fun arrival(ride: Leg): Arrival? = live[ArrivalKey(ride.fromStop, ride.tripId)]

        fun withLive(live: Map<ArrivalKey, Arrival>) = Resolved(lines, stops, routeTypes, live, shapes, pollSecs, patterns, delays)

        // After live times that couldn't be refreshed: only those a vehicle reported in the last two minutes stay live.
        // Kept as they were, a minutes-old time would go on showing as live. The timetable's times, of buses not out
        // yet, stay as they are, beside the live ones.
        fun aged(now: Long = System.currentTimeMillis() / 1000) =
            withLive(live.filterValues { it.rtUtc <= 0 || it.sampleUtc > 0 && now - it.sampleUtc <= STALE_LIVE_S })

        // The live time for the ride at the rider's stop. A line's last stop may have none.
        fun alightLiveAt(ride: Leg): Long? = live[ArrivalKey(ride.toStop, ride.tripId)]?.rtUtc?.takeIf { it > 0 }

        // When the ride gets to the rider's stop: the live time, or the plan moved by how far the ride runs from it.
        fun alightAt(ride: Leg): Long = alightLiveAt(ride) ?: (ride.arr + (delays[ride.tripId] ?: 0L))

        // Each bus once, the ride's own first: two rides of a line from the stop can both be found to take one bus.
        fun liveFor(ride: Leg): List<Arrival> = live.values.filter { a ->
            a.stopId == ride.fromStop && a.lineId == ride.lineId &&
                patterns[a.patternId]?.contains(ride.toStop) == true
        }.sortedBy { if (it.tripId == ride.tripId) 0 else 1 }.distinctBy { it.vehicleId.ifEmpty { it.tripId.toString() } }
            .sortedBy { it.departure().timeUtc }

        // The live platform; else the one the plan gives this very train, which plans can name well ahead (a live
        // time without a platform mustn't hide it); else a bus station's from the timetable.
        fun platform(ride: Leg, wait: Leg? = null): String =
            arrival(ride)?.platform?.ifBlank { null }
                ?: departures(ride, wait).firstOrNull { it.tripId == ride.tripId && it.platform.isNotBlank() }?.platform
                ?: wait?.options?.firstOrNull { it.lineId == ride.lineId }?.nextDeps
                    ?.firstOrNull { it.tripId == ride.tripId && it.platform.isNotBlank() }?.platform
                ?: plannedPlatform(ride, ride.fromStop)

        // The platform a train stops at where the rider gets off it, once the live times there have it.
        fun alightPlatform(ride: Leg): String = live[ArrivalKey(ride.toStop, ride.tripId)]?.platform.orEmpty()

        // A bus station's platform from the timetable, which numbers them. A train's comes only with its live times.
        private fun plannedPlatform(ride: Leg, stopId: Int): String {
            if (isTrain(ride)) return ""
            val code = stop(stopId)?.code?.toIntOrNull()?.takeIf { it > 0 } ?: return ""
            val number = ride.shortName.ifBlank { line(ride.lineId)?.number.orEmpty() }.trim().ifEmpty { return "" }
            return uk.noammm.kav.Loaded.net?.platformOf(code, number, KavLive.secondOfDay(ride.dep)).orEmpty()
        }

        fun isTrain(ride: Leg): Boolean = routeType(line(ride.lineId)?.agencyId ?: -1) == 2

        // A route card's departures: every way of taking its first ride (its line and the others from the same stop to
        // the same stop), each line's from where the ride's own start.
        fun cardDepartures(ride: Leg, wait: Leg?, now: Long = System.currentTimeMillis() / 1000): List<Departure> {
            val since = if (ride.dep - now <= 3600) now - 60 else ride.dep - 10 * 60
            return boardingOptions(ride, wait).flatMap { (option, boarding) -> departures(option, boarding, now, since) }
        }

        fun departures(ride: Leg, wait: Leg?, now: Long = System.currentTimeMillis() / 1000, since: Long? = null): List<Departure> {
            val boarding = wait?.options?.firstOrNull { it.lineId == ride.lineId }
            val future = boarding?.nextDeps.orEmpty().filter { it.tripId != 0L }
            val selected = future.firstOrNull { it.tripId == ride.tripId }
                ?: ride.nextDeps.firstOrNull()
                ?: boarding?.nextDeps?.firstOrNull { it.tripId == 0L }?.copy(tripId = ride.tripId)
                ?: Departure(ride.tripId, ride.dep)
            val alert = boarding?.alertCategory ?: 0
            // A bus its live record says is canceled for the stop (status 3) is shown so, though
            // the plan still has it coming.
            val planned = (listOf(selected.copy(alert = alert)) + future.filter { it.tripId != ride.tripId })
                .distinctBy { it.tripId to if (it.tripId == 0L) it.timeUtc else 0L }
                .map { d -> if (d.tripId != 0L && live[ArrivalKey(ride.fromStop, d.tripId)]?.status == 3) d.copy(status = 3) else d }
            // Live arrivals cover the next hour or so. For a trip leaving within it they count from now, catchable or
            // not; one planned later keeps the buses from shortly before its own, and past
            // what they cover its own times. A card's other lines start where its ride's own do (since).
            val mine = { d: Departure -> selected.tripId != 0L && d.tripId == selected.tripId }
            val from = since ?: if (selected.timeUtc - now <= 3600) now - 60 else selected.timeUtc - 10 * 60
            val arrivals = liveFor(ride).filter { a -> a.departure(alert).let { mine(it) || it.timeUtc >= from } }
            val current = arrivals.map { it.departure(alert) }
            // Each train run is a route of its own in the ministry's timetable, so the live times are the train's own
            // and those of the plan's other trains found on the board: the rest of its line's trains before and after
            // it come from the plan, the train's own too once it has left.
            if (isTrain(ride) && current.isNotEmpty()) {
                val known = current.map { it.tripId }.toSet()
                return (current + planned.filter { it.tripId !in known && it.timeUtc >= from }).sortedBy { it.timeUtc }
            }
            if (current.any { mine(it) || it.timeUtc >= selected.timeUtc }) {
                // Past what the stop's live times cover, the plan's own later ones. Kav's own timetable rows on a stop's
                // board, no bus in their key, cover nothing: beside them come the plan's, but one at the same time as a
                // row, both being Kav's planner's.
                val known = current.map { it.tripId }.toSet()
                val covered = arrivals.filter { !it.vehicleId.endsWith("_") }.maxOfOrNull { it.departure(alert).timeUtc }
                    ?.let { maxOf(now + LIVE_AHEAD_S, it) }
                val times = current.map { it.timeUtc }
                return (current + planned.filter { p ->
                    p.tripId !in known && times.none { kotlin.math.abs(it - p.timeUtc) <= 60 } &&
                        (if (covered != null) p.timeUtc > covered else mine(p) || p.timeUtc >= from)
                }).sortedBy { it.timeUtc }
            }
            // A line's departures before its ride's stay off for a ride over an hour away: a later route's list starts
            // from its own.
            return planned.filter { mine(it) || it.timeUtc >= from }.sortedBy { it.timeUtc }
        }

        fun line(id: Int): LineInfo? = lines[id]
        fun stop(id: Int): StopInfo? = stops[id]
        // As shown (StopNames).
        fun stopName(id: Int): String? = stops[id]?.shown()
        fun routeType(agencyId: Int): Int = routeTypes[agencyId] ?: 3
        fun agencyName(agencyId: Int): String? = KavPlan.agencyName(agencyId)

    }

    // How far each ride runs from the plan: the live time at the stop where the rider gets off, else where they
    // board. Against the plan, not the timetable: the plan already counts the delay expected when it was planned.
    internal fun delaysOf(list: List<Itinerary>, live: Map<ArrivalKey, Arrival>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (i in list) for (leg in i.legs) for (ride in leg.options) {
            if (ride.kind != LegKind.RIDE || ride.tripId == 0L) continue
            val off = live[ArrivalKey(ride.toStop, ride.tripId)]?.rtUtc?.takeIf { it > 0 }?.let { it - ride.arr }
            val on = live[ArrivalKey(ride.fromStop, ride.tripId)]?.rtUtc?.takeIf { it > 0 }?.let { it - ride.dep }
            (off ?: on)?.let { out[ride.tripId] = it }
        }
        return out
    }

    val ALL_ROUTE_TYPES = listOf(0, 1, 2, 3, 4, 5, 6, 7)

    // How old a vehicle's report can be for its time to stay live when a refresh fails.
    const val STALE_LIVE_S = 120L

    // How far ahead a stop's live times go: Kav's server asks the ministry for its next hour and a half.
    const val LIVE_AHEAD_S = 5400L
}
