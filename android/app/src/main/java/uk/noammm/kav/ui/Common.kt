package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net

import java.util.Calendar
import kotlin.math.*

/** Space occupied by the floating tabs; inset content, leaving page backgrounds full height. */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/** How much of the bottom edge the system is covering right now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun bottomCover(): androidx.compose.ui.unit.Dp =
    WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

/** Opens the service alert behind an AlertRow: (line group id, the row's own label). */
val LocalServiceAlertOpener = staticCompositionLocalOf<(Int, String) -> Unit> { { _, _ -> } }

/* time */

fun hhmm(s: Int): String = "%02d:%02d".format((s / 3600) % 24, (s / 60) % 60)

fun dur(s: Int): String =
    if (s >= 3600) "${s / 3600}h ${((s % 3600) / 60.0).roundToInt()}m"
    else "${(s / 60.0).roundToInt()} min"

fun nowSec(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
}

/** "4 min" while it is close enough to matter, otherwise null. */
fun relative(t: Int, from: Int = nowSec()): String? {
    val d = t - from
    if (d < 0 || d > 3600) return null
    val m = (d / 60.0).roundToInt()
    return if (m <= 0) "now" else "$m min"
}

/* geo */

private const val EARTH = 6371000.0

fun metres(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val p1 = la1 * PI / 180; val p2 = la2 * PI / 180
    val dp = (la2 - la1) * PI / 180; val dl = (lo2 - lo1) * PI / 180
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH * asin(min(1.0, sqrt(a)))
}

fun distanceLabel(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000)

/* modes */

/**
 * One case per vehicle Moovit names. `TransitType.VehicleType` is TRAM, SUBWAY, TRAIN,
 * BUS, FERRY, CABLE, GONDOLA, FUNICULAR, and `MVRouteType` numbers them in that same
 * order, so the wire value and the vehicle are the same fact, and the four that Kav
 * used to fold into TRAM (cable, gondola, funicular, monorail) each get their own mark.
 */
enum class Mode { TRAM, SUBWAY, TRAIN, BUS, FERRY, CABLE, GONDOLA, FUNICULAR, TAXI, OTHER }

/**
 * MVRouteType, value for value: Tram 0, Subway 1, Rail 2, Bus 3, Ferry 4, Cable 5,
 * Gondola 6, Funicular 7. The rest are the extended GTFS types the Israeli MOT feed
 * actually ships, 8 is the share-taxi network (מוניות שירות) and 715 the
 * demand-responsive shuttles, and 8 used to fall through to OTHER and draw a bare
 * circle. Trolleybuses ride as buses and a monorail as a tram, as Moovit has neither.
 *
 * Note what this cannot resolve on its own: the MOT feed gives the Carmelit and the
 * Rakavlit the same type 5, though one is a funicular and the other an aerial cable
 * car. Online, [Moovit.Resolved.routeType] answers from the metro's own agency record
 * and separates them; offline both take the cable car.
 */
fun modeOf(type: Int): Mode = when (type) {
    0 -> Mode.TRAM
    1 -> Mode.SUBWAY
    2 -> Mode.TRAIN
    3, 11 -> Mode.BUS
    4 -> Mode.FERRY
    5 -> Mode.CABLE
    6 -> Mode.GONDOLA
    7 -> Mode.FUNICULAR
    8, 715 -> Mode.TAXI
    12 -> Mode.TRAM
    else -> Mode.OTHER
}

fun modeName(m: Mode): String = when (m) {
    Mode.BUS -> "Bus"; Mode.TRAIN -> "Train"; Mode.TRAM -> "Light rail"
    Mode.SUBWAY -> "Metro"; Mode.FERRY -> "Ferry"; Mode.CABLE -> "Cable car"
    Mode.GONDOLA -> "Cable car"; Mode.FUNICULAR -> "Funicular"
    Mode.TAXI -> "Share taxi"; Mode.OTHER -> "Other"
}

@Composable
fun WalkGlyph(tint: Color = K.dim, size: androidx.compose.ui.unit.Dp = 13.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.11f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
        drawCircle(tint, radius = w * .12f, center = Offset(w * .52f, h * .14f))
        line(.52f, .28f, .48f, .56f)          // torso
        line(.48f, .56f, .34f, .88f)          // back leg
        line(.48f, .56f, .66f, .84f)          // front leg
        line(.52f, .36f, .72f, .46f)          // arm
    }
}

/* line badge */

@Composable
fun LineBadge(net: Net, route: Int, modifier: Modifier = Modifier) {
    val mode = modeOf(net.routes.getOrNull(route)?.type ?: 3)
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(K.plate)
            .border(1.dp, K.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ModeGlyph(mode, K.dim, 12.dp)
        Text(
            (net.routes.getOrNull(route)?.short ?: "·").ifBlank { "·" },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.text,
        )
    }
}

