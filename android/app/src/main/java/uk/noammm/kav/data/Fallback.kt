package uk.noammm.kav.data

import java.util.Calendar

/**
 * Planning with no connection.
 *
 * The app is online-first: Moovit ranks the trips, prices them and tracks the vehicles.
 * With no network none of that is reachable, and the honest alternative is the timetable
 * already sitting in the APK, so this plans on the device and then dresses the result
 * up as the same [Moovit.Itinerary] the online path produces, which means the results
 * screen, the trip detail and the timeline all keep working unchanged.
 *
 * What it cannot fake, and does not: fares, CO2, service alerts, live arrivals or a
 * vehicle position. Those come back as absent rather than as invented numbers, and the
 * UI already knows how to draw an itinerary that has none of them.
 */
object Fallback {

    /** Local midnight, because the offline planner counts in seconds since it. */
    private fun midnightUtc(): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 1000
    }

    private fun nearest(net: Net, lat: Double, lon: Double): Int {
        var best = Double.MAX_VALUE; var idx = -1
        for (i in net.stops.indices) {
            val dy = (net.stops[i].lat - lat) * 111.0
            val dx = (net.stops[i].lon - lon) * 93.0
            val d = dy * dy + dx * dx
            if (d < best) { best = d; idx = i }
        }
        return idx
    }

    /**
     * Plan [from] → [to] on the device. Returns the itineraries plus the names and line
     * numbers they need, in the same shape the online path hands to the UI.
     */
    fun plan(
        net: Net,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        count: Int = 4,
    ): Pair<List<Moovit.Itinerary>, Moovit.Resolved> {
        val o = nearest(net, from.first, from.second)
        val d = nearest(net, to.first, to.second)
        if (o < 0 || d < 0 || o == d) return emptyList<Moovit.Itinerary>() to Moovit.Resolved()

        val base = midnightUtc()
        val c = Calendar.getInstance()
        var t = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)

        val lines = LinkedHashMap<Int, Moovit.LineInfo>()
        val stops = LinkedHashMap<Int, Moovit.StopInfo>()
        val types = LinkedHashMap<Int, Int>()
        val out = ArrayList<Moovit.Itinerary>(count)

        repeat(count) {
            val j = net.plan(o, d, t) ?: return@repeat
            val legs = ArrayList<Moovit.Leg>(j.legs.size * 2)
            for (l in j.legs) {
                fun note(stop: Int) {
                    if (stop in 0 until net.stops.size && stop !in stops) {
                        val code = net.stops[stop].code.takeIf { it > 0 }?.toString() ?: ""
                        stops[stop] = Moovit.StopInfo(stop, net.stops[stop].name, code)
                    }
                }
                note(l.from); note(l.to)
                if (l.ride) {
                    // the offline route index doubles as the line id here: nothing in the
                    // UI reads it except through Resolved, which is built right below
                    val route = l.route
                    if (route !in lines) {
                        val rt = net.routes.getOrNull(route)?.type ?: 3
                        lines[route] = Moovit.LineInfo(
                            groupId = -1,
                            number = net.routes.getOrNull(route)?.short ?: "",
                            agencyId = route,
                            origin = net.stops.getOrNull(l.from)?.name ?: "",
                            destination = net.stops.getOrNull(l.to)?.name ?: "",
                            caption = "",
                        )
                        types[route] = rt
                    }
                    // a wait leg so the card can say when the next one leaves
                    legs.add(
                        Moovit.Leg(
                            Moovit.LegKind.WAIT, lineId = route,
                            dep = base + l.dep, arr = base + l.dep,
                            fromStop = l.from, toStop = l.to,
                            nextDeps = listOf(
                                Moovit.Departure(
                                    tripId = l.trip.toLong(), staticUtc = base + l.dep,
                                ),
                            ),
                        ),
                    )
                    legs.add(
                        Moovit.Leg(
                            Moovit.LegKind.RIDE, lineId = route, tripId = l.trip.toLong(),
                            dep = base + l.dep, arr = base + l.arr,
                            stops = listOf(l.from, l.to),
                            fromStop = l.from, toStop = l.to,
                        ),
                    )
                } else {
                    legs.add(
                        Moovit.Leg(
                            Moovit.LegKind.WALK,
                            dep = base + l.dep, arr = base + l.arr,
                            fromStop = l.from, toStop = l.to,
                            meters = metresBetween(net, l.from, l.to),
                        ),
                    )
                }
            }
            if (legs.isNotEmpty()) {
                out.add(
                    Moovit.Itinerary(
                        guid = "offline-${out.size}",
                        group = 2,
                        legs = legs,
                        dep = base + j.depart,
                        arr = base + j.arrive,
                        section = "Planned on your phone",
                    ),
                )
            }
            t = j.depart + 60
        }
        return out to Moovit.Resolved(lines, stops, types)
    }

    private fun metresBetween(net: Net, a: Int, b: Int): Int {
        if (a !in net.stops.indices || b !in net.stops.indices) return 0
        val dy = (net.stops[a].lat - net.stops[b].lat) * 111_000
        val dx = (net.stops[a].lon - net.stops[b].lon) * 93_000
        return Math.sqrt(dy * dy + dx * dx).toInt()
    }
}
