@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** A persistent map with content-sized step cards and floating navigation controls. */

private val hm = SimpleDateFormat("HH:mm", Locale.US)

/* the steps */

internal sealed class Step {
    abstract val focus: List<Pair<Double, Double>>

    class Start(val label: String, val time: Long, override val focus: List<Pair<Double, Double>>) : Step()
    class Walk(
        val leg: Moovit.Leg, val toStop: Int, val toRide: Moovit.Leg?,
        override val focus: List<Pair<Double, Double>>,
    ) : Step()
    class Wait(
        val ride: Moovit.Leg, val wait: Moovit.Leg?, val legIndex: Int,
        override val focus: List<Pair<Double, Double>>,
    ) : Step()
    class Ride(
        val ride: Moovit.Leg, val wait: Moovit.Leg?, val legIndex: Int,
        override val focus: List<Pair<Double, Double>>,
    ) : Step()
    class Taxi(val leg: Moovit.Leg, override val focus: List<Pair<Double, Double>>) : Step()
    class Cycle(val leg: Moovit.Leg, override val focus: List<Pair<Double, Double>>) : Step()
    class Arrive(val label: String, val time: Long, override val focus: List<Pair<Double, Double>>) : Step()
}

/** Legs in, pager pages out. A ride becomes two pages, the wait, then the ride. */
internal fun buildSteps(trip: Moovit.Itinerary, fromLabel: String, toLabel: String): List<Step> {
    val all = trip.legs
    val whole = all.flatMap { it.shape }
    val out = ArrayList<Step>()
    out.add(Step.Start(fromLabel, trip.dep, all.firstOrNull { it.shape.isNotEmpty() }?.shape ?: whole))

    all.forEachIndexed { i, l ->
        when (l.kind) {
            Moovit.LegKind.WALK -> {
                // a street walk and the walk inside the station are one walk to a rider
                val prev = all.getOrNull(i - 1)
                if (prev?.kind == Moovit.LegKind.WALK) return@forEachIndexed
                var mins = 0; var metres = 0; var j = i
                while (j < all.size && all[j].kind == Moovit.LegKind.WALK) {
                    mins += all[j].minutes; metres += all[j].meters; j++
                }
                if (mins < 1 && metres <= 30) return@forEachIndexed
                val merged = Moovit.Leg(
                    Moovit.LegKind.WALK, dep = l.dep, arr = all[j - 1].arr,
                    fromStop = l.fromStop, toStop = all[j - 1].toStop,
                    meters = metres, shape = all.subList(i, j).flatMap { it.shape },
                )
                // A walk ends at the stop you board at, so it is marked with the mode
                // of the vehicle waiting there, the wait leg in between is not one.
                val boards = all.drop(j).firstOrNull { it.kind != Moovit.LegKind.WAIT }
                    ?.takeIf { it.kind == Moovit.LegKind.RIDE }
                out.add(Step.Walk(merged, merged.toStop, boards, merged.shape.ifEmpty { whole }))
            }
            Moovit.LegKind.RIDE -> {
                val wait = all.getOrNull(i - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }
                out.add(Step.Wait(l, wait, i, l.shape.take(1).ifEmpty { whole }))
                out.add(Step.Ride(l, wait, i, l.shape.ifEmpty { whole }))
            }
            Moovit.LegKind.TAXI -> out.add(Step.Taxi(l, l.shape.ifEmpty {
                listOfNotNull(l.taxiPickup, l.taxiDropoff)
            }))
            Moovit.LegKind.BIKE -> out.add(Step.Cycle(l, l.shape.ifEmpty { whole }))
            else -> {}
        }
    }
    out.add(Step.Arrive(toLabel, trip.arr, all.lastOrNull { it.shape.isNotEmpty() }?.shape ?: whole))
    return out
}

/**
 * Which of a wait leg's alternative lines the rider is navigating with, the plan's
 * own first option until they pick another. A leg that offers only one line has
 * nothing to choose, so this always resolves to something.
 */
