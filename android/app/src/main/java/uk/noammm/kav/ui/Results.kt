@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ranked route choices: duration, route, departure and useful trip details. */

private val hm = SimpleDateFormat("HH:mm", Locale.US)

/* glyphs */

@Composable
private fun Chevron(tint: Color = K.surface4, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawLine(tint, Offset(w * mirrorX(.36f), h * .22f), Offset(w * mirrorX(.66f), h * .5f), w * .12f, StrokeCap.Round)
        drawLine(tint, Offset(w * mirrorX(.66f), h * .5f), Offset(w * mirrorX(.36f), h * .78f), w * .12f, StrokeCap.Round)
    }
}

/** The vertical depart→arrive arrow beside the two times. */
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

/** Moovit's real-time mark: broadcast arcs opening off a point. */
@Composable
fun LiveGlyph(tint: Color = K.live, size: androidx.compose.ui.unit.Dp = 11.dp) {
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

/** The same mark struck through: the metro has tracking, this trip's has been lost. */
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

/** A warning triangle: the vehicle has left its route (MVVehicleStatus OUT_OF_SHAPE). */
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

/** Moovit's anim_traffic_delay, still: a clock with a widening delay arc behind it. */
@Composable
fun DelayGlyph(tint: Color = K.problem, size: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = w * .11f
        drawArc(
            tint, startAngle = 40f, sweepAngle = 285f, useCenter = false,
            topLeft = Offset(w * .06f, w * .06f),
            size = androidx.compose.ui.geometry.Size(w * .88f, w * .88f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(w * .5f, w * .5f), Offset(w * .5f, w * .26f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .5f, w * .5f), Offset(w * .72f, w * .60f), sw, StrokeCap.Round)
    }
}

/**
 * The one place a departure's leading mark is drawn. Moovit animates its real-time
 * mark and freezes it at LOW certainty; Kav's is still in both cases, so the two
 * differ only in colour, everything else follows the table in [depMark].
 */
@Composable
fun DepMarkGlyph(d: Moovit.Departure, size: androidx.compose.ui.unit.Dp = 11.dp) {
    val tint = depColour(d)
    when (depMark(d)) {
        DepMark.LIVE, DepMark.LIVE_STILL -> LiveGlyph(tint, size)
        DepMark.LIVE_OFF -> LiveOffGlyph(tint, size)
        DepMark.WARNING -> WarnGlyph(tint, size)
        DepMark.DELAY -> DelayGlyph(tint, size)
        DepMark.CLOCK -> ClockGlyph(K.muted, size)
        DepMark.NONE -> Unit
    }
}

@Composable
private fun PlusGlyph(tint: Color = K.muted) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        drawLine(tint, Offset(w * .5f, w * .18f), Offset(w * .5f, w * .82f), w * .10f, StrokeCap.Round)
        drawLine(tint, Offset(w * .18f, w * .5f), Offset(w * .82f, w * .5f), w * .10f, StrokeCap.Round)
    }
}

@Composable
private fun MapGlyph(tint: Color = K.muted) {
    Canvas(Modifier.size(15.dp)) {
        val w = size.width; val h = size.height; val sw = w * .09f
        fun v(x: Float, y1: Float, y2: Float) =
            drawLine(tint, Offset(w * x, h * y1), Offset(w * x, h * y2), sw, StrokeCap.Round)
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), sw, StrokeCap.Round)
        seg(.08f, .26f, .36f, .16f); seg(.36f, .16f, .64f, .30f); seg(.64f, .30f, .92f, .18f)
        seg(.08f, .82f, .36f, .72f); seg(.36f, .72f, .64f, .86f); seg(.64f, .86f, .92f, .74f)
        seg(.08f, .26f, .08f, .82f); seg(.92f, .18f, .92f, .74f)
        v(.36f, .16f, .72f); v(.64f, .30f, .86f)
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

/* the plan header: two endpoints, swap, add stop */

