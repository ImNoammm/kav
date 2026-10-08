package uk.noammm.kav.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Moovit's own search can't place an exact street address (its app asks Google with a key only it may use).
// A search holding a house number asks Google's map search for the house, and GovMap, the Survey of Israel's map,
// when Google only knows the street: measured on 200 bank branches, Google has the house for about 9 in 10 and
// GovMap for 4 in 5, and each finds some the other misses. GovMap gets only the typed text, never a location.
object Addresses {
    private const val SEARCH = "https://www.govmap.gov.il/api/search-service/autocomplete"

    fun find(query: String, at: Pair<Double, Double>?): List<Moovit.Place> {
        val typed = TypedAddress.parse(query) ?: return emptyList()
        runCatching { GoogleMaps.geocode(query, at) }.getOrNull()?.let { return listOf(it) }
        val body = JSONObject()
            .put("searchText", query.trim())
            .put("language", if (query.any { it in 'א'..'ת' }) "he" else "en")
            .put("isAccurate", false)
            .put("maxResults", 10)
            .toString().toByteArray()
        val c = (URL(SEARCH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("Content-Type", "application/json")
            // GovMap's firewall turns away anything that doesn't call itself a browser.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Kav")
        }
        val raw = try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw RuntimeException("GovMap HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        val results = JSONObject(String(raw, Charsets.UTF_8)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Moovit.Place>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (r.optString("type") != "address") continue
            // "Wingate 168 Herzliya": the street ends at the last number, town names never hold one.
            val m = Regex("""^(.*\s(\d\S*))\s(\D+)$""").find(r.optString("text").trim()) ?: continue
            val (street, number, town) = m.destructured
            // GovMap also offers the street's other numbers, other streets with this number and other towns' streets.
            if (!typed.numberIs(number) || !typed.streetIs(street) || !typed.townIs(town)) continue
            val (lat, lon) = mercator(r.optString("shape")) ?: continue
            out.add(Moovit.Place(name = street, detail = town, lat = lat, lon = lon))
        }
        val near = at ?: return out.take(2)
        return out.sortedBy { uk.noammm.kav.ui.metres(near.first, near.second, it.lat, it.lon) }.take(2)
    }

    // GovMap answers in web Mercator metres: "POINT(3874213.7 3785156.9)".
    private fun mercator(shape: String): Pair<Double, Double>? {
        val xy = shape.removePrefix("POINT(").removeSuffix(")").trim().split(' ')
        val x = xy.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = xy.getOrNull(1)?.toDoubleOrNull() ?: return null
        val r = 6378137.0
        return Math.toDegrees(Math.atan(Math.sinh(y / r))) to Math.toDegrees(x / r)
    }
}

// A typed address: the street before the house number, the town after it ("וינגייט 168 הרצליה"). An answer counts only
// when it is this house: same number, every street word in its street and, when a town was typed, in that town. A
// near miss is worse than none, it sends someone to the right number on another street or in another town.
internal class TypedAddress private constructor(
    private val number: String,
    private val street: List<String>,
    private val town: List<String>,
) {
    fun numberIs(word: String) = word.takeWhile(Char::isDigit) == number

    // Order doesn't matter (GovMap writes "בגין מנחם"), and a longer word may carry one typo.
    fun streetIs(text: String): Boolean {
        val words = words(text)
        return street.all { s ->
            words.any { w -> w.startsWith(s) || (w.length >= 3 && s.startsWith(w)) || (s.length >= 5 && close(s, w)) }
        }
    }

    // Ben Gurion 13 in Ashkelon is not the one in Ashdod. Towns are spelled many ways, so the first letters decide.
    fun townIs(text: String): Boolean {
        if (town.isEmpty()) return true
        val words = words(text)
        return town.any { t -> words.any { w -> w.take(3) == t.take(3) } }
    }

    companion object {
        private val KINDS = setOf("רח", "רחוב", "שד", "שדרות", "דרך", "סמטת", "כיכר", "ככר", "st", "street", "rd", "road",
            "ave", "avenue", "blvd", "boulevard")

        // Spellings that vary by writer: a leading ה, and the vowel letters (יקנעם/יוקנעם, קרית/קריית).
        private fun bare(w: String) = w.removePrefix("ה").filterNot { it == 'י' || it == 'ו' }

        // Geresh and gershayim belong inside a word: צה"ל, נביא אל-ח'דר.
        private val MARKS = Regex("[\"'׳״`]")
        private fun words(text: String) = searchWords(text.replace(MARKS, "")).map(::bare)

        private fun close(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
            return a.drop(i + 1) == b.drop(i + 1) || a.drop(i) == b.drop(i + 1) || a.drop(i + 1) == b.drop(i)
        }

        fun parse(query: String): TypedAddress? {
            val words = searchWords(query.replace(MARKS, ""))
            val at = words.indexOfFirst { it.first().isDigit() }
            if (at < 0) return null
            // Short names count too: חן, תל חי, שא נס.
            val street = words.take(at).filter { it !in KINDS && it.length >= 2 }.map(::bare).filter { it.isNotEmpty() }
            val town = words.drop(at + 1).filter { it !in KINDS && !it.first().isDigit() && it.length >= 2 }.map(::bare)
            if (street.isEmpty()) return null
            return TypedAddress(words[at].takeWhile(Char::isDigit), street, town)
        }
    }
}
