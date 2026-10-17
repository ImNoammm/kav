package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import uk.noammm.kav.BuildConfig

// Live buses from Kav's own server (server/live.py), which reads the Ministry of Transport's real-time feed. With it on,
// every bus, position and live time comes from there and Moovit only plans trips. The vehicle id of a tracked Moovit
// arrival is the ministry's own name for the journey, route_firstdeparture_vehicle, which is how the two meet.
object KavLive {
    private const val BASE = "https://kav.noammm.uk/live/v1/"

    // The server asks the ministry directly for at most this many routes, or stops, at once.
    const val FOLLOW_MAX = 4
    const val STOPS_MAX = 20

    // The setting, and whether the server is answering with live buses. While it isn't, Moovit's are used without
    // touching the setting, and the server's again as soon as it answers.
    var chosen by mutableStateOf(true)
    var up by mutableStateOf(true)
    val enabled get() = chosen && up

    // A request that failed asks for the server to be checked now rather than at the next turn.
    val recheck = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    // bearing in degrees from north and speed in km/h, both -1 when the report has none.
    class Spot(val lat: Double, val lon: Double, val at: Long, val bearing: Int = -1, val speed: Int = -1)

    class Answer(val spots: Map<String, Spot>, val next: Long)

    // A route as the ministry's timetable names it, with the kind Kav's own timetable gives it.
    class Line(
        val number: String, val to: String, val toCity: String, val from: String, val fromCity: String,
        val agency: Int, val type: Int, val name: String,
    )

    class Bus(val key: String, val route: Int, val spot: Spot)

    // A bus due at a stop. first is when its trip left its first stop; aimed is only known before it sets out, expected
    // always; spot is where it is, when it reports one; platform a train's, once the operator has given it.
    class Due(
        val stop: Int, val route: Int, val key: String, val number: String, val first: Long, val aimed: Long,
        val expected: Long, val spot: Spot?, val order: Int, val platform: String = "",
    ) {
        val underWay get() = spot != null || aimed == 0L
    }

    class Area(val buses: List<Bus>, val lines: Map<Int, Line>, val next: Long)

    class Board(val due: List<Due>, val lines: Map<Int, Line>, val next: Long)

    fun routeOf(vehicleId: String): Int? = vehicleId.substringBefore('_', "").toIntOrNull()?.takeIf { it > 0 }

    // The vehicle part of the key is the bus's number plate, written the Israeli way.
    fun plateOf(vehicleId: String): String? {
        val n = vehicleId.substringAfterLast('_', "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        return when (n.length) {
            7 -> "${n.take(2)}-${n.substring(2, 5)}-${n.takeLast(2)}"
            8 -> "${n.take(3)}-${n.substring(3, 5)}-${n.takeLast(3)}"
            else -> n
        }
    }

    // In the order the arrivals come, so the bus that matters most is asked about first.
    fun routesOf(arrivals: Collection<Moovit.Arrival>): List<Int> =
        arrivals.asSequence().filter { it.hasLocation }.mapNotNull { routeOf(it.vehicleId) }.distinct().toList()

    // The routes of a trip's rides that have a bus Moovit tracks, the planned buses' first.
    fun tripRoutes(trip: Moovit.Itinerary, r: Moovit.Resolved): List<Int> {
        val rides = trip.rides.flatMap { it.options }
        return routesOf(rides.mapNotNull { r.arrival(it) } + rides.flatMap { r.liveFor(it) })
    }

    // The trip's buses where the server last saw them, for what runs outside the screen; nothing when it's off or away.
    fun tripSpots(trip: Moovit.Itinerary, r: Moovit.Resolved): Map<String, Spot> {
        if (!enabled) return emptyMap()
        val routes = tripRoutes(trip, r)
        if (routes.isEmpty()) return emptyMap()
        return try { ask(routes).spots } catch (e: java.io.IOException) { emptyMap() }
    }