@Composable
fun PlanHeader(
    from: String,
    to: String,
    fromIsHere: Boolean,
    toIsHere: Boolean,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onSwap: () -> Unit,
    onAddStop: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { BackButton(onBack); Spacer(Modifier.width(K.gap2)) }

            // the two fields, with the swap button straddling the seam between them
            Box(Modifier.weight(1f)) {
                Column {
                    Endpoint(from, here = fromIsHere, dot = false, onClick = onFrom)
                    Spacer(Modifier.height(K.gap2))
                    Endpoint(to, here = toIsHere, dot = true, onClick = onTo)
                }
                SwapControl(Modifier.align(Alignment.CenterEnd).padding(end = K.gap2), onSwap)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (onAddStop != null) Box(
                    Modifier.size(44.dp).semantics { contentDescription = T("Add stop", "הוספת תחנה") }
                        .clickable(role = Role.Button, onClick = onAddStop), contentAlignment = Alignment.Center,
                ) { PlusGlyph() }
            }
        }
    }
}

/**
 * The control that straddles the seam between a start and a destination. Home shows
 * the same two ends as this header does, so it swaps them with the same button rather
 * than a second one drawn to look like it.
 */
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
        // origin is a ring, destination is a filled pin, Moovit's own distinction
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

/** "Depart now ▾" on the left, "View" (the map) on the right. */
@Composable
fun DepartRow(label: String, onWhen: () -> Unit, onMap: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = K.gap3, end = K.gap3, top = 2.dp, bottom = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp)).background(K.plate)
                .clickable(onClick = onWhen).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, color = K.text)
            Spacer(Modifier.width(8.dp)); Caret()
        }
        Spacer(Modifier.weight(1f))
        if (onMap != null) Row(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp)).background(K.plate)
                .clickable(onClick = onMap).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapGlyph(); Spacer(Modifier.width(7.dp))
            Text(T("View", "תצוגה"), fontSize = 13.sp, color = K.text)
        }
    }
}

/* the card */

/** Minutes-from-now while that is short enough to be useful, else a clock time. */
private class DepLabel(val text: String, val dep: Moovit.Departure)

private fun departLabels(deps: List<Moovit.Departure>, now: Long): Pair<List<DepLabel>, Boolean> {
    val next = deps.sortedBy { it.timeUtc }.filter { it.timeUtc >= now - 60 }.take(3)
    var allMinutes = next.isNotEmpty()
    val out = next.map { d ->
        val m = ((d.timeUtc - now) / 60).toInt()
        // Moovit's com.moovit.util.time.d.e: minutes only within
        // absoluteTimeThresholdInMinutes (60 in its own resources), and never once
        // real-time was dropped, a stale estimate is not worth counting down.
        val relative = m in 0..60 && !d.rtDropped
        val text = if (relative) (if (m <= 0) T("now", "עכשיו") else "$m")
                   else { allMinutes = false; hm.format(Date(d.timeUtc * 1000)) }
        DepLabel(text, d)
    }
    return out to allMinutes
}

/** Shared waiting/detail row; every departure retains its own presentation state. */
@Composable
internal fun DepartureTimes(deps: List<Moovit.Departure>, now: Long) {
    val (labels, allMinutes) = departLabels(deps, now)
    // a normal word space, not the 8dp that separated these when nothing else did:
    // with commas carrying the separation the wider gap read as three chips, not a list
    FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap1), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index == 0 && depMark(label.dep) != DepMark.NONE) {
                    DepMarkGlyph(label.dep, 12.dp); Spacer(Modifier.width(4.dp))
                }
                // the comma belongs to the time it follows, so it takes that time's
                // colour rather than breaking the run with a neutral separator
                Text(
                    if (index < labels.lastIndex) label.text + "," else label.text,
                    fontSize = 14.sp, color = depColour(label.dep), fontWeight = FontWeight.Medium,
                )
            }
        }
        if (allMinutes) Text(T("min", "דק׳"), fontSize = 14.sp, color = K.dim)
    }
}

