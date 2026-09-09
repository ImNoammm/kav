package uk.noammm.kav.data

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * The KAV3 timetable bundle and the CSA router over it.
 *
 * Transcribed from webui/app.js: same binary file, same algorithm, no
 * reinterpretation. Nothing here touches Android, it is integer arrays and
 * arithmetic, so it stays testable on a plain JVM.
 */

/** Growable int buffer, the `Vec` of webui/app.js. */
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

class Net private constructor() {

    // stops
    lateinit var name: Array<String>; private set
    lateinit var lat: DoubleArray; private set
    lateinit var lon: DoubleArray; private set
    lateinit var code: IntArray; private set
    lateinit var cityOf: IntArray; private set
    lateinit var city: Array<String>; private set

    // routes
    lateinit var rShort: Array<String>; private set
    lateinit var rLong: Array<String>; private set
    lateinit var rType: IntArray; private set

    // trips (CSR of stop_times)
    lateinit var tripRoute: IntArray; private set
    lateinit var tripStart: IntArray; private set
    lateinit var stStop: IntArray; private set
    lateinit var stArr: IntArray; private set
    lateinit var stDep: IntArray; private set

    // footpaths
    lateinit var xStart: IntArray; private set
    lateinit var xTo: IntArray; private set
    lateinit var xW: IntArray; private set

    // connections, departure-sorted
    lateinit var cST: IntArray; private set      // stop_time index
    lateinit var cTrip: IntArray; private set
    lateinit var bucket: IntArray; private set   // first connection departing >= t
    var nConn = 0; private set

    /** Size of the decoded bundle, for the honest number in Settings. */
    var sourceBytes = 0L; private set

    // per-stop departure index
    lateinit var dStart: IntArray; private set
    lateinit var dConn: IntArray; private set

    // search haystack
    lateinit var hay: Array<String>; private set

    val nStops get() = lat.size
    val nTrips get() = tripRoute.size
    val nRoutes get() = rShort.size

    fun cityOf(s: Int): String = city.getOrElse(cityOf[s]) { "" }
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

        name = Array(nS) { "" }
        lat = DoubleArray(nS); lon = DoubleArray(nS)
        code = IntArray(nS); cityOf = IntArray(nS)
        var la = 0L; var lo = 0L
        for (i in 0 until nS) {
            la += vi(); lo += vi()
            lat[i] = la / 1e5; lon[i] = lo / 1e5
            code[i] = vi(); cityOf[i] = vi(); name[i] = vs()
        }

        rShort = Array(nR) { "" }; rLong = Array(nR) { "" }; rType = IntArray(nR)
        for (i in 0 until nR) { rShort[i] = vs(); rLong[i] = vs(); rType[i] = vi() }

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

        xStart = IntArray(nS + 1)
        val xt = IntVec(1 shl 18); val xw = IntVec(1 shl 18)
        for (i in 0 until nS) {
            xStart[i] = xt.n
            val c = vi()
            for (k in 0 until c) { xt.push(i + vi()); xw.push(vi()) }
        }
        xStart[nS] = xt.n
        xTo = xt.trimmed(); xW = xw.trimmed()

        // The road polylines follow, for the map. v0 has no map, so parsing stops
        // here and the ~50 MB of geometry is never allocated.

        hay = Array(nS) { (name[it] + " " + cityOf(it)).lowercase() }
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
        // cnt[d] is now the first index whose departure is >= d, exactly the bucket
        bucket = cnt.copyOf(span + 1)

        cST = IntArray(m); cTrip = IntArray(m)
        for (t in 0 until nT) {
            var i = tripStart[t]; val z = tripStart[t + 1] - 1
            while (i < z) {
                val q = cnt[stDep[i]]++
                cST[q] = i; cTrip[q] = t
                i++
            }
        }
        nConn = m

