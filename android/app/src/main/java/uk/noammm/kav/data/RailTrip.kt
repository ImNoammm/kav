package uk.noammm.kav.data

import java.util.Calendar
import java.util.TimeZone

// An Israel Railways journey from the rail card as a trip Kav shows like any other: each train a ride between its
// stations, with the stations between from Kav's timetable when it has that run, and a change of trains a wait.
object RailTrip {
    // Ids of their own, above Kav's planner's (KavPlan.isKav holds for them too).
    private const val STOPS = 2_100_000_000
    private const val LINES = 2_110_000_000

    private val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    // A train of one of these trips: its line is the railway's run, not one of Kav's lines.
    fun owns(lineId: Int): Boolean = lineId >= LINES

    fun owns(trip: Transit.Itinerary): Boolean = trip.guid.startsWith("rail-")

    // How often the railway is asked again about a trip under way.
    private const val POLL_S = 60

    // Whether a train is boarded across the platform the train before it comes in at, as the railway said when last asked,
    // by train and boarding stop: a trip read back from its file has it again once the railway is asked.
    private val islands = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Boolean>()

    fun across(ride: Transit.Leg): Boolean = islands[ride.tripId to ride.fromStop] == true

    // agency: the railway's id in the plan the card came from, so its trains get its name and colours. Each train comes
    // with the railway's forecast where it's boarded and left, and its platforms, as live times do, and the waits with
    // the railway's notices about their trains.
    fun of(net: Net, journey: IsraelRail.Journey, agency: Int): Pair<Transit.Itinerary, Transit.Resolved>? {
        if (journey.trains.isEmpty()) return null
        val stops = HashMap<Int, Transit.StopInfo>()
        val lines = HashMap<Int, Transit.LineInfo>()
        val legs = ArrayList<Transit.Leg>()
        val live = HashMap<Transit.ArrivalKey, Transit.Arrival>()
        val patterns = HashMap<Int, List<Int>>()
        val delays = HashMap<Long, Long>()
        val asked = System.currentTimeMillis() / 1000
        fun stop(s: Int): Int = (STOPS + s).also { id ->
            stops.getOrPut(id) { Transit.StopInfo(id, net.stopName(s), net.code[s].toString(), net.lat[s], net.lon[s]) }
        }
        var lastArr = journey.dep
        journey.trains.forEachIndexed { k, train ->
            val from = stationStops(net, train.from)
            val to = stationStops(net, train.to)
            if (from.isEmpty() || to.isEmpty()) return null
            val run = runOf(net, train, from, to)
            val path = run?.let { (_, i, j) -> (i..j).map { net.stStop[it] } } ?: listOf(from.first(), to.first())
            val ids = path.map(::stop)
            val line = LINES + train.number
            val tripId = train.number.toLong()
            val last = run?.let { (t, _, _) -> net.stStop[net.tripStart[t + 1] - 1] } ?: to.first()
            val first = run?.let { (t, _, _) -> net.stStop[net.tripStart[t]] } ?: from.first()
            // No group: the railway's notices are shown whole.
            lines[line] = Transit.LineInfo(0, train.number.toString(), agency, net.stopName(first), net.stopName(last), "")
            val leaves = train.late?.let { train.dep + it * 60L } ?: 0L
            val reaches = (train.lateAtEnd ?: train.late)?.let { train.arr + it * 60L } ?: 0L
            val late = train.late ?: 0
            val status = if (late > 0) 2 else if (late < 0) 4 else 0
            fun arrival(at: Int, planned: Long, forecast: Long, platform: String) = Transit.Arrival(
                at, line, tripId, planned, forecast, 0, status, 0, 0, false, false, false,
                sampleUtc = asked, patternId = train.number, platform = platform,
            )
            live[Transit.ArrivalKey(ids.first(), tripId)] = arrival(ids.first(), train.dep, leaves, train.fromPlatform)
            live[Transit.ArrivalKey(ids.last(), tripId)] = arrival(ids.last(), train.arr, reaches, train.toPlatform)
            patterns[train.number] = ids
            if (reaches > 0) delays[tripId] = reaches - train.arr
            if (k > 0) islands[tripId to ids.first()] = train.samePlatform
            val notices = journey.notices.filter { train.number in it.trains || (k == 0 && it.trains.isEmpty()) }
            legs += Transit.Leg(
                Transit.LegKind.WAIT, lineId = line, dep = lastArr, arr = train.dep, fromStop = ids.first(), toStop = ids.first(),
                alertCategory = if (notices.isEmpty()) 0 else if (notices.any { it.severe }) 4 else 3,
                alertText = notices.joinToString("\n\n") { it.text },
            )
            legs += Transit.Leg(
                Transit.LegKind.RIDE, lineId = line, tripId = tripId, dep = train.dep, arr = train.arr,
                stops = ids, fromStop = ids.first(), toStop = ids.last(),
                nextDeps = listOf(Transit.Departure(tripId, train.dep, rtUtc = leaves, status = status, platform = train.fromPlatform)),
                shape = path.map { net.lat[it] to net.lon[it] },
            )
            lastArr = train.arr
        }
        // As the railway's own list says it, beside the trip's other facts.
        val late = journey.trains.mapNotNull { it.late }.maxOrNull()?.takeIf { it > 0 }
        val trip = Transit.Itinerary(
            guid = "rail-" + journey.trains.joinToString("-") { "${it.number}@${it.dep}" },
            group = 2, legs = legs, dep = journey.dep, arr = journey.arr,
            tags = listOfNotNull(late?.let(KavPlan::lateTag)),
        )
        return trip to Transit.Resolved(lines, stops, mapOf(agency to 2), live, pollSecs = POLL_S, patterns = patterns, delays = delays)
    }