/** Alternatives are choices within one ride, so separate them with / rather than a transfer arrow. */
@Composable
internal fun RouteChoices(ride: Moovit.Leg, r: Moovit.Resolved) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ride.lineChoices.forEachIndexed { index, id ->
            if (index > 0) Text("/", fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = 4.dp))
            LineBadgeOnline(id, ride.options.firstOrNull { it.lineId == id }?.shortName.orEmpty(), r)
        }
    }
}

/**
 * Moovit's `TimePresentationType.primaryColorAttrId`, state for state. Its own dark
 * theme resolves colorLive to a green, colorProblem to an amber, colorCritical to a
 * pink and colorOnSurface to a pale grey; Kav keeps its own quieter tokens for those
 * four roles (D11) but assigns them to exactly the same states.
 */
fun depColour(d: Moovit.Departure): Color = when (d.state) {
    Moovit.TimeState.REAL_TIME, Moovit.TimeState.REAL_TIME_HIGH -> K.live
    Moovit.TimeState.REAL_TIME_MEDIUM -> K.problem
    Moovit.TimeState.REAL_TIME_LOW, Moovit.TimeState.OUT_OF_SHAPE -> K.critical
    Moovit.TimeState.REAL_TIME_DROPPED, Moovit.TimeState.CANCELED -> K.dim
    Moovit.TimeState.STATIC, Moovit.TimeState.STATISTICAL, Moovit.TimeState.FREQUENCY -> K.scheduled
}

