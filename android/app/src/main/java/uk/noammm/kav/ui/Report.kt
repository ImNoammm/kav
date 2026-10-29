package uk.noammm.kav.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.RavPass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight

// Reporting a driver, a bus or an inspector to the National Public Transport Authority, on its own online form, which
// takes every bus company's lines. Kav walks the rider through it a step at a time from what it knows about the ride,
// then fills in the form in a page inside the app. The rider confirms their phone there with the code the form texts
// and taps Send: Kav itself sends nothing anywhere.

private const val FORM = "https://govforms.gov.il/mw/forms/PublicTransportRequest@mot.gov.il"
// Kav hands the rider's photos to its script at this address on the form's own site; nothing is fetched from there.
private const val PHOTOS = "https://govforms.gov.il/__kav/"

// The authority's subjects and sub-subjects for buses, as its form writes them.
private const val TIMES = "זמנים ואי עצירה"
private const val SERVICE = "תלונה על שירות אמצעי תחבורה"
private const val CONDUCT = "התנהגות נהג / פקח"
private const val CONDITION = "תקינות/נקיון"
private const val SIGNS = "תקינות ומידע בשלטים"
private const val ACCESS = "נגישות"
private const val FARES = "תעריפים ותשלומים"
private const val APPEAL = "ערעור על תעריף מוגדל"

// The form's report text holds 400 characters; it takes four attachments of up to 4 MB each, and reports up to two
// months after the event.
private const val MAX_TEXT = 400
private const val MAX_PHOTOS = 4
private const val MAX_PHOTO_BYTES = 3_900_000
private const val PHOTO_SIDE = 2560
private const val MAX_DAYS_BACK = 60

// A stop of the ride or of the bus's line: its id where Kav got it, its name, and the code on its sign, which is the
// ministry's own number for it.
internal class ReportStop(val id: Int, val name: String = "", val code: String? = null)

// What Kav knows about the bus being reported, from a ride or from Live.
internal class ReportFor(
    val line: String,
    // Moovit's name for the operator, Hebrew first: "מטרופולין (Metropoline)".
    val agency: String,
    // The towns the line runs between in its direction, as Moovit names them.
    val origin: String,
    val destination: String,
    // The stops of the ride, or of the bus's line, in order, and where the rider got on and off among them (-1 when
    // Kav doesn't know). Moovit's ids, unless Kav's server gave them.
    val stops: List<ReportStop> = emptyList(),
    val boarded: Int = -1,
    val alighted: Int = -1,
    val moovitStops: Boolean = true,
    val plate: String? = null,
    // When it happened, in seconds.
    val at: Long,
    val near: Pair<Double, Double>? = null,
    // The towns at both ends even of a line inside one, as the ministry's form names lines, and its own operator number
    // and direction ("2" and "#" for a line named "...-2#"): only Kav's server has them.
    val towns: Pair<String, String>? = null,
    val operatorId: Int? = null,
    val direction: Pair<String, String>? = null,
)

// The direction and alternative at the end of a line's name in the ministry's data, "...-2#" being ("2", "#").
internal fun ministryDirection(name: String): Pair<String, String>? {
    val end = name.substringAfterLast('-', "")
    return if (end.length >= 2 && end[0].isDigit()) end.take(1) to end.drop(1) else null
}

// The ride as the rider confirmed it: what Kav filled in, changed where it was wrong.
internal class Ride(
    val line: String,
    val from: ReportStop? = null,
    val to: ReportStop? = null,
    val plate: String = "",
    val at: Long,
)

internal enum class ReportAbout { DRIVER, BUS, INSPECTOR }

// What a report is about, each on one of the authority's own subjects for buses, with the sentence that opens its text.
internal enum class ReportIssue(
    val about: ReportAbout, private val en: String, private val he: String, private val enDetail: String,
    private val heDetail: String, val subject: String, val second: String, val sentence: String,
) {
    NOT_STOPPED(ReportAbout.DRIVER, "Didn't stop", "לא עצר",
        "Passed your stop without stopping to let you on.", "חלף על פני התחנה בלי לעצור ולפתוח דלתות.",
        TIMES, "אי עצירה בתחנה", "האוטובוס חלף על פני התחנה בלי לעצור ולפתוח דלתות."),
    RUDE(ReportAbout.DRIVER, "Was rude", "התנהג בגסות",
        "Shouted, insulted or treated you or others badly.", "צעק, העליב או התייחס אליכם או לאחרים בצורה לא נאותה.",
        SERVICE, CONDUCT, "הנהג התנהג בגסות."),
    DANGEROUS(ReportAbout.DRIVER, "Drove dangerously", "נהג בצורה מסוכנת",
        "Speeding, sharp braking, running a red light and the like.", "נסיעה מהירה, בלימות חדות, מעבר באדום וכדומה.",
        SERVICE, CONDUCT, "הנהג נהג בצורה מסוכנת."),
    PHONE(ReportAbout.DRIVER, "On the phone while driving", "דיבר בטלפון בזמן הנהיגה",
        "Talked or typed on a phone while driving.", "דיבר או הקליד בטלפון בזמן הנהיגה.",
        SERVICE, CONDUCT, "הנהג השתמש בטלפון בזמן הנהיגה."),
    NO_HELP(ReportAbout.DRIVER, "Didn't help with accessibility", "לא סייע בנגישות",
        "Didn't lower the ramp or help someone with a disability.", "לא הוריד את הרמפה או לא עזר לנוסע עם מוגבלות.",
        SERVICE, ACCESS, "הנהג לא סייע לנוסע עם מוגבלות."),
    DRIVER_OTHER(ReportAbout.DRIVER, "Something else", "משהו אחר",
        "Anything else about how the driver behaved.", "כל דבר אחר בהתנהגות הנהג.",
        SERVICE, CONDUCT, "תלונה על הנהג."),
    NO_SHOW(ReportAbout.BUS, "Didn't come", "לא הגיע",
        "The bus never reached the stop.", "האוטובוס לא הגיע לתחנה בכלל.",
        TIMES, "אי הגעה", "האוטובוס לא הגיע לתחנה."),
    LATE(ReportAbout.BUS, "Late or early", "איחר או הקדים",
        "It came late, or left early, against the timetable.", "הגיע באיחור או יצא מוקדם, בניגוד ללוח הזמנים.",
        TIMES, "הקדמה/ איחור", "האוטובוס איחר או הקדים, בניגוד ללוח הזמנים."),
    CROWDED(ReportAbout.BUS, "Too crowded", "עמוס מדי",
        "A regular ride that's packed far beyond normal.", "נסיעה קבועה שעמוסה הרבה מעבר לרגיל.",
        TIMES, "עומס נוסעים", "האוטובוס היה עמוס מאוד."),
    DIRTY(ReportAbout.BUS, "Dirty or broken", "מלוכלך או תקול",
        "Something dirty or broken on the bus.", "משהו מלוכלך או שבור באוטובוס.",
        SERVICE, CONDITION, "האוטובוס היה מלוכלך או תקול."),
    WRONG_SIGNS(ReportAbout.BUS, "Wrong signs", "שלטים שגויים",
        "Its signs or screens were wrong or not working.", "השלטים או המסכים בו היו שגויים או לא עבדו.",
        SERVICE, SIGNS, "השלטים באוטובוס היו שגויים או לא עבדו."),
    NOT_ACCESSIBLE(ReportAbout.BUS, "Accessibility", "נגישות",
        "A ramp, seat or other accessibility that didn't work.", "רמפה, מושב או אמצעי נגישות אחר שלא עבד.",
        SERVICE, ACCESS, "יש בעיית נגישות באוטובוס."),
    WRONG_FARE(ReportAbout.BUS, "Charged the wrong fare", "חויבתי בתעריף שגוי",
        "You paid for this ride and were charged too much, or twice.", "שילמתם על הנסיעה וחויבתם ביותר מדי, או פעמיים.",
        FARES, "חיוב שגוי", "חויבתי בתעריף שגוי על הנסיעה."),
    INSPECTOR_RUDE(ReportAbout.INSPECTOR, "Rude or aggressive", "גס או אלים",
        "Rude, aggressive or threatening.", "התנהג בגסות, בתוקפנות או באיומים.",
        SERVICE, CONDUCT, "הפקח התנהג בגסות או באלימות."),
    FINE_APPEAL(ReportAbout.INSPECTOR, "Appeal a fine", "ערעור על קנס",
        "An inspector gave you a demand to pay a higher fare, and you think it's wrong.",
        "פקח נתן לכם דרישה לתשלום תעריף מוגדל, ולדעתכם היא שגויה.",
        FARES, APPEAL, "ברצוני לערער על דרישה לתשלום תעריף נסיעה מוגדל."),
    INSPECTOR_OTHER(ReportAbout.INSPECTOR, "Something else", "משהו אחר",
        "Anything else about how an inspector acted.", "כל דבר אחר בהתנהגות הפקח.",
        SERVICE, CONDUCT, "תלונה על פקח.");

    val label get() = T(en, he)
    val detail get() = T(enDetail, heDetail)
    // The form asks at which stop it happened, from the line's own stops.
    val atStop get() = subject == TIMES
    // It asks whether it was on the bus or at a stop: on the bus, for these.
    val onBus get() = second == CONDITION || second == SIGNS || second == ACCESS
    // Behaviour reports need the rider's answers about recordings, witnesses and testimony.
    val conduct get() = second == CONDUCT
    // Fares and fines: the form asks how the rider paid, and when they paid or got on.
    val payment get() = subject == FARES
    // An appeal goes by the number on the inspector's demand.
    val appeal get() = second == APPEAL
}