    private fun get(path: String, quick: Boolean = false): String {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            if (quick) { connectTimeout = 4_000; readTimeout = 4_000 } else { connectTimeout = 10_000; readTimeout = 15_000 }
            setRequestProperty("User-Agent", "Kav")
            setRequestProperty("Accept", "application/json")
            if (BuildConfig.KAV_LIVE_KEY.isNotEmpty()) {
                setRequestProperty("X-Kav", mark("/live/v1/$path", BuildConfig.KAV_LIVE_KEY, System.currentTimeMillis()))
            }
        }
        try {
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("Kav live HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            if (!quick) recheck.trySend(Unit)
            throw e
        } finally {
            c.disconnect()
        }
    }

    // The server answers Kav alone: each request carries the ten minutes it's asked in and an HMAC of them with its
    // path, made with the key Kav is built with.
    internal fun mark(path: String, key: String, nowMs: Long): String {
        val slot = nowMs / 600_000
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            .apply { init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
            .doFinal("$slot:${java.net.URLDecoder.decode(path, "UTF-8")}".toByteArray())
        return "$slot." + mac.joinToString("") { "%02x".format(it) }.take(32)
    }

    // Whether the server is answering with live buses, waiting only a few seconds to know.
    fun answering(): Boolean = try { JSONObject(get("status", quick = true)).optBoolean("ok") } catch (e: Exception) { false }

    // The buses on up to FOLLOW_MAX routes, straight from the ministry, fresher than the server's snapshot.
    fun ask(routes: List<Int>): Answer {
        val asked = routes.distinct().take(FOLLOW_MAX).sorted()
        return parse(get("follow/" + asked.joinToString(",")))
    }

    // Every bus in a box from the server's snapshot: south, west, north, east as box() makes them.
    fun area(box: DoubleArray): Area =
        parseArea(get("area/" + box.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }))

    // Every bus on the routes with this number, and all those routes, so a page can pick its own.
    fun line(number: String): Area = parseArea(get("line/" + URLEncoder.encode(number, "UTF-8").replace("+", "%20")))

    // Every bus due at these stops in the next hour and a half, straight from the ministry.
    fun stops(codes: Collection<Int>): Board =
        parseBoard(get("stops/" + codes.filter { it > 0 }.distinct().sorted().take(STOPS_MAX).joinToString(",")))

