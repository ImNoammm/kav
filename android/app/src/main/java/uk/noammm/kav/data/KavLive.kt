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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import uk.noammm.kav.BuildConfig

// Live buses from Kav's own server (server/live.py), which reads the Ministry of Transport's real-time feed. With it on,
// every bus, position and live time comes from there, and the server plans trips too (KavPlan). The vehicle id of a
// tracked arrival is the ministry's own name for the journey, route_firstdeparture_vehicle.
object KavLive {
    // Kav's own server, or the rider's: a copy of server/ answering over HTTPS, at the address set in Settings, with the
    // key set beside it when it takes only requests signed with one (its KAV_APP_KEY). The rider's own is set up once,
    // for live locations and trip planning alike; ownServer is empty until it is.
    const val KAV_SERVER = "https://kav.noammm.uk"
    var ownServer by mutableStateOf("")
    var ownKey by mutableStateOf("")

    // Where live locations come from, Kav's server until the settings are read. Trip planning has its own choice
    // (KavPlan.source).
    var source by mutableStateOf(Source.KAV)

    // The server a choice asks: the rider's own once it's set up, Kav's otherwise.
    fun serverOf(s: Source): String = if (s == Source.OWN && ownServer.isNotEmpty()) ownServer else KAV_SERVER
    val server get() = serverOf(source)
    val key get() = ownKey
    val own get() = server != KAV_SERVER

    // The server the daily timetable comes from (Sources.timetableFrom).
    val timetableServer get() = Sources.timetableFrom(source, KavPlan.source, ownServer)

    // What requests to a server are signed with: the key Kav is built with for Kav's, the rider's for their own.
    fun keyFor(at: String, own: String = key): String = if (at == KAV_SERVER) BuildConfig.KAV_LIVE_KEY else own

    // The server asks the ministry directly for at most this many routes, or stops, at once.
    const val FOLLOW_MAX = 4
    const val STOPS_MAX = 20

    // Live locations always come from a server, and whether it's answering with live buses. While it isn't, the screens
    // keep asking it and say so.
    val chosen get() = true
    var up by mutableStateOf(true)
    val enabled get() = chosen && up
    // Whether the ministry answers the server about single stations and lines. Its snapshot can be fine while those
    // fail, and then the screens that need them keep asking until it answers again.
    var stopsUp by mutableStateOf(true)
    val stopsEnabled get() = enabled && stopsUp

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
    fun routesOf(arrivals: Collection<Transit.Arrival>): List<Int> =
        arrivals.asSequence().filter { it.hasLocation }.mapNotNull { routeOf(it.vehicleId) }.distinct().toList()

    // The routes of a trip's rides that have a tracked bus, the planned buses' first. Not a train's: it's where its
    // times put it (withTrainsPlaced), and the ministry's position for it, which can stay where the train stood with a
    // fresh time while it runs on, would take it back there.
    fun tripRoutes(trip: Transit.Itinerary, r: Transit.Resolved): List<Int> {
        val rides = trip.rides.flatMap { it.options }.filter { !r.isTrain(it) }
        return routesOf(rides.mapNotNull { r.arrival(it) } + rides.flatMap { r.liveFor(it) })
    }

    // The trip's buses where the server last saw them, for what runs outside the screen; nothing when it's off or away.
    fun tripSpots(trip: Transit.Itinerary, r: Transit.Resolved): Map<String, Spot> {
        if (!enabled) return emptyMap()
        val routes = tripRoutes(trip, r)
        if (routes.isEmpty()) return emptyMap()
        return try { ask(routes).spots } catch (e: java.io.IOException) { emptyMap() }
    }