/**
 * The mark that leads a time, from the same table: an animated signal while a vehicle
 * is tracked (a still one once the estimate is weak), a struck-through signal when
 * tracking was lost, a warning when the vehicle has left its route, a clock for a
 * plain timetable. Heavy or medium traffic replaces it with the delay glyph, keeping
 * the state's colour. Cancelled and frequency-based services carry no mark at all.
 */
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
            .clip(RoundedCornerShape(K.rCard))
            .background(K.surface1)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        // left: how long, and between which two clock times
        Column(
            // A floor, not a width. 108dp was measured against "2h 21m"; the same
            // sentence in Hebrew is longer, and a fixed box clipped it silently, which
            // in a right-to-left line takes the LAST word off the LEFT edge: the card
            // read "2 hours 21" with the unit gone. English is unchanged, it never
            // reaches the floor; Hebrew takes the few dp it needs and the strip reflows.
            Modifier.widthIn(min = 108.dp).padding(7.dp)
                .clip(RoundedCornerShape(13.dp)).background(K.sunken)
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

        // right: the route itself, then when it goes, then what it is
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
    // The unit is glued to its number in Hebrew too, the way "21m" glues it. The
    // headline box is 108dp and the spaced-out form did not fit in it.
    if (min >= 60) T("${min / 60}h ${min % 60}m", "${min / 60}ש׳ ${min % 60}דק׳") else "$min"

private fun durationUnit(min: Int): String = if (min >= 60) "" else if (min == 1) T("min", "דק׳") else T("mins", "דק׳")

/** One entry in the strip: consecutive walk legs read as a single walk. */
private class StripItem(
    val kind: Moovit.LegKind,
    val minutes: Int,
    val alert: Int = 0,
    val ride: Moovit.Leg? = null,
)

private fun stripItems(it: Moovit.Itinerary): List<StripItem> {
    val out = ArrayList<StripItem>()
    for (l in it.legs) {
        // the walk inside a station is not a leg of the journey to Moovit's strip
        if (l.pathway) continue
        when (l.kind) {
            // a street walk followed by a walk inside the station is one walk to a rider
            Moovit.LegKind.WALK -> {
                val last = out.lastOrNull()
                if (last != null && last.kind == Moovit.LegKind.WALK) {
                    out[out.size - 1] = StripItem(Moovit.LegKind.WALK, last.minutes + l.minutes)
                } else out.add(StripItem(Moovit.LegKind.WALK, l.minutes))
            }
            Moovit.LegKind.RIDE, Moovit.LegKind.TAXI, Moovit.LegKind.BIKE -> {
                // the alert rides on the WAIT leg just before this one
                val alert = it.legs.getOrNull(it.legs.indexOf(l) - 1)
                    ?.takeIf { w -> w.kind == Moovit.LegKind.WAIT }?.alertCategory ?: 0
                out.add(StripItem(l.kind, l.minutes, alert, l))
            }
            else -> {}
        }
    }
    // a 0-minute walk is a step off the kerb, not a leg worth a slot. A 0-minute
    // BIKE leg is different, Moovit still draws the bicycle at the far end of
    // "🚲 14 › 655 › 🚲", it just has no number to print on it.
    return out.filter { s -> s.kind != Moovit.LegKind.WALK || s.minutes >= 1 }
}

/**
 * Moovit prints the minutes beside the FIRST leg only, and only when that leg is a
 * walk or a ride long enough to plan around. Measured against the real app for one
 * trip (Rosh HaAyin → Azrieli, 2026-09-08): `🚶 › 472 › 🚶` with a 4-minute first walk
 * printed nothing, `🚶 7 › 283 › 🚶` printed only the 7, `🚶 › 74 › 🚶 › 657 › 🚶`
 * printed nothing although its LAST walk is 7 minutes, and `🚲 14 › 655 › 🚲` printed
 * only the 14. Kav used to print every leg's minutes, which is the loudest difference
 * between the two strips.
 */
private fun showMinutes(index: Int, leg: StripItem): Boolean =
    index == 0 && leg.minutes >= 5

/**
 * How far the alert pip hangs off the badge's top-right corner. The strip reserves this
 * on every ride item so the overhang has somewhere to land: drawn outside the item's
 * bounds the pip was sliced flat by the card's rounded clip. Padding both sides
 * vertically keeps the badge centred against the walk glyphs beside it.
 */
private val PIP_OVERHANG = 5.dp

/** walk › [line] › walk, the strip that tells you the shape of the trip at a glance. */
@Composable
private fun RouteStrip(it: Moovit.Itinerary, r: Moovit.Resolved) {
    val shown = stripItems(it)
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEachIndexed { i, leg ->
            if (i > 0) Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { Chevron(size = 11.dp) }
            when (leg.kind) {
                // The pip sits half off the badge's top-right corner, so the row has to
                // reserve that overhang: drawn outside the strip's bounds it was sliced
                // flat by the card's own rounded clip. Padding the box and pulling the
                // badge back by the same amount keeps the badge where it was and gives
                // the pip somewhere to be.
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
                    if (showMinutes(i, leg)) {
                        Spacer(Modifier.width(4.dp))
                        Text("${leg.minutes}", fontSize = 14.sp, color = K.text)
                    }
                }
                else -> Row(
                    Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    WalkGlyph(K.muted, 18.dp)
                    if (showMinutes(i, leg)) {
                        Spacer(Modifier.width(4.dp))
                        Text("${leg.minutes}", fontSize = 14.sp, color = K.text)
                    }
                }
            }
        }
    }
}

/**
 * A neutral badge carrying the line's mark and number, sized so that a five-leg strip
 * ("🚶 › 74 › 🚶 › 657 › 🚶") still fits on one line: Kav's wider badge was wrapping it onto two.
 *
 * Until the line's group has been fetched there is no number to print, and an internal
 * id is not one: an unresolved badge shows its mode mark alone and fills in when the
 * name arrives, rather than flashing "2781223" at you.
 */
