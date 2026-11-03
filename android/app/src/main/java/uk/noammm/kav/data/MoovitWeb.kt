package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject

// Moovit's public website supplies plans and timetables through MoovitWebPage.
// Its stop, line and operator ids stay intact; live times use the selected live provider,
// matched by stop code and line number.
internal object MoovitWeb {
    const val SERVER = "https://moovitapp.com"
    private const val GUID = "moovit-"

    // A route Moovit planned here, which takes live times as Kav's own do.
    fun owns(trip: Transit.Itinerary): Boolean = trip.guid.startsWith(GUID)

    class NoStopovers : java.io.IOException("Moovit's web planner doesn't plan through stops on the way")

    // The site's trip planner page, in Kav's language ("en" or "he").
    fun page(language: String) = "$SERVER/tripplan/israel-1/poi/$language?ref=16&customerId=4908"

    // Whether that page answers this phone, as the other planners are checked: a HEAD request, so nothing is downloaded,
    // in the hidden browser's own name (agent), since the site turns other clients away.
    fun check(agent: String?): KavLive.Check {
        val c = try {
            java.net.URL(page("en")).openConnection() as java.net.HttpURLConnection
        } catch (e: java.io.IOException) {
            return KavLive.Check.Unreached(KavLive.Check.Why.NO_SUCH_HOST)
        }
        return try {
            c.requestMethod = "HEAD"; c.connectTimeout = 8_000; c.readTimeout = 8_000
            agent?.let { c.setRequestProperty("User-Agent", it) }
            if (c.responseCode != 200) KavLive.Check.Unreached(KavLive.Check.Why.NO_ANSWER)
            else KavLive.Check.Answers(false, 0, System.currentTimeMillis() / 1000, planner = true, predictions = null,
                timetable = null, bundleKey = null)
        } catch (e: java.net.UnknownHostException) {
            KavLive.Check.Unreached(KavLive.Check.Why.NO_SUCH_HOST)
        } catch (e: javax.net.ssl.SSLException) {
            KavLive.Check.Unreached(KavLive.Check.Why.HTTPS)
        } catch (e: java.net.SocketTimeoutException) {
            KavLive.Check.Unreached(KavLive.Check.Why.TIMEOUT)
        } catch (e: java.io.IOException) {
            KavLive.Check.Unreached(KavLive.Check.Why.NO_ANSWER)
        } finally {
            c.disconnect()
        }
    }

    // The search as the site's page asks it: the fastest routes (tripPlanPref 2), leaving now or at a time, arriving by
    // one or the last trip (timeType, the same 1 to 3 as Transit's), on the route types asked for, with bikes and the
    // rider's own car (routeTransportOptions 1 and 5) as the site has them.
    internal fun query(from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, types: List<Int>) =
        JSONObject().apply {
            put("tripPlanPref", 2)
            put("time", if (whenMs > 0) whenMs else System.currentTimeMillis())
            put("timeType", timeType)
            put("isCurrentTime", whenMs <= 0 && timeType == Transit.TIME_DEPARTURE)
            // In Moovit's numbers: the week's funicular, the Carmelit, is Moovit's 5, and Moovit's 7 is its shuttles,
            // always asked for and left out by the Shuttle filter, as on Kav's planner.
            put("routeTypes", (types.ifEmpty { Transit.ALL_ROUTE_TYPES }.map { if (it == 7) 5 else it } + 7).distinct().joinToString(","))
            put("routeTransportOptions", "1,5")
            for ((side, point) in listOf("fromLocation" to from, "toLocation" to to)) {
                put("${side}_id", 0); put("${side}_type", 6)
                put("${side}_latitude", Math.round(point.first * 1e6)); put("${side}_longitude", Math.round(point.second * 1e6))
                put("${side}_caption", "")
            }
        }

    fun plan(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, types: List<Int>, taxi: Boolean,
        stopovers: List<Transit.Place>,
    ): KavPlan.Planned {
        if (stopovers.isNotEmpty()) throw NoStopovers()
        val planned = parse(MoovitWebPage.plan(query(from, to, whenMs, timeType, types)))
        if (taxi) return planned
        val plan = planned.plan
        return KavPlan.Planned(Transit.Plan(plan.itineraries.filterNot(plan::isTaxiCard), plan.sections), planned.resolved)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
    private fun JSONObject.flag(key: String) = when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> false
    }
    private fun JSONObject.ms(key: String) = optLong(key).takeIf { it > 0 } ?: 0L

