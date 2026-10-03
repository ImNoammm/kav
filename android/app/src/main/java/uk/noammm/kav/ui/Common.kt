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
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net

import java.util.Calendar
import kotlin.math.*

val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun bottomCover(): androidx.compose.ui.unit.Dp =
    WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

val LocalServiceAlertOpener = staticCompositionLocalOf<(Int, String) -> Unit> { { _, _ -> } }

fun hhmm(s: Int): String = "%02d:%02d".format((s / 3600) % 24, (s / 60) % 60)

// Rounded to the minute once, so 1:59:30 is "2h 0m" and 59:45 is "1h 0m", never "1h 60m" or "60 min".
fun dur(s: Int): String {
    val m = (s / 60.0).roundToInt()
    return if (m >= 60) T("${m / 60}h ${m % 60}m", "${m / 60} שע' ${m % 60} דק'")
    else T("$m min", "$m דק'")
}

fun nowSec(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
}

fun relative(t: Int, from: Int = nowSec()): String? {
    val d = t - from
    if (d < 0 || d > 3600) return null
    val m = (d / 60.0).roundToInt()
    return if (m <= 0) T("now", "עכשיו") else T("$m min", "$m דק'")
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
    else T("%.1f km", "%.1f ק\"מ").format(m / 1000)

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

@Composable
fun LineBadge(net: Net, route: Int, modifier: Modifier = Modifier) {
    val rType = net.rType.getOrElse(route) { 3 }
    val plate = plateFor(rType)
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(plate?.fill ?: K.plate)
            .border(1.dp, plate?.edge ?: K.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ModeGlyph(modeOf(rType), plate?.ink ?: K.dim, 12.dp)
        Text(
            net.rShort.getOrElse(route) { "·" }.ifBlank { "·" },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = plate?.ink ?: K.text,
        )
    }
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
            Note(T("Could not open the offline timetable: ${model.netError}", "לא ניתן היה לפתוח את לוח הזמנים המקוון: ${model.netError}"))
            Spacer(Modifier.height(K.gap3))
            Chip(T("Retry", "נסו שוב"), false) {
                model.netError = null
                model.netLoadAttempt++
            }
        }
        else -> LoadingBlock(T("Opening the timetable", "פותחים את לוח הזמנים"))
    }
}

@Composable
fun PreciseLocationNudge() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (uk.noammm.kav.hasPreciseLocation(ctx)) return
    if (!uk.noammm.kav.hasLocationPermission(ctx)) return
    Row(
        Modifier.padding(horizontal = K.gap3, vertical = K.gap2).fillMaxWidth()
            .panel(12.dp)
            .padding(K.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(T("Your location is not accurate", "המיקום שלכם אינו מדויק"), fontSize = 13.sp, color = K.text)
            Text(
                T(
                    "Turn on ‘Use precise location’. Without it Android rounds your position " +
                        "to about a kilometre, which can plan your trip from the wrong town.",
                    "הפעילו את ‘השתמשו במיקום מדויק’. בלעדיו אנדרואיד מעגל את המיקום שלכם " +
                        "לכדי קילומטר בערך, מה שעלול לתכנן את הנסיעה מהעיר הלא נכונה.",
                ),
                fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(K.gap3))
        Text(
            T("Change settings", "שינוי הגדרות"), fontSize = 12.sp, color = K.text,
            modifier = Modifier.panel(999.dp)
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
    val tint = if (category >= 4) K.critical else K.problem
    val open = LocalServiceAlertOpener.current
    Row(
        Modifier.fillMaxWidth().padding(top = K.gap2)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tint, RoundedCornerShape(8.dp))
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
        if (groupId > 0) Text(T.onward, fontSize = 15.sp, color = K.dim)
    }
}

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
    BottomSheet(onDismiss) { close ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fallbackLabel.ifBlank { T("Service alert", "הודעת שירות") },
                fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                T("Close", "סגירה"), fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                    .clickable(role = Role.Button) { close(onDismiss) }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        val list = alerts
        when {
            list == null && !failed ->
                LoadingPulse(T("Fetching the notice", "מביאים את ההודעה"), Modifier.fillMaxWidth().padding(top = K.gap4, bottom = K.gap2))
            failed || list.isNullOrEmpty() ->
                Text(
                    T("The operator published no further detail for this alert.", "המפעיל לא פרסם פרטים נוספים על הודעה זו."),
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
                    .background(Color.Black.copy(alpha = .55f))
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
                    .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                    .padding(K.gap3)
                    .panel(K.rCard, solid = true)
                    .clickable(enabled = false) {}
                    .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(K.gap4)
                    .animateContentSize(),
            ) { content(close) }
        }
    }
}

private fun alertWindow(a: Moovit.ServiceAlert): String? {
    val day = java.text.SimpleDateFormat("d MMM", T.locale)
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

@Composable
internal fun StopGlyphOrPhoto(stopId: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp) {
    var bmp by remember(stopId) { mutableStateOf(StopPhotos.thumbNow(stopId)) }
    LaunchedEffect(stopId) { if (bmp == null && stopId > 0) bmp = StopPhotos.thumb(stopId) }
    val shot = bmp
    if (shot == null) {
        if (mode != null) StationMark(mode, mark)
        return
    }
    var open by remember { mutableStateOf(false) }
    Image(
        shot.asImageBitmap(), T("A photo of the stop", "תמונה של התחנה"),
        Modifier.size(thumb).clip(RoundedCornerShape(10.dp)).clickable { open = true },
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
