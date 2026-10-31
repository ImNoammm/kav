package uk.noammm.kav.data

import uk.noammm.kav.ui.metres

// Where Kav's own planner puts a town the rider picked ("Jerusalem"): its hub, found in the timetable alone. A town
// reaches as far as its stops do in gaps of 600 m at most, so a village whose stops carry its regional council's name
// stays in the village, and train stations, which have no town in the timetable, join their nearest stop's.
object TownHub {
    private val CENTRAL = Regex("^(ת\\. ?מרכזית|תחנה מרכזית|התחנה המרכזית|מרכזית )")

    // A train station this close to the hub moves the start toward it, to STATION_START from it: the planner boards its
    // trains from there without a walk (it does from about 50 m), and buses keep about the hub's own walk. Jerusalem's
    // central bus station is 60 m from Yitzhak Navon, whose platforms are a 12 min walk from the street.
    private const val STATION_NEAR = 100.0
    private const val STATION_START = 40.0

    // Where a town picked at that point starts: its hub's stop, or the way from it to a train station. The station is
    // the town's main one, by its weekly trains, when as many departures leave within 150 m of it as of the hub (Tel
    // Aviv's HaShalom, 1.7 km from its hub, where trains to and from the town otherwise end and start with a 24 min
    // walk); else one beside the hub. Null when the timetable has no town there.
    fun point(net: Net, lat: Double, lon: Double): Pair<Double, Double>? {
        val code = townAt(net, lat, lon)
        if (code < 0) return null
        val town = townStops(net, lat, lon, code) ?: return null
        val hub = hubOf(net, lat, lon, town)
        val hLat = net.lat[hub]
        val hLon = net.lon[hub]
        val types = net.stopType
        val station = mainStation(net, town).takeIf { it >= 0 && town.area.getValue(it) >= town.area.getValue(hub) }
            ?: nearest(net, hLat, hLon, STATION_NEAR) { types[it] == 2 }
        if (station < 0) return hLat to hLon
        val sLat = net.lat[station]
        val sLon = net.lon[station]
        val f = STATION_START / metres(hLat, hLon, sLat, sLon)
        return if (f >= 1.0) hLat to hLon else sLat + (hLat - sLat) * f to sLon + (hLon - sLon) * f
    }

    // The hub's stop for a town picked at that point, or -1 when the timetable has no town there.
    fun of(net: Net, lat: Double, lon: Double): Int =
        townAt(net, lat, lon).let { code -> if (code < 0) -1 else townStops(net, lat, lon, code)?.let { hubOf(net, lat, lon, it) } ?: -1 }

    // The town of the served stop nearest the point, within 1 km; -1 when there's none.
    private fun townAt(net: Net, lat: Double, lon: Double): Int {
        val served = net.stopType
        val near = nearest(net, lat, lon, 1000.0) { served[it] >= 0 && net.city[net.cityOf[it]].isNotBlank() }
        return if (near < 0) -1 else net.cityOf[near]
    }

    // A town's stops that run, linked to the one nearest the point by gaps of 600 m at most, and each one's area: the
    // weekly departures of those within 150 m of it.
    private class Town(val stops: List<Int>, val area: Map<Int, Int>)

    private fun townStops(net: Net, lat: Double, lon: Double, town: Int): Town? {
        val blank = BooleanArray(net.city.size) { net.city[it].isBlank() }
        val week = HashMap<Int, Int>()
        fun departures(s: Int) = week.getOrPut(s) {
            var n = 0
            for (i in net.dStart[s] until net.dStart[s + 1]) n += Integer.bitCount(net.tripDays[net.tripOf(net.cST[net.dConn[i]])] and 0x7f)
            n
        }
        val joined = (0 until net.nStops).filter { s ->
            blank[net.cityOf[s]] && nearest(net, net.lat[s], net.lon[s], 400.0) { !blank[net.cityOf[it]] }
                .let { it >= 0 && net.cityOf[it] == town }
        }
        val all = ((0 until net.nStops).filter { net.cityOf[it] == town } + joined).filter { departures(it) > 0 }
        if (all.isEmpty()) return null
        val start = all.minBy { metres(lat, lon, net.lat[it], net.lon[it]) }
        val linked = hashSetOf(start)
        val todo = arrayListOf(start)
        while (todo.isNotEmpty()) {
            val s = todo.removeAt(todo.size - 1)
            for (x in all) {
                if (x !in linked && Math.abs(net.lat[x] - net.lat[s]) < 0.006 &&
                    metres(net.lat[s], net.lon[s], net.lat[x], net.lon[x]) <= 600.0) { linked.add(x); todo.add(x) }
            }
        }
        val stops = all.filter { it in linked }
        val area = stops.associateWith { s ->
            stops.sumOf { x ->
                if (Math.abs(net.lat[x] - net.lat[s]) < 0.0015 && metres(net.lat[s], net.lon[s], net.lat[x], net.lon[x]) <= 150.0) departures(x) else 0
            }
        }
        return Town(stops, area)
    }

    private fun hubOf(net: Net, lat: Double, lon: Double, town: Town): Int {
        val stops = town.stops
        val area = town.area
        val top = area.values.max()
        stops.filter { CENTRAL.containsMatchIn(net.name[it]) }.maxByOrNull { area.getValue(it) }
            ?.takeIf { area.getValue(it) >= 0.5 * top }?.let { return it }
        stops.minBy { metres(lat, lon, net.lat[it], net.lon[it]) }
            .takeIf { metres(lat, lon, net.lat[it], net.lon[it]) <= 100.0 && area.getValue(it) >= 0.3 * top }?.let { return it }
        return stops.maxBy { area.getValue(it) }
    }

    // The town's train station with the most trains in a week, or -1 when it has none.
    private fun mainStation(net: Net, town: Town): Int {
        fun trains(s: Int): Int {
            var n = 0
            for (i in net.dStart[s] until net.dStart[s + 1]) {
                val t = net.tripOf(net.cST[net.dConn[i]])
                if (net.rType[net.tripRoute[t]] == 2) n += Integer.bitCount(net.tripDays[t] and 0x7f)
            }
            return n
        }
        return town.stops.map { it to trains(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: -1
    }

    private fun nearest(net: Net, lat: Double, lon: Double, reach: Double, ok: (Int) -> Boolean): Int {
        var best = -1
        var bestD = reach
        for (s in 0 until net.nStops) {
            if (Math.abs(net.lat[s] - lat) > 0.03 || Math.abs(net.lon[s] - lon) > 0.04 || !ok(s)) continue
            val d = metres(lat, lon, net.lat[s], net.lon[s])
            if (d < bestD) { best = s; bestD = d }
        }
        return best
    }
}
