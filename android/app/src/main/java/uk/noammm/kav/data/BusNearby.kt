package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// Bus Nearby and egg's HopOn protocol, as shipped in their public Android client. Neither requests a user key for
// these read-only services. Routes and stop codes are the ministry's; other feeds are excluded to avoid id collisions.
// The map shows vehicles serving nearby stops, not a complete vehicle snapshot of its box.
internal object BusNearby {
    const val SERVER = "https://app.busnearby.co.il"
    private val israel = ZoneId.of("Asia/Jerusalem")
    private data class Route(val line: KavLive.Line, val codes: List<Int>, val shape: List<Pair<Double, Double>>)
    private val routes = ConcurrentHashMap<Pair<Source, Int>, Route>()
    private val seenAt = ConcurrentHashMap<Pair<Source, Int>, Int>()
    private val observedLines = ConcurrentHashMap<Pair<Source, Int>, KavLive.Line>()

    internal fun query(text: String, at: Long = System.currentTimeMillis()): String {
        val day = Instant.ofEpochMilli(at).atZone(israel)
        val key = "${day.dayOfMonth}/${day.monthValue - 1}/${day.year}"
        return Base64.getEncoder().encodeToString(ByteArray(text.length) { i ->
            (text[i].code xor key[i % key.length].code).toByte()
        })
    }

    internal fun variant(source: Source): String = if (source == Source.EGG) "egged" else "busnearby"

    private fun get(path: String, args: String, source: Source): String = fetch("$SERVER/$path?${query(args)}", source)

    class NotFound(message: String) : java.io.IOException(message)

