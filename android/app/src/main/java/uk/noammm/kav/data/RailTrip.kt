package uk.noammm.kav.data

import java.util.Calendar
import java.util.TimeZone

// An Israel Railways journey from the rail card as a trip Kav shows like any other: each train a ride between its
// stations, with the stations between from Kav's timetable when it has that run, and a change of trains a wait.
object RailTrip {
    // Ids of their own, above Kav's planner's (KavPlan.isKav holds for them too), so nothing takes them for Moovit's.
    private const val STOPS = 2_100_000_000
    private const val LINES = 2_110_000_000

    private val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    // A train of one of these trips: its line is the railway's run, not one of Moovit's or Kav's lines.
    fun owns(lineId: Int): Boolean = lineId >= LINES

    // agency: the railway's id in the plan the card came from, so its trains get its name and colours.
    fun of(net: Net, journey: IsraelRail.Journey, agency: Int): Pair<Moovit.Itinerary, Moovit.Resolved>? {
        if (journey.trains.isEmpty()) return null
        val stops = HashMap<Int, Moovit.StopInfo>()
        val lines = HashMap<Int, Moovit.LineInfo>()
        val legs = ArrayList<Moovit.Leg>()
        fun stop(s: Int): Int = (STOPS + s).also { id ->
            stops.getOrPut(id) { Moovit.StopInfo(id, net.stopName(s), net.code[s].toString(), net.lat[s], net.lon[s]) }
        }
        var lastArr = journey.dep
        for (train in journey.trains) {
            val from = stationStops(net, train.from)
            val to = stationStops(net, train.to)
            if (from.isEmpty() || to.isEmpty()) return null
            val run = runOf(net, train, from, to)
            val path = run?.let { (_, i, j) -> (i..j).map { net.stStop[it] } } ?: listOf(from.first(), to.first())
            val ids = path.map(::stop)
            val line = LINES + train.number
            val last = run?.let { (t, _, _) -> net.stStop[net.tripStart[t + 1] - 1] } ?: to.first()
            val first = run?.let { (t, _, _) -> net.stStop[net.tripStart[t]] } ?: from.first()
            lines[line] = Moovit.LineInfo(line, train.number.toString(), agency, net.stopName(first), net.stopName(last), "")
            legs += Moovit.Leg(Moovit.LegKind.WAIT, lineId = line, dep = lastArr, arr = train.dep, fromStop = ids.first(), toStop = ids.first())
            legs += Moovit.Leg(
                Moovit.LegKind.RIDE, lineId = line, tripId = train.number.toLong(), dep = train.dep, arr = train.arr,
                stops = ids, fromStop = ids.first(), toStop = ids.last(),
                nextDeps = listOf(Moovit.Departure(train.number.toLong(), train.dep, platform = train.fromPlatform)),
                shape = path.map { net.lat[it] to net.lon[it] },
            )
            lastArr = train.arr
        }
        val trip = Moovit.Itinerary(
            guid = "rail-" + journey.trains.joinToString("-") { "${it.number}@${it.dep}" },
            group = 2, legs = legs, dep = journey.dep, arr = journey.arr,
        )
        return trip to Moovit.Resolved(lines, stops, mapOf(agency to 2))
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
