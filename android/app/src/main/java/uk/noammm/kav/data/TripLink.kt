package uk.noammm.kav.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

// Links that open a trip in Kav: Kav's own (tripLink), geo: links, and points typed or pasted as text.
object TripLink {
    const val HOST = "kav.noammm.uk"

    class Ride(val lineId: Int, val tripId: Long, val depSec: Long)

    class Plan(
        val fromName: String?, val fromLat: Double?, val fromLon: Double?,
        val toName: String?, val toLat: Double?, val toLon: Double?,
        val departMs: Long,
        val autoRun: Boolean,
        val rides: List<Ride> = emptyList(),
    )

    fun parse(url: String?): Plan? {
        if (url.isNullOrBlank() || url.length > 16_384) return null
        val trimmed = url.trim()
        return if (trimmed.startsWith("geo:", ignoreCase = true)) geo(trimmed.substring(4)) else kav(trimmed)
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
