package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Net

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun bottomCover(): androidx.compose.ui.unit.Dp =
    WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

// Opens a ride's line in the Lines tab at the stop it is boarded from, for all its departures there.
val LocalLineOpener = staticCompositionLocalOf<((Transit.Leg, Transit.Resolved) -> Unit)?> { null }

// Timetables and departures are in Israel's time, whatever zone the phone is set to.
val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

// Times read "14:05", or "2:05 PM" for anyone who asked for AM/PM.
val CLOCK get() = if (Shown.twelveHour) "h:mm a" else "HH:mm"

fun clockFormat() = java.text.SimpleDateFormat(CLOCK, Locale.US).apply { timeZone = ISRAEL }

fun hhmm(s: Int): String {
    val h = (s / 3600) % 24; val m = (s / 60) % 60
    return if (!Shown.twelveHour) "%02d:%02d".format(Locale.US, h, m)
    else "%d:%02d %s".format(Locale.US, (h + 11) % 12 + 1, m, if (h < 12) "AM" else "PM")
}

// A length of time, written one way everywhere: "55 min", "1 hr 8 min", "2 hr".
fun dur(s: Int): String = durMinutes((s / 60.0).roundToInt())

fun durMinutes(m: Int): String = durParts(m).joinToString(" ") { (n, unit) -> "$n $unit" }

// Its numbers and their units, for setting the numbers larger.
fun durParts(m: Int): List<Pair<Int, String>> = when {
    m < 60 -> listOf(m to T("min", "דק׳"))
    m % 60 == 0 -> listOf(m / 60 to T("hr", "שע׳"))
    else -> listOf(m / 60 to T("hr", "שע׳"), m % 60 to T("min", "דק׳"))
}

fun nowSec(): Int {
    val c = Calendar.getInstance(ISRAEL)
    return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
}

fun relative(t: Int, from: Int = nowSec()): String? {
    val d = t - from
    if (d < 0 || d > 3600) return null
    val m = (d / 60.0).roundToInt()
    return if (m <= 0) T("now", "עכשיו") else T("$m min", "$m דק׳")
}

private const val EARTH = 6371000.0

fun metres(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val p1 = la1 * PI / 180; val p2 = la2 * PI / 180
    val dp = (la2 - la1) * PI / 180; val dl = (lo2 - lo1) * PI / 180
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH * asin(min(1.0, sqrt(a)))
}

fun distanceLabel(m: Double): String =
    if (m < 1000) T("${m.roundToInt()} m", "${m.roundToInt()} מ'")
    else T("%.1f km", "%.1f ק\"מ").format(Locale.US, m / 1000)

enum class Mode { TRAM, SUBWAY, TRAIN, BUS, FERRY, CABLE, GONDOLA, FUNICULAR, TAXI, OTHER }

fun modeOf(type: Int): Mode = when (type) {
    0 -> Mode.TRAM
    1 -> Mode.SUBWAY
    2 -> Mode.TRAIN
    3, 11, 711 -> Mode.BUS
    4 -> Mode.FERRY
    5 -> Mode.CABLE
    6 -> Mode.GONDOLA
    7 -> Mode.FUNICULAR
    8, 715 -> Mode.TAXI
    12 -> Mode.TRAM
    else -> Mode.OTHER
}

fun isRail(routeType: Int, agencyId: Int) = routeType == 2 || agencyId == 854820

// Israel Railways is paid at the station's gates, before the platform, so a train's payment comes with waiting for it
// as well as with the ride. Light rail, buses and the rest are paid as they are ridden.
internal fun paidAtEntry(ride: Transit.Leg, r: Transit.Resolved): Boolean {
    val agency = r.line(ride.lineId)?.agencyId ?: -1
    return isRail(r.routeType(agency), agency)
}

data class ModePlate(val fill: Color, val edge: Color, val ink: Color)

