package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ui.T
import java.util.Locale

// Trips planned on Kav's own server (server/live.py, OpenTripPlanner on the ministry's timetable), or the rider's own
// copy of it. The answer becomes the routes and details the screens show (Transit): stops, lines and operators get ids
// from BASE up, trips the negative ids KavLive gives the ministry's journeys. Their live times come from wherever live
// locations do, which is a choice of its own.
object KavPlan {
    private const val BASE = 2_000_000_000
    private const val PREFS = "kav-plan"

    class Planned(val plan: Transit.Plan, val resolved: Transit.Resolved, val errors: List<String> = emptyList())

    // Operators by the ids given here, kept so a trip reopened later still names them.
    private val operators = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var store: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        store = s
        runCatching { JSONObject(s.getString("operators", "{}") ?: "{}") }.getOrNull()?.let { o ->
            for (k in o.keys()) k.toIntOrNull()?.let { operators[it] = o.optString(k) }
        }
    }

    fun agencyName(agencyId: Int): String? = operators[agencyId]

    // Who plans trips: Kav's server or the rider's own (KavLive.ownServer, the one live locations can use too). Any
    // other stored choice plans on Kav's server.
    var source by mutableStateOf(Source.KAV)

    // The server chosen to plan: Kav's, or the rider's own once it's set up (Kav's until then).
    val server: String get() = KavLive.serverOf(source)

    // Whether that server is answering, and whether it plans trips at all: Kav's always does, a self-hosted one only when
    // its status lists a planner (server/selfhost with PLANNER=off leaves it out). While either isn't so, no trip is
    // planned and the rider is told.
    var up by mutableStateOf(true)
    var offered by mutableStateOf(true)

    // The server that plans now, through any stops the rider added and for the last way there tonight too; null while it
    // isn't answering, or doesn't plan.
    fun planner(): String? = server.takeIf { up && offered }
    fun wanted(): Boolean = planner() != null

    // A shared trip Kav planned only a Kav planner finds again, whichever plans now: the one chosen, else the server
    // live locations come from.
    fun linkPlanner(): String? = planner() ?: KavLive.server.takeIf { KavLive.enabled }

    fun owns(trip: Transit.Itinerary): Boolean = trip.guid.startsWith("kav-")

    // A shared link that names Kav's own rides, which only Kav's planner can find again.
    fun owns(rides: List<TripLink.Ride>): Boolean = rides.isNotEmpty() && rides.all { it.lineId >= BASE }

    // A trip shared as Kav's own link (TripLink.tripLink): Kav opens it as this very trip, planning it again from its
    // places and departure and finding it by its rides. Nothing is stored anywhere. rides: false for a trip no planner
    // finds again by its rides, which then plans the same way at the same time.
    fun shareLink(trip: Transit.Itinerary, fromName: String, toName: String, rides: Boolean = true): String? {
        val start = trip.legs.firstNotNullOfOrNull { leg -> leg.shape.firstOrNull() } ?: return null
        val end = trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull() ?: return null
        return TripLink.tripLink(start, end, fromName, toName, trip.dep,
            if (rides) trip.rides.map { TripLink.Ride(it.lineId, it.tripId, it.dep) } else emptyList())
    }

    // Whether an id is one given here.
    fun isKav(id: Int): Boolean = id >= BASE

    fun stopId(gtfs: String): Int = BASE + gtfs.toInt()
    fun lineId(gtfs: String): Int = BASE + gtfs.toInt()
    // The ministry's route id of a line given here, which its live reports name; null for any other line.
    fun routeOf(lineId: Int): Int? = (lineId - BASE).takeIf { isKav(lineId) }
    // One per operator and kind of line, since the screens take a line's kind from its operator.
    fun agencyId(gtfs: Int, type: Int): Int = BASE + gtfs * 1000 + type % 1000
    fun gtfsAgency(agencyId: Int): Int? = if (isKav(agencyId)) (agencyId - BASE) / 1000 else null
    fun typeOf(agencyId: Int): Int? = if (isKav(agencyId)) (agencyId - BASE) % 1000 else null

    // Leaving now (n) also brings the taxi card, which is only offered then, but not on the way to other stops;
    // the last departure (l) is the last way there tonight. Stops on the way go last, in order.
    internal fun path(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>,
        taxi: Boolean = false, stopovers: List<Pair<Double, Double>> = emptyList(),
    ): String {
        val at = (if (whenMs > 0) whenMs else System.currentTimeMillis()) / 1000
        val way = when {
            timeType == Transit.TIME_ARRIVAL -> "a"
            timeType == Transit.TIME_LAST -> "l"
            whenMs <= 0 && taxi && stopovers.isEmpty() -> "n"
            else -> "d"
        }
        val types = routeTypes.distinct().sorted().filter { it in 0..7 }
        val only = if (types.isEmpty() || types.size == 8) "" else "/" + types.joinToString(",")
        val via = if (stopovers.isEmpty()) "" else "/via/" + stopovers.take(3).joinToString(";") { (lat, lon) ->
            String.format(Locale.US, "%.6f,%.6f", lat, lon)
        }
        return String.format(Locale.US, "plan/%.6f,%.6f,%.6f,%.6f/%d%s%s", from.first, from.second, to.first, to.second, at, way, only) + via
    }

    // at: the server that plans (planner, linkPlanner).
    fun plan(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>, taxi: Boolean,
        stopovers: List<Transit.Place> = emptyList(), at: String,
    ): Planned {
        val planned = parse(asked(path(from, to, whenMs, timeType, routeTypes, taxi, stopovers.map { it.lat to it.lon }), at))
        // The server's timetable runs two weeks ahead. Walking and cycling don't need it.
        if (planned.plan.itineraries.none { it.rides.isNotEmpty() } && "OUTSIDE_SERVICE_PERIOD" in planned.errors) {
            throw NoTimetable()
        }
        saveOperators()
        return Planned(planned.plan, live(planned.plan.itineraries, planned.resolved))
    }

    class NoTimetable : java.io.IOException("Kav's planner has no timetable for that day")

    // The server plans a few trips at once and turns the next one away as busy: that one, and one that timed out, is
    // asked again after each of these waits, with up to a third more at random so riders turned away together don't
    // come back together, before the rider is told. Only while one more try as long as the last still fits in
    // retryBudgetMs: a server that doesn't answer at all costs a connect timeout on each of its addresses at every try.
    internal var retryWaitsMs = listOf(1_200L, 2_400L)
    internal var retryBudgetMs = 20_000L
    internal var clock: () -> Long = System::currentTimeMillis

    private fun asked(path: String, at: String): String {
        val start = clock()
        var tries = 0
        while (true) {
            val began = clock()
            try {
                return KavLive.planned(path, at)
            } catch (e: java.io.IOException) {
                val again = e is java.net.SocketTimeoutException || e.message == "Kav live busy"
                val wait = retryWaitsMs.getOrNull(tries++)
                val now = clock()
                if (!again || wait == null || now - start + wait + (now - began) > retryBudgetMs) throw e
                Thread.sleep(wait + kotlin.random.Random.nextLong(wait / 3 + 1))
            }
        }
    }

    // Live times for Kav's routes, from where live locations come from like the rest of them: a server, Kav's or the
    // rider's own, answers about the ministry's own stops and routes, which Kav's ids are.
    fun live(list: List<Transit.Itinerary>, r: Transit.Resolved, ending: Boolean = false): Transit.Resolved {
        if (list.isEmpty()) return r
        val board: (List<Int>) -> KavLive.Board = when {
            KavLive.stopsEnabled -> { codes -> KavLive.stops(codes) }
            else -> return r.aged()
        }
        val rides = try {
            KavLive.rideArrivals(list, r.stops, r.lines, ending, uk.noammm.kav.Loaded.net, board)
        } catch (e: Exception) {
            return r.aged()
        }
        return Transit.Resolved(r.lines, r.stops, r.routeTypes, rides.live, r.shapes, 15, r.patterns + rides.patterns,
            r.delays + Transit.delaysOf(list, rides.live))
    }

    private fun saveOperators() {
        val o = JSONObject()
        for ((k, v) in operators) o.put(k.toString(), v)
        store?.edit()?.putString("operators", o.toString())?.apply()
    }

    internal fun parse(body: String): Planned {
        val o = JSONObject(body)
        val stops = HashMap<Int, Transit.StopInfo>()
        o.optJSONObject("stops")?.let { s ->
            for (k in s.keys()) {
                val a = s.getJSONArray(k)
                val id = stopId(k)
                stops[id] = Transit.StopInfo(id, a.optString(1), a.optString(0), a.optDouble(2), a.optDouble(3))
            }
        }
        val names = o.optJSONObject("agencies") ?: JSONObject()
        val lines = HashMap<Int, Transit.LineInfo>()
        // The ministry's own name for each line, which tells a line's two ways apart in the timetable.
        val routeNames = HashMap<Int, String>()
        val types = HashMap<Int, Int>()
        o.optJSONObject("lines")?.let { l ->
            for (k in l.keys()) {
                // [number, to, to city, from, from city, operator, type, the ministry's own name], as KavLive's lines.
                val a = l.getJSONArray(k)
                val type = a.optInt(6, 3)
                val agency = agencyId(a.optInt(5), type)
                types[agency] = type
                names.optString(a.optInt(5).toString()).takeIf { it.isNotBlank() }?.let { operators[agency] = it }
                val from = a.optString(3); val to = a.optString(1)
                val fromCity = a.optString(4); val toCity = a.optString(2)
                // A train is named for where it runs.
                val number = a.optString(0).ifBlank { if (from.isNotBlank() && to.isNotBlank()) "$from - $to" else a.optString(7) }
                val caption = if (fromCity.isBlank() || fromCity == toCity) toCity else "$fromCity ↔︎ $toCity"
                val id = lineId(k)
                lines[id] = Transit.LineInfo(id, number, agency, from, to, caption)
                routeNames[id] = a.optString(7)
            }
        }
        val routes = ArrayList<Transit.Itinerary>()
        val all = o.optJSONArray("it") ?: JSONArray()
        for (i in 0 until all.length()) {
            val it = all.getJSONObject(i)
            val legs = ArrayList<Transit.Leg>()
            val parts = it.getJSONArray("legs")
            var lastArr = it.optLong("dep")
            for (j in 0 until parts.length()) {
                val l = parts.getJSONObject(j)
                val dep = l.optLong("dep"); val arr = l.optLong("arr")
                val shape = KavLive.decodePolyline(l.optString("shape"))
                fun point(k: String) = l.optJSONArray(k)?.let { p -> p.optDouble(0) to p.optDouble(1) }
                when (l.optString("k")) {
                    "w" -> legs += Transit.Leg(Transit.LegKind.WALK, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape)
                    "b" -> legs += Transit.Leg(Transit.LegKind.BIKE, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape)
                    // Waiting for the taxi, then the drive.
                    "x" -> legs += Transit.Leg(Transit.LegKind.OTHER, dep = dep, arr = arr)
                    "t" -> legs += Transit.Leg(
                        Transit.LegKind.TAXI, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape,
                        taxiPickup = point("from"), taxiDropoff = point("to"),
                    )
                    else -> {
                        // The ride and the wait for it, and the same for every other line from that stop to that stop.
                        val waitFrom = minOf(lastArr, dep)
                        val (wait, ride) = ride(l, shape, waitFrom, lines, routeNames, stops, types)
                        val alts = l.optJSONArray("alts") ?: JSONArray()
                        if (alts.length() == 0) {
                            legs += wait; legs += ride
                        } else {
                            val options = listOf(wait to ride) + (0 until alts.length()).map { k ->
                                val alt = alts.getJSONObject(k)
                                ride(alt, KavLive.decodePolyline(alt.optString("shape")), waitFrom, lines, routeNames, stops, types)
                            }
                            legs += wait.copy(alternatives = options.map { it.first })
                            legs += ride.copy(alternatives = options.map { it.second })
                        }
                    }
                }
                lastArr = arr
            }
            if (legs.isEmpty()) continue
            val tags = (it.optJSONArray("tags") ?: JSONArray()).let { t -> (0 until t.length()).map { k -> t.optString(k) } }
            routes += Transit.Itinerary(
                guid = "kav-" + Integer.toHexString(legs.joinToString("|") { "${it.kind}${it.lineId}${it.tripId}${it.dep}" }.hashCode()),
                group = 2, legs = legs, dep = it.optLong("dep"), arr = it.optLong("arr"), tags = tags,
                sectionId = it.optInt("sec", SECTION_MAIN),
            )
        }
        val errors = (o.optJSONArray("errors") ?: JSONArray()).let { e -> (0 until e.length()).map { e.optString(it) } }
        return Planned(Transit.Plan(routes, sections()), Transit.Resolved(lines, stops, types), errors)
    }

    private const val SECTION_MAIN = 1

    // The sections Kav's server fills, by their ids, in order: the timetable card, the routes, taxis, transit with a
    // bike, walking or cycling all the way, and East Jerusalem's operators' routes. Their names come when they're shown
    // (sectionName), so they follow the language Kav is shown in.
    private fun sections() = listOf(
        Transit.Section(3, "", 1, Transit.SECTION_NO_GROUPING, 0),
        Transit.Section(SECTION_MAIN, "", 127, 16, 1),
        Transit.Section(Transit.SECTION_TAXI, "", Int.MAX_VALUE, 1, 2),
        Transit.Section(522, "", 1, 3, 3),
        Transit.Section(521, "", 3, 2, 4),
        Transit.Section(1461, "", Int.MAX_VALUE, 1, 5),
    )

    // A section's heading by its id, in the language Kav is shown in now; null for a section Kav doesn't name.
    fun sectionName(id: Int): String? = when (id) {
        Transit.SECTION_TAXI -> T("Taxi & Ride Hailing", "מוניות")
        522 -> T("Combined Transit & Personal Bike Routes", "מסלולי תחב\"צ בשילוב אופניים")
        521 -> T("Walking & Biking Routes", "מסלולי הליכה ואופניים")
        1461 -> T("Suggested routes including East Jerusalem agencies", "מסלולים הכוללים את קווי מזרח ירושלים")
        else -> null
    }

    // One way to take a stretch on one line: the wait at its first stop, with that line's next departures, and the
    // ride itself, along its road; through its stops when the answer has no road for it,
    // since the map draws a ride and finds the rider aboard by it.
    private fun ride(
        l: JSONObject, shape: List<Pair<Double, Double>>, waitFrom: Long,
        lines: Map<Int, Transit.LineInfo>, names: Map<Int, String>, stops: Map<Int, Transit.StopInfo>,
        types: Map<Int, Int>,
    ): Pair<Transit.Leg, Transit.Leg> {
        val dep = l.optLong("dep"); val arr = l.optLong("arr")
        val line = lineId(l.getString("line"))
        val trip = KavLive.tripIdOf("gtfs:" + l.getString("trip"))
        val calls = l.optJSONArray("stops") ?: JSONArray()
        val ids = (0 until calls.length()).map { stopId(calls.getString(it)) }
        val number = lines[line]?.number.orEmpty()
        // The timetable's own time, which Kav's live times find the bus by, worked out from when its trip set out since
        // the planner's times are predicted; and the planner's, live where its updates moved it.
        val first = l.optLong("first", 0L)
        val timetabled = (if (first > 0) timetabled(stops[ids.firstOrNull() ?: -1]?.code, number, first, names[line].orEmpty()) else null)
            ?: l.optLong("sdep", dep)
        val live = l.optBoolean("live")
        val mine = Transit.Departure(
            trip, timetabled, rtUtc = if (live) dep else 0L,
            statisticalUtc = if (!live && dep != timetabled) dep else 0L,
        )
        // The line's departures there after this one, and for a route's first ride those from the time asked about
        // before it: the card lists them from then, catchable or not.
        // A third field 1: the server has that one live (a train card's other trains, which the board can't match).
        val departures = { key: String ->
            val list = l.optJSONArray(key) ?: JSONArray()
            (0 until list.length()).mapNotNull { n ->
                list.optJSONArray(n)?.let { d ->
                    val at = d.optLong(0)
                    Transit.Departure(KavLive.tripIdOf("gtfs:" + d.optString(1)), at, rtUtc = if (d.optInt(2) == 1) at else 0L)
                }
            }
        }
        val later = departures("next")
        val earlier = departures("before")
        val from = ids.firstOrNull() ?: -1
        val to = ids.lastOrNull() ?: -1
        // A train's headsign is its run number; its line's name describes the whole route.
        val label = l.optString("head").takeIf {
            types[lines[line]?.agencyId] == 2 && it.isNotBlank() && it.all(Char::isDigit)
        } ?: number
        return Transit.Leg(
            Transit.LegKind.WAIT, lineId = line, dep = minOf(waitFrom, dep), arr = dep,
            fromStop = from, toStop = to, nextDeps = earlier + listOf(mine) + later,
        ) to Transit.Leg(
            Transit.LegKind.RIDE, lineId = line, tripId = trip, dep = dep, arr = arr, stops = ids,
            fromStop = from, toStop = to, nextDeps = listOf(mine), shortName = label,
            shape = shape.ifEmpty { ids.mapNotNull { stops[it]?.point } },
        )
    }

    // When the timetable has the trip of that line that set out at first stop at the stop with that code, from Kav's own
    // timetable; null when it hasn't the trip.
    private fun timetabled(code: String?, number: String, first: Long, name: String): Long? {
        val net = uk.noammm.kav.Loaded.net ?: return null
        val stop = KavLive.stopOfCode(net, code?.toIntOrNull() ?: return null).takeIf { it >= 0 } ?: return null
        val at = KavLive.scheduledAt(net, stop, number, first, name) ?: return null
        return first + Math.floorMod(at - KavLive.secondOfDay(first), 86_400)
    }

    // What each route is best at, in a word: the planner's tags, kept on the route as they came so the words follow the
    // language Kav is shown in.
    private val TAG_WORDS = mapOf(
        "early_arrival" to ("Earliest arrival" to "הגעה הכי מוקדמת"),
        "latest_departure" to ("Latest departure" to "יציאה הכי מאוחרת"),
        "direct" to ("No transfers" to "ללא החלפות"),
        "least_walking" to ("Least walking" to "הכי פחות הליכה"),
        "low_transfer" to ("Fewest transfers" to "הכי מעט החלפות"),
        "last_departure" to ("Last trip" to "נסיעה אחרונה"),
        "long_walk" to ("Long walk" to "הליכה ארוכה"),
        "long_wait_time" to ("Long wait" to "המתנה ארוכה"),
        "risky_transfer" to ("Tight connection" to "החלפה צפופה"),
        "high_frequency" to ("Frequent service" to "תדירות גבוהה"),
        "flexible_route" to ("Flexible route" to "מסלול גמיש"),
        "after_shabbat" to ("After Shabbat" to "אחרי שבת"),
        "after_holiday" to ("After the holiday" to "אחרי החג"),
    )

    // A late train's tag (RailTrip): its minutes.
    private const val LATE = "late:"
    private val LATE_WORDS = Regex("^(\\d+) min late$|^באיחור של (\\d+) דק׳$")

    fun lateTag(minutes: Int) = "$LATE$minutes"

    // A tag's words as Kav is shown now; null for a tag it has no words for.
    fun tagWord(tag: String): String? = TAG_WORDS[tag]?.let { (en, he) -> T(en, he) }
        ?: tag.takeIf { it.startsWith(LATE) }?.removePrefix(LATE)?.toIntOrNull()?.let { T("$it min late", "באיחור של $it דק׳") }

    // A saved tag read back. An older Kav saved its words, in either language: those turn back into the tag.
    internal fun tagOf(saved: String): String? = when {
        saved in TAG_WORDS || saved.startsWith(LATE) -> saved
        else -> TAG_WORDS.entries.firstOrNull { saved == it.value.first || saved == it.value.second }?.key
            ?: LATE_WORDS.find(saved)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { "$LATE$it" }
    }
}
