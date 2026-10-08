@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit
import java.util.Date
import java.util.Locale

private val hm get() = clockFormat()

@Composable
private fun Chevron(tint: Color = K.surface4, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawLine(tint, Offset(w * mirrorX(.36f), h * .22f), Offset(w * mirrorX(.66f), h * .5f), w * .12f, StrokeCap.Round)
        drawLine(tint, Offset(w * mirrorX(.66f), h * .5f), Offset(w * mirrorX(.36f), h * .78f), w * .12f, StrokeCap.Round)
    }
}

@Composable
private fun TimeArrow(tint: Color = K.dim) {
    Canvas(Modifier.size(10.dp, 30.dp)) {
        val w = size.width; val h = size.height; val x = w * .5f
        drawCircle(tint, w * .22f, Offset(x, h * .12f))
        drawLine(tint, Offset(x, h * .26f), Offset(x, h * .86f), w * .16f, StrokeCap.Round)
        drawLine(tint, Offset(x - w * .26f, h * .68f), Offset(x, h * .88f), w * .16f, StrokeCap.Round)
        drawLine(tint, Offset(x + w * .26f, h * .68f), Offset(x, h * .88f), w * .16f, StrokeCap.Round)
    }
}

@Composable
internal fun ClockGlyph(tint: Color = K.muted, size: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(tint, w * .44f, Offset(w * .5f, w * .5f), style = Stroke(w * .10f))
        drawLine(tint, Offset(w * .5f, w * .5f), Offset(w * .5f, w * .24f), w * .10f, StrokeCap.Round)
        drawLine(tint, Offset(w * .5f, w * .5f), Offset(w * .70f, w * .58f), w * .10f, StrokeCap.Round)
    }
}