    // The site's page (its answer pages, and the metro's operators): its itineraries, its section table, and the
    // details of their stops and lines that come with them.
    internal fun parse(o: JSONObject): KavPlan.Planned {
        val pages = o.optJSONArray("pages").objects()
        val entries = pages.flatMap { it.optJSONArray("results").objects() }
        val itineraries = ArrayList<Transit.Itinerary>()
        var sections = emptyList<Transit.Section>()
        for (e in entries) {
            val result = e.optJSONObject("result") ?: continue
            result.optJSONObject("itinerary")?.let(::itinerary)?.let(itineraries::add)
            result.optJSONObject("tripPlanSections")?.optJSONArray("tripPlanSections")?.let { list ->
                sections = list.objects().mapIndexed(::section)
            }
        }
        if (itineraries.isEmpty()) pages.flatMap { it.optJSONArray("errors").objects() }.firstOrNull()?.let { throw refusal(it) }
        val stops = HashMap<Int, Transit.StopInfo>()
        val lines = HashMap<Int, Transit.LineInfo>()
        for (data in entries.mapNotNull { it.optJSONObject("supplementalData") }) {
            for (s in data.optJSONArray("mVStopSyncedMetaDataList").objects()) {
                val id = s.optInt("stopId", -1).takeIf { it > 0 } ?: continue
                val name = s.optString("stopName").ifEmpty { null } ?: continue
                val at = s.optJSONObject("stopLocation")
                stops[id] = Transit.StopInfo(
                    id, name, s.optString("stopCode"),
                    at?.let { it.optInt("latitude") / 1e6 } ?: Double.NaN, at?.let { it.optInt("longitude") / 1e6 } ?: Double.NaN,
                )
            }
            for (g in data.optJSONArray("lineGroupSummaryList").objects()) {
                val caption = if (g.has("caption2")) g.optString("caption2") else g.optString("caption1")
                for (l in g.optJSONArray("lineSummaries").objects()) {
                    val id = l.optInt("lineId", -1).takeIf { it > 0 } ?: continue
                    lines[id] = Transit.LineInfo(
                        g.optInt("groupId", -1), g.optString("lineNumber"), g.optInt("agencyId", -1),
                        l.optString("origin"), l.optString("destination"), caption,
                    )
                }
            }
        }
        // Every operator's kind of line, as the week numbers them (Moovit's shuttles are its type 7, the week's
        // funicular), and its name, kept with the other planners' so a trip reopened later names it.
        val types = HashMap<Int, Int>()
        for (a in o.optJSONArray("agencies").objects()) {
            val id = a.optInt("agencyId", -1).takeIf { it >= 0 } ?: continue
            types[id] = typeOf(a.optInt("routeType", 3), a.optString("agencyName"))
            a.optString("agencyName").takeIf { it.isNotBlank() }?.let { KavPlan.keepOperator(id, it) }
        }
        return KavPlan.Planned(Transit.Plan(itineraries.distinctBy { it.guid }, sections), Transit.Resolved(lines, stops, types))
    }

    // Moovit's app lists every suggested route under no heading (it caps them at 127 and names them nothing); the site
    // names them and shows two.
    private const val SUGGESTED_ROUTES = 16

    private fun section(index: Int, s: JSONObject): Transit.Section {
        val type = s.optInt("sectionType")
        val suggested = type == SUGGESTED_ROUTES
        return Transit.Section(
            s.optInt("sectionId", -1), if (suggested) "" else s.optString("name"),
            if (suggested) 127 else s.optInt("maxItemsToDisplay", Int.MAX_VALUE), type, index,
        )
    }

    // The site's reasons for no routes are the codes Moovit's app gave (Transit.PLAN_*).
    private fun refusal(e: JSONObject): Transit.PlannerRefusal {
        val code = when (val c = e.opt("errorCode")) {
            is Number -> c.toInt()
            else -> mapOf("TRIP_PLAN_OUT_OF_METRO_AREA" to Transit.PLAN_TOO_FAR, "TOO_CLOSE" to Transit.PLAN_TOO_CLOSE,
                "NO_TRIP_PLAN_FOUND" to Transit.PLAN_NO_ROUTES)[c?.toString()] ?: 0
        }
        return Transit.PlannerRefusal(code, e.optString("message"), "")
    }