internal fun boardingChoice(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    pick: Int,
): Pair<Moovit.Leg, Moovit.Leg?> {
    val options = Moovit.boardingOptions(ride, wait)
    return options.getOrNull(pick) ?: options.first()
}

/** The itinerary's legs with each ride replaced by the line the rider chose. */
private fun chosenLegs(trip: Moovit.Itinerary, chosen: Map<Int, Int>): List<Moovit.Leg> =
    trip.legs.mapIndexed { i, l ->
        if (l.kind != Moovit.LegKind.RIDE) l else boardingChoice(
            l, trip.legs.getOrNull(i - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }, chosen[i] ?: 0,
        ).first
    }

/* the screen */

/** Primary action for the selected trip. */
@Composable
fun StartButton(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(K.live)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val w = size.width; val h = size.height
            drawPath(
                Path().apply {
                    moveTo(w * .18f, h * .12f); lineTo(w * .90f, h * .50f)
                    lineTo(w * .18f, h * .88f); close()
                },
                K.bg,
            )
        }
        Spacer(Modifier.width(9.dp))
        Text("Start", fontSize = 14.sp, color = K.bg, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun NavigateScreen(
    model: KavModel,
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onStop: () -> Unit,
    onExit: () -> Unit = onStop,
    onPlan: () -> Unit = onExit,
) {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) { kotlinx.coroutines.delay(1000); value = System.currentTimeMillis() / 1000 }
    }
    val steps = remember(trip, fromLabel, toLabel) { buildSteps(trip, fromLabel, toLabel) }
    // The line the rider is actually taking, per ride leg. A plan that offers 282, 37
    // and 471 for one boarding is three journeys, not one, and which of them you are
    // on decides the arrival times, the stop list and the route on the map. It lives
    // on the journey, so Home and the small window see the same choice.
    val journey = model.activeJourney?.takeIf { it.trip === trip }
    val chosen: Map<Int, Int> = journey?.chosen ?: emptyMap()
    fun choose(leg: Int, option: Int) {
        model.activeJourney?.takeIf { it.trip === trip }?.let { model.activeJourney = it.copy(chosen = it.chosen + (leg to option)) }
    }
    val here = model.here
    val fix = model.fix
    // The step the journey is on, the clock, the vehicle and the phone decide, and
    // the cards follow it. Swiping is reading ahead; the map goes with the card shown.
    val currentStep = model.journeyStep.coerceIn(0, steps.lastIndex)
    val pager = rememberPagerState(initialPage = currentStep) { steps.size }
    LaunchedEffect(currentStep) { pager.animateScrollToPage(currentStep) }
    val scope = rememberCoroutineScope()

    val backdrop = remember { HazeState() }
    val bottomInset = LocalBottomBarInset.current
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().background(K.bg)) {
            val contentHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
            val compact = contentHeight < 480.dp
            val panelWidth = (maxWidth * .46f).coerceIn(240.dp, 340.dp).coerceAtMost(maxWidth * .60f)
            val cardHeight = (contentHeight * .30f).coerceIn(160.dp, 240.dp)
            NavigateMap(trip, r, steps.getOrNull(pager.settledPage), chosen, here, fix, model.heading, now,
                Modifier.fillMaxSize().haze(backdrop),
                contentPadding = if (compact) PaddingValues(top = 96.dp, end = panelWidth, bottom = bottomInset + 12.dp)
                    else PaddingValues(top = 138.dp, bottom = cardHeight + 88.dp + bottomInset))

            Column(Modifier.align(Alignment.TopStart)
                .then(if (compact) Modifier.width(maxWidth - panelWidth) else Modifier.fillMaxWidth())) {
                Row(
                    Modifier.fillMaxWidth().padding(K.gap3).glassSurface()
                        .padding(K.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                            .semantics { contentDescription = "Back to the plan" }
                            .clickable(role = Role.Button, onClick = onExit),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("‹", fontSize = 28.sp, color = K.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Your trip", fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                        Text("${dur((trip.arr - now).toInt().coerceAtLeast(0))} · ${hm.format(Date(trip.arr * 1000))}",
                            fontSize = 12.sp, color = K.muted, maxLines = 1)
                    }
                    // the plan you read before starting, still reachable mid-trip
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                            .semantics { contentDescription = "Show trip plan" }
                            .clickable(role = Role.Button, onClick = onPlan),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlanGlyph()
                    }
                }
                if (!compact) StepStrip(pager.currentPage, steps.size, currentStep) { target ->
                    scope.launch { pager.animateScrollToPage(target) }
                }
            }

            Column(
                if (compact) Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight()
                    .padding(top = K.gap3, bottom = bottomInset)
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset),
            ) {
                if (compact) {
                    StepStrip(pager.currentPage, steps.size, currentStep) { target ->
                        scope.launch { pager.animateScrollToPage(target) }
                    }
                    Spacer(Modifier.height(K.gap2))
                }
                val ceiling = with(LocalDensity.current) { cardHeight.roundToPx() }
                val pageHeights = remember(steps) { mutableStateMapOf<Int, Int>() }
                HorizontalPager(
                    state = pager,
                    modifier = if (compact) Modifier.weight(1f)
                        else Modifier.pageSized(pager, pageHeights, ceiling, bottom = true),
                    contentPadding = PaddingValues(horizontal = K.gap3),
                    pageSpacing = K.gap2,
                    verticalAlignment = Alignment.Bottom,
                    beyondViewportPageCount = 1,
                ) { page ->
                    Box(Modifier.fillMaxWidth().onSizeChanged { pageHeights[page] = it.height }.animateContentSize()) {
                        StepCard(
                            steps[page], r, active = page == currentStep, now = now,
                            chosen = chosen, fix = fix,
                            onChoose = ::choose,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(K.gap3).glassSurface()
                        .padding(horizontal = K.gap3, vertical = K.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("End trip", fontSize = 14.sp, color = K.text,
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rControl))
                            .clickable(role = Role.Button, onClick = onStop)
                            .padding(horizontal = K.gap3, vertical = K.gap3))
                    Spacer(Modifier.weight(1f))
                    Text("${pager.currentPage + 1} / ${steps.size}", fontSize = 12.sp, color = K.dim)
                }
            }
        }
    }
}

