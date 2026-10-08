package uk.noammm.kav

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.ColorUtils
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.ui.Fix
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.Mode
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.aboard
import uk.noammm.kav.ui.alongPath
import uk.noammm.kav.ui.boardingChoice
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.chosenLegs
import uk.noammm.kav.ui.clockFormat
import uk.noammm.kav.ui.distanceLabel
import uk.noammm.kav.ui.distanceTo
import uk.noammm.kav.ui.distanceToPath
import uk.noammm.kav.ui.drawModeMark
import uk.noammm.kav.ui.drawWalker
import uk.noammm.kav.ui.isFresh
import uk.noammm.kav.ui.legMode
import uk.noammm.kav.ui.modeName
import uk.noammm.kav.ui.pathLength
import uk.noammm.kav.ui.routeTints
import uk.noammm.kav.ui.stopsProgress
import uk.noammm.kav.ui.whenLabel
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Glyph(val mode: Mode?, val here: Boolean = false)

internal data class TripNotice(
    val title: String,
    val text: String,
    val arrive: String,
    val chip: String,
    val parts: List<Pair<Int, Int>>,
    val progress: Int,
    val glyph: Glyph,
    val tint: Int,
    // The notification's button for a bus ride kept with Asshole mode: "PAY QUICK", then "Paying" and "Paid".
    val pay: String? = null,
) {
    val max get() = parts.sumOf { it.first }
}

private val TRAVELLED get() = K.routeIdle.toArgb()
private val WALK get() = K.muted.toArgb()
private const val PART_BUDGET = 12

private class Run(
    val step: Int,
    val shape: List<Pair<Double, Double>>,
    val metres: Double,
    val dep: Long,
    val arr: Long,
    val colour: Int,
    val walk: Boolean,
)

private class Say(val title: String, val text: String, val chip: String, val glyph: Glyph, val tint: Int)

private fun lengthOf(leg: Moovit.Leg): Double =
    pathLength(leg.shape).takeIf { it >= 1.0 }
        ?: leg.meters.toDouble().takeIf { it >= 1.0 }
        ?: max(1.0, (leg.arr - leg.dep) / 60.0 * 250.0)

private fun along(run: Run, live: Fix?, now: Long): Double {
    if (live != null && run.shape.size >= 2 && distanceToPath(live.lat, live.lon, run.shape) < 150) {
        return alongPath(live.lat, live.lon, run.shape).coerceIn(0.0, run.metres)
    }
    if (run.walk) return 0.0
    val span = run.arr - run.dep
    return if (span > 0) ((now - run.dep).toDouble() / span).coerceIn(0.0, 1.0) * run.metres else 0.0
}