// The rider's own details, which the authority asks for: it doesn't handle anonymous reports. They stay on the phone,
// sealed with the key payments use, and only ever go into the authority's form.
internal data class Reporter(
    val first: String = "", val last: String = "", val id: String = "", val gender: String = "",
    val phone: String = "", val email: String = "", val town: String = "",
) {
    companion object {
        private const val KEY = "reporter"
        private fun store(ctx: Context) = ctx.getSharedPreferences("report", Context.MODE_PRIVATE)

        fun load(ctx: Context): Reporter? = store(ctx).getString(KEY, null)?.let(Seal::open)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                Reporter(o.optString("first"), o.optString("last"), o.optString("id"), o.optString("gender"),
                    o.optString("phone"), o.optString("email"), o.optString("town"))
            }.getOrNull()
        }

        fun save(ctx: Context, r: Reporter) {
            val json = JSONObject().put("first", r.first).put("last", r.last).put("id", r.id).put("gender", r.gender)
                .put("phone", r.phone).put("email", r.email).put("town", r.town).toString()
            store(ctx).edit().putString(KEY, Seal.close(json)).apply()
        }

        fun forget(ctx: Context) = store(ctx).edit().remove(KEY).apply()
    }
}

// The rider's answers to the questions the authority asks about behaviour, as its form numbers them.
internal class Answers(
    val recorded: String = "", val witnesses: String = "", val testifyAt: String = "", val willing: Boolean = false,
)

// How the rider paid for a fare or fine report, as the form numbers it (1 Rav-Kav, 2 an app, 3 a credit card; apps 1
// Rav-Pass, 2 Moovit, 3 Pango, 4 Cello), when they paid or got on, and an appeal's demand number.
internal class Payment(
    val means: String = "", val ravKav: String = "", val cardholder: String = "", val app: String = "",
    val appPhone: String = "", val paidAt: Long = 0, val demand: String = "",
)

private val FINAL_TO_PLAIN = mapOf('ם' to 'מ', 'ן' to 'נ', 'ך' to 'כ', 'ף' to 'פ', 'ץ' to 'צ')
private val FINAL_BEFORE_LETTER = Regex("[םןךףץ](?=[א-ת])")