/** ‹ · · • · · › , one dot per step, the live one in the accent, and the arrows Moovit puts either side. */
@Composable
private fun StepStrip(current: Int, count: Int, live: Int, goTo: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3).glassSurface(24.dp)
            .height(48.dp).padding(horizontal = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(back = true, enabled = current > 0) { goTo(current - 1) }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until count) {
                val on = i == current
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .semantics { contentDescription = "Step ${i + 1} of $count"; selected = on }
                        .clickable(role = Role.Button) { goTo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(if (on) 7.dp else 5.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (i == live) K.accent else if (on) K.text else K.surface4))
                }
            }
        }
        Arrow(back = false, enabled = current < count - 1) { goTo(current + 1) }
    }
}

@Composable
private fun Arrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) K.text else K.surface4
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(999.dp))
            .semantics { contentDescription = if (back) "Previous step" else "Next step" }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width; val h = size.height
            val x1 = if (back) .64f else .38f
            val x2 = if (back) .34f else .68f
            drawLine(tint, Offset(w * x1, h * .18f), Offset(w * x2, h * .5f), w * .13f, StrokeCap.Round)
            drawLine(tint, Offset(w * x2, h * .5f), Offset(w * x1, h * .82f), w * .13f, StrokeCap.Round)
        }
    }
}

/* the cards */