    private fun itinerary(it: JSONObject): Transit.Itinerary? {
        val legs = it.optJSONArray("legs").objects().map(::leg)
        if (legs.isEmpty()) return null
        val times = legs.flatMap { listOf(it.dep, it.arr) }.filter { it > 0 }
        val price = it.optJSONObject("itineraryFare")?.optJSONObject("price")
        return Transit.Itinerary(
            guid = GUID + it.optString("guid"),
            group = it.optInt("groupType", -1),
            legs = legs,
            dep = legs.firstOrNull { it.dep > 0 }?.dep ?: times.minOrNull() ?: 0,
            arr = legs.lastOrNull { it.arr > 0 }?.arr ?: times.maxOrNull() ?: 0,
            fare = price?.optInt("balance", -1) ?: -1,
            currency = price?.optString("currencySymbol").orEmpty(),
            co2g = it.optJSONObject("emissionLevel")?.optInt("co2", -1) ?: -1,
            accessible = it.flag("isAccessible"),
            // Moovit's tags are those Kav's planner gives, by their words; they show in the language Kav is shown in.
            tags = it.optJSONArray("tags").objects().mapNotNull { t ->
                t.optString("id").takeIf { id -> KavPlan.tagWord(id) != null } ?: KavPlan.tagOf(t.optString("text"))
            },
            section = it.optString("sectionName"),
            sectionId = it.optInt("sectionId", -1),
        )
    }

    private fun times(l: JSONObject): Pair<Long, Long> {
        val t = l.optJSONObject("time") ?: return 0L to 0L
        return t.ms("startTimeUtc") / 1000 to t.ms("endTimeUtc") / 1000
    }

    private fun shape(l: JSONObject) = KavLive.decodePolyline(l.optJSONObject("shape")?.optString("polyline"))
    private fun meters(l: JSONObject) = (l.optJSONObject("shape")?.optDouble("distanceInMeters")?.takeIf { !it.isNaN() } ?: 0.0).toInt()