// The form takes only plain characters and Hebrew letters in its text, never & < >, and a final letter only at the
// end of a word.
internal fun formText(s: String): String {
    val mapped = buildString {
        for (c in s) when (c) {
            '׳', '‘', '’', '‚', '`' -> append('\'')
            '״', '“', '”', '„' -> append('"')
            '…' -> append("...")
            '&', '<', '>' -> {}
            '\n' -> append('\n')
            else -> when {
                c.category == CharCategory.DASH_PUNCTUATION -> append('-')
                c.isWhitespace() -> append(' ')
                c.code in 0x20..0x7E || c in 'א'..'ת' -> append(c)
            }
        }
    }
    return FINAL_BEFORE_LETTER.replace(mapped) { FINAL_TO_PLAIN.getValue(it.value[0]).toString() }
        .replace(Regex(" +"), " ").replace(Regex(" *\n *"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

// A name as the form takes it: Hebrew letters, 2 to 25 with spaces, dashes and quotes.
internal fun nameText(s: String): String = s.trim().replace('׳', '\'').replace('״', '"').replace(Regex(" +"), " ")

internal fun hebrewName(s: String): Boolean {
    val t = nameText(s)
    return t.length in 2..25 && t.any { it in 'א'..'ת' } && !FINAL_BEFORE_LETTER.containsMatchIn(t) &&
        t.all { it in 'א'..'ת' || it in " -'\"()" }
}

// An Israeli ID number with its check digit, as the form checks it.
internal fun validId(id: String): Boolean {
    if (id.isEmpty() || id.length > 9 || !id.all(Char::isDigit) || id.all { it == '0' }) return false
    val sum = id.padStart(9, '0').mapIndexed { i, c -> (c - '0') * (i % 2 + 1) }.sumOf { if (it > 9) it - 9 else it }
    return sum % 10 == 0
}

// A mobile number the way the form takes it, ten digits from 0, from however it was typed.
internal fun formPhone(p: String): String? {
    var d = p.filter(Char::isDigit)
    if (d.startsWith("00972")) d = d.drop(5) else if (d.startsWith("972")) d = d.drop(3)
    if (!d.startsWith("0")) d = "0$d"
    return d.takeIf { it.length == 10 }
}

internal fun validEmail(s: String): Boolean = s.trim().length in 6..50 &&
    Regex("^\\w+([-+.]\\w+)*@\\w+([-.]\\w+)*\\.[a-zA-Z0-9-]{2,9}$").matches(s.trim())

// The operator as the authority's list writes it, without Moovit's English in brackets.
internal fun formOperator(agency: String): String = agency.substringBefore(" (").trim()

// The line's number as the form takes it: digits only, 1 to 998.
internal fun formLine(line: String): String? = line.filter(Char::isDigit).takeIf { it.toIntOrNull() in 1..998 }

// The authority takes testimony in Jerusalem, Tel Aviv or Haifa: the one nearest to where it happened.
internal fun nearestTestimony(at: Pair<Double, Double>?): String {
    val (lat, lon) = at ?: return "2"
    return listOf("1" to (31.778 to 35.235), "2" to (32.080 to 34.781), "3" to (32.794 to 34.990))
        .minBy { (_, c) -> metres(lat, lon, c.first, c.second) }.first
}

private fun reportFacts(issue: ReportIssue, r: ReportFor, ride: Ride, inspector: String): String = listOfNotNull(
    "קו ${formText(ride.line)}" +
        (formText(formOperator(r.agency)).ifBlank { null }?.let { " של $it" } ?: "") +
        (formText(r.destination).ifBlank { null }?.let { " לכיוון $it" } ?: "") + ".",
    ride.from?.name?.let(::formText)?.ifBlank { null }?.let { from ->
        if (issue.atStop) "בתחנה $from."
        else "עליתי בתחנה $from" + (ride.to?.name?.let(::formText)?.ifBlank { null }?.let { " ונסעתי אל $it" } ?: "") + "."
    },
    formText(ride.plate).ifBlank { null }?.let { "מספר רישוי $it." },
    inspector.filter(Char::isDigit).ifBlank { null }?.let { "מספר הפקח: $it." },
).joinToString("\n")

// How long the rider's own words can be, beside what Kav writes.
internal fun reportRoom(issue: ReportIssue, r: ReportFor, ride: Ride, inspector: String = ""): Int =
    (MAX_TEXT - issue.sentence.length - reportFacts(issue, r, ride, inspector).length - 2).coerceAtLeast(0)

// The report's text: what happened in the authority's words, the rider's own, then the ride, within the form's 400
// characters. The rider's words give way first.
internal fun reportText(issue: ReportIssue, words: String, r: ReportFor, ride: Ride, inspector: String = ""): String {
    val room = reportRoom(issue, r, ride, inspector)
    val own = formText(words).let { if (it.length > room) it.take(room).trimEnd() else it }
    return listOf(issue.sentence, own, reportFacts(issue, r, ride, inspector)).filter { it.isNotBlank() }
        .joinToString("\n").take(MAX_TEXT)
}

private fun israel(pattern: String, at: Long) =
    SimpleDateFormat(pattern, Locale.US).apply { timeZone = ISRAEL }.format(Date(at * 1000))

// What assets/report.js puts in the form.
internal fun reportJson(
    issue: ReportIssue, r: ReportFor, ride: Ride, text: String, me: Reporter, answers: Answers, consent: Boolean,
    photos: List<String> = emptyList(), payment: Payment = Payment(),
): String = JSONObject()
    .put("subject", issue.subject).put("second", issue.second).put("location", if (issue.onBus) "1" else "")
    .put("date", israel("dd/MM/yyyy", ride.at)).put("time", israel("HH:mm", ride.at))
    .put("line", formLine(ride.line).orEmpty()).put("operator", formOperator(r.agency))
    .put("from", r.towns?.first?.ifBlank { null } ?: r.origin).put("to", r.towns?.second?.ifBlank { null } ?: r.destination)
    .put("operatorId", r.operatorId?.toString().orEmpty())
    .put("direction", r.direction?.first.orEmpty()).put("alternative", r.direction?.second.orEmpty())
    .put("stop", if (issue.atStop) ride.from?.code.orEmpty() else "")
    .put("stopName", if (issue.atStop) ride.from?.name.orEmpty() else "")
    .put("plate", ride.plate.filter(Char::isDigit).takeIf { it.length in 5..8 }.orEmpty())
    .put("conduct", issue.conduct).put("willing", answers.willing).put("recorded", answers.recorded)
    .put("witnesses", answers.witnesses).put("testifyAt", answers.testifyAt)
    .put("demand", if (issue.appeal) payment.demand.filter(Char::isDigit) else "")
    .put("paymentMeans", if (issue.payment) payment.means else "")
    .put("ravKav", if (issue.payment && payment.means == "1") payment.ravKav.filter(Char::isDigit) else "")
    .put("cardholder", if (issue.payment && payment.means != "2") nameText(payment.cardholder) else "")
    .put("paymentApp", if (issue.payment && payment.means == "2") payment.app else "")
    .put("appPhone", if (issue.payment && payment.means == "2") formPhone(payment.appPhone).orEmpty() else "")
    .put("validation", if (issue.payment && payment.paidAt > 0) israel("HH:mm", payment.paidAt) else "")
    .put("details", text)
    .put("photos", JSONArray(photos))
    .put("id", me.id.padStart(9, '0')).put("last", me.last).put("first", me.first).put("gender", me.gender)
    .put("phone", me.phone).put("email", me.email).put("city", me.town)
    .put("consent", consent)
    .toString()

// When the rider paid for this line with Rav-Pass in Kav: the ticket for it bought nearest the time, within three hours.
internal fun paidWith(r: ReportFor, tickets: List<RavPass.Ticket>): Long? {
    val number = r.line.filter(Char::isLetterOrDigit)
    return tickets.map { it.line.filter(Char::isLetterOrDigit) to it.boughtUtc / 1000 }
        .filter { (line, at) -> line == number && kotlin.math.abs(at - r.at) < 3 * 3600 }
        .minByOrNull { (_, at) -> kotlin.math.abs(at - r.at) }?.second
}

// A report about the bus of a ride, the one the rider is on when Kav knows it, from where they got on to where they
// got off.
internal fun rideReport(ride: Moovit.Leg, r: Moovit.Resolved, bus: Moovit.Arrival?, at: Long): ReportFor? {
    if (legMode(ride, r) != Mode.BUS) return null
    val line = r.line(ride.lineId)
    val stops = ride.stops.map { id -> r.stop(id).let { ReportStop(id, it?.name.orEmpty(), it?.code?.ifBlank { null }) } }
    val from = r.stop(ride.fromStop)
    return ReportFor(
        line = ride.shortName.ifBlank { line?.number.orEmpty() }.ifBlank { return null },
        agency = r.agencyName(line?.agencyId ?: -1).orEmpty(),
        origin = line?.origin.orEmpty(), destination = line?.destination.orEmpty(),
        stops = stops,
        boarded = stops.indexOfFirst { it.id == ride.fromStop }.takeIf { it >= 0 } ?: if (stops.isEmpty()) -1 else 0,
        alighted = stops.indexOfLast { it.id == ride.toStop }.takeIf { it >= 0 } ?: stops.lastIndex,
        plate = bus?.let { KavLive.plateOf(it.vehicleId) },
        at = at, near = from?.point ?: ride.shape.firstOrNull(),
    )
}

// Seconds of a day so many days back, at an hour and minute, in Israel.
private fun momentOf(daysBack: Int, hour: Int, minute: Int): Long = Calendar.getInstance(ISRAEL).apply {
    add(Calendar.DAY_OF_YEAR, -daysBack)
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun israelDay(seconds: Long): Long {
    val ms = seconds * 1000
    return (ms + ISRAEL.getOffset(ms)) / 86_400_000L
}

internal fun daysBack(at: Long, now: Long = System.currentTimeMillis() / 1000): Int = (israelDay(now) - israelDay(at)).toInt()

private fun dayName(back: Int, at: Long): String = when (back) {
    0 -> T("Today", "היום")
    1 -> T("Yesterday", "אתמול")
    else -> SimpleDateFormat("EEE d MMM", T.locale).apply { timeZone = ISRAEL }.format(Date(at * 1000))
}

private fun momentLabel(at: Long): String = dayName(daysBack(at), at) + ", " + clockFormat().format(Date(at * 1000))

private fun aboutLabel(a: ReportAbout) = when (a) {
    ReportAbout.DRIVER -> T("The driver", "הנהג")
    ReportAbout.BUS -> T("The bus", "האוטובוס")
    ReportAbout.INSPECTOR -> T("An inspector", "פקח")
}

private fun aboutDetail(a: ReportAbout) = when (a) {
    ReportAbout.DRIVER -> T("Didn't stop, was rude, drove dangerously…", "לא עצר, התנהג בגסות, נהג בצורה מסוכנת…")
    ReportAbout.BUS -> T("Didn't come, was late, crowded, broken, charged wrongly…", "לא הגיע, איחר, עמוס, תקול, חיוב שגוי…")
    ReportAbout.INSPECTOR -> T("How an inspector behaved, or appealing their fine.", "איך פקח התנהג, או ערעור על קנס שנתן.")
}

private fun genderName(code: String) = when (code) {
    "1" -> T("Male", "זכר"); "2" -> T("Female", "נקבה"); "3" -> T("Other", "אחר"); else -> null
}

private fun testimonyCity(code: String) = when (code) {
    "1" -> T("Jerusalem", "ירושלים"); "2" -> T("Tel Aviv", "תל אביב"); "3" -> T("Haifa", "חיפה"); else -> null
}

private enum class ReportStep { ABOUT, WHAT, RIDE, MORE, YOU, CHECK }

// A photo the rider picked, already a JPEG the form takes.
internal class ReportPhoto(val file: File, val thumb: ImageBitmap)

// What the form's page needs: the photos to hand its script, and the report, made once the page knows where it hands
// them over.
internal class Filling(val photos: List<File>, val json: (photoUrls: List<String>) -> String)

// The report as the rider fills it in, a step at a time.
private class Draft(val report: ReportFor, saved: Reporter?, account: RavPass.Account?) {
    var step by mutableStateOf(ReportStep.ABOUT)
    var about by mutableStateOf<ReportAbout?>(null)
    var issue by mutableStateOf<ReportIssue?>(null)
    var line by mutableStateOf(report.line)
    var boardedAt by mutableIntStateOf(report.boarded)
    var alightedAt by mutableIntStateOf(report.alighted)
    // Where the rider got on and off, typed, when Kav has no stops to choose from.
    var boardedTyped by mutableStateOf("")
    var alightedTyped by mutableStateOf("")
    var plate by mutableStateOf(report.plate.orEmpty())
    var at by mutableLongStateOf(report.at)
    var words by mutableStateOf("")
    var inspector by mutableStateOf("")
    var recorded by mutableStateOf("")
    var witnesses by mutableStateOf("")
    var testifyAt by mutableStateOf(nearestTestimony(report.near))
    var willing by mutableStateOf(false)
    var photos by mutableStateOf(emptyList<ReportPhoto>())
    // How the rider paid, for fares and fines. Riders who pay with Rav-Pass in Kav start from that, at the time of the
    // ticket Kav bought for this line, if there's one.
    var demand by mutableStateOf("")
    var means by mutableStateOf(if (account != null) "2" else "")
    var ravKav by mutableStateOf("")
    var cardholder by mutableStateOf("")
    var app by mutableStateOf(if (account != null) "1" else "")
    var appPhone by mutableStateOf(account?.phone?.let(::formPhone).orEmpty())
    var paidAt by mutableLongStateOf(paidWith(report, Payer.tickets) ?: report.at)
    var first by mutableStateOf(saved?.first ?: account?.first?.takeIf(::hebrewName).orEmpty())
    var last by mutableStateOf(saved?.last ?: account?.last?.takeIf(::hebrewName).orEmpty())
    var id by mutableStateOf(saved?.id.orEmpty())
    var gender by mutableStateOf(saved?.gender.orEmpty())
    var phone by mutableStateOf(saved?.phone ?: account?.phone?.let(::formPhone).orEmpty())
    var email by mutableStateOf(saved?.email ?: account?.email.orEmpty())
    var town by mutableStateOf(saved?.town.orEmpty())
    var consent by mutableStateOf(false)
    var kept by mutableStateOf(saved != null)
    var choosingTime by mutableStateOf(false)
    var choosingPaid by mutableStateOf(false)
    // Whether the rider tried to go on from the step shown: from then on what's wrong there is marked, and unmarked as
    // soon as it's put right.
    var tried by mutableStateOf(false)
    val errors: Map<String, String> get() = if (tried) problems(step) else emptyMap()

    fun go(to: ReportStep) { tried = false; step = to }

    fun back(): Boolean {
        if (step == ReportStep.ABOUT) return false
        go(ReportStep.entries[step.ordinal - 1])
        return true
    }

    // Goes on only once the step has nothing wrong.
    fun next(then: () -> Unit) {
        tried = true
        if (problems(step).isEmpty()) then()
    }

    private fun problems(s: ReportStep): Map<String, String> = when (s) {
        ReportStep.RIDE -> rideErrors()
        ReportStep.MORE -> moreErrors()
        ReportStep.YOU -> youErrors()
        else -> emptyMap()
    }

    fun rideErrors(): Map<String, String> = buildMap {
        if (formLine(line) == null) put("line", T("Enter the line's number.", "הזינו את מספר הקו."))
        val i = issue ?: return@buildMap
        if (i.appeal && demand.filter(Char::isDigit).isEmpty()) put("demand", T("The number at the top of the demand.", "המספר שבראש הדרישה."))
        if (!i.payment) return@buildMap
        when (means) {
            "" -> put("means", T("Choose how you paid.", "בחרו איך שילמתם."))
            "1" -> if (ravKav.filter(Char::isDigit).length !in 2..10) put("ravKav", T("The number on your Rav-Kav card, up to 10 digits.", "המספר על כרטיס הרב-קו, עד 10 ספרות."))
            "2" -> {
                if (app.isEmpty()) put("app", T("Choose the app.", "בחרו את היישומון."))
                if (formPhone(appPhone) == null) put("appPhone", T("A mobile number, 10 digits.", "מספר נייד, 10 ספרות."))
            }
        }
        if (means != "2" && cardholder.isNotBlank() && !hebrewName(cardholder))
            put("cardholder", T("In Hebrew letters, or leave it empty.", "באותיות עבריות, או השאירו ריק."))
    }

    fun payment() = Payment(means, ravKav, cardholder, app, appPhone, paidAt, demand)

    fun moreErrors(): Map<String, String> = buildMap {
        if (issue?.conduct != true) return@buildMap
        val ask = T("The ministry asks this.", "משרד התחבורה שואל את זה.")
        if (recorded.isEmpty()) put("recorded", ask)
        if (witnesses.isEmpty()) put("witnesses", ask)
        if (!willing) put("willing", T("The ministry handles these reports only from riders willing to give testimony.",
            "משרד התחבורה מטפל בדיווחים כאלה רק כשהמדווחים מוכנים למסור עדות."))
    }

    fun youErrors(): Map<String, String> = buildMap {
        val hebrew = T("In Hebrew letters, as on your ID card.", "באותיות עבריות, כמו בתעודת הזהות.")
        if (!hebrewName(first)) put("first", hebrew)
        if (!hebrewName(last)) put("last", hebrew)
        if (!validId(id)) put("id", T("That isn't a valid ID number: 9 digits with the check digit.", "מספר הזהות לא תקין: 9 ספרות כולל ספרת הביקורת."))
        if (gender.isEmpty()) put("gender", T("Choose your gender.", "בחרו מין."))
        if (formPhone(phone) == null) put("phone", T("A mobile number, 10 digits.", "מספר נייד, 10 ספרות."))
        if (!validEmail(email)) put("email", T("Enter a valid email address.", "הזינו כתובת דואר אלקטרוני תקינה."))
        if (town.isBlank()) put("town", T("Enter the town you live in.", "הזינו את יישוב המגורים."))
        if (!consent) put("consent", T("The authority needs this to handle the report.", "הרשות צריכה את זה כדי לטפל בפנייה."))
    }

    fun me() = Reporter(nameText(first), nameText(last), id.trim(), gender, formPhone(phone).orEmpty(), email.trim(), town.trim())

    fun ride(named: (ReportStop) -> ReportStop): Ride {
        fun stop(i: Int, typed: String) = report.stops.getOrNull(i)?.let(named)
            ?: typed.trim().ifBlank { null }?.let { ReportStop(-1, it) }
        return Ride(line.trim(), stop(boardedAt, boardedTyped), stop(alightedAt, alightedTyped), plate.trim(), at)
    }
}

@Composable
internal fun ReportScreen(report: ReportFor, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = remember(report) { Draft(report, Reporter.load(ctx), Payer.account) }
    var filling by remember { mutableStateOf<Filling?>(null) }
    // The names of stops that came without one, and the code on the sign of the stop chosen, asked of Moovit.
    val asked = rememberStopNames(
        if (!report.moovitStops) emptyList()
        else report.stops.filterIndexed { i, s -> s.name.isBlank() || (s.code == null && i == d.boardedAt) }.map { it.id },
    )
    val named: (ReportStop) -> ReportStop = { s ->
        asked[s.id]?.let { ReportStop(s.id, s.name.ifBlank { it.name }, s.code ?: it.code.ifBlank { null }) } ?: s
    }
    DisposableEffect(Unit) {
        clearPhotos(ctx)
        onDispose { clearPhotos(ctx) }
    }
    BackHandler { if (!d.back()) onClose() }
    Box(Modifier.fillMaxSize().background(K.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(T("Report", "דיווח"), T("to the ministry", "למשרד התחבורה"), back = { if (!d.back()) onClose() })
            StepBar(d.step)
            AnimatedContent(
                d.step, transitionSpec = { if (targetState > initialState) forward() else backward() },
                modifier = Modifier.weight(1f), label = "reportStep",
            ) { step ->
                when (step) {
                    ReportStep.ABOUT -> AboutStep(d)
                    ReportStep.WHAT -> WhatStep(d)
                    ReportStep.RIDE -> RideStep(d, named)
                    ReportStep.MORE -> MoreStep(d, named)
                    ReportStep.YOU -> YouStep(d)
                    ReportStep.CHECK -> CheckStep(d, named) { filling = it }
                }
            }
        }
        // Sheets cover the whole screen, so they're drawn here rather than in a step's scrolling page.
        if (d.choosingTime) MomentSheet(d.at, T("When it happened", "מתי זה קרה"), onPick = { d.at = it }) { d.choosingTime = false }
        // The time paid is on the day it happened.
        if (d.choosingPaid) MomentSheet(d.paidAt, T("When you paid or got on", "מתי תיקפתם או עליתם"), withDay = false,
            day = d.at, onPick = { d.paidAt = it }) { d.choosingPaid = false }
        filling?.let { MinistryForm(it, onBack = { filling = null }, onDone = onClose) }
    }
}

@Composable
private fun AboutStep(d: Draft) = StepPage(
    T("What do you want to report?", "על מה תרצו לדווח?"),
    T("Kav fills in the National Public Transport Authority's form for you. It takes every bus company's lines.",
        "Kav ממלאת בשבילכם את הטופס של הרשות הארצית לתחבורה ציבורית. הוא מקבל את הקווים של כל חברות האוטובוסים."),
) {
    BusCard(d.report)
    ReportAbout.entries.forEach { a ->
        Choice(aboutLabel(a), aboutDetail(a), d.about == a) {
            if (d.issue?.about != a) d.issue = null
            d.about = a
            d.go(ReportStep.WHAT)
        }
    }
}

@Composable
private fun WhatStep(d: Draft) = StepPage(
    T("What happened?", "מה קרה?"),
    T("Choose what fits best. You can tell them more in a moment.", "בחרו את מה שהכי מתאים. בהמשך אפשר לספר עוד."),
) {
    ReportIssue.entries.filter { it.about == d.about }.forEach { i ->
        Choice(i.label, i.detail, d.issue == i) {
            d.issue = i
            d.go(ReportStep.RIDE)
        }
    }
}

@Composable
private fun RideStep(d: Draft, named: (ReportStop) -> ReportStop) {
    val issue = d.issue ?: return
    StepPage(
        T("The ride", "הנסיעה"),
        if (d.report.boarded >= 0) T("Kav filled this in from your ride. Change anything that's wrong.", "Kav מילאה את זה מהנסיעה שלכם. שנו כל מה שלא נכון.")
        else T("Kav filled in what it knows about this bus. Add where you got on if you know it.", "Kav מילאה את מה שהיא יודעת על האוטובוס. הוסיפו איפה עליתם, אם אתם יודעים."),
        action = T("Next", "הבא"), errors = d.errors,
        onAction = { d.next { d.go(ReportStep.MORE) } },
    ) {
        Field(T("Line", "קו"), d.line, { d.line = it.take(6) }, error = d.errors["line"])
        if (issue.atStop) {
            StopField(T("Stop where it happened", "התחנה שבה זה קרה"), d.report.stops, d.boardedAt, d.boardedTyped, named,
                onPick = { d.boardedAt = it }, onType = { d.boardedTyped = it })
        } else {
            StopField(T("Where you got on", "איפה עליתם"), d.report.stops, d.boardedAt, d.boardedTyped, named,
                onPick = { i -> d.boardedAt = i; if (d.alightedAt in 0..i) d.alightedAt = -1 }, onType = { d.boardedTyped = it })
            StopField(T("Where you got off", "איפה ירדתם"), d.report.stops, d.alightedAt, d.alightedTyped, named,
                after = d.boardedAt, onPick = { d.alightedAt = it }, onType = { d.alightedTyped = it })
        }
        Field(T("Number plate", "מספר רישוי"), d.plate, { d.plate = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
            placeholder = "12-345-67", helper = T("On the bus's front and back, if you saw it", "בחזית ובגב האוטובוס, אם ראיתם"),
            keyboard = KeyboardType.Phone)
        WhenField(T("When it happened", "מתי זה קרה"), momentLabel(d.at)) { d.choosingTime = true }
        if (issue.appeal) {
            Heading(T("The demand", "הדרישה"))
            Hint(true, T("Appeals go to the National Transport Supervisor, within 60 days of the demand. The form asks for a " +
                "photo of it too, which you can add in the next step.", "ערעורים עוברים למפקח הארצי על התעבורה, בתוך 60 יום " +
                "מקבלת הדרישה. הטופס מבקש גם צילום שלה, ואפשר להוסיף אותו בשלב הבא."))
            Field(T("Demand number", "מספר הדרישה"), d.demand, { d.demand = it.filter(Char::isDigit).take(16) },
                helper = T("At the top of the demand the inspector gave you", "בראש הדרישה שקיבלתם מהפקח"),
                error = d.errors["demand"], keyboard = KeyboardType.Number)
        }
        if (issue.payment) Paying(d)
    }
}

// How the rider paid, for a fare or a fine, and when.
@Composable
private fun Paying(d: Draft) {
    Heading(T("How you paid", "איך שילמתם"))
    Dropdown(T("Paid with", "שילמתם עם"), meansName(d.means), T("Choose how you paid", "בחרו איך שילמתם"),
        listOf("1", "2", "3").map { it to meansName(it).orEmpty() }, d.errors["means"]) { d.means = it }
    AnimatedVisibility(d.means == "1", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Field(T("Rav-Kav number", "מספר הרב-קו"), d.ravKav, { d.ravKav = it.filter(Char::isDigit).take(10) },
            helper = T("On the back of the card", "בגב הכרטיס"), error = d.errors["ravKav"], keyboard = KeyboardType.Number)
    }
    AnimatedVisibility(d.means == "2", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            Dropdown(T("App", "יישומון"), appName(d.app), T("Choose the app", "בחרו יישומון"),
                listOf("1", "2", "3", "4").map { it to appName(it).orEmpty() }, d.errors["app"]) { d.app = it }
            Field(T("Phone number in the app", "מספר הטלפון ביישומון"), d.appPhone,
                { d.appPhone = it.filter { c -> c.isDigit() || c == '-' || c == '+' }.take(16) },
                error = d.errors["appPhone"], keyboard = KeyboardType.Phone)
        }
    }
    AnimatedVisibility(d.means == "1" || d.means == "3", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Field(T("Name on the card", "שם בעל הכרטיס"), d.cardholder, { d.cardholder = it.take(25) },
            helper = T("Optional, in Hebrew", "לא חובה, בעברית"), error = d.errors["cardholder"])
    }
    WhenField(T("When you paid or got on", "מתי תיקפתם או עליתם"), clockFormat().format(Date(d.paidAt * 1000))) { d.choosingPaid = true }
}

private fun meansName(code: String) = when (code) {
    "1" -> T("A Rav-Kav card", "כרטיס רב-קו"); "2" -> T("A payment app", "יישומון תשלום"); "3" -> T("A credit card", "כרטיס אשראי")
    else -> null
}

private fun appName(code: String) = when (code) {
    "1" -> T("Rav-Pass", "רב-פס"); "2" -> T("Moovit", "מוביט"); "3" -> T("Pango", "פנגו"); "4" -> T("Cello", "סלו"); else -> null
}

@Composable
private fun MoreStep(d: Draft, named: (ReportStop) -> ReportStop) {
    val issue = d.issue ?: return
    StepPage(
        T("Tell them more", "ספרו עוד"),
        when {
            issue.conduct -> T("For a report like this the ministry needs a few answers.", "בדיווח כזה משרד התחבורה צריך כמה תשובות.")
            issue.appeal -> T("Explain why the demand is wrong, and add a photo of it.", "הסבירו למה הדרישה שגויה, וצרפו צילום שלה.")
            else -> T("All of it's optional, but it helps the ministry handle the report.", "הכול לא חובה, אבל זה עוזר למשרד התחבורה לטפל בדיווח.")
        },
        action = T("Next", "הבא"), errors = d.errors,
        onAction = { d.next { d.go(ReportStep.YOU) } },
    ) {
        val room = reportRoom(issue, d.report, d.ride(named), d.inspector)
        LaunchedEffect(room) { if (d.words.length > room) d.words = d.words.take(room) }
        Words(if (issue.appeal) T("Why the demand is wrong", "למה הדרישה שגויה") else T("What happened, in your words", "מה קרה, במילים שלכם"),
            d.words, room) { d.words = it }
        if (issue.about == ReportAbout.INSPECTOR) Field(T("Inspector's number", "מספר הפקח"), d.inspector,
            { d.inspector = it.filter(Char::isDigit).take(10) },
            helper = T("On their badge, if you saw it", "על התג שלו, אם ראיתם"), keyboard = KeyboardType.Number)
        Photos(d)
        if (issue.conduct) {
            Heading(T("The ministry also asks", "משרד התחבורה שואל גם"))
            YesNo(T("Did you record it on a phone or camera?", "תיעדתם את האירוע בטלפון או במצלמה?"), d.recorded,
                d.errors["recorded"]) { d.recorded = it }
            Hint(d.recorded == "1", T("Add photos above. The form takes photos, not video or sound; the ministry asks you for " +
                "those if it needs them.", "צרפו תמונות למעלה. הטופס מקבל תמונות, לא וידאו או הקלטות; משרד התחבורה יבקש " +
                "אותם אם יצטרך."))
            YesNo(T("Were there witnesses?", "היו עדים לאירוע?"), d.witnesses, d.errors["witnesses"]) { d.witnesses = it }
            Hint(d.witnesses == "1", T("Write who they were and how to reach them in your words above, as the ministry asks.",
                "כתבו מי היו העדים ואיך אפשר להשיג אותם בתיאור למעלה, כפי שמשרד התחבורה מבקש."))
            Dropdown(T("Where could you give testimony?", "איפה תוכלו למסור עדות?"), testimonyCity(d.testifyAt),
                T("Choose a city", "בחרו עיר"), listOf("1", "2", "3").map { it to testimonyCity(it).orEmpty() }) { d.testifyAt = it }
            Check(d.willing, T("I'll be willing to give testimony at the Ministry of Transport and in court",
                "אהיה מוכן/ה למסור עדות במשרד התחבורה ובבית המשפט"), d.errors["willing"]) { d.willing = it }
        }
    }
}

@Composable
private fun YouStep(d: Draft) {
    val ctx = LocalContext.current
    StepPage(
        T("Your details", "הפרטים שלכם"),
        T("The ministry doesn't handle anonymous reports. Kav keeps these only on this phone, sealed, and puts them in the " +
            "form for you.", "משרד התחבורה לא מטפל בדיווחים אנונימיים. Kav שומרת את הפרטים רק בטלפון הזה, מוצפנים, וממלאת " +
            "אותם בטופס בשבילכם."),
        action = T("Next", "הבא"), errors = d.errors,
        onAction = {
            d.next {
                Reporter.save(ctx, d.me())
                d.kept = true
                d.go(ReportStep.CHECK)
            }
        },
    ) {
        Field(T("First name", "שם פרטי"), d.first, { d.first = it.take(25) },
            helper = T("In Hebrew, as on your ID card", "בעברית, כמו בתעודת הזהות"), error = d.errors["first"])
        Field(T("Last name", "שם משפחה"), d.last, { d.last = it.take(25) }, error = d.errors["last"])
        Field(T("ID number", "מספר זהות"), d.id, { d.id = it.filter(Char::isDigit).take(9) },
            helper = T("9 digits, with the check digit", "9 ספרות, כולל ספרת הביקורת"), error = d.errors["id"],
            keyboard = KeyboardType.Number)
        Dropdown(T("Gender", "מין"), genderName(d.gender), T("Choose your gender", "בחרו מין"),
            listOf("1", "2", "3").map { it to genderName(it).orEmpty() }, d.errors["gender"]) { d.gender = it }
        Field(T("Mobile number", "טלפון נייד"), d.phone, { d.phone = it.filter { c -> c.isDigit() || c == '-' || c == '+' }.take(16) },
            placeholder = "050-123-4567", helper = T("The form texts it a code, to confirm it's yours", "הטופס שולח אליו קוד, כדי לוודא שהוא שלכם"),
            error = d.errors["phone"], keyboard = KeyboardType.Phone)
        Field(T("Email", "דואר אלקטרוני"), d.email, { d.email = it.trim().take(50) }, error = d.errors["email"],
            keyboard = KeyboardType.Email)
        Field(T("Town you live in", "יישוב מגורים"), d.town, { d.town = it.take(40) }, error = d.errors["town"], ime = ImeAction.Done)
        Check(d.consent, T("The authority may keep my details to handle this report",
            "אני מסכים/ה שהרשות תשמור את פרטיי לצורך הטיפול בפנייה"), d.errors["consent"]) { d.consent = it }
        if (d.kept) Text(T("Forget my details", "מחיקת הפרטים שלי"), fontSize = 14.sp, color = K.accentInk,
            modifier = Modifier.clickable(role = Role.Button) {
                Reporter.forget(ctx)
                d.kept = false
                d.first = ""; d.last = ""; d.id = ""; d.gender = ""; d.phone = ""; d.email = ""; d.town = ""
            }.padding(vertical = K.gap1))
    }
}

@Composable
private fun CheckStep(d: Draft, named: (ReportStop) -> ReportStop, onFill: (Filling) -> Unit) {
    val issue = d.issue ?: return
    val about = d.about ?: return
    val ride = d.ride(named)
    StepPage(
        T("Check it", "בדקו לפני המילוי"),
        T("Kav opens the ministry's form and fills it in. There you confirm your phone with the code they text you, check " +
            "it and tap Send (שליחה).", "Kav פותחת את הטופס של משרד התחבורה וממלאת אותו. שם מאמתים את הטלפון בקוד שנשלח " +
            "ב-SMS, בודקים ומקישים על שליחה."),
        action = T("Fill in the ministry's form", "מילוי הטופס של משרד התחבורה"),
        onAction = {
            val me = d.me()
            val answers = if (issue.conduct) Answers(d.recorded, d.witnesses, d.testifyAt, d.willing) else Answers()
            val text = reportText(issue, d.words, d.report, ride, d.inspector)
            val paid = d.payment()
            onFill(Filling(d.photos.map { it.file }) { urls -> reportJson(issue, d.report, ride, text, me, answers, d.consent, urls, paid) })
        },
    ) {
        Summary(T("What happened", "מה קרה"), listOf("${aboutLabel(about)} · ${issue.label}")) { d.go(ReportStep.WHAT) }
        Summary(T("The ride", "הנסיעה"), listOfNotNull(
            listOf(T("Line ${ride.line}", "קו ${ride.line}"), formOperator(d.report.agency)).filter { it.isNotBlank() }.joinToString(" · "),
            ride.from?.name?.ifBlank { null }?.let { from ->
                if (issue.atStop) T("At $from", "בתחנה $from")
                else ride.to?.name?.ifBlank { null }?.let { T("From $from to $it", "מ$from אל $it") } ?: T("From $from", "מ$from")
            },
            ride.plate.ifBlank { null }?.let { T("Number plate $it", "מספר רישוי $it") },
            momentLabel(ride.at),
        )) { d.go(ReportStep.RIDE) }
        if (issue.payment) Summary(T("How you paid", "איך שילמתם"), listOfNotNull(
            d.demand.ifBlank { null }?.takeIf { issue.appeal }?.let { T("Demand $it", "דרישה $it") },
            when (d.means) {
                "1" -> T("Rav-Kav ${d.ravKav}", "רב-קו ${d.ravKav}")
                "2" -> listOfNotNull(appName(d.app), formPhone(d.appPhone)).joinToString(" · ")
                else -> meansName(d.means)
            },
            T("Paid or got on at ${clockFormat().format(Date(d.paidAt * 1000))}", "תיקוף או עלייה ב-${clockFormat().format(Date(d.paidAt * 1000))}"),
        )) { d.go(ReportStep.RIDE) }
        Summary(T("More", "עוד"), listOfNotNull(
            d.words.trim().ifBlank { null } ?: T("Nothing added", "לא נוסף תיאור"),
            d.photos.size.takeIf { it > 0 }?.let { if (it == 1) T("1 photo", "תמונה אחת") else T("$it photos", "$it תמונות") },
        )) { d.go(ReportStep.MORE) }
        Summary(T("You", "אתם"), listOf("${nameText(d.first)} ${nameText(d.last)}", T("ID ending ${d.id.takeLast(3)}", "ת״ז שמסתיימת ב-${d.id.takeLast(3)}"))) {
            d.go(ReportStep.YOU)
        }
    }
}

// A step: its title and what it's for, its fields, and its button kept at the bottom.
@Composable
private fun StepPage(
    title: String, note: String?, action: String? = null, errors: Map<String, String> = emptyMap(),
    onAction: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    val inset = maxOf(bottomCover(), LocalBottomBarInset.current)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = K.gap4).padding(top = K.gap2, bottom = if (action == null) inset + K.gap5 else K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(title, style = K.type.title)
            note?.let { Text(it, style = K.type.subhead) }
            Spacer(Modifier.height(K.gap1))
            content()
        }
        if (action != null) Column(
            Modifier.fillMaxWidth().background(K.bg).padding(horizontal = K.gap4).padding(top = K.gap2, bottom = inset + K.gap3),
            verticalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            AnimatedVisibility(errors.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(T("Fix what's marked above.", "תקנו את מה שמסומן למעלה."), style = K.type.subhead, color = K.critical)
            }
            KavButton(action, onAction, wide = true)
        }
    }
}

// How far along the report is.
@Composable
private fun StepBar(step: ReportStep) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap2),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReportStep.entries.forEach { s ->
            val tint by animateColorAsState(if (s <= step) K.accent else K.plateStrong, tween(260), label = "stepBar")
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tint))
        }
    }
}

