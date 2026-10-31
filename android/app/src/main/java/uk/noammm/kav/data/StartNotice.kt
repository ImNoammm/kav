package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import uk.noammm.kav.R
import uk.noammm.kav.ui.COFFEE_URL
import uk.noammm.kav.ui.T

// A message this version brings, said once as Kav opens, to a rider updating and to a fresh install alike. Its text is
// bundled in res/raw/notice.json, in English and Hebrew, under an id; it's due until the rider closes it, and then never
// again for that id. A file that's missing or not whole shows nothing. Nothing is asked of the network.
internal object StartNotice {
    class Message(
        val id: String,
        private val en: List<String>,
        private val he: List<String>,
    ) {
        val title get() = T(en[0], he[0])
        val text get() = T(en[1], he[1])
        val helpTitle get() = T(en[2], he[2])
        val help get() = T(en[3], he[3])
    }

    private val FIELDS = listOf("title", "text", "helpTitle", "help")

    // The message in the file when it's whole: an id and every field in English, each Hebrew one falling back to it.
    fun parse(json: String?): Message? = runCatching {
        val o = JSONObject(json ?: return null)
        val id = o.getString("id").trim().ifEmpty { return null }
        val en = o.getJSONObject("en").let { e -> FIELDS.map { e.getString(it).trim().ifEmpty { return null } } }
        val he = o.optJSONObject("he").let { h -> FIELDS.mapIndexed { i, f -> h?.optString(f)?.trim().orEmpty().ifEmpty { en[i] } } }
        Message(id, en, he)
    }.getOrNull()

    fun due(json: String?, closed: Set<String>): Message? = parse(json)?.takeIf { it.id !in closed }

    // Either button closes it: the coffee one opens Buy Me a Coffee first, and both record it as seen.
    fun close(m: Message, coffee: Boolean, open: (String) -> Unit, record: (String) -> Unit) {
        if (coffee) open(COFFEE_URL)
        record(m.id)
    }

    private fun store(ctx: Context) = ctx.getSharedPreferences("notice", Context.MODE_PRIVATE)

    // Small and in the APK, so read as Kav opens, before another popup can take the opening.
    fun notice(ctx: Context): Message? = due(
        runCatching { ctx.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() } }.getOrNull(),
        store(ctx).getStringSet("closed", null).orEmpty(),
    )

    // Seen: and "Enjoying Kav?", which asks for a coffee too, sits out the next opening (StartPopups).
    fun closed(ctx: Context, id: String) {
        val s = store(ctx)
        s.edit().putStringSet("closed", s.getStringSet("closed", null).orEmpty() + id).putBoolean("supportWaits", true).apply()
    }

    fun supportWaits(ctx: Context) = store(ctx).getBoolean("supportWaits", false)

    fun supportWaited(ctx: Context) = store(ctx).edit().remove("supportWaits").apply()
}