    private fun point(location: JSONObject?): Pair<Double, Double>? {
        val ll = location?.optJSONObject("latlon") ?: return null
        if (!ll.has("latitude") || !ll.has("longitude")) return null
        val lat = ll.optInt("latitude") / 1e6; val lon = ll.optInt("longitude") / 1e6
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    // Trains come as "3 🚆 #121": the platform, then the train's number. Until the platform is known only the glyph and
    // the number are sent.
    private fun platformOf(raw: String): String =
        raw.substringBefore('#').trim().substringBefore(' ').takeIf { it.length <= 4 && it.all(Char::isLetterOrDigit) }.orEmpty()

    private fun departure(a: JSONObject): Transit.Departure? {
        val rt = a.ms("rtEtdUTC"); val stat = a.ms("statisticalEtdUTC"); val sched = a.ms("staticEtdUTC")
        if (rt == 0L && stat == 0L && sched == 0L) return null
        return Transit.Departure(
            tripId = a.optLong("tripId"), staticUtc = sched / 1000, rtUtc = rt / 1000, statisticalUtc = stat / 1000,
            status = a.optInt("status"), certainty = a.optInt("arrivalCertainty"), traffic = a.optInt("trafficStatus"),
            frequency = a.has("frequencyId"), rtDropped = rt == 0L && a.flag("rtDropInMetro"),
            vehicleStatus = a.optJSONObject("vehicleLocation")?.optInt("vehicleStatus") ?: 0,
            platform = platformOf(a.optString("platformName")),
        )
    }

    // A wait for one line, alone (waitToLineLeg) or one of a stop's lines (an alternative of waitToMultiLineLeg, whose
    // stops are the leg's own): its departure first, then the line's next ones, each with the line's service alert.
    private fun wait(l: JSONObject, multi: JSONObject? = null): Transit.Leg {
        val (dep, arr) = times(l)
        val status = l.optJSONObject("serviceAlert")?.optJSONObject("serviceStatus")
        val alert = status?.optInt("category") ?: 0
        val end = l.optJSONObject("time")?.ms("endTimeUtc") ?: 0L
        val deps = listOfNotNull(if (end > 0) Transit.Departure(0, end / 1000) else null) +
            l.optJSONObject("futureDepartureTimes")?.optJSONArray("futureDepartures").objects().mapNotNull(::departure)
        val stops = multi ?: l
        return Transit.Leg(
            Transit.LegKind.WAIT, lineId = l.optInt("waitToLineId", -1), dep = dep, arr = arr,
            fromStop = stops.optInt("waitAtStopId", -1), toStop = stops.optInt("departOnStopId", -1),
            nextDeps = if (alert == 0) deps else deps.map { it.copy(alert = alert) },
            alertCategory = alert, alertText = status?.optString("desc").orEmpty(),
        )
    }

    private fun ride(l: JSONObject): Transit.Leg {
        val (dep, arr) = times(l)
        val stops = l.optJSONArray("stopSequenceIds").ints()
        val trip = l.optLong("tripId")
        val start = l.optJSONObject("time")?.ms("startTimeUtc") ?: 0L
        val price = l.optJSONObject("legFare")?.optJSONObject("price")
        return Transit.Leg(
            Transit.LegKind.RIDE, lineId = l.optInt("lineId", -1), tripId = trip, dep = dep, arr = arr, stops = stops,
            nextDeps = listOfNotNull(if (start > 0) Transit.Departure(trip, start / 1000) else null),
            fromStop = stops.firstOrNull() ?: -1, toStop = stops.lastOrNull() ?: -1,
            shortName = l.optString("tripShortName"), shape = shape(l),
            fare = price?.optInt("balance", -1) ?: -1, currency = price?.optString("currencySymbol").orEmpty(),
        )
    }

    // Moovit's "Shared Mobility" routes ride a dockless leg: a shared bike, scooter, moped or car, ridden like a bike.
    private fun dockless(l: JSONObject, dep: Long, arr: Long): Transit.Leg {
        val car = l.optJSONObject("info")?.optJSONObject("carDetails")
        val vehicle = l.optJSONObject("info")?.optJSONObject("docklessDetails")
        return Transit.Leg(
            Transit.LegKind.BIKE, dep = dep, arr = arr, meters = meters(l), shape = shape(l),
            shared = when {
                car != null -> -1
                vehicle != null -> vehicle.optInt("type", 1)
                else -> 1
            },
            sharedBy = (car ?: vehicle)?.optString("operatorName").orEmpty(),
        )
    }

    // One leg, by the kind its one field names (MVTripPlanLeg).
    internal fun leg(o: JSONObject): Transit.Leg {
        val kind = o.keys().asSequence().firstOrNull() ?: return Transit.Leg(Transit.LegKind.OTHER)
        val l = o.optJSONObject(kind) ?: return Transit.Leg(Transit.LegKind.OTHER)
        val (dep, arr) = times(l)
        return when (kind) {
            "walkLeg" -> {
                val journey = l.optJSONObject("journey")
                Transit.Leg(
                    Transit.LegKind.WALK, dep = dep, arr = arr,
                    fromStop = journey?.optJSONObject("origin")?.optInt("id", -1) ?: -1,
                    toStop = journey?.optJSONObject("dest")?.optInt("id", -1) ?: -1,
                    meters = meters(l), shape = shape(l),
                )
            }
            "pathwayWalkLeg" -> Transit.Leg(Transit.LegKind.WALK, dep = dep, arr = arr, toStop = l.optInt("stopId", -1), pathway = true)
            "waitToLineLeg" -> wait(l)
            "waitToMultiLineLeg" -> {
                val options = l.optJSONArray("alternatives").objects().map { wait(it, multi = l) }
                (options.getOrNull(l.optInt("primaryAlternativeIndex")) ?: options.firstOrNull())?.copy(alternatives = options)
                    ?: Transit.Leg(Transit.LegKind.WAIT, dep = dep, arr = arr)
            }
            "lineLeg" -> ride(l)
            "multiLineLeg" -> l.optJSONObject("lineLeg")?.let { ride(it).copy(alternativeLineIds = l.optJSONArray("alternativeLines").ints()) }
                ?: Transit.Leg(Transit.LegKind.OTHER, dep = dep, arr = arr)
            "lineWithAlternativesLeg" -> {
                val options = l.optJSONArray("alternativeLines").objects().map(::ride)
                (options.getOrNull(l.optInt("primaryAlternativeIndex")) ?: options.firstOrNull())?.copy(alternatives = options)
                    ?: Transit.Leg(Transit.LegKind.OTHER, dep = dep, arr = arr)
            }
            "taxiLeg" -> {
                val journey = l.optJSONObject("journey")
                Transit.Leg(
                    Transit.LegKind.TAXI, dep = dep, arr = arr, meters = meters(l), shape = shape(l),
                    taxiPickup = point(journey?.optJSONObject("origin")), taxiDropoff = point(journey?.optJSONObject("dest")),
                )
            }
            "bicycleLeg", "bicycleRentalLeg" -> Transit.Leg(Transit.LegKind.BIKE, dep = dep, arr = arr, meters = meters(l), shape = shape(l))
            "docklessLeg" -> dockless(l, dep, arr)
            else -> Transit.Leg(Transit.LegKind.OTHER, dep = dep, arr = arr)
        }
    }

    // A trip shared from Moovit (TripLink): its itinerary by its id and its two ends, asked in the site's hidden page as
    // the site's own page opens the link (moovit_web_lines.js), which Kav opens as the exact shared trip.
    fun shared(id: String): TripLink.Plan = sharedPlan(MoovitWebPage.lines(JSONObject().put("kind", "shared").put("id", id)))

    // Its ends, and when it leaves if that's still to come; else it's planned from now.
    internal fun sharedPlan(o: JSONObject, now: Long = System.currentTimeMillis()): TripLink.Plan {
        if (o.optBoolean("expired")) throw TripLink.Expired()
        val itinerary = o.optJSONObject("itinerary") ?: throw java.io.IOException("This link holds no trip")
        val entry = JSONObject().put("result", JSONObject().put("itinerary", itinerary))
            .put("supplementalData", o.optJSONObject("supplementalData") ?: JSONObject())
        val exact = parse(JSONObject().put("pages", JSONArray().put(JSONObject().put("results", JSONArray().put(entry))))
            .put("agencies", o.optJSONArray("agencies") ?: JSONArray()))
        if (exact.plan.itineraries.isEmpty()) throw java.io.IOException("This link holds no trip")
        val from = o.optJSONArray("from"); val to = o.optJSONArray("to")
        val at = o.optLong("at")
        return TripLink.Plan(
            from?.optString(2)?.ifBlank { null }, from?.optDouble(0), from?.optDouble(1),
            to?.optString(2)?.ifBlank { null }, to?.optDouble(0), to?.optDouble(1),
            departMs = at.takeIf { it > now } ?: 0L, autoRun = true, exact = exact,
        )
    }

    // Stop boards use each line's day from the site's Lines tab. The local week finds candidates;
    // only patterns the site confirms at that stop contribute departures.
    private val days = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    fun lines(): List<Schedules.Line> = uk.noammm.kav.Loaded.net.let { net ->
        lines(MoovitWebPage.lines(JSONObject().put("kind", "lines")), net?.city?.toHashSet().orEmpty(), net?.name?.toHashSet().orEmpty())
    }

    // Each line group as a line, its ends from the ministry's long name of its first line; else, as the site names its
    // light rail, trains and shuttles, "one end - the other", or its caption. A train line goes by no number, as the
    // week's (the site numbers it by its two stations), and one way: the site has a group for each. A station's own name
    // can have " - " in it ("בית שאן - דוד לוי"): a longer name is cut where both sides are names the week knows.
    internal fun lines(o: JSONObject, towns: Set<String>, known: Set<String> = emptySet()): List<Schedules.Line> {
        val agencies = HashMap<Int, Pair<String, Int>>()
        o.optJSONArray("agencies")?.let { a ->
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { x -> agencies[x.optInt(0)] = x.optString(1) to x.optInt(2, 3) }
        }
        val groups = o.optJSONArray("groups") ?: JSONArray()
        return (0 until groups.length()).mapNotNull { i ->
            val g = groups.optJSONArray(i) ?: return@mapNotNull null
            val (agency, type) = agencies[g.optInt(1)] ?: ("" to 3)
            val operator = agency.substringBefore(" (").trim()
            val long = g.optString(3).trim(); val caption = g.optString(4).trim()
            val ends = Schedules.ends(long, towns)
            val parts = long.split(" - ").map { it.trim() }
            val named = when {
                ends != null || parts.size < 2 || parts.any(String::isEmpty) -> null
                parts.size == 2 -> parts
                else -> (1 until parts.size).map { k -> listOf(parts.take(k).joinToString(" - "), parts.drop(k).joinToString(" - ")) }
                    .firstOrNull { (a, b) -> a in known && b in known }
            }
            val captioned = caption.replace("\uFE0E", "").split('↔').map { it.trim() }.filter { it.isNotEmpty() }
            val kind = typeOf(type, operator)
            Schedules.Line(g.optString(0), if (kind == 2) "" else g.optString(2).trim(), kind, operator,
                ends?.first?.first ?: named?.get(0) ?: captioned.firstOrNull() ?: long,
                ends?.second?.first ?: named?.get(1) ?: captioned.getOrNull(1).orEmpty(), listOf(g.optString(0)),
                listOfNotNull(long.ifEmpty { null }), '↔' in caption || named != null && kind != 2)
        }
    }

    // Moovit's route types as the week's: its shuttles are type 7, which in the week is the Carmelit (type 5 here).
    internal fun typeOf(type: Int, operator: String): Int = when {
        "כרמלית" in operator -> 7
        type == 7 -> 711
        else -> type
    }

    // One line's day, kept for a few lines; with ahead, the first of that many days from it that has trips, the days
    // before it kept as having none.
    private fun day(line: Schedules.Line, on: java.time.LocalDate, ahead: Int = 1): JSONObject {
        val prefix = "line/${uk.noammm.kav.ui.T.lang}/${line.id}"
        fun kept(d: java.time.LocalDate): JSONObject? {
            val key = "${Schedules.today()}/$prefix/$d"
            return days[key] ?: Schedules.kept(Source.MOOVIT, "$prefix/$d")?.let { JSONObject(it) }?.also { days[key] = it }
        }
        fun keep(d: java.time.LocalDate, o: JSONObject) {
            days["${Schedules.today()}/$prefix/$d"] = o
            Schedules.keep(Source.MOOVIT, "$prefix/$d", o.toString())
        }
        for (k in 0 until ahead) {
            val kept = kept(on.plusDays(k.toLong())) ?: break
            if ((kept.optJSONArray("ways")?.length() ?: 0) > 0 || k == ahead - 1) return kept
        }
        if (days.size > 20) days.clear()
        val o = MoovitWebPage.lines(JSONObject().put("kind", "line").put("group", line.id.toLong()).put("day", on.toEpochDay())
            .put("ahead", ahead))
        val found = java.time.LocalDate.ofEpochDay(o.optLong("day", on.toEpochDay()))
        var d = on
        while (d.isBefore(found)) { keep(d, JSONObject().put("ways", JSONArray())); d = d.plusDays(1) }
        keep(found, o)
        return o
    }

    // The line's ways on its first day from on with trips, within a week.
    fun ways(line: Schedules.Line, on: java.time.LocalDate): List<Schedules.Way> = ways(day(line, on, ahead = 7))

    // Each way the day's trips take, the one with the most trips first: its stops in order (the week on the phone's
    // town for each), its road, and the stops where every trip of it only lets riders off.
    internal fun ways(o: JSONObject): List<Schedules.Way> {
        val net = uk.noammm.kav.Loaded.net
        val stops = HashMap<Long, Schedules.Stop>()
        o.optJSONArray("stops")?.let { a ->
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { s ->
                val code = s.optString(1).toIntOrNull() ?: 0
                val town = net?.let { n -> KavLive.stopOfCode(n, code).takeIf { it >= 0 }?.let { n.cityOf(it) } }.orEmpty()
                stops[s.optLong(0)] = Schedules.Stop(code, s.optString(2), town, s.optDouble(3) / 1e6, s.optDouble(4) / 1e6)
            }
        }
        val patterns = HashMap<Long, List<Long>>()
        o.optJSONArray("patterns")?.let { a ->
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { p ->
                patterns[p.optLong(0)] = p.optJSONArray(1)?.let { ids -> (0 until ids.length()).map { ids.optLong(it) } }.orEmpty()
            }
        }
        val shapes = HashMap<Long, String>()
        o.optJSONArray("shapes")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { shapes[it.optLong(0)] = it.optString(1) } }
        val ways = o.optJSONArray("ways") ?: return emptyList()
        return (0 until ways.length()).map { ways.getJSONObject(it) }.sortedByDescending { it.optJSONArray("trips")?.length() ?: 0 }
            .mapNotNull { w ->
                val list = patterns[w.optLong("pattern")]?.map { stops[it] ?: Schedules.Stop(0, "", "", Double.NaN, Double.NaN) }
                    ?: return@mapNotNull null
                val trips = w.optJSONArray("trips") ?: JSONArray()
                val alight = (0 until trips.length()).map { k ->
                    val blocked = trips.optJSONArray(k)?.optJSONArray(2)
                    (0 until (blocked?.length() ?: 0)).map { blocked!!.optInt(it) }.toSet()
                }.reduceOrNull { a, b -> a intersect b }.orEmpty()
                Schedules.Way("${w.optLong("line")}/${w.optLong("pattern")}", list.lastOrNull()?.name.orEmpty(), list,
                    KavLive.decodePolyline(shapes[w.optLong("shape")]), alight)
            }.filter { it.stops.size >= 2 }
    }