        // per-stop departures, already in time order
        val nS = lat.size
        dStart = IntArray(nS + 1)
        val deg = IntArray(nS)
        for (i in 0 until m) deg[stStop[cST[i]]]++
        for (s in 0 until nS) dStart[s + 1] = dStart[s] + deg[s]
        val fill = dStart.copyOf(nS)
        dConn = IntArray(m)
        for (i in 0 until m) dConn[fill[stStop[cST[i]]]++] = i
    }

    /* CSA
       Earliest arrival, stop to stop. Scratch arrays are kept and refilled so a
       query allocates nothing. */
    private var arrT: IntArray? = null
    private lateinit var boardT: IntArray
    private lateinit var viaConn: IntArray
    private lateinit var viaWalk: IntArray
    private lateinit var tripSeen: BooleanArray
    private lateinit var tripBoard: IntArray
    private lateinit var tripBoardT: IntArray

    @Synchronized
    fun plan(from: Int, to: Int, depTime: Int): Journey? {
        val nS = lat.size; val nT = tripRoute.size
        var a0 = arrT
        if (a0 == null) {
            a0 = IntArray(nS); arrT = a0
            boardT = IntArray(nS); viaConn = IntArray(nS); viaWalk = IntArray(nS)
            tripSeen = BooleanArray(nT); tripBoard = IntArray(nT); tripBoardT = IntArray(nT)
        }
        val arrT = a0
        arrT.fill(INF); boardT.fill(INF); viaConn.fill(-1); viaWalk.fill(-1); tripSeen.fill(false)

        arrT[from] = depTime; boardT[from] = depTime
        for (x in xStart[from] until xStart[from + 1]) {
            val j = xTo[x]; val a = depTime + xW[x]
            if (a < arrT[j]) { arrT[j] = a; boardT[j] = a; viaWalk[j] = from }
        }

        var i = bucket[minOf(depTime, bucket.size - 1)]
        while (i < nConn) {
            val j = cST[i]; val d = stDep[j]
            if (d >= arrT[to]) break
            val t = cTrip[i]; val f = stStop[j]
            if (!tripSeen[t]) {
                if (boardT[f] > d) { i++; continue }
                tripSeen[t] = true; tripBoard[t] = f; tripBoardT[t] = d
            }
            val to2 = stStop[j + 1]; val a = stArr[j + 1]
            if (a < arrT[to2]) {
                arrT[to2] = a; boardT[to2] = a + MIN_CHANGE; viaConn[to2] = i; viaWalk[to2] = -1
                for (x in xStart[to2] until xStart[to2 + 1]) {
                    val nb = xTo[x]; val aw = a + xW[x]
                    if (aw < arrT[nb]) { arrT[nb] = aw; boardT[nb] = aw; viaWalk[nb] = to2; viaConn[nb] = -1 }
                }
            }
            i++
        }
        if (arrT[to] >= INF) return null

        val legs = ArrayList<Leg>()
        var cur = to; var guard = 0
        while (cur != from && guard++ < 200) {
            if (viaWalk[cur] >= 0) {
                val prev = viaWalk[cur]
                legs.add(Leg(false, -1, -1, prev, cur, arrT[prev], arrT[cur]))
                cur = prev
            } else if (viaConn[cur] >= 0) {
                val c = viaConn[cur]; val t = cTrip[c]; val brd = tripBoard[t]
                legs.add(Leg(true, t, tripRoute[t], brd, cur, tripBoardT[t], stArr[cST[c] + 1]))
                cur = brd
            } else break
        }
        legs.reverse()
        return Journey(if (legs.isEmpty()) depTime else legs[0].dep, arrT[to], legs)
    }

    companion object {
        /** Reads a `.kav` or gzipped `.kav.gz` stream. */
        fun read(input: InputStream): Net {
            val raw = input.buffered().use { it.readBytes() }
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                GZIPInputStream(raw.inputStream(), 1 shl 16).use { it.readBytes() }
            else raw
            return Net().also { it.parse(bytes); it.sourceBytes = bytes.size.toLong() }
        }
    }
}