@Composable
fun LiveGlyph(tint: Color = K.realtime, size: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .11f
        drawCircle(tint, w * .13f, Offset(w * .24f, h * .80f))
        for (r in listOf(.42f, .70f)) {
            drawArc(
                tint, startAngle = -90f, sweepAngle = 60f, useCenter = false,
                topLeft = Offset(w * .24f - w * r, h * .80f - w * r),
                size = androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun LiveOffGlyph(tint: Color = K.dim, size: androidx.compose.ui.unit.Dp = 11.dp) {
    Box(contentAlignment = Alignment.Center) {
        LiveGlyph(tint, size)
        Canvas(Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height
            drawLine(tint, Offset(w * .14f, h * .86f), Offset(w * .86f, h * .14f), w * .11f, StrokeCap.Round)
        }
    }
}

@Composable
fun WarnGlyph(tint: Color = K.critical, size: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .11f
        val p = Path().apply {
            moveTo(w * .5f, h * .10f); lineTo(w * .94f, h * .86f)
            lineTo(w * .06f, h * .86f); close()
        }
        drawPath(p, tint, style = Stroke(sw, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * .5f, h * .38f), Offset(w * .5f, h * .61f), sw, StrokeCap.Round)
        drawCircle(tint, sw * .60f, Offset(w * .5f, h * .74f))
    }
}

@Composable
fun DepMarkGlyph(d: Moovit.Departure, size: androidx.compose.ui.unit.Dp = 11.dp) {
    val tint = depColour(d)
    when (depMark(d)) {
        DepMark.LIVE, DepMark.LIVE_STILL -> LiveGlyph(tint, size)
        DepMark.LIVE_OFF -> LiveOffGlyph(tint, size)
        DepMark.WARNING -> WarnGlyph(tint, size)
        DepMark.DELAY -> ClockGlyph(tint, size)
        DepMark.CLOCK -> ClockGlyph(K.muted, size)
        DepMark.NONE -> Unit
    }
}

@Composable
private fun Caret(tint: Color = K.muted) {
    Canvas(Modifier.size(10.dp)) {
        val w = size.width; val h = size.height
        drawLine(tint, Offset(w * .18f, h * .38f), Offset(w * .5f, h * .66f), w * .14f, StrokeCap.Round)
        drawLine(tint, Offset(w * .5f, h * .66f), Offset(w * .82f, h * .38f), w * .14f, StrokeCap.Round)
    }
}

@Composable
private fun AccessibleGlyph(tint: Color = K.muted, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .10f
        drawCircle(tint, w * .11f, Offset(w * .46f, h * .13f))
        drawLine(tint, Offset(w * .46f, h * .26f), Offset(w * .46f, h * .52f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .46f, h * .34f), Offset(w * .74f, h * .34f), sw, StrokeCap.Round)
        drawCircle(tint, w * .28f, Offset(w * .46f, h * .66f), style = Stroke(sw))
        drawLine(tint, Offset(w * .62f, h * .56f), Offset(w * .80f, h * .88f), sw, StrokeCap.Round)
    }
}

@Composable
fun PlanHeader(
    from: String,
    to: String,
    fromIsHere: Boolean,
    toIsHere: Boolean,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onSwap: () -> Unit,
    onBack: (() -> Unit)? = null,
    stops: List<String> = emptyList(),
    onAddStop: (() -> Unit)? = null,
    onRemoveStop: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(260)).padding(horizontal = K.gap3, vertical = K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { BackButton(onBack); Spacer(Modifier.width(K.gap2)) }

            Box(Modifier.weight(1f)) {
                Column {
                    Endpoint(from, here = fromIsHere, dot = false, onClick = onFrom)
                    stops.forEachIndexed { i, name ->
                        Spacer(Modifier.height(K.gap2))
                        StopoverRow(name) { onRemoveStop(i) }
                    }
                    Spacer(Modifier.height(K.gap2))
                    Endpoint(to, here = toIsHere, dot = true, onClick = onTo)
                }
                if (stops.isEmpty()) SwapControl(Modifier.align(Alignment.CenterEnd).padding(end = K.gap2), onSwap)
            }
        }
        // Up to three stops on the way, like Moovit's own planner.
        if (onAddStop != null && stops.size < MAX_STOPOVERS) Text(
            T("+ Add a stop", "+ הוספת עצירה"), fontSize = 14.sp, color = K.accent,
            modifier = Modifier.padding(start = if (onBack != null) 52.dp else 0.dp, top = K.gap2)
                .clip(RoundedCornerShape(K.rPill)).clickable(role = Role.Button, onClick = onAddStop)
                .padding(horizontal = K.gap2, vertical = 6.dp),
        )
    }
}

const val MAX_STOPOVERS = 3

@Composable
private fun StopoverRow(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).glassSurface(K.rControl).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) { drawCircle(K.muted, size.width * .30f) }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).semantics { contentDescription = T("Remove stop", "הסרת עצירה") }
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("✕", fontSize = 14.sp, color = K.dim) }
    }
}

@Composable
fun SwapControl(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp).glassSurface(22.dp)
            .semantics { contentDescription = T("Swap start and destination", "החלפת התחלה ויעד") }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SwapGlyph() }
}

@Composable
private fun SwapGlyph() {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width; val h = size.height; val sw = w * .11f
        fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(K.muted, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
        l(.32f, .16f, .32f, .84f); l(.18f, .70f, .32f, .86f); l(.46f, .70f, .32f, .86f)
        l(.68f, .84f, .68f, .16f); l(.54f, .30f, .68f, .14f); l(.82f, .30f, .68f, .14f)
    }
}

@Composable
private fun Endpoint(label: String, here: Boolean, dot: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).glassSurface(K.rControl)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 58.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val w = size.width
            if (dot) drawCircle(K.text, w * .40f, Offset(w * .5f, w * .5f))
            else drawCircle(K.dim, w * .34f, Offset(w * .5f, w * .5f), style = Stroke(w * .16f))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label, fontSize = 14.sp,
            color = if (here) K.live else if (label.endsWith("…")) K.dim else K.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun DepartRow(label: String, onWhen: () -> Unit, order: String, onOrder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = K.gap3, end = K.gap3, top = 2.dp, bottom = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        MenuPill(label, onWhen)
        MenuPill(order, onOrder)
    }
}

