package uk.noammm.kav.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

// Provider timetables are separate from planning and live locations. Answers survive restarts
// for the Israeli day; the local week supplies stop names and stands in while a provider loads.
// Kav and self-hosted timetables read the local week without provider requests.
object Schedules {
    private val israel = ZoneId.of("Asia/Jerusalem")
    private var cache: ScheduleCache? = null

    fun init(ctx: Context) { cache = ScheduleCache(File(ctx.filesDir, "schedules/answers")) }
    internal fun kept(s: Source, key: String): String? = cache?.read(s, key)
    internal fun keep(s: Source, key: String, text: String) { cache?.write(s, key, text) }
    internal fun cached(s: Source, key: String, fetch: () -> String): String = cache?.get(s, key, fetch) ?: fetch()

    // Kav's week until the settings are read. A new choice drops what the last one answered.
    private var picked by mutableStateOf(Source.KAV)
    var source: Source
        get() = picked
        set(value) {
            if (picked == value) return
            picked = value
            ways.clear(); times.clear(); departures.clear(); listed = null
        }
    val online get() = source.provider

    // A line as its provider lists it: its id there, its number, mode (a timetable route type), operator and two ends;
    // routes: its provider's routes, both ways where it has them (both); names: the ministry's long names of those,
    // which the week on the phone has too (Lines finds saved, recent and nearby lines by them).
    class Line(
        val id: String, val number: String, val type: Int, val operator: String, val from: String, val to: String,
        val routes: List<String>, val names: List<String>, val both: Boolean = false,
    )

    // One way a line runs: its provider's id for it, where it ends, its stops in order and its road (empty when the
    // provider gives none, and the stops are joined instead); alight, the places in it where riders only get off; also,
    // the provider's ids of ways calling at the very same stops, whose trips are this one's too.
    class Way(
        val id: String, val to: String, val stops: List<Stop>, val shape: List<Pair<Double, Double>>,
        val alight: Set<Int> = emptySet(), val also: List<String> = emptyList(),
    )
    class Stop(val code: Int, val name: String, val city: String, val lat: Double, val lon: Double)

    // A departure from a stop: when (epoch seconds), its line, its number (a train's own, where a train line has none),
    // where it ends, and the platform where the provider says.
    class Departure(val at: Long, val line: Line, val number: String, val to: String, val platform: String)

    fun today(): LocalDate = LocalDate.now(israel)
    // A day's midnight in Israel, in epoch seconds.
    fun start(day: LocalDate): Long = day.atStartOfDay(israel).toEpochSecond()

    // The provider's lines, kept on the phone: asked again once a day on Wi-Fi, and on mobile data only once a week, as
    // the list is a big one (HopOn's is 1.6 MB) and changes little. A provider that can't be reached leaves the copy.
    private const val FRESH_MS = 86_400_000L
    private const val FRESH_METERED_MS = 7 * 86_400_000L
    @Volatile private var listed: Pair<Source, List<Line>>? = null
    private var listedAt = 0L

    @Synchronized
    fun lines(ctx: Context, s: Source = source): List<Line> {
        val freshFor = if (metered(ctx)) FRESH_METERED_MS else FRESH_MS
        listed?.takeIf { it.first == s && System.currentTimeMillis() - listedAt < freshFor }?.let { return it.second }
        val file = File(ctx.filesDir, "schedules/${s.key}-lines${if (s.hopOn) "-grouped" else ""}.json")
        val kept = runCatching { if (file.exists()) readLines(file.readText()) else null }.getOrNull()
        val age = System.currentTimeMillis() - file.lastModified()
        val fresh = kept != null && age < freshFor
        val lines = if (fresh) kept!! else try {
            fetchLines(s).also { got ->
                // A list the phone can't keep still works for now.
                runCatching {
                    file.parentFile?.mkdirs()
                    val part = File(file.path + ".part")
                    part.writeText(writeLines(got))
                    if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
                    if (s.hopOn && file.exists()) File(ctx.filesDir, "schedules/${s.key}-lines.json").delete()
                }
            }
        } catch (e: Exception) {
            // No answer, or one that isn't the list (an error page, a changed site): the copy on the phone, if any.
            kept ?: throw e
        }
        if (source == s) { listedAt = file.lastModified(); listed = s to lines }
        return lines
    }