/* agency marks */

/**
 * Israel Railways' mark: a light plate carrying three stepped bands. Moovit prints
 * the agency's real logo here, in colour; this is the same geometry drawn in the
 * app's own palette, so a train badge cannot be mistaken for a bus badge at a
 * glance, which is the whole job the logo does on the card.
 */
@Composable
fun RailMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            K.text,
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        // three parallel bands, each rising left-to-right with a step in the middle
        val band = w * .135f
        for (i in 0..2) {
            val y = h * (.70f - i * .19f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .10f, y)
                cubicTo(w * .34f, y, w * .40f, y - h * .17f, w * .62f, y - h * .17f)
                lineTo(w * .90f, y - h * .17f)
            }
            drawPath(
                path, K.surface1,
                style = Stroke(band, cap = StrokeCap.Butt, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}

/**
 * The mark that goes on a line badge. Rail gets the railway plate; everything else
 * gets its mode glyph. [agencyId] 854820 is Israel Railways in metro 1.
 */
@Composable
fun AgencyMark(routeType: Int, agencyId: Int, tint: Color = K.muted, size: androidx.compose.ui.unit.Dp = 15.dp) {
    if (routeType == 2 || agencyId == 854820) RailMark(size)
    else ModeGlyph(modeOf(routeType), tint, size)
}

/* the offline timetable, on demand */

/**
 * Runs [content] with the timetable loaded by the shell. Keeping the load outside
 * these browser screens lets it finish when a tab leaves the composition.
 */
@Composable
fun WithTimetable(model: uk.noammm.kav.KavModel, content: @Composable (Net) -> Unit) {
    val net = model.net
    when {
        net != null -> content(net)
        model.netError != null -> Column(Modifier.padding(K.gap4)) {
            Note("Could not open the offline timetable: ${model.netError}")
            Spacer(Modifier.height(K.gap3))
            Chip("Retry", false) {
                model.netError = null
                model.netLoadAttempt++
            }
        }
        else -> LoadingBlock("Opening the timetable")
    }
}

/* precise location */

/**
 * Android will not re-prompt once someone has picked "Approximate", so an app stuck on
 * a coarse grant can only point at Settings. Moovit does exactly this, and the wording
 * here is its own (`location_not_accurate_title` / `_message1` / `_button`).
 *
 * It matters more than it sounds: a coarse fix is fuzzed by a kilometre or more, which
 * is enough to plan your trip from the next town.
 */
@Composable
fun PreciseLocationNudge() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (uk.noammm.kav.hasPreciseLocation(ctx)) return
    if (!uk.noammm.kav.hasLocationPermission(ctx)) return   // the normal prompt covers this
    Row(
        Modifier.padding(horizontal = K.gap3, vertical = K.gap2).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(K.plate)
            .border(1.dp, K.border, RoundedCornerShape(12.dp))
            .padding(K.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Your location is not accurate", fontSize = 13.sp, color = K.text)
            Text(
                "Turn on ‘Use precise location’. Without it Android rounds your position " +
                    "to about a kilometre, which can plan your trip from the wrong town.",
                fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(K.gap3))
        Text(
            "Change settings", fontSize = 12.sp, color = K.text,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(K.plateStrong)
                .clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", ctx.packageName, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/* service alerts */

/** The amber ⓘ Moovit stamps on the corner of a line badge that has an alert. */
@Composable
fun AlertPip(category: Int, size: androidx.compose.ui.unit.Dp = 13.dp) {
    if (category < 3) return
    val tint = if (category >= 4) K.critical else K.problem
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(K.bg, w * .5f, Offset(w * .5f, w * .5f))
        drawCircle(tint, w * .42f, Offset(w * .5f, w * .5f), style = Stroke(w * .13f))
        drawCircle(tint, w * .055f, Offset(w * .5f, w * .31f))
        drawLine(
            tint, Offset(w * .5f, w * .44f), Offset(w * .5f, w * .70f),
            w * .11f, StrokeCap.Round,
        )
    }
}

/**
 * Moovit's alert row: an amber-outlined strip under the line, carrying the server's own
 * word for what is wrong, "Detour", "Modified Service", and a chevron. The text is
 * never invented here; it is MVServiceStatus.desc exactly as sent.
 */
@Composable
fun AlertRow(category: Int, text: String, groupId: Int = 0) {
    if (category < 3 || text.isBlank()) return
    val tint = if (category >= 4) K.critical else K.problem
    val open = LocalServiceAlertOpener.current
    Row(
        Modifier.fillMaxWidth().padding(top = K.gap2)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tint, RoundedCornerShape(8.dp))
            // the chevron promises a screen, so it only earns one when there is a
            // line group to fetch the operator's wording for
            .then(
                if (groupId > 0) Modifier.clickable(role = Role.Button) { open(groupId, text) }
                else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlertPip(category, 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = K.text, modifier = Modifier.weight(1f))
        if (groupId > 0) Text("›", fontSize = 15.sp, color = K.dim)
    }
}

/* service alerts */

/**
 * The alert behind the row, in the operator's own words.
 *
 * A plan leg carries only MVServiceStatus, a category and a short label such as
 * "Modified Service", so the text a rider actually wants has to be fetched:
 * V4/ServiceAlert/LineGroupsServiceAlerts for the ids, then ServiceAlertsById for
 * the wording. Nothing here is summarised or reworded.
 */
@Composable
fun ServiceAlertSheet(groupId: Int, fallbackLabel: String, onDismiss: () -> Unit) {
    var alerts by remember(groupId) { mutableStateOf<List<Moovit.ServiceAlert>?>(null) }
    var failed by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        val s = Online.session ?: run { failed = true; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { Moovit.serviceAlerts(s, listOf(groupId)) } }
            .onSuccess { alerts = it }
            .onFailure { failed = true }
    }
    // The sheet used to be placed outright, at whatever height "Loading…" happened to
    // need, and then jump to its full height when the fetch landed a moment later,
    // which read as a stack appearing and then sticking rather than a panel sliding up.
    // It now enters as one motion, and its own height changes are animated too, so the
    // arriving text grows the panel instead of snapping it.
    var shown by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) { shown = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
                    .clickable(onClick = onDismiss),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it },
            ),
            exit = androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(160),
                targetOffsetY = { it },
            ),
        ) {
        Column(
            // consume the tap so the panel itself does not dismiss
            Modifier.fillMaxWidth()
                // The same bottom edge the When sheet has to clear: this screen draws
                // behind the floating tab bar, so a panel pinned to BottomCenter ends
                // up with its last lines, and a long alert's scrolled tail, under it.
                .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                .padding(K.gap3)
                .clip(RoundedCornerShape(K.rCard)).background(K.surface1)
                .clickable(enabled = false) {}
                .padding(K.gap4)
                .animateContentSize(),
        ) {
            // The heading names which alert this is and Close is the way out of it;
            // an operator's notice runs to pages, so both stay put and only the
            // notice scrolls, rather than the way out leaving with the first screen.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fallbackLabel.ifBlank { "Service alert" },
                    fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close", fontSize = 14.sp, color = K.accent,
                    modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = K.gap2, vertical = K.gap1),
                )
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            val list = alerts
            when {
                list == null && !failed ->
                    LoadingPulse("Fetching the notice", Modifier.fillMaxWidth().padding(top = K.gap4, bottom = K.gap2))
                failed || list.isNullOrEmpty() ->
                    Text(
                        "The operator published no further detail for this alert.",
                        fontSize = 14.sp, color = K.muted, modifier = Modifier.padding(top = K.gap3),
                    )
                else -> list.forEachIndexed { i, a ->
                    if (i > 0) {
                        Spacer(Modifier.height(K.gap3))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(K.border))
                    }
                    Spacer(Modifier.height(K.gap3))
                    a.title.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(K.gap2))
                    }
                    alertWindow(a)?.let {
                        Text(it, fontSize = 12.sp, color = K.dim)
                        Spacer(Modifier.height(K.gap2))
                    }
                    alertText(a)?.let {
                        Text(it, fontSize = 14.sp, color = K.muted, lineHeight = 20.sp)
                    }
                }
            }
            }
        }
        }
    }
}

/** "From 8 Sep", "Until 12 Sep", "8–12 Sep", only what the server actually bounds. */
private fun alertWindow(a: Moovit.ServiceAlert): String? {
    val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    fun at(t: Long) = day.format(java.util.Date(t * 1000))
    return when {
        a.activeFrom > 0 && a.activeTo > 0 -> "${at(a.activeFrom)} – ${at(a.activeTo)}"
        a.activeFrom > 0 -> "From ${at(a.activeFrom)}"
        a.activeTo > 0 -> "Until ${at(a.activeTo)}"
        else -> null
    }
}

/** Operators publish most alert bodies as HTML; read it, do not print the markup. */
private fun alertText(a: Moovit.ServiceAlert): String? {
    val raw = a.body.takeIf { it.isNotBlank() } ?: return null
    if (!a.html && !raw.contains('<')) return raw.trim()
    return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString().replace(Regex("\n{3,}"), "\n\n").trim().ifBlank { null }
}