@Composable
private fun MenuPill(label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).glassSurface(K.rControl)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = K.text)
        Spacer(Modifier.width(8.dp)); Caret()
    }
}

private class DepLabel(val text: String, val dep: Moovit.Departure, val isNow: Boolean = false, val isClock: Boolean = false)

// Minutes for the next hour, a clock time after that, with the day when it isn't today
// (on Shabbat the first bus can be the next evening).
private fun departLabels(deps: List<Moovit.Departure>, now: Long): Pair<List<DepLabel>, Boolean> {
    val next = deps.sortedBy { it.timeUtc }.filter { it.timeUtc >= now - 60 }.take(3)
    val mins = next.map { d -> ((d.timeUtc - now) / 60).toInt().takeIf { it in 0..60 && !d.rtDropped } }
    val allMinutes = mins.all { it != null } && mins.any { it != null && it > 0 }
    var dayShown = 0L
    val out = next.mapIndexed { i, d ->
        val m = mins[i]
        when {
            m == null -> {
                val days = daysAhead(d.timeUtc, now).coerceAtLeast(0)
                DepLabel(clockLabel(d.timeUtc, if (days == dayShown) 0L else days), d, isClock = true).also { dayShown = days }
            }
            m <= 0 -> DepLabel(T("now", "עכשיו"), d, isNow = true)
            allMinutes -> DepLabel("$m", d)
            else -> DepLabel(T("$m min", "$m דק׳"), d)
        }
    }
    return out to allMinutes
}

private fun daysAhead(t: Long, now: Long): Long {
    val zone = ISRAEL.toZoneId()
    return java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate(),
        java.time.Instant.ofEpochSecond(t).atZone(zone).toLocalDate(),
    )
}

private fun clockLabel(t: Long, days: Long): String {
    val time = hm.format(Date(t * 1000))
    return when (days) {
        0L -> time
        1L -> T("tomorrow $time", "מחר $time")
        else -> java.time.format.DateTimeFormatter.ofPattern("EEEE", T.locale)
            .format(java.time.Instant.ofEpochSecond(t).atZone(ISRAEL.toZoneId())) + " " + time
    }
}

@Composable
internal fun DepartureTimes(deps: List<Moovit.Departure>, now: Long) {
    val (labels, allMinutes) = departLabels(deps, now)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap1), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index == 0 && depMark(label.dep) != DepMark.NONE) {
                    DepMarkGlyph(label.dep, 12.dp); Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (index < labels.lastIndex) label.text + "," else label.text,
                    fontSize = 14.sp, color = depColour(label.dep), fontWeight = FontWeight.Medium,
                )
            }
        }
        if (allMinutes) Text(T("min", "דק׳"), fontSize = 14.sp, color = K.dim)
    }
}