    private fun fetchLines(s: Source): List<Line> = when {
        s.hopOn -> BusNearby.lines(s)
        s == Source.MOOVIT -> MoovitWeb.lines()
        else -> throw IllegalStateException("${s.key} is no timetable provider")
    }

    // A line's ways, kept in memory for the day; the provider clients also keep their answers on disk.
    private val ways = ConcurrentHashMap<String, List<Way>>()

    fun ways(line: Line): List<Way> {
        val s = source
        return ways.getOrPut("${s.key}/${line.id}/${today()}") {
            arrange(when {
                s.hopOn -> BusNearby.ways(line, s)
                s == Source.MOOVIT -> MoovitWeb.ways(line, today())
                else -> throw IllegalStateException("${s.key} is no timetable provider")
            }, byEnd = s.hopOn && line.type == 2)
        }
    }

    // The ways a line's page offers. Two of the provider's calling at the very same stops are one, with both's trips.
    // byEnd: a HopOn train line, whose times at a station are all its trains to the way's end, has one way to each end,
    // along its longest train's stops, as the week's train line page has it. The first way to each end comes first, so
    // both directions show before the branches.
    internal fun arrange(ways: List<Way>, byEnd: Boolean): List<Way> {
        val groups = LinkedHashMap<Any, MutableList<Way>>()
        for (w in ways) groups.getOrPut(if (byEnd) w.stops.last().code else w.stops.map { it.code }) { ArrayList() }.add(w)
        val one = groups.values.map { same ->
            if (same.size == 1) return@map same[0]
            val main = same.maxBy { it.stops.size }
            // Riders only get off at a stop where none of them takes any on.
            val alight = if (!byEnd) same.map { it.alight }.reduce { a, b -> a intersect b }
                else main.stops.indices.filterTo(HashSet()) { i ->
                    same.all { w -> w.stops.indices.filter { w.stops[it].code == main.stops[i].code }.all { it in w.alight } }
                }
            Way(main.id, main.to, main.stops, main.shape.ifEmpty { same.firstOrNull { it.shape.isNotEmpty() }?.shape.orEmpty() },
                alight, main.also + same.filter { it !== main }.flatMap { listOf(it.id) + it.also })
        }
        val firsts = one.distinctBy { it.stops.last().code }
        return firsts + one.filter { it !in firsts }
    }

    // When a line leaves one of its way's stops (at, its place in the way) on a day, epoch seconds in order.
    private val times = ConcurrentHashMap<String, List<Long>>()

    fun times(line: Line, way: Way, at: Int, day: LocalDate): List<Long> {
        val s = source
        return times.getOrPut("${s.key}/${today()}/${line.id}/${way.id}/$at/$day") {
            when {
                s.hopOn -> BusNearby.times(line, way, at, day, s)
                s == Source.MOOVIT -> MoovitWeb.times(line, way, at, day)
                else -> throw IllegalStateException("${s.key} is no timetable provider")
            }
        }
    }

    // HopOn gives a stop's day directly; Moovit's comes from the schedules of the lines calling there.
    val boards get() = source.provider

    // A stop's departures on a day, by its code, soonest first; kept for the day.
    private val departures = ConcurrentHashMap<String, List<Departure>>()

    fun departures(ctx: Context, code: Int, day: LocalDate): List<Departure> {
        val s = source
        return departures.getOrPut("${s.key}/${today()}/$code/$day") {
            val lines = lines(ctx, s)
            when {
                s.hopOn -> BusNearby.departures(code, day, lines, s)
                s == Source.MOOVIT -> MoovitWeb.departures(code, day, lines)
                else -> throw IllegalStateException("${s.key} is no timetable provider")
            }.sortedBy { it.at }
        }
    }

