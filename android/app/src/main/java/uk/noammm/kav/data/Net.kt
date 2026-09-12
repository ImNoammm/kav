package uk.noammm.kav.data

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * The KAV3 timetable bundle and the CSA router over it.
 *
 * Nothing here touches Android, it is integer arrays and arithmetic, so it
 * stays testable on a plain JVM.
 */

/** Growable int buffer. */
private class IntVec(cap: Int = 1 shl 16) {
    var a = IntArray(cap)
    var n = 0
    fun push(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun trimmed(): IntArray = a.copyOf(n)
}

const val MIN_CHANGE = 120
const val INF = 0x7fffffff
const val DIRECTION_PAIR_M = 500.0

class Leg(
    val ride: Boolean,
    val trip: Int,
    val route: Int,
    val from: Int,
    val to: Int,
    val dep: Int,
    val arr: Int,
)

class Journey(val depart: Int, val arrive: Int, val legs: List<Leg>) {
    val duration get() = arrive - depart
    val rides get() = legs.count { it.ride }
}

data class Route(val short: String, val long: String, val type: Int) {
    /** Indices into tripRoute/tripStart of every trip on this route, filled once at load. */
    var trips: IntArray = EMPTY
    /** One entry per direction: the ordered stop indices of that direction's longest trip.
     *  Index 0 is this route's own direction; index 1, if present, is the opposite-direction
     *  route's stops (same short name, endpoints swapped). Always has at least one entry. */
    var stops: Array<IntArray> = EMPTY_STOPS
    /** Parallel to [stops]: which underlying route index each direction's stops came from
     *  (this route's own index at 0, the paired route's index at 1). */
    var directions: IntArray = EMPTY
    /** This route's own longest trip: the one whose stop_times produced [stops]'s own
     *  (index-0) entry, so a stop's scheduled time on this line can be looked up without
     *  a fresh scan. -1 if the route has no trips today. */
    var bestTrip: Int = -1
    private companion object {
        val EMPTY = IntArray(0)
        val EMPTY_STOPS: Array<IntArray> = emptyArray()
    }
}

data class Stop(val name: String, val lat: Double, val lon: Double, val code: Int, val cityOf: Int)

class Net private constructor() {

    // stops
    lateinit var stops: Array<Stop>; private set
    lateinit var city: Array<String>; private set

    // routes
    lateinit var routes: Array<Route>; private set

    // trips (CSR of stop_times)
    lateinit var tripRoute: IntArray; private set
    lateinit var tripStart: IntArray; private set
    lateinit var stStop: IntArray; private set
    lateinit var stArr: IntArray; private set
    lateinit var stDep: IntArray; private set

    // connections, departure-sorted
    lateinit var cST: IntArray; private set      // stop_time index
    lateinit var cTrip: IntArray; private set

    // per-stop departure index
    lateinit var dStart: IntArray; private set
    lateinit var dConn: IntArray; private set

    // search haystack
    lateinit var hay: Array<String>; private set

    val nStops get() = stops.size
    val nTrips get() = tripRoute.size
    val nRoutes get() = routes.size

    fun cityOf(s: Int): String = city.getOrElse(stops[s].cityOf) { "" }
    /** "Stop 12345" for display, or null when the feed has no code for this stop. */
    fun stopCode(s: Int): String? = stops[s].code.takeIf { it > 0 }?.let { "Stop $it" }
    fun tripLast(t: Int): Int = stStop[tripStart[t + 1] - 1]

    /* varint reader
       Zigzag, exactly as the exporter writes it. The sign handling is the one
       thing here that must not drift: a wrong decode moves stops kilometres. */
    private lateinit var b: ByteArray
    private var p = 0

    private fun vi(): Int {
        var sh = 0; var r = 0; var x: Int
        do {
            x = b[p++].toInt() and 0xFF
            r = r or ((x and 0x7f) shl sh)
            sh += 7
        } while (x and 0x80 != 0)
        return (r ushr 1) xor -(r and 1)
    }