private val TramPlate = ModePlate(Color(0xFFD8232A), Color(0xFFA81A20), Color(0xFFFCF4F4))
private val RailPlate = ModePlate(Color(0xFF1F6FD0), Color(0xFF1854A3), Color(0xFFF3F7FC))
private val TaxiPlate = ModePlate(Color(0xFFF5C518), Color(0xFFC79D0E), Color(0xFF16160F))
private val CarmelitPlate = ModePlate(Color(0xFF0A822E), Color(0xFF086423), Color(0xFFF2FBF4))
private val RakavlitPlate = ModePlate(Color(0xFF8950D4), Color(0xFF693EA3), Color(0xFFF7F4FD))

fun plateFor(routeType: Int, agencyId: Int = -1): ModePlate? = when {
    isRail(routeType, agencyId) -> RailPlate
    modeOf(routeType) == Mode.TRAM -> TramPlate
    modeOf(routeType) == Mode.TAXI -> TaxiPlate
    modeOf(routeType) == Mode.FUNICULAR -> CarmelitPlate
    modeOf(routeType) == Mode.CABLE || modeOf(routeType) == Mode.GONDOLA -> RakavlitPlate
    else -> null
}

// A ride by its kind in words, then who runs it: "Light rail · כפיר" beside "Bus · אקסטרה ירושלים", as the same number
// can be both (tram 1 and bus 1 in Jerusalem).
fun rideBy(routeType: Int, operator: String?): String =
    typeName(routeType) + (operator?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

// What a line's badge says aloud: its kind and number, "Light rail 1", "Bus 1".
fun lineDescription(routeType: Int, number: String?): String =
    typeName(routeType) + (number?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

fun typeName(routeType: Int): String = when (routeType) {
    711 -> T("Shuttle", "שאטל")
    7 -> T("Carmelit", "כרמלית")
    5, 6 -> T("Rakavlit", "רכבלית")
    else -> modeName(modeOf(routeType))
}

fun modeName(m: Mode): String = when (m) {
    Mode.BUS -> T("Bus", "אוטובוס"); Mode.TRAIN -> T("Train", "רכבת"); Mode.TRAM -> T("Light rail", "רכבת קלה")
    Mode.SUBWAY -> T("Metro", "מטרו"); Mode.FERRY -> T("Ferry", "מעבורת"); Mode.CABLE -> T("Cable car", "רכבל")
    Mode.GONDOLA -> T("Cable car", "רכבל"); Mode.FUNICULAR -> T("Funicular", "פוניקולר")
    Mode.TAXI -> T("Share taxi", "מונית שירות"); Mode.OTHER -> T("Other", "אחר")
}

@Composable
fun WalkGlyph(tint: Color = K.dim, size: androidx.compose.ui.unit.Dp = 13.dp) {
    Canvas(Modifier.size(size)) { drawWalker(tint) }
}

fun DrawScope.drawWalker(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.11f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
    drawCircle(tint, radius = w * .12f, center = Offset(w * .52f, h * .14f))
    line(.52f, .28f, .48f, .56f)
    line(.48f, .56f, .34f, .88f)
    line(.48f, .56f, .66f, .84f)
    line(.52f, .36f, .72f, .46f)
}

// label: a train's number, which the timetable doesn't keep, where a live source has given it. type: the mode of a
// line the timetable hasn't, as the live source has it, and agency its operator, by the ministry's id.
@Composable
fun LineBadge(
    net: Net, route: Int, modifier: Modifier = Modifier, label: String? = null, type: Int = net.rType.getOrElse(route) { 3 },
    agency: Int = -1, dense: Boolean = false,
) {
    val plate = linePlate(type, -1) { Operators.of(net, route) ?: Operators.ofGtfs(agency) }
    LinePlateBadge(label?.ifBlank { null } ?: net.rShort.getOrElse(route) { "" }, modeOf(type), plate, modifier, dense)
}

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

@Composable
fun CarmelitMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            Color(0xFFEF8E1F),
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        val sw = w * .11f
        for (i in 0 until 2) {
            val dx = w * .17f * i
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(dx + w * .14f, h * .33f)
                lineTo(dx + w * .48f, h * .33f)
                lineTo(dx + w * .14f, h * .67f)
                lineTo(dx + w * .48f, h * .67f)
            }
            drawPath(
                path, Color.White,
                style = Stroke(sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun RakavlitMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawCircle(Color.White, radius = w * .48f, center = Offset(w / 2, h / 2))
        val blades = listOf(Color(0xFF2C6BB3), Color(0xFF8AB6E0), Color(0xFF9BA1A8))
        for (i in 0 until 6) {
            drawArc(
                blades[i % 3],
                startAngle = 60f * i + 8f,
                sweepAngle = 40f,
                useCenter = true,
                topLeft = Offset(w * .06f, h * .06f),
                size = androidx.compose.ui.geometry.Size(w * .88f, h * .88f),
            )
        }
        drawCircle(Color.White, radius = w * .16f, center = Offset(w / 2, h / 2))
    }
}

@Composable
fun ShuttleMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val plate = Color(0xFF565B63)
        drawRoundRect(
            plate,
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * .14f, h * .28f),
            size = androidx.compose.ui.geometry.Size(w * .72f, h * .36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .09f),
        )
        drawRect(
            plate,
            topLeft = Offset(w * .22f, h * .36f),
            size = androidx.compose.ui.geometry.Size(w * .56f, h * .12f),
        )
        drawCircle(Color.White, radius = w * .075f, center = Offset(w * .32f, h * .72f))
        drawCircle(Color.White, radius = w * .075f, center = Offset(w * .68f, h * .72f))
    }
}

