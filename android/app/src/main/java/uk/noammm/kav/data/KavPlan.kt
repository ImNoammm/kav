package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ui.T
import java.util.Locale

// Trips planned on Kav's own server (server/live.py, OpenTripPlanner on the ministry's timetable) instead of by Moovit.
// The answer becomes the routes and details Moovit's plans give the screens: stops, lines and operators get ids above
// Moovit's, trips the negative ids KavLive gives the ministry's journeys, and live times come from Kav's server too.
object KavPlan {
    private const val BASE = 2_000_000_000
    private const val PREFS = "kav-plan"

    class Planned(val plan: Moovit.Plan, val resolved: Moovit.Resolved, val errors: List<String> = emptyList())

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

    // The setting: Kav's own planner rather than Moovit's. It runs on Kav's server, so it needs that chosen too.
    var chosen by mutableStateOf(false)

    // Kav plans when it's chosen and its server is chosen and answering, through any stops the rider added and for the
    // last way there tonight too; Moovit when it isn't.
    fun wanted(): Boolean = chosen && KavLive.enabled && offered

    // Whether the server in use plans trips at all: Kav's always does, a self-hosted one only when its status lists a
    // planner (server/selfhost with PLANNER=off leaves it out). Without one, Moovit plans and the choice is kept.
    var offered by mutableStateOf(true)

    fun owns(trip: Moovit.Itinerary): Boolean = trip.guid.startsWith("kav-")

    // A shared link that names Kav's own rides, which only Kav's planner can find again.
    fun owns(rides: List<MoovitLink.Ride>): Boolean = rides.isNotEmpty() && rides.all { it.lineId >= BASE }

    // A trip Kav planned, shared as a directions link: Moovit opens it as the way there, and Kav as this very trip,
    // planning it again from the places and departure and finding it by its rides (kav_trip). Nothing is stored anywhere.
    fun shareLink(trip: Moovit.Itinerary, fromName: String, toName: String): String? {
        val start = trip.legs.firstNotNullOfOrNull { leg -> leg.shape.firstOrNull() } ?: return null
        val end = trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull() ?: return null
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val rides = trip.rides.joinToString("~") { "${it.lineId}.${it.tripId}.${it.dep}" }
        return String.format(Locale.US,
            "https://moovitapp.com/directions?orig_lat=%.6f&orig_lon=%.6f&orig_name=%s&dest_lat=%.6f&dest_lon=%.6f&dest_name=%s&date=%d&auto_run=true&kav_trip=%s",
            start.first, start.second, enc(fromName), end.first, end.second, enc(toName), trip.dep * 1000, rides)
    }

    // Whether an id is one given here, which Moovit has never heard of and mustn't be asked about.
    fun isKav(id: Int): Boolean = id >= BASE

    fun stopId(gtfs: String): Int = BASE + gtfs.toInt()
    fun lineId(gtfs: String): Int = BASE + gtfs.toInt()
    // The ministry's route id of a line given here, which its live reports name; null for Moovit's lines.
    fun routeOf(lineId: Int): Int? = (lineId - BASE).takeIf { isKav(lineId) }
    // One per operator and kind of line, since the screens take a line's kind from its operator.
    fun agencyId(gtfs: Int, type: Int): Int = BASE + gtfs * 1000 + type % 1000
    fun gtfsAgency(agencyId: Int): Int? = if (isKav(agencyId)) (agencyId - BASE) / 1000 else null