    private fun vs(): String {
        val n = vi()
        val s = String(b, p, n, StandardCharsets.UTF_8)
        p += n
        return s
    }

    private fun parse(buf: ByteArray) {
        b = buf; p = 0
        val magic = String(b, 0, 4, StandardCharsets.US_ASCII)
        if (magic != "KAV3") throw IllegalArgumentException("bad bundle: $magic")
        p = 4

        val nS = vi(); val nR = vi(); val nT = vi(); val nC = vi()

        city = Array(nC) { vs() }

        var la = 0L; var lo = 0L
        stops = Array(nS) {
            la += vi(); lo += vi()
            val sLat = la / 1e5; val sLon = lo / 1e5
            val sCode = vi(); val sCityOf = vi(); val sName = vs()
            Stop(sName, sLat, sLon, sCode, sCityOf)
        }

        routes = Array(nR) { Route(vs(), vs(), vi()) }

        tripRoute = IntArray(nT); tripStart = IntArray(nT + 1)
        val ss = IntVec(1 shl 21); val sa = IntVec(1 shl 21); val sd = IntVec(1 shl 21)
        for (t in 0 until nT) {
            tripRoute[t] = vi()
            val n = vi()
            var pt = vi(); var ps = 0
            tripStart[t] = ss.n
            for (k in 0 until n) {
                val a = pt + vi(); val d = a + vi(); val s = ps + vi()
                sa.push(a); sd.push(d); ss.push(s)
                pt = d; ps = s
            }
        }
        tripStart[nT] = ss.n
        stStop = ss.trimmed(); stArr = sa.trimmed(); stDep = sd.trimmed()

        // The footpaths and then the road polylines follow. Nothing reads either:
        // the footpath graph existed only so the on-device planner could change
        // between stops, and the map is its own file. Parsing stops here, so
        // neither is ever allocated.
        // Invert tripRoute (trip -> route) into route -> its trips, a counting sort
        // exactly like the connection/departure indices below. Then, once per route,
        // pick the longest trip as the stand-in for "the line" and keep its stop
        // sequence too, so the Lines screen never re-derives it on render.
        val own = arrayOfNulls<IntArray>(nR)
        run {
            val deg = IntArray(nR)
            for (t in 0 until nT) deg[tripRoute[t]]++
            val start = IntArray(nR + 1)
            for (r in 0 until nR) start[r + 1] = start[r] + deg[r]
            val fill = start.copyOf(nR)
            val order = IntArray(nT)
            for (t in 0 until nT) order[fill[tripRoute[t]]++] = t
            for (r in 0 until nR) {
                val trips = order.copyOfRange(start[r], start[r + 1])
                routes[r].trips = trips
                var best = -1; var bestLen = -1
                for (t in trips) {
                    val len = tripStart[t + 1] - tripStart[t]
                    if (len > bestLen) { bestLen = len; best = t }
                }
                routes[r].bestTrip = best
                own[r] = if (best < 0) IntArray(0) else stStop.copyOfRange(tripStart[best], tripStart[best + 1])
            }
        }

        // Pair up opposite-direction routes: same short name, first/last stop within
        // DIRECTION_PAIR_M of one another. Grouping by short name first keeps this
        // cheap even though the feed repeats a short name across many rows (rail
        // direction ~500 times, per searchRoutes). stops[i] and directions[i] are
        // parallel: directions[i] names which route's own trip produced the stop
        // sequence at stops[i], since the two directions' stop sequences (and route
        // metadata) can genuinely differ, not just reverse.
        run {
            // A terminus's boarding platform and its drop-off bay are almost always
            // separate stop_ids a short walk apart, so an exact-stop (or exact-name)
            // match at the far end misses most real pairs; a radius catches them.
            val limit2 = DIRECTION_PAIR_M * DIRECTION_PAIR_M
            fun near(s1: Int, s2: Int): Boolean {
                val dy = (stops[s1].lat - stops[s2].lat) * 111_000
                val dx = (stops[s1].lon - stops[s2].lon) * 93_000
                return dy * dy + dx * dx <= limit2
            }
            val bySort = HashMap<String, MutableList<Int>>()
            for (r in 0 until nR) if (own[r]!!.isNotEmpty()) bySort.getOrPut(routes[r].short) { mutableListOf() }.add(r)
            val paired = BooleanArray(nR)
            for (group in bySort.values) {
                for (i in group.indices) {
                    val a = group[i]
                    if (paired[a]) continue
                    val aStops = own[a]!!
                    for (k in i + 1 until group.size) {
                        val b = group[k]
                        if (paired[b]) continue
                        val bStops = own[b]!!
                        if (near(aStops.first(), bStops.last()) && near(aStops.last(), bStops.first())) {
                            routes[a].stops = arrayOf(aStops, bStops); routes[a].directions = intArrayOf(a, b)
                            routes[b].stops = arrayOf(bStops, aStops); routes[b].directions = intArrayOf(b, a)
                            paired[a] = true; paired[b] = true
                            break
                        }
                    }
                }
            }
            for (r in 0 until nR) if (routes[r].stops.isEmpty()) {
                routes[r].stops = arrayOf(own[r]!!); routes[r].directions = intArrayOf(r)
            }
        }

        // The road polylines follow, for the map. v0 has no map, so parsing stops
        // here and the ~50 MB of geometry is never allocated.

        hay = Array(nS) { (stops[it].name + " " + cityOf(it)).lowercase() }
        b = ByteArray(0)   // let the 25 MB source buffer go before the arrays grow
        buildConnections()
    }