// The bus being reported, as Kav knows it.
@Composable
private fun BusCard(r: ReportFor) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.plateStrong)
        .padding(horizontal = K.gap4, vertical = K.gap3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(listOf(T("Line ${r.line}", "קו ${r.line}"), formOperator(r.agency)).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 16.sp, color = K.text, fontWeight = FontWeight.Medium)
        r.destination.ifBlank { null }?.let { Text(T("to $it", "לכיוון $it"), style = K.type.subhead) }
        r.plate?.let { Text(T("Number plate $it", "מספר רישוי $it"), style = K.type.subhead) }
    }
}

// One answer of a few, with what it means; taking it goes on to the next step.
@Composable
private fun Choice(title: String, detail: String, picked: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (picked) K.accent else Color.Transparent, tween(200), label = "choice")
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).border(1.5.dp, edge, RoundedCornerShape(K.rCard))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, color = K.text, fontWeight = FontWeight.Medium)
            Text(detail, style = K.type.subhead)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = if (picked) K.accentInk else K.textTertiary, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = K.type.caption, modifier = Modifier.padding(top = K.gap2))
}

@Composable
private fun Label(text: String, error: Boolean) {
    Text(text, style = K.type.subhead, color = if (error) K.critical else K.muted)
}

// Under a field: what's wrong with it, else a word on what goes there.
@Composable
private fun Under(error: String?, helper: String?) {
    Crossfade(error ?: helper, label = "under") { t ->
        if (t != null) Text(t, fontSize = 12.sp, lineHeight = 16.sp, color = if (error != null) K.critical else K.dim)
    }
}