internal fun tripNotice(journey: ActiveJourney, current: Int, fix: Fix?, now: Long, accent: Int): TripNotice? {
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    if (steps.isEmpty()) return null
    val index = current.coerceIn(0, steps.lastIndex)
    val r = journey.resolved
    val live = fix?.takeIf { it.isFresh(now) }
    fun pick(ride: Moovit.Leg, wait: Moovit.Leg?, legIndex: Int) =
        boardingChoice(ride, wait, journey.chosen[legIndex] ?: 0)
    val coloured = chosenLegs(journey.trip, journey.chosen)
        .filter { it.shape.size >= 2 && it.kind != Moovit.LegKind.WALK }
    val tints = routeTints(coloured, r)
    fun colourOf(leg: Moovit.Leg) = coloured.indexOfFirst { it === leg }.let { if (it < 0) accent else tints[it].toArgb() }

    val runs = steps.mapIndexedNotNull { i, s ->
        val (leg, colour) = when (s) {
            is Step.Walk -> s.leg to WALK
            is Step.Ride -> pick(s.ride, s.wait, s.legIndex).first.let { it to colourOf(it) }
            is Step.Taxi -> s.leg to colourOf(s.leg)
            is Step.Cycle -> s.leg to colourOf(s.leg)
            else -> return@mapIndexedNotNull null
        }
        Run(i, leg.shape, lengthOf(leg), leg.dep, leg.arr, colour, s is Step.Walk)
    }
    val metres = runs.map { max(1, it.metres.roundToInt()) }
    val walks = runs.count { it.walk }
    val spare = (PART_BUDGET - (runs.size - walks) - 1).coerceAtLeast(1)
    fun dashesIn(i: Int, dash: Int) = max(1, (metres[i] + dash / 2) / dash)
    var dash = max(1, metres.sum() / 20)
    while (runs.indices.filter { runs[it].walk }.sumOf { dashesIn(it, dash) } > max(spare, walks)) dash += max(1, dash / 8)
    val lens = runs.indices.map { if (runs[it].walk) dashesIn(it, dash) * dash else metres[it] }
    val total = lens.sum().coerceAtLeast(1)
    val runAt = runs.indexOfFirst { it.step == index }
    // On foot, where you were last seen; on board, the timetable keeps the vehicle moving.
    val into = runs.getOrNull(runAt)?.let { along(it, if (it.walk) fix else live, now) } ?: 0.0
    val done = runs.indices.filter { runs[it].step < index }.sumOf { lens[it] }
    val progress = when {
        steps[index] is Step.Arrive -> total
        runAt >= 0 -> (done + into / runs[runAt].metres * lens[runAt]).roundToInt()
        else -> done
    }.coerceIn(0, total)
    val left = runs.getOrNull(runAt)?.let { (it.metres - into).coerceAtLeast(0.0) } ?: 0.0

    val parts = ArrayList<Pair<Int, Int>>()
    fun add(length: Int, colour: Int) {
        if (length > 0) parts.add(length to colour)
    }
    var start = 0
    runs.forEachIndexed { i, run ->
        val end = start + lens[i]
        if (run.walk) {
            for (at in start until end step dash) add(dash, if (at + dash / 2 < progress) TRAVELLED else run.colour)
        } else when {
            end <= progress -> add(lens[i], TRAVELLED)
            start >= progress -> add(lens[i], run.colour)
            else -> { add(progress - start, TRAVELLED); add(end - progress, run.colour) }
        }
        start = end
    }
    if (parts.isEmpty()) add(1, accent)

    val hm = clockFormat()
    fun time(utc: Long) = hm.format(Date(utc * 1000))
    fun lineName(ride: Moovit.Leg): String {
        val number = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
        val mode = modeName(legMode(ride, r))
        return if (number.isBlank()) mode else "$mode $number"
    }
    val lastRide = steps.indexOfLast { it is Step.Ride }
    fun place(stop: Int) = r.stopName(stop)?.takeIf { it.isNotBlank() }
        ?: if (index > lastRide) journey.toLabel else T("your stop", "התחנה שלכם")
    val togo = distanceLabel(left)

    val say = when (val step = steps[index]) {
        is Step.Start -> Say(
            T("Leave at ${time(step.time)}", "יציאה ב-${time(step.time)}"),
            T("Start from ${step.label}", "התחלה מ${step.label}"),
            time(step.time), Glyph(null), accent,
        )
        is Step.Walk -> {
            val next = (steps.drop(index + 1).firstOrNull { it is Step.Wait } as? Step.Wait)
                ?.takeIf { step.toRide != null }?.let { pick(it.ride, it.wait, it.legIndex).first }
            Say(
                "$togo · ${place(step.toStop)}",
                next?.let { T("Walk, then ${lineName(it)}", "הליכה, ואז ${lineName(it)}") }
                    ?: T("Walk to your destination", "הליכה אל היעד"),
                togo, Glyph(null), accent,
            )
        }
        is Step.Wait -> {
            val (ride, wait) = pick(step.ride, step.wait, step.legIndex)
            val dep = r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }
                ?: Moovit.Departure(ride.tripId, ride.dep)
            val glyph = Glyph(legMode(ride, r))
            if (dep.status == 3) Say(
                T("${lineName(ride)} is cancelled", "${lineName(ride)} מבוטל"),
                T("Find another route before continuing.", "מצאו מסלול אחר לפני שתמשיכו."),
                T("Cancelled", "בוטל"), glyph, colourOf(ride),
            ) else {
                val mins = ((dep.timeUtc - now) / 60).coerceAtLeast(0)
                Say(
                    "${lineName(ride)} · ${whenLabel(dep.timeUtc, now)}",
                    listOfNotNull(
                        r.stopName(ride.fromStop)?.takeIf { it.isNotBlank() },
                        r.platform(ride, wait).takeIf { it.isNotBlank() }?.let { T("Platform $it", "רציף $it") },
                        if (dep.live && r.arrival(ride) != null) T("Live", "בזמן אמת") else T("Scheduled", "מתוזמן"),
                    ).joinToString(" · "),
                    if (mins == 0L) T("now", "עכשיו") else T("$mins min", "$mins דק׳"),
                    glyph, colourOf(ride),
                )
            }
        }
        is Step.Ride -> {
            val ride = pick(step.ride, step.wait, step.legIndex).first
            // The chip says how long until getting off: the share of the route still ahead of the ride's planned time.
            val length = runs.getOrNull(runAt)?.metres ?: 0.0
            val mins = if (length > 0) kotlin.math.ceil(left / length * (ride.arr - ride.dep) / 60.0).toLong()
            else (ride.arr - now) / 60
            Say(
                "$togo · ${place(ride.toStop)}",
                T("On ${lineName(ride)}", "ב${lineName(ride)}"),
                if (mins <= 0L) T("now", "עכשיו") else T("$mins min", "$mins דק׳"),
                Glyph(legMode(ride, r)), colourOf(ride),
            )
        }
        is Step.Taxi -> Say(
            "$togo · ${place(step.leg.toStop)}", T("Take a taxi", "קחו מונית"),
            togo, Glyph(Mode.TAXI), colourOf(step.leg),
        )
        is Step.Cycle -> Say(
            "$togo · ${place(step.leg.toStop)}", T("Cycle", "רכיבה"),
            togo, Glyph(Mode.OTHER), colourOf(step.leg),
        )
        is Step.Arrive -> {
            val away = step.focus.lastOrNull()?.let { end -> live?.distanceTo(end) }
            val here = away == null || away < 40
            Say(
                if (here) T("Arrived · ${step.label}", "הגעתם · ${step.label}") else "${distanceLabel(away!!)} · ${step.label}",
                T("Planned arrival ${time(step.time)}", "הגעה מתוכננת בשעה ${time(step.time)}"),
                if (here) T("Here", "כאן") else distanceLabel(away!!),
                Glyph(null, here = true), accent,
            )
        }
    }
    return TripNotice(
        say.title, say.text, T("Arrive ${time(journey.trip.arr)}", "הגעה ב-${time(journey.trip.arr)}"),
        say.chip, parts, progress, say.glyph, say.tint,
    )
}