    private val shapes = object : LinkedHashMap<Int, List<Pair<Double, Double>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Pair<Double, Double>>>?) = size > 40
    }

    // The road a route takes, from the ministry's timetable. A route's road doesn't change in a day, so it's kept.
    fun shape(route: Int): List<Pair<Double, Double>> {
        synchronized(shapes) { shapes[route] }?.let { return it }
        val path = Moovit.decodePolyline(JSONObject(get("shape/$route")).optString("shape"))
        synchronized(shapes) { shapes[route] = path }
        return path
    }

    fun shapeNow(route: Int): List<Pair<Double, Double>> = synchronized(shapes) { shapes[route] }.orEmpty()

    // When the trip left its first stop, from the middle of its key.
    fun firstOf(vehicleId: String): Long? = vehicleId.split('_').getOrNull(1)
        ?.let { runCatching { java.time.OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

    // The box around where the map looks, widened to whole hundredths of a degree (about a kilometre), so the server
    // learns only roughly where, and kept within what it answers.
    fun box(lat: Double, lon: Double, km: Double): DoubleArray {
        val dLat = (km / 111.0).coerceIn(0.01, 0.18)
        val dLon = (km / (111.0 * cos(Math.toRadians(lat)))).coerceIn(0.01, 0.18)
        return doubleArrayOf(
            floor((lat - dLat) * 100) / 100, floor((lon - dLon) * 100) / 100,
            ceil((lat + dLat) * 100) / 100, ceil((lon + dLon) * 100) / 100,
        )
    }

    internal fun parse(body: String): Answer {
        val o = JSONObject(body)
        val rows = o.optJSONArray("v") ?: JSONArray()
        val out = HashMap<String, Spot>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val key = row.optString(0).takeIf { it.isNotEmpty() } ?: continue
            out[key] = spotOf(row, 1) ?: continue
        }
        return Answer(out, o.optLong("next"))
    }

    // lat, lon, recorded, bearing, speed from position i on.
    private fun spotOf(row: JSONArray, i: Int): Spot? {
        if (row.isNull(i) || row.isNull(i + 1)) return null
        val lat = row.optDouble(i); val lon = row.optDouble(i + 1); val at = row.optLong(i + 2)
        if (lat.isNaN() || lon.isNaN() || at <= 0) return null
        val bearing = row.optInt(i + 3, -1).takeIf { it in 0..359 } ?: -1
        val speed = row.optInt(i + 4, -1).takeIf { it in 0..199 } ?: -1
        return Spot(lat, lon, at, bearing, speed)
    }

    private fun parseLines(o: JSONObject?): Map<Int, Line> {
        val out = HashMap<Int, Line>()
        o ?: return out
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            val route = k.toIntOrNull() ?: continue
            out[route] = Line(
                a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4),
                a.optInt(5, -1), a.optInt(6, 3), a.optString(7),
            )
        }
        return out
    }

    internal fun parseArea(body: String): Area {
        val o = JSONObject(body)
        val rows = o.optJSONArray("v") ?: JSONArray()
        val buses = ArrayList<Bus>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val key = row.optString(0).takeIf { it.isNotEmpty() } ?: continue
            buses += Bus(key, routeOf(key) ?: continue, spotOf(row, 1) ?: continue)
        }
        return Area(buses, parseLines(o.optJSONObject("lines")), o.optLong("next"))
    }

    internal fun parseBoard(body: String): Board {
        val o = JSONObject(body)
        val rows = o.optJSONArray("a") ?: JSONArray()
        val due = ArrayList<Due>(rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val key = r.optString(2).takeIf { it.isNotEmpty() } ?: continue
            due += Due(
                r.optInt(0), r.optInt(1), key, r.optString(3), r.optLong(4), r.optLong(5), r.optLong(6),
                spotOf(r, 7), r.optInt(12, -1), r.optString(13, ""),
            )
        }
        return Board(due, parseLines(o.optJSONObject("lines")), o.optLong("next"))
    }

    // Kav's screens were made for Moovit's arrivals: what the server says, in that shape. Ids made up here are negative,
    // so they never meet one of Moovit's.
    fun tripIdOf(key: String): Long {
        var h = -3750763034362895579L
        for (c in key) { h = (h xor c.code.toLong()) * 1099511628211L }
        return -(h and Long.MAX_VALUE) - 1
    }

    // An operator that stands for a kind of line: the kind is all the screens take from it.
    fun agencyOf(type: Int) = -1000 - type

    fun kindOf(agency: Int) = if (agency <= -1000) -1000 - agency else null

    // Where a line goes, the way Moovit says it: the town for a line between towns, else the stop.
    fun lineInfo(line: Line?, number: String): Moovit.LineInfo {
        val far = line != null && line.toCity.isNotBlank() && line.toCity != line.fromCity
        return Moovit.LineInfo(
            -1, line?.number?.ifBlank { null } ?: number, agencyOf(line?.type ?: 3),
            (if (far) line?.fromCity else line?.from).orEmpty(), (if (far) line?.toCity else line?.to).orEmpty(), "",
        )
    }

    fun busArrival(bus: Bus): Moovit.Arrival = Moovit.Arrival(
        -1, -bus.route, tripIdOf(bus.key), 0, 0, 0, 0, 0, 0, false, false, true,
        bus.spot.lat, bus.spot.lon, bus.key, bus.spot.at, 0, -1, -1, -1, -1, -1, "", bus.spot.bearing, bus.spot.speed,
    )

    // A bus's position this many seconds old is live; to STALE_S it's an estimate; past that it may be well off.
    // Buses report every 15-40 s.
    const val FRESH_S = 60
    const val STALE_S = 180

    // How sure a live time is, the way Moovit grades its own (1 sure, 2 fairly, 3 maybe not): by how old the bus's
    // position is.
    fun certaintyOf(d: Due, now: Long): Int {
        val age = d.spot?.let { now - it.at } ?: return 3
        return if (age < FRESH_S) 1 else if (age < STALE_S) 2 else 3
    }

    // A bus due at a stop. On its way: the plan's time and the ministry's live one. Not yet out: Moovit's "not departed",
    // at the ministry's own time, which can be minutes off the timetable Moovit and Kav plan with.
    fun dueArrival(
        d: Due, stopId: Int, lineId: Int = -d.route, tripId: Long = tripIdOf(d.key), planned: Long = 0, pattern: Int = -1,
        now: Long = System.currentTimeMillis() / 1000,
    ): Moovit.Arrival = Moovit.Arrival(
        stopId, lineId, tripId,
        if (d.underWay) planned.takeIf { it > 0 } ?: d.aimed.takeIf { it > 0 } ?: d.expected
        else d.expected.takeIf { it > 0 } ?: d.aimed.takeIf { it > 0 } ?: planned,
        if (d.underWay) d.expected else 0, 0, 0, if (d.underWay) certaintyOf(d, now) else 0, 0, false, false, d.spot != null,
        d.spot?.lat ?: 0.0, d.spot?.lon ?: 0.0, d.key, d.spot?.at ?: 0, if (d.underWay) 0 else 3,
        -1, -1, -1, -1, pattern, d.platform, d.spot?.bearing ?: -1, d.spot?.speed ?: -1,
    )

    // What a trip's live layer from the ministry holds: the arrivals, and for the line's next buses at a boarding stop
    // a made-up pattern of the two stops they're known to serve, as Moovit's patterns say for its own.
    class Rides(val live: Map<Moovit.ArrivalKey, Moovit.Arrival>, val patterns: Map<Int, List<Int>>)

    private val israel = java.util.TimeZone.getTimeZone("Asia/Jerusalem")

    fun secondOfDay(epoch: Long): Int = Math.floorMod(epoch + israel.getOffset(epoch * 1000) / 1000, 86_400L).toInt()

    // The day of the week in Israel, Sunday 0, as the timetable counts its days.
    fun dayOf(epoch: Long): Int =
        Math.floorMod(Math.floorDiv(epoch + israel.getOffset(epoch * 1000) / 1000, 86_400L) + 4, 7L).toInt()

    // Whether the timetable runs trip t the day it set out at first. A trip is in the timetable once for each set of
    // days it runs on, so the same times can be there for another day too.
    private fun runsThen(net: Net, t: Int, first: Long): Boolean =
        net.runsOn(t, dayOf(first - net.stDep[net.tripStart[t]] + 43_200))

    // The timetable's stop time at this stop of the line's trip that left its first stop at first: what the ministry
    // calls a trip by, matched to Kav's own, the one that runs that day first. -1 if it hasn't one.
    private fun stopTimeOf(net: Net, stop: Int, number: String, first: Long): Int {
        val origin = secondOfDay(first)
        var other = -1
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            if (net.rShort[net.tripRoute[t]] != number) continue
            val off = abs(net.stDep[net.tripStart[t]] % 86_400 - origin)
            if (off > 60 && off < 86_340) continue
            if (runsThen(net, t, first)) return st
            if (other < 0) other = st
        }
        return other
    }

    // When the timetable has that trip leave the stop. Seconds from the timetable's midnight, or null if it hasn't.
    fun scheduledAt(net: Net, stop: Int, number: String, first: Long): Int? =
        stopTimeAt(net, stop, number, first)?.let { net.stDep[it] }

    // The timetable's stop time of that trip at the stop, or null.
    private fun stopTimeAt(net: Net, stop: Int, number: String, first: Long): Int? =
        stopTimeOf(net, stop, number, first).takeIf { it >= 0 }

    // The same for a train, which the timetable keeps without its number: the train trip that left its first station
    // when this one did, leaving the stop nearest when it's expected (two can set out the same minute), of those that
    // run that day first.
    fun trainAt(net: Net, stop: Int, first: Long, expected: Long): Int? {
        val origin = secondOfDay(first)
        val due = secondOfDay(expected)
        var found: Int? = null
        var best = Int.MAX_VALUE
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            val route = net.tripRoute[t]
            if (net.rType[route] != 2 || net.rShort[route].isNotEmpty()) continue
            val off = abs(net.stDep[net.tripStart[t]] % 86_400 - origin)
            if (off > 60 && off < 86_340) continue
            val late = abs(Math.floorMod(net.stDep[st] - due + 43_200, 86_400) - 43_200)
            if (late > 3_600) continue
            val rank = late + if (runsThen(net, t, first)) 0 else 86_400
            if (rank < best) { best = rank; found = st }
        }
        return found
    }

    // A live time on a stop's board: the timetable trip it is, and when that was due at the stop and now is, in
    // seconds from the board's midnight, as its rows count them: a trip from yesterday still running counts past it.
    // to is where a train really goes when that isn't where the timetable has it go, else null.
    class BoardTime(val due: Due, val trip: Int, val static: Int, val rt: Int, val to: String? = null)

    // The buses and trains due at the stop that are under way, each on the board's row it is. now is an epoch second.
    fun boardTimes(net: Net, stop: Int, board: Board, now: Long): List<BoardTime> {
        val midnight = now - secondOfDay(now)
        return board.due.filter { it.underWay && it.expected > 0 }.mapNotNull { d ->
            // A train is in the timetable without its number: found by when it set out instead.
            val st = stopTimeAt(net, stop, d.number, d.first) ?: trainAt(net, stop, d.first, d.expected)
                ?: return@mapNotNull null
            val t = net.tripOf(st)
            val late = Math.floorMod(secondOfDay(d.expected) - net.stDep[st] % 86_400 + 43_200, 86_400) - 43_200
            val static = (d.expected - late - midnight).toInt()
            val rt = (d.expected - midnight).toInt()
            if (net.rType[net.tripRoute[t]] != 2) return@mapNotNull BoardTime(d, t, static, rt)
            // Kav's timetable is a week's, and works can move where a night's trains start or end: the ministry knows
            // where this one goes.
            val dest = board.lines[d.route]?.to.orEmpty()
            val ends = goesTo(dest)
            if (!runsThen(net, t, d.first)) {
                // Today's timetable hasn't it start then: today's train leaving at that time for the same place.
                val row = net.trainLeaving(stop, static, dayOf(now), goesTo(board.lines[d.route]?.from.orEmpty()), ends)
                    ?: return@mapNotNull null
                return@mapNotNull BoardTime(d, row.trip, static, rt, row.to)
            }
            val to = net.cutShort(st, ends) ?: dest.takeIf { it.isNotEmpty() && !ends(net.name[net.tripLast(t)]) }
            BoardTime(d, t, static, rt, to)
        }
    }

    // Whether a station's name in the timetable is the one the ministry names a route's end by, which can be shorter
    // ("בית שאן" for "בית שאן - דוד לוי").
    private fun goesTo(dest: String): (String) -> Boolean =
        { name -> dest.isNotEmpty() && (name.startsWith(dest) || dest.startsWith(name)) }

    // The ministry's trains at a station not yet out that end early, on the board's rows (boardTimes places
    // those under way). Only what it lists counts: its answers can leave out a train that runs, so none is said not to.
    fun trainsToday(net: Net, stop: Int, board: Board, now: Long): TrainsToday {
        val midnight = now - secondOfDay(now)
        val ends = HashMap<Int, String>()
        for (d in board.due) {
            val line = board.lines[d.route] ?: continue
            if (line.type != 2 || d.underWay || d.aimed <= 0) continue
            val row = net.trainLeaving(stop, (d.aimed - midnight).toInt(), dayOf(now), goesTo(line.from), goesTo(line.to))
            row?.to?.let { ends[row.trip] = it }
        }
        return TrainsToday(ends = ends)
    }

    // A train line's trains for its page, routes being the timetable's for it: a train has no number to ask the server
    // for its line by, so its stations are asked, and each train due at one is placed on the timetable by when it set out.
    fun trainsOn(net: Net, routes: Set<Int>, stations: List<Int>): List<Moovit.Arrival> {
        val board = boardAt(stations.map { net.code[it] })
        return trainsOn(net, routes, withoutLostTrains(board, System.currentTimeMillis() / 1000) { stationAt(net, it) })
    }

    internal fun trainsOn(net: Net, routes: Set<Int>, due: List<Due>): List<Moovit.Arrival> =
        due.filter { it.underWay && it.spot != null }.groupBy { it.key }.values.mapNotNull { rows ->
            rows.firstOrNull { d ->
                val stop = stopOfCode(net, d.stop)
                val st = if (stop >= 0) trainAt(net, stop, d.first, d.expected) else null
                st != null && net.tripRoute[net.tripOf(st)] in routes
            }
        }.map { d -> busArrival(Bus(d.key, d.route, d.spot!!)) }

    // The same for one of Moovit's train lines: the trains due at its stations that set out when one of its trips does.
    // where(code) is a station's position.
    fun trainsStarting(codes: List<Int>, starts: List<Long>, where: (Int) -> Pair<Double, Double>?): List<Moovit.Arrival> =
        trainsStarting(withoutLostTrains(boardAt(codes), System.currentTimeMillis() / 1000, where), starts)

    internal fun trainsStarting(due: List<Due>, starts: List<Long>): List<Moovit.Arrival> =
        due.filter { d -> d.underWay && d.spot != null && starts.any { abs(it - d.first) <= 60 } }
            .distinctBy { it.key }.map { d -> busArrival(Bus(d.key, d.route, d.spot!!)) }

    // Everything due at these stops, asked STOPS_MAX at a time.
    private fun boardAt(codes: List<Int>): Board {
        val boards = codes.filter { it > 0 }.distinct().chunked(STOPS_MAX).map { stops(it) }
        return Board(boards.flatMap { it.due }, boards.fold(HashMap<Int, Line>()) { m, b -> m.apply { putAll(b.lines) } }, 0)
    }

    private fun stationAt(net: Net, code: Int): Pair<Double, Double>? =
        stopOfCode(net, code).takeIf { it >= 0 }?.let { net.lat[it] to net.lon[it] }

    // The ministry can keep a train where it was long ago, freshly stamped, while its times stay right. A position no
    // train could reach its station from in time is dropped and the times kept. where(code) is a station's position.
    internal fun withoutLostTrains(board: Board, now: Long, where: (Int) -> Pair<Double, Double>?): List<Due> {
        val lost = board.due.filter { d ->
            val spot = d.spot ?: return@filter false
            val at = where(d.stop) ?: return@filter false
            board.lines[d.route]?.type == 2 &&
                uk.noammm.kav.ui.metres(spot.lat, spot.lon, at.first, at.second) > 5_000 + TRAIN_M_S * maxOf(0L, d.expected - now)
        }.mapTo(HashSet()) { it.key }
        if (lost.isEmpty()) return board.due
        return board.due.map { d ->
            if (d.key !in lost) d else Due(d.stop, d.route, d.key, d.number, d.first, d.aimed, d.expected, null, d.order, d.platform)
        }
    }

    // 162 km/h, faster than any train here runs.
    private const val TRAIN_M_S = 45.0

    // Whether that trip goes on from the stop to another one.
    fun reaches(net: Net, stop: Int, to: Int, number: String, first: Long): Boolean {
        val st = stopTimeOf(net, stop, number, first)
        if (st < 0 || to < 0) return false
        return (st + 1 until net.tripStart[net.tripOf(st) + 1]).any { net.stStop[it] == to }
    }

    // The stops, in order, of the timetable's trip of this line that left its first stop at first: of the route the
    // ministry names when the timetable has that name, the one nearest the bus. Empty if it hasn't the trip.
    fun tripStops(net: Net, number: String, name: String, first: Long, lat: Double, lon: Double): List<Int> {
        val origin = secondOfDay(first)
        val trips = net.tripRoute.indices.filter { t ->
            val off = abs(net.stDep[net.tripStart[t]] % 86_400 - origin)
            net.rShort[net.tripRoute[t]] == number && (off <= 60 || off >= 86_340)
        }
        fun away(t: Int) = (net.tripStart[t] until net.tripStart[t + 1]).minOf { k ->
            val s = net.stStop[k]
            (net.lat[s] - lat) * (net.lat[s] - lat) + (net.lon[s] - lon) * (net.lon[s] - lon)
        }
        val named = trips.filter { name.isNotEmpty() && net.rLong[net.tripRoute[it]] == name }
        val trip = named.ifEmpty { trips }.minByOrNull(::away) ?: return emptyList()
        return (net.tripStart[trip] until net.tripStart[trip + 1]).map { net.stStop[it] }
    }

    // A trip's rides as Moovit's live layer would have them, from the ministry instead: each ride's own bus, the one
    // the timetable has leave the boarding stop when the plan says, timed where the rider boards and, on the trip being
    // taken, at every stop of the ride, as are the line's other buses. A ride whose own bus can't be told apart keeps
    // its plan's time, and the line's next buses still give its departures.
    fun rideArrivals(
        list: List<Moovit.Itinerary>, stops: Map<Int, Moovit.StopInfo>, lines: Map<Int, Moovit.LineInfo>,
        ending: Boolean, net: Net?,
    ): Rides {
        // A plan's ride time already counts the delay Moovit expects; the timetable's own time for it is in its departures.
        val rides = list.flatMap { i ->
            val deps = i.legs.flatMap { it.options }.flatMap { it.nextDeps }
            i.rides.flatMap { it.options }.filter { it.kind == Moovit.LegKind.RIDE && it.tripId != 0L && it.fromStop > 0 }
                .map { ride -> ride to deps.firstOrNull { it.tripId == ride.tripId && it.staticUtc > 0 }?.staticUtc }
        }.distinctBy { it.first.fromStop to it.first.tripId }
        fun codeOf(stop: Int) = (stops[stop] ?: Moovit.knownStop(stop))?.code?.toIntOrNull() ?: 0
        val codes = rides.flatMap { (r, _) -> listOf(r.fromStop) + if (ending) r.stops.drop(1) else emptyList() }
            .map(::codeOf).filter { it > 0 }.distinct()
        if (codes.isEmpty()) return Rides(emptyMap(), emptyMap())
        // A long ride's stops take several requests: all at once, rather than each waiting on the last.
        val chunks = codes.chunked(STOPS_MAX)
        val boards = if (chunks.size == 1) listOf(stops(chunks[0])) else {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(chunks.size, 4))
            try {
                chunks.map { c -> pool.submit<Board> { stops(c) } }.map { it.get() }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdown()
            }
        }
        val board = Board(boards.flatMap { it.due }, boards.fold(HashMap<Int, Line>()) { m, b -> m.apply { putAll(b.lines) } }, 0)
        val due = withoutLostTrains(board, System.currentTimeMillis() / 1000) { code ->
            net?.let { stationAt(it, code) } ?: stops.values.firstOrNull { it.code.toIntOrNull() == code }?.point
        }
        val out = LinkedHashMap<Moovit.ArrivalKey, Moovit.Arrival>()
        val patterns = HashMap<Int, List<Int>>()
        for ((ride, timetabled) in rides) {
            val code = codeOf(ride.fromStop)
            val number = ride.shortName.ifBlank { lines[ride.lineId]?.number.orEmpty() }.trim()
            if (code <= 0 || number.isEmpty()) continue
            val stop = net?.let { stopOfCode(it, code) } ?: -1
            val planned = secondOfDay(timetabled ?: ride.dep)
            // Without the timetable's time, the plan's own is within Moovit's expected delay of it.
            val slack = if (timetabled != null) 120 else 1200
            val same = due.filter { it.stop == code && it.number == number }
            val bus = chooseBus(same, planned, slack, ride.dep) { d ->
                if (net != null && stop >= 0) scheduledAt(net, stop, number, d.first) else null
            }
            // The line's buses that go on to where the rider gets off: the plan's own bus's route, and any route the
            // timetable has get there, even once the plan's bus has gone.
            val to = net?.let { stopOfCode(it, codeOf(ride.toStop)) } ?: -1
            val routes = same.map { it.route }.distinct().filter { route ->
                route == bus?.route ||
                    net != null && stop >= 0 && same.any { it.route == route && reaches(net, stop, to, number, it.first) }
            }
            if (routes.isEmpty()) continue
            val pattern = -2 - Math.floorMod(ride.fromStop.toLong() * 1_000_003 + ride.toStop, 1_000_000_000L).toInt()
            patterns[pattern] = listOf(ride.fromStop, ride.toStop)
            // The next ones the rider could take instead.
            for (other in same.filter { it.route in routes && it.key != bus?.key }) {
                out[Moovit.ArrivalKey(ride.fromStop, tripIdOf(other.key))] =
                    dueArrival(other, ride.fromStop, ride.lineId, pattern = pattern)
            }
            if (ending) for (id in ride.stops.drop(1)) {
                val at = codeOf(id).takeIf { it > 0 } ?: continue
                for (d in due.filter { it.stop == at && it.route in routes }) {
                    val mine = d.key == bus?.key
                    val trip = if (mine) ride.tripId else tripIdOf(d.key)
                    out[Moovit.ArrivalKey(id, trip)] = dueArrival(d, id, ride.lineId, trip, if (mine && id == ride.toStop) ride.arr else 0)
                }
            }
            if (bus == null) continue
            out[Moovit.ArrivalKey(ride.fromStop, ride.tripId)] =
                dueArrival(bus, ride.fromStop, ride.lineId, ride.tripId, timetabled ?: ride.dep, pattern)
        }
        return Rides(out, patterns)
    }

    // The plan's own bus among those due at its stop, by when the timetable has it there: Kav's timetable, else the
    // ministry's own time from before it set out. One out on a trip Kav's timetable lacks (trains, mostly) goes by its
    // live time against the plan's, which counts the delay Moovit expects.
    internal fun chooseBus(same: List<Due>, planned: Int, slack: Int, dep: Long, scheduled: (Due) -> Int?): Due? {
        fun gap(d: Due) = (scheduled(d) ?: d.aimed.takeIf { it > 0 }?.let(::secondOfDay))
            ?.let { abs(Math.floorMod(it - planned + 43_200, 86_400) - 43_200) }
        val timed = same.mapNotNull { d -> gap(d)?.let { d to it } }
        timed.minByOrNull { it.second }?.takeIf { it.second <= slack }?.let { return it.first }
        return same.filter { gap(it) == null && it.expected > 0 }.minByOrNull { abs(it.expected - dep) }
            ?.takeIf { abs(it.expected - dep) <= 300 }
    }

    // The stop in Kav's timetable with this code, or -1.
    fun stopOfCode(net: Net, code: Int): Int {
        if (code <= 0) return -1
        val index = codes?.takeIf { it.first === net }?.second ?: synchronized(this) {
            HashMap<Int, Int>(net.nStops).also { m -> for (s in 0 until net.nStops) m.putIfAbsent(net.code[s], s) }
                .also { codes = net to it }
        }
        return index[code] ?: -1
    }

    @Volatile private var codes: Pair<Net, Map<Int, Int>>? = null

    // The newer of the two positions. Moovit puts the bus on its route, so its own stays when it's the same report,
    // which still gets the heading and speed only the ministry gives.
    fun fresher(a: Moovit.Arrival, spots: Map<String, Spot>): Moovit.Arrival {
        if (!a.hasLocation) return a
        val s = spots[a.vehicleId] ?: return a
        return when {
            s.at > a.sampleUtc -> a.movedTo(s.lat, s.lon, s.at, s.bearing, s.speed)
            s.at == a.sampleUtc -> a.movedTo(a.lat, a.lon, a.sampleUtc, s.bearing, s.speed)
            else -> a
        }
    }

    fun fresher(live: Map<Moovit.ArrivalKey, Moovit.Arrival>, spots: Map<String, Spot>): Map<Moovit.ArrivalKey, Moovit.Arrival> =
        if (spots.isEmpty()) live else live.mapValues { fresher(it.value, spots) }
}
