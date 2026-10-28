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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
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

val LocalServiceAlertOpener = staticCompositionLocalOf<(Int, String) -> Unit> { { _, _ -> } }

// Opens a ride's line in the Lines tab at the stop it is boarded from, for all its departures there.
val LocalLineOpener = staticCompositionLocalOf<((Moovit.Leg, Moovit.Resolved) -> Unit)?> { null }

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
fun AlertRow(category: Int, text: String, groupId: Int = 0) {
    if (category < 3 || text.isBlank()) return
    val open = LocalServiceAlertOpener.current
    // A quiet tile; the alert's colour comes with its warning glyph, and a chevron where it opens the notice.
    Row(
        Modifier.fillMaxWidth().padding(top = K.gap2)
            .clip(RoundedCornerShape(K.rTile))
            .background(K.fill)
            .then(
                if (groupId > 0) Modifier.clickable(role = Role.Button) { open(groupId, text) }
                else Modifier,
            )
            .padding(horizontal = K.gap3, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        AlertPip(category, 18.dp)
        Text(text, style = K.type.subhead, color = K.text, modifier = Modifier.weight(1f))
        if (groupId > 0) androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = K.textTertiary, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ServiceAlertSheet(groupId: Int, fallbackLabel: String, onDismiss: () -> Unit) {
    var alerts by remember(groupId) { mutableStateOf<List<Moovit.ServiceAlert>?>(null) }
    var failed by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        val s = runCatching { Online.open() }.getOrNull() ?: run { failed = true; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { Moovit.serviceAlerts(s, listOf(groupId)) } }
            .onSuccess { alerts = it }
            .onFailure { failed = true }
    }
    BottomSheet(onDismiss) { close ->
        SheetHeader(fallbackLabel.ifBlank { T("Service alert", "הודעת שירות") }, { close(onDismiss) })
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        val list = alerts
        when {
            list == null && !failed ->
                LoadingPulse(T("Fetching the notice", "מביאים את ההודעה"), Modifier.fillMaxWidth().padding(top = K.gap4, bottom = K.gap2))
            failed || list.isNullOrEmpty() ->
                Text(
                    T("The operator published no further detail for this alert.", "המפעיל לא פרסם פרטים נוספים על הודעה זו."),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap3),
                )
            else -> list.forEachIndexed { i, a ->
                if (i > 0) {
                    Spacer(Modifier.height(K.gap3))
                    Separator(0.dp)
                }
                Spacer(Modifier.height(K.gap3))
                a.title.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = K.type.headline)
                    Spacer(Modifier.height(K.gap2))
                }
                alertWindow(a)?.let {
                    Text(it, style = K.type.caption)
                    Spacer(Modifier.height(K.gap2))
                }
                alertText(a)?.let {
                    Text(it, style = K.type.body, color = K.textSecondary)
                }
            }
        }
        }
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

private fun alertWindow(a: Moovit.ServiceAlert): String? {
    val day = java.text.SimpleDateFormat("d MMM", T.locale).apply { timeZone = ISRAEL }
    fun at(t: Long) = day.format(java.util.Date(t * 1000))
    return when {
        a.activeFrom > 0 && a.activeTo > 0 -> T("${at(a.activeFrom)} to ${at(a.activeTo)}", "${at(a.activeFrom)} עד ${at(a.activeTo)}")
        a.activeFrom > 0 -> T("From ${at(a.activeFrom)}", "מ-${at(a.activeFrom)}")
        a.activeTo > 0 -> T("Until ${at(a.activeTo)}", "עד ${at(a.activeTo)}")
        else -> null
    }
}

private fun alertText(a: Moovit.ServiceAlert): String? {
    val raw = a.body.takeIf { it.isNotBlank() } ?: return null
    if (!a.html && !raw.contains('<')) return raw.trim()
    return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString().replace(Regex("\n{3,}"), "\n\n").trim().ifBlank { null }
}