@Composable
fun AgencyMark(routeType: Int, agencyId: Int, tint: Color = K.muted, size: androidx.compose.ui.unit.Dp = 15.dp) {
    when {
        routeType == 711 -> ShuttleMark(size)
        isRail(routeType, agencyId) -> RailMark(size)
        modeOf(routeType) == Mode.FUNICULAR -> CarmelitMark(size)
        modeOf(routeType) == Mode.CABLE || modeOf(routeType) == Mode.GONDOLA -> RakavlitMark(size)
        else -> ModeGlyph(modeOf(routeType), tint, size)
    }
}

@Composable
fun WithTimetable(model: uk.noammm.kav.KavModel, content: @Composable (Net) -> Unit) {
    val net = model.net
    when {
        net != null -> content(net)
        model.netError != null -> Column(Modifier.padding(K.gap4)) {
            Note(T("Could not open the offline timetable: ${model.netError}", "לא ניתן היה לפתוח את לוח הזמנים הלא מקוון: ${model.netError}"))
            Spacer(Modifier.height(K.gap3))
            KavButton(T("Try again", "נסו שוב"), style = ButtonStyle.Secondary, onClick = {
                model.netError = null
                model.netLoadAttempt++
            })
        }
        else -> LoadingBlock(T("Opening the timetable", "פותחים את לוח הזמנים"))
    }
}

