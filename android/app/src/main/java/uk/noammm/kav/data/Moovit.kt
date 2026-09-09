package uk.noammm.kav.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Moovit's mobile API, online mode. Proven end to end against the live service:
 *
 *   register()      POST app4 UserAuth/CreateUser  -> user_key + JWT tokens (one call)
 *   planTrip()      POST app5 V4/TripPlanner2/Search
 *   liveVehicles()  POST app5 V4/StopsArrivals      -> real-time vehicle GPS positions
 *
 * Transport is Thrift TBinaryProtocol, bare struct (Thrift.kt). Auth is three headers:
 * api_key (app constant), user_key, access-token (a 24h Login JWT). CreateUser returns
 * everything in one call, no Firebase, no separate token step.
 *
 * PRIVACY: this is the online mode. Unlike Kav's offline planner, every call here goes
 * to Moovit's servers with the trip/stop you are looking at. That is the honest cost of
 * Moovit's live data, and the UI must say so.
 */

class MoovitSession(
    val userKey: String,
    val accessToken: String,
    val refreshToken: String,
    val metroId: Int,
    val accessExpiresUtc: Long,
)

class LiveVehicle(
    val lineId: Int,
    val tripId: Long,
    val vehicleId: String,
    val lat: Double,
    val lon: Double,
    val sampleUtc: Long,
    val etaUtc: Long,
    val status: Int,
)

object Moovit {
    const val APP_ID = "moovit_2751703405"
    const val CLIENT_VERSION = "5.199.1.1804"
    private const val APP4 = "https://app4.moovitapp.com/services-app/services/"
    private const val APP5 = "https://app5.moovitapp.com/services-app/services/"
    /**
     * The metro revision every request has to quote. It is NOT a constant: Moovit
     * publishes a new one whenever the timetable changes, one rolled overnight on
     * 2026-09-08, and every call made with a stale one comes back **HTTP 412
     * Precondition Failed** with an empty body. Hardcoding it means the whole app
     * silently stops working the next time they publish.
     *
     * The server hands back the right value in the 412's own `metro-revision-number`
     * header, so the fix is to believe it and retry. Kept in memory: a fresh process
     * may 412 once, adopt the new revision and carry on without the user seeing it.
     */
    @Volatile
    private var metroRev: String = "1788783184120"

    private const val REV_HEADER = "Metro-Revision-Number"

    // HTTP

    /** Replaces the revision header, whatever case the caller spelled it in. */
    private fun withRev(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals(REV_HEADER, ignoreCase = true) } + (REV_HEADER to metroRev)

    /**
     * Adopts a newer revision the server names in a 412. Returns true when the caller
     * should retry, only once, and only if the value actually changed.
     */
    private fun adoptRevision(c: HttpURLConnection, code: Int): Boolean {
        if (code != 412) return false
        val fresh = c.getHeaderField(REV_HEADER)?.trim().orEmpty()
        if (fresh.isEmpty() || fresh == metroRev) return false
        metroRev = fresh
        return true
    }