@Composable
private fun LineBadgeOnline(lineId: Int, shortName: String, r: Moovit.Resolved) {
    val info = r.line(lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    // A rail leg carries its own train number (tripShortName, "343") and that is what
    // the badge shows; a bus has none, and takes the number of its line group ("282").
    // Rail line GROUPS are named by their whole route, which is not a badge.
    val label = shortName.ifBlank { null } ?: info?.number?.ifBlank { null }
    Column(
        Modifier.width(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(K.badgePlate)
            .border(1.dp, K.borderStrong, RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = if (label == null) 5.dp else 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgencyMark(rt, agency, K.muted, 14.dp)
            if (label != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
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

/** "Leaves in 29, 59, 20:19 from <stop> • ₪14.50"
 *
 * ONE paragraph, not a Row of pieces. Moovit's line wraps as a single run of text, so
 * "Leaves in" sits on the first line with the numbers beside it; laying it out as
 * `Row { Text("Leaves in") ; glyph ; Text(rest) }` centres the label against a
 * two-line block and drops it visibly below the first line. The live/clock mark is
 * inline content so it flows with the words instead of anchoring its own column.
 */
@Composable
private fun DepartureLine(it: Moovit.Itinerary, r: Moovit.Resolved, now: Long) {
    // A trip that STARTS in a taxi is described by the car, exactly as Moovit does it:
    // "Pickup in 4 mins", not the departures of a train two legs later.
    // (the taxi is preceded by its own MVWaitToTaxiLeg, so look at the first two)
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
    val fare = if (it.fare >= 0) "%s%.2f".format(it.currency.ifBlank { "" }, it.fare / 100.0) else null

    if (labels.isEmpty() && stop == null && fare == null) return
    val lead = labels.firstOrNull()?.dep
    Text(
        buildAnnotatedString {
            if (labels.isNotEmpty()) {
                withStyle(SpanStyle(color = K.dim)) { append(T("Leaves in ", "יציאה בעוד ")) }
                // only the FIRST time carries a mark, and only when its state has one
                if (lead != null && depMark(lead) != DepMark.NONE) appendInlineContent(MARK, "·")
            }
            // Moovit's own formatter (com.moovit.util.time.d.d) colours EVERY time in
            // the list by its own presentation type, the mark belongs to the first
            // one, the colours belong to each.
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
                    append(stop)
                }
            }
            if (fare != null) withStyle(SpanStyle(color = K.dim)) { append(" • $fare") }
        },
        inlineContent = mapOf(
            MARK to InlineTextContent(
                Placeholder(13.sp, 13.sp, PlaceholderVerticalAlign.TextCenter),
            ) {
                lead?.let { DepMarkGlyph(it, 13.dp) }
            },
        ),
        fontSize = 13.sp, color = K.dim, lineHeight = 18.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

private const val MARK = "mark"

/**
 * Moovit's own card carries exactly two chips, its "Smart Tips" upsell and the
 * emissions pill, and never the itinerary's tags, even when the server sends them
 * ("Earliest arrival", "No transfers" both ride on the 472 itinerary here and neither
 * appears on the card). Kav drops the upsell and keeps the pill, so a card ends up
 * with the emissions figure and, when the plan is marked step-free, that.
 * The tags are still parsed; they belong on the trip detail, not on the card.
 */
private fun cardChips(it: Moovit.Itinerary): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(2)
    if (it.accessible) out.add("access" to T("Step-free", "נגיש לנכים"))
    if (it.co2g >= 0) out.add("co2" to co2(it.co2g))
    return out
}

@Composable
private fun InfoChip(kind: String, label: String) {
    val co2 = kind == "co2"
    Row(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (co2) K.co2Pill else K.sunken)
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

/** Moovit's emissions mark: a filled green globe, on the one blue pill in the app. */
@Composable
private fun GlobeGlyph(size: androidx.compose.ui.unit.Dp = 13.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val r = w * .5f
        drawCircle(K.muted, r, Offset(r, r))
        val sw = w * .085f
        // a meridian and two parallels, cut out of the disc in the pill's own colour
        drawLine(K.co2Pill, Offset(r, w * .06f), Offset(r, w * .94f), sw)
        drawLine(K.co2Pill, Offset(w * .10f, w * .36f), Offset(w * .90f, w * .36f), sw)
        drawLine(K.co2Pill, Offset(w * .10f, w * .64f), Offset(w * .90f, w * .64f), sw)
    }
}