    fun times(line: Schedules.Line, way: Schedules.Way, at: Int, on: java.time.LocalDate): List<Long> = times(day(line, on), way, at)

    // A way's times are also those of the ways calling at its very same stops (also).
    internal fun times(o: JSONObject, way: Schedules.Way, at: Int): List<Long> =
        (listOf(way.id) + way.also).flatMap { times(o, it, at) }.distinct().sorted()

    fun departures(code: Int, on: java.time.LocalDate, lines: List<Schedules.Line>): List<Schedules.Departure> {
        val net = uk.noammm.kav.Loaded.net ?: throw java.io.IOException("No stop catalogue on the phone")
        val candidates = linesAt(net, code, lines)
        if (candidates.isEmpty()) throw java.io.IOException("No Moovit lines found for stop $code")
        val board = JSONObject(Schedules.cached(Source.MOOVIT, "board/${uk.noammm.kav.ui.T.lang}/$code/$on") {
            MoovitWebPage.lines(JSONObject().put("kind", "board").put("day", on.toEpochDay())
                .put("groups", JSONArray(candidates.map { it.id.toLong() }))).toString()
        })
        return departures(board, code, candidates.associateBy { it.id })
    }

    // The phone's stop codes find candidate operator/number pairs. Moovit's patterns, not the local trips, then
    // decide which ways actually call there, including variants the local week doesn't have.
    internal fun linesAt(net: Net, code: Int, lines: List<Schedules.Line>): List<Schedules.Line> {
        val keys = HashSet<String>()
        for (s in net.code.indices) if (net.code[s] == code) for (i in net.dStart[s] until net.dStart[s + 1]) {
            val r = net.tripRoute[net.tripOf(net.cST[net.dConn[i]])]
            keys.add("${net.agencyOf(r)}|${net.rType[r]}|${if (net.rType[r] == 2) "" else net.rShort[r]}")
        }
        return lines.filter { "${it.operator}|${it.type}|${it.number}" in keys }
    }

