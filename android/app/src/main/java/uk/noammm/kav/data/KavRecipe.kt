package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import java.net.URI

// Kav's own recipe, published at recipe/kav.json in Kav's repo and kept current the way Vela's is (SignedFile): the
// Moovit values Kav sends, which Moovit changes now and then, and a patch for Google's recipe when Vela fixes
// something only in its app. The key that signs it stays on Noam's computer
// and tools/sign_recipe.sh signs with it, so a fix reaches every Kav within a day without an update.
internal object KavRecipe {
    private val FILE = SignedFile(
        "kav-recipe", "https://raw.githubusercontent.com/ImNoammm/kav/main/recipe/kav.json",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcD2voG/h6bEWo7OVRptCGRxvmww3y7dN3Az60ys1Isx7yH3U+5Y8TPfoSddRKXKmtbVM9FqybU2S+L+KcInidg==",
    )

    class Moovit(
        val apiKey: String,
        val clientVersion: String,
        val storeAppId: String,
        // The payment context Kav pays in, and the one its sign-in uses when Moovit's own setting can't be read.
        val payContext: String,
        val payLoginContext: String,
        val app4: String,
        val app5: String,
        val app4cdn: String,
        val static: String,
        // A header named here replaces Kav's own, and one set to null is left out.
        val userHeaders: Map<String, String?>,
        val headers: Map<String, String?>,
    )

    private val DEFAULT = Moovit(
        apiKey = "moovit_2751703405",
        clientVersion = "5.201.5.1810",
        storeAppId = "com.tranzmate",
        payContext = "IsraelMot",
        payLoginContext = "Login@Default",
        app4 = "https://app4.moovitapp.com/services-app/services/",
        app5 = "https://app5.moovitapp.com/services-app/services/",
        app4cdn = "https://app4cdn.moovitapp.com/services-app/services/",
        static = "https://static.moovitapp.com/v4/",
        userHeaders = emptyMap(),
        headers = emptyMap(),
    )

    @Volatile private var version = 0

    @Volatile
    var moovit = DEFAULT
        private set

    // The copy kept from an earlier check, then the published one at most once a day. Blocks, so off the main thread.
    fun refresh(ctx: Context) = FILE.refresh(ctx) { body ->
        val o = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull() ?: return@refresh false
        val v = o.optInt("version", 0)
        if (v <= version) return@refresh false
        val m = o.optJSONObject("moovit")?.let { parse(it) ?: return@refresh false } ?: DEFAULT
        version = v
        moovit = m
        GoogleRecipe.patchWith(o.optJSONObject("google"))
        true
    }

    // Every address has to be Moovit's own, over https, or the whole recipe is turned away.
    private fun parse(o: JSONObject): Moovit? {
        fun text(k: String, base: String) = o.optString(k).ifBlank { base }
        val hosts = o.optJSONObject("hosts")
        fun host(k: String, base: String) = hosts?.optString(k)?.ifBlank { null } ?: base
        fun headers(k: String): Map<String, String?> {
            val h = o.optJSONObject(k) ?: return emptyMap()
            val names = h.names() ?: return emptyMap()
            return (0 until names.length()).map { names.getString(it) }.associateWith { if (h.isNull(it)) null else h.getString(it) }
        }
        val m = Moovit(
            apiKey = text("apiKey", DEFAULT.apiKey),
            clientVersion = text("clientVersion", DEFAULT.clientVersion),
            storeAppId = text("storeAppId", DEFAULT.storeAppId),
            payContext = text("payContext", DEFAULT.payContext),
            payLoginContext = text("payLoginContext", DEFAULT.payLoginContext),
            app4 = host("app4", DEFAULT.app4),
            app5 = host("app5", DEFAULT.app5),
            app4cdn = host("app4cdn", DEFAULT.app4cdn),
            static = host("static", DEFAULT.static),
            userHeaders = headers("userHeaders"),
            headers = headers("headers"),
        )
        val moovits = listOf(m.app4, m.app5, m.app4cdn, m.static).all { url ->
            runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host != null &&
                (it.host == "moovitapp.com" || it.host.endsWith(".moovitapp.com")) } == true
        }
        return m.takeIf { moovits }
    }
}
