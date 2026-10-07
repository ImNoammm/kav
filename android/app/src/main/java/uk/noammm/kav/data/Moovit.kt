package uk.noammm.kav.data

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

class MoovitSession(
    val userKey: String,
    val accessToken: String,
    val refreshToken: String,
    val metroId: Int,
    val accessExpiresUtc: Long,
)

object Moovit {
    const val APP_ID = "moovit_2751703405"
    const val CLIENT_VERSION = "5.201.5.1810"
    internal const val APP4 = "https://app4.moovitapp.com/services-app/services/"
    private const val APP5 = "https://app5.moovitapp.com/services-app/services/"

    @Volatile
    var shareLocation = true

    // What Moovit is told instead while private search is on: a city's centre or a chosen place.
    @Volatile var standIn: () -> Pair<Double, Double>? = { null }
    internal val NEUTRAL = 32.0755 to 34.7755
    @Volatile
    private var metroRev: String = "1788783184120"

    private const val REV_HEADER = "Metro-Revision-Number"

    private fun withRev(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals(REV_HEADER, ignoreCase = true) } + (REV_HEADER to metroRev)

    private fun adoptRevision(c: HttpURLConnection, code: Int): Boolean {
        if (code != 412) return false
        val fresh = c.getHeaderField(REV_HEADER)?.trim().orEmpty()
        if (fresh.isEmpty() || fresh == metroRev) return false
        metroRev = fresh
        return true
    }

