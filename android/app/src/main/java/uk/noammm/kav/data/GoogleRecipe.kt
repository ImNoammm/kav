package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import java.net.URI

// How Kav asks Google Maps' web search, and where the answers sit in its reply. Google reshapes these now and then.
// Vela Maps (github.com/PimpinPumpkin/Vela) keeps them current in a signed calibration.json its app reads at launch,
// so a fix reaches every install without an update, and Kav reads the same file. It takes a copy only when Vela's key
// signed it, it points at Google alone and it is newer than Kav's, and it reads just the search recipe from it: none
// of Vela's notices or scripts. Kav's own recipe (KavRecipe) can patch it for a fix Vela only shipped in its app.
internal class GoogleRecipe private constructor(
    val version: Int,
    val userAgent: String,
    private val searchEndpoint: String,
    val searchPb: String,
    private val searchPaths: Map<String, IntArray>,
    private val suggestEndpoint: String,
    val suggestPb: String,
    private val suggestPaths: Map<String, IntArray>,
) {
    fun searchUrl(language: String) = local(searchEndpoint, language)
    fun suggestUrl(language: String) = local(suggestEndpoint, language)

    // Positions in a search reply: "results" and "single" from its top, the rest within an entry holding a place at [1].
    fun search(key: String): IntArray = searchPaths[key] ?: DEFAULT.searchPaths.getValue(key)
    fun suggest(key: String): IntArray = suggestPaths[key] ?: DEFAULT.suggestPaths.getValue(key)

    companion object {
        // The public half of Vela's signing key, the one Vela's own app pins.
        private val VELA = SignedFile(
            "google-recipe", "https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuz8/zxOJFhVqKco74fkmzrLlyPra4/pTEUm7lmue/Kig0T497fcs+hjhZkaSqVZAwloNrr0+0ILi7yATmU+d3g==",
        )
        private val GOOGLE = setOf("www.google.com", "google.com")
        private val SEARCH_KEYS = listOf("results", "single", "name", "address", "lat", "lng")
        private val SUGGEST_KEYS = listOf("rows", "title", "primary", "secondary", "lat", "lng", "lat2", "lng2")

        // Vela's recipe at version 25 (8 October 2026), which Kav uses until Vela signs a newer one.
        val DEFAULT = GoogleRecipe(
            version = 25,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
            searchEndpoint = "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us",
            searchPb = "!1s{QUERY}!4m8!1m3!1d25229.167291701906!2d{LNG}!3d{LAT}!3m2!1i1024!2i768!4f13.1!7i20!10b1!12m52!1m5!18b1" +
                "!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m25!32i1!49b1!63m0!66b1!85b1!114b1!149b1!206b1!209b1!212b1!21" +
                "6b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1!250b1!253b1!260b1!266b1!273b1!281b1!291m0!10b1!12b1!13b1!1" +
                "4b1!16b1!17m1!3e1!20m3!5e2!6b1!14b1!46m1!1b0!96b1!99b1!19m4!2m3!1i360!2i120!4i8!20m57!2m2!1i203!2i100!3m" +
                "2!2i4!5b1!6m6!1m2!1i86!2i86!1m2!1i408!2i240!7m33!1m3!1e1!2b0!3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3!1m3!1e8" +
                "!2b0!3e3!1m3!1e10!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9!2b1!3e2!2b1!9b0!15m8!1m7!1m2!1m1!1e2" +
                "!2m2!1i195!2i195!3i20!15i9937!24m107!1m25!13m9!2b1!3b1!4b1!6i1!8b1!9b1!14b1!20b1!25b1!18m14!3b1!4b1!5b1!" +
                "6b1!13b1!14b1!17b1!21b1!22b1!32b1!33m1!1b1!34b1!36e2!10m1!8e3!11m1!3e1!17b1!20m2!1e3!1e6!24b1!25b1!26b1!" +
                "27b1!29b1!30m1!2b1!36b1!37b1!39m3!2m2!2i1!3i1!43b1!52b1!54m1!1b1!55b1!56m1!1b1!61m2!1m1!1e1!65m5!3m4!1m3" +
                "!1m2!1i224!2i298!72m22!1m8!2b1!5b1!7b1!12m4!1b1!2b1!4m1!1e1!4b1!8m10!1m6!4m1!1e1!4m1!1e3!4m1!1e4!3sother" +
                "_user_google_review_posts__and__hotel_and_vr_partner_review_posts!6m1!1e1!9b1!89b1!90m2!1m1!1e2!98m3!1b1" +
                "!2b1!3b1!103b1!113b1!114m3!1b1!2m1!1b1!117b1!122m1!1b1!126b1!127b1!128m1!1b0!26m4!2m3!1i80!2i92!4i8!30m2" +
                "8!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6" +
                "!1m2!1i0!2i748!2m2!1i1024!2i768!34m19!2b1!3b1!4b1!6b1!8m6!1b1!3b1!4b1!5b1!6b1!7b1!9b1!12b1!14b1!20b1!23b" +
                "1!25b1!26b1!31b1!37m1!1e81!42b1!49m10!3b1!6m2!1b1!2b1!7m2!1e3!2b1!8b1!9b1!10e2!50m3!2e2!3m1!3b1!61b1!67m" +
                "5!7b1!10b1!14b1!15m1!1b0!69i782!77b1",
            searchPaths = mapOf(
                "results" to intArrayOf(64), "single" to intArrayOf(0, 1, 0, 14), "name" to intArrayOf(1, 11),
                "address" to intArrayOf(1, 39), "lat" to intArrayOf(1, 9, 2), "lng" to intArrayOf(1, 9, 3),
            ),
            suggestEndpoint = "https://www.google.com/s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=en&gl=us",
            suggestPb = "!2i5!4m12!1m3!1d{SPAN}!2d{LNG}!3d{LAT}!2m3!1f0!2f0!3f0!3m2!1i{W}!2i{H}!4f13.1" +
                "!7i20!10b1!12m6!1m2!18b1!30b1!2m2!1i203!2i100!19m4!1m3!1i1!2i1!3i1!20m1!1e1",
            suggestPaths = mapOf(
                "rows" to intArrayOf(0, 1), "title" to intArrayOf(0, 0), "primary" to intArrayOf(1, 0),
                "secondary" to intArrayOf(2, 0), "lat" to intArrayOf(11, 2), "lng" to intArrayOf(11, 3),
                "lat2" to intArrayOf(13, 0, 3, 2), "lng2" to intArrayOf(13, 0, 3, 3),
            ),
        )

        // Vela's newest recipe that verified, and Kav's patch on top of it.
        @Volatile private var vela = DEFAULT
        @Volatile private var patch: JSONObject? = null

        @Volatile
        var current = DEFAULT
            private set

        // The copy kept from an earlier check, then Vela's latest at most once a day. Blocks, so off the main thread.
        fun refresh(ctx: Context) = VELA.refresh(ctx) { body ->
            val recipe = runCatching { parse(JSONObject(body.toString(Charsets.UTF_8)), DEFAULT) }.getOrNull()
            if (recipe == null || recipe.version <= vela.version) return@refresh false
            vela = recipe
            current = patched()
            true
        }

        // Kav's own recipe can carry a fix Vela shipped only in its app, made for the Vela file it names
        // ("velaVersion"). Once Vela publishes a newer file of its own, Vela's wins.
        fun patchWith(google: JSONObject?) {
            patch = google
            current = patched()
        }

        private fun patched(): GoogleRecipe {
            val p = patch?.takeIf { it.length() > 0 } ?: return vela
            if (vela.version > p.optInt("velaVersion", Int.MAX_VALUE)) return vela
            return runCatching { parse(p, vela) }.getOrNull() ?: vela
        }

        // Fields left out keep base's. A recipe that would ask anyone but Google, or lacks a slot Kav fills in, is
        // turned away whole.
        private fun parse(o: JSONObject, base: GoogleRecipe): GoogleRecipe? {
            fun paths(name: String, keys: List<String>, from: Map<String, IntArray>): Map<String, IntArray> {
                val given = o.optJSONObject(name) ?: return from
                return from + keys.mapNotNull { k ->
                    given.optJSONArray(k)?.let { a -> k to IntArray(a.length()) { a.getInt(it) } }
                }
            }
            val r = GoogleRecipe(
                version = if (o.has("version")) o.getInt("version") else base.version,
                userAgent = o.optString("userAgent").ifBlank { base.userAgent },
                searchEndpoint = o.optString("searchEndpoint").ifBlank { base.searchEndpoint },
                searchPb = o.optString("searchPb").ifBlank { base.searchPb },
                searchPaths = paths("paths", SEARCH_KEYS, base.searchPaths),
                suggestEndpoint = o.optString("suggestEndpoint").ifBlank { base.suggestEndpoint },
                suggestPb = o.optString("suggestPb").ifBlank { base.suggestPb },
                suggestPaths = paths("suggestPaths", SUGGEST_KEYS, base.suggestPaths),
            )
            val google = listOf(r.searchEndpoint, r.suggestEndpoint).all { runCatching { URI(it).host }.getOrNull() in GOOGLE }
            val slots = listOf("{QUERY}", "{LAT}", "{LNG}").all { it in r.searchPb } && listOf("{LAT}", "{LNG}").all { it in r.suggestPb }
            return r.takeIf { google && slots }
        }

        // Kav asks in the language typed and from Israel, whatever the recipe was written for.
        private fun local(endpoint: String, language: String) = endpoint
            .replace(Regex("([?&]hl=)[^&]*"), "\$1$language")
            .replace(Regex("([?&]gl=)[^&]*"), "\$1il")
    }
}
