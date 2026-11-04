package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject

// The sender's page choices are saved alongside the shared trip.
data class ShareStyle(
    val language: String = "auto",
    val theme: String = "auto",
    val apps: Set<String> = APPS.toSet(),
    val order: List<String> = APPS,
    val map: Boolean = true,
    val stopLanguage: String = "auto",
    val expiryDays: Int = 7,
) {
    fun json(): JSONObject = JSONObject().put("language", language).put("theme", theme)
        .put("apps", JSONArray(APPS.filter { it in apps })).put("order", JSONArray(order)).put("map", map)
        .put("stopLanguage", stopLanguage).put("expiryDays", expiryDays)

    // Resolve Automatic when sharing, so every recipient sees the sender's choices.
    fun resolved(pageLanguage: String, stopNamesEnglish: Boolean, pageTheme: String): ShareStyle = copy(
        language = if (language == "auto") pageLanguage else language,
        stopLanguage = if (stopLanguage == "auto") { if (stopNamesEnglish) "en" else "he" } else stopLanguage,
        theme = if (theme == "auto") pageTheme else theme,
    )

    fun page(): JSONObject = JSONObject().put("lang", language).put("stops", stopLanguage).put("theme", theme)
        .put("apps", order.filter { it in apps }.joinToString(",")).put("map", if (map) "1" else "0")

    companion object {
        val APPS = listOf("moovit", "egg", "bnb", "kav")

        fun read(o: JSONObject): ShareStyle {
            val defaults = ShareStyle()
            val shown = o.optJSONArray("apps")
            val savedOrder = o.optJSONArray("order")
            val order = savedOrder?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in APPS }.distinct() }.orEmpty()
            return ShareStyle(
                o.optString("language").takeIf { it in listOf("auto", "en", "he") } ?: defaults.language,
                o.optString("theme").let { if (it == "black") "oled" else it }.takeIf { it in listOf("auto", "oled", "dark", "light") } ?: defaults.theme,
                if (shown == null) defaults.apps else (0 until shown.length()).map { shown.optString(it) }.filter { it in APPS }.toSet(),
                order + APPS.filter { it !in order },
                o.optBoolean("map", true),
                o.optString("stopLanguage").takeIf { it in listOf("auto", "en", "he") } ?: defaults.stopLanguage,
                o.optInt("expiryDays").takeIf { it in listOf(3, 7, 30) } ?: defaults.expiryDays,
            )
        }
    }
}