@Composable
private fun Field(
    label: String, value: String, onValue: (String) -> Unit, placeholder: String = "", helper: String? = null,
    error: String? = null, keyboard: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Next,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, error != null)
        KavField(value, onValue, placeholder,
            if (error != null) Modifier.border(1.dp, K.critical, K.capsule) else Modifier,
            keyboard = keyboard, ime = ime)
        Under(error, helper)
    }
}

@Composable
private fun Chevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = turn }) {
        val w = size.width
        drawLine(K.muted, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), w * .12f, StrokeCap.Round)
        drawLine(K.muted, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), w * .12f, StrokeCap.Round)
    }
}

// A field that opens to its choices.
@Composable
private fun <V> Dropdown(
    label: String, value: String?, placeholder: String, options: List<Pair<V, String>>, error: String? = null,
    onPick: (V) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, error != null)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).onSizeChanged { width = it.width }.clip(K.capsule).background(K.fill)
                    .then(if (error != null) Modifier.border(1.dp, K.critical, K.capsule) else Modifier)
                    .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = K.gap4, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                Text(value ?: placeholder, style = K.type.body, color = if (value == null) K.dim else K.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Chevron(open)
            }
            DropdownMenu(
                open, onDismissRequest = { open = false },
                modifier = Modifier.width(with(LocalDensity.current) { width.toDp() }).heightIn(max = 360.dp).background(K.bg),
                containerColor = K.bg, tonalElevation = 0.dp, shadowElevation = 8.dp,
                shape = RoundedCornerShape(K.rTile),
                border = BorderStroke(0.5.dp, K.separator),
            ) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(
                        text = { Text(text, style = K.type.body, color = if (text == value) K.accentInk else K.text) },
                        onClick = { open = false; onPick(v) },
                    )
                }
            }
        }
        error?.let { Under(it, null) }
    }
}