    /* A connection is the hop from stop_time j to j+1, so j and the trip index
       describe it completely:
         dep = stDep[j]  arr = stArr[j+1]  from = stStop[j]  to = stStop[j+1]
       Storing those four separately cost five int arrays per connection; at
       national scale (4.35M connections) that was ~122 MB. This keeps two, and
       drops the `order` permutation by counting-sorting straight into place. */
    private fun buildConnections() {
        val nT = tripRoute.size
        var m = 0; var maxT = 0
        for (t in 0 until nT) {
            val a = tripStart[t]; val z = tripStart[t + 1] - 1
            if (z > a) m += z - a
            for (i in a until z) if (stDep[i] > maxT) maxT = stDep[i]
        }
        val span = maxT + 2
        val cnt = IntArray(span + 2)
        for (t in 0 until nT) {
            var i = tripStart[t]; val z = tripStart[t + 1] - 1
            while (i < z) { cnt[stDep[i] + 1]++; i++ }
        }
        for (i in 0..span) cnt[i + 1] += cnt[i]

        cST = IntArray(m); cTrip = IntArray(m)
        for (t in 0 until nT) {
            var i = tripStart[t]; val z = tripStart[t + 1] - 1
            while (i < z) {
                val q = cnt[stDep[i]]++
                cST[q] = i; cTrip[q] = t
                i++
            }
        }

        // per-stop departures, already in time order
        val nS = stops.size
        dStart = IntArray(nS + 1)
        val deg = IntArray(nS)
        for (i in 0 until m) deg[stStop[cST[i]]]++
        for (s in 0 until nS) dStart[s + 1] = dStart[s] + deg[s]
        val fill = dStart.copyOf(nS)
        dConn = IntArray(m)
        for (i in 0 until m) dConn[fill[stStop[cST[i]]]++] = i
    }



    companion object {
        /** Reads a `.kav` or gzipped `.kav.gz` stream. */
        fun read(input: InputStream): Net {
            val raw = input.buffered().use { it.readBytes() }
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                GZIPInputStream(raw.inputStream(), 1 shl 16).use { it.readBytes() }
            else raw
            return Net().also { it.parse(bytes) }
        }
    }
}