    // The lines kept on the phone, as written above.
    internal fun writeLines(lines: List<Line>): String = JSONArray().apply {
        for (l in lines) put(JSONObject().put("id", l.id).put("n", l.number).put("t", l.type).put("o", l.operator)
            .put("a", l.from).put("b", l.to).put("r", JSONArray(l.routes)).put("ln", JSONArray(l.names)).put("2", l.both))
    }.toString()

    internal fun readLines(text: String): List<Line> {
        val a = JSONArray(text)
        fun strings(x: JSONArray?) = (0 until (x?.length() ?: 0)).map { x!!.getString(it) }
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Line(o.getString("id"), o.optString("n"), o.optInt("t", 3), o.optString("o"), o.optString("a"), o.optString("b"),
                strings(o.optJSONArray("r")), strings(o.optJSONArray("ln")), o.optBoolean("2"))
        }
    }

    private fun metered(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    // A ministry long name's two ends, each its stop and town: "stop-town<->stop-town-direction", the direction (and its
    // alternative, "1#", "11 (1)") at the end. A town is told from its stop by the town names the week on the phone
    // knows (towns), else at the last hyphen.
    internal fun ends(long: String, towns: Set<String>): Pair<Pair<String, String>, Pair<String, String>>? {
        val parts = long.split("<->")
        if (parts.size != 2) return null
        fun place(raw: String, last: Boolean): Pair<String, String> {
            var p = raw.trim()
            if (last) p.substringAfterLast('-', "").trim().takeIf { it.firstOrNull()?.isDigit() == true }?.let {
                p = p.substring(0, p.lastIndexOf('-')).trim()
            }
            // The longest known town it ends with, else what's after the last hyphen.
            val cut = p.indices.firstOrNull { p[it] == '-' && p.substring(it + 1).trim() in towns } ?: p.lastIndexOf('-')
            return if (cut < 0) p to "" else p.substring(0, cut).trim() to p.substring(cut + 1).trim()
        }
        return place(parts[0], false) to place(parts[1], true)
    }
}

// Provider timetable answers survive a restart, but are asked afresh on the next Israeli day. Only timetable
// requests use this store; live arrivals and planning never do. A failed request leaves no answer behind.
internal class ScheduleCache(private val dir: File, private val today: () -> LocalDate = Schedules::today) {
    private val locks = ConcurrentHashMap<String, Any>()
    private var cleaned: LocalDate? = null

    private fun file(s: Source, key: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "${s.key}-$hash.json")
    }

    @Synchronized private fun clean() {
        val day = today()
        if (cleaned == day) return
        val midnight = Schedules.start(day) * 1000
        dir.listFiles()?.filter { it.extension == "json" && it.lastModified() < midnight }?.forEach { it.delete() }
        cleaned = day
    }

    fun read(s: Source, key: String): String? {
        clean()
        return runCatching {
            val o = JSONObject(file(s, key).readText())
            o.getString("answer").takeIf { o.getString("saved") == today().toString() }
        }.getOrNull()
    }

    fun write(s: Source, key: String, text: String) {
        clean()
        // Don't keep an error page in place of a timetable.
        val value = org.json.JSONTokener(text).nextValue()
        require(value is JSONObject || value is JSONArray)
        var part: File? = null
        try {
            dir.mkdirs()
            part = File.createTempFile("answer-", ".part", dir)
            part.writeText(JSONObject().put("saved", today().toString()).put("answer", text).toString())
            part.renameTo(file(s, key))
        } catch (_: java.io.IOException) {
            // The provider's answer still works if the phone can't keep a copy.
        } finally { part?.delete() }
    }

    fun get(s: Source, key: String, fetch: () -> String): String = synchronized(locks.getOrPut("${s.key}/$key") { Any() }) {
        read(s, key) ?: fetch().also { write(s, key, it) }
    }
}