    internal fun departures(o: JSONObject, code: Int, lines: Map<String, Schedules.Line>): List<Schedules.Departure> {
        val out = ArrayList<Schedules.Departure>()
        for (day in o.optJSONArray("days").objects()) {
            val line = lines[day.optString("group")] ?: continue
            for (way in ways(day)) for (at in 0 until way.stops.lastIndex) if (way.stops[at].code == code) {
                for (time in times(day, way.id, at)) out.add(Schedules.Departure(time, line, line.number, way.to, ""))
            }
        }
        return out.distinctBy { "${it.line.operator}|${it.line.type}|${it.number}|${it.to}|${it.at}" }.sortedBy { it.at }
    }

    // When the way's trips leave its stop at, from when each set out and its seconds to each stop after the first.
    internal fun times(o: JSONObject, way: String, at: Int): List<Long> {
        val ways = o.optJSONArray("ways") ?: return emptyList()
        val w = (0 until ways.length()).map { ways.getJSONObject(it) }
            .firstOrNull { "${it.optLong("line")}/${it.optLong("pattern")}" == way } ?: return emptyList()
        val profiles = w.optJSONArray("profiles") ?: JSONArray()
        val trips = w.optJSONArray("trips") ?: JSONArray()
        return (0 until trips.length()).mapNotNull { k ->
            val t = trips.optJSONArray(k) ?: return@mapNotNull null
            val blocked = t.optJSONArray(2)
            if ((0 until (blocked?.length() ?: 0)).any { blocked!!.optInt(it) == at }) return@mapNotNull null
            val start = t.optLong(0) / 1000
            val offsets = profiles.optJSONArray(t.optInt(1))
            when {
                at == 0 -> start
                offsets != null && at - 1 < offsets.length() -> start + offsets.optLong(at - 1)
                else -> null
            }
        }.distinct().sorted()
    }
}
