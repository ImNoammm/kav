package uk.noammm.kav.data

import java.net.URI
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

// Links that open a trip in Kav: Kav's own (tripLink), trips shared from Bus Nearby, egg and Moovit, geo: links, and
// points typed or pasted as text.
object TripLink {
    const val HOST = "kav.noammm.uk"

    class Ride(val lineId: Int, val tripId: Long, val depSec: Long)

    // A trip another app shared by its id there, which that app's own servers turn back into the trip (resolve).
    class Shared(val source: Source, val id: String)

    class Plan(
        val fromName: String?, val fromLat: Double?, val fromLon: Double?,
        val toName: String?, val toLat: Double?, val toLon: Double?,
        val departMs: Long,
        val autoRun: Boolean,
        val rides: List<Ride> = emptyList(),
        val shared: Shared? = null,
        val exact: KavPlan.Planned? = null,
    )

    // A shared trip its app no longer has.
    class Expired : java.io.IOException("This shared trip is no longer available")

    fun parse(url: String?): Plan? {
        if (url.isNullOrBlank() || url.length > 16_384) return null
        val trimmed = url.trim()
        return if (trimmed.startsWith("geo:", ignoreCase = true)) geo(trimmed.substring(4)) else kav(trimmed) ?: other(trimmed)
    }

    // The first link in a text shared to Kav that opens a trip: the other apps share theirs inside a message.
    fun inText(text: String?): Plan? = text?.let { Regex("(?:https?|moovit|busnearby|egged)://[^\\s<>\"']+").findAll(it) }
        ?.firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', '!', '?')) }

    // The trip a shared link names, from the app that shared it, asked as that app opens its own link.
    fun resolve(s: Shared): Plan = when {
        s.id.startsWith("https://") -> resolveShort(s.id)
        s.source.hopOn -> BusNearby.shared(s.id, s.source)
        s.source == Source.MOOVIT -> MoovitWeb.shared(s.id)
        else -> throw IllegalArgumentException("${s.source.key} shares no trips")
    }