@Composable
internal fun PlatformTag(platform: String) {
    if (platform.isBlank()) return
    Row(
        Modifier.panel(6.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            T("Platform $platform", "רציף $platform"),
            fontSize = 12.sp, color = K.text, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// One badge per ride, as Moovit draws it: every line that can take it, "90 / 29 / 27".
@Composable
private fun RouteChoices(ride: Moovit.Leg, r: Moovit.Resolved) {
    val labels = ride.lineChoices.mapNotNull { id ->
        ride.options.firstOrNull { it.lineId == id }?.shortName?.ifBlank { null } ?: r.line(id)?.number?.ifBlank { null }
    }.distinct()
    // A long list wraps after a slash, never between a number and its slash.
    LineBadgeOnline(ride.lineId, labels.joinToString(" / ") { it.replace(' ', '\u00A0') }.replace(" /", "\u00A0/"), r)
}

fun depColour(d: Moovit.Departure): Color = when (d.state) {
    Moovit.TimeState.REAL_TIME, Moovit.TimeState.REAL_TIME_HIGH -> K.realtime
    Moovit.TimeState.REAL_TIME_MEDIUM -> K.problem
    Moovit.TimeState.REAL_TIME_LOW, Moovit.TimeState.OUT_OF_SHAPE -> K.critical
    Moovit.TimeState.REAL_TIME_DROPPED, Moovit.TimeState.CANCELED -> K.dim
    Moovit.TimeState.STATIC, Moovit.TimeState.STATISTICAL, Moovit.TimeState.FREQUENCY -> K.scheduled
}

enum class DepMark { LIVE, LIVE_STILL, LIVE_OFF, WARNING, CLOCK, DELAY, NONE }

fun depMark(d: Moovit.Departure): DepMark = when {
    d.state == Moovit.TimeState.CANCELED || d.state == Moovit.TimeState.FREQUENCY -> DepMark.NONE
    d.delayed -> DepMark.DELAY
    else -> when (d.state) {
        Moovit.TimeState.REAL_TIME, Moovit.TimeState.REAL_TIME_HIGH,
        Moovit.TimeState.REAL_TIME_MEDIUM -> DepMark.LIVE
        Moovit.TimeState.REAL_TIME_LOW -> DepMark.LIVE_STILL
        Moovit.TimeState.REAL_TIME_DROPPED -> DepMark.LIVE_OFF
        Moovit.TimeState.OUT_OF_SHAPE -> DepMark.WARNING
        else -> DepMark.CLOCK
    }
}

@Composable
fun ItineraryCard(it: Moovit.Itinerary, r: Moovit.Resolved, onClick: (() -> Unit)? = null) {
    val now = System.currentTimeMillis() / 1000
    Row(
        Modifier.fillMaxWidth()
            .panel(K.rCard)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.widthIn(min = 108.dp).padding(7.dp)
                .clip(RoundedCornerShape(13.dp)).border(0.5.dp, K.text.copy(alpha = .14f), RoundedCornerShape(13.dp))
                .padding(horizontal = 11.dp, vertical = K.gap3),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                val big = durationValue(it.durationMin)
                Text(
                    big, fontSize = if (big.length > 3) 21.sp else 26.sp, color = K.text,
                    fontWeight = FontWeight.Normal, maxLines = 1,
                )
                val unit = durationUnit(it.durationMin)
                if (unit.isNotEmpty()) Text(
                    " $unit", fontSize = 14.sp, color = K.muted, modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeArrow()
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(hm.format(Date(it.dep * 1000)), fontSize = 13.sp, color = K.dim)
                    Text(hm.format(Date(it.arr * 1000)), fontSize = 13.sp, color = K.text)
                }
            }
        }

        Column(Modifier.weight(1f).padding(end = K.gap3, top = K.gap3, bottom = K.gap3, start = K.gap2)) {
            RouteStrip(it, r)
            Spacer(Modifier.height(K.gap2))
            DepartureLine(it, r, now)
            it.legs.firstOrNull { leg -> leg.kind == Moovit.LegKind.TAXI }?.let { taxi ->
                Spacer(Modifier.height(K.gap2))
                GettButton(taxi)
            }
            val chips = cardChips(it)
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(K.gap2))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { c -> InfoChip(c.first, c.second) }
                }
            }
        }
    }
}

private fun durationValue(min: Int): String =
    if (min >= 60) T("${min / 60}h ${min % 60}m", "${min / 60}ש׳ ${min % 60}דק׳") else "$min"

private fun durationUnit(min: Int): String = if (min >= 60) "" else if (min == 1) T("min", "דק׳") else T("mins", "דק׳")

private class StripItem(
    val kind: Moovit.LegKind,
    val minutes: Int,
    val alert: Int = 0,
    val ride: Moovit.Leg? = null,
)