@Composable
internal fun StepCard(
    step: Step,
    r: Moovit.Resolved,
    active: Boolean,
    now: Long,
    chosen: Map<Int, Int> = emptyMap(),
    fix: Fix? = null,
    onChoose: (leg: Int, option: Int) -> Unit = { _, _ -> },
) {
    when (step) {
        is Step.Start -> Card("Start from", active) {
            Text(step.label, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Leave at ${hm.format(Date(step.time * 1000))}", fontSize = 12.sp, color = K.dim)
        }

        is Step.Arrive -> Card("Arrive", active) {
            Text(step.label, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hm.format(Date(step.time * 1000)), fontSize = 12.sp, color = K.dim)
        }

        is Step.Walk -> {
            val stop = r.stop(step.toStop)
            Card(
                "Walk ${step.leg.minutes} min to".takeIf { step.leg.minutes >= 1 } ?: "Walk to",
                active,
                trailing = if (step.leg.meters > 0) distanceLabel(step.leg.meters.toDouble()) else null,
            ) {
                StopLine(stop?.name ?: "your stop", stop?.code, step.toRide?.let { legMode(it, r) })
            }
        }

        is Step.Taxi -> Card("Take a taxi", active, trailing = "${step.leg.minutes} min") {
            Text("Continue with Gett", fontSize = 15.sp, color = K.text)
            Spacer(Modifier.height(K.gap2))
            GettButton(step.leg)
        }

        is Step.Cycle -> Card("Cycle ${step.leg.minutes} min", active) {
            Text("Follow the route to your next stop", fontSize = 15.sp, color = K.text)
        }

        is Step.Wait -> {
            val options = Moovit.boardingOptions(step.ride, step.wait)
            val pick = (chosen[step.legIndex] ?: 0).coerceIn(0, options.lastIndex)
            Card(if (options.size > 1) "Select a line" else "Wait for", active) {
                options.forEachIndexed { index, (ride, wait) ->
                    if (index > 0) Spacer(Modifier.height(K.gap2))
                    val picked = index == pick
                    Column(
                        // The clip belongs to the selectable plate, not to the option
                        // itself: with a single line there is no plate, nothing is
                        // padded off the edge, and a bare rControl arc would run
                        // straight through the first and last child - slicing the
                        // line badge's top corners and the alert row's bottom ones.
                        Modifier.fillMaxWidth()
                            .then(
                                if (options.size > 1) Modifier
                                    .clip(RoundedCornerShape(K.rControl))
                                    .background(if (picked) K.plate else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (picked) K.borderStrong else K.border,
                                        RoundedCornerShape(K.rControl),
                                    )
                                    .semantics { selected = picked }
                                    .clickable(role = Role.RadioButton) {
                                        onChoose(step.legIndex, index)
                                    }
                                    .padding(K.gap2)
                                else Modifier,
                            ),
                    ) {
                        LineRow(ride, r)
                        Spacer(Modifier.height(K.gap2))
                        DepartureTimes(r.departures(ride, wait), now)
                        wait?.let {
                            AlertRow(it.alertCategory, it.alertText, r.line(ride.lineId)?.groupId ?: 0)
                        }
                    }
                }
            }
        }

        is Step.Ride -> {
            // The line the rider chose on the wait page before this one.
            val (ride, _) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0)
            val stops = ride.stops
            val names = r.stops + rememberStopNames(stops)
            val alight = r.stop(ride.toStop) ?: names[ride.toStop]
            val arrival = r.arrival(ride)
            Card(
                "Ride ${stops.size - 1} stops to".takeIf { stops.size > 1 } ?: "Ride to",
                active,
                trailing = "${ride.minutes} min",
            ) {
                LineRow(ride, r)
                Spacer(Modifier.height(K.gap2))
                StopLine(alight?.name ?: "your stop", alight?.code, legMode(ride, r))
                if (stops.size > 1) {
                    Spacer(Modifier.height(K.gap2))
                    Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
                    Spacer(Modifier.height(K.gap2))
                    StopRail(stops, names, stopsPassed(ride, names, arrival, fix, now), ride.arr)
                }
            }
        }
    }
}

/**
 * A stop the rider has to find. The plan screen already marks one with a station
 * plate the way Moovit does; without it a name like "דרך הציונות/בזלת" was bare text
 * in a card whose every other row carried a mark, and read as part of the sentence
 * above it rather than as the place you are walking to.
 */
@Composable
private fun StopLine(name: String, code: String?, mode: Mode?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (mode != null) {
            StationMark(mode, 17.dp)
            Spacer(Modifier.width(K.gap2))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!code.isNullOrBlank()) Text("Stop " + code, fontSize = 12.sp, color = K.dim)
        }
    }
}