    private fun post(base: String, path: String, body: ByteArray, headers: Map<String, String>): Pair<Int, ByteArray> {
        repeat(2) { attempt ->
            val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 25000
                for ((k, v) in withRev(headers)) setRequestProperty(k, v)
            }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { s ->
                val bytes = s.readBytes()
                if (c.contentEncoding == "gzip") GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            } ?: ByteArray(0)
            if (attempt == 0 && adoptRevision(c, code)) return@repeat   // retry with the new one
            return code to raw
        }
        return 412 to ByteArray(0)
    }

    // auth
    private fun latlon(lat: Double, lon: Double) = TWriter()
        .i32Field(1, (lat * 1e6).toInt()).i32Field(2, (lon * 1e6).toInt())

    private fun locale() = TWriter().strField(1, "en").strField(2, "GB").strField(3, "")
    private fun dpk() = TWriter().strField(1, "").strField(2, "").strField(3, "")

    private fun createUserBody(lat: Double, lon: Double): ByteArray = TWriter().apply {
        structField(1, latlon(lat, lon))
        structField(3, locale())
        strField(4, "Nothing Galaga"); strField(5, "15_35"); i32Field(6, 2)   // deviceName, osVersion, Android
        structField(7, dpk())
        strField(8, ""); strField(9, ""); boolField(10, true)                  // adId/appsflyer EMPTY
        i32Field(11, 5); i64Field(12, System.currentTimeMillis()); i32Field(13, 1)
        strField(15, UUID.randomUUID().toString().replace("-", "").substring(0, 16))  // uniqueId
        strField(16, APP_ID)                                                   // externalApiKey (required!)
        strField(19, UUID.randomUUID().toString())                            // clientInstallationKey
        strField(20, UUID.randomUUID().toString().replace("-", ""))           // brazeAliasUniqueId
        strField(21, "com.tranzmate")                                          // storeApplicationId (required!)
        stop()
    }.bytes()

    /** Create a fresh Moovit identity. One call returns user_key + access/refresh JWTs. */
    fun register(lat: Double = 32.0755, lon: Double = 34.7755): MoovitSession {
        val h = mapOf(
            "Content-Type" to "application/octet", "Accept" to "application/json",
            "Accept-Encoding" to "gzip", "User-Agent" to "ktor-client",
            "api_key" to APP_ID, "client_version" to CLIENT_VERSION, "phone_type" to "2",
        )
        val (code, raw) = post(APP4, "UserAuth/CreateUser", createUserBody(lat, lon), h)
        if (code != 200) throw RuntimeException("CreateUser HTTP $code")
        // reply is field-id-keyed Thrift-JSON: {"1":{"rec":{...}}}
        val rec = JSONObject(String(raw, Charsets.UTF_8)).getJSONObject("1").getJSONObject("rec")
        val tokens = rec.getJSONObject("7").getJSONObject("rec").getJSONObject("1").getJSONObject("rec")
        val access = tokens.getJSONObject("1").getJSONObject("rec")
        val refresh = tokens.getJSONObject("2").getJSONObject("rec")
        return MoovitSession(
            userKey = rec.getJSONObject("1").getString("str"),
            accessToken = access.getJSONObject("3").getString("str"),
            accessExpiresUtc = access.getJSONObject("2").getLong("i64") / 1000,
            refreshToken = refresh.getJSONObject("3").getString("str"),
            metroId = rec.getJSONObject("3").getInt("i16"),
        )
    }

    private fun authHeaders(s: MoovitSession) = mapOf(
        "Content-Type" to "application/octet", "Accept" to "application/octet",
        "Accept-Encoding" to "gzip", "User-Agent" to "Dalvik/2.1.0",
        "api_key" to APP_ID, "client_version" to CLIENT_VERSION, "phone_type" to "2",
        "request-sequence-id" to "1",
        "user_key" to s.userKey, "access-token" to s.accessToken,
        "Metro-Revision-Metro-Id" to s.metroId.toString(), REV_HEADER to metroRev,
    )

    // live arrivals
    // A trip plan's selected MVTime can already be real-time. Its future MVArrival
    // list is separate and may omit that selected trip. StopsArrivals enriches those
    // times with current estimates, confidence and vehicle positions.

    data class ArrivalKey(val stopId: Int, val tripId: Long)

    class Arrival(
        val stopId: Int,
        val lineId: Int,
        val tripId: Long,
        val staticUtc: Long,
        val rtUtc: Long,
        /** MVArrival.statisticalEtdUTC, learned from past runs, 0 when absent. */
        val statisticalUtc: Long,
        val status: Int,
        val certainty: Int,
        val traffic: Int,
        /** MVArrival.frequencyId is set: an "every N minutes" service. */
        val frequency: Boolean,
        /** MVArrival.rtDropInMetro with no rtEtdUTC: this trip's tracking was lost. */
        val rtDropped: Boolean,
        val tracked: Boolean,
        /** MVVehicleLocation, where the vehicle actually is, when it is being tracked. */
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val vehicleId: String = "",
        val sampleUtc: Long = 0,
        /** MVVehicleStatus: 1 ON_SHAPE, 2 OUT_OF_SHAPE, 3 TRIP_NOT_STARTED. */
        val vehicleStatus: Int = 0,
        /** MVVehicleProgress.nextStopIndex, into the trip's own stop pattern. */
        val nextStopIndex: Int = -1,
        /** where YOUR stop sits in that pattern, and how long the pattern is. */
        val stopIndex: Int = -1,
        val patternStops: Int = -1,
        /** MVArrival.tripShapeId, the whole route this vehicle is driving. */
        val tripShapeId: Int = -1,
        /** MVArrival.patternId: its stop sequence determines whether it reaches our exit. */
        val patternId: Int = -1,
    ) {
        val key get() = ArrivalKey(stopId, tripId)
        fun departure(alert: Int = 0) = Departure(
            tripId, staticUtc, rtUtc, statisticalUtc, status, certainty, traffic,
            frequency, rtDropped, vehicleStatus, alert,
        )
        val hasLocation get() = tracked && lat != 0.0 && lon != 0.0
        /** "Stops away: n", how many stops the vehicle still has before yours. */
        val stopsAway get() =
            if (nextStopIndex >= 0 && stopIndex >= nextStopIndex) stopIndex - nextStopIndex else -1
    }

    /**
     * A service alert exactly as the server publishes it, the wording is never
     * invented or summarised here.
     *
     * MVServiceAlertDetails: 1 alertId, 3 serviceStatus (MVServiceStatus: 1 category,
     * 2 desc), 6 activeFrom, 7 activeTo, 8 title, 9 desc (MVText: 1 data), 10 infoUrl.
     */
    class ServiceAlert(
        val id: String,
        /** MVServiceStatusCategory: 3 Modified, 4 Critical. */
        val category: Int,
        /** the short label the card already shows, "Detour", "Modified Service". */
        val label: String,
        val title: String,
        val body: String,
        /** MVTextFormat: 0 PLAIN, 1 HTML, the operator writes most of these in HTML. */
        val html: Boolean = false,
        /** seconds; 0 when the server gives no bound. */
        val activeFrom: Long = 0,
        val activeTo: Long = 0,
        val url: String = "",
    )

    /**
     * The alerts affecting a set of line GROUPS, with their text.
     *
     * Two calls, because that is what the server offers: LineGroupsServiceAlerts
     * (MVGetServiceAlertsByLinesRequest, field 1 = lineGroupIds) answers with ids per
     * line, and ServiceAlertsById (field 1 = alertIds) answers with the details. A
     * plan leg carries only MVServiceStatus, a category and a label, so the body a
     * rider wants to read has to be fetched.
     */
    @Suppress("UNCHECKED_CAST")
    fun serviceAlerts(s: MoovitSession, groupIds: List<Int>): List<ServiceAlert> {
        val groups = groupIds.filter { it > 0 }.distinct()
        if (groups.isEmpty()) return emptyList()
        val digestBody = TWriter().apply { i32ListField(1, groups); stop() }.bytes()
        val (digestCode, digestRaw) =
            post(APP5, "V4/ServiceAlert/LineGroupsServiceAlerts", digestBody, authHeaders(s))
        if (digestCode != 200) throw java.io.IOException("LineGroupsServiceAlerts HTTP $digestCode")
        val digests = (TReader(digestRaw).readStruct()[1] as? List<*>).orEmpty()
        val ids = LinkedHashSet<String>()
        val labels = LinkedHashMap<String, Pair<Int, String>>()
        for (d in digests) {
            val line = d as? Map<Int, Any?> ?: continue
            val status = line[2] as? Map<Int, Any?>
            val category = (status?.get(1) as? Int) ?: 0
            val label = (status?.get(2) as? String).orEmpty()
            for (id in (line[1] as? List<*>).orEmpty()) {
                val key = id as? String ?: continue
                ids.add(key)
                labels[key] = category to label
            }
        }
        if (ids.isEmpty()) return emptyList()
        val detailBody = TWriter().apply {
            listField(1, TType.STRING, ids.toList()) { w, v -> w.str(v) }
            stop()
        }.bytes()
        val (code, raw) = post(APP5, "V4/ServiceAlert/ServiceAlertsById", detailBody, authHeaders(s))
        if (code != 200) throw java.io.IOException("ServiceAlertsById HTTP $code")
        return (TReader(raw).readStruct()[1] as? List<*>).orEmpty().mapNotNull { entry ->
            val a = entry as? Map<Int, Any?> ?: return@mapNotNull null
            val id = (a[1] as? String).orEmpty()
            val status = a[3] as? Map<Int, Any?>
            val fallback = labels[id]
            ServiceAlert(
                id = id,
                category = (status?.get(1) as? Int) ?: fallback?.first ?: 0,
                label = (status?.get(2) as? String)?.ifBlank { null } ?: fallback?.second.orEmpty(),
                title = (a[8] as? String).orEmpty(),
                body = ((a[9] as? Map<Int, Any?>)?.get(1) as? String).orEmpty(),
                html = (a[9] as? Map<Int, Any?>)?.get(2) == 1,
                activeFrom = ((a[6] as? Long) ?: 0L) / 1000,
                activeTo = ((a[7] as? Long) ?: 0L) / 1000,
                url = (a[10] as? String).orEmpty(),
            )
        }
    }

    /** Live arrivals keyed by boarding stop AND trip: one vehicle has a different
     *  ETA at every stop. Poll no faster than the
     *  interval the server returns (~20 s). */
    @Suppress("UNCHECKED_CAST")
    fun stopArrivals(s: MoovitSession, stopIds: List<Int>): Pair<Map<ArrivalKey, Arrival>, Int> {
        if (stopIds.isEmpty()) return emptyMap<ArrivalKey, Arrival>() to 20
        val body = TWriter().apply {
            i32ListField(1, stopIds)
            structField(2, arrivalsConf())
            stop()
        }.bytes()
        val (code, raw) = post(APP5, "V4/StopsArrivals", body, authHeaders(s))
        return parseStopArrivals(code, raw)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseStopArrivals(code: Int, raw: ByteArray): Pair<Map<ArrivalKey, Arrival>, Int> {
        if (code != 200) throw java.io.IOException("StopsArrivals HTTP $code")
        val out = LinkedHashMap<ArrivalKey, Arrival>()
        var poll = 20
        val rd = TReader(raw)
        // one MVStopArrivalsResponse per requested stop, concatenated
        while (rd.hasMore()) {
            val resp = rd.readStruct()
            if (resp.isEmpty()) break
            val stopId = (resp[1] as? Int) ?: -1
            (resp[5] as? Int)?.let { poll = it }
            val lineArrivals = resp[3] as? List<Any?> ?: continue
            for (la in lineArrivals) {
                val lm = la as? Map<Int, Any?> ?: continue
                val lineId = (lm[1] as? Int) ?: -1
                val arrivals = lm[2] as? List<Any?> ?: continue
                for (a in arrivals) {
                    val am = a as? Map<Int, Any?> ?: continue
                    val tripId = (am[2] as? Long) ?: continue
                    val v = am[11] as? Map<Int, Any?>
                    val ll = v?.get(1) as? Map<Int, Any?>
                    val prog = v?.get(2) as? Map<Int, Any?>
                    out[ArrivalKey(stopId, tripId)] = Arrival(
                        stopId = stopId, lineId = lineId, tripId = tripId,
                        staticUtc = ((am[3] as? Long) ?: 0L) / 1000,
                        rtUtc = ((am[4] as? Long) ?: 0L) / 1000,
                        statisticalUtc = ((am[17] as? Long) ?: 0L) / 1000,
                        status = (am[5] as? Int) ?: 0,
                        certainty = (am[18] as? Int) ?: 0,
                        traffic = (am[19] as? Int) ?: 0,
                        frequency = am[9] != null,
                        rtDropped = am[4] == null && am[20] == true,
                        tracked = v != null,
                        lat = ((ll?.get(1) as? Int) ?: 0) / 1e6,
                        lon = ((ll?.get(2) as? Int) ?: 0) / 1e6,
                        vehicleId = (v?.get(3) as? String).orEmpty(),
                        sampleUtc = ((v?.get(4) as? Long) ?: 0L) / 1000,
                        vehicleStatus = (v?.get(5) as? Int) ?: 0,
                        nextStopIndex = (prog?.get(1) as? Int) ?: -1,
                        stopIndex = (am[12] as? Int) ?: -1,
                        patternStops = (am[13] as? Int) ?: -1,
                        tripShapeId = (am[15] as? Int) ?: -1,
                        patternId = (am[1] as? Int) ?: -1,
                    )
                }
            }
        }
        return out to poll
    }

    // live vehicles
    /** includeShapeSegments also gates MVArrival.tripShapeId: without it, GPS can
     *  arrive with no route id at all. Only the vehicle-only overview opts out. */
    internal fun arrivalsConf(includeShapes: Boolean = true) = TWriter()
        .boolField(2, false).boolField(3, false).boolField(4, true).boolField(5, includeShapes).boolField(6, false)

    /**
     * Real-time vehicle positions for the given Moovit stop ids (poll no faster than
     * the returned interval, ~20 s). Returns positions and nextPollingIntervalSecs.
     */
    fun liveVehicles(s: MoovitSession, stopIds: List<Int>): Pair<List<LiveVehicle>, Int> {
        val body = TWriter().apply {
            i32ListField(1, stopIds)
            structField(2, arrivalsConf(includeShapes = false))
            stop()
        }.bytes()
        val (code, raw) = post(APP5, "V4/StopsArrivals", body, authHeaders(s))
        if (code != 200) throw RuntimeException("StopsArrivals HTTP $code")
        val resp = TReader(raw).readStruct()
        val out = ArrayList<LiveVehicle>()
        @Suppress("UNCHECKED_CAST")
        val lineArrivals = resp[3] as? List<Map<Int, Any?>> ?: emptyList()
        for (la in lineArrivals) {
            val lineId = (la[1] as? Int) ?: -1
            @Suppress("UNCHECKED_CAST")
            val arrivals = la[2] as? List<Map<Int, Any?>> ?: continue
            for (arr in arrivals) {
                @Suppress("UNCHECKED_CAST")
                val vloc = arr[11] as? Map<Int, Any?> ?: continue
                @Suppress("UNCHECKED_CAST")
                val ll = vloc[1] as? Map<Int, Any?> ?: continue
                out.add(LiveVehicle(
                    lineId = lineId,
                    tripId = (arr[2] as? Long) ?: 0L,
                    vehicleId = (vloc[3] as? String) ?: "",
                    lat = ((ll[1] as? Int) ?: 0) / 1e6,
                    lon = ((ll[2] as? Int) ?: 0) / 1e6,
                    sampleUtc = ((vloc[4] as? Long) ?: 0L) / 1000,
                    etaUtc = (((arr[4] as? Long) ?: (arr[3] as? Long) ?: 0L)) / 1000,
                    status = (vloc[5] as? Int) ?: 0,
                ))
            }
        }
        val poll = (resp[5] as? Int) ?: (resp[6] as? Int) ?: 20
        return out to poll
    }

    // stop database (V5/Entities/EntitiesPage, entity_type=3)
    // Moovit ships this as offline data; we page it and cache. Each entity is a
    // stop with id + latlon. Paging cursor = max id seen + 1 (start from a seed).
    private const val APP4CDN = "https://app4cdn.moovitapp.com/services-app/services/"

    private fun get(base: String, path: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        repeat(2) { attempt ->
            // the query string carries the revision too, so it has to be rewritten
            val url = path.replace(Regex("metro_revision=\\d+"), "metro_revision=$metroRev")
            val c = (URL(base + url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 25000
                for ((k, v) in withRev(headers)) setRequestProperty(k, v)
            }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { st ->
                val b = st.readBytes(); if (c.contentEncoding == "gzip") GZIPInputStream(b.inputStream()).readBytes() else b
            } ?: ByteArray(0)
            if (attempt == 0 && adoptRevision(c, code)) return@repeat
            return code to raw
        }
        return 412 to ByteArray(0)
    }

    class Stop(val id: Int, val lat: Double, val lon: Double, val name: String)

    @Suppress("UNCHECKED_CAST")
    private fun extractStops(tree: Map<Int, Any?>, out: ArrayList<Stop>) {
        for (v in tree.values) {
            if (v is Map<*, *>) {
                val m = v as Map<Int, Any?>
                val la = m[1] as? Int; val lo = m[2] as? Int
                if (la != null && lo != null && la in 29_000_000..34_000_000 && lo in 33_000_000..36_000_000) {
                    val id = tree.values.firstOrNull { it is Int && it in 100..50_000_000 } as? Int
                    val nm = tree.values.firstOrNull { it is String && it.isNotEmpty() } as? String ?: ""
                    if (id != null) out.add(Stop(id, la / 1e6, lo / 1e6, nm))
                }
                extractStops(m, out)
            } else if (v is List<*>) {
                for (e in v) if (e is Map<*, *>) extractStops(e as Map<Int, Any?>, out)
            }
        }
    }

    /** Page the metro stop DB from [seedId]. Returns unique stops; cache the result. */
    fun stopDatabase(s: MoovitSession, seedId: Int = 1500, maxPages: Int = 40): List<Stop> {
        val h = authHeaders(s)
        val out = ArrayList<Stop>(); val seen = HashSet<Int>(); var frm = seedId
        repeat(maxPages) {
            val qs = "V5/Entities/EntitiesPage?entities_count=100&entity_type=3&from_entity_id=$frm" +
                "&metro_area_id=${s.metroId}&metro_revision=$metroRev&protocol_version=1&resolve_references=false"
            val (code, raw) = try { get(APP4CDN, qs, h) } catch (e: Exception) { return out }
            if (code != 200) return out
            val page = ArrayList<Stop>(); extractStops(TReader(raw).readStruct(), page)
            val fresh = page.filter { seen.add(it.id) }
            if (fresh.isEmpty()) return out
            out.addAll(fresh); frm = fresh.maxOf { it.id } + 1
        }
        return out
    }

    fun nearbyStops(stops: List<Stop>, lat: Double, lon: Double, k: Int = 15): List<Stop> {
        fun d(s: Stop) = Math.hypot((s.lat - lat) * 111, (s.lon - lon) * 93)
        return stops.sortedBy { d(it) }.take(k)
    }

    // entity lookups (V5/Entities/Entity)
    // A trip plan only ever carries Moovit's INTERNAL line ids (6087158), never the
    // number painted on the bus. The number lives in the line's group, which the app
    // syncs for offline use, and a single one of those entities is a plain GET.
    // MVSyncEntityType: 3 = stop, 4 = line summaries, 10 = metro area data.
    // (EntitiesPage only serves type 3; every other type is 400 there.)

    class LineInfo(
        val groupId: Int,
        val number: String,
        val agencyId: Int,
        val origin: String,
        val destination: String,
        val caption: String,
        /** The line group's own brand colour as ARGB, or 0 when the group has none.
         *  MVLineGroupSummary field 4, the exact value Moovit paints under the number
         *  on its badge (bus 11 in Rosh HaAyin: -8406771 = #7FB90D). Read from the
         *  server, never guessed here. */
        val color: Int = 0,
    )

    class StopInfo(
        val id: Int,
        val name: String,
        val code: String,
        val lat: Double = Double.NaN,
        val lon: Double = Double.NaN,
    ) {
        val point: Pair<Double, Double>? get() = validPoint(lat, lon)
    }

    private fun validPoint(lat: Double, lon: Double): Pair<Double, Double>? =
        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null

    private val lineCache = java.util.concurrent.ConcurrentHashMap<Int, LineInfo>()
    private val stopCache = java.util.concurrent.ConcurrentHashMap<Int, StopInfo>()
    private val agencyMode = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    @Suppress("UNCHECKED_CAST")
    private fun entity(s: MoovitSession, type: Int, id: Int): Map<Int, Any?>? {
        val qs = "V5/Entities/Entity?entity_type=$type&entity_id=$id&metro_area_id=${s.metroId}" +
            "&metro_revision=$metroRev&protocol_version=1&resolve_references=false"
        val (code, raw) = try { get(APP4CDN, qs, authHeaders(s)) } catch (e: Exception) { return null }
        if (code != 200 || raw.size < 8) return null
        val list = try { TReader(raw).readStruct()[1] as? List<Any?> } catch (e: Exception) { null } ?: return null
        val first = list.firstOrNull() as? Map<Int, Any?> ?: return null
        return first[1] as? Map<Int, Any?>          // MVSyncedEntity union
    }

    /** lineId → the group it belongs to: the number on the bus, its agency, its ends. */
    @Suppress("UNCHECKED_CAST")
    fun lineInfo(s: MoovitSession, lineId: Int): LineInfo? {
        lineCache[lineId]?.let { return it }
        val g = entity(s, 4, lineId)?.get(8) as? Map<Int, Any?> ?: return null   // lineGroupSummary
        val summaries = g[6] as? List<Any?> ?: emptyList<Any?>()
        val mine = summaries.mapNotNull { it as? Map<Int, Any?> }.firstOrNull { it[1] == lineId }
        val info = LineInfo(
            groupId = (g[1] as? Int) ?: -1,
            number = (g[2] as? String).orEmpty(),
            agencyId = (g[3] as? Int) ?: -1,
            origin = (mine?.get(2) as? String).orEmpty(),
            destination = (mine?.get(3) as? String).orEmpty(),
            caption = (g[9] as? String) ?: (g[8] as? String).orEmpty(),
            color = (g[4] as? Int) ?: 0,
        )
        // Every line in the group came back in the same call; keep them all.
        for (e in summaries) {
            val m = e as? Map<Int, Any?> ?: continue
            val lid = m[1] as? Int ?: continue
            lineCache[lid] = LineInfo(
                info.groupId, info.number, info.agencyId,
                (m[2] as? String).orEmpty(), (m[3] as? String).orEmpty(), info.caption,
                info.color,
            )
        }
        lineCache[lineId] = info
        return info
    }

    private val shapeCache = java.util.concurrent.ConcurrentHashMap<Int, List<Pair<Double, Double>>>()

    /**
     * The full route a vehicle is driving, by MVArrival.tripShapeId. This is what makes
     * it possible to draw the part of the line that happens BEFORE your stop, the trip
     * plan only ever carries your own leg.
     */
    fun tripShape(s: MoovitSession, shapeId: Int): List<Pair<Double, Double>> {
        if (shapeId <= 0) return emptyList()
        shapeCache[shapeId]?.let { return it }
        val e = entity(s, 15, shapeId) ?: return emptyList()
        val pts = tripShapeOf(shapeId, e)
        if (pts.isNotEmpty()) shapeCache[shapeId] = pts
        return pts
    }

    /** sync.thrift: type 15 / union 11; gtfs.thrift: MVTripShape.2 encodedShape. */
    @Suppress("UNCHECKED_CAST")
    internal fun tripShapeOf(shapeId: Int, e: Map<Int, Any?>): List<Pair<Double, Double>> {
        val rec = e[11] as? Map<Int, Any?> ?: return emptyList()
        if (rec[1] != shapeId) return emptyList()
        val pts = decodePolyline(rec[2] as? String)
        return pts.takeIf { it.size >= 2 && it.all { p -> validPoint(p.first, p.second) != null } } ?: emptyList()
    }

    /** Cached only, safe from a composable without touching the network. */
    fun cachedShape(shapeId: Int): List<Pair<Double, Double>> = shapeCache[shapeId] ?: emptyList()

    /** stopId → its name, pole code and actual stop coordinates. */
    fun stopInfo(s: MoovitSession, stopId: Int): StopInfo? {
        stopCache[stopId]?.let { return it }
        val info = entity(s, 3, stopId)?.let { stopInfoOf(stopId, it) } ?: return null
        stopCache[stopId] = info
        return info
    }

    /** MVStopMetaData.3 is MVLatLon with integer microdegrees (gtfs/common.thrift). */
    @Suppress("UNCHECKED_CAST")
    internal fun stopInfoOf(stopId: Int, e: Map<Int, Any?>): StopInfo? {
        val st = e[5] as? Map<Int, Any?> ?: return null
        val name = st[2] as? String ?: return null
        val ll = st[3] as? Map<Int, Any?>
        return StopInfo(
            stopId, name, (st[4] as? String).orEmpty(),
            (ll?.get(1) as? Int)?.div(1e6) ?: Double.NaN,
            (ll?.get(2) as? Int)?.div(1e6) ?: Double.NaN,
        )
    }

    /**
     * agencyId → GTFS route type, read once from the metro's own agency list, so a
     * badge can show a train rather than a bus without guessing from the name.
     */
    @Suppress("UNCHECKED_CAST")
    fun agencyRouteType(s: MoovitSession, agencyId: Int): Int {
        agencyMode[agencyId]?.let { return it }
        val metro = entity(s, 10, s.metroId)?.get(4) as? Map<Int, Any?> ?: return 3
        val agencies = metro[3] as? List<Any?> ?: return 3
        for (a in agencies) {
            val m = a as? Map<Int, Any?> ?: continue
            val id = m[1] as? Int ?: continue
            agencyMode[id] = (m[3] as? Int) ?: 3
        }
        return agencyMode[agencyId] ?: 3
    }

    // place search (V4/CloudSearch/FullSearch)
    // Moovit's OWN search, not a third-party geocoder: the same stations, streets and
    // sites the official app lists, in the order it lists them, each with the straight
    // -line distance it prints. Kav used Photon (OSM) before this; Photon returned a
    // different set with duplicates ("Dizengoff" seven times), and once the app is
    // already asking Moovit to plan the trip there is nothing left to keep independent.
    // MVSearchResultType: 1 stop, 2 street, 3 site, 4 city, 5 geocode.

    class Place(
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        val type: Int = 5,
        /** straight-line metres from where you searched; -1 when the reply omits it. */
        val meters: Int = -1,
    )

    private fun searchBody(query: String, lat: Double, lon: Double, metroId: Int): ByteArray =
        TWriter().apply {
            strField(1, query)
            i32Field(2, metroId)
            structField(3, latlon(lat, lon))
            i16Field(4, 0)                                   // startIndex
            boolField(5, true)                               // useGeoCoder
            boolField(7, true)                               // returnSortingInfo
            i32ListField(8, listOf(1, 2, 3, 4, 5))           // every result type
            structField(9, locale())
            boolField(10, true)                              // dontRemovePOI
            stop()
        }.bytes()

    /**
     * Search places.
     *
     * A note on ORDER, because this deliberately differs from the official app. Moovit
     * trusts the server's ranking here, but that ranking is relevance-only, not
     * distance-aware: searching "הדרור 30" from Rosh HaAyin returns an address in Yavne
     * 31 km away FIRST and the match 747 m from you LAST. Moovit gets away with it
     * because numbered addresses in its app do not come from this endpoint at all, they
     * come from Google Places Autocomplete, called straight from the device with a key
     * restricted to com.tranzmate's signing certificate. That path cannot be replicated
     * from another package, so this one has to be good enough on its own, and burying
     * the nearest match is not good enough.
     *
     * `useGeoCoder` is not the lever: true and false return byte-identical results.
     */
    fun searchPlaces(s: MoovitSession, query: String, lat: Double, lon: Double): List<Place> {
        if (query.isBlank()) return emptyList()
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val (code, raw) = post(APP5, "V4/CloudSearch/FullSearch", searchBody(query, lat, lon, s.metroId), h)
        if (code != 200) throw RuntimeException("Search HTTP $code")
        val root = JSONObject(String(raw, Charsets.UTF_8))
        val out = ArrayList<Place>()
        forEachItem(jList(root, "2")) { item ->
            val title = jStr(item, "4") ?: return@forEachItem
            val ll = jRec(item, "6") ?: return@forEachItem
            val subs = ArrayList<String>()
            forEachItem(jList(item, "5")) { t -> jStr(t, "1")?.let { subs.add(it) } }
            out.add(Place(
                name = title,
                detail = subs.joinToString(", "),
                lat = ((jInt(ll, "1") ?: 0L) / 1e6),
                lon = ((jInt(ll, "2") ?: 0L) / 1e6),
                type = (jInt(item, "1") ?: 5L).toInt(),
                meters = (jInt(item, "11") ?: -1L).toInt(),
            ))
        }
        // nearest first, keeping the server's relative order among equals, a transit
        // search is nearly always about somewhere you can actually get to
        return out.sortedBy { if (it.meters >= 0) it.meters else Int.MAX_VALUE }
    }

    // trip planning
    // TripPlanner2/Search returns a STREAM of concatenated JSON objects, one per
    // itinerary. MVTripPlanLeg is a union and the field id IS the leg kind:
    //   1 walk   2 waitToLine   3 line   4 waitToTaxi   5 taxi   6 multiLine
    //   7 carpool   8 pathwayWalk   9 waitToMultiLine   10 lineWithAlternatives
    //   11 bicycle   12 bicycleRental   13 event   14 parking   15 dockless   16 car
    // A bus ride is normally the PAIR 9 (wait at the stop) + 10 (the ride itself):
    // reading 9 as the ride is what produced 0-minute itineraries.

    enum class LegKind { WALK, WAIT, RIDE, TAXI, BIKE, OTHER }

    /**
     * How a departure time should be presented, a direct port of Moovit's own
     * `TimePresentationType`, which is the single thing that decides a time's colour
     * and its little leading mark. Its selection order lives in
     * `com.moovit.util.time.f.a(Time)` and is reproduced verbatim in [Departure.state];
     * the colours each one carries are in [uk.noammm.kav.ui.depColour].
     */
    enum class TimeState { STATIC, STATISTICAL, REAL_TIME, REAL_TIME_HIGH, REAL_TIME_MEDIUM, REAL_TIME_LOW, REAL_TIME_DROPPED, CANCELED, OUT_OF_SHAPE, FREQUENCY }

    /**
     * One upcoming departure, carrying the three candidate times Moovit's own `Time`
     * carries plus the flags that classify them (linearrivals.thrift MVArrival):
     *   3 staticEtdUTC   4 rtEtdUTC   17 statisticalEtdUTC
     *   5 status         MVArrivalStatus2   1 ON_TIME 2 DELAYED 3 CANCELLED 4 AHEAD
     *   18 certainty     MVArrivalCertainty 1 HIGH    2 MEDIUM  3 LOW
     *   19 trafficStatus MVTrafficStatus    1 NONE    2 MEDIUM  3 HEAVY
     *   9 frequencyId    11 vehicleLocation.vehicleStatus   20 rtDropInMetro
     * Which of the three times is SHOWN is Moovit's `Time.f()`: the tracked one if
     * there is one, else the statistical estimate, else the timetable.
     */
    data class Departure(
        val tripId: Long,
        /** MVArrival.staticEtdUTC, the timetable, always present. */
        val staticUtc: Long,
        /** MVArrival.rtEtdUTC, a tracked vehicle's estimate, 0 when absent. */
        val rtUtc: Long = 0,
        /** MVArrival.statisticalEtdUTC, learned from past runs, 0 when absent. */
        val statisticalUtc: Long = 0,
        val status: Int = 0,
        val certainty: Int = 0,
        val traffic: Int = 0,
        /** MVArrival.frequencyId is set: a "every N minutes" service, not a timetable. */
        val frequency: Boolean = false,
        /** MVArrival.rtDropInMetro with no rtEtdUTC: tracking exists here but was lost. */
        val rtDropped: Boolean = false,
        /** MVVehicleStatus: 1 ON_SHAPE, 2 OUT_OF_SHAPE, 3 TRIP_NOT_STARTED. */
        val vehicleStatus: Int = 0,
        /** MVServiceStatusCategory on this line/stop: 3 Modified, 4 Critical. */
        val alert: Int = 0,
    ) {
        /** Moovit's `Time.f()`, the time actually shown. */
        val timeUtc get() = if (rtUtc > 0) rtUtc else if (statisticalUtc > 0) statisticalUtc else staticUtc

        /**
         * Moovit's `com.moovit.util.time.f.a(Time)`, condition for condition and in its
         * order. Note what is NOT here: a DELAYED status, heavy traffic and an
         * AHEAD_OF_TIME status never change a time's colour, traffic only swaps the
         * leading mark for a delay glyph, and the status is spelled out in words.
         *
         * The one addition is TRIP_NOT_STARTED, and it is Moovit's rule too: `f.a` never
         * sees it because every presentation site tests the vehicle status first and
         * skips the colour table entirely. `m5e.c` draws
         * mvf_clock_solid_16_surface_inverse_emphasis_medium, a grey clock, instead of
         * the live-tinted marker, and StopArrivalsActivity$StopArrivalsMetadataType
         * .isNotDepartYetState raises the DID_NOT_DEPART_YET row while suppressing the
         * live sample time. A train sitting at its origin is not being tracked along
         * this trip yet, whatever estimate the server publishes for it.
         */
        val state: TimeState get() = when {
            status == 3 -> TimeState.CANCELED
            frequency -> TimeState.FREQUENCY
            vehicleStatus == 2 -> TimeState.OUT_OF_SHAPE
            rtUtc > 0 && vehicleStatus != 3 -> when (certainty) {
                1 -> TimeState.REAL_TIME_HIGH
                2 -> TimeState.REAL_TIME_MEDIUM
                3 -> TimeState.REAL_TIME_LOW
                else -> TimeState.REAL_TIME
            }
            rtDropped -> TimeState.REAL_TIME_DROPPED
            statisticalUtc > 0 -> TimeState.STATISTICAL
            else -> TimeState.STATIC
        }

        /** A vehicle is being tracked right now, the animated signal mark. */
        val live get() = state == TimeState.REAL_TIME || state == TimeState.REAL_TIME_HIGH ||
            state == TimeState.REAL_TIME_MEDIUM || state == TimeState.REAL_TIME_LOW
        /** MVTrafficStatus 2 MEDIUM or 3 HEAVY: the mark becomes a delay glyph. */
        val delayed get() = traffic == 2 || traffic == 3
    }

    data class Leg(
        val kind: LegKind,
        val lineId: Int = -1,
        val tripId: Long = 0,
        val dep: Long = 0,
        val arr: Long = 0,
        val stops: List<Int> = emptyList(),
        val fromStop: Int = -1,
        val toStop: Int = -1,
        val meters: Int = 0,
        /** Selected MVTime departure followed by any future MVArrival departures. */
        val nextDeps: List<Departure> = emptyList(),
        /** rail: the train number, which is what its badge shows instead of a line
         *  number. Buses leave it empty and fall back to the line group's number. */
        val shortName: String = "",
        /** MVPathwayWalkLeg: the walk INSIDE a station, between a platform and the
         *  street or another platform. Moovit leaves it out of the little route strip
         *  on a result card, "Gett › 655 › walk", never "Gett › walk › 655 › walk",
         *  though it still belongs on the map and in the step-by-step. */
        val pathway: Boolean = false,
        /** this leg's own fare in minor units, -1 when the plan gives none. */
        val fare: Int = -1,
        val currency: String = "",
        /** the leg's real geometry, decoded from the plan's encoded polyline. */
        val shape: List<Pair<Double, Double>> = emptyList(),
        /** MVServiceStatusCategory: 3 Modified, 4 Critical. 0 = nothing wrong. */
        val alertCategory: Int = 0,
        /** the server's own words for it, "Detour", "Modified Service". */
        val alertText: String = "",
        /** MVTaxiLeg.journey endpoints, independent of the full itinerary's ends. */
        val taxiPickup: Pair<Double, Double>? = null,
        val taxiDropoff: Pair<Double, Double>? = null,
        /** Full alternatives in server order; this leg's fields remain the primary. */
        val alternatives: List<Leg> = emptyList(),
        /** Legacy MVMultiLineLeg supplies only ids, not other rides' trip/geometry. */
        val alternativeLineIds: List<Int> = emptyList(),
    ) {
        val options get() = alternatives.ifEmpty { listOf(this) }
        val lineChoices get() = (options.map { it.lineId } + alternativeLineIds).distinct()
        val minutes get() = (((arr - dep) / 60).toInt()).coerceAtLeast(0)
    }

    class Itinerary(
        val guid: String,
        val group: Int,
        val legs: List<Leg>,
        val dep: Long,
        val arr: Long,
        /** minor units (agorot); -1 when the plan carries no fare. */
        val fare: Int = -1,
        val currency: String = "",
        /** grams of CO2e for this itinerary; -1 when absent. */
        val co2g: Int = -1,
        val accessible: Boolean = false,
        val tags: List<String> = emptyList(),
        /** the heading Moovit files this itinerary under, e.g. "Taxi & Ride Hailing". */
        val section: String = "",
        /** MVTripPlanItinerary.sectionId, the key into the server's section table. */
        val sectionId: Int = -1,
    ) {
        val durationMin get() = ((arr - dep) / 60).toInt()
        val rides get() = legs.filter { it.kind == LegKind.RIDE }
        val lineIds get() = rides.flatMap { it.lineChoices }
        val transfers get() = (rides.size - 1).coerceAtLeast(0)
    }

    private fun jInt(o: JSONObject, k: String): Long? {
        val n = o.optJSONObject(k) ?: return null
        return when {
            n.has("i64") -> n.getLong("i64"); n.has("i32") -> n.getLong("i32")
            n.has("i16") -> n.getLong("i16"); n.has("i8") -> n.getLong("i8")
            n.has("byte") -> n.getLong("byte"); else -> null
        }
    }
    private fun jDbl(o: JSONObject, k: String): Double? = o.optJSONObject(k)?.optDouble("dbl")?.takeIf { !it.isNaN() }
    private fun jStr(o: JSONObject, k: String): String? = o.optJSONObject(k)?.optString("str")?.takeIf { it.isNotEmpty() }
    private fun jBool(o: JSONObject, k: String): Boolean = (o.optJSONObject(k)?.optInt("tf", 0) ?: 0) != 0
    private fun jRec(o: JSONObject, k: String): JSONObject? = o.optJSONObject(k)?.optJSONObject("rec")

    /** Thrift-JSON lists are ["<type>", <count>, v0, v1, …], values start at 2. */
    private fun jList(o: JSONObject, k: String): org.json.JSONArray? = o.optJSONObject(k)?.optJSONArray("lst")
    private inline fun forEachItem(arr: org.json.JSONArray?, f: (JSONObject) -> Unit) {
        if (arr == null) return
        for (i in 2 until arr.length()) arr.optJSONObject(i)?.let(f)
    }
    private fun intList(arr: org.json.JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 2 until arr.length()) {
            val v = arr.opt(i)
            if (v is Int) out.add(v) else if (v is Number) out.add(v.toInt())
        }
        return out
    }

    private fun withAlert(d: Departure, alert: Int) = Departure(
        d.tripId, d.staticUtc, d.rtUtc, d.statisticalUtc, d.status, d.certainty,
        d.traffic, d.frequency, d.rtDropped, d.vehicleStatus, alert,
    )

    /** MVArrival -> Departure. Keeps all three candidate times, because which one
     *  is shown and how it is coloured are Moovit's decision, not the parser's. */
    private fun departureOf(a: JSONObject): Departure? {
        val rt = jInt(a, "4")?.takeIf { it > 0 } ?: 0L
        val stat = jInt(a, "17")?.takeIf { it > 0 } ?: 0L
        val sched = jInt(a, "3")?.takeIf { it > 0 } ?: 0L
        if (rt == 0L && stat == 0L && sched == 0L) return null
        return Departure(
            tripId = jInt(a, "2") ?: 0L,
            staticUtc = sched / 1000,
            rtUtc = rt / 1000,
            statisticalUtc = stat / 1000,
            status = (jInt(a, "5") ?: 0L).toInt(),
            certainty = (jInt(a, "18") ?: 0L).toInt(),
            traffic = (jInt(a, "19") ?: 0L).toInt(),
            frequency = jInt(a, "9") != null,
            // MVArrival.rtDropInMetro only means anything when there is no rtEtdUTC:
            // the metro HAS live tracking, and this trip's has been lost.
            rtDropped = rt == 0L && jBool(a, "20"),
            vehicleStatus = (jRec(a, "11")?.let { v -> jInt(v, "5") } ?: 0L).toInt(),
        )
    }

    /**
     * Google's encoded-polyline algorithm, which is what MVTripPlanShape.polyline is.
     * Each coordinate is a zig-zag varint delta in 1e-5 degrees, five bits per byte
     * with 0x20 as the continuation flag and 63 added so it stays printable.
     */
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

    private fun timeOf(o: JSONObject): Pair<Long, Long> {
        val t = jRec(o, "1") ?: return 0L to 0L
        return (jInt(t, "1") ?: 0L) / 1000 to (jInt(t, "2") ?: 0L) / 1000
    }

    /**
     * The leg's own MVTime as a departure, Kav's fallback for when futureDepartures
     * does not list the trip the plan chose.
     *
     * It is deliberately NOT classified as tracked. Moovit builds a wait leg's
     * departure schedule from futureDepartures alone (xa7, the MVWaitToLineLeg
     * branch: `com.moovit.util.time.e.a(..., futureDepartures, ...)`); MVTime only
     * supplies the leg's start and end times. The server sets MVTime.isRealTime on
     * lines whose arrivals carry no rtEtdUTC at all, measured live, line 10844371
     * had isRealTime=1 with startTimeUtc == staticStartTimeUtc and statistical-only
     * arrivals, so trusting that flag painted a merely statistical time as tracked.
     * With no MVArrival behind it there is nothing to classify, so it reads as a
     * timetable entry, which is what Moovit shows.
     */
    private fun timeDeparture(time: JSONObject?, tripId: Long = 0, end: Boolean = false): Departure? {
        time ?: return null
        val shown = jInt(time, if (end) "2" else "1")?.takeIf { it > 0 } ?: return null
        return Departure(tripId, staticUtc = shown / 1000)
    }

    /** One MVLineLeg → a RIDE. */
    private fun rideOf(l: JSONObject): Leg {
        val (dep, arr) = timeOf(l)
        val stops = intList(jList(l, "3"))
        return Leg(
            LegKind.RIDE,
            lineId = (jInt(l, "2") ?: -1L).toInt(),
            tripId = jInt(l, "6") ?: 0L,
            dep = dep, arr = arr, stops = stops,
            nextDeps = listOfNotNull(timeDeparture(jRec(l, "1"), jInt(l, "6") ?: 0L)),
            fromStop = stops.firstOrNull() ?: -1,
            toStop = stops.lastOrNull() ?: -1,
            shortName = jStr(l, "8").orEmpty(),
            shape = decodePolyline(jRec(l, "4")?.let { jStr(it, "2") }),
            fare = (jRec(l, "5")?.let { jRec(it, "2") }?.let { jInt(it, "1") } ?: -1L).toInt(),
            currency = jRec(l, "5")?.let { jRec(it, "2") }?.let { jStr(it, "3") }.orEmpty(),
        )
    }

    private fun locationPoint(location: JSONObject?): Pair<Double, Double>? {
        val ll = location?.let { jRec(it, "3") } ?: return null
        val lat = jInt(ll, "1") ?: return null
        val lon = jInt(ll, "2") ?: return null
        return validPoint(lat / 1e6, lon / 1e6)
    }

    private fun waitOf(inner: JSONObject, multi: JSONObject? = null): Leg {
        val (dep, arr) = timeOf(inner)
        val service = jRec(inner, if (multi == null) "6" else "4")?.let { jRec(it, "2") }
        val alert = (service?.let { jInt(it, "1") } ?: 0L).toInt()
        val deps = ArrayList<Departure>()
        timeDeparture(jRec(inner, "1"), end = true)?.let { deps.add(it) }
        forEachItem(jRec(inner, if (multi == null) "5" else "3")?.let { jList(it, "2") }) {
            departureOf(it)?.let { d -> deps.add(d) }
        }
        return Leg(
            LegKind.WAIT, lineId = (jInt(inner, "2") ?: -1L).toInt(), dep = dep, arr = arr,
            fromStop = (jInt(multi ?: inner, if (multi == null) "3" else "2") ?: -1L).toInt(),
            toStop = (jInt(multi ?: inner, if (multi == null) "4" else "3") ?: -1L).toInt(),
            nextDeps = deps.map { if (alert == 0) it else withAlert(it, alert) },
            alertCategory = alert, alertText = service?.let { jStr(it, "2") }.orEmpty(),
        )
    }

    /** Pair by line identity: wait and ride primary indices need not be the same. */
    fun boardingOptions(ride: Leg, wait: Leg?): List<Pair<Leg, Leg?>> = ride.options.map { option ->
        option to wait?.options?.firstOrNull { it.lineId == option.lineId }
    }

    internal fun parseLeg(leg: JSONObject): Leg {
        val fid = leg.keys().asSequence().firstOrNull() ?: return Leg(LegKind.OTHER)
        val inner = jRec(leg, fid) ?: return Leg(LegKind.OTHER)
        val (dep, arr) = timeOf(inner)
        return when (fid.toIntOrNull()) {
            // MVWalkLeg: journey(2) carries the two endpoints, shape(3).1 the metres
            1 -> {
                val j = jRec(inner, "2")
                val to = j?.let { jRec(it, "2") }
                Leg(
                    LegKind.WALK, dep = dep, arr = arr,
                    fromStop = (j?.let { jRec(it, "1") }?.let { jInt(it, "2") } ?: -1L).toInt(),
                    toStop = (to?.let { jInt(it, "2") } ?: -1L).toInt(),
                    meters = (jRec(inner, "3")?.let { jDbl(it, "1") } ?: 0.0).toInt(),
                    shape = decodePolyline(jRec(inner, "3")?.let { jStr(it, "2") }),
                )
            }
            8 -> Leg(
                LegKind.WALK, dep = dep, arr = arr,
                toStop = (jInt(inner, "2") ?: -1L).toInt(), pathway = true,
            )
            2 -> waitOf(inner)
            // Each wait has its own selected MVTime, future list and service alert.
            9 -> {
                val options = ArrayList<Leg>()
                forEachItem(jList(inner, "5")) { a ->
                    options.add(waitOf(a, multi = inner))
                }
                val primary = (jInt(inner, "6") ?: 0L).toInt()
                (options.getOrNull(primary) ?: options.firstOrNull())?.copy(alternatives = options)
                    ?: Leg(LegKind.WAIT, dep = dep, arr = arr)
            }
            3 -> rideOf(inner)
            6 -> jRec(inner, "1")?.let {
                rideOf(it).copy(alternativeLineIds = intList(jList(inner, "2")))
            } ?: Leg(LegKind.OTHER, dep = dep, arr = arr)
            // Preserve every full ride, including its own trip id, stops and time.
            10 -> {
                val options = ArrayList<Leg>()
                forEachItem(jList(inner, "1")) { options.add(rideOf(it)) }
                val primary = (jInt(inner, "2") ?: 0L).toInt()
                (options.getOrNull(primary) ?: options.firstOrNull())?.copy(alternatives = options)
                    ?: Leg(LegKind.OTHER, dep = dep, arr = arr)
            }
            // MVTaxiLeg: journey(2), shape(3); each journey endpoint is an
            // MVLocationDescriptor whose field 3 contains MVLatLon microdegrees.
            5 -> {
                val journey = jRec(inner, "2")
                val shape = jRec(inner, "3")
                Leg(
                    LegKind.TAXI, dep = dep, arr = arr,
                    meters = (shape?.let { jDbl(it, "1") } ?: 0.0).toInt(),
                    shape = decodePolyline(shape?.let { jStr(it, "2") }),
                    taxiPickup = locationPoint(journey?.let { jRec(it, "1") }),
                    taxiDropoff = locationPoint(journey?.let { jRec(it, "2") }),
                )
            }
            11, 12 -> Leg(LegKind.BIKE, dep = dep, arr = arr)
            else -> Leg(LegKind.OTHER, dep = dep, arr = arr)
        }
    }

    private fun parseItinerary(it: JSONObject): Itinerary? {
        val legsArr = jList(it, "5") ?: return null
        val legs = ArrayList<Leg>()
        forEachItem(legsArr) { legs.add(parseLeg(it)) }
        if (legs.isEmpty()) return null

        val fareRec = jRec(it, "10")?.let { jRec(it, "1") }
        val emission = jRec(it, "13")
        val tags = ArrayList<String>()
        forEachItem(jList(it, "18")) { t -> jStr(t, "3")?.let { tags.add(it) } }

        val times = legs.flatMap { listOf(it.dep, it.arr) }.filter { it > 0 }
        return Itinerary(
            guid = jStr(it, "1").orEmpty(),
            group = (jInt(it, "3") ?: -1L).toInt(),
            legs = legs,
            dep = legs.firstOrNull { it.dep > 0 }?.dep ?: times.minOrNull() ?: 0,
            arr = legs.lastOrNull { it.arr > 0 }?.arr ?: times.maxOrNull() ?: 0,
            fare = (fareRec?.let { jInt(it, "1") } ?: -1L).toInt(),
            currency = fareRec?.let { jStr(it, "3") }.orEmpty(),
            co2g = (emission?.let { jInt(it, "1") } ?: -1L).toInt(),
            accessible = jBool(it, "9"),
            tags = tags,
            section = jStr(it, "14").orEmpty(),
            sectionId = (jInt(it, "2") ?: -1L).toInt(),
        )
    }

    /**
     * How the server wants its results laid out. This is NOT guesswork from the section
     * names on each itinerary: the stream carries a MVTripPlanSections object of its own
     * (union field 2 of MVTripPlanSectionedResponse) listing every section in DISPLAY
     * ORDER with its id, its heading and, the part that matters, how many results it
     * is allowed to show. A live reply looks like this:
     *
     *   idx  id    max  type  name
     *    0   1561    1    1   ''                                    (the taxi card)
     *    3   1       127  16  ''                                    (the suggested routes)
     *    5   1581    -    1   'Taxi & Ride Hailing'
     *    6   522     1    3   'Combined Transit & Personal Bike Routes'
     *    9   521     3    2   'Walking & Biking Routes'
     *
     * Each itinerary carries its sectionId in field 2. Bucket by that, order the buckets
     * by their position in this list, and cap each one at maxItemsToDisplay. Reading the
     * order off the itineraries instead gets close but shows, for instance, three bike
     * combinations where Moovit shows one.
     */
    class Section(
        val id: Int,
        val name: String,
        val maxItems: Int,
        val type: Int,
        val index: Int,
    )

    class Plan(
        val itineraries: List<Itinerary> = emptyList(),
        val sections: List<Section> = emptyList(),
    ) {
        private val byId = sections.associateBy { it.id }

        /** The itineraries in the order and quantity the server asked for. */
        fun laidOut(): List<Itinerary> {
            if (sections.isEmpty()) return itineraries
            val seen = HashMap<Int, Int>()
            return itineraries
                .sortedBy { byId[it.sectionId]?.index ?: Int.MAX_VALUE }
                .filter {
                    val cap = byId[it.sectionId]?.maxItems ?: Int.MAX_VALUE
                    val n = (seen[it.sectionId] ?: 0) + 1
                    seen[it.sectionId] = n
                    n <= cap
                }
        }

        fun heading(it: Itinerary): String = byId[it.sectionId]?.name.orEmpty()
    }

    /**
     * Plan a trip online. Returns itineraries in the order the server sent them, that
     * order IS Moovit's ranking, together with the section table that says how to lay
     * them out.
     */
    /** MVTimeType (tripplanner.thrift): what the time you gave actually means. */
    const val TIME_ARRIVAL = 1
    const val TIME_DEPARTURE = 2
    const val TIME_LAST = 3

    fun planItineraries(
        s: MoovitSession,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        /** epoch millis to leave at; 0 = now. Drives Moovit's Earlier / Later. */
        whenMs: Long = 0L,
        timeType: Int = TIME_DEPARTURE,
        /** MVRouteTypes the plan may use; a mode left out is planned around. */
        routeTypes: List<Int> = ALL_ROUTE_TYPES,
        skipTaxi: Boolean = false,
    ): Plan {
        val body = tripPlanRequest(from, to, whenMs, timeType, routeTypes, skipTaxi)
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val (code, raw) = post(APP5, "V4/TripPlanner2/Search", body, h)
        if (code != 200) throw RuntimeException("TripPlanner HTTP $code")
        val out = ArrayList<Itinerary>()
        var sections = emptyList<Section>()
        val tok = org.json.JSONTokener(String(raw, Charsets.UTF_8))
        while (tok.more()) {
            val v = try { tok.nextValue() } catch (e: Exception) { break }
            val obj = v as? JSONObject ?: continue
            // MVTripPlanSectionedResponse: 1 = itinerary, 2 = the section table
            obj.optJSONObject("1")?.optJSONObject("rec")?.let { rec ->
                parseItinerary(rec)?.let { out.add(it) }
            }
            obj.optJSONObject("2")?.optJSONObject("rec")?.let { rec ->
                val list = ArrayList<Section>()
                var idx = 0
                forEachItem(jList(rec, "1")) { sec ->
                    list.add(
                        Section(
                            id = (jInt(sec, "2") ?: -1L).toInt(),
                            name = jStr(sec, "1").orEmpty(),
                            maxItems = (jInt(sec, "3") ?: Int.MAX_VALUE.toLong()).toInt(),
                            type = (jInt(sec, "4") ?: 0L).toInt(),
                            index = idx++,
                        ),
                    )
                }
                sections = list
            }
        }
        return Plan(out, sections)
    }

    /**
     * Everything an itinerary needs that a trip plan does not carry. Handed to the UI
     * as one immutable value rather than left in the caches: a card that read a cache
     * directly would keep showing the raw line id, because nothing about a background
     * cache write tells Compose to draw the row again.
     */
    class Resolved(
        val lines: Map<Int, LineInfo> = emptyMap(),
        val stops: Map<Int, StopInfo> = emptyMap(),
        val routeTypes: Map<Int, Int> = emptyMap(),
        /** Arrivals at each boarding stop; the same trip can appear at several stops. */
        val live: Map<ArrivalKey, Arrival> = emptyMap(),
        /** whole-route geometry by tripShapeId, for the part before your stop. */
        val shapes: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
        /** seconds the server asked us to wait before polling live data again. */
        val pollSecs: Int = 20,
        /** Actual stop sequences for live arrival patterns, used to filter destinations. */
        val patterns: Map<Int, List<Int>> = emptyMap(),
    ) {
        fun arrival(ride: Leg): Arrival? = live[ArrivalKey(ride.fromStop, ride.tripId)]

        /** Selected trip plus later runs, with one entry per trip and per-stop live data. */
        fun departures(ride: Leg, wait: Leg?): List<Departure> {
            val boarding = wait?.options?.firstOrNull { it.lineId == ride.lineId }
            val future = boarding?.nextDeps.orEmpty().filter { it.tripId != 0L }
            // The arrival for the chosen trip beats the leg's own MVTime: only the
            // MVArrival carries rtEtdUTC, statisticalEtdUTC and certainty, and Moovit
            // classifies every departure it shows from exactly those fields.
            val selected = future.firstOrNull { it.tripId == ride.tripId }
                ?: ride.nextDeps.firstOrNull()
                ?: boarding?.nextDeps?.firstOrNull { it.tripId == 0L }?.copy(tripId = ride.tripId)
                ?: Departure(ride.tripId, ride.dep)
            val alert = boarding?.alertCategory ?: 0
            val planned = (listOf(selected.copy(alert = alert)) + future.filter { it.tripId != ride.tripId })
                .distinctBy { it.tripId to if (it.tripId == 0L) it.timeUtc else 0L }
            // Moovit's DefaultItineraryRealTimeRepository (com.moovit.data.tripplan
            // .realtime.c.b) keeps every live arrival at this leg's line + boarding
            // stop whose pattern contains the destination stop, sorts it, and does
            // nothing else. In particular there is no floor at the plan's own
            // departure anywhere in it, ab7.e is called with a null time at every
            // site, so z4.s only ever applies the destination-pattern test, and the
            // bus four minutes out was exactly the one Moovit showed and Kav hid.
            // A qualifying live schedule replaces the plan's outright; e.a keeps
            // statistical and realtime timestamps separate, so statistical data is
            // still never promoted to tracked. Trimming to now belongs to the
            // presentation layer, as in mb7's schedule.m(now) and departLabels here.
            val current = live.values.filter { a ->
                a.stopId == ride.fromStop && a.lineId == ride.lineId &&
                    patterns[a.patternId]?.contains(ride.toStop) == true
            }.map { it.departure(alert) }
            if (current.isNotEmpty()) return current.sortedBy { it.timeUtc }
            return planned.sortedBy { it.timeUtc }
        }

        fun line(id: Int): LineInfo? = lines[id]
        fun stop(id: Int): StopInfo? = stops[id]
        fun stopName(id: Int): String? = stops[id]?.name
        fun routeType(agencyId: Int): Int = routeTypes[agencyId] ?: 3


    }

    /**
     * Resolve every line and boarding stop an itinerary list mentions, once.
     *
     * IN PARALLEL, because each one is its own GET: a plan to Tel Aviv names half a
     * dozen lines and a dozen stops, and fetching them one after another left the
     * badges blank and the stop names missing for the best part of half a minute on a
     * mobile connection. Moovit does not have this problem, it syncs the metro's whole
     * entity database up front, so the nearest honest fix is to stop serialising.
     */
    fun hydrate(s: MoovitSession, list: List<Itinerary>): Resolved {
        val lineIds = LinkedHashSet<Int>()
        val stopIds = LinkedHashSet<Int>()
        for (i in list) for (leg in i.legs) for (l in leg.options) {
            if (l.kind == LegKind.RIDE) lineIds.addAll(l.lineChoices.filter { it > 0 })
            // both ends of a ride: the detail screen names where you get on AND off
            if (l.kind == LegKind.WAIT || l.kind == LegKind.RIDE) {
                if (l.fromStop > 0) stopIds.add(l.fromStop)
                if (l.toStop > 0) stopIds.add(l.toStop)
            }
        }
        val lines = LinkedHashMap<Int, LineInfo>()
        val stops = LinkedHashMap<Int, StopInfo>()
        val types = LinkedHashMap<Int, Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val lineJobs = lineIds.map { id -> id to pool.submit<LineInfo?> { lineInfo(s, id) } }
            val stopJobs = stopIds.map { id -> id to pool.submit<StopInfo?> { stopInfo(s, id) } }
            for ((id, f) in lineJobs) runCatching { f.get() }.getOrNull()?.let { lines[id] = it }
            for ((id, f) in stopJobs) runCatching { f.get() }.getOrNull()?.let { stops[id] = it }
            val agencies = lines.values.map { it.agencyId }.distinct()
            val typeJobs = agencies.map { a -> a to pool.submit<Int> { agencyRouteType(s, a) } }
            for ((a, f) in typeJobs) types[a] = runCatching { f.get() }.getOrNull() ?: 3
        } finally {
            pool.shutdown()
        }

        val boarding = list.flatMap { i -> i.legs.filter { it.kind == LegKind.WAIT || it.kind == LegKind.RIDE } }
            .flatMap { it.options }.map { it.fromStop }.filter { it > 0 }.distinct().take(40)
        val (live, poll) = try { stopArrivals(s, boarding) } catch (e: Exception) { emptyMap<ArrivalKey, Arrival>() to 20 }
        return Resolved(lines, stops, types, live, shapesFor(s, list, live), poll, patternsFor(s, list, live))
    }

    /** StopsArrivals includes unrelated lines too; only our planned rides need geometry. */
    internal fun trackedShapeIds(list: List<Itinerary>, live: Map<ArrivalKey, Arrival>): List<Int> =
        list.asSequence().flatMap { it.rides.asSequence() }.flatMap { it.options.asSequence() }
            .mapNotNull { live[ArrivalKey(it.fromStop, it.tripId)] }
            .filter { it.hasLocation && it.tripShapeId > 0 }
            .map { it.tripShapeId }.distinct().toList()

    /** Fetch each relevant route once, in parallel, and keep geometry across failed polls. */
    private fun shapesFor(
        s: MoovitSession,
        list: List<Itinerary>,
        live: Map<ArrivalKey, Arrival>,
        previous: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
    ): Map<Int, List<Pair<Double, Double>>> {
        val out = LinkedHashMap(previous.filterValues { it.size >= 2 })
        val missing = trackedShapeIds(list, live).filter { id ->
            cachedShape(id).takeIf { it.size >= 2 }?.let { out[id] = it }
            id !in out
        }
        if (missing.isEmpty()) return out
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, missing.size))
        try {
            val jobs = missing.map { id -> id to pool.submit<List<Pair<Double, Double>>> { tripShape(s, id) } }
            for ((id, job) in jobs) {
                runCatching { job.get() }.getOrNull()?.takeIf { it.size >= 2 }?.let { out[id] = it }
            }
        } finally {
            pool.shutdown()
        }
        return out
    }

    /** Only patterns of our line/boarding-stop pairs need to be resolved. */
    private fun patternsFor(
        s: MoovitSession,
        list: List<Itinerary>,
        live: Map<ArrivalKey, Arrival>,
        previous: Map<Int, List<Int>> = emptyMap(),
    ): Map<Int, List<Int>> {
        val requested = list.flatMap { it.rides }.flatMap { it.options }
            .map { it.lineId to it.fromStop }.toSet()
        val ids = live.values.filter { (it.lineId to it.stopId) in requested }
            .map { it.patternId }.filter { it > 0 }.distinct()
        val out = LinkedHashMap(previous)
        val missing = ids.filter { id ->
            patternCache[metroRev to id]?.let { out[id] = it }
            id !in out
        }
        if (missing.isEmpty()) return out
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, missing.size))
        try {
            val jobs = missing.map { id -> id to pool.submit<List<Int>> {
                val stops = entity(s, 13, id)?.let { tripPatternOf(id, it) }.orEmpty()
                if (stops.isNotEmpty()) patternCache[metroRev to id] = stops
                stops
            } }
            for ((id, job) in jobs) runCatching { job.get() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { out[id] = it }
        } finally {
            pool.shutdown()
        }
        return out
    }

    private val patternCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, List<Int>>()

    /** sync TripPattern=13, union=9; MVTripPattern.2 contains stop ids. */
    @Suppress("UNCHECKED_CAST")
    internal fun tripPatternOf(id: Int, entity: Map<Int, Any?>): List<Int> {
        val pattern = entity[9] as? Map<Int, Any?> ?: return emptyList()
        if (pattern[1] != id) return emptyList()
        return (pattern[2] as? List<*>)?.filterIsInstance<Int>().orEmpty()
    }

    /** Just the live layer, for a refresh that must not re-resolve names. */
    fun refreshLive(s: MoovitSession, list: List<Itinerary>, prev: Resolved): Resolved {
        val boarding = list.flatMap { i -> i.legs.filter { it.kind == LegKind.WAIT || it.kind == LegKind.RIDE } }
            .flatMap { it.options }.map { it.fromStop }.filter { it > 0 }.distinct().take(40)
        val (live, poll) = try { stopArrivals(s, boarding) } catch (e: Exception) { return prev }
        return Resolved(prev.lines, prev.stops, prev.routeTypes, live, shapesFor(s, list, live, prev.shapes), poll,
            patternsFor(s, list, live, prev.patterns))
    }

    /** Tram 0, Subway 1, Rail 2, Bus 3, Ferry 4, Cable 5, Gondola 6, Funicular 7. */
    val ALL_ROUTE_TYPES = listOf(0, 1, 2, 3, 4, 5, 6, 7)

    // MVTripPlanRequest with the full field set that yields transit results.
    private fun tripPlanRequest(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        whenMs: Long = 0L,
        timeType: Int = TIME_DEPARTURE,
        routeTypes: List<Int> = ALL_ROUTE_TYPES,
        skipTaxi: Boolean = false,
    ): ByteArray {
        fun locTarget(lat: Double, lon: Double, caption: String?, locType: Int, source: Int): TWriter {
            val inner = TWriter()
            if (caption != null) inner.strField(1, caption)
            inner.structField(3, latlon(lat, lon)); inner.i32Field(4, locType)
            return TWriter().structField(1, inner).i32Field(2, source)
        }
        return TWriter().apply {
            i32Field(1, 2)                                   // tripPlanPref = Fastest
            i64Field(2, if (whenMs > 0) whenMs else System.currentTimeMillis())
            // currentTimeSelected must be false once a time is chosen, or the server
            // plans from now regardless of the timestamp
            i32Field(3, timeType)
            // only a plain "leave now" is the current time: an arrival or a latest
            // departure is always about a time the rider named
            boolField(4, whenMs <= 0L && timeType == TIME_DEPARTURE)
            // Every MVRouteType by default. Asking for a subset is asking the server to
            // plan around whole modes, the list here used to omit Gondola, which is
            // what the Haifa Rakavlit is, so no plan could ever route over it. Now a
            // mode is left out only when the rider switched it off.
            i32ListField(5, routeTypes.ifEmpty { ALL_ROUTE_TYPES })  // routeTypes
            structField(6, locTarget(from.first, from.second, null, 9, 5))    // from: UserLocation
            structField(7, locTarget(to.first, to.second, "Destination", 1, 4))
            boolField(10, skipTaxi)                          // skipTaxiSearch
            i32ListField(13, listOf(5, 1, 2, 4))             // transportTypes
            boolField(15, true)                              // addFlexTimeSearch
            structField(16, TWriter().boolField(1, false).boolField(3, false))  // personalPreferences
            i32Field(17, 1)                                  // algorithmType = PREFERRED
            strField(18, "suggested_routes")                 // tripPlanInitiator
            stop()
        }.bytes()
    }
}