private fun stripItems(it: Moovit.Itinerary): List<StripItem> {
    val out = ArrayList<StripItem>()
    for (l in it.legs) {
        if (l.pathway) continue
        when (l.kind) {
            Moovit.LegKind.WALK -> {
                val last = out.lastOrNull()
                if (last != null && last.kind == Moovit.LegKind.WALK) {
                    out[out.size - 1] = StripItem(Moovit.LegKind.WALK, last.minutes + l.minutes)
                } else out.add(StripItem(Moovit.LegKind.WALK, l.minutes))
            }
            Moovit.LegKind.RIDE, Moovit.LegKind.TAXI, Moovit.LegKind.BIKE -> {
                val alert = it.legs.getOrNull(it.legs.indexOf(l) - 1)
                    ?.takeIf { w -> w.kind == Moovit.LegKind.WAIT }?.alertCategory ?: 0
                out.add(StripItem(l.kind, l.minutes, alert, l))
            }
            else -> {}
        }
    }
    return out.filter { s -> s.kind != Moovit.LegKind.WALK || s.minutes >= 1 }
}

private fun showMinutes(leg: StripItem): Boolean = leg.minutes >= 5

private val PIP_OVERHANG = 5.dp

@Composable
internal fun RouteStrip(it: Moovit.Itinerary, r: Moovit.Resolved) {
    val shown = stripItems(it)
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEachIndexed { i, leg ->
            // Each arrow travels with the step after it, so a wrapped row never leaves one at a line's end.
            Row(
                Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            if (i > 0) Chevron(size = 11.dp)
            when (leg.kind) {
                Moovit.LegKind.RIDE -> Box(
                    Modifier.padding(vertical = PIP_OVERHANG, horizontal = PIP_OVERHANG),
                ) {
                    leg.ride?.let { RouteChoices(it, r) }
                    if (leg.alert >= 3) Box(
                        Modifier.align(Alignment.TopEnd).offset(x = PIP_OVERHANG, y = -PIP_OVERHANG),
                    ) {
                        AlertPip(leg.alert)
                    }
                }
                Moovit.LegKind.TAXI -> Row(
                    Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeGlyph(Mode.TAXI, K.muted, 19.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Gett", fontSize = 14.sp, color = K.text)
                }
                Moovit.LegKind.BIKE -> Row(
                    Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    BikeGlyph()
                    if (showMinutes(leg)) {
                        Spacer(Modifier.width(4.dp))
                        Text("${leg.minutes}", fontSize = 14.sp, color = K.text)
                    }
                }
                else -> Row(
                    Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    WalkGlyph(K.muted, 18.dp)
                    if (showMinutes(leg)) {
                        Spacer(Modifier.width(4.dp))
                        Text("${leg.minutes}", fontSize = 14.sp, color = K.text)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun LineBadgeOnline(lineId: Int, shortName: String, r: Moovit.Resolved) {
    val info = r.line(lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val label = shortName.ifBlank { null } ?: info?.number?.ifBlank { null }
    val plate = plateFor(rt, agency)
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).background(plate?.fill ?: K.badgePlate)
            .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = if (label == null) 5.dp else 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgencyMark(rt, agency, plate?.ink ?: K.muted, 14.dp)
            if (label != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    label, fontSize = 15.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun BikeGlyph(tint: Color = K.muted) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height; val sw = w * .08f
        drawCircle(tint, w * .22f, Offset(w * .24f, h * .70f), style = Stroke(sw))
        drawCircle(tint, w * .22f, Offset(w * .76f, h * .70f), style = Stroke(sw))
        drawLine(tint, Offset(w * .24f, h * .70f), Offset(w * .46f, h * .38f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .46f, h * .38f), Offset(w * .76f, h * .70f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .46f, h * .38f), Offset(w * .66f, h * .38f), sw, StrokeCap.Round)
    }
}

@Composable
private fun DepartureLine(it: Moovit.Itinerary, r: Moovit.Resolved, now: Long) {
    val taxi = it.legs.take(2).firstOrNull { l -> l.kind == Moovit.LegKind.TAXI }
    if (taxi != null) {
        val mins = (((taxi.dep - now) + 59) / 60).coerceAtLeast(0)
        Text(
            T(
                "Pickup in $mins ${if (mins == 1L) "min" else "mins"}",
                "איסוף בעוד $mins דק׳",
            ),
            fontSize = 13.sp, color = K.dim,
        )
        return
    }
    val rideIndex = it.legs.indexOfFirst { leg -> leg.kind == Moovit.LegKind.RIDE }
    val ride = it.legs.getOrNull(rideIndex)
    val wait = it.legs.getOrNull(rideIndex - 1)?.takeIf { leg -> leg.kind == Moovit.LegKind.WAIT }
    val stop = (wait?.fromStop ?: ride?.fromStop)?.takeIf { id -> id > 0 }?.let { id -> r.stopName(id) }
    val deps = ride?.let { leg -> Moovit.boardingOptions(leg, wait).flatMap { (option, boarding) ->
        r.departures(option, boarding)
    } }.orEmpty()
    val (labels, allMinutes) = departLabels(deps, now)
    val fare = if (it.fare >= 0) "%s%.2f".format(Locale.US, it.currency.ifBlank { "" }, it.fare / 100.0) else null

    if (labels.isEmpty() && stop == null && fare == null) return
    val lead = labels.firstOrNull()?.dep
    Text(
        buildAnnotatedString {
            if (labels.isNotEmpty()) {
                val first = labels.first()
                val prefix = if (first.isNow || first.isClock) T("Leaves ", "יציאה ") else T("Leaves in ", "יציאה בעוד ")
                withStyle(SpanStyle(color = K.dim)) { append(prefix) }
                if (lead != null && depMark(lead) != DepMark.NONE) appendInlineContent(MARK, "·")
            }
            labels.forEachIndexed { i, l ->
                if (i > 0) withStyle(SpanStyle(color = K.dim)) { append(", ") }
                withStyle(SpanStyle(color = depColour(l.dep), fontWeight = FontWeight.Medium)) {
                    append(l.text)
                }
            }
            if (labels.isNotEmpty() && allMinutes) {
                withStyle(SpanStyle(color = K.dim)) { append(T(" mins", " דק׳")) }
            }
            if (stop != null) {
                withStyle(SpanStyle(color = K.dim)) {
                    append(if (labels.isEmpty()) T("From ", "מ־") else T(" from ", " מ־"))
                    // Isolated, so a Hebrew name in an English line doesn't pull the fare's bullet to its other side.
                    append("\u2068$stop\u2069")
                }
            }
            if (fare != null) withStyle(SpanStyle(color = K.dim)) { append(" • $fare") }
        },
        inlineContent = mapOf(
            // The mark keeps a little room of its own, so the first time never touches it.
            MARK to InlineTextContent(
                Placeholder(17.sp, 13.sp, PlaceholderVerticalAlign.TextCenter),
            ) {
                val size = with(androidx.compose.ui.platform.LocalDensity.current) { 13.sp.toDp() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    lead?.let { DepMarkGlyph(it, size) }
                }
            },
        ),
        fontSize = 13.sp, color = K.dim, lineHeight = 18.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

private const val MARK = "mark"

private fun cardChips(it: Moovit.Itinerary): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(2)
    if (it.accessible) out.add("access" to T("Step-free", "נגיש לנכים"))
    if (Shown.co2 && it.co2g >= 0) out.add("co2" to co2(it.co2g))
    return out
}

@Composable
private fun InfoChip(kind: String, label: String) {
    val co2 = kind == "co2"
    Row(
        Modifier.panel(999.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            "access" -> { AccessibleGlyph(); Spacer(Modifier.width(5.dp)) }
            "co2" -> { GlobeGlyph(); Spacer(Modifier.width(5.dp)) }
        }
        Text(label, fontSize = 12.sp, color = if (co2) K.text else K.muted)
    }
}

@Composable
internal fun GlobeGlyph(
    tint: Color = K.muted,
    cut: Color = K.co2Pill,
    size: androidx.compose.ui.unit.Dp = 13.dp,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val r = w * .5f
        drawCircle(tint, r, Offset(r, r))
        val sw = w * .085f
        drawLine(cut, Offset(r, w * .06f), Offset(r, w * .94f), sw)
        drawLine(cut, Offset(w * .10f, w * .36f), Offset(w * .90f, w * .36f), sw)
        drawLine(cut, Offset(w * .10f, w * .64f), Offset(w * .90f, w * .64f), sw)
    }
}
