package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ui.T
import java.util.Calendar
import java.util.TimeZone

// What a rider keeps at hand: stops with their next departures on the Stations tab, lines at the top of the
// Lines tab, and alerts that keep a stop's next buses of some lines in the notification shade on chosen days between
// two times.

// A stop as its code and platform name it: the timetable numbers its stops afresh each time it's built.
fun Net.stopKey(s: Int): String = "${code.getOrElse(s) { 0 }}/${platform.getOrElse(s) { "" }}"

// The timetable's stop a key names, or -1. A bus station's platforms share its code, and two stops can share both
// (one of a pair often with no departures at all): the one with the most departures.
fun Net.stopOfKey(key: String): Int {
    val c = key.substringBefore('/').toIntOrNull()?.takeIf { it > 0 } ?: return -1
    val p = key.substringAfter('/', "")
    return (0 until nStops).filter { code[it] == c && platform.getOrElse(it) { "" } == p }
        .maxByOrNull { dStart[it + 1] - dStart[it] } ?: -1
}

// The lines of a stop an alert is for, by number, on its days (bit 0 Sunday) between from and to, minutes of the day in
// Israel. A window that ends before it starts runs past midnight; one that ends as it starts lasts the whole day.
class LineAlert(
    val id: Long, val stop: String, val name: String, val lines: List<String>, val days: Int, val from: Int, val to: Int,
    // At a train station, which numbers no line, the trains it's for by where they end.
    val toward: List<End> = emptyList(),
) {
    // Where trains end: the timetable's name, with the stop code and place that tell it from a station of a like name.
    class End(val name: String, val code: Int, val lat: Double, val lon: Double) {
        fun json(): JSONObject = JSONObject().put("name", name).put("code", code).put("lat", lat).put("lon", lon)
    }

    val code: Int get() = stop.substringBefore('/').toIntOrNull() ?: 0

    fun json(): JSONObject = JSONObject().put("id", id).put("stop", stop).put("name", name)
        .put("lines", JSONArray(lines)).put("days", days).put("from", from).put("to", to)
        .apply { if (toward.isNotEmpty()) put("toward", JSONArray(toward.map { it.json() })) }

    // The days' windows around now, as epoch seconds, from the day before (one running past midnight) to a week on.
    private fun windows(now: Long): List<LongRange> {
        val c = Calendar.getInstance(ISRAEL_TIME)
        val out = ArrayList<LongRange>()
        for (d in -1..7) {
            c.timeInMillis = now * 1000
            c.add(Calendar.DAY_OF_YEAR, d)
            if (days and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)) == 0) continue
            c.set(Calendar.HOUR_OF_DAY, from / 60); c.set(Calendar.MINUTE, from % 60)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val start = c.timeInMillis / 1000
            if (to <= from) c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, to / 60); c.set(Calendar.MINUTE, to % 60)
            out += start until c.timeInMillis / 1000
        }
        return out
    }

    // The window now is in, or null.
    fun window(now: Long): LongRange? = windows(now).firstOrNull { now in it }

    // When the next window opens, after now.
    fun nextStart(now: Long): Long? = windows(now).map { it.first }.filter { it > now }.minOrNull()

    companion object {
        const val WEEKDAYS = 0b0011111

        fun of(o: JSONObject): LineAlert? {
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            val lines = strings("lines")
            val toward = o.optJSONArray("toward")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .filter { it.optString("name").isNotBlank() }
                .map { End(it.optString("name"), it.optInt("code"), it.optDouble("lat"), it.optDouble("lon")) }
            if ((lines.isEmpty() && toward.isEmpty()) || o.optString("stop").isBlank()) return null
            return LineAlert(
                o.optLong("id"), o.optString("stop"), o.optString("name"), lines, o.optInt("days", WEEKDAYS) and 0x7F,
                o.optInt("from").coerceIn(0, 1439), o.optInt("to").coerceIn(0, 1439), toward = toward,
            )
        }

        fun list(raw: String?): List<LineAlert> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::of) }
        }.getOrDefault(emptyList())

        fun json(alerts: List<LineAlert>): String = JSONArray(alerts.map { it.json() }).toString()
    }
}

private val ISRAEL_TIME: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

// When the alerts next need the phone: about a minute on while one is showing, to keep its times fresh, else when the
// next one opens. Null with none to wait for. One swiped away stays quiet until quietUntil. Android may hold an inexact
// alarm back by three quarters of the time until it, so a start far off is woken towards in steps that come in time
// however late they are.
fun nextWake(alerts: List<LineAlert>, now: Long, quietUntil: (LineAlert) -> Long = { 0L }): Long? {
    val at = alerts.mapNotNull { a -> if (a.window(now) != null && quietUntil(a) <= now) now + 60 else a.nextStart(now) }.minOrNull()
        ?: return null
    return if (at - now <= 120) at else now + (at - now) * 4 / 7
}

// A bus of an alert's lines due at its stop, live or by the timetable.
class Coming(val line: String, val at: Long, val live: Boolean)

// What an alert says: each line with its next three, soonest line first; minutes for a live time, the clock for the
// timetable's. Only what's due within the hour.
fun comingText(coming: List<Coming>, now: Long, clock: (Long) -> String): String {
    val soon = coming.filter { it.at >= now - 30 && it.at - now <= 3600 }.sortedBy { it.at }
    if (soon.isEmpty()) return T("Nothing due in the next hour", "אין יציאות בשעה הקרובה")
    return soon.groupBy { it.line }.entries.joinToString(" · ") { (line, buses) ->
        "$line: " + buses.take(3).joinToString(", ") { b ->
            if (!b.live) clock(b.at) else ((b.at - now + 30) / 60).let { m ->
                if (m < 1) T("now", "עכשיו") else T("$m min", "$m דק׳")
            }
        }
    }
}
