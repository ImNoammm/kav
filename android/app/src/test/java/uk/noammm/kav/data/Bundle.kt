package uk.noammm.kav.data

import java.io.ByteArrayOutputStream

// Writes a small KAV5 bundle, the same layout tools/export_web_bundle.py writes, so tests
// can load a timetable through Net.read without the real feed.
class Bundle {
    class Trip(val days: Int, val stops: List<Int>, val deps: List<Int>)

    private val stops = ArrayList<String>()
    private val trips = ArrayList<Trip>()

    fun stop(name: String): Int { stops.add(name); return stops.size - 1 }

    // `days` has bit 0 for Sunday; `deps` are seconds from the midnight the trip sets out on.
    fun trip(stops: List<Int>, deps: List<Int>, days: Int = EVERY_DAY): Bundle {
        require(stops.size == deps.size)
        trips.add(Trip(days, stops, deps))
        return this
    }

    fun net(): Net = Net.read(bytes().inputStream())

    private fun bytes(): ByteArray {
        val o = ByteArrayOutputStream()
        fun vi(v: Int) {
            var r = (v shl 1) xor (v shr 31)
            while (r and 0x7f.inv() != 0) { o.write((r and 0x7f) or 0x80); r = r ushr 7 }
            o.write(r)
        }
        fun vs(s: String) { val b = s.toByteArray(Charsets.UTF_8); vi(b.size); o.write(b) }

        o.write("KAV5".toByteArray(Charsets.US_ASCII))
        vi(stops.size); vi(1); vi(trips.size); vi(1); vi(trips.sumOf { it.stops.size })
        vi(1); vs("Agency")
        vs("City")
        var la = 0; var lo = 0
        stops.forEachIndexed { i, name ->
            val lat = 3_200_000 + i * 100; val lon = 3_480_000 + i * 100
            vi(lat - la); vi(lon - lo); la = lat; lo = lon
            vi(10_000 + i); vi(0); vs(name)
        }
        vs("1"); vs("A - B"); vi(3); vi(0)
        for (t in trips) {
            vi(0); vi(t.days); vi(t.stops.size)
            vi(t.deps[0])
            var pt = t.deps[0]; var ps = 0
            for (j in t.stops.indices) {
                vi(t.deps[j] - pt); vi(0); vi(t.stops[j] - ps)
                pt = t.deps[j]; ps = t.stops[j]
            }
        }
        return o.toByteArray()
    }

    companion object {
        const val EVERY_DAY = 0x7f
        const val SUN = 0; const val MON = 1; const val WED = 3; const val SAT = 6
        fun on(vararg days: Int) = days.fold(0) { m, d -> m or (1 shl d) }
        fun hm(h: Int, m: Int) = h * 3600 + m * 60
    }
}