// A stop of the ride to choose; without stops to choose from, a name to type.
@Composable
private fun StopField(
    label: String, stops: List<ReportStop>, picked: Int, typed: String, named: (ReportStop) -> ReportStop,
    after: Int = -1, onPick: (Int) -> Unit, onType: (String) -> Unit,
) {
    if (stops.isEmpty()) {
        Field(label, typed, { onType(it.take(60)) })
        return
    }
    Dropdown(
        label, stops.getOrNull(picked)?.let { named(it).name.ifBlank { "…" } }, T("Choose a stop", "בחרו תחנה"),
        stops.indices.filter { it > after }.map { it to named(stops[it]).name.ifBlank { "…" } },
        onPick = onPick,
    )
}

@Composable
private fun YesNo(question: String, value: String, error: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Text(question, style = K.type.body, color = if (error != null) K.critical else K.text)
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(K.capsule).background(K.fill).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("1" to T("Yes", "כן"), "2" to T("No", "לא")).forEach { (code, text) ->
                val on = value == code
                val fill by animateColorAsState(if (on) K.accent else Color.Transparent, tween(180), label = "yesNo")
                val ink by animateColorAsState(if (on) K.onAccent else K.text, tween(180), label = "yesNoText")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(K.capsule).background(fill)
                        .clickable(role = Role.RadioButton) { onPick(code) },
                    contentAlignment = Alignment.Center,
                ) { Text(text, style = K.type.bodyStrong, color = ink) }
            }
        }
        error?.let { Under(it, null) }
    }
}