    // Leaving now (n) also brings Moovit's taxi card, as Moovit only offers it then, but not on the way to other stops;
    // the last departure (l) is the last way there tonight. Stops on the way go last, in order.
    internal fun path(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>,
        taxi: Boolean = false, stopovers: List<Pair<Double, Double>> = emptyList(),
    ): String {
        val at = (if (whenMs > 0) whenMs else System.currentTimeMillis()) / 1000
        val way = when {
            timeType == Moovit.TIME_ARRIVAL -> "a"
            timeType == Moovit.TIME_LAST -> "l"
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

    fun plan(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>, taxi: Boolean,
        stopovers: List<Moovit.Place> = emptyList(),
    ): Planned {
        val planned = parse(KavLive.planned(path(from, to, whenMs, timeType, routeTypes, taxi, stopovers.map { it.lat to it.lon })))
        // The server's timetable runs two weeks ahead; Moovit plans further. Walking and cycling don't need it.
        if (planned.plan.itineraries.none { it.rides.isNotEmpty() } && "OUTSIDE_SERVICE_PERIOD" in planned.errors) {
            throw java.io.IOException("Kav's planner has no timetable for that day")
        }
        saveOperators()
        return Planned(planned.plan, live(planned.plan.itineraries, planned.resolved))
    }

    // Live times for Kav's routes, from Kav's server like the rest of them.
    fun live(list: List<Moovit.Itinerary>, r: Moovit.Resolved, ending: Boolean = false): Moovit.Resolved {
        if (list.isEmpty()) return r
        if (!KavLive.stopsEnabled) return r.aged()
        val rides = try { KavLive.rideArrivals(list, r.stops, r.lines, ending, uk.noammm.kav.Loaded.net) } catch (e: Exception) { return r.aged() }
        return Moovit.Resolved(r.lines, r.stops, r.routeTypes, rides.live, r.shapes, 15, r.patterns + rides.patterns,
            r.delays + Moovit.delaysOf(list, rides.live))
    }

    private fun saveOperators() {
        val o = JSONObject()
        for ((k, v) in operators) o.put(k.toString(), v)
        store?.edit()?.putString("operators", o.toString())?.apply()
    }

    internal fun parse(body: String): Planned {
        val o = JSONObject(body)
        val stops = HashMap<Int, Moovit.StopInfo>()
        o.optJSONObject("stops")?.let { s ->
            for (k in s.keys()) {
                val a = s.getJSONArray(k)
                val id = stopId(k)
                stops[id] = Moovit.StopInfo(id, a.optString(1), a.optString(0), a.optDouble(2), a.optDouble(3))
            }
        }
        val names = o.optJSONObject("agencies") ?: JSONObject()
        val lines = HashMap<Int, Moovit.LineInfo>()
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
                // A train is named for where it runs, as Moovit names its trains.
                val number = a.optString(0).ifBlank { if (from.isNotBlank() && to.isNotBlank()) "$from - $to" else a.optString(7) }
                val caption = if (fromCity.isBlank() || fromCity == toCity) toCity else "$fromCity ↔︎ $toCity"
                val id = lineId(k)
                lines[id] = Moovit.LineInfo(id, number, agency, from, to, caption)
                routeNames[id] = a.optString(7)
            }
        }
        val routes = ArrayList<Moovit.Itinerary>()
        val all = o.optJSONArray("it") ?: JSONArray()
        for (i in 0 until all.length()) {
            val it = all.getJSONObject(i)
            val legs = ArrayList<Moovit.Leg>()
            val parts = it.getJSONArray("legs")
            var lastArr = it.optLong("dep")
            for (j in 0 until parts.length()) {
                val l = parts.getJSONObject(j)
                val dep = l.optLong("dep"); val arr = l.optLong("arr")
                val shape = Moovit.decodePolyline(l.optString("shape"))
                fun point(k: String) = l.optJSONArray(k)?.let { p -> p.optDouble(0) to p.optDouble(1) }
                when (l.optString("k")) {
                    "w" -> legs += Moovit.Leg(Moovit.LegKind.WALK, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape)
                    "b" -> legs += Moovit.Leg(Moovit.LegKind.BIKE, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape)
                    // Waiting for the taxi, as Moovit's card has it, then the drive.
                    "x" -> legs += Moovit.Leg(Moovit.LegKind.OTHER, dep = dep, arr = arr)
                    "t" -> legs += Moovit.Leg(
                        Moovit.LegKind.TAXI, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape,
                        taxiPickup = point("from"), taxiDropoff = point("to"),
                    )
                    else -> {
                        // The ride and the wait for it, and the same for every other line from that stop to that stop.
                        val waitFrom = minOf(lastArr, dep)
                        val (wait, ride) = ride(l, shape, waitFrom, lines, routeNames, stops)
                        val alts = l.optJSONArray("alts") ?: JSONArray()
                        if (alts.length() == 0) {
                            legs += wait; legs += ride
                        } else {
                            val options = listOf(wait to ride) + (0 until alts.length()).map { k ->
                                val alt = alts.getJSONObject(k)
                                ride(alt, Moovit.decodePolyline(alt.optString("shape")), waitFrom, lines, routeNames, stops)
                            }
                            legs += wait.copy(alternatives = options.map { it.first })
                            legs += ride.copy(alternatives = options.map { it.second })
                        }
                    }
                }
                lastArr = arr
            }
            if (legs.isEmpty()) continue
            val tags = (it.optJSONArray("tags") ?: JSONArray()).let { t -> (0 until t.length()).mapNotNull { k -> label(t.optString(k)) } }
            routes += Moovit.Itinerary(
                guid = "kav-" + Integer.toHexString(legs.joinToString("|") { "${it.kind}${it.lineId}${it.tripId}${it.dep}" }.hashCode()),
                group = 2, legs = legs, dep = it.optLong("dep"), arr = it.optLong("arr"), tags = tags,
                sectionId = it.optInt("sec", SECTION_MAIN),
            )
        }
        val errors = (o.optJSONArray("errors") ?: JSONArray()).let { e -> (0 until e.length()).map { e.optString(it) } }
        return Planned(Moovit.Plan(routes, sections()), Moovit.Resolved(lines, stops, types), errors)
    }