    // A trip of these under way, its trains' forecasts and platforms asked of the railway again: the same trains on the
    // same day, between the first train's station and the last one's. As it was when the railway can't be reached.
    fun live(trip: Transit.Itinerary, prev: Transit.Resolved, net: Net?): Transit.Resolved {
        val timetable = net ?: return prev
        val rides = trip.rides
        fun station(stopId: Int) = timetable.code.getOrNull(stopId - STOPS)?.let(IsraelRail::stationOf)
        val from = rides.firstOrNull()?.let { station(it.fromStop) } ?: return prev
        val to = rides.lastOrNull()?.let { station(it.toStop) } ?: return prev
        val numbers = rides.map { it.tripId.toInt() }
        val journey = runCatching { IsraelRail.search(from, to, rides.first().dep * 1000) }.getOrNull()
            ?.firstOrNull { j -> j.trains.map { it.number } == numbers } ?: return prev
        val agency = prev.lines[rides.first().lineId]?.agencyId ?: return prev
        val fresh = of(timetable, journey, agency)?.second ?: return prev
        return Transit.Resolved(prev.lines, prev.stops, prev.routeTypes, fresh.live, prev.shapes, POLL_S, fresh.patterns, fresh.delays)
    }

    // A station's stops in Kav's timetable: one for each of its platforms, all with the station's code.
    private fun stationStops(net: Net, station: Int): List<Int> {
        val code = IsraelRail.codeOf(station) ?: return emptyList()
        return (0 until net.nStops).filter { net.code[it] == code }
    }

    // The timetable's run of this train: a train trip leaving one of the first station's stops at its time and
    // reaching one of the last's at its time, on that day (or the day before, for a trip that runs past midnight).
    // Its index and where the two stations are in its stop times.
    private fun runOf(net: Net, train: IsraelRail.Train, from: List<Int>, to: List<Int>): Triple<Int, Int, Int>? {
        val at = Calendar.getInstance(ISRAEL).apply { timeInMillis = train.dep * 1000 }
        val day = at.get(Calendar.DAY_OF_WEEK) - 1
        val midnight = (at.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        val fromSet = from.toHashSet()
        val toSet = to.toHashSet()
        for ((runDay, shift) in listOf(day to 0L, (day + 6) % 7 to 86_400L)) {
            val dep = train.dep - midnight + shift
            val arr = train.arr - midnight + shift
            for (t in 0 until net.tripRoute.size) {
                if (net.rType[net.tripRoute[t]] != 2 || !net.runsOn(t, runDay)) continue
                val end = net.tripStart[t + 1]
                var i = net.tripStart[t]
                while (i < end && !(net.stStop[i] in fromSet && kotlin.math.abs(net.stDep[i] - dep) <= 120)) i++
                if (i >= end) continue
                var j = i + 1
                while (j < end && !(net.stStop[j] in toSet && kotlin.math.abs(net.stDep[j] - arr) <= 300)) j++
                if (j < end) return Triple(t, i, j)
            }
        }
        return null
    }
}
