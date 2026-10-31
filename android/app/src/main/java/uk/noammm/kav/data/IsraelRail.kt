package uk.noammm.kav.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Israel Railways' own timetable: the trains between two of its stations on a day, with their changes,
// platforms and, for those running, how late they are, asked the way the railway's website asks it. Only when the rider
// asks for it: the railway learns the two stations and the day.
object IsraelRail {
    private const val SEARCH = "https://rail-api.rail.co.il/rjpa/api/v1/timetable/searchTrain"
    // The public key rail.co.il's own pages send with the same request.
    private const val KEY = "5e64d66cf03f4547bcac5de2de06b566"
    private val ISRAEL_TIME: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    // Each station's stop code in the ministry's timetable to the railway's own id: all 70, matched train by train (a
    // train of the same number calling at as many stations at the same times). Two codes name Hamifrats Central.
    private val STATIONS = mapOf(
        2643 to 4310, 2652 to 4300, 2653 to 3900, 17000 to 300, 17002 to 400, 17004 to 700, 17008 to 1220,
        17010 to 1300, 17012 to 1500, 17014 to 1600, 17016 to 2100, 17018 to 2200, 17020 to 2300, 17022 to 2500,
        17024 to 2800, 17026 to 2820, 17028 to 3100, 17030 to 3300, 17032 to 3400, 17034 to 3500, 17036 to 3600,
        17038 to 3700, 17040 to 4100, 17042 to 4170, 17044 to 4250, 17046 to 4600, 17048 to 4640, 17050 to 4660,
        17052 to 4680, 17054 to 4690, 17056 to 4800, 17058 to 5000, 17060 to 5010, 17062 to 5150, 17064 to 5200,
        17066 to 5300, 17068 to 5410, 17070 to 5800, 17072 to 5900, 17074 to 6300, 17080 to 7000, 17082 to 7300,
        17084 to 7320, 17086 to 7500, 17088 to 8550, 17090 to 8600, 17092 to 8700, 17094 to 8800, 17096 to 9000,
        17098 to 9100, 17100 to 9200, 17102 to 9800, 17104 to 4900, 17106 to 9600, 17108 to 9650, 17109 to 9700,
        17110 to 1240, 17111 to 1280, 17112 to 1260, 17113 to 1250, 17114 to 3310, 17115 to 1840, 17116 to 1820,
        17117 to 1400, 17118 to 680, 17119 to 6150, 17120 to 6900, 17121 to 2940, 17122 to 2960, 17123 to 1220
    )

    fun stationOf(code: Int): Int? = STATIONS[code]

    // A station's stop code, for its name in Kav's timetable.
    fun codeOf(station: Int): Int? = STATIONS.entries.firstOrNull { it.value == station }?.key

    // A train of a journey: its stations (the railway's ids), times as epoch seconds and platforms, whether it's boarded
    // on the same platform island the train before comes in at, and how many minutes late it is, once it runs: where it's
    // boarded, and where it's left.
    class Train(
        val number: Int, val from: Int, val to: Int, val dep: Long, val arr: Long,
        val fromPlatform: String, val toPlatform: String, val samePlatform: Boolean, val late: Int?,
        val lateAtEnd: Int? = late,
    )

    // A notice the railway shows with a journey, about some of its trains or, naming none, all of them.
    class Notice(val text: String, val trains: Set<Int>, val severe: Boolean)

    class Journey(val dep: Long, val arr: Long, val trains: List<Train>, val notices: List<Notice> = emptyList())

    private fun clock() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = ISRAEL_TIME }

    // The railway's answer: every journey between the two stations that day.
    fun parse(body: String): List<Journey> {
        val travels = JSONObject(body).optJSONObject("result")?.optJSONArray("travels") ?: return emptyList()
        val at = clock()
        fun time(s: String): Long? = runCatching { at.parse(s)!!.time / 1000 }.getOrNull()
        return (0 until travels.length()).mapNotNull { i ->
            val t = travels.optJSONObject(i) ?: return@mapNotNull null
            val list = t.optJSONArray("trains") ?: return@mapNotNull null
            val trains = (0 until list.length()).mapNotNull { j ->
                val x = list.optJSONObject(j) ?: return@mapNotNull null
                val from = x.optInt("orignStation")
                val to = x.optInt("destinationStation")
                // Its own forecast at a station, else how late it is now.
                val eta = x.optJSONArray("etaDiffTimes")?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()
                val now = x.optJSONObject("trainPosition")?.takeIf { it.has("calcDiffMinutes") }?.optInt("calcDiffMinutes")
                fun lateAt(station: Int) = eta.firstOrNull { it.optInt("stationId") == station }?.takeIf { it.has("difMin") }?.optInt("difMin") ?: now
                Train(
                    x.optInt("trainNumber"), from, to,
                    time(x.optString("departureTime")) ?: return@mapNotNull null,
                    time(x.optString("arrivalTime")) ?: return@mapNotNull null,
                    x.optInt("originPlatform").takeIf { it > 0 }?.toString().orEmpty(),
                    x.optInt("destPlatform").takeIf { it > 0 }?.toString().orEmpty(),
                    x.optString("isSamePlatformIsland") == "Yes", lateAt(from), lateAt(to),
                )
            }
            if (trains.isEmpty()) return@mapNotNull null
            Journey(
                time(t.optString("departureTime")) ?: trains.first().dep, time(t.optString("arrivalTime")) ?: trains.last().arr,
                trains, notices(t.optJSONArray("travelMessages")),
            )
        }.sortedBy { it.dep }
    }

    // A journey's notices, as the railway words them in the language asked for. Its title often only starts the text.
    private fun notices(list: org.json.JSONArray?): List<Notice> = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val m = list!!.optJSONObject(i) ?: return@mapNotNull null
        val text = m.optString("message").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
        val title = m.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" }?.trimEnd(',', ':')
        val trains = m.optString("trainNumber").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        Notice(if (title == null || text.startsWith(title)) text else "$title: $text", trains, m.optInt("sevirity") >= 2)
    }.distinctBy { it.text }

    // The journeys between two stations on the day of `atMs`, Israel time, with their notices in Kav's language.
    fun search(from: Int, to: Int, atMs: Long): List<Journey> {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ISRAEL_TIME }.format(Date(atMs))
        val hour = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = ISRAEL_TIME }.format(Date(atMs))
        val ask = JSONObject().put("fromStation", from.toString()).put("toStation", to.toString()).put("date", day)
            .put("hour", hour).put("scheduleType", "ByDeparture").put("systemType", "2")
            .put("languageId", if (uk.noammm.kav.ui.T.rtl) "Hebrew" else "English")
        val c = (URL(SEARCH).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("ocp-apim-subscription-key", KEY)
            // It turns away requests that don't come from a browser.
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            c.outputStream.use { it.write(ask.toString().toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException("Israel Railways HTTP ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