// A stop of Kav's timetable (net, stop) by its photo: its Moovit id, which the photo is filed under, is found and kept on
// the way, whatever the live times come from. For screens that know the stop by the timetable, or by Kav's own ids.
@Composable
internal fun StopGlyphOrPhoto(
    net: Net, stop: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS,
) {
    var id by remember(net, stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    var looked by remember(net, stop) { mutableStateOf(id > 0) }
    LaunchedEffect(net, stop, StopPhotos.enabled) {
        if (StopPhotos.enabled) id = findMoovitId(net, stop, id) { looked = true } else looked = true
    }
    StopGlyphOrPhoto(id, mode, thumb, mark, resolving = !looked, look = look, kind = kind)
}

// The stop's Moovit id, for its photo and live times. A search that failed, as opposed to one that found nothing, is
// tried again, less and less often, for as long as the stop is on screen.
internal suspend fun findMoovitId(net: Net, stop: Int, known: Int, onTried: () -> Unit): Int {
    var id = known
    var wait = 2_000L
    while (id <= 0) {
        id = StopPhotos.idOf(net, stop) ?: -1
        onTried()
        if (id > 0 || StopPhotos.knownMissing(net, stop)) break
        delay(wait)
        wait = (wait * 2).coerceAtMost(60_000L)
    }
    return id
}

// look: where the stop is, for a stop with no photo to open in Street View instead. kind: the stop's mode, drawn where
// its photo goes until one arrives, or for good when there is none.
@Composable
internal fun StopGlyphOrPhoto(
    stopId: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, resolving: Boolean = false, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS,
) {
    // Photos off: nothing asked of Moovit, and the place kept.
    if (!StopPhotos.enabled) {
        when {
            mode != null -> StationMark(mode, mark)
            look != null -> NoPhoto(thumb, look)
            else -> StopTile(thumb, kind)
        }
        return
    }
    var bmp by remember(stopId) { mutableStateOf(StopPhotos.thumbNow(stopId)) }
    var has by remember(stopId) { mutableStateOf(StopPhotos.hasPhotosNow(stopId)) }
    var busy by remember(stopId) { mutableStateOf(bmp == null && stopId > 0) }
    // Moovit's list of the stop's photos, then the thumbnail: on a weak connection either can fail, and is asked
    // again, less and less often, for as long as the stop is on screen.
    LaunchedEffect(stopId) {
        if (bmp != null || stopId <= 0) return@LaunchedEffect
        var wait = 2_000L
        while (true) {
            has = StopPhotos.hasPhotos(stopId)
            if (has == true) bmp = StopPhotos.thumb(stopId)
            busy = false
            if (bmp != null || has == false) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }
    val shot = bmp
    // A trip step keeps its mode mark unless a photo is on its way; a stop known to have none opens Street View.
    if (shot == null && mode != null && !((busy || resolving) && has == true)) {
        StationMark(mode, mark)
        return
    }
    if (shot == null && !resolving && (has == false || stopId <= 0) && look != null) {
        NoPhoto(thumb, look)
        return
    }
    // Elsewhere the tile keeps the photo's place, and the photo fades in over it.
    androidx.compose.animation.Crossfade(shot, animationSpec = tween(220), label = "stopPhoto") { photo ->
        if (photo == null) StopTile(thumb, kind) else StopPhoto(stopId, photo, thumb)
    }
}

@Composable
private fun StopPhoto(stopId: Int, shot: android.graphics.Bitmap, thumb: Dp) {
    var open by remember { mutableStateOf(false) }
    Image(
        shot.asImageBitmap(), T("A photo of the stop", "תמונה של התחנה"),
        Modifier.size(thumb).clip(RoundedCornerShape(K.rTile)).clickable { open = true },
        contentScale = ContentScale.Crop,
    )
    if (open) {
        var full by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(stopId) { full = StopPhotos.full(stopId) }
        Dialog(onDismissRequest = { open = false }) {
            Image(
                (full ?: shot).asImageBitmap(), null,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = false },
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

// Where a stop's photo goes while there is none: the stop's mode on a quiet tile of the photo's size and shape.
@Composable
private fun StopTile(size: Dp, mode: Mode) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(K.rTile)).background(K.fill),
        contentAlignment = Alignment.Center,
    ) { ModeGlyph(mode, K.muted, size * .44f) }
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