@Composable
private fun Hint(shown: Boolean, text: String) {
    AnimatedVisibility(shown, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        NoteCard(text)
    }
}

@Composable
private fun Check(checked: Boolean, text: String, error: String? = null, onChange: (Boolean) -> Unit) {
    val fill by animateColorAsState(if (checked) K.accent else K.surface4, tween(160), label = "check")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = K.gap1),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Box(
                Modifier.padding(top = 1.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(fill)
                    .border(1.dp, if (checked) K.accent else if (error != null) K.critical else K.border, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = K.onAccent, modifier = Modifier.size(16.dp)) }
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = K.text, modifier = Modifier.weight(1f))
        }
        error?.let { Under(it, null) }
    }
}

@Composable
private fun Words(label: String, value: String, room: Int, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, false)
        BasicTextField(
            value = value,
            onValueChange = { onValue(it.take(room)) },
            textStyle = K.type.body,
            cursorBrush = SolidColor(K.text),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(K.rTile)).background(K.fill)
                .semantics { contentDescription = label }.padding(horizontal = K.gap4, vertical = 12.dp),
        )
        Under(null, T("${room - value.length} characters left", "נותרו ${room - value.length} תווים"))
    }
}

@Composable
private fun WhenField(label: String, value: String, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, false)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(K.capsule).background(K.fill)
                .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = K.gap4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = K.type.body, modifier = Modifier.weight(1f))
            Text(T("Change", "שינוי"), fontSize = 14.sp, color = K.accentInk)
        }
    }
}

// The day and time it happened, as far back as the form takes reports. Without the day to choose, the time is on the
// day given: when they paid is on the day it happened.
@Composable
private fun MomentSheet(
    at: Long, title: String, withDay: Boolean = true, day: Long = at, onPick: (Long) -> Unit, onDismiss: () -> Unit,
) {
    val start = remember { Calendar.getInstance(ISRAEL).apply { timeInMillis = at * 1000 } }
    var back by remember { mutableIntStateOf(daysBack(day)) }
    var hour by remember { mutableIntStateOf(start.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(start.get(Calendar.MINUTE)) }
    val now = remember { System.currentTimeMillis() / 1000 }
    BottomSheet(onDismiss, scrolls = true) { close ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = K.type.headline, modifier = Modifier.weight(1f))
            Text(T("Close", "סגירה"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clip(K.capsule).clickable(role = Role.Button) { close(onDismiss) }
                    .padding(horizontal = K.gap2, vertical = K.gap1))
        }
        Spacer(Modifier.height(K.gap3))
        TimeEntry(hour, minute) { h, m -> hour = h; minute = m }
        Spacer(Modifier.height(K.gap2))
        val picked = momentOf(back, hour, minute)
        if (withDay) Row(Modifier.fillMaxWidth().clip(K.capsule).background(K.fill), verticalAlignment = Alignment.CenterVertically) {
            DayArrow(true, back < MAX_DAYS_BACK, T("Earlier day", "יום קודם")) { back++ }
            Text(dayName(back, picked), style = K.type.body, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            DayArrow(false, back > 0, T("Later day", "יום אחרי")) { back-- }
        }
        val future = picked > now
        AnimatedVisibility(future) {
            Text(T("That's later than now.", "זה מאוחר מעכשיו."), style = K.type.subhead, color = K.critical,
                modifier = Modifier.padding(top = K.gap2))
        }
        Spacer(Modifier.height(K.gap3))
        KavButton(T("Done", "סיום"), { close { onPick(picked); onDismiss() } }, enabled = !future, wide = true)
    }
}

@Composable
private fun DayArrow(back: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (back) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null, tint = if (enabled) K.text else K.textTertiary, modifier = Modifier.size(24.dp),
        )
    }
}

