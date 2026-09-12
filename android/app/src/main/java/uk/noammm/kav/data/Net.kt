package uk.noammm.kav.data

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Reader for the KAV3 timetable bundle: stops, lines, trips and a per-stop
 * departure index, which is what the stops, lines and live screens browse.
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

    // connections, departure-sorted
    lateinit var cST: IntArray; private set      // stop_time index
    lateinit var cTrip: IntArray; private set

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

        // The footpaths and then the road polylines follow. Nothing reads either:
        // the footpath graph existed only so the on-device planner could change
        // between stops, and the map is its own file. Parsing stops here, so
        // neither is ever allocated.

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
        val nS = lat.size
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
