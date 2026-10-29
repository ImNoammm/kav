package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

// The language stops', stations', towns' and lines' ends' names are shown in, a setting of its own that changes nothing
// else (Settings, Language and time): English where the timetable has a name's English one (the ministry's), else the
// Hebrew. Kav keeps working in the ministry's Hebrew names, which its server matches on, and changes them only where a
// name is shown; Moovit answers in Kav's own language, so its names are changed there the other way too.
object StopNames {
    var english by mutableStateOf(false)
}

// A name as shown, for a name from the timetable, Kav's server, Moovit or a stop Kav keeps.
fun shownName(name: String): String = uk.noammm.kav.Loaded.net?.shown(name) ?: name

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

    lateinit var name: Array<String>; private set
    lateinit var lat: DoubleArray; private set
    lateinit var lon: DoubleArray; private set
    lateinit var code: IntArray; private set
    lateinit var cityOf: IntArray; private set
    lateinit var city: Array<String>; private set
    // A stop's platform (רציף) where the ministry numbers one, else blank: a bus station's platforms are stops of their
    // own that share its code. Bundles before KAV6 have none.
    lateinit var platform: Array<String>; private set
    // Each stop's and town's English name, blank where the ministry has none; empty arrays in bundles without an "en" section.
    var nameEn: Array<String> = emptyArray(); private set
    var cityEn: Array<String> = emptyArray(); private set
    // The names lines end at that are no stop's (the ministry's long names give some another way), in English.
    private var moreEn: Map<String, String> = emptyMap()

    lateinit var rShort: Array<String>; private set
    lateinit var rLong: Array<String>; private set
    lateinit var rType: IntArray; private set
    lateinit var rAgency: IntArray; private set

    lateinit var agency: Array<String>; private set

    lateinit var tripRoute: IntArray; private set
    lateinit var tripStart: IntArray; private set
    // Bit 0 is Sunday. Bundles older than KAV5 hold one day, so their trips run every day.
    lateinit var tripDays: IntArray; private set
    lateinit var stStop: IntArray; private set
    lateinit var stDep: IntArray; private set

    lateinit var cST: IntArray; private set

    lateinit var dStart: IntArray; private set
    lateinit var dConn: IntArray; private set

    lateinit var hay: Array<String>; private set
    var hayEn: Array<String> = emptyArray(); private set

    val nStops get() = lat.size
    val nRoutes get() = rShort.size

    fun cityOf(s: Int): String = city.getOrElse(cityOf[s]) { "" }

    // A stop's and its town's names as shown (StopNames).
    fun stopName(s: Int): String = name[s].let { if (StopNames.english) nameEn.getOrNull(s)?.ifEmpty { null } ?: it else it }
    fun cityName(s: Int): String =
        cityOf(s).let { if (StopNames.english) cityEn.getOrNull(cityOf[s])?.ifEmpty { null } ?: it else it }

    // Any name the timetable has, a stop's, a town's or a line's end, as shown: the ministry's Hebrew (Kav's server, a
    // stop Kav kept) in English, or Moovit's English, when Kav is in English, back in Hebrew. Others stay as they are.
    fun shown(name: String): String = when {
        name.isEmpty() -> name
        StopNames.english -> english[name.trim()] ?: name
        else -> hebrew[name.trim()] ?: name
    }

    private val english: Map<String, String> by lazy {
        val m = HashMap<String, String>(nameEn.size + cityEn.size + moreEn.size)
        for (i in nameEn.indices) if (nameEn[i].isNotEmpty()) m.putIfAbsent(name[i], nameEn[i])
        for (i in cityEn.indices) if (cityEn[i].isNotEmpty()) m.putIfAbsent(city[i], cityEn[i])
        for ((he, en) in moreEn) m.putIfAbsent(he, en)
        m
    }

    private val hebrew: Map<String, String> by lazy { HashMap<String, String>().apply { for ((he, en) in english) putIfAbsent(en, he) } }
    fun agencyOf(r: Int): String = agency.getOrElse(rAgency.getOrElse(r) { -1 }) { "" }
    fun tripLast(t: Int): Int = stStop[tripStart[t + 1] - 1]
    fun runsOn(t: Int, day: Int): Boolean = tripDays[t] and (1 shl day) != 0

    fun tripOf(stopTime: Int): Int {
        var lo = 0; var hi = tripRoute.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tripStart[mid] <= stopTime) lo = mid else hi = mid - 1
        }
        return lo
    }

    // The platform a line leaves a bus station from, at about that second of the day: the only one the timetable has it
    // leave from there, else the one of its departure nearest that time, within five minutes. Blank when it can't tell.
    fun platformOf(code: Int, number: String, second: Int): String {
        val stops = platformStops[code] ?: return ""
        // Screens ask on every redraw; a big station has thousands of departures to go through.
        val key = "$code/$number/$second"
        synchronized(platformsFound) { platformsFound[key] }?.let { return it }
        val seen = HashSet<String>()
        var nearest = ""
        var gap = Int.MAX_VALUE
        for (s in stops) for (i in dStart[s] until dStart[s + 1]) {
            val st = cST[dConn[i]]
            if (rShort[tripRoute[tripOf(st)]] != number) continue
            seen.add(platform[s])
            val off = Math.abs(Math.floorMod(stDep[st] - second + 43_200, 86_400) - 43_200)
            if (off < gap) { gap = off; nearest = platform[s] }
        }
        val found = when {
            seen.size == 1 -> seen.first()
            gap <= 300 -> nearest
            else -> ""
        }
        synchronized(platformsFound) { platformsFound[key] = found }
        return found
    }

    private val platformsFound = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    private val platformStops: Map<Int, IntArray> by lazy {
        val byCode = HashMap<Int, MutableList<Int>>()
        for (s in 0 until nStops) if (platform[s].isNotEmpty() && code[s] > 0) byCode.getOrPut(code[s]) { ArrayList() }.add(s)
        byCode.mapValues { it.value.toIntArray() }
    }

    // Where each town or city is, as the average of its stops.
    val cityCentre: Array<Pair<Double, Double>?> by lazy {
        val sumLat = DoubleArray(city.size); val sumLon = DoubleArray(city.size); val count = IntArray(city.size)
        for (s in 0 until nStops) {
            val c = cityOf[s]
            if (c !in city.indices || city[c].isBlank()) continue
            sumLat[c] += lat[s]; sumLon[c] += lon[s]; count[c]++
        }
        Array(city.size) { c -> if (count[c] == 0) null else sumLat[c] / count[c] to sumLon[c] / count[c] }
    }

    val stopWords: Array<String> by lazy {
        Array(nStops) {
            val named = name[it] + nameEn.getOrNull(it)?.takeIf { e -> e.isNotEmpty() }?.let { e -> " $e" }.orEmpty()
            spacedWords(if (code[it] > 0) named + " " + code[it] else named)
        }
    }
    val stopType: IntArray by lazy {
        val out = IntArray(nStops) { -1 }
        for (t in tripRoute.indices) {
            val type = rType[tripRoute[t]]
            for (k in tripStart[t] until tripStart[t + 1]) {
                val s = stStop[k]
                if (out[s] < 0 || type < out[s]) out[s] = type
            }
        }
        out
    }

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
        if (magic != "KAV3" && magic != "KAV4" && magic != "KAV5" && magic != "KAV6") throw IllegalArgumentException("bad bundle: $magic")
        val v4 = magic != "KAV3"
        val v5 = magic == "KAV5" || magic == "KAV6"
        p = 4

        val nS = vi(); val nR = vi(); val nT = vi(); val nC = vi()
        val nST = if (v5) vi() else -1
        agency = if (v4) Array(vi()) { vs() } else emptyArray()

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
        rAgency = IntArray(nR) { -1 }
        for (i in 0 until nR) {
            rShort[i] = vs(); rLong[i] = vs(); rType[i] = vi()
            if (v4) rAgency[i] = vi()
        }

        tripRoute = IntArray(nT); tripStart = IntArray(nT + 1)
        tripDays = IntArray(nT) { 0x7f }
        if (v5) {
            stStop = IntArray(nST); stDep = IntArray(nST)
            var k = 0
            for (t in 0 until nT) {
                tripRoute[t] = vi(); tripDays[t] = vi()
                val n = vi()
                var pt = vi(); var ps = 0
                tripStart[t] = k
                for (j in 0 until n) {
                    val d = pt + vi() + vi(); val s = ps + vi()
                    stDep[k] = d; stStop[k] = s; k++
                    pt = d; ps = s
                }
            }
            tripStart[nT] = k
        } else {
            val ss = IntVec(1 shl 21); val sd = IntVec(1 shl 21)
            for (t in 0 until nT) {
                tripRoute[t] = vi()
                val n = vi()
                var pt = vi(); var ps = 0
                tripStart[t] = ss.n
                for (k in 0 until n) {
                    val d = pt + vi() + vi(); val s = ps + vi()
                    sd.push(d); ss.push(s)
                    pt = d; ps = s
                }
            }
            tripStart[nT] = ss.n
            stStop = ss.trimmed(); stDep = sd.trimmed()
        }

        platform = Array(nS) { "" }
        if (magic == "KAV6") {
            var s = 0
            repeat(vi()) { s += vi(); platform[s] = vs() }
            // Named sections after it, each its name and length, so one this version doesn't know is skipped.
            while (p < b.size) {
                val section = vs()
                val end = vi().let { p + it }
                if (section == "en") {
                    cityEn = Array(vi()) { vs() }
                    nameEn = Array(vi()) { vs() }
                    moreEn = HashMap<String, String>().apply { repeat(vi()) { put(vs(), vs()) } }
                    if (nameEn.size != nS || cityEn.size != nC) { nameEn = emptyArray(); cityEn = emptyArray(); moreEn = emptyMap() }
                }
                p = end
            }
        }

        hay = Array(nS) { (name[it] + " " + cityOf(it)).lowercase() }
        // Found by its English name too, whichever the rider reads.
        hayEn = if (nameEn.isEmpty()) emptyArray()
            else Array(nS) { (nameEn[it] + " " + cityEn.getOrElse(cityOf[it]) { "" }).trim().lowercase() }
        b = ByteArray(0)
        buildConnections()
    }

    // A downloaded week without English names (no "en" section) takes them from the week Kav shipped with: a stop's by
    // its code and Hebrew name, else by the Hebrew name alone, and a town's by its Hebrew name. False when there was
    // nothing to take.
    fun borrowEnglish(from: Net): Boolean {
        if (nameEn.isNotEmpty() || from.nameEn.isEmpty()) return false
        val exact = HashMap<String, String>()
        val byName = HashMap<String, String>()
        for (s in 0 until from.nStops) {
            val en = from.nameEn[s]
            if (en.isEmpty()) continue
            exact.putIfAbsent("${from.code[s]}|${from.name[s]}", en)
            byName.putIfAbsent(from.name[s], en)
        }
        val towns = HashMap<String, String>()
        for (c in from.city.indices) from.cityEn.getOrNull(c)?.takeIf { it.isNotEmpty() }?.let { towns.putIfAbsent(from.city[c], it) }
        nameEn = Array(nStops) { exact["${code[it]}|${name[it]}"] ?: byName[name[it]] ?: "" }
        cityEn = Array(city.size) { towns[city[it]] ?: "" }
        moreEn = from.moreEn
        hayEn = Array(nStops) { (nameEn[it] + " " + cityEn.getOrElse(cityOf[it]) { "" }).trim().lowercase() }
        return true
    }

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

        cST = IntArray(m)
        for (t in 0 until nT) {
            var i = tripStart[t]; val z = tripStart[t + 1] - 1
            while (i < z) {
                cST[cnt[stDep[i]]++] = i
                i++
            }
        }

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
        fun read(input: InputStream): Net {
            val raw = input.buffered().use { it.readBytes() }
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                GZIPInputStream(raw.inputStream(), 1 shl 16).use { it.readBytes() }
            else raw
            return Net().also { it.parse(bytes) }
        }
    }
}
