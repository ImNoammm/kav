package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

// Fetch the metro's alert list and match ministry route names on the phone, without sending the trip.
object MoovitAlerts {
    // category: Moovit's MVServiceStatusCategory, 3 a change of service, 4 a disruption. label: what it is, "Detour".
    class Alert(val category: Int, val label: String, val ids: List<String>)

    class Notice(
        val id: String, val category: Int, val label: String, val title: String, val body: String, val html: Boolean,
        val activeFrom: Long, val activeTo: Long, val url: String,
    )

    var enabled by mutableStateOf(false)

    private class Board(val lines: Map<String, Alert>, val at: Long, val lang: String)
    private var board by mutableStateOf<Board?>(null)
    private val asking = AtomicBoolean(false)
    @Volatile private var failedAt = 0L

    private const val FRESH_S = 10 * 60L
    private const val RETRY_S = 2 * 60L

    private fun key(routeName: String) = routeName.trim().replace(Regex("\\s+"), " ")

    // Each way of each line with an alert. A way can be under two of Moovit's line groups, or one group listed twice
    // with two statuses: the worse status names it, and every alert behind it is kept.
    internal fun parse(o: JSONObject): Map<String, Alert> {
        val out = HashMap<String, Alert>()
        val lines = o.optJSONArray("lines") ?: return out
        for (i in 0 until lines.length()) {
            val a = lines.optJSONArray(i) ?: continue
            val category = a.optInt(3)
            val label = a.optString(4)
            val ids = a.optJSONArray(5)?.let { l -> (0 until l.length()).map { l.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            val names = a.optJSONArray(6) ?: continue
            for (n in 0 until names.length()) {
                val k = key(names.optString(n))
                if (k.isEmpty()) continue
                val had = out[k]
                out[k] = when {
                    had == null -> Alert(category, label, ids)
                    category > had.category -> Alert(category, label, (ids + had.ids).distinct())
                    else -> Alert(had.category, had.label, (had.ids + ids).distinct())
                }
            }
        }
        return out
    }

    internal fun parseNotices(o: JSONObject): List<Notice> {
        val list = o.optJSONArray("alerts") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { a ->
            Notice(
                a.optString("id"), a.optInt("category"), a.optString("label"), a.optString("title"), a.optString("body"),
                a.optBoolean("html"), a.optLong("from") / 1000, a.optLong("to") / 1000, a.optString("url"),
            )
        }
    }

    // The line's alert, once Moovit's list is in; nothing while it's switched off.
    fun of(line: Transit.LineInfo?): Alert? {
        if (!enabled) return null
        val name = line?.routeName?.let(::key)?.ifEmpty { null } ?: return null
        return board?.lines?.get(name)
    }

    // Moovit's list, at most every ten minutes, and in Kav's language, which its status labels come in. A failure waits
    // two minutes before the next try.
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val now = System.currentTimeMillis() / 1000
        val lang = if (uk.noammm.kav.ui.T.rtl) "he" else "en"
        board?.let { if (it.lang == lang && now - it.at < FRESH_S) return@withContext }
        if (now - failedAt < RETRY_S || !asking.compareAndSet(false, true)) return@withContext
        try {
            board = Board(parse(MoovitWebPage.alerts(JSONObject().put("kind", "metro"))), now, lang)
        } catch (e: Exception) {
            failedAt = now
        } finally {
            asking.set(false)
        }
    }

    // The notices behind an alert, in full. Throws when Moovit couldn't be asked.
    fun notices(ids: List<String>): List<Notice> {
        val asked = ids.filter { it.isNotBlank() }.distinct()
        if (asked.isEmpty()) return emptyList()
        return parseNotices(MoovitWebPage.alerts(JSONObject().put("kind", "details").put("ids", JSONArray(asked))))
    }
}