    private fun fetch(url: String, source: Source): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "he")
            c.setRequestProperty("X-BNB-VARIANT", variant(source))
            if (c.responseCode == 404) throw NotFound("${variant(source)} HTTP 404")
            if (c.responseCode != 200) throw java.io.IOException("${variant(source)} HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // A trip shared from Bus Nearby or egg (TripLink): the itinerary its app saved under that id, asked as its app asks
    // when the link is opened, with no key. HopOn answers 404 once it has expired.
    fun shared(id: String, source: Source): TripLink.Plan = try {
        sharedPlan(JSONObject(fetch("$SERVER/itinerary/${java.net.URLEncoder.encode(id, "UTF-8")}", source)))
    } catch (e: NotFound) { throw TripLink.Expired() }

    // Its ends, as its first leg starts and its last ends, and when it leaves, if that's still to come; else it's planned
    // from now.
    internal fun sharedPlan(o: JSONObject, now: Long = System.currentTimeMillis()): TripLink.Plan {
        val legs = o.optJSONArray("legs")?.takeIf { it.length() > 0 } ?: throw java.io.IOException("This link holds no trip")
        val from = legs.getJSONObject(0).getJSONObject("from")
        val to = legs.getJSONObject(legs.length() - 1).getJSONObject("to")
        val leaves = o.optLong("startTime").takeIf { it > 0 } ?: legs.getJSONObject(0).optLong("startTime")
        return TripLink.Plan(
            from.optString("name").ifBlank { null }, from.getDouble("lat"), from.getDouble("lon"),
            to.optString("name").ifBlank { null }, to.getDouble("lat"), to.getDouble("lon"),
            departMs = leaves.takeIf { it > now } ?: 0L, autoRun = true,
        )
    }

    fun check(source: Source): KavLive.Check = try {
        val board = stops(listOf(24068), source)
        KavLive.Check.Answers(true, board.due.mapNotNull { it.spot?.let { _ -> it.key } }.distinct().size,
            System.currentTimeMillis() / 1000, true, true, null, null)
    } catch (e: java.net.UnknownHostException) {
        KavLive.Check.Unreached(KavLive.Check.Why.NO_SUCH_HOST)
    } catch (e: javax.net.ssl.SSLException) {
        KavLive.Check.Unreached(KavLive.Check.Why.HTTPS)
    } catch (e: java.net.SocketTimeoutException) {
        KavLive.Check.Unreached(KavLive.Check.Why.TIMEOUT)
    } catch (e: Exception) {
        KavLive.Check.Unreached(KavLive.Check.Why.NO_ANSWER)
    }

    private fun planTime(whenMs: Long, timeType: Int): java.time.ZonedDateTime {
        var at = Instant.ofEpochMilli(whenMs.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(israel)
        // This is the client's own last-trip search: arrive by 02:30 at the end of the service day.
        if (timeType == Transit.TIME_LAST) {
            if (at.hour > 4) at = at.plusDays(1)
            at = at.withHour(2).withMinute(30).withSecond(0).withNano(0)
        }
        return at
    }

    internal fun planQuery(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int,
        types: List<Int>, via: List<Pair<Double, Double>>,
    ): String {
        val at = planTime(whenMs, timeType)
        val modes = mapOf(0 to "TRAM", 1 to "SUBWAY", 2 to "RAIL", 3 to "BUS", 4 to "FERRY", 5 to "CABLE_CAR", 6 to "GONDOLA", 7 to "FUNICULAR")
        val mode = if (types.isEmpty() || types.distinct().size == 8) "TRANSIT" else types.distinct().mapNotNull(modes::get).joinToString(",")
        fun point(p: Pair<Double, Double>) = String.format(java.util.Locale.US, "%.6f,%.6f", p.first, p.second)
        var q = "locale=he&mode=$mode,WALK&pathComparator=duration&numItineraries=3&showIntermediateStops=true" +
            "&fromPlace=${point(from)}&toPlace=${point(to)}&date=${at.toLocalDate()}&time=${at.toLocalTime().toString().take(5)}"
        if (timeType == Transit.TIME_ARRIVAL || timeType == Transit.TIME_LAST) q += "&arriveBy=true"
        for (p in via.take(3)) q += "&intermediatePlaces=${point(p)}"
        return q
    }

    fun plan(from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int,
        types: List<Int>, via: List<Pair<Double, Double>>, source: Source): String {
        val asked = whenMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val o = JSONObject(get("directions", planQuery(from, to, asked, timeType, types, via), source))
        val plan = o.optJSONObject("plan") ?: return o.toString()
        val trips = plan.optJSONArray("itineraries") ?: return o.toString()
        val until = planTime(asked, timeType).toInstant().toEpochMilli()
        val kept = JSONArray()
        for (i in 0 until trips.length()) {
            val t = trips.getJSONObject(i)
            // HopOn can start the walk before a departure search, or finish it after an arrival search.
            // Keep its actual times and order, but only journeys meeting the rider's whole-trip boundary.
            val fits = when (timeType) {
                Transit.TIME_DEPARTURE -> {
                    val legs = t.getJSONArray("legs")
                    val walkNow = whenMs <= 0 && (0 until legs.length()).none { legs.getJSONObject(it).optBoolean("transitLeg") }
                    t.getLong("startTime") >= asked || walkNow
                }
                Transit.TIME_LAST -> t.getLong("endTime") <= until && t.getLong("startTime") >= asked
                else -> t.getLong("endTime") <= until
            }
            if (fits) kept.put(t)
        }
        plan.put("itineraries", kept)
        return o.toString()
    }

    // Turn the planner's OTP answer into the existing Kav trip model, including scheduled and real-time times.
    internal fun planBody(o: JSONObject): JSONObject {
        o.optJSONObject("error")?.let { e -> throw Transit.PlannerRefusal(
            Transit.PLAN_NO_ROUTES, e.optString("msg"), e.optString("message")) }
        val plan = o.getJSONObject("plan")
        val stops = JSONObject(); val lines = JSONObject(); val agencies = JSONObject(); val trips = JSONArray()
        fun id(raw: String): String {
            require(!raw.contains(':') || raw.substringBefore(':') == "1") { "Unsupported provider feed" }
            return raw.substringAfter(':').also { require(it.toIntOrNull()?.let { n -> n in 1..100_000_000 } == true) }
        }
        fun stop(p: JSONObject): String = id(p.getString("stopId")).also { k ->
            stops.put(k, JSONArray().put(p.optString("stopCode")).put(p.optString("name")).put(p.getDouble("lat")).put(p.getDouble("lon")))
        }
        val all = plan.getJSONArray("itineraries")
        for (i in 0 until all.length()) {
            val it = all.getJSONObject(i); val parts = it.getJSONArray("legs"); val legs = JSONArray()
            var supported = true
            for (j in 0 until parts.length()) {
                val l = parts.getJSONObject(j); val transit = l.optBoolean("transitLeg")
                if (transit && (l.optString("routeId").substringBefore(':', "1") != "1" || l.optString("agencyId").toIntOrNull() == null)) {
                    supported = false; break
                }
                val leg = JSONObject().put("dep", l.getLong("startTime") / 1000).put("arr", l.getLong("endTime") / 1000)
                    .put("m", l.optDouble("distance").toInt())
                val geometry = l.optJSONObject("legGeometry")
                leg.put("shape", geometry?.optString("points6")?.takeIf { it.isNotEmpty() } ?: geometry?.optString("points").orEmpty())
                    .put("precision", if (geometry?.has("points6") == true) 6 else 5)
                if (!transit) {
                    leg.put("k", if (l.optString("mode") == "BICYCLE") "b" else "w")
                } else {
                    val route = id(l.getString("routeId")); val agency = l.getString("agencyId")
                    val from = l.getJSONObject("from"); val to = l.getJSONObject("to")
                    val calls = JSONArray().put(stop(from))
                    l.optJSONArray("intermediateStops")?.let { mid -> for (n in 0 until mid.length()) calls.put(stop(mid.getJSONObject(n))) }
                    calls.put(stop(to))
                    agencies.put(agency, l.optString("agencyName"))
                    lines.put(route, JSONArray().put(l.optString("routeShortName", l.optString("route")))
                        .put(l.optString("headsign", to.optString("name"))).put("").put(from.optString("name")).put("")
                        .put(agency.toInt()).put(typeOf(l.optString("mode"), l.optString("agencyName"), l.optString("routeLongName"),
                            l.optString("routeShortName", l.optString("route")))).put(l.optString("routeLongName")))
                    leg.put("k", "r").put("line", route).put("trip", l.getString("tripId").substringAfter(':'))
                        .put("stops", calls).put("live", l.optBoolean("realTime"))
                        .put("sdep", l.getLong("startTime") / 1000 - l.optInt("departureDelay"))
                }
                legs.put(leg)
            }
            if (supported) trips.put(JSONObject().put("dep", it.getLong("startTime") / 1000)
                .put("arr", it.getLong("endTime") / 1000).put("legs", legs))
        }
        return JSONObject().put("stops", stops).put("lines", lines).put("agencies", agencies).put("it", trips)
    }

    fun stops(codes: Collection<Int>, source: Source): KavLive.Board {
        val asked = codes.filter { it > 0 }.distinct().take(KavLive.STOPS_MAX)
        if (asked.isEmpty()) return KavLive.Board(emptyList(), emptyMap(), next())
        return board(JSONObject(get("stops", "mref=${asked.joinToString(",")}&ex=1", source)), source)
    }

    private fun epoch(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }.getOrDefault(0)
    private fun next() = System.currentTimeMillis() / 1000 + 20

    internal fun board(o: JSONObject, source: Source = Source.BUS_NEARBY): KavLive.Board {
        val due = ArrayList<KavLive.Due>(); val lines = HashMap<Int, KavLive.Line>()
        val net = uk.noammm.kav.Loaded.net
        fun stopName(code: String): String = code.toIntOrNull()?.let { n -> net?.let { KavLive.stopOfCode(it, n) } }
            ?.takeIf { it >= 0 }?.let { net!!.name[it] }.orEmpty()
        for (code in o.keys()) {
            val stop = code.toIntOrNull() ?: continue
            val rows = o.optJSONArray(code) ?: continue
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                if (r.optString("feedId", "1") != "1") continue
                val route = r.optInt("route"); val first = epoch(r.optString("aimedDeparture"))
                val expected = epoch(r.optString("expectedArrival"))
                if (route <= 0 || first == 0L || expected == 0L) continue
                val key = "${route}_${r.getString("aimedDeparture")}_${r.optString("vehicle").ifEmpty { "trip:" + r.optString("tripId").replace('_', '-') }}"
                val c = r.optJSONObject("coord")
                val spot = c?.takeIf { !r.optBoolean("outdated") && it.optDouble("latitude").isFinite() && it.optDouble("longitude").isFinite() }?.let {
                    KavLive.Spot(it.getDouble("latitude"), it.getDouble("longitude"),
                        it.optLong("timestamp", epoch(r.optString("recordedAtTime"))), it.optInt("heading", -1), r.optInt("kmh", -1))
                }
                due += KavLive.Due(stop, route, key, r.optString("line"), first,
                    if (r.optString("actualDeparture").isNotEmpty() || spot != null) 0 else expected,
                    expected, spot, r.optInt("stopIndex", -1), r.optString("platform"))
                seenAt[source to route] = stop
                val known = routes[source to route]?.line
                lines[route] = known ?: KavLive.Line(r.optString("line"), stopName(r.optString("destination")), "",
                    stopName(r.optString("origin")), "", r.optInt("operator"), r.optInt("route_type", 3), "")
                observedLines[source to route] = lines.getValue(route)
            }
        }
        // HopOn can list a bus at a stop twice, once without its place on the route: it's kept once, as the row with
        // its place. A loop's bus, due at the stop at two places, stays twice.
        val placed = due.filter { it.order >= 0 }.mapTo(HashSet()) { "${it.stop}|${it.key}" }
        val once = due.filter { it.order >= 0 || "${it.stop}|${it.key}" !in placed }.distinctBy { "${it.stop}|${it.key}|${it.order}" }
        return KavLive.Board(once, lines, next())
    }

    private fun route(route: Int, source: Source): Route {
        routes[source to route]?.let { return it }
        // A real observed station supplies context; the server's returned route id, never a local index, is checked.
        val code = seenAt[source to route] ?: 0
        val list = JSONArray(get("lines", "lineref=$route&stopcode=$code", source))
        val r = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optInt("routeid") == route }
            ?: throw java.io.IOException("Provider has no route $route")
        val codes = r.getJSONArray("stopCodes").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val net = uk.noammm.kav.Loaded.net
        fun name(code: Int?) = code?.let { c -> net?.let { KavLive.stopOfCode(it, c) } }?.takeIf { it >= 0 }?.let { net!!.name[it] }.orEmpty()
        val number = r.optString("line")
        val head = r.optString("headsign").substringAfter('_')
        val line = KavLive.Line(number, name(codes.lastOrNull()).ifEmpty { head }, "", name(codes.firstOrNull()), "",
            r.optInt("agency_id"), observedLines[source to route]?.type ?: 3, "")
        return Route(line, codes, KavLive.decodePolyline(r.optString("path"), 6)).also { routes[source to route] = it }
    }

    fun shape(route: Int, source: Source) = route(route, source).shape

    fun follow(ids: List<Int>, source: Source): KavLive.Answer {
        val wanted = ids.distinct().take(KavLive.FOLLOW_MAX)
        val codes = wanted.flatMap { route(it, source).codes }.distinct()
        val spots = HashMap<String, KavLive.Spot>()
        for (part in codes.chunked(KavLive.STOPS_MAX)) for (d in stops(part, source).due) {
            val spot = d.spot ?: continue
            if (d.route in wanted && spot.at >= (spots[d.key]?.at ?: 0)) spots[d.key] = spot
        }
        return KavLive.Answer(spots, next())
    }

    fun area(box: DoubleArray, source: Source): KavLive.Area {
        val lat = (box[0] + box[2]) / 2; val lon = (box[1] + box[3]) / 2
        val radius = (maxOf(box[2] - box[0], box[3] - box[1]) * 111_000 / 2).toInt().coerceIn(500, 5000)
        val near = JSONArray(get("stopsNearLocation", "lat=$lat&lon=$lon&maxstops=20&minstops=1&needravkav=false&radius=$radius&accuracy=20&needmicromobility=false", source))
        val codes = (0 until near.length()).map { near.getJSONObject(it).optInt("stop_code") }.filter { it > 0 }.distinct()
        val board = stops(codes, source)
        val buses = board.due.mapNotNull { d -> d.spot?.takeIf { it.lat in box[0]..box[2] && it.lon in box[1]..box[3] }?.let { KavLive.Bus(d.key, d.route, it) } }
            .groupBy { it.key }.values.map { same -> same.maxBy { it.spot.at } }
        return KavLive.Area(buses, board.lines, board.next)
    }

    // Discovery starts with the local timetable's real stop codes, then takes route identities from provider answers.
    fun line(number: String, source: Source, requestedCodes: List<Int>): KavLive.Area {
        val net = uk.noammm.kav.Loaded.net
        val local = net?.rShort?.indices?.filter { net.rShort[it] == number }.orEmpty()
        val codes = requestedCodes.filter { it > 0 }.distinct()
        if (codes.isEmpty()) throw java.io.IOException("No stations supplied for this line")
        val due = ArrayList<KavLive.Due>(); val lines = HashMap<Int, KavLive.Line>()
        for (part in codes.chunked(KavLive.STOPS_MAX)) {
            val board = stops(part, source)
            due += board.due.filter { it.number == number }
            lines += board.lines.filterValues { it.number == number }
        }
        // Name matching on the line page needs the timetable's long name, resolved only from matching endpoints.
        for (id in lines.keys.toList()) {
            val r = route(id, source); val l = r.line
            val matches = local.filter { n -> net!!.rLong[n].contains(l.to) && l.to.isNotEmpty() && net!!.rLong[n].contains(l.from) && l.from.isNotEmpty() }
            lines[id] = KavLive.Line(l.number, l.to, l.toCity, l.from, l.fromCity, l.agency,
                matches.firstOrNull()?.let { net!!.rType[it] } ?: l.type, matches.singleOrNull()?.let { net!!.rLong[it].trim() }.orEmpty())
        }
        val buses = due.mapNotNull { d -> d.spot?.let { KavLive.Bus(d.key, d.route, it) } }.groupBy { it.key }.values.map { it.maxBy { b -> b.spot.at } }
        return KavLive.Area(buses, lines, next())
    }

    // HopOn's route index, line groups, patterns, stop metadata and daily departures.
    // Only ministry-feed routes are used to avoid id collisions.
    private const val INDEX = "https://api.busnearby.co.il/directions/index"
    private val operatorNames = ConcurrentHashMap<Source, Map<String, String>>()

    private fun schedule(path: String, args: String, source: Source): String =
        Schedules.cached(source, "$path?$args") { get(path, args, source) }

    // HopOn's modes as the week's route types, with its own two: the Carmelit (7) and shuttles (711).
    internal fun typeOf(mode: String, operator: String, name: String, number: String): Int = when (mode) {
        "TRAM" -> 0; "SUBWAY" -> 1; "RAIL" -> 2; "FERRY" -> 4; "GONDOLA" -> 6; "FUNICULAR" -> 7; "SHARE_TAXI" -> 8
        "CABLE_CAR" -> if (operator == "כרמלית") 7 else 5
        else -> if ("שאטל" in name || "שאטל" in number) 711 else 3
    }

    fun lines(source: Source): List<Schedules.Line> {
        // The short route index omits the ministry line id. The agency index keeps it, so directions and branches
        // can share one entry without merging unrelated services that happen to have the same public number.
        val agencies = JSONArray(fetch("$INDEX/agencies/1", source))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until agencies.length()).map { i ->
                val id = agencies.getJSONObject(i).getString("id")
                pool.submit<JSONArray> { JSONArray(fetch("$INDEX/agencies/1/$id/routes?detail=true", source)) }
            }
            val index = JSONArray()
            for (task in tasks) {
                val rows = try { task.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
                for (i in 0 until rows.length()) index.put(rows.getJSONObject(i))
            }
            return lines(index, uk.noammm.kav.Loaded.net?.city?.toHashSet().orEmpty())
        } finally { pool.shutdownNow() }
    }

    // One entry per operator, mode, ministry line id and public number, with all directions and variants inside it.
    // A train line still goes by its two stations, each train being a route of its own. Older short index answers
    // without ministry ids fall back to their pair of ends.
    internal fun lines(index: JSONArray, towns: Set<String>): List<Schedules.Line> {
        class Fold(val number: String, val type: Int, val operator: String, val from: String, val to: String, val direction: String) {
            val routes = ArrayList<String>(); val names = ArrayList<String>(); var both = false
            val start = from.substringBefore('/').trim()
        }
        val folds = LinkedHashMap<String, Fold>()
        for (i in 0 until index.length()) {
            val r = index.getJSONObject(i)
            val id = r.optString("id")
            if (id.substringBefore(':') != "1") continue
            val name = r.optString("longName").trim()
            val operator = r.optString("agencyName", r.optJSONObject("agency")?.optString("name").orEmpty()).trim()
            val mode = r.optString("mode").ifEmpty { when (r.optInt("type", 3)) {
                0 -> "TRAM"; 1 -> "SUBWAY"; 2 -> "RAIL"; 4 -> "FERRY"; 5 -> "CABLE_CAR"
                6 -> "GONDOLA"; 7 -> "FUNICULAR"; 8 -> "SHARE_TAXI"; else -> "BUS"
            } }
            val type = typeOf(mode, operator, name, r.optString("shortName"))
            val number = if (type == 2) "" else r.optString("shortName").trim()
            val ends = Schedules.ends(name, towns)
            val from = ends?.first?.first ?: name; val to = ends?.second?.first.orEmpty()
            val start = from.substringBefore('/').trim()
            val ministry = if (type == 2) "" else r.optString("motLineId").ifEmpty { r.optString("desc").substringBefore('-') }
            val group = if (ministry.isNotEmpty()) "mot:$ministry" else listOf(start, to.substringBefore('/').trim()).sorted().joinToString("|")
            val direction = r.optString("motDirection")
            val f = folds.getOrPut("$operator|$type|$number|$group") {
                Fold(number, type, operator, from, to, direction)
            }
            if (if (direction.isNotEmpty() && f.direction.isNotEmpty()) direction != f.direction else start != f.start) f.both = true
            f.routes += id.substringAfter(':'); f.names += name
        }
        return folds.values.map { Schedules.Line(it.routes.first(), it.number, it.type, it.operator, it.from, it.to, it.routes, it.names, it.both) }
    }

    // A line's ways as a line's page in the apps has them: its group, every way and variant under the ministry's line
    // id, its own routes first; each way's stops and road; and all their stops' names and places in one more request.
    fun ways(line: Schedules.Line, source: Source): List<Schedules.Way> {
        fun pattern(route: String) = JSONArray(schedule("lines", "lineref=$route&stopcode=0", source)).optJSONObject(0)
        val first = pattern(line.routes.first()) ?: throw java.io.IOException("Provider has no route ${line.routes.first()}")
        val group = runCatching {
            JSONArray(schedule("linegroup", "route_desc=${first.opt("route_desc")}&line=${first.optString("line")}&locale=he", source))
        }.getOrNull()
        val found = LinkedHashMap<String, JSONObject>()
        found[line.routes.first()] = first
        // A train line can have a hundred trains, each a route of its own: asked a few at a time.
        val rest = groupRoutes(line, group).filter { it.isNotEmpty() && it !in found }
        for ((r, p) in rest.zip(each(rest, ::pattern))) if (p != null) found[r] = p
        val stops = meta(found.values.flatMap { codesOf(it) }.distinct(), source)
        return found.map { (route, w) -> way(route, w, stops) }.filter { it.stops.size >= 2 }
    }

    // Each of the requests on a few threads at once, their answers in order.
    private fun <T, R> each(items: List<T>, ask: (T) -> R): List<R> {
        if (items.size < 2) return items.map(ask)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(6, items.size))
        try {
            return items.map { pool.submit<R> { ask(it) } }.map { f ->
                try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            }
        } finally { pool.shutdownNow() }
    }

    internal fun groupRoutes(line: Schedules.Line, group: JSONArray?): List<String> =
        (line.routes + (0 until (group?.length() ?: 0)).map { group!!.getJSONObject(it) }
            .filter { line.type == 2 || it.optString("line").trim() == line.number }
            .map { it.optString("route") }).distinct()

    private fun codesOf(w: JSONObject): List<String> = w.optJSONArray("stopCodes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    // A way from its pattern (lines) and its stops' names and places (meta), or the week on the phone's for a stop HopOn
    // didn't describe. Its pickDropPattern says, stop by stop, 1 where riders only get on, 2 where they only get off
    // (an intercity line's stops in the city it's bound for, or a depot it sets out from) and 0 for both.
    internal fun way(route: String, w: JSONObject, stops: Map<String, Schedules.Stop>): Schedules.Way {
        val net = uk.noammm.kav.Loaded.net
        val list = codesOf(w).map { c ->
            stops[c] ?: c.toIntOrNull()?.let { n -> net?.let { KavLive.stopOfCode(it, n) } }?.takeIf { it >= 0 }?.let { s ->
                Schedules.Stop(net!!.code[s], net.name[s], net.cityOf(s), net.lat[s], net.lon[s])
            } ?: Schedules.Stop(c.toIntOrNull() ?: 0, c, "", Double.NaN, Double.NaN)
        }
        val calls = w.optJSONArray("pickDropPattern")
        val alight = (0 until (calls?.length() ?: 0)).filterTo(HashSet()) { calls!!.optInt(it) == 2 }
        return Schedules.Way(route, list.lastOrNull()?.name.orEmpty(), list, KavLive.decodePolyline(w.optString("path"), 6), alight)
    }

    // Stops' names and places by their codes, HopOn's in Hebrew as the ministry names them.
    private fun meta(codes: List<String>, source: Source): Map<String, Schedules.Stop> =
        each(codes.filter { it.isNotEmpty() }.chunked(60)) { part ->
            stops(JSONArray(schedule("meta", "stops=${part.joinToString(",")}", source)))
        }.fold(HashMap<String, Schedules.Stop>()) { out, part -> for ((code, stop) in part) out.putIfAbsent(code, stop); out }

    // A meta answer's stops by code: a code a station's platforms share comes once, as its first.
    internal fun stops(a: JSONArray): Map<String, Schedules.Stop> {
        val out = LinkedHashMap<String, Schedules.Stop>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val code = o.optString("stop_code")
            if (code !in out) out[code] = Schedules.Stop(code.toIntOrNull() ?: 0, o.optString("stop_name"), o.optString("city"),
                o.optDouble("latitude"), o.optDouble("longitude"))
        }
        return out
    }

    // When a way's line leaves one of its stops on a day. A loop's stop it calls at twice is asked by its place; a train
    // line's answer is every train of it, not the one train its way was taken from; a bus way's, its routes' that call at
    // the very same stops (also).
    fun times(line: Schedules.Line, way: Schedules.Way, at: Int, day: java.time.LocalDate, source: Source): List<Long> {
        val code = way.stops[at].code
        val loop = line.type != 2 && way.stops.count { it.code == code } > 1
        val ids = listOf(way.id) + way.also
        val routes = if (line.type == 2) line.routes else ids
        return routes.chunked(300).flatMap { part ->
            val rows = JSONArray(schedule("dailyschedule",
                "routes=${part.joinToString(",")}&stopcode=$code&startTime=${Schedules.start(day)}" + if (loop) "&stopIndex=$at" else "", source))
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }.filter { it.optInt("pickDrop") != 2 &&
            if (line.type == 2) it.optString("destination") == way.stops.last().code.toString()
            else it.optString("routeId", it.optString("route")) in ids }
            .map(::timeOf).distinct().sorted()
    }

    private fun timeOf(r: JSONObject): Long =
        epoch(r.optString("expectedArrival")).takeIf { it > 0 } ?: (r.optLong("serviceDay") + r.optLong("realtimeDeparture"))

    // A stop's departures on a day: the lines HopOn lists at it (meta), their routes in the route index, and that day's
    // times of them all at the stop, in one request for up to 300 routes. A train station lists none: every train
    // route is asked (each train is a route of its own), and only those calling there answer.
    fun departures(code: Int, day: java.time.LocalDate, lines: List<Schedules.Line>, source: Source): List<Schedules.Departure> {
        val listed = JSONArray(schedule("meta", "stops=$code", source))
        val served = (0 until listed.length()).mapNotNull { listed.getJSONObject(it).optString("stop_lines").takeIf { s -> s.startsWith("{") } }
            .flatMap { s -> JSONObject(s).let { o -> o.keys().asSequence().map { n -> n to o.optString(n) }.toList() } }.toSet()
        val operators = if (served.isEmpty()) emptyMap() else operators(source)
        val byRoute = HashMap<String, Schedules.Line>()
        val asked = ArrayList<String>()
        for (l in lines) {
            val wanted = if (served.isEmpty()) l.type == 2
                else served.any { (n, o) -> n == l.number && operators[o].let { it == null || it == l.operator } }
            if (!wanted) continue
            for (r in l.routes) byRoute[r] = l
            asked += l.routes
        }
        val net = uk.noammm.kav.Loaded.net
        fun name(code: String): String? = code.toIntOrNull()?.let { c -> net?.let { KavLive.stopOfCode(it, c) } }
            ?.takeIf { it >= 0 }?.let { net!!.name[it] }
        return asked.distinct().chunked(300).flatMap { part ->
            departures(JSONArray(schedule("dailyschedule",
                "routes=${part.joinToString(",")}&stopcode=$code&startTime=${Schedules.start(day)}", source)), byRoute, ::name)
        }.distinctBy { "${it.line.id}|${it.number}|${it.at}" }
    }

    // A day's rows at a stop as departures: none where riders only get off (pickDrop 2), each named by where it ends.
    internal fun departures(rows: JSONArray, lines: Map<String, Schedules.Line>, name: (String) -> String?): List<Schedules.Departure> =
        (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { r ->
            if (r.optString("feedId", "1") != "1" || r.optInt("pickDrop") == 2) return@mapNotNull null
            val line = lines[r.optString("routeId", r.optString("route"))] ?: return@mapNotNull null
            Schedules.Departure(timeOf(r), line, r.optString("line", line.number), name(r.optString("destination")) ?: line.to,
                r.optString("platform").takeIf { it != "null" }.orEmpty())
        }

    private fun operators(source: Source): Map<String, String> = operatorNames.getOrPut(source) {
        val a = JSONArray(Schedules.cached(source, "agencies/1") { fetch("$INDEX/agencies/1", source) })
        (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString("id") to o.optString("name") } }
    }
}