/** The plan as a rail of stops, the shape the detail screen draws at full size. */
@Composable
private fun PlanGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height; val sw = w * .09f
        val x = w * .18f
        drawLine(K.text, Offset(x, h * .18f), Offset(x, h * .82f), sw, StrokeCap.Round)
        listOf(.18f, .50f, .82f).forEach { y ->
            drawCircle(K.text, w * .13f, Offset(x, h * y))
            drawLine(K.text, Offset(w * .42f, h * y), Offset(w * .88f, h * y), sw, StrokeCap.Round)
        }
    }
}

/**
 * The stops of a ride, each a circle on the line's rail, and the ones already behind
 * the rider filled in, so the circle you are watching moves down the list as you go.
 * [passed] is how many are behind; below zero, nobody knows yet.
 */
@Composable
private fun StopRail(
    stops: List<Int>,
    names: Map<Int, Moovit.StopInfo>,
    passed: Int,
    arriveUtc: Long,
) {
    Column(Modifier.fillMaxWidth()) {
        stops.forEachIndexed { i, id ->
            val first = i == 0
            val last = i == stops.lastIndex
            val done = passed >= 0 && i < passed
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Canvas(Modifier.width(20.dp).fillMaxHeight()) {
                    val x = size.width * .5f
                    val cy = 11.dp.toPx().coerceAtMost(size.height * .5f)
                    val rail = if (done) K.routeIdle else K.route
                    if (!first) drawLine(rail, Offset(x, 0f), Offset(x, cy), 3.dp.toPx())
                    if (!last) drawLine(rail, Offset(x, cy), Offset(x, size.height), 3.dp.toPx())
                    // the circle: hollow for a stop still ahead, filled once passed,
                    // and larger at the two ends of the ride
                    val rad = if (first || last) 5.dp.toPx() else 3.5.dp.toPx()
                    drawCircle(K.bg, rad + 2.dp.toPx(), Offset(x, cy))
                    if (done) drawCircle(K.routeIdle, rad, Offset(x, cy))
                    else drawCircle(K.route, rad, Offset(x, cy), style = Stroke(2.dp.toPx()))
                }
                Spacer(Modifier.width(K.gap2))
                Text(
                    names[id]?.name ?: "#$id",
                    fontSize = 13.sp,
                    color = if (done) K.dim else K.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                )
                if (last) Text(
                    hm.format(Date(arriveUtc * 1000)),
                    fontSize = 12.sp, color = K.dim, modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun LineRow(ride: Moovit.Leg, r: Moovit.Resolved) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(IntrinsicSize.Min).clip(RoundedCornerShape(7.dp)).background(K.plate)
                .border(1.dp, K.borderStrong, RoundedCornerShape(7.dp)),
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, K.muted, 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "#${ride.lineId}",
                    fontSize = 16.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        Spacer(Modifier.width(K.gap3))
        Text(
            info?.destination?.ifBlank { null }?.let { "to $it" } ?: "",
            fontSize = 13.sp, color = K.muted, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** The body wraps short instructions and scrolls only when it exceeds the available space. */
@Composable
private fun Card(
    header: String,
    active: Boolean,
    trailing: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glassSurface(K.rCard),
    ) {
        Row(
            Modifier.fillMaxWidth().background(if (active) K.live.copy(alpha = .18f) else Color.Transparent)
                .padding(horizontal = K.gap3, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                header, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (active) K.live else K.text, modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(
                trailing, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (active) K.live else K.muted,
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(K.gap3),
            content = body,
        )
    }
}

/* the map */

/**
 * The camera for the card on screen. A walk and a ride are seen from where you are,
 * facing the way you are going, the compass for a walk, the road for a ride. The
 * other cards frame their ground and stay north-up.
 *
 * While riding, the vehicle leads when it is reporting where it is: it is on the road
 * the map is drawing, its fix is the operator's rather than a phone in a pocket on a
 * bus, and it is the thing the rider is looking for. The phone stands in only for a
 * vehicle that is not tracked. On a walk the phone is the only thing there is, and a
 * fix a few minutes old still says where the walk is better than the whole step does,
 * a trip resumed after a pause used to lose its camera to that.
 */
private const val CAMERA_FIX_S = 15 * 60L

private fun followFor(
    step: Step?, chosenRide: Moovit.Leg?, r: Moovit.Resolved, fix: Fix?, heading: Float?, now: Long,
): Follow? {
    val recent = fix?.takeIf { now - it.at <= CAMERA_FIX_S }
    return when (step) {
        is Step.Walk -> {
            val at = recent ?: return null
            val along = bearingAlong(at.lat, at.lon, step.leg.shape)
            Follow(at.lat, at.lon, heading ?: along ?: 0f, zoom = 19.1f)
        }
        is Step.Ride -> {
            val ride = chosenRide ?: return null
            val vehicle = r.arrival(ride)?.takeIf { it.hasLocation && it.vehicleStatus != 3 }
            val (lat, lon) = when {
                vehicle != null -> vehicle.lat to vehicle.lon
                recent != null -> recent.lat to recent.lon
                else -> return null
            }
            Follow(lat, lon, bearingAlong(lat, lon, ride.shape) ?: heading ?: 0f, zoom = 18.3f)
        }
        else -> null
    }
}

@Composable
private fun NavigateMap(
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    step: Step?,
    chosen: Map<Int, Int>,
    here: Pair<Double, Double>?,
    fix: Fix?,
    heading: Float?,
    now: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    // Draw the journey the rider chose, not the plan's first option.
    val picked = chosenLegs(trip, chosen)
    val legs = picked.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val pulse = rememberLivePulse()
    val rideLegs = picked.filter { it.kind == Moovit.LegKind.RIDE }
    val fetched = rememberLineRoutes(rideLegs.map { r.arrival(it)?.tripShapeId ?: -1 })
    val lineRoutes = rideLegs
        .map { l ->
            val a = r.arrival(l)
            lineRoute(a, r).ifEmpty { fetched[a?.tripShapeId ?: -1].orEmpty() }
        }
        .filter { it.size >= 2 }
    val vehicles = rideLegs.mapNotNull { r.arrival(it)?.takeIf { a -> a.hasLocation } }
    // every stop on every ride, so the map shows the same circles the card does
    val stopNames = rememberStopNames(remember(rideLegs) { rideLegs.flatMap { it.stops } })
    val stopPoints = remember(rideLegs, r.stops, stopNames) { rideStopPoints(rideLegs, r.stops + stopNames) }

    val chosenRide = when (step) {
        is Step.Wait -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        is Step.Ride -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        else -> null
    }
    val focusedVehicle = chosenRide?.let { r.arrival(it) }?.takeIf { it.hasLocation }
    val chosenFocus = when (step) {
        is Step.Wait -> chosenRide?.shape?.take(1)
        is Step.Ride -> chosenRide?.shape
        else -> null
    }.orEmpty()
    val focus = chosenFocus.ifEmpty { step?.focus.orEmpty() }
        .ifEmpty { legs.flatMap { it.shape } }
    val framedPoints = focus + listOfNotNull(focusedVehicle?.let { it.lat to it.lon })
    val mePulse = animateFloatAsState(if (here == null) 0f else 1f, tween(350), label = "meReveal")
    val vehicleAlpha = animateFloatAsState(if (vehicles.isEmpty()) 0f else 1f, tween(350), label = "vehicleReveal")
    val follow = followFor(step, chosenRide, r, fix, heading, now)
    val fresh = fix?.takeIf { it.isFresh(now) }

    // The part of the current ride already behind you goes grey, from wherever the
    // vehicle is, or you are, when it is not tracked, along its route.
    val ridingLeg = (step as? Step.Ride)?.let { chosenRide }
    val behind = remember(ridingLeg, fresh?.lat, fresh?.lon, focusedVehicle?.lat, focusedVehicle?.lon, step) {
        val shape = ridingLeg?.shape ?: return@remember emptyList<Pair<Double, Double>>()
        val at = when {
            focusedVehicle != null && distanceToPath(focusedVehicle.lat, focusedVehicle.lon, shape) < 80 &&
                focusedVehicle.nextStopIndex > focusedVehicle.stopIndex -> focusedVehicle.lat to focusedVehicle.lon
            fresh != null && distanceToPath(fresh.lat, fresh.lon, shape) < 80 -> fresh.lat to fresh.lon
            else -> return@remember emptyList<Pair<Double, Double>>()
        }
        splitPath(shape, at.first, at.second).first
    }

    // The route, the stops and the ends go to MapLibre itself: drawn in the same GL
    // frame as the ground, they cannot slide against it while the camera is easing.
    val walkLegs = legs.filter { it.kind == Moovit.LegKind.WALK }
    val rideLegsShapes = legs.filter { it.kind != Moovit.LegKind.WALK }
    val geometry = remember(lineRoutes, legs, behind, stopPoints) {
        MapGeometry(
            lines = lineRoutes.map { MapLine(it, K.routeIdle, 3f, casing = 6f) } +
                walkLegs.map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                rideLegsShapes.map { MapLine(it.shape, K.route, 4f, casing = 8f) } +
                listOf(MapLine(behind, K.routeIdle, 4f, casing = 8f)),
            dots = stopPoints.flatMap { (lat, lon) ->
                listOf(MapDot(lat, lon, K.bg, 6f), MapDot(lat, lon, Color.Transparent, 3.5f, K.route, 2f))
            } + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 8f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.text, 5f) },
            ),
        )
    }

    // You and the buses are ground-locked, so they render as GL layers with the map,
    // on the Compose canvas they lag the ground by a frame and slide during gestures.
    // Only the pulse ring stays on the canvas: its radius animates every frame, and a
    // diffuse ring can tolerate the one-frame slide the crisp markers cannot.
    val walkArrow = step is Step.Walk && heading != null
    val live = MapGeometry(
        dots = buildList {
            here?.let { (lat, lon) ->
                add(MapDot(lat, lon, K.text.copy(alpha = 0.16f * mePulse.value), 15f))
                if (!walkArrow) {
                    add(MapDot(lat, lon, K.bg.copy(alpha = mePulse.value), 8f))
                    add(MapDot(lat, lon, K.text.copy(alpha = mePulse.value), 5f))
                }
            }
            for (v in vehicles) {
                val tint = if (v.vehicleStatus == 2) K.problem else K.live
                add(MapDot(v.lat, v.lon, K.bg.copy(alpha = vehicleAlpha.value), 9f))
                add(MapDot(v.lat, v.lon, tint.copy(alpha = vehicleAlpha.value), 6f))
            }
        },
        markers = if (walkArrow) here?.let { (lat, lon) ->
            // the compass arrow: which way you are facing, on the ground
            listOf(MapMarker(lat, lon, MAP_ARROW_ICON, heading ?: 0f, mePulse.value))
        }.orEmpty() else emptyList(),
    )

    TileMap(framedPoints, modifier, focusKey = step to chosenRide?.tripId,
        recenterOn = here, contentPadding = contentPadding, follow = follow,
        geometry = geometry, live = live,
        animatedOverlay = { proj ->
            for (v in vehicles) {
                val p = proj.point(v.lat, v.lon)
                val tint = if (v.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f * vehicleAlpha.value), 17.dp.toPx() * pulse.value, p)
            }
        },
    )
}

/** Stops use their actual coordinates; route endpoints remain usable while names load. */
internal fun rideStopPoints(legs: List<Moovit.Leg>, stops: Map<Int, Moovit.StopInfo>): List<Pair<Double, Double>> =
    legs.flatMap { leg ->
        leg.stops.mapNotNull { stops[it]?.point } + listOfNotNull(
            leg.shape.firstOrNull().takeIf { stops[leg.fromStop]?.point == null && stops[leg.stops.firstOrNull()]?.point == null },
            leg.shape.lastOrNull().takeIf { stops[leg.toStop]?.point == null && stops[leg.stops.lastOrNull()]?.point == null },
        )
    }.distinct()