    private fun get(path: String, quick: Boolean = false, at: String = server): String {
        val c = (URL("$at/live/v1/$path").openConnection() as HttpURLConnection).apply {
            // A server that answers connects within a few seconds; one that doesn't costs the connect timeout on each of
            // its addresses (Kav's has four).
            if (quick) { connectTimeout = 4_000; readTimeout = 4_000 } else { connectTimeout = 5_000; readTimeout = 15_000 }
            setRequestProperty("User-Agent", "Kav")
            setRequestProperty("Accept", "application/json")
            sign(this, "/live/v1/$path", keyFor(at))
        }
        try {
            val code = c.responseCode
            if (code == 503) {
                // Busy is the server's own load, gone by the next turn: not the ministry going quiet.
                val why = runCatching { JSONObject(c.errorStream.bufferedReader().use { it.readText() }).optString("error") }.getOrDefault("")
                if (why == "busy") throw java.io.IOException("Kav live busy")
                throw Refused()
            }
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

    fun sign(c: HttpURLConnection, path: String, key: String) {
        if (key.isNotEmpty()) c.setRequestProperty("X-Kav", mark(path, key, System.currentTimeMillis()))
    }

    // Whether the server is answering with live buses, waiting only a few seconds to know.
    fun answering(at: String = server): Boolean =
        try { JSONObject(get("status", quick = true, at = at)).optBoolean("ok") } catch (e: Exception) { false }

    // What a server says about itself, for Settings' "Test connection": each part of it a rider would miss, and the
    // public half of the key its timetable is signed with, when it isn't Kav's (server/selfhost makes one). An older
    // server says nothing of its parts: null.
    sealed class Check {
        class Unreached(val why: Why) : Check()
        // It turned the request away, as a server does a request signed with a key it doesn't take, or with none.
        object Refused : Check()
        // Something answers there, but not as Kav's server does.
        object NotKav : Check()
        class Answers(
            val live: Boolean, val vehicles: Int, val at: Long, val planner: Boolean?, val predictions: Boolean?,
            val timetable: String?, val bundleKey: String?,
        ) : Check()

        enum class Why { NO_SUCH_HOST, HTTPS, TIMEOUT, NO_ANSWER }
    }

    fun check(at: String, key: String): Check {
        val c = try {
            URL("$at/live/v1/status").openConnection() as HttpURLConnection
        } catch (e: java.io.IOException) {
            return Check.Unreached(Check.Why.NO_SUCH_HOST)
        }
        return try {
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "Kav")
            c.setRequestProperty("Accept", "application/json")
            sign(c, "/live/v1/status", keyFor(at, key))
            val code = c.responseCode
            status(code, (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() })
        } catch (e: java.net.UnknownHostException) {
            Check.Unreached(Check.Why.NO_SUCH_HOST)
        } catch (e: javax.net.ssl.SSLException) {
            Check.Unreached(Check.Why.HTTPS)
        } catch (e: java.net.SocketTimeoutException) {
            Check.Unreached(Check.Why.TIMEOUT)
        } catch (e: java.io.IOException) {
            Check.Unreached(Check.Why.NO_ANSWER)
        } finally {
            c.disconnect()
        }
    }

    // live.py answers a request it won't take as it answers a path it doesn't know: 404, {"error": "not found"}.
    internal fun status(code: Int, body: String?): Check {
        val o = runCatching { JSONObject(body ?: "") }.getOrNull()
        if (code == 404 && o?.optString("error") == "not found") return Check.Refused
        if (code != 200 || o == null || !o.has("ok")) return Check.NotKav
        fun flag(name: String) = if (o.has(name)) o.optBoolean(name) else null
        return Check.Answers(
            live = o.optBoolean("ok"), vehicles = o.optInt("vehicles"), at = o.optLong("at"),
            planner = flag("planner"), predictions = flag("predictions"),
            timetable = if (o.has("timetable")) o.optString("timetable") else null,
            bundleKey = o.optString("bundleKey").takeIf { it.isNotEmpty() },
        )
    }

    // A server's address as typed, the way requests are sent to it: https:// when it names none, without the API's own
    // path or a slash at the end. Null when it isn't an https address: Android keeps apps off plain http.
    fun serverAddress(typed: String): String? {
        val s = typed.trim().trimEnd('/').removeSuffix("/live/v1").trimEnd('/')
        if (s.isEmpty()) return null
        val url = if ("://" in s) s else "https://$s"
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrBlank() || u.rawQuery != null || u.rawFragment != null) return null
        // The host in lower case, as DNS and certificates take it: KAV.NOAMMM.UK is Kav's own.
        return "https://" + u.rawAuthority.lowercase() + url.substring(u.scheme.length + 3 + u.rawAuthority.length)
    }

    // A trip a server plans (KavPlan), as it answers: the one chosen for planning, not live locations.
    fun planned(path: String, at: String): String = get(path, at = at)

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
    fun stops(codes: Collection<Int>): Board = try {
        parseBoard(get("stops/" + codes.filter { it > 0 }.distinct().sorted().take(STOPS_MAX).joinToString(",")))
            .also { stopsUp = true }
    } catch (e: Refused) {
        stopsUp = false
        throw e
    }

    // Whether the ministry answers about stations again: any station would do, Savidor is one.
    fun stopsAnswering(): Boolean = try { stops(listOf(17038)); true } catch (e: Exception) { false }

    // The server is up but couldn't get the ministry's answer, or has no fresh one.
    class Refused : java.io.IOException("Kav live HTTP 503")