private val icons = HashMap<Triple<Glyph, Int, Int>, Bitmap>()

internal fun trackerIcon(glyph: Glyph, tint: Int): Bitmap = icon(glyph, tint, 96, round = true)

internal fun plateIcon(glyph: Glyph, tint: Int): Bitmap = icon(glyph, tint, 144, round = false)

private fun icon(glyph: Glyph, tint: Int, px: Int, round: Boolean): Bitmap = icons.getOrPut(Triple(glyph, tint, px)) {
    val image = ImageBitmap(px, px)
    val ink = if (ColorUtils.calculateLuminance(tint) > 0.55) Color(0xFF141414) else Color.White
    CanvasDrawScope().draw(
        Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(px.toFloat(), px.toFloat()),
    ) {
        val centre = Offset(size.width / 2, size.height / 2)
        if (round) {
            drawCircle(Color.White, size.minDimension / 2)
            drawCircle(Color(tint), size.minDimension / 2 * 0.84f)
        } else {
            drawRoundRect(Color(tint), cornerRadius = CornerRadius(size.width * 0.26f))
        }
        val span = size.width * if (round) 0.5f else 0.56f
        val mode = glyph.mode
        when {
            glyph.here -> {
                drawCircle(ink, span * 0.42f, centre, style = Stroke(span * 0.13f))
                drawCircle(ink, span * 0.17f, centre)
            }
            mode == null -> inset((size.width - span) / 2) { drawWalker(ink) }
            else -> drawModeMark(mode, centre, span, ink)
        }
    }
    image.asAndroidBitmap()
}

internal class TripAlert(val key: String, val title: String, val text: String)

internal fun tripAlert(journey: ActiveJourney, current: Int, fix: Fix?, now: Long): TripAlert? {
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    if (steps.isEmpty()) return null
    val r = journey.resolved
    return when (val step = steps[current.coerceIn(0, steps.lastIndex)]) {
        is Step.Wait -> {
            val (ride, wait) = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0)
            val at = r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }?.timeUtc ?: ride.dep
            val line = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
            if (at - now !in 0..90 || line.isBlank()) null else TripAlert(
                "wait-${step.legIndex}", T("$line is arriving", "$line מגיע"),
                listOfNotNull(r.stopName(ride.fromStop), whenLabel(at, now)).joinToString(" · "),
            )
        }
        is Step.Ride -> {
            val ride = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0).first
            val total = ride.stops.size
            val progress = stopsProgress(ride, r.stops, r.arrival(ride), fix, now)
            // Counting stops needs every stop's position, and a saved trip often knows only a few of them: then the
            // last stop is near when less than an average gap between stops is left along the route.
            val located = ride.stops.all { r.stops[it]?.point != null }
            val left = fix?.takeIf { !located && total >= 2 && it.isFresh(now) && it.aboard(ride.shape) }?.let {
                val length = pathLength(ride.shape)
                (length - alongPath(it.lat, it.lon, ride.shape)) to length / (total - 1)
            }
            val nearEnd = when {
                left != null -> left.first <= left.second
                progress >= 0 -> total >= 2 && progress >= total - 1f
                else -> ride.arr - now in 0..120
            }
            if (!nearEnd) null else TripAlert(
                "ride-${step.legIndex}", T("Get off at the next stop", "רדו בתחנה הבאה"),
                r.stopName(ride.toStop) ?: journey.toLabel,
            )
        }
        else -> null
    }
}
