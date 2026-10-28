package uk.noammm.kav.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import kotlin.math.max
import kotlin.math.min

// Each bus operator's own colour, on its lines' plates and its buses' roads on the map. The idea and the
// colours are Sammy Zaionz's (szaionz), from his route_colors branch. An operator is known by the ministry's id for it
// (Kav's server and planner) or by its name in the ministry's timetable; Moovit names it the same with the English in
// brackets, "דן (Dan)". Operators without a colour here keep Kav's own.
internal object Operators {
    private class Operator(val gtfs: Int, val name: String, val english: String?, val argb: Long)

    private val all = listOf(
        Operator(3, "אגד", "Egged", 0xFF018369),
        Operator(4, "אלקטרה אפיקים תחבורה", "Electra Afikim Tachbura", 0xFFB2C44A),
        Operator(5, "דן", "Dan", 0xFF0E2A5A),
        Operator(8, "גי.בי.טורס", "GB-Tours", 0xFFA42A2A),
        Operator(10, "מועצה אזורית אילות", null, 0xFF000000),
        Operator(14, "נתיב אקספרס", "Nateev Express", 0xFFE17C30),
        Operator(15, "מטרופולין", "Metropoline", 0xFFF79A34),
        Operator(16, "סופרבוס", "Superbus", 0xFF003E7E),
        Operator(18, "קווים", "Kavim", 0xFF8CBCD8),
        Operator(23, "גלים", "Galeem", 0xFF4491CB),
        Operator(24, "מועצה אזורית גולן", "Golan", 0xFF6EB742),
        Operator(25, "אלקטרה אפיקים", "Electra Afikim", 0xFF7FB90D),
        Operator(31, "דן בדרום", "Dan Badarom", 0xFF1C3786),
        Operator(32, "דן באר שבע", "Dan Beer-Sheva", 0xFF2F4C9B),
        Operator(34, "תנופה", "Tnufa", 0xFF45AA9E),
        Operator(35, "בית שמש אקספרס", "Beit Shemesh Express", 0xFFF08B1F),
        Operator(37, "אקסטרה", "Extra", 0xFF46C0E6),
        Operator(38, "אקסטרה ירושלים", null, 0xFF46C0E6),
        Operator(39, "דן נתיבים בעמ", null, 0xFF000000),
        Operator(42, "ירושלים-רמאללה איחוד", null, 0xFF008F51),
        Operator(44, "ירושלים-אבו-תור-ענאתא איחוד", null, 0xFF31316E),
        Operator(45, "ירושלים-אלווסט איחוד", null, 0xFF31316E),
        Operator(49, "ירושלים - עיסאוויה מחנה שעפאט איחוד", null, 0xFF008F51),
        Operator(50, "ירושלים-דרום איחוד", null, 0xFF31316E),
        Operator(51, "ירושלים-צור באהר איחוד", null, 0xFF31316E),
        Operator(135, "דרך אגד עוטף ירושלים", null, 0xFF0A66AC),
    )
    private val byGtfs = all.associateBy { it.gtfs }
    private val byName = all.associateBy { flat(it.name) } + all.filter { it.english != null }.associateBy { flat(it.english!!) }

    private fun flat(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun ofGtfs(agency: Int): Color? = byGtfs[agency]?.let { Color(it.argb) }

    // By name: the Hebrew before any brackets, else the English in them.
    fun ofName(name: String?): Color? {
        if (name.isNullOrBlank()) return null
        val hebrew = flat(name.substringBefore('('))
        val english = flat(name.substringAfter('(', "").substringBefore(')'))
        return (byName[hebrew] ?: byName[english].takeIf { english.isNotEmpty() })?.let { Color(it.argb) }
    }

    // A ride's operator, from a trip's details: Kav's planner's by the ministry's id it carries, Moovit's by name.
    fun of(r: Moovit.Resolved, agencyId: Int): Color? =
        KavPlan.gtfsAgency(agencyId)?.let(::ofGtfs) ?: ofName(r.agencyName(agencyId))

    // A timetable route's operator.
    fun of(net: Net, route: Int): Color? = if (route < 0) null else ofName(net.agencyOf(route))
}

// Bus lines only: trains, light rail and the rest keep their mode's plate.
internal fun busOperator(routeType: Int, agencyId: Int, colour: () -> Color?): Color? =
    if (plateFor(routeType, agencyId) != null) null else colour()

// The colour as Kav draws it on its own background: dark ones lifted in the dark looks and pale ones deepened in the
// light one, so Dan's navy still shows on a black map and Kavim's sky blue on a white one.
internal fun onScreen(c: Color, light: Boolean = K.light): Color {
    val towards = if (light) Color.Black else Color.White
    var t = 0f
    var out = c
    while (t < .7f && (if (light) out.luminance() > .4f else out.luminance() < .1f)) {
        t += .05f
        out = lerp(c, towards, t)
    }
    return out
}

// Whether a colour has a hue at all. A grey road would read as one already driven.
internal fun hued(c: Color): Boolean = max(c.red, max(c.green, c.blue)) - min(c.red, min(c.green, c.blue)) > .15f

// A bus line's plate in its operator's colour, as the map draws that colour, with ink that reads on it. Operators
// without a hue (black, grey) keep Kav's plain plate.
internal fun operatorPlate(colour: Color?): ModePlate? {
    val shown = onScreen(colour?.takeIf(::hued) ?: return null)
    // A middle colour neither ink reads on is deepened a little, until white does.
    var fill = shown
    var t = 0f
    while (max(contrast(Color.White, fill), contrast(DarkInk, fill)) < 4.5f && t < .3f) {
        t += .02f
        fill = lerp(shown, Color.Black, t)
    }
    return ModePlate(fill, lerp(fill, Color.Black, .2f), inkOn(fill))
}

private val DarkInk = Color(0xFF16160F)

// White where it reads at 4.5:1, else whichever of white and near black reads better.
internal fun inkOn(fill: Color): Color {
    val white = contrast(Color.White, fill)
    return if (white >= 4.5f || white >= contrast(DarkInk, fill)) Color.White else DarkInk
}

internal fun contrast(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (max(x, y) + .05f) / (min(x, y) + .05f)
}

// The plate a line's number sits on: its mode's, else its operator's for a bus, else none (Kav's plain plate).
internal fun linePlate(routeType: Int, agencyId: Int, operator: () -> Color?): ModePlate? =
    plateFor(routeType, agencyId) ?: operatorPlate(operator())