// The rider's photos, made ready for the form as they're picked, with room for four.
@Composable
private fun Photos(d: Draft) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        val wanted = uris.take((MAX_PHOTOS - d.photos.size - working).coerceAtLeast(0))
        working += wanted.size
        scope.launch {
            for (uri in wanted) {
                formPhoto(ctx, uri)?.let { d.photos = d.photos + it }
                working--
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(T("Photos", "תמונות"), false)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            d.photos.forEach { p ->
                Box(Modifier.size(76.dp)) {
                    Image(p.thumb, contentDescription = T("Photo", "תמונה"), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(K.rTile)))
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(K.bg.copy(alpha = .8f))
                            .clickable(role = Role.Button, onClickLabel = T("Remove", "הסרה")) { d.photos = d.photos - p; p.file.delete() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = K.text, modifier = Modifier.size(16.dp)) }
                }
            }
            repeat(working) {
                Box(Modifier.size(76.dp).panel(K.rTile), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = K.muted, strokeWidth = 2.dp)
                }
            }
            if (d.photos.size + working < MAX_PHOTOS) Box(
                Modifier.size(76.dp).panel(12.dp)
                    .clickable(role = Role.Button, onClickLabel = T("Add photos", "הוספת תמונות")) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(28.dp)) }
        }
        Under(null, T("Up to 4. The ministry's form takes photos, not video or sound.", "עד 4. הטופס של משרד התחבורה מקבל תמונות, לא וידאו או הקלטות."))
    }
}

// One part of the report, to look over before filling in the form; tapping it goes back to change it.
@Composable
private fun Summary(title: String, lines: List<String>, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).clickable(role = Role.Button, onClick = onChange)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 12.sp, color = K.dim)
            lines.forEach { Text(it, style = K.type.body, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        Text(T("Change", "שינוי"), fontSize = 14.sp, color = K.accentInk)
    }
}

private fun clearPhotos(ctx: Context) {
    File(ctx.cacheDir, "report").deleteRecursively()
}

// A picked photo as the form takes it: an upright JPEG under 4 MB, at most PHOTO_SIDE across.
private suspend fun formPhoto(ctx: Context, uri: Uri): ReportPhoto? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = decodePhoto(ctx, uri) ?: return@runCatching null
        var quality = 88
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 12
        } while (bytes.size > MAX_PHOTO_BYTES && quality >= 40)
        if (bytes.size > MAX_PHOTO_BYTES) return@runCatching null
        val file = File(File(ctx.cacheDir, "report").apply { mkdirs() }, "photo-${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        val scale = 240f / max(bitmap.width, bitmap.height)
        val thumb = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
        ReportPhoto(file, thumb.asImageBitmap())
    }.getOrNull()
}

private fun decodePhoto(ctx: Context, uri: Uri): Bitmap? {
    val resolver = ctx.contentResolver
    if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val scale = min(1f, PHOTO_SIDE.toFloat() / max(info.size.width, info.size.height))
        decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > PHOTO_SIDE) sample *= 2
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

// How far assets/report.js got, as it leaves it in the page, whether the photos made it onto the form; and, once the form
// shows it was sent, its number.
private class Progress(val state: String = "loading", val ref: String? = null, val photosLost: Boolean = false) {
    val sent get() = state == "sent"

    companion object {
        const val POLL = "(function(){var k=window.__kav||{state:'loading'};" +
            "if(document.querySelector('[data-testid=\"submitSuccess\"]')){k.state='sent';" +
            "k.ref=(window.formParams&&formParams.process&&formParams.process.referenceNumber)||null;}" +
            "return JSON.stringify(k);})()"

        fun parse(raw: String?): Progress? = runCatching {
            val o = JSONObject(JSONTokener(raw).nextValue() as String)
            val missed = o.optJSONArray("missed")
            Progress(
                o.optString("state", "loading"), o.opt("ref")?.takeIf { it != JSONObject.NULL }?.toString(),
                missed != null && (0 until missed.length()).any { missed.optString(it) == "Attachments" },
            )
        }.getOrNull()
    }
}

// The authority's form in a page inside Kav, filled in by assets/report.js, with what it reached above it. Only web
// pages open here; the rider's photos are handed over at an address only the script knows, and files the form asks
// for besides come from the phone's own picker.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MinistryForm(filling: Filling, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val script = remember { runCatching { ctx.assets.open("report.js").bufferedReader().use { it.readText() } }.getOrNull() }
    val handover = remember { "$PHOTOS${UUID.randomUUID()}/" }
    val fill = remember { filling.json(filling.photos.indices.map { "$handover$it" }) }
    var progress by remember { mutableStateOf(Progress()) }
    // The form's page itself didn't come: no connection, or the site can't be reached.
    var failed by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        chooser = null
    }
    val page = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Google's reCAPTCHA on the form runs from its own domain.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return scheme != "http" && scheme != "https"
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (!url.startsWith(handover)) return null
                    val photo = filling.photos.getOrNull(url.removePrefix(handover).toIntOrNull() ?: -1)?.takeIf { it.exists() }
                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse("image/jpeg", null, photo.inputStream())
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (script != null && url?.startsWith(FORM) == true) view.evaluateJavascript("$script($fill);", null)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failed = true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                ): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    return runCatching { pick.launch(params.createIntent()) }.isSuccess.also { if (!it) chooser = null }
                }
            }
            loadUrl(FORM)
        }
    }
    LaunchedEffect(page) {
        while (true) {
            delay(700)
            page.evaluateJavascript(Progress.POLL) { raw -> Progress.parse(raw)?.let { progress = it } }
        }
    }
    val (status, tint) = if (failed) {
        T("The form didn't load. Check your connection and try again.", "הטופס לא נטען. בדקו את החיבור לאינטרנט ונסו שוב.") to
            K.critical
    } else when (progress.state) {
        "ready" -> T("Filled in. Confirm your phone with the code the form texts you, check both steps, then tap שליחה.",
            "הטופס מולא. אמתו את הטלפון בקוד שהטופס שולח, בדקו את שני השלבים והקישו על שליחה.") to K.realtime
        "partial" -> (if (progress.photosLost) T("Your photos didn't upload, attach them again in the form. ",
            "התמונות לא הועלו, צרפו אותן שוב בטופס. ") else "") +
            T("Kav filled in what it could. Fill in what the form marks, confirm your phone and tap שליחה.",
                "Kav מילאה את מה שהצליחה. השלימו את מה שהטופס מסמן, אמתו את הטלפון והקישו על שליחה.") to K.problem
        "error" -> T("Kav couldn't fill in the form. You can still fill it in here.",
            "Kav לא הצליחה למלא את הטופס. אפשר עדיין למלא אותו כאן.") to K.critical
        "sent" -> (progress.ref?.let { T("Sent. Application number $it.", "נשלח. מספר הפנייה $it.") }
            ?: T("Sent.", "נשלח.")) to K.realtime
        else -> T("Filling in the form…", "ממלאים את הטופס…") to K.dim
    }
    // A window of its own: Kav draws its screens through a layer for the glass bars, and a page drawn that way loses its
    // top.
    Dialog(
        onDismissRequest = { if (progress.sent) onDone() else onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = K.light
                    isAppearanceLightNavigationBars = K.light
                }
            }
        }
        Column(Modifier.fillMaxSize().background(K.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap2), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(T("Ministry of Transport", "משרד התחבורה"), style = K.type.headline)
                    Crossfade(status to tint, label = "formStatus") { (text, colour) ->
                        Text(text, style = K.type.subhead, color = colour)
                    }
                }
                if (failed) Text(T("Try again", "ניסיון חוזר"), style = K.type.body, color = K.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) {
                        failed = false
                        progress = Progress()
                        page.loadUrl(FORM)
                    }.padding(K.gap2))
                Text(if (progress.sent) T("Done", "סיום") else T("Cancel", "ביטול"), style = K.type.body,
                    color = if (progress.sent) K.accentInk else K.muted,
                    modifier = Modifier.clickable(role = Role.Button) { if (progress.sent) onDone() else onBack() }.padding(K.gap2))
            }
            AndroidView(
                factory = { page },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    chooser?.onReceiveValue(null)
                    chooser = null
                    it.destroy()
                },
            )
        }
    }
}