    // seen: every address asked on the way, across short links that lead to others, so two pages naming each other end.
    private fun resolveShort(url: String, seen: MutableSet<String> = HashSet()): Plan {
        var next = url
        fun open(p: Plan): Plan = p.shared?.let { s -> if (s.id.startsWith("https://")) resolveShort(s.id, seen) else resolve(s) } ?: p
        for (attempt in 0 until 5) {
            if (!seen.add(next)) throw java.io.IOException("Shared link redirects in a loop")
            if (seen.size > 8) throw java.io.IOException("Too many shared link redirects")
            val connection = (URI(next).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 5_000; readTimeout = 10_000
            }
            try {
                val location = connection.getHeaderField("Location")
                if (connection.responseCode in 300..399 && location != null) {
                    next = URI(next).resolve(location).toString()
                    parse(next)?.let { if (it.shared?.id != next) return open(it) }
                    continue
                }
                if (connection.responseCode != 200) throw java.io.IOException("Shared link HTTP ${connection.responseCode}")
                val page = connection.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(4096)
                    while (text.length < 262_144) {
                        val n = reader.read(buffer, 0, minOf(buffer.size, 262_144 - text.length))
                        if (n < 0) break
                        text.append(buffer, 0, n)
                    }
                    text.toString()
                }
                    .replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                for (match in Regex("(?:https?://|moovit://|busnearby://|egged://|intent://)[^\\s\\\"'<>\\\\]+")
                    .findAll(page)) {
                    val link = if (match.value.startsWith("intent://") && ";scheme=moovit;" in match.value)
                        "moovit://" + match.value.removePrefix("intent://").substringBefore("#Intent") else match.value
                    parse(link)?.let { if (it.shared?.id != next) return open(it) }
                }
                throw java.io.IOException("Shared page contains no trip")
            } finally { connection.disconnect() }
        }
        throw java.io.IOException("Too many shared link redirects")
    }

    // Bus Nearby's and egg's: HopOn's itinerary id after "itinerary" in a link on their hosts (busnear.by,
    // busnearby.co.il, egg.hopon.co) or app schemes, where their own apps look for it. Moovit's: an itinerary's id after
    // /i/ (moovitapp.com/i/<id>, which its site sends on to /tripplan/i/<id>, or moovit://i/<id>), or an older
    // directions link with the trip's ends in it.
    private fun other(url: String): Plan? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.port != -1) return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val web = scheme == "https" || scheme == "http"
        // An app scheme's first part is its host: busnearby://itinerary/<id>.
        val parts = (if (web) emptyList() else listOf(host)) + uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            .map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        fun shared(source: Source, id: String?) = id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
            ?.let { Plan(null, null, null, null, null, null, 0L, autoRun = true, shared = Shared(source, it)) }
        val hopOn = when {
            web && (host == "busnear.by" || host == "busnearby.co.il" || host.endsWith(".busnearby.co.il")) || scheme == "busnearby" -> Source.BUS_NEARBY
            web && host == "egg.hopon.co" || scheme == "egged" -> Source.EGG
            else -> null
        }
        if (hopOn != null) return if (web && host == "busnear.by")
            Plan(null, null, null, null, null, null, 0L, true, shared = Shared(hopOn, url))
        else shared(hopOn, parts.indexOf("itinerary").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) })
        if (web && host == "moovit.onelink.me") {
            val p = parameters(uri.rawQuery.orEmpty())
            for (key in listOf("deep_link_value", "af_dp", "link", "af_android_url", "af_web_dp"))
                parse(p[key])?.let { return it }
            return Plan(null, null, null, null, null, null, 0L, true, shared = Shared(Source.MOOVIT, url))
        }
        if (!(web && (host == "moovitapp.com" || host == "www.moovitapp.com") || scheme == "moovit")) return null
        when {
            parts.firstOrNull() == "i" -> return shared(Source.MOOVIT, parts.getOrNull(1))
            web && parts.take(2) == listOf("tripplan", "i") -> return shared(Source.MOOVIT, parts.getOrNull(2))
            parts != listOf("directions") -> return null
        }
        val p = parameters(uri.rawQuery.orEmpty())
        val to = point("${p["dest_lat"]},${p["dest_lon"]}")
        val toName = p["dest_name"]?.takeIf { it.isNotBlank() }
        if (to == null && toName == null) return null
        val from = point("${p["orig_lat"]},${p["orig_lon"]}")
        return Plan(
            p["orig_name"]?.takeIf { it.isNotBlank() }, from?.first, from?.second, toName, to?.first, to?.second,
            departMs = p["date"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0L, autoRun = p["auto_run"] != "false" && p["auto_run"] != "0",
        )
    }

    // A trip shared from Kav: https://kav.noammm.uk/t/ with what was shared after the #, which a browser never sends,
    // so the server never sees it: where it starts and ends ("lat,lon") and their names, when it leaves (at, epoch
    // seconds) and the rides that find this very trip again (trip). Without Kav, the server sends the browser on to Kav's
    // releases.
    fun tripLink(
        from: Pair<Double, Double>, to: Pair<Double, Double>, fromName: String, toName: String, at: Long,
        rides: List<Ride> = emptyList(),
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun at(p: Pair<Double, Double>) = String.format(Locale.US, "%.6f,%.6f", p.first, p.second)
        val trip = if (rides.isEmpty()) "" else "&trip=" + rides.joinToString("~") { "${it.lineId}.${it.tripId}.${it.depSec}" }
        return "https://$HOST/t/#from=${at(from)}&to=${at(to)}&fromName=${enc(fromName)}&toName=${enc(toName)}&at=$at$trip"
    }

    // Kav's own link as tripLink writes it. One without its part after the #, a line's or a stop's (/l/, /s/, which Kav
    // doesn't share), or one whose values don't read, opens Kav as it is.
    private fun kav(url: String): Plan? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.host?.lowercase(Locale.US) != HOST) return null
        if (uri.userInfo != null || uri.port != -1) return null
        val path = uri.rawPath.orEmpty()
        if (path != "/t" && !path.startsWith("/t/")) return null
        val p = parameters(uri.rawFragment.orEmpty())
        val to = p["to"]?.let(::point)
        val toName = p["toName"]?.takeIf { it.isNotBlank() }
        if (to == null && toName == null) return null
        val from = p["from"]?.let(::point)
        val at = p["at"]?.toLongOrNull()?.takeIf { it in 1..4_102_444_800L } ?: 0L
        return Plan(
            p["fromName"]?.takeIf { it.isNotBlank() }, from?.first, from?.second, toName, to?.first, to?.second,
            departMs = at * 1000, autoRun = true, rides = ridesOf(p["trip"]),
        )
    }

    private fun ridesOf(v: String?): List<Ride> {
        if (v.isNullOrBlank()) return emptyList()
        return v.split('~').map { part ->
            val bits = part.split('.')
            if (bits.size != 3) return emptyList()
            Ride(
                bits[0].toIntOrNull() ?: return emptyList(), bits[1].toLongOrNull() ?: return emptyList(),
                bits[2].toLongOrNull() ?: return emptyList(),
            )
        }
    }

    private fun point(text: String): Pair<Double, Double>? {
        val parts = text.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return (lat to lon).takeIf { lat != 0.0 || lon != 0.0 }
    }

    // A point typed or pasted as text, as maps copy it: "32.0853, 34.7818", or with a space for the comma.
    fun coordinates(text: String): Pair<Double, Double>? =
        Regex("^\\s*(-?\\d{1,2}\\.\\d+)\\s*[,\\s]\\s*(-?\\d{1,3}\\.\\d+)\\s*$").find(text)
            ?.let { point("${it.groupValues[1]},${it.groupValues[2]}") }

    // geo: links (RFC 5870, plus Android's ?q=): a point, a point with "(label)", or a place name to search for.
    // Kav plans the way there from where you are, as apps like GeoShare expect of a navigation app.
    private fun geo(body: String): Plan? {
        val q = parameters(body.substringAfter('?', "").substringBefore('#'))["q"]?.trim().orEmpty()
        val labelled = Regex("^(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*(?:\\((.*)\\))?$").find(q)
        val at = labelled?.let { point("${it.groupValues[1]},${it.groupValues[2]}") }
            ?: point(body.substringBefore('?').substringBefore(';'))
        val name = if (labelled != null) labelled.groupValues[3].takeIf { it.isNotBlank() } else q.takeIf { it.isNotBlank() }
        if (at == null && name == null) return null
        return Plan(null, null, null, name, at?.first, at?.second, 0L, autoRun = true)
    }

    private fun parameters(query: String): Map<String, String> = buildMap {
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key.isNotEmpty() && key !in this) put(key, runCatching {
                URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
            }.getOrDefault(""))
        }
    }
}