    internal fun post(
        base: String, path: String, body: ByteArray, headers: Map<String, String>, readMs: Int = 25000,
        revision: Boolean = true,
    ): Pair<Int, ByteArray> {
        repeat(2) { attempt ->
            val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = readMs
                for ((k, v) in if (revision) withRev(headers) else headers) setRequestProperty(k, v)
            }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (!revision) c.getHeaderField(REV_HEADER)?.trim()?.takeIf { it.isNotEmpty() }?.let { metroRev = it }
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { s ->
                val bytes = s.readBytes()
                if (c.contentEncoding == "gzip") GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            } ?: ByteArray(0)
            if (attempt == 0 && (adoptRevision(c, code) || code == 412)) return@repeat
            return code to raw
        }
        return 412 to ByteArray(0)
    }

    private fun latlon(lat: Double, lon: Double) = TWriter()
        .i32Field(1, (lat * 1e6).toInt()).i32Field(2, (lon * 1e6).toInt())

    private fun locale() =
        if (uk.noammm.kav.ui.T.rtl) TWriter().strField(1, "he").strField(2, "IL").strField(3, "")
        else TWriter().strField(1, "en").strField(2, "GB").strField(3, "")
    private fun dpk() = TWriter().strField(1, "").strField(2, "").strField(3, "")

    private fun createUserBody(lat: Double, lon: Double): ByteArray = TWriter().apply {
        structField(1, latlon(lat, lon))
        structField(3, locale())
        // The phone's own model and Android version, as Moovit's app sends them. Moovit refused a
        // fixed value that every copy of Kav sent.
        strField(4, "${Build.MANUFACTURER} ${Build.PRODUCT}")
        strField(5, "${Build.VERSION.RELEASE}_${Build.VERSION.SDK_INT}"); i32Field(6, 2)
        structField(7, dpk())
        strField(8, ""); strField(9, ""); boolField(10, true)
        i32Field(11, 5); i64Field(12, System.currentTimeMillis()); i32Field(13, 1)
        strField(15, UUID.randomUUID().toString().replace("-", "").substring(0, 16))
        strField(16, APP_ID)
        strField(19, UUID.randomUUID().toString())
        strField(20, UUID.randomUUID().toString().replace("-", ""))
        strField(21, "com.tranzmate")
        // useNewBrazeWorkspace: Moovit's app sets it, and the payment SMS codes of a user made without it are refused.
        boolField(22, true)
        stop()
    }.bytes()

    // Exactly what Moovit's own app sends when it creates or renews a user; Moovit refuses values only Kav sends.
    private val userHeaders = mapOf(
        "Content-Type" to "application/octet", "Accept" to "application/octet,application/json,application/json",
        "Accept-Encoding" to "gzip;q=1.0,identity;q=0.5", "User-Agent" to "ktor-client",
        "api_key" to APP_ID, "client_version" to CLIENT_VERSION, "phone_type" to "2",
    )

    private fun sessionOf(userKey: String, metroId: Int, tokens: JSONObject): MoovitSession {
        val access = tokens.getJSONObject("1").getJSONObject("rec")
        val refresh = tokens.getJSONObject("2").getJSONObject("rec")
        return MoovitSession(
            userKey = userKey,
            accessToken = access.getJSONObject("3").getString("str"),
            accessExpiresUtc = access.getJSONObject("2").getLong("i64") / 1000,
            refreshToken = refresh.getJSONObject("3").getString("str"),
            metroId = metroId,
        )
    }

    fun register(lat: Double = NEUTRAL.first, lon: Double = NEUTRAL.second): MoovitSession {
        val (la, lo) = if (shareLocation) lat to lon else standIn() ?: NEUTRAL
        // Moovit's CDN refuses a new user asked for with a revision; the answer names the current one.
        val (code, raw) = post(APP4, "UserAuth/CreateUser", createUserBody(la, lo), userHeaders, revision = false)
        if (code != 200) throw RuntimeException("CreateUser HTTP $code")
        val rec = JSONObject(String(raw, Charsets.UTF_8)).getJSONObject("1").getJSONObject("rec")
        return sessionOf(
            rec.getJSONObject("1").getString("str"), rec.getJSONObject("3").getInt("i16"),
            rec.getJSONObject("7").getJSONObject("rec").getJSONObject("1").getJSONObject("rec"),
        )
    }

    // Another day of access for the same user, the way Moovit's app renews its own. The refresh
    // token lasts for years.
    fun renew(s: MoovitSession): MoovitSession {
        val body = TWriter().apply { strField(1, s.refreshToken); stop() }.bytes()
        val (code, raw) = post(APP4, "UserAuth/RefreshTokens", body, userHeaders, revision = false)
        if (code != 200) throw RuntimeException("RefreshTokens HTTP $code")
        return sessionOf(s.userKey, s.metroId, JSONObject(String(raw, Charsets.UTF_8)).getJSONObject("1").getJSONObject("rec"))
    }

    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private val agent = System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "Dalvik/2.1.0"

    // As the app's other requests carry them: the phone's own Dalvik agent and a count that goes up.
    internal fun authHeaders(s: MoovitSession) = mapOf(
        "Content-Type" to "application/octet", "Accept" to "application/octet",
        "Accept-Encoding" to "gzip;q=1.0, identity;q=0.5", "User-Agent" to agent,
        "api_key" to APP_ID, "client_version" to CLIENT_VERSION, "phone_type" to "2",
        "request-sequence-id" to sequence.incrementAndGet().toString(), "gtfs-language" to "",
        "user_key" to s.userKey, "access-token" to s.accessToken,
        "Metro-Revision-Metro-Id" to s.metroId.toString(), REV_HEADER to metroRev,
    )

    data class ArrivalKey(val stopId: Int, val tripId: Long)

    class Arrival(
        val stopId: Int,
        val lineId: Int,
        val tripId: Long,
        val staticUtc: Long,
        val rtUtc: Long,
        val statisticalUtc: Long,
        val status: Int,
        val certainty: Int,
        val traffic: Int,
        val frequency: Boolean,
        val rtDropped: Boolean,
        val tracked: Boolean,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val vehicleId: String = "",
        val sampleUtc: Long = 0,
        val vehicleStatus: Int = 0,
        val nextStopIndex: Int = -1,
        val stopIndex: Int = -1,
        val patternStops: Int = -1,
        val tripShapeId: Int = -1,
        val patternId: Int = -1,
        val platform: String = "",
    ) {
        val key get() = ArrivalKey(stopId, tripId)
        fun departure(alert: Int = 0) = Departure(
            tripId, staticUtc, rtUtc, statisticalUtc, status, certainty, traffic,
            frequency, rtDropped, vehicleStatus, alert, platform,
        )
        val hasLocation get() = tracked && lat != 0.0 && lon != 0.0
        val stopsAway get() =
            if (nextStopIndex >= 0 && stopIndex >= nextStopIndex) stopIndex - nextStopIndex else -1
    }

    class ServiceAlert(
        val id: String,
        val category: Int,
        val label: String,
        val title: String,
        val body: String,
        val html: Boolean = false,
        val activeFrom: Long = 0,
        val activeTo: Long = 0,
        val url: String = "",
    )

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
                        platform = platformOf(am[6] as? String),
                    )
                }
            }
        }
        return out to poll
    }

    internal fun arrivalsConf() = TWriter()
        .boolField(2, false).boolField(3, false).boolField(4, true).boolField(5, true).boolField(6, false)

    private const val APP4CDN = "https://app4cdn.moovitapp.com/services-app/services/"

    private fun get(base: String, path: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        repeat(2) { attempt ->
            val url = path.replace(Regex("(metro_revision|metroRevisionNumber)=\\d+"), "$1=$metroRev")
            val c = (URL(base + url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 25000
                for ((k, v) in withRev(headers)) setRequestProperty(k, v)
            }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { st ->
                val b = st.readBytes(); if (c.contentEncoding == "gzip") GZIPInputStream(b.inputStream()).readBytes() else b
            } ?: ByteArray(0)
            if (attempt == 0 && (adoptRevision(c, code) || code == 412)) return@repeat
            return code to raw
        }
        return 412 to ByteArray(0)
    }

    class Stop(val id: Int, val lat: Double, val lon: Double, val name: String)

    class LineInfo(
        val groupId: Int,
        val number: String,
        val agencyId: Int,
        val origin: String,
        val destination: String,
        val caption: String,
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
    private val agencyNames = java.util.concurrent.ConcurrentHashMap<Int, String>()

    @Suppress("UNCHECKED_CAST")
    private fun entity(s: MoovitSession, type: Int, id: Int): Map<Int, Any?>? {
        val qs = "V5/Entities/Entity?entity_type=$type&entity_id=$id&metro_area_id=${s.metroId}" +
            "&metro_revision=$metroRev&protocol_version=1&resolve_references=false"
        val (code, raw) = try { get(APP4CDN, qs, authHeaders(s)) } catch (e: Exception) { return null }
        if (code != 200 || raw.size < 8) return null
        val list = try { TReader(raw).readStruct()[1] as? List<Any?> } catch (e: Exception) { null } ?: return null
        val first = list.firstOrNull() as? Map<Int, Any?> ?: return null
        return first[1] as? Map<Int, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun lineInfo(s: MoovitSession, lineId: Int): LineInfo? {
        lineCache[lineId]?.let { return it }
        val g = entity(s, 4, lineId)?.get(8) as? Map<Int, Any?> ?: return null
        val summaries = g[6] as? List<Any?> ?: emptyList<Any?>()
        val mine = summaries.mapNotNull { it as? Map<Int, Any?> }.firstOrNull { it[1] == lineId }
        val info = LineInfo(
            groupId = (g[1] as? Int) ?: -1,
            number = (g[2] as? String).orEmpty(),
            agencyId = (g[3] as? Int) ?: -1,
            origin = (mine?.get(2) as? String).orEmpty(),
            destination = (mine?.get(3) as? String).orEmpty(),
            caption = (g[9] as? String) ?: (g[8] as? String).orEmpty(),
        )
        for (e in summaries) {
            val m = e as? Map<Int, Any?> ?: continue
            val lid = m[1] as? Int ?: continue
            lineCache[lid] = LineInfo(
                info.groupId, info.number, info.agencyId,
                (m[2] as? String).orEmpty(), (m[3] as? String).orEmpty(), info.caption,
            )
        }
        lineCache[lineId] = info
        return info
    }

    private val shapeCache = java.util.concurrent.ConcurrentHashMap<Int, List<Pair<Double, Double>>>()

    fun tripShape(s: MoovitSession, shapeId: Int): List<Pair<Double, Double>> {
        if (shapeId <= 0) return emptyList()
        shapeCache[shapeId]?.let { return it }
        val e = entity(s, 15, shapeId) ?: return emptyList()
        val pts = tripShapeOf(shapeId, e)
        if (pts.isNotEmpty()) shapeCache[shapeId] = pts
        return pts
    }

    @Suppress("UNCHECKED_CAST")
    internal fun tripShapeOf(shapeId: Int, e: Map<Int, Any?>): List<Pair<Double, Double>> {
        val rec = e[11] as? Map<Int, Any?> ?: return emptyList()
        if (rec[1] != shapeId) return emptyList()
        val pts = decodePolyline(rec[2] as? String)
        return pts.takeIf { it.size >= 2 && it.all { p -> validPoint(p.first, p.second) != null } } ?: emptyList()
    }

    fun cachedShape(shapeId: Int): List<Pair<Double, Double>> = shapeCache[shapeId] ?: emptyList()

    fun stopInfo(s: MoovitSession, stopId: Int): StopInfo? {
        stopCache[stopId]?.let { return it }
        val info = entity(s, 3, stopId)?.let { stopInfoOf(stopId, it) } ?: return null
        stopCache[stopId] = info
        return info
    }

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

    fun agencyRouteType(s: MoovitSession, agencyId: Int): Int {
        agencyMode[agencyId]?.let { return it }
        loadAgencies(s)
        return agencyMode[agencyId] ?: 3
    }

    private val agencyLock = Any()
    @Volatile
    private var agenciesLoadedRev: String? = null

    // One metro entity lists every agency, so it is fetched once per revision.
    @Suppress("UNCHECKED_CAST")
    private fun loadAgencies(s: MoovitSession) {
        if (agenciesLoadedRev == metroRev) return
        synchronized(agencyLock) {
            if (agenciesLoadedRev == metroRev) return
            val metro = entity(s, 10, s.metroId)?.get(4) as? Map<Int, Any?> ?: return
            val agencies = metro[3] as? List<Any?> ?: return
            for (a in agencies) {
                val m = a as? Map<Int, Any?> ?: continue
                val id = m[1] as? Int ?: continue
                agencyMode[id] = (m[3] as? Int) ?: 3
                (m[2] as? String)?.takeIf { it.isNotBlank() }?.let { agencyNames[id] = it }
            }
            agenciesLoadedRev = metroRev
        }
    }

    fun agencyName(agencyId: Int): String? = agencyNames[agencyId]

    class LineGroup(
        val id: Int,
        val number: String,
        val name: String,
        val cities: String,
        val agencyId: Int,
        val routeType: Int,
    )

    private const val STATIC = "https://static.moovitapp.com/v4/"

    // Every line Moovit knows, from the file its own app searches. The name follows the
    // metro revision, so loading the agencies first brings the revision up to date.
    @Suppress("UNCHECKED_CAST")
    fun lineCatalogue(s: MoovitSession): List<LineGroup> {
        loadAgencies(s)
        var (code, raw) = get(STATIC, "$metroRev/0/line_search_data_${s.metroId}.gz", emptyMap())
        if (code != 200) {
            entity(s, 10, s.metroId)
            val again = get(STATIC, "$metroRev/0/line_search_data_${s.metroId}.gz", emptyMap())
            code = again.first; raw = again.second
        }
        if (code != 200) throw java.io.IOException("line catalogue HTTP $code")
        val data = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(raw.inputStream()).readBytes()
        } else raw
        val r = TReader(data)
        val out = ArrayList<LineGroup>()
        repeat(r.i32()) {
            val section = r.readStruct()
            val type = section[1] as? Int ?: 3
            val agency = section[2] as? Int ?: -1
            for (item in section[3] as? List<Any?> ?: emptyList()) {
                val m = item as? Map<Int, Any?> ?: continue
                val id = m[1] as? Int ?: continue
                val cities = (m[4] as? List<Any?>).orEmpty()
                    .mapNotNull { (it as? Map<Int, Any?>)?.get(1) as? String }.joinToString(" ")
                val number = (m[8] as? String)?.ifBlank { null } ?: (m[6] as? String).orEmpty()
                out.add(LineGroup(id, number, (m[7] as? String).orEmpty(), cities, agency, type))
            }
        }
        return out
    }

    class LineTrips(val lineId: Int, val patternId: Int, val shapeId: Int, val departures: List<Long>)

    // The trips of one line group on one service day (yyyyMMdd), one entry per trip group:
    // its line (direction), stop pattern, shape and departures in epoch seconds.
    @Suppress("UNCHECKED_CAST")
    fun lineGroupTrips(s: MoovitSession, groupId: Int, serviceDate: String): List<LineTrips> {
        val qs = "V4/GetLineGroupTrips?serviceDate=$serviceDate&lineGroupId=$groupId&metroAreaId=${s.metroId}" +
            "&metroRevisionNumber=$metroRev&osTypeId=2&protocolVersionId=4"
        val (code, raw) = get(APP4CDN, qs, authHeaders(s))
        if (code != 200) throw java.io.IOException("GetLineGroupTrips HTTP $code")
        val out = ArrayList<LineTrips>()
        for (lt in TReader(raw).readStruct()[1] as? List<Any?> ?: emptyList()) {
            val line = lt as? Map<Int, Any?> ?: continue
            val lineId = line[1] as? Int ?: continue
            val patterns = (line[3] as? List<Any?>).orEmpty().mapNotNull { it as? Map<Int, Any?> }
                .associate { (it[1] as? Int) to ((it[2] as? Int) ?: -1) }
            for (g in line[2] as? List<Any?> ?: emptyList()) {
                val group = g as? Map<Int, Any?> ?: continue
                val departures = (group[3] as? List<Any?>).orEmpty().mapNotNull { (it as? Long)?.div(1000) }
                out.add(LineTrips(lineId, patterns[group[1] as? Int] ?: -1, (group[2] as? Int) ?: -1, departures))
            }
        }
        return out
    }

    class Place(
        val name: String,
        val detail: String,
        val lat: Double,
        val lon: Double,
        val type: Int = 5,
    )

    private fun searchBody(
        query: String, at: Pair<Double, Double>?, metroId: Int, sections: List<Int> = listOf(2, 3, 4, 5),
    ): ByteArray =
        TWriter().apply {
            strField(1, query)
            i32Field(2, metroId)
            at?.let { structField(3, latlon(it.first, it.second)) }
            i16Field(4, 0)
            i32ListField(8, sections)
            structField(9, locale())
            stop()
        }.bytes()

    // From six letters on, Moovit's app asks Google with a key only it may use, so Kav keeps asking Moovit.
    fun searchPlaces(s: MoovitSession, query: String, at: Pair<Double, Double>?): List<Place> {
        if (query.isBlank()) return emptyList()
        val where = if (shareLocation) at else standIn()
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val (code, raw) = post(APP5, "V4/CloudSearch/FullSearch", searchBody(query, where, s.metroId), h, readMs = 2500)
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
            ))
        }
        // Moovit ranks by text alone, so the same street in the user's own town can lose to Tel Aviv's.
        // Only names holding every typed word move up: Moovit also returns stray near hits, like Shenkar's library
        // for "מכללת ספיר". House numbers are left out, Moovit's street names don't carry them.
        val words = searchWords(query).filter { w -> !w.all { it.isDigit() } }
        val near = where?.let { (la, lo) ->
            out.filter { p ->
                uk.noammm.kav.ui.metres(la, lo, p.lat, p.lon) < 15_000 &&
                    searchWords(p.name).let { name -> words.isNotEmpty() && words.all { w -> name.any { it.startsWith(w) } } }
            }
        }.orEmpty()
        return (near + (out - near.toSet())).take(5)
    }

    // The timetable keys stops by GTFS code, so Moovit's own id comes from a search.
    // Private search sends the stand-in rather than the stop; the stop's own position still picks the hit.
    fun searchStopId(
        s: MoovitSession, name: String, at: Pair<Double, Double>, owns: (Double, Double) -> Boolean = { _, _ -> true },
    ): Int? {
        if (name.isBlank()) return null
        val where = if (shareLocation) at else standIn()
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val (code, raw) = post(
            APP5, "V4/CloudSearch/FullSearch", searchBody(name, where, s.metroId, sections = listOf(1)), h, readMs = 2500,
        )
        if (code != 200) throw RuntimeException("Search HTTP $code")
        val root = JSONObject(String(raw, Charsets.UTF_8))
        var best: Int? = null
        var bestD = 120.0
        forEachItem(jList(root, "2")) { item ->
            if ((jInt(item, "1") ?: 0L).toInt() != 1) return@forEachItem
            val id = (jInt(item, "2") ?: return@forEachItem).toInt()
            val ll = jRec(item, "6") ?: return@forEachItem
            val lat = (jInt(ll, "1") ?: 0L) / 1e6
            val lon = (jInt(ll, "2") ?: 0L) / 1e6
            if (!owns(lat, lon)) return@forEachItem
            val d = Math.hypot((lat - at.first) * 111_000, (lon - at.second) * 93_000)
            if (d < bestD) { best = id; bestD = d }
        }
        return best
    }

    class StopImages(val thumb: String?, val photos: List<String>)

    fun stopImages(s: MoovitSession, stopId: Int): StopImages {
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val body = TWriter().apply { i32Field(1, stopId); stop() }.bytes()
        val (code, raw) = post(APP5, "V5/StopImages/GetStopImages", body, h, readMs = 4000)
        // A stop without photos comes back as 204.
        if (code == 204) return StopImages(null, emptyList())
        if (code != 200) throw RuntimeException("Stop images HTTP $code")
        val root = JSONObject(String(raw, Charsets.UTF_8))
        val out = ArrayList<String>()
        forEachItem(jList(root, "1")) { img -> jStr(img, "1")?.let { out.add(it) } }
        // The photos run to several megabytes each; field 2 is a small thumbnail of one of them.
        return StopImages(jStr(root, "2"), out)
    }

    // Trains come as "3 🚆 #121": the platform, then the train's number. Until the platform is
    // known only the glyph and the number are sent.
    private fun platformOf(raw: String?): String =
        raw?.substringBefore('#')?.trim()?.substringBefore(' ')
            ?.takeIf { it.length <= 4 && it.all(Char::isLetterOrDigit) }.orEmpty()

    enum class LegKind { WALK, WAIT, RIDE, TAXI, BIKE, OTHER }

    data class RideStops(val from: Int, val to: Int)

    enum class TimeState { STATIC, STATISTICAL, REAL_TIME, REAL_TIME_HIGH, REAL_TIME_MEDIUM, REAL_TIME_LOW, REAL_TIME_DROPPED, CANCELED, OUT_OF_SHAPE, FREQUENCY }

    data class Departure(
        val tripId: Long,
        val staticUtc: Long,
        val rtUtc: Long = 0,
        val statisticalUtc: Long = 0,
        val status: Int = 0,
        val certainty: Int = 0,
        val traffic: Int = 0,
        val frequency: Boolean = false,
        val rtDropped: Boolean = false,
        val vehicleStatus: Int = 0,
        val alert: Int = 0,
        val platform: String = "",
    ) {
        val timeUtc get() = if (rtUtc > 0) rtUtc else if (statisticalUtc > 0) statisticalUtc else staticUtc

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

        val live get() = state == TimeState.REAL_TIME || state == TimeState.REAL_TIME_HIGH ||
            state == TimeState.REAL_TIME_MEDIUM || state == TimeState.REAL_TIME_LOW
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
        val nextDeps: List<Departure> = emptyList(),
        val shortName: String = "",
        val pathway: Boolean = false,
        val fare: Int = -1,
        val currency: String = "",
        val shape: List<Pair<Double, Double>> = emptyList(),
        val alertCategory: Int = 0,
        val alertText: String = "",
        val taxiPickup: Pair<Double, Double>? = null,
        val taxiDropoff: Pair<Double, Double>? = null,
        val alternatives: List<Leg> = emptyList(),
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
        val fare: Int = -1,
        val currency: String = "",
        val co2g: Int = -1,
        val accessible: Boolean = false,
        val tags: List<String> = emptyList(),
        val section: String = "",
        val sectionId: Int = -1,
        val wire: String? = null,
    ) {
        val durationMin get() = ((arr - dep) / 60).toInt()
        val rides get() = legs.filter { it.kind == LegKind.RIDE }
        val lineIds get() = rides.flatMap { it.lineChoices }
        val transfers get() = (rides.size - 1).coerceAtLeast(0)
        val rideStops get() = rides.map { RideStops(it.fromStop, it.toStop) }
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
        d.traffic, d.frequency, d.rtDropped, d.vehicleStatus, alert, d.platform,
    )

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
            rtDropped = rt == 0L && jBool(a, "20"),
            vehicleStatus = (jRec(a, "11")?.let { v -> jInt(v, "5") } ?: 0L).toInt(),
            platform = platformOf(jStr(a, "6")),
        )
    }

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

    private fun timeDeparture(time: JSONObject?, tripId: Long = 0, end: Boolean = false): Departure? {
        time ?: return null
        val shown = jInt(time, if (end) "2" else "1")?.takeIf { it > 0 } ?: return null
        return Departure(tripId, staticUtc = shown / 1000)
    }

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

    fun boardingOptions(ride: Leg, wait: Leg?): List<Pair<Leg, Leg?>> = ride.options.map { option ->
        option to wait?.options?.firstOrNull { it.lineId == option.lineId }
    }

    internal fun parseLeg(leg: JSONObject): Leg {
        val fid = leg.keys().asSequence().firstOrNull() ?: return Leg(LegKind.OTHER)
        val inner = jRec(leg, fid) ?: return Leg(LegKind.OTHER)
        val (dep, arr) = timeOf(inner)
        return when (fid.toIntOrNull()) {
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
            10 -> {
                val options = ArrayList<Leg>()
                forEachItem(jList(inner, "1")) { options.add(rideOf(it)) }
                val primary = (jInt(inner, "2") ?: 0L).toInt()
                (options.getOrNull(primary) ?: options.firstOrNull())?.copy(alternatives = options)
                    ?: Leg(LegKind.OTHER, dep = dep, arr = arr)
            }
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
            wire = it.toString(),
        )
    }

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

    const val TIME_ARRIVAL = 1
    const val TIME_DEPARTURE = 2
    const val TIME_LAST = 3

    class PlannerRefusal(
        val code: Int,
        val title: String,
        val detail: String,
    ) : RuntimeException("TripPlanner refused ($code): $title")

    const val PLAN_TOO_CLOSE = 10
    const val PLAN_NO_ROUTES = 11
    const val PLAN_TOO_FAR = 2
    const val PLAN_NO_COVERAGE = 1032

    fun planItineraries(
        s: MoovitSession,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        whenMs: Long = 0L,
        timeType: Int = TIME_DEPARTURE,
        routeTypes: List<Int> = ALL_ROUTE_TYPES,
        skipTaxi: Boolean = false,
        via: List<RideStops> = emptyList(),
        stopovers: List<Place> = emptyList(),
        toName: String? = null,
    ): Plan {
        val stops = via.flatMap { listOf(it.from, it.to) }.fold(ArrayList<Int>()) { out, id ->
            if (out.lastOrNull() != id) out.add(id)
            out
        }.map { id ->
            require(id > 0)
            stopInfo(s, id)?.takeIf { it.point != null }
                ?: throw IllegalStateException("A saved stop is no longer available.")
        }
        val body = tripPlanRequest(from, to, whenMs, timeType, routeTypes, skipTaxi, stops, stopovers, toName)
        val h = authHeaders(s) + mapOf("Accept" to "application/json")
        val (code, raw) = post(APP5, "V4/TripPlanner2/Search", body, h)
        if (code == 424) throw refusal(raw)
        if (code != 200) throw RuntimeException("TripPlanner HTTP $code")
        val out = ArrayList<Itinerary>()
        var sections = emptyList<Section>()
        val tok = org.json.JSONTokener(String(raw, Charsets.UTF_8))
        while (tok.more()) {
            val v = try { tok.nextValue() } catch (e: Exception) { break }
            val obj = v as? JSONObject ?: continue
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

    fun shareItinerary(s: MoovitSession, trip: Itinerary): String {
        val wire = trip.wire ?: throw IllegalStateException("This saved trip needs to be refreshed before sharing.")
        val body = TWriter().i32Field(1, 1).strField(2, trip.guid)
            .structField(3, thriftFields(JSONObject(wire))).stop().bytes()
        val (code, raw) = post(APP5, "V5/Sharing/ShareItinerary", body,
            authHeaders(s) + ("Accept" to "application/json"))
        if (code != 200) throw RuntimeException("Share itinerary HTTP $code")
        val link = jRec(JSONObject(String(raw, Charsets.UTF_8)), "1")?.let { jStr(it, "1") }
            ?: throw IllegalStateException("Moovit returned no itinerary link.")
        require(MoovitLink.parse(link)?.sharedId != null) { "Moovit returned an unsupported itinerary link." }
        return link
    }

    class SharedTrip(val trip: Itinerary, val from: Place?, val to: Place?)

    fun sharedItinerary(s: MoovitSession, id: String): SharedTrip {
        val body = TWriter().strField(1, id).stop().bytes()
        val (code, raw) = post(APP5, "V4/TripPlanner2/GetSharedItinerary", body,
            authHeaders(s) + ("Accept" to "application/json"))
        if (code != 200) throw RuntimeException("Shared itinerary HTTP $code")
        val root = JSONObject(String(raw, Charsets.UTF_8))
        val trip = jRec(root, "1")?.let(::parseItinerary)
            ?: throw IllegalStateException("This shared trip is no longer available.")
        val request = jRec(root, "2")
        fun endpoint(field: String): Place? {
            val location = request?.let { jRec(it, field) }?.let { jRec(it, "1") } ?: return null
            val point = locationPoint(location) ?: return null
            return Place(jStr(location, "1").orEmpty(), "", point.first, point.second)
        }
        return SharedTrip(trip, endpoint("6"), endpoint("7"))
    }

    private fun refusal(raw: ByteArray): PlannerRefusal {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { null }
        return PlannerRefusal(
            code = o?.let { jInt(it, "3") }?.toInt() ?: 0,
            title = o?.let { jStr(it, "1") }.orEmpty(),
            detail = o?.let { jStr(it, "2") }.orEmpty(),
        )
    }

    class Resolved(
        val lines: Map<Int, LineInfo> = emptyMap(),
        val stops: Map<Int, StopInfo> = emptyMap(),
        val routeTypes: Map<Int, Int> = emptyMap(),
        val live: Map<ArrivalKey, Arrival> = emptyMap(),
        val shapes: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
        val pollSecs: Int = 20,
        val patterns: Map<Int, List<Int>> = emptyMap(),
    ) {
        fun arrival(ride: Leg): Arrival? = live[ArrivalKey(ride.fromStop, ride.tripId)]

        fun liveFor(ride: Leg): List<Arrival> = live.values.filter { a ->
            a.stopId == ride.fromStop && a.lineId == ride.lineId &&
                patterns[a.patternId]?.contains(ride.toStop) == true
        }.sortedBy { it.departure().timeUtc }

        fun platform(ride: Leg, wait: Leg? = null): String =
            arrival(ride)?.platform?.ifBlank { null }
                ?: departures(ride, wait).firstOrNull { it.platform.isNotBlank() }?.platform
                ?: ""

        fun departures(ride: Leg, wait: Leg?): List<Departure> {
            val boarding = wait?.options?.firstOrNull { it.lineId == ride.lineId }
            val future = boarding?.nextDeps.orEmpty().filter { it.tripId != 0L }
            val selected = future.firstOrNull { it.tripId == ride.tripId }
                ?: ride.nextDeps.firstOrNull()
                ?: boarding?.nextDeps?.firstOrNull { it.tripId == 0L }?.copy(tripId = ride.tripId)
                ?: Departure(ride.tripId, ride.dep)
            val alert = boarding?.alertCategory ?: 0
            val planned = (listOf(selected.copy(alert = alert)) + future.filter { it.tripId != ride.tripId })
                .distinctBy { it.tripId to if (it.tripId == 0L) it.timeUtc else 0L }
            // Live arrivals cover the next hour or so. Buses well before the planned one are left
            // out, and a trip planned past what they cover keeps its own times.
            val mine = { d: Departure -> selected.tripId != 0L && d.tripId == selected.tripId }
            val current = liveFor(ride).map { it.departure(alert) }
                .filter { mine(it) || it.timeUtc >= selected.timeUtc - 10 * 60 }
            if (current.any { mine(it) || it.timeUtc >= selected.timeUtc }) return current
            return planned.sortedBy { it.timeUtc }
        }

        fun line(id: Int): LineInfo? = lines[id]
        fun stop(id: Int): StopInfo? = stops[id]
        fun stopName(id: Int): String? = stops[id]?.name
        fun routeType(agencyId: Int): Int = routeTypes[agencyId] ?: 3
        fun agencyName(agencyId: Int): String? = Moovit.agencyName(agencyId)

    }

    fun hydrate(s: MoovitSession, list: List<Itinerary>): Resolved {
        val lineIds = LinkedHashSet<Int>()
        val stopIds = LinkedHashSet<Int>()
        for (i in list) for (leg in i.legs) for (l in leg.options) {
            if (l.kind == LegKind.RIDE) lineIds.addAll(l.lineChoices.filter { it > 0 })
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

    internal fun trackedShapeIds(list: List<Itinerary>, live: Map<ArrivalKey, Arrival>): List<Int> =
        list.asSequence().flatMap { it.rides.asSequence() }.flatMap { it.options.asSequence() }
            .mapNotNull { live[ArrivalKey(it.fromStop, it.tripId)] }
            .filter { it.hasLocation && it.tripShapeId > 0 }
            .map { it.tripShapeId }.distinct().toList()

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
            val jobs = missing.map { id -> id to pool.submit<List<Int>> { tripPattern(s, id) } }
            for ((id, job) in jobs) runCatching { job.get() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { out[id] = it }
        } finally {
            pool.shutdown()
        }
        return out
    }

    private val patternCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, List<Int>>()

    fun tripPattern(s: MoovitSession, id: Int): List<Int> {
        if (id <= 0) return emptyList()
        patternCache[metroRev to id]?.let { return it }
        val stops = entity(s, 13, id)?.let { tripPatternOf(id, it) }.orEmpty()
        if (stops.isNotEmpty()) patternCache[metroRev to id] = stops
        return stops
    }

    @Suppress("UNCHECKED_CAST")
    internal fun tripPatternOf(id: Int, entity: Map<Int, Any?>): List<Int> {
        val pattern = entity[9] as? Map<Int, Any?> ?: return emptyList()
        if (pattern[1] != id) return emptyList()
        return (pattern[2] as? List<*>)?.filterIsInstance<Int>().orEmpty()
    }

    private val patternStopCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, List<StopInfo>>()

    // Asked to resolve references, Moovit sends every stop on the pattern along with it, codes included.
    @Suppress("UNCHECKED_CAST")
    fun patternStops(s: MoovitSession, patternId: Int): List<StopInfo> {
        if (patternId <= 0) return emptyList()
        patternStopCache[metroRev to patternId]?.let { return it }
        val qs = "V5/Entities/Entity?entity_type=13&entity_id=$patternId&metro_area_id=${s.metroId}" +
            "&metro_revision=$metroRev&protocol_version=1&resolve_references=true"
        val (code, raw) = get(APP4CDN, qs, authHeaders(s))
        if (code != 200) throw RuntimeException("Pattern HTTP $code")
        val entries = (TReader(raw).readStruct()[1] as? List<Any?>).orEmpty()
            .mapNotNull { (it as? Map<Int, Any?>)?.get(1) as? Map<Int, Any?> }
        val stops = entries.mapNotNull { e -> ((e[5] as? Map<Int, Any?>)?.get(1) as? Int)?.let { stopInfoOf(it, e) } }
        entries.map { tripPatternOf(patternId, it) }.firstOrNull { it.isNotEmpty() }
            ?.let { patternCache[metroRev to patternId] = it }
        for (st in stops) stopCache[st.id] = st
        patternStopCache[metroRev to patternId] = stops
        return stops
    }

    fun refreshLive(s: MoovitSession, list: List<Itinerary>, prev: Resolved): Resolved {
        val boarding = list.flatMap { i -> i.legs.filter { it.kind == LegKind.WAIT || it.kind == LegKind.RIDE } }
            .flatMap { it.options }.map { it.fromStop }.filter { it > 0 }.distinct().take(40)
        val (live, poll) = try { stopArrivals(s, boarding) } catch (e: Exception) { return prev }
        return Resolved(prev.lines, prev.stops, prev.routeTypes, live, shapesFor(s, list, live, prev.shapes), poll,
            patternsFor(s, list, live, prev.patterns))
    }

    val ALL_ROUTE_TYPES = listOf(0, 1, 2, 3, 4, 5, 6, 7)

    private fun tripPlanRequest(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        whenMs: Long = 0L,
        timeType: Int = TIME_DEPARTURE,
        routeTypes: List<Int> = ALL_ROUTE_TYPES,
        skipTaxi: Boolean = false,
        via: List<StopInfo> = emptyList(),
        stopovers: List<Place> = emptyList(),
        toName: String? = null,
    ): ByteArray {
        fun locTarget(lat: Double, lon: Double, caption: String?, locType: Int, source: Int): TWriter {
            val inner = TWriter()
            if (caption != null) inner.strField(1, caption)
            inner.structField(3, latlon(lat, lon)); inner.i32Field(4, locType)
            return TWriter().structField(1, inner).i32Field(2, source)
        }
        return TWriter().apply {
            i32Field(1, 2)
            i64Field(2, if (whenMs > 0) whenMs else System.currentTimeMillis())
            i32Field(3, timeType)
            boolField(4, whenMs <= 0L && timeType == TIME_DEPARTURE)
            i32ListField(5, routeTypes.ifEmpty { ALL_ROUTE_TYPES })
            structField(6, locTarget(from.first, from.second, null, 9, 5))
            structField(7, locTarget(to.first, to.second, toName?.takeIf { it.isNotBlank() } ?: "Destination", 1, 4))
            boolField(10, skipTaxi)
            i32ListField(13, listOf(5, 1, 2, 4))
            boolField(15, true)
            structField(16, TWriter().boolField(1, false).boolField(2, false).boolField(3, false))
            i32Field(17, if (via.isEmpty()) 1 else 0)
            strField(18, "suggested_routes")
            if (via.isNotEmpty()) {
                structField(19, TWriter().listField(1, TType.STRUCT, via) { writer, stop ->
                    // Saved boarding/alighting points use IMPLICIT locations, as Moovit's saved routes do.
                    writer.structField(2, TWriter().structField(1,
                        TWriter().strField(1, stop.name).i32Field(2, stop.id)
                            .structField(3, latlon(stop.lat, stop.lon)).i32Field(4, 5)).i32Field(2, 0)).stop()
                })
            } else if (stopovers.isNotEmpty()) {
                // Stops the rider added on the way: EXPLICIT locations, which Moovit routes through in order.
                structField(19, TWriter().listField(1, TType.STRUCT, stopovers) { writer, p ->
                    writer.structField(2, TWriter().structField(1,
                        TWriter().strField(1, p.name).structField(3, latlon(p.lat, p.lon)).i32Field(4, 1)).i32Field(2, 1)).stop()
                })
            }
            stop()
        }.bytes()
    }
}
