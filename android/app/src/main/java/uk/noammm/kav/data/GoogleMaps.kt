package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

// Google Maps' own web search, asked the way a logged-out browser asks it: no key, no account, no cookies.
// Moovit's app gets its places from Google too, through a key only it may use. How to ask, and where the answers sit,
// is the recipe Vela Maps keeps current (GoogleRecipe). Only the typed text and the area Moovit is told go out.
object GoogleMaps {
    private val sequence = AtomicInteger(1)

    class Suggestions(val places: List<Moovit.Place>, val queries: List<String>)

    private val NONE = Suggestions(emptyList(), emptyList())

    // Places as Google suggests them while typing, and the searches it would correct the text to ("תחנת רכת חדרה"
    // becomes "תחנת רכבת חדרה מזרח"). A suggested address only carries its street's position, so callers geocode those.
    fun suggest(query: String, at: Pair<Double, Double>?): Suggestions {
        if (query.isBlank()) return NONE
        val recipe = GoogleRecipe.current
        val (lat, lon) = where(at)
        // The map's centre and span bias what comes back.
        val pb = recipe.suggestPb.replace("{SPAN}", "20000").replace("{LNG}", "$lon").replace("{LAT}", "$lat")
            .replace("{W}", "1920").replace("{H}", "945")
        val body = get(recipe, recipe.suggestUrl(language(query)) +
            "&pb=$pb&q=${enc(query)}&tch=1&ech=${sequence.getAndIncrement()}") ?: return NONE
        // {"c":0,"d":")]}'\n[[query, [rows...]]]"} and a comment after it.
        val d = (JSONTokener(body).nextValue() as? JSONObject)?.optString("d") ?: return NONE
        val rows = at(JSONArray(d.substringAfter('\n')), recipe.suggest("rows")) as? JSONArray ?: return NONE
        val places = ArrayList<Moovit.Place>()
        val queries = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            // A row is nulls around one block, the first array whose first child is an array starting with text.
            val block = (0 until row.length()).mapNotNull { row.optJSONArray(it) }
                .firstOrNull { b -> b.optJSONArray(0)?.opt(0) is String } ?: continue
            val title = (at(block, recipe.suggest("title")) as? String)?.ifBlank { null } ?: continue
            val la = number(block, recipe.suggest("lat")) ?: number(block, recipe.suggest("lat2"))
            val lo = number(block, recipe.suggest("lng")) ?: number(block, recipe.suggest("lng2"))
            if (la == null || lo == null) { queries.add(title); continue }
            places.add(Moovit.Place(
                name = (at(block, recipe.suggest("primary")) as? String)?.ifBlank { null } ?: title,
                detail = (at(block, recipe.suggest("secondary")) as? String).orEmpty(),
                lat = la, lon = lo,
            ))
        }
        return Suggestions(places, queries)
    }

    // The house a typed address names, as Google's map search places it, or null when it only knows the street.
    fun geocode(query: String, at: Pair<Double, Double>?): Moovit.Place? {
        val typed = TypedAddress.parse(query) ?: return null
        val recipe = GoogleRecipe.current
        val (lat, lon) = where(at)
        val pb = recipe.searchPb.replace("{QUERY}", enc(query.replace("*", "*2A").replace("!", "*21")))
            .replace("{LAT}", "$lat").replace("{LNG}", "$lon")
        val body = get(recipe, recipe.searchUrl(language(query)) + "&q=${enc(query)}&pb=$pb") ?: return null
        val root = JSONArray(body.substringAfter(")]}'").trim())
        // An address comes back as one geocoded place, anything else as a list. An entry holds its place at [1].
        val entry = (at(root, recipe.search("single")) as? JSONArray)?.let { JSONArray().put(JSONObject.NULL).put(it) }
            ?: (at(root, recipe.search("results")) as? JSONArray)?.optJSONArray(0) ?: return null
        val la = number(entry, recipe.search("lat")) ?: return null
        val lo = number(entry, recipe.search("lng")) ?: return null
        val text = (at(entry, recipe.search("address")) as? String)?.ifBlank { null }
            ?: at(entry, recipe.search("name")) as? String ?: return null
        // "5R83+J4 תאו, וינגייט 168, הרצליה, 4675765": the part with the house, then its town.
        val parts = text.split(",").map { it.trim() }
        val house = parts.indexOfFirst { part -> searchWords(part).any(typed::numberIs) && typed.streetIs(part) }
        if (house < 0) return null
        val town = parts.drop(house + 1).firstOrNull { p -> p.any { !it.isDigit() } }.orEmpty()
        // Google answers with the nearest street of that name when the typed town has none.
        if (!typed.townIs(town)) return null
        return Moovit.Place(name = parts[house], detail = town, lat = la, lon = lo)
    }

    private fun where(at: Pair<Double, Double>?) = (if (Moovit.shareLocation) at else Moovit.standIn()) ?: Moovit.NEUTRAL

    private fun language(query: String) = if (query.any { it in 'א'..'ת' }) "iw" else "en"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(recipe: GoogleRecipe, url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("User-Agent", recipe.userAgent)
        }
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun at(a: JSONArray, path: IntArray): Any? {
        var x: Any? = a
        for (i in path) x = (x as? JSONArray)?.opt(i)
        return x
    }

    private fun number(a: JSONArray, path: IntArray) = (at(a, path) as? Number)?.toDouble()
}
