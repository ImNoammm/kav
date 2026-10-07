package uk.noammm.kav.data

import java.net.URLDecoder
import java.net.URI
import java.net.HttpURLConnection
import java.util.Locale

object MoovitLink {
    class Ride(val lineId: Int, val tripId: Long, val depSec: Long)

    class Plan(
        val fromName: String?, val fromLat: Double?, val fromLon: Double?,
        val toName: String?, val toLat: Double?, val toLon: Double?,
        val departMs: Long,
        val autoRun: Boolean,
        val rides: List<Ride> = emptyList(),
        val sharedId: String? = null,
        val shortUrl: String? = null,
        val lineGroup: Int? = null,
        val stopId: Int? = null,
    )

    fun parse(url: String?): Plan? = parse(url, 0)

    private fun parse(url: String?, depth: Int): Plan? {
        if (url.isNullOrBlank()) return null
        if (depth > 4 || url.length > 16_384) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) return geo(trimmed.substring(4))
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.port != -1) return null
        val scheme = trimmed.substringBefore("://", "").lowercase(Locale.US)
        val rest = trimmed.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val path = uri.path.orEmpty().trimEnd('/')
        val web = scheme in listOf("http", "https") && host in listOf("moovitapp.com", "www.moovitapp.com")
        val shared = when {
            web && path.startsWith("/i/") -> path.removePrefix("/i/")
            scheme == "moovit" && host == "i" -> path.removePrefix("/")
            else -> null
        }
        // A line or a stop opened from Moovit, the way its own app reads them: moovit://line?lgi=, moovit://station/<id>.
        val line = (scheme == "moovit" && host == "line") || (web && path == "/line")
        if (line) return parameters(uri.rawQuery.orEmpty())["lgi"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            Plan(null, null, null, null, null, null, 0L, false, lineGroup = it)
        }
        val station = (scheme == "moovit" && host == "station") || (web && path.startsWith("/station/"))
        if (station) return path.substringAfterLast('/').toIntOrNull()?.takeIf { it > 0 }?.let {
            Plan(null, null, null, null, null, null, 0L, false, stopId = it)
        }
        if (shared != null) return shared.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }?.let {
            Plan(null, null, null, null, null, null, 0L, true, sharedId = it)
        }
        if (scheme in listOf("https", "http") && host == "moovit.onelink.me") {
            val params = parameters(uri.rawQuery.orEmpty())
            for (key in listOf("deep_link_value", "af_dp", "link", "af_android_url", "af_web_dp")) {
                parse(params[key], depth + 1)?.takeIf { it.shortUrl == null }?.let { return it }
            }
            return Plan(null, null, null, null, null, null, 0L, true,
                shortUrl = trimmed.replaceFirst(Regex("^http:"), "https:"))
        }
        val ok = when (scheme) {
            "moovit" -> host == "directions"
            "https", "http" -> web && path == "/directions"
            else -> false
        }
        if (!ok) return null
        val query = rest.substringAfter('?', "").substringBefore('#')
        val params = parameters(query)
        fun latLon(latKey: String, lonKey: String): Pair<Double, Double>? {
            val lat = params[latKey]?.toDoubleOrNull() ?: return null
            val lon = params[lonKey]?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }
        val from = latLon("orig_lat", "orig_lon")
        val fromName = params["orig_name"]?.takeIf { it.isNotBlank() }
        val to = latLon("dest_lat", "dest_lon")
        val toName = params["dest_name"]?.takeIf { it.isNotBlank() }
        if (to == null && toName == null) return null
        val autoRun = when (params["auto_run"]) {
            "false", "0" -> false
            else -> true
        }
        return Plan(
            fromName, from?.first, from?.second,
            toName, to?.first, to?.second,
            departMs = params["date"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            autoRun = autoRun,
            rides = ridesOf(params["kav_trip"]),
        )
    }

    // geo: links (RFC 5870, plus Android's ?q=): a point, a point with "(label)", or a place name to search for.
    // Kav plans the way there from where you are, as apps like GeoShare expect of a navigation app.
    private fun geo(body: String): Plan? {
        fun point(text: String): Pair<Double, Double>? {
            val parts = text.split(',')
            if (parts.size < 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return (lat to lon).takeIf { lat != 0.0 || lon != 0.0 }
        }
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

    // No AppsFlyer SDK or tracking identity: follow only Moovit's public redirect pages.
    fun resolve(plan: Plan): Plan {
        var next = plan.shortUrl ?: return plan
        val visited = HashSet<String>()
        repeat(5) {
            if (!visited.add(next)) throw IllegalStateException("This Moovit link redirects in a loop.")
            val parsed = parse(next) ?: throw IllegalArgumentException("Unsupported Moovit link.")
            if (parsed.shortUrl == null) return parsed
            val connection = (URI(parsed.shortUrl).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36")
            }
            try {
                val code = connection.responseCode
                val redirect = connection.getHeaderField("Location")
                if (code in 300..399 && redirect != null) {
                    embedded(redirect)?.let { return it }
                    next = URI(next).resolve(redirect).toString()
                } else {
                    if (code != 200) throw IllegalStateException("Moovit link HTTP $code")
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
                    return embedded(page) ?: throw IllegalStateException("This Moovit link contains no route.")
                }
            } finally { connection.disconnect() }
        }
        throw IllegalStateException("Too many Moovit link redirects.")
    }

    internal fun embedded(text: String): Plan? {
        val plain = text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        for (match in Regex("intent://[^\\s\"'<>]+").findAll(plain)) {
            val intent = match.value
            if (intent.contains(";scheme=moovit;")) {
                parse("moovit://" + intent.removePrefix("intent://").substringBefore("#Intent"))?.let { return it }
            }
        }
        parse(plain)?.takeIf { it.shortUrl == null }?.let { return it }
        for (match in Regex("(?:https?://(?:www\\.)?moovitapp\\.com/|moovit://)[^\\s\"'<>\\\\]+").findAll(plain)) {
            parse(match.value)?.takeIf { it.shortUrl == null }?.let { return it }
        }
        return null
    }

    private fun ridesOf(v: String?): List<Ride> {
        if (v.isNullOrBlank()) return emptyList()
        val out = ArrayList<Ride>()
        for (part in v.split('~')) {
            val bits = part.split('.')
            if (bits.size != 3) return emptyList()
            val line = bits[0].toIntOrNull() ?: return emptyList()
            val trip = bits[1].toLongOrNull() ?: return emptyList()
            val dep = bits[2].toLongOrNull() ?: return emptyList()
            out.add(Ride(line, trip, dep))
        }
        return out
    }

}