@Composable
fun PreciseLocationNudge() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (uk.noammm.kav.hasPreciseLocation(ctx)) return
    if (!uk.noammm.kav.hasLocationPermission(ctx)) return
    Column(Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth().panel(K.rCard).padding(K.gap4)) {
        Column {
            Text(T("Your location is not accurate", "המיקום שלכם אינו מדויק"), style = K.type.headline)
            Text(
                T(
                    "Turn on ‘Use precise location’. Without it Android rounds your position " +
                        "to about a kilometre, which can plan your trip from the wrong town.",
                    "הפעילו את ‘השתמשו במיקום מדויק’. בלעדיו אנדרואיד מעגל את המיקום שלכם " +
                        "לכדי קילומטר בערך, מה שעלול לתכנן את הנסיעה מהעיר הלא נכונה.",
                ),
                style = K.type.subhead, modifier = Modifier.padding(top = 2.dp),
            )
        }
        KavButton(
            T("Change settings", "שינוי הגדרות"), style = ButtonStyle.Secondary, modifier = Modifier.padding(top = K.gap3),
            onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", ctx.packageName, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

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

@Composable
fun AlertRow(category: Int, text: String) {
    if (category < 3 || text.isBlank()) return
    // A quiet tile; the alert's colour comes with its warning glyph.
    Row(
        Modifier.fillMaxWidth().padding(top = K.gap2)
            .clip(RoundedCornerShape(K.rTile))
            .background(K.fill)
            .padding(horizontal = K.gap3, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        AlertPip(category, 18.dp)
        Text(text, style = K.type.subhead, color = K.text, modifier = Modifier.weight(1f))
    }
}

@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    scrolls: Boolean = false,
    content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { then -> if (after == null) { after = then; state.targetState = false } }
    androidx.activity.compose.BackHandler { close(onDismiss) }
    if (state.isIdle && !state.currentState) LaunchedEffect(Unit) { after?.invoke() }
    // Pulled down by its grabber, the sheet follows the finger; let go far enough or fast enough and it closes,
    // otherwise it springs back.
    var pulled by remember { mutableFloatStateOf(0f) }
    val far = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    androidx.compose.animation.AnimatedVisibility(
        state,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize()
                    .animateEnterExit(
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
                    )
                    .background(Color.Black.copy(alpha = .4f))
                    .clickable { close(onDismiss) },
            )
            Column(
                Modifier.fillMaxWidth()
                    .animateEnterExit(
                        enter = androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.spring(
                                dampingRatio = 0.9f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            ),
                        ) { it },
                        exit = androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                        ) { it },
                    )
                    .offset { androidx.compose.ui.unit.IntOffset(0, pulled.roundToInt()) }
                    .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                    .padding(K.gap3)
                    .panel(K.rSheet, solid = true)
                    .clickable(enabled = false) {},
            ) {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).draggable(
                        androidx.compose.foundation.gestures.rememberDraggableState { d -> pulled = (pulled + d).coerceAtLeast(0f) },
                        androidx.compose.foundation.gestures.Orientation.Vertical,
                        onDragStopped = { speed ->
                            if (pulled > far || speed > 1500f) close(onDismiss)
                            else androidx.compose.animation.core.animate(pulled, 0f, speed, spring(dampingRatio = 1f, stiffness = 500f)) { v, _ -> pulled = v }
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(K.capsule).background(K.textTertiary.copy(alpha = .5f)))
                }
                Column(
                    Modifier.then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = K.gap4, end = K.gap4, bottom = K.gap4)
                        .animateContentSize(),
                ) { content(close) }
            }
        }
    }
}

// A stop of Kav's timetable (net, stop), for screens that know the stop by the timetable, or by Kav's own ids.
@Composable
internal fun StopGlyphOrPhoto(
    net: Net, stop: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS, opened: Boolean = false,
) = StopGlyphOrPhoto(-1, mode, thumb, mark, look = look, kind = kind, opened = opened)

// A station by its mode's mark; a stop by a tile that opens Street View where there's a place to look at (look), else
// by its mode (kind).
@Composable
internal fun StopGlyphOrPhoto(
    stopId: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, resolving: Boolean = false, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS, opened: Boolean = false,
) {
    when {
        mode != null -> StationMark(mode, mark)
        look != null -> NoPhoto(thumb, look)
        else -> StopMark(thumb, kind)
    }
}

// Where no photo is asked for: the stop's sign, as a trip's steps draw it, on its own in the photo's place.
@Composable
private fun StopMark(size: Dp, mode: Mode) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) { StationMark(mode, size * .7f) }
}

// A stop with no photo: Street View's glyph on the tile, which opens it there.
@Composable
private fun NoPhoto(thumb: Dp, look: StopLook) {
    Box(
        Modifier.size(thumb).clip(RoundedCornerShape(K.rTile)).background(K.fill)
            .semantics { contentDescription = T("No photo of this stop. See it in Street View", "אין תמונה של התחנה. צפייה ב-Street View") }
            .clickable(role = Role.Button) { StreetViewer.open = look },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(thumb * .5f)) { streetGlyph(K.muted) } }
}

// A small turning arc, for something loading in its place.
@Composable
internal fun Spinner(size: Dp, track: Color = K.surface4, arc: Color = K.accent) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn",
    )
    Canvas(Modifier.size(size)) {
        val w = 2.5.dp.toPx()
        val box = Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), box, style = Stroke(w))
        drawArc(arc, turn, 100f, false, Offset(w / 2, w / 2), box, style = Stroke(w, cap = StrokeCap.Round))
    }
}