    private val shapes = object : LinkedHashMap<Int, List<Pair<Double, Double>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Pair<Double, Double>>>?) = size > 40
    }

    // The road a route takes, from the ministry's timetable. A route's road doesn't change in a day, so it's kept; an
    // empty answer, as a server gives while it restarts, isn't, so the route is asked about again.
    fun shape(route: Int): List<Pair<Double, Double>> {
        synchronized(shapes) { shapes[route] }?.let { return it }
        val path = decodePolyline(JSONObject(get("shape/$route")).optString("shape"))
        if (path.size >= 2) synchronized(shapes) { shapes[route] = path }
        return path
    }

    fun shapeNow(route: Int): List<Pair<Double, Double>> = synchronized(shapes) { shapes[route] }.orEmpty()

    // A road as the server writes it: Google's encoded polyline, five decimal places.
    fun decodePolyline(encoded: String?): List<Pair<Double, Double>> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(encoded.length / 4)
        var i = 0; var lat = 0; var lon = 0
        while (i < encoded.length) {
            var shift = 0; var result = 0; var b: Int
            do {
                if (i >= encoded.length) return out
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift); shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do {
                if (i >= encoded.length) return out
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift); shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(lat / 1e5 to lon / 1e5)
        }
        return out
    }

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

    // What the server says, in the shape the screens take (Transit.Arrival). Ids made up here are negative, so they never
    // meet one of Kav's planner's.
    fun tripIdOf(key: String): Long {
        var h = -3750763034362895579L
        for (c in key) { h = (h xor c.code.toLong()) * 1099511628211L }
        return -(h and Long.MAX_VALUE) - 1
    }

    // An operator that stands for a kind of line: the kind is all the screens take from it.
    fun agencyOf(type: Int) = -1000 - type

    // Where a line goes: the town for a line between towns, else the stop.
    fun lineInfo(line: Line?, number: String): Transit.LineInfo {
        val far = line != null && line.toCity.isNotBlank() && line.toCity != line.fromCity
        return Transit.LineInfo(
            -1, line?.number?.ifBlank { null } ?: number, agencyOf(line?.type ?: 3),
            (if (far) line?.fromCity else line?.from).orEmpty(), (if (far) line?.toCity else line?.to).orEmpty(), "",
        )
    }

    fun busArrival(bus: Bus): Transit.Arrival = Transit.Arrival(
        -1, -bus.route, tripIdOf(bus.key), 0, 0, 0, 0, 0, 0, false, false, true,
        bus.spot.lat, bus.spot.lon, bus.key, bus.spot.at, 0, -1, -1, -1, -1, -1, "", bus.spot.bearing, bus.spot.speed,
    )

    // A bus's position this many seconds old is live; to STALE_S it's an estimate; past that it may be well off.
    // Buses report every 15-40 s.
    const val FRESH_S = 90
    const val STALE_S = 210

    // How sure a live time is (1 sure, 2 fairly, 3 maybe not): by how old the bus's
    // position is.
    fun certaintyOf(d: Due, now: Long): Int {
        val age = d.spot?.let { now - it.at } ?: return 3
        return if (age < FRESH_S) 1 else if (age < STALE_S) 2 else 3
    }

    // A bus due at a stop. On its way: the plan's time and the ministry's live one. Not yet out: "not departed", at the
    // ministry's own time, which can be minutes off the timetable Kav plans with.
    fun dueArrival(
        d: Due, stopId: Int, lineId: Int = -d.route, tripId: Long = tripIdOf(d.key), planned: Long = 0, pattern: Int = -1,
        now: Long = System.currentTimeMillis() / 1000,
    ): Transit.Arrival = Transit.Arrival(
        stopId, lineId, tripId,
        if (d.underWay) planned.takeIf { it > 0 } ?: d.aimed.takeIf { it > 0 } ?: d.expected
        else d.expected.takeIf { it > 0 } ?: d.aimed.takeIf { it > 0 } ?: planned,
        if (d.underWay) d.expected else 0, 0, 0, if (d.underWay) certaintyOf(d, now) else 0, 0, false, false, d.spot != null,
        d.spot?.lat ?: 0.0, d.spot?.lon ?: 0.0, d.key, d.spot?.at ?: 0, if (d.underWay) 0 else 3,
        -1, -1, -1, -1, pattern, d.platform, d.spot?.bearing ?: -1, d.spot?.speed ?: -1,
    )

    // What a trip's live layer from the ministry holds: the arrivals, and for the line's next buses at a boarding stop
    // a made-up pattern of the two stops they're known to serve.
    class Rides(val live: Map<Transit.ArrivalKey, Transit.Arrival>, val patterns: Map<Int, List<Int>>)

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
    // Whether a trip the timetable has set out at second s of its day is the one that set out at first: by the clock,
    // as the ministry names most trips, or by noon less twelve hours, as a timetable counts, which the night the clocks go
    // back is an hour off the clock (the ministry names that Saturday's trips after two by it).
    internal fun startsAt(s: Int, first: Long): Boolean {
        val off = abs(s % 86_400 - secondOfDay(first))
        return off <= 60 || off >= 86_340 || abs(secondOfDay(first - s + 43_200) - 43_200) <= 60
    }

    // Of the route the ministry names, by its long name (name), where the timetable has it: both ways of a line can set
    // out the same minute.
    internal fun stopTimeOf(net: Net, stop: Int, number: String, first: Long, name: String = ""): Int {
        var found = -1
        var best = 4
        val goal = if (name.isEmpty()) 2 else 0
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            if (net.rShort[net.tripRoute[t]] != number) continue
            if (!startsAt(net.stDep[net.tripStart[t]], first)) continue
            val rank = (if (name.isNotEmpty() && net.rLong[net.tripRoute[t]] == name) 0 else 2) + if (runsThen(net, t, first)) 0 else 1
            if (rank < best) { best = rank; found = st }
            if (rank == goal) break
        }
        return found
    }

    // When the timetable has that trip leave the stop. Seconds from the timetable's midnight, or null if it hasn't.
    fun scheduledAt(net: Net, stop: Int, number: String, first: Long, name: String = ""): Int? =
        stopTimeAt(net, stop, number, first, name)?.let { net.stDep[it] }

    // The timetable's stop time of that trip at the stop, or null.
    private fun stopTimeAt(net: Net, stop: Int, number: String, first: Long, name: String = ""): Int? =
        stopTimeOf(net, stop, number, first, name).takeIf { it >= 0 }

    // The same for a train, which the timetable keeps without its number: the train trip that left its first station
    // when this one did, leaving the stop nearest when it's expected (two can set out the same minute), of those that
    // run that day first.
    fun trainAt(net: Net, stop: Int, first: Long, expected: Long): Int? {
        val due = secondOfDay(expected)
        var found: Int? = null
        var best = Int.MAX_VALUE
        for (i in net.dStart[stop] until net.dStart[stop + 1]) {
            val st = net.cST[net.dConn[i]]
            val t = net.tripOf(st)
            val route = net.tripRoute[t]
            if (net.rType[route] != 2 || net.rShort[route].isNotEmpty()) continue
            if (!startsAt(net.stDep[net.tripStart[t]], first)) continue
            val late = abs(Math.floorMod(net.stDep[st] - due + 43_200, 86_400) - 43_200)
            if (late > 3_600) continue
            val rank = late + if (runsThen(net, t, first)) 0 else 86_400
            if (rank < best) { best = rank; found = st }
        }
        return found
    }

    // A live time on a stop's board: the timetable trip it is, and when that was due at the stop and now is, in
    // seconds from the board's midnight, as its rows count them: a trip from yesterday still running counts past it.
    // to is where a train really goes when that isn't where the timetable has it go, else null. One the timetable has no
    // trip for has trip -1, its live time for both, and to where the server has its line go.
    class BoardTime(val due: Due, val trip: Int, val static: Int, val rt: Int, val to: String? = null)

    // The buses and trains due at the stop that are under way, each on the board's row it is. now is an epoch second.
    // One the timetable hasn't (a trip it doesn't run that day, works, a change made after it was built) is on a row of
    // its own: dropped, a stop can look as if nothing there were live. Only the stop's own: a
    // station's platforms share its code, so
    // there one of a line the platform's timetable has.
    fun boardTimes(net: Net, stop: Int, board: Board, now: Long): List<BoardTime> {
        val midnight = now - secondOfDay(now)
        val code = net.code.getOrElse(stop) { 0 }
        val platform = net.platform.getOrElse(stop) { "" }.isNotEmpty()
        fun own(d: Due): BoardTime? {
            if (d.stop != code || platform && !net.serves(stop, d.number)) return null
            val rt = (d.expected - midnight).toInt()
            return BoardTime(d, -1, rt, rt, board.lines[d.route]?.to?.ifEmpty { null })
        }
        // Only this stop's: the saved stops are asked for at once, and a bus due at one isn't due at another then.
        return board.due.filter { it.stop == code && it.underWay && it.expected > 0 }.mapNotNull { d ->
            // A train is in the timetable without its number: found by when it set out instead.
            val st = stopTimeAt(net, stop, d.number, d.first, board.lines[d.route]?.name.orEmpty()) ?: trainAt(net, stop, d.first, d.expected)
                ?: return@mapNotNull own(d)
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
                    ?: return@mapNotNull own(d)
                return@mapNotNull BoardTime(d, row.trip, static, rt, row.to)
            }
            val to = net.cutShort(st, ends) ?: dest.takeIf { it.isNotEmpty() && !ends(net.name[net.tripLast(t)]) }
            BoardTime(d, t, static, rt, to)
        }
    }

    // Whether the timetable has the line of that number leave the stop on any day.
    private fun Net.serves(stop: Int, number: String): Boolean =
        (dStart[stop] until dStart[stop + 1]).any { rShort[tripRoute[tripOf(cST[dConn[it]])]] == number }

    // Whether a station's name in the timetable is the one the ministry names a route's end by, which can be shorter
    // ("בית שאן" for "בית שאן - דוד לוי").
    private fun goesTo(dest: String): (String) -> Boolean =
        { name -> dest.isNotEmpty() && (name.startsWith(dest) || dest.startsWith(name)) }

    // The ministry's trains at a station not yet out that end early, and their numbers and platforms, on the board's
    // rows (boardTimes places those under way). Only what it lists counts: its answers can leave out a train that runs,
    // so none is said not to.
    fun trainsToday(net: Net, stop: Int, board: Board, now: Long): TrainsToday {
        val midnight = now - secondOfDay(now)
        val ends = HashMap<Int, String>()
        val named = HashMap<Int, TrainName>()
        for (d in board.due) {
            val line = board.lines[d.route] ?: continue
            if (line.type != 2 || d.underWay || d.aimed <= 0) continue
            val row = net.trainLeaving(stop, (d.aimed - midnight).toInt(), dayOf(now), goesTo(line.from), goesTo(line.to))
                ?: continue
            row.to?.let { ends[row.trip] = it }
            TrainName.of(d.number, d.platform)?.let { named[row.trip] = it }
        }
        return TrainsToday(ends = ends, named = named)
    }

    // A train line's trains for its page, routes being the timetable's for it: a train has no number to ask the server
    // for its line by, so its stations are asked, and each train due at one is placed on the timetable by when it set out.
    fun trainsOn(net: Net, routes: Set<Int>, stations: List<Int>): List<Transit.Arrival> {
        val board = boardAt(stations.map { net.code[it] })
        val now = System.currentTimeMillis() / 1000
        return trainsOn(net, routes, withTrainsPlaced(net, board, withoutLostTrains(board, now) { stationAt(net, it) }, now) { emptyList() })
    }

    internal fun trainsOn(net: Net, routes: Set<Int>, due: List<Due>): List<Transit.Arrival> =
        due.filter { it.underWay && it.spot != null }.groupBy { it.key }.values.mapNotNull { rows ->
            rows.firstOrNull { d ->
                val stop = stopOfCode(net, d.stop)
                val st = if (stop >= 0) trainAt(net, stop, d.first, d.expected) else null
                st != null && net.tripRoute[net.tripOf(st)] in routes
            }
        }.map { d -> busArrival(Bus(d.key, d.route, d.spot!!)) }

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

    // A train's times hold where its position may not, so trains under way are placed where their times put them
    // (trainSpot); the ministry's position stays only for one the timetable can't place. track(row) is the train's
    // track where it's known: the ministry's timetable has none for trains.
    internal fun withTrainsPlaced(
        net: Net?, board: Board, due: List<Due>, now: Long, track: (Due) -> List<Pair<Double, Double>>,
    ): List<Due> {
        if (net == null) return due
        val placed = due.filter { it.underWay && board.lines[it.route]?.type == 2 }.groupBy { it.key }
            .mapValues { (_, rows) -> trainSpot(net, rows, now, track(rows[0])) }
        return due.map { d ->
            val spot = placed[d.key] ?: return@map d
            Due(d.stop, d.route, d.key, d.number, d.first, d.aimed, d.expected, spot, d.order, d.platform)
        }
    }

    // Where a train is now, the way the operator's own "last station, next station, delay" puts it: the ministry's time
    // at the next station it's due at moves the timetable's, and the train is that far between the station it last
    // left and the next, along its track. Its positions can be stuck far behind while its times hold. rows are one
    // train's, at any of its stations; null when it can't be placed or has done its run.
    internal fun trainSpot(net: Net, rows: List<Due>, now: Long, shape: List<Pair<Double, Double>>): Spot? {
        for (d in rows.filter { it.expected > 0 }.sortedBy { it.expected }) {
            val stop = stopOfCode(net, d.stop).takeIf { it >= 0 } ?: continue
            val st = trainAt(net, stop, d.first, d.expected) ?: trainEndingAt(net, stop, d.first, d.expected) ?: continue
            val t = net.tripOf(st)
            fun at(k: Int) = d.expected + (net.stDep[k] - net.stDep[st])
            fun point(k: Int) = net.lat[net.stStop[k]] to net.lon[net.stStop[k]]
            val first = net.tripStart[t]
            val next = (first until net.tripStart[t + 1]).firstOrNull { at(it) > now } ?: return null
            if (next == first) return Spot(point(first).first, point(first).second, now)
            val f = (now - at(next - 1)).toDouble() / (at(next) - at(next - 1)).coerceAtLeast(1)
            val (p, heading) = along(shape, point(next - 1), point(next), f)
            return Spot(p.first, p.second, now, heading)
        }
        return null
    }

    // The same as trainAt for a train on its last leg, due only at the station its trip ends at, which trainAt can't find
    // it by: no departure leaves from there.
    private fun trainEndingAt(net: Net, stop: Int, first: Long, expected: Long): Int? {
        val due = secondOfDay(expected)
        var found: Int? = null
        var best = Int.MAX_VALUE
        for (t in net.tripRoute.indices) {
            val end = net.tripStart[t + 1] - 1
            if (end <= net.tripStart[t] || net.stStop[end] != stop) continue
            val route = net.tripRoute[t]
            if (net.rType[route] != 2 || net.rShort[route].isNotEmpty()) continue
            if (!startsAt(net.stDep[net.tripStart[t]], first)) continue
            val late = abs(Math.floorMod(net.stDep[end] - due + 43_200, 86_400) - 43_200)
            if (late > 3_600) continue
            val rank = late + if (runsThen(net, t, first)) 0 else 86_400
            if (rank < best) { best = rank; found = end }
        }
        return found
    }

    // The point f (0 to 1) of the way from a to b along the track, where the shape has one between them, else straight;
    // and the way it heads there, in degrees from north.
    internal fun along(
        shape: List<Pair<Double, Double>>, a: Pair<Double, Double>, b: Pair<Double, Double>, f: Double,
    ): Pair<Pair<Double, Double>, Int> {
        fun apart(p: Pair<Double, Double>, q: Pair<Double, Double>) =
            uk.noammm.kav.ui.metres(p.first, p.second, q.first, q.second)
        val path = if (shape.size < 2) listOf(a, b) else {
            val ia = shape.indices.minBy { apart(shape[it], a) }
            val ib = shape.indices.minBy { apart(shape[it], b) }
            when {
                ia < ib -> listOf(a) + shape.subList(ia + 1, ib) + listOf(b)
                ia > ib -> listOf(a) + shape.subList(ib + 1, ia).reversed() + listOf(b)
                else -> listOf(a, b)
            }
        }
        val legs = path.zipWithNext { p, q -> apart(p, q) }
        var left = legs.sum() * f.coerceIn(0.0, 1.0)
        for ((i, leg) in legs.withIndex()) {
            if (left > leg && i < legs.lastIndex) { left -= leg; continue }
            val g = if (leg > 0) (left / leg).coerceIn(0.0, 1.0) else 0.0
            val p = path[i]; val q = path[i + 1]
            val dy = q.first - p.first
            val dx = (q.second - p.second) * cos(Math.toRadians(p.first))
            val heading = if (dx == 0.0 && dy == 0.0) -1 else Math.floorMod(Math.toDegrees(atan2(dx, dy)).roundToInt(), 360)
            return (p.first + dy * g to p.second + (q.second - p.second) * g) to heading
        }
        return a to -1
    }

    // Whether the timetable's trip goes on from that stop time of it to the stop.
    internal fun goesOn(net: Net, st: Int?, to: Int): Boolean =
        st != null && st >= 0 && to >= 0 && (st + 1 until net.tripStart[net.tripOf(st) + 1]).any { net.stStop[it] == to }

    // How far a bus can be from every stop of a trip and still be on it: beyond this it's another town's line that
    // shares its number.
    private const val ON_TRIP_M = 1_500.0

    // The stops, in order, of the timetable's trip of this line that left its first stop at first, among those that pass
    // the bus: of the route the ministry names when the timetable has that name, then of the line's kind (type, -1 when
    // not known), the one nearest the bus. Empty if it hasn't the trip, rather than another line's of that number.
    fun tripStops(net: Net, number: String, name: String, first: Long, lat: Double, lon: Double, type: Int = -1): List<Int> {
        fun away(t: Int) = (net.tripStart[t] until net.tripStart[t + 1]).minOf { k ->
            val s = net.stStop[k]
            uk.noammm.kav.ui.metres(lat, lon, net.lat[s], net.lon[s])
        }
        val near = net.tripRoute.indices.filter { t ->
            net.rShort[net.tripRoute[t]] == number && startsAt(net.stDep[net.tripStart[t]], first) && away(t) <= ON_TRIP_M
        }
        val named = near.filter { name.isNotEmpty() && net.rLong[net.tripRoute[it]] == name }.ifEmpty { near }
        val trip = named.filter { type < 0 || net.rType[net.tripRoute[it]] == type }.ifEmpty { named }.minByOrNull(::away)
            ?: return emptyList()
        return (net.tripStart[trip] until net.tripStart[trip + 1]).map { net.stStop[it] }
    }

    // A trip's rides as its live layer has them, from the ministry: each ride's own bus, the one
    // the timetable has leave the boarding stop when the plan says, timed where the rider boards and, on the trip being
    // taken, at every stop of the ride, as are the line's other buses. A ride whose own bus can't be told apart keeps
    // its plan's time, and the line's next buses still give its departures.
    // board: what's due at a list of stops, by their codes, from the server live locations come from.
    fun rideArrivals(
        list: List<Transit.Itinerary>, stops: Map<Int, Transit.StopInfo>, lines: Map<Int, Transit.LineInfo>,
        ending: Boolean, net: Net?, board: (List<Int>) -> Board = { KavLive.stops(it) },
    ): Rides {
        // A plan's ride time already counts the delay expected; the timetable's own time for it is in its departures.
        val rides = list.flatMap { i ->
            val deps = i.legs.flatMap { it.options }.flatMap { it.nextDeps }
            i.rides.flatMap { it.options }.filter { it.kind == Transit.LegKind.RIDE && it.tripId != 0L && it.fromStop > 0 }
                .map { ride -> ride to deps.firstOrNull { it.tripId == ride.tripId && it.staticUtc > 0 }?.staticUtc }
        }.distinctBy { Triple(it.first.fromStop, it.first.tripId, it.first.toStop) }
        val codes = rides.flatMap { (r, _) -> listOf(r.fromStop) + if (ending) r.stops.drop(1) else emptyList() }
            .map { stopCodeOf(stops, it) }.filter { it > 0 }.distinct()
        if (codes.isEmpty()) return Rides(emptyMap(), emptyMap())
        // A long ride's stops take several requests: all at once, rather than each waiting on the last.
        val chunks = codes.chunked(STOPS_MAX)
        val boards = if (chunks.size == 1) listOf(board(chunks[0])) else {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(chunks.size, 4))
            try {
                chunks.map { c -> pool.submit<Board> { board(c) } }.map { it.get() }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdown()
            }
        }
        val all = Board(boards.flatMap { it.due }, boards.fold(HashMap<Int, Line>()) { m, b -> m.apply { putAll(b.lines) } }, 0)
        // The line's departures each ride's card lists, from the wait before it.
        val waits = list.flatMap { i -> i.legs.filter { it.kind == Transit.LegKind.WAIT }.flatMap { it.options } }
        return rideArrivals(rides, all, stops, lines, ending, net, System.currentTimeMillis() / 1000) { ride ->
            waits.filter { it.lineId == ride.lineId && it.fromStop == ride.fromStop }.flatMap { it.nextDeps }
        }
    }

    private fun stopCodeOf(stops: Map<Int, Transit.StopInfo>, stop: Int) =
        stops[stop]?.code?.toIntOrNull() ?: 0

    // The same from what the ministry has due at the rides' stops: rides each with the timetable's time for it, if known;
    // others(ride), the line's departures its card lists.
    internal fun rideArrivals(
        rides: List<Pair<Transit.Leg, Long?>>, board: Board, stops: Map<Int, Transit.StopInfo>,
        lines: Map<Int, Transit.LineInfo>, ending: Boolean, net: Net?, now: Long,
        others: (Transit.Leg) -> List<Transit.Departure> = { emptyList() },
    ): Rides {
        fun codeOf(stop: Int) = stopCodeOf(stops, stop)
        // A train on a ride follows the track the plan draws for it.
        val tracks = rides.associate { (ride, _) -> ride.shortName.ifBlank { lines[ride.lineId]?.number.orEmpty() }.trim() to ride.shape }
        val due = withTrainsPlaced(net, board, withoutLostTrains(board, now) { code ->
            net?.let { stationAt(it, code) } ?: stops.values.firstOrNull { it.code.toIntOrNull() == code }?.point
        }, now) { tracks[it.number].orEmpty() }
        val out = LinkedHashMap<Transit.ArrivalKey, Transit.Arrival>()
        val patterns = HashMap<Int, List<Int>>()
        // Each ride's own bus, there under its plan's trip: not again among its line's others (one bus shown twice).
        val owned = ArrayList<Triple<Int, Due, Int>>()
        for ((ride, timetabled) in rides) {
            // A ride hours from now, another day's maybe: the buses due now aren't its.
            if (ride.dep - now > FAR || now - ride.arr > FAR) continue
            val code = codeOf(ride.fromStop)
            val number = ride.shortName.ifBlank { lines[ride.lineId]?.number.orEmpty() }.trim()
            if (code <= 0 || number.isEmpty()) continue
            val stop = net?.let { stopOfCode(it, code) } ?: -1
            val planned = secondOfDay(timetabled ?: ride.dep)
            // Without the timetable's time, the plan's own is within the expected delay of it.
            val slack = if (timetabled != null) 120 else 1200
            val same = sameLine(due, code, ride, number)
            // The timetable keeps a train without its number: it's found by when it set out and is due instead. A train
            // Kav planned is one by its line's kind too, once the board no longer has it.
            val train = (KavPlan.routeOf(ride.lineId)?.let { board.lines[it] } ?: same.firstOrNull()?.let { board.lines[it.route] })?.type == 2 ||
                lines[ride.lineId]?.agencyId?.let(KavPlan::typeOf) == 2
            val atStop = { d: Due ->
                if (net == null || stop < 0) null else if (train) trainAt(net, stop, d.first, d.expected)
                else stopTimeAt(net, stop, number, d.first, board.lines[d.route]?.name.orEmpty())
            }
            val (bus, ridden) = riderBus(ride, same, chooseBus(same, planned, slack, ride.dep) { d ->
                atStop(d)?.let { net!!.stDep[it] }
            }, now)
            // The line's buses that go on to where the rider gets off: the plan's own bus's route, and any route the
            // timetable has get there, even once the plan's bus has gone.
            val to = net?.let { stopOfCode(it, codeOf(ride.toStop)) } ?: -1
            val routes = (same.map { it.route } + listOfNotNull(ridden?.route)).distinct().filter { route ->
                route == ridden?.route || net != null && same.any { it.route == route && goesOn(net, atStop(it), to) }
            }
            // A train line's other trains are routes of their own, which sameLine leaves out: those its card lists are its
            // trains between the same two ends under way, due at the stop then and going on to where the rider gets off.
            val runs = if (!train || net == null || stop < 0) emptyList() else {
                val plan = lines[ride.lineId]
                val free = due.filter { d ->
                    val line = board.lines[d.route]
                    d.stop == code && d.underWay && d.route !in routes && line != null && line.type == 2 &&
                        line.from == plan?.origin && line.to == plan?.destination &&
                        goesOn(net, trainAt(net, stop, d.first, d.expected), to)
                }.toMutableList()
                others(ride).filter { it.tripId != 0L && it.tripId != ride.tripId }.sortedBy { it.timeUtc }.mapNotNull { p ->
                    free.filter { abs(it.expected - p.timeUtc) <= 120 }.minByOrNull { abs(it.expected - p.timeUtc) }
                        ?.also { free.remove(it) }?.let { p.tripId to it }
                }
            }
            if (routes.isEmpty() && runs.isEmpty()) continue
            // One made-up pattern a line at the stop, naming every stop its rides from there get off at: a pattern a
            // ride kept the last ride's stop, and the line's buses went from the other routes boarding there.
            val pattern = -2 - Math.floorMod(ride.fromStop.toLong() * 1_000_003 + ride.lineId, 1_000_000_000L).toInt()
            patterns[pattern] = ((patterns[pattern] ?: listOf(ride.fromStop)) + ride.toStop).distinct()
            // The next ones the rider could take instead.
            for (other in same.filter { it.route in routes && !sameTrip(it, ridden) }) {
                out[Transit.ArrivalKey(ride.fromStop, tripIdOf(other.key))] =
                    dueArrival(other, ride.fromStop, ride.lineId, pattern = pattern)
            }
            for ((trip, d) in runs) out[Transit.ArrivalKey(ride.fromStop, trip)] = dueArrival(d, ride.fromStop, ride.lineId, trip, pattern = pattern)
            if (ending) for (id in ride.stops.drop(1)) {
                val at = codeOf(id).takeIf { it > 0 } ?: continue
                for (d in due.filter { it.stop == at && it.route in routes && (it.route > 0 || it.number == number) }) {
                    val mine = sameTrip(d, ridden)
                    val trip = if (mine) ride.tripId else tripIdOf(d.key)
                    out[Transit.ArrivalKey(id, trip)] = dueArrival(d, id, ride.lineId, trip, if (mine && id == ride.toStop) ride.arr else 0)
                }
            }
            if (bus == null) continue
            owned += Triple(ride.fromStop, bus, ride.lineId)
            out[Transit.ArrivalKey(ride.fromStop, ride.tripId)] =
                dueArrival(bus, ride.fromStop, ride.lineId, ride.tripId, timetabled ?: ride.dep, pattern)
        }
        for ((stop, bus, line) in owned) {
            val key = Transit.ArrivalKey(stop, tripIdOf(bus.key))
            if (out[key]?.lineId == line) out.remove(key)
        }
        return Rides(out, patterns)
    }

    // How far from now a ride can be and still be the buses due now.
    private const val FAR = 7_200L

    // The buses due at a ride's stop on its line. A ride Kav planned names the ministry's own route, which also finds a
    // train, whose plan names its route rather than the train's number; any other goes by the line's number.
    internal fun sameLine(due: List<Due>, code: Int, ride: Transit.Leg, number: String): List<Due> {
        val route = KavPlan.routeOf(ride.lineId)
        return due.filter { it.stop == code && if (route != null && it.route > 0) it.route == route else it.number == number }
    }

    // The plan's own bus among those due at its stop, by when the timetable has it there: Kav's timetable, else the
    // ministry's own time from before it set out. Failing that (a trip Kav's timetable lacks, trains mostly, or a plan
    // whose time is the planner's predicted one, read before Kav's timetable had loaded) it goes by its live time against
    // the plan's, which counts the delay expected.
    internal fun chooseBus(same: List<Due>, planned: Int, slack: Int, dep: Long, scheduled: (Due) -> Int?): Due? {
        fun gap(d: Due) = (scheduled(d) ?: d.aimed.takeIf { it > 0 }?.let(::secondOfDay))
            ?.let { abs(Math.floorMod(it - planned + 43_200, 86_400) - 43_200) }
        val timed = same.mapNotNull { d -> gap(d)?.let { d to it } }
        timed.minByOrNull { it.second }?.takeIf { it.second <= slack }?.let { return it.first }
        return same.filter { it.expected > 0 }.minByOrNull { abs(it.expected - dep) }
            ?.takeIf { abs(it.expected - dep) <= 300 }
    }

    // The bus each ride was found to take, by where the rider boards and the plan's trip, kept until two hours after
    // the ride was to end.
    private val taken = HashMap<Pair<Int, Long>, Pair<Due, Long>>()

    // The rider's bus on a ride: the one chosen among those due where they board, else the one found there before.
    // Once it has left that stop the ministry lists it there no more, and it's still the rider's at the stops after: no
    // other bus takes its place. The bus as it's due where they board, if it still is, and the rider's bus.
    internal fun riderBus(ride: Transit.Leg, same: List<Due>, chosen: Due?, now: Long): Pair<Due?, Due?> {
        val id = ride.fromStop to ride.tripId
        synchronized(taken) {
            taken.values.removeAll { it.second < now }
            val known = taken[id]?.first
            if (known != null && same.none { sameTrip(it, known) }) return null to known
            val bus = chosen ?: same.firstOrNull { sameTrip(it, known) }
            if (bus != null) taken[id] = bus to maxOf(ride.dep, ride.arr) + 7_200
            return bus to bus
        }
    }

    // A bus is its trip, the route and when it set out: its key also names the vehicle, which the ministry may not have
    // named yet, and a trip can come with another vehicle's reports at another stop.
    private fun sameTrip(d: Due, other: Due?) = other != null && d.route == other.route && d.first == other.first

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

    // The newer of the two positions. The arrival's own, placed on its route, stays when it's the same report, which
    // still gets the heading and speed only the ministry gives.
    fun fresher(a: Transit.Arrival, spots: Map<String, Spot>): Transit.Arrival {
        if (!a.hasLocation) return a
        val s = spots[a.vehicleId] ?: return a
        return when {
            s.at > a.sampleUtc -> a.movedTo(s.lat, s.lon, s.at, s.bearing, s.speed)
            s.at == a.sampleUtc -> a.movedTo(a.lat, a.lon, a.sampleUtc, s.bearing, s.speed)
            else -> a
        }
    }

    fun fresher(live: Map<Transit.ArrivalKey, Transit.Arrival>, spots: Map<String, Spot>): Map<Transit.ArrivalKey, Transit.Arrival> =
        if (spots.isEmpty()) live else live.mapValues { fresher(it.value, spots) }

    // A ride's bus or train where the server's answer for its route (spots) has it, when its live time came without a
    // position: its trip, by key. With no live time at all, as for a train that has left the rider's station, the run
    // of the route that set out last before the ride (dep), each train run being a route of its own.
    fun followed(a: Transit.Arrival?, spots: Map<String, Spot>, route: Int, dep: Long): Transit.Arrival? {
        if (a?.hasLocation == true) return a
        if (a != null) return spots[a.vehicleId]?.let { s -> a.movedTo(s.lat, s.lon, s.at, s.bearing, s.speed, tracked = true) } ?: a
        val run = spots.keys.filter { routeOf(it) == route }
            .mapNotNull { k -> firstOf(k)?.takeIf { it <= dep }?.let { k to it } }.maxByOrNull { it.second }?.first ?: return null
        return busArrival(Bus(run, route, spots.getValue(run)))
    }
}