    private const val SECTION_MAIN = 1

    // Moovit's sections that Kav's server fills, by Moovit's ids, in its order and with its names: the timetable card,
    // the routes, taxis, transit with a bike, walking or cycling all the way, and East Jerusalem's operators' routes.
    private fun sections() = listOf(
        Moovit.Section(3, "", 1, Moovit.SECTION_NO_GROUPING, 0),
        Moovit.Section(SECTION_MAIN, "", 127, 16, 1),
        Moovit.Section(Moovit.SECTION_TAXI, T("Taxi & Ride Hailing", "מוניות"), Int.MAX_VALUE, 1, 2),
        Moovit.Section(522, T("Combined Transit & Personal Bike Routes", "מסלולי תחב\"צ בשילוב אופניים"), 1, 3, 3),
        Moovit.Section(521, T("Walking & Biking Routes", "מסלולי הליכה ואופניים"), 3, 2, 4),
        Moovit.Section(1461, T("Suggested routes including East Jerusalem agencies", "מסלולים הכוללים את קווי מזרח ירושלים"),
            Int.MAX_VALUE, 1, 5),
    )

    // One way to take a stretch on one line: the wait at its first stop, with that line's next departures, and the
    // ride itself, along its road; through its stops when the answer has no road for it,
    // since the map draws a ride and finds the rider aboard by it.
    private fun ride(
        l: JSONObject, shape: List<Pair<Double, Double>>, waitFrom: Long,
        lines: Map<Int, Moovit.LineInfo>, names: Map<Int, String>, stops: Map<Int, Moovit.StopInfo>,
    ): Pair<Moovit.Leg, Moovit.Leg> {
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
        val mine = Moovit.Departure(
            trip, timetabled, rtUtc = if (live) dep else 0L,
            statisticalUtc = if (!live && dep != timetabled) dep else 0L,
        )
        // The line's departures there after this one, and for a route's first ride those from the time asked about
        // before it: Moovit's card lists them from then, catchable or not.
        // A third field 1: the server has that one live (a train card's other trains, which the board can't match).
        val departures = { key: String ->
            val list = l.optJSONArray(key) ?: JSONArray()
            (0 until list.length()).mapNotNull { n ->
                list.optJSONArray(n)?.let { d ->
                    val at = d.optLong(0)
                    Moovit.Departure(KavLive.tripIdOf("gtfs:" + d.optString(1)), at, rtUtc = if (d.optInt(2) == 1) at else 0L)
                }
            }
        }
        val later = departures("next")
        val earlier = departures("before")
        val from = ids.firstOrNull() ?: -1
        val to = ids.lastOrNull() ?: -1
        return Moovit.Leg(
            Moovit.LegKind.WAIT, lineId = line, dep = minOf(waitFrom, dep), arr = dep,
            fromStop = from, toStop = to, nextDeps = earlier + listOf(mine) + later,
        ) to Moovit.Leg(
            Moovit.LegKind.RIDE, lineId = line, tripId = trip, dep = dep, arr = arr, stops = ids,
            fromStop = from, toStop = to, nextDeps = listOf(mine), shortName = number,
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

    // What each route is best at, in Moovit's words for it.
    private fun label(tag: String): String? = when (tag) {
        "early_arrival" -> T("Earliest arrival", "הגעה הכי מוקדמת")
        "latest_departure" -> T("Latest departure", "יציאה הכי מאוחרת")
        "direct" -> T("No transfers", "ללא החלפות")
        "least_walking" -> T("Least walking", "הכי פחות הליכה")
        "low_transfer" -> T("Fewest transfers", "הכי מעט החלפות")
        "last_departure" -> T("Last trip", "נסיעה אחרונה")
        "long_walk" -> T("Long walk", "הליכה ארוכה")
        "long_wait_time" -> T("Long wait", "המתנה ארוכה")
        "risky_transfer" -> T("Tight connection", "החלפה צפופה")
        "high_frequency" -> T("Frequent service", "תדירות גבוהה")
        "flexible_route" -> T("Flexible route", "מסלול גמיש")
        else -> null
    }
}
