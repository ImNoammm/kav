@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.shownName
import java.util.Date
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.OutlinedFlag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

private val hm get() = clockFormat()

internal sealed class Step {
    abstract val focus: List<Pair<Double, Double>>

    class Start(val label: String, val time: Long, override val focus: List<Pair<Double, Double>>) : Step()
    class Walk(
        val leg: Transit.Leg, val toStop: Int, val toRide: Transit.Leg?,
        override val focus: List<Pair<Double, Double>>,
        // The wait before that ride and the ride's leg, for the option the rider chose there.
        val toWait: Transit.Leg? = null, val toLeg: Int = -1,
        // The place the trip ends at, for the walk that ends it.
        val toName: String? = null,
    ) : Step()
    class Wait(
        val ride: Transit.Leg, val wait: Transit.Leg?, val legIndex: Int,
        override val focus: List<Pair<Double, Double>>,
        // The ride before it, when the rider changes to this one straight off it.
        val after: Transit.Leg? = null,
    ) : Step()
    class Ride(
        val ride: Transit.Leg, val wait: Transit.Leg?, val legIndex: Int,
        override val focus: List<Pair<Double, Double>>,
        // The ride the rider changes to after this one, waiting for it as its own step would.
        val change: Wait? = null,
    ) : Step()
    class Taxi(val leg: Transit.Leg, override val focus: List<Pair<Double, Double>>) : Step()
    class Cycle(val leg: Transit.Leg, override val focus: List<Pair<Double, Double>>) : Step()
    class Arrive(
        val label: String, val time: Long, override val focus: List<Pair<Double, Double>>,
        // The walk the trip ends with, when it does: the way the place is seen in Street View.
        val walk: List<Pair<Double, Double>> = emptyList(),
    ) : Step()
}

internal fun buildSteps(trip: Transit.Itinerary, fromLabel: String, toLabel: String): List<Step> {
    val all = trip.legs
    val whole = all.flatMap { it.shape }
    val out = ArrayList<Step>()
    out.add(Step.Start(fromLabel, trip.dep, all.firstOrNull { it.shape.isNotEmpty() }?.shape ?: whole))

    all.forEachIndexed { i, l ->
        when (l.kind) {
            Transit.LegKind.WALK -> {
                val prev = all.getOrNull(i - 1)
                if (prev?.kind == Transit.LegKind.WALK) return@forEachIndexed
                var mins = 0; var metres = 0; var j = i
                while (j < all.size && all[j].kind == Transit.LegKind.WALK) {
                    mins += all[j].minutes; metres += all[j].meters; j++
                }
                if (mins < 1 && metres <= 30) return@forEachIndexed
                val merged = Transit.Leg(
                    Transit.LegKind.WALK, dep = l.dep, arr = all[j - 1].arr,
                    fromStop = l.fromStop, toStop = all[j - 1].toStop,
                    meters = metres, shape = all.subList(i, j).flatMap { it.shape },
                )
                val k = (j until all.size).firstOrNull { all[it].kind != Transit.LegKind.WAIT }
                    ?.takeIf { all[it].kind == Transit.LegKind.RIDE }
                // Kav's planner gives a walk no stops of its own: it ends where the next ride boards.
                val to = merged.toStop.takeIf { it >= 0 } ?: k?.let { all[it].fromStop } ?: -1
                out.add(Step.Walk(merged, to, k?.let { all[it] }, merged.shape.ifEmpty { whole },
                    k?.let { all.getOrNull(it - 1)?.takeIf { w -> w.kind == Transit.LegKind.WAIT } }, k ?: -1,
                    toLabel.takeIf { j == all.size }))
            }
            Transit.LegKind.RIDE -> {
                val wait = all.getOrNull(i - 1)?.takeIf { it.kind == Transit.LegKind.WAIT }
                val j = (i + 1 until all.size).firstOrNull { all[it].kind != Transit.LegKind.WAIT && all[it].kind != Transit.LegKind.WALK }
                val change = j?.takeIf { all[it].kind == Transit.LegKind.RIDE }?.let {
                    Step.Wait(all[it], all.getOrNull(it - 1)?.takeIf { w -> w.kind == Transit.LegKind.WAIT }, it, all[it].shape.take(1))
                }
                // Straight off another ride: only waiting, or a step or two inside the station, between them.
                val before = (i - 1 downTo 0).firstOrNull { k ->
                    val b = all[k]
                    b.kind != Transit.LegKind.WAIT && !(b.kind == Transit.LegKind.WALK && (b.pathway || (b.minutes < 1 && b.meters <= 30)))
                }?.let(all::get)?.takeIf { it.kind == Transit.LegKind.RIDE }
                out.add(Step.Wait(l, wait, i, l.shape.take(1).ifEmpty { whole }, before))
                out.add(Step.Ride(l, wait, i, l.shape.ifEmpty { whole }, change))
            }
            Transit.LegKind.TAXI -> out.add(Step.Taxi(l, l.shape.ifEmpty {
                listOfNotNull(l.taxiPickup, l.taxiDropoff)
            }))
            Transit.LegKind.BIKE -> out.add(Step.Cycle(l, l.shape.ifEmpty { whole }))
            else -> {}
        }
    }
    out.add(Step.Arrive(toLabel, trip.arr, all.lastOrNull { it.shape.isNotEmpty() }?.shape ?: whole,
        all.takeLastWhile { it.kind == Transit.LegKind.WALK }.flatMap { it.shape }))
    return out
}

// Where a walk step goes: its stop, or the place the trip ends at.
internal fun walkTo(step: Step.Walk, r: Transit.Resolved): String =
    r.stop(step.toStop)?.shown() ?: step.toName ?: T("your stop", "התחנה שלכם")

// A boarding option's outline: the chosen line stands out (white on the dark looks, the strong grey on the light one)
// and the rest stay muted. K.border is white on the dark looks, so it can't be the muted one there.
internal fun choiceOutline(picked: Boolean): Color =
    if (picked) K.accentInk else K.borderStrong

// The option a stored choice means: that one while the leg still offers it, else the first (a refreshed plan can offer
// fewer lines). The card's outline and the planned ride both go by it, so they never disagree.
internal fun boardingIndex(pick: Int, count: Int): Int = if (pick in 0 until count) pick else 0

internal fun boardingChoice(
    ride: Transit.Leg,
    wait: Transit.Leg?,
    pick: Int,
): Pair<Transit.Leg, Transit.Leg?> {
    val options = Transit.boardingOptions(ride, wait)
    return options[boardingIndex(pick, options.size)]
}

internal fun chosenLegs(trip: Transit.Itinerary, chosen: Map<Int, Int>): List<Transit.Leg> =
    trip.legs.mapIndexed { i, l ->
        if (l.kind != Transit.LegKind.RIDE) l else boardingChoice(
            l, trip.legs.getOrNull(i - 1)?.takeIf { it.kind == Transit.LegKind.WAIT }, chosen[i] ?: 0,
        ).first
    }

// When the trip gets there as things stand. On board, the time left on the ride and the plan after it, the same count
// the ride's card shows; otherwise the plan, moved by how far its last ride runs from it.
internal fun tripArrival(
    trip: Transit.Itinerary, r: Transit.Resolved, chosen: Map<Int, Int>,
    riding: Transit.Leg? = null, fix: Fix? = null, now: Long = 0,
): Long {
    if (riding != null) return now + minutesToAlight(riding, r, fix, now) * 60 + (trip.arr - riding.arr)
    val last = chosenLegs(trip, chosen).lastOrNull { it.kind == Transit.LegKind.RIDE } ?: return trip.arr
    return trip.arr + (r.alightAt(last) - last.arr)
}

@Composable
fun StartButton(onClick: () -> Unit) {
    Row(
        Modifier.clip(K.capsule).background(K.live)
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
                K.onAccent,
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(T("Start", "התחלה"), fontSize = 14.sp, color = K.onAccent, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun NavigateScreen(
    model: KavModel,
    trip: Transit.Itinerary,
    r: Transit.Resolved,
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
    val journey = model.activeJourney?.takeIf { it.trip === trip }
    val chosen: Map<Int, Int> = journey?.chosen ?: emptyMap()
    fun choose(leg: Int, option: Int) {
        model.activeJourney?.takeIf { it.trip === trip }?.let { model.activeJourney = it.copy(chosen = it.chosen + (leg to option)) }
    }
    val here = model.here
    val fix = model.fix
    // Where the map shows and follows you: the last sharp fix, or a rough one that came well after it.
    val seen = model.roughFix?.takeIf { rough -> fix == null || rough.at > fix.at + 10 } ?: fix
    val currentStep = model.journeyStep.coerceIn(0, steps.lastIndex)
    // The ride being waited for now, whichever page is looked at: on the map its bus is the soonest of its line to come.
    val waitingFor = (steps.getOrNull(currentStep) as? Step.Wait)?.let { boardingChoice(it.ride, it.wait, chosen[it.legIndex] ?: 0) }
    // The vehicle tapped on the map: its card takes the step cards' place until closed.
    var tappedBus by remember(trip) { mutableStateOf<Long?>(null) }
    val bus = chosenLegs(trip, chosen).firstOrNull { it.kind == Transit.LegKind.RIDE && shownArrival(it, waitingFor, r, now)?.tripId == tappedBus }
        ?.let { leg -> shownArrival(leg, waitingFor, r, now)?.takeIf { it.hasLocation }?.let { leg to it } }
    val pager = rememberPagerState(initialPage = currentStep) { steps.size }
    LaunchedEffect(currentStep) { pager.animateScrollToPage(currentStep) }
    val following = pager.settledPage == currentStep ||
        pager.targetPage == currentStep ||
        (steps.getOrNull(currentStep) is Step.Start && pager.settledPage == currentStep + 1)
    val scope = rememberCoroutineScope()

    val liquid = rememberLiquidBackdrop()
    val bottomInset = LocalBottomBarInset.current
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        BoxWithConstraints(Modifier.fillMaxSize().background(K.bg)) {
            val contentHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
            val compact = contentHeight < 480.dp
            val panelWidth = (maxWidth * .46f).coerceIn(240.dp, 340.dp).coerceAtMost(maxWidth * .60f)
            val cardHeight = (contentHeight * .30f).coerceIn(160.dp, 240.dp)
            NavigateMap(trip, r, steps.getOrNull(pager.settledPage), chosen, waitingFor, here, seen, model.heading, now,
                following = following, tapped = tappedBus, onTap = { tappedBus = it },
                Modifier.fillMaxSize().glassBackdrop(liquid),
                contentPadding = if (compact) PaddingValues(top = 96.dp, end = panelWidth, bottom = bottomInset + 12.dp)
                    else PaddingValues(top = 138.dp, bottom = cardHeight + 88.dp + bottomInset),
                attribution = compact)

            Column(Modifier.align(Alignment.TopStart)
                .then(if (compact) Modifier.width(maxWidth - panelWidth) else Modifier.fillMaxWidth())) {
                Row(
                    Modifier.fillMaxWidth().padding(K.gap3).glassSurface()
                        .padding(K.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KavIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, T("Back to home", "חזרה לבית"), onExit,
                        tint = K.text)
                    Column(Modifier.weight(1f)) {
                        val riding = (steps.getOrNull(currentStep) as? Step.Ride)
                            ?.let { boardingChoice(it.ride, it.wait, chosen[it.legIndex] ?: 0).first }
                        val arrive = tripArrival(trip, r, chosen, riding, fix, now)
                        Text(T("Trip", "נסיעה"), style = K.type.headline)
                        LiveText("${dur((arrive - now).toInt().coerceAtLeast(0))} · ${hm.format(Date(arrive * 1000))}",
                            style = K.type.subhead.tabular, maxLines = 1)
                    }
                    KavIconButton(Icons.AutoMirrored.Rounded.FormatListBulleted, T("Show trip plan", "הצגת המסלול"), onPlan,
                        tint = K.text)
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
                } else {
                    // The map's credit sits in the right corner just above the card, whatever the card's height or the
                    // language's direction.
                    MapAttribution(Modifier.align(AbsoluteAlignment.Right).absolutePadding(right = K.gap3 + 6.dp, bottom = 3.dp))
                }
                androidx.compose.animation.AnimatedContent(
                    targetState = bus,
                    contentKey = { it != null },
                    transitionSpec = {
                        (androidx.compose.animation.slideInVertically(tween(260)) { it } + androidx.compose.animation.fadeIn(tween(200))) togetherWith
                            (androidx.compose.animation.slideOutVertically(tween(220)) { it } + androidx.compose.animation.fadeOut(tween(160)))
                    },
                    modifier = if (compact) Modifier.weight(1f) else Modifier,
                    contentAlignment = Alignment.BottomCenter,
                    label = "busCard",
                ) { showing ->
                if (showing != null) {
                    VehicleCard(showing.second, showing.first, r, now, Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap3),
                        onReport = { model.report = it }) { tappedBus = null }
                } else Column(if (compact) Modifier.fillMaxHeight() else Modifier) {
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
                    Column(Modifier.fillMaxWidth().onSizeChanged { pageHeights[page] = it.height }.animateContentSize()) {
                        CurrentStepButton(page, currentStep) { scope.launch { pager.animateScrollToPage(currentStep) } }
                        StepCard(
                            steps[page], r, active = page == currentStep, now = now,
                            chosen = chosen, fix = fix,
                            onChoose = ::choose,
                            pay = if (Shown.payments) { leg, ride -> TripPay(model, leg, ride, r) } else null,
                            report = { model.report = it },
                        )
                    }
                }
                Row(
                    Modifier.padding(K.gap3).fillMaxWidth().heightIn(min = 52.dp).glassSurface(K.rCard)
                        .clickable(role = Role.Button, onClick = onStop),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = K.critical, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(K.gap2))
                    Text(T("End trip", "סיום נסיעה"), style = K.type.bodyStrong, color = K.critical)
                }
                }
                }
            }
        }
    }
}

@Composable
internal fun CurrentStepButton(page: Int, current: Int, onClick: () -> Unit) {
    if (page == current) return
    val back = page > current
    Row(Modifier.fillMaxWidth().padding(bottom = K.gap2),
        horizontalArrangement = if (back) Arrangement.Start else Arrangement.End) {
        Row(
            Modifier.heightIn(min = 44.dp).glassSurface(22.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = K.gap3, vertical = K.gap2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            if (back) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, tint = K.text, modifier = Modifier.size(20.dp))
            Text(T("Current step", "השלב הנוכחי"), style = K.type.subhead, color = K.text, fontWeight = FontWeight.Medium)
            if (!back) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StepStrip(current: Int, count: Int, live: Int, goTo: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3).height(48.dp).glassSurface(24.dp)
            .padding(horizontal = K.gap1),
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
                        .semantics { contentDescription = T("Step ${i + 1} of $count", "שלב ${i + 1} מתוך $count"); selected = on }
                        .clickable(role = Role.Button) { goTo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    StepDot(looked = on, now = i == live)
                }
            }
        }
        Arrow(back = false, enabled = current < count - 1) { goTo(current + 1) }
    }
}

// A step's dot: wider for the step looked at, the accent for the step the rider is on.
@Composable
internal fun StepDot(looked: Boolean, now: Boolean) {
    val width by animateDpAsState(if (looked) 16.dp else 6.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "dot")
    Box(Modifier.padding(horizontal = 3.dp).size(width, 6.dp).clip(K.capsule)
        .background(if (now) K.accent else if (looked) K.text else K.fillStrong))
}

@Composable
private fun Arrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .semantics { contentDescription = if (back) T("Previous step", "שלב קודם") else T("Next step", "שלב הבא") }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (back) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null, tint = if (enabled) K.text else K.textTertiary, modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun StepCard(
    step: Step,
    r: Transit.Resolved,
    active: Boolean,
    now: Long,
    chosen: Map<Int, Int> = emptyMap(),
    fix: Fix? = null,
    onChoose: (leg: Int, option: Int) -> Unit = { _, _ -> },
    // The ride's payment, or its ticket once bought, on its riding card; a train's on the card waiting for it too.
    pay: (@Composable (legIndex: Int, ride: Transit.Leg) -> Unit)? = null,
    // Reporting the bus ridden to the ministry.
    report: ((ReportFor) -> Unit)? = null,
) {
    when (step) {
        is Step.Start -> Card(T("Start from", "התחלה מ-"), active) {
            Text(step.label, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(T("Leave at ${hm.format(Date(step.time * 1000))}", "יציאה בשעה ${hm.format(Date(step.time * 1000))}"), fontSize = 12.sp, color = K.dim)
        }

        is Step.Arrive -> Card(T("Arrive", "הגעה"), active) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Column(Modifier.weight(1f)) {
                    Text(step.label, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LiveText(hm.format(Date(step.time * 1000)), style = K.type.subhead.tabular)
                }
                lookAhead(step.walk)?.let { StreetViewButton(it, step.label) }
            }
        }

        is Step.Walk -> {
            val stop = r.stop(step.toStop)
            Card(
                T("Walk ${step.leg.minutes} min to", "הליכה ${step.leg.minutes} דק׳ אל").takeIf { step.leg.minutes >= 1 } ?: T("Walk to", "הליכה אל"),
                active,
                trailing = if (step.leg.meters > 0) distanceLabel(step.leg.meters.toDouble()) else null,
            ) {
                StopLine(
                    walkTo(step, r), stop?.code,
                    step.toRide?.let { legMode(it, r) },
                    // The chosen line's platform, from the plan's departures too, as the wait and trip details show it.
                    platform = step.toRide?.let { boardingChoice(it, step.toWait, chosen[step.toLeg] ?: 0) }
                        ?.let { (ride, wait) -> r.platform(ride, wait) }.orEmpty(),
                    stopId = step.toStop,
                    streetView = step.toRide?.let { ride -> stop?.point?.let { lookAt(it, ride, r) } },
                )
            }
        }

        is Step.Taxi -> Card(T("Take a taxi", "נסיעה במונית"), active, trailing = durMinutes(step.leg.minutes)) {
            Text(T("Continue with Gett", "המשיכו עם Gett"), style = K.type.body)
            Spacer(Modifier.height(K.gap2))
            GettButton(step.leg)
        }

        is Step.Cycle -> Card(cycleLabel(step.leg), active) {
            Text(T("Follow the route to your next stop", "המשיכו במסלול אל התחנה הבאה"), style = K.type.body)
        }

        is Step.Wait -> {
            val options = Transit.boardingOptions(step.ride, step.wait)
            val pick = boardingIndex(chosen[step.legIndex] ?: 0, options.size)
            // Once a line is picked, here or on the trip's page, the card is about waiting for it, the others a tap away.
            var choosing by remember(step.legIndex) { mutableStateOf(false) }
            val choice = options.size > 1 && (chosen[step.legIndex] == null || choosing)
            Card(if (choice) T("Select a line", "בחרו קו") else T("Wait for", "המתנה ל-"), active) {
                // Changing trains here: where the next one leaves from, on the card for waiting for it.
                step.after?.let { trainChange(it, options[pick].first, options[pick].second, r) }?.let {
                    ChangeBlock(it)
                    Spacer(Modifier.height(K.gap3))
                }
                Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                options.forEachIndexed { index, (ride, wait) ->
                    if (!choice && index != pick) return@forEachIndexed
                    if (choice && index > 0) Spacer(Modifier.height(K.gap2))
                    val picked = index == pick
                    Column(
                        Modifier.fillMaxWidth()
                            .then(
                                if (choice) Modifier
                                    .clip(RoundedCornerShape(K.rTile))
                                    .background(if (picked) K.plate else Color.Transparent)
                                    .border(1.dp, choiceOutline(picked), RoundedCornerShape(K.rTile))
                                    .semantics { selected = picked }
                                    .clickable(role = Role.RadioButton) {
                                        onChoose(step.legIndex, index)
                                        choosing = false
                                    }
                                    .padding(K.gap2)
                                else Modifier,
                            ),
                    ) {
                        LineRow(ride, r)
                        Spacer(Modifier.height(K.gap2))
                        val platform = r.platform(ride, wait)
                        if (platform.isNotBlank()) {
                            PlatformTag(platform)
                            Spacer(Modifier.height(K.gap2))
                        }
                        DepartureTimes(r.departures(ride, wait), now)
                        // A train late by the railway's forecast says by how much, as its own list does.
                        if (r.isTrain(ride)) r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }?.let(::lateBy)?.let {
                            Text(lateLabel(it), fontSize = 14.sp, color = K.critical, modifier = Modifier.padding(top = K.gap1))
                        }
                        wait?.let {
                            AlertRow(it.alertCategory, it.alertText)
                        }
                    }
                }
                if (!choice && options.size > 1) Text(
                    T("Change line", "החלפת קו"), fontSize = 14.sp, color = K.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { choosing = true }.padding(vertical = K.gap1),
                )
                }
                // A train is paid at the station's gates, so its payment is here already, on reaching the station.
                if (paidAtEntry(step.ride, r)) pay?.invoke(step.legIndex, options[pick].first)
            }
        }

        is Step.Ride -> {
            val (ride, _) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0)
            val stops = ride.stops
            val names = r.stops
            val alight = r.stop(ride.toStop) ?: names[ride.toStop]
            // The bus the rider is on, where it is along the ride and when it reaches each stop.
            val trip = riderTrip(ride, r, fix, now)
            val progress = stopsProgress(ride, names, busOn(ride, r, trip), fix, now)
            val times = rideTimes(ride, r, trip, names, fix, now)
            // On board, the time left until the rider's stop; before, how long the ride takes.
            val mins = if (active) (((times.lastOrNull()?.utc ?: r.alightAt(ride)) - now + 59) / 60).coerceAtLeast(0)
                else ride.minutes.toLong()
            // On board, the stops left to the rider's own, counted down as the bus passes them.
            val left = stops.size - progress.toInt()
            val header = when {
                active && progress >= 1f && left <= 1 -> T("Get off at the next stop", "רדו בתחנה הבאה")
                active && progress >= 1f -> T("Get off in $left stops", "ירידה בעוד $left תחנות")
                stops.size > 1 -> T("Ride ", "נסיעה ") + T.count(stops.size - 1, "1 stop", "תחנה אחת", "stops", "תחנות") + T(" to", " אל")
                else -> T("Ride to", "נסיעה אל")
            }
            val scroll = rememberScrollState()
            var railTop by remember { mutableIntStateOf(0) }
            Card(header, active, trailing = durMinutes(mins.toInt()), scroll = scroll) {
                StopLine(alight?.shown() ?: T("your stop", "התחנה שלכם"), alight?.code, legMode(ride, r), stopId = ride.toStop,
                    streetView = alight?.point?.let { lookAt(it, ride, r) })
                Spacer(Modifier.height(K.gap2))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Box(Modifier.weight(1f)) { LineRow(ride, r) }
                    if (report != null) rideReport(ride, r, busOn(ride, r, trip), now)?.let { ReportButton { report(it) } }
                }
                // Above the stops, so a long ride's payment is in sight without scrolling.
                pay?.invoke(step.legIndex, ride)
                if (stops.size > 1) {
                    // The rail draws its first stop 5 dp into its row, hence the 5 dp less above it.
                    if (pay != null) {
                        // The pay card already parts the line from the stops: the same gap below it as above.
                        Spacer(Modifier.height(K.gap3 - 5.dp))
                    } else {
                        Spacer(Modifier.height(K.gap2))
                        Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
                        Spacer(Modifier.height(K.gap2 - 5.dp))
                    }
                    StopRail(
                        stops, names, progress, ride.arr, times,
                        Modifier.onGloballyPositioned { railTop = it.positionInParent().y.toInt() },
                        follow = if (active) { row -> scroll.animateScrollTo(railTop + row) } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun StopLine(
    name: String, code: String?, mode: Mode?, platform: String = "", stopId: Int = -1,
    streetView: StreetLook? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val lines: @Composable () -> Unit = {
            Column {
                Text(name, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!code.isNullOrBlank()) Text(T("Stop ", "תחנה ") + code, fontSize = 12.sp, color = K.dim)
            }
        }
        if (mode != null || stopId > 0) MarkedLines(stopId, mode, lines, Modifier.weight(1f)) {
            if (platform.isNotBlank()) PlatformTag(platform)
        } else Column(Modifier.weight(1f)) {
            lines()
            if (platform.isNotBlank()) {
                Spacer(Modifier.height(K.gap1))
                PlatformTag(platform)
            }
        }
        if (streetView != null) {
            Spacer(Modifier.width(K.gap2))
            StreetViewButton(streetView, name)
        }
    }
}

// A stop's mark beside its name and number: as tall as those two lines together and centred on them, but never more
// than a one-line name and its number make, so a long name doesn't blow it up. below: the platform, under the lines.
@Composable
private fun MarkedLines(
    stopId: Int, mode: Mode?, lines: @Composable () -> Unit, modifier: Modifier = Modifier,
    below: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { c ->
        val gap = K.gap3.roundToPx()
        val most = 40.dp.roundToPx()
        fun measureLines(mark: Int, key: String) = subcompose(key, lines).first()
            .measure(Constraints(maxWidth = (c.maxWidth - mark - gap).coerceAtLeast(0)))
        // Measured once with a guess at the mark, then again with room for the mark it really gets.
        val first = measureLines(most, "guess")
        val text = measureLines(minOf(first.height, most), "lines")
        val size = minOf(text.height, most)
        val mark = subcompose("mark") {
            StopGlyphOrPhoto(
                stopId, mode, thumb = size.toDp(), mark = size.toDp(), opened = !uk.noammm.kav.data.KavPlan.isKav(stopId),
            )
        }.map { it.measure(Constraints.fixed(size, size)) }
        val under = subcompose("below", below).map { it.measure(Constraints(maxWidth = (c.maxWidth - size - gap).coerceAtLeast(0))) }
        val underHeight = under.maxOfOrNull { it.height }?.takeIf { it > 0 }?.let { it + K.gap1.roundToPx() } ?: 0
        layout(c.maxWidth, text.height + underHeight) {
            mark.forEach { it.placeRelative(0, (text.height - size) / 2) }
            text.placeRelative(size + gap, 0)
            under.forEach { it.placeRelative(size + gap, text.height + K.gap1.roundToPx()) }
        }
    }
}

@Composable
internal fun StopRail(
    stops: List<Int>,
    names: Map<Int, Transit.StopInfo>,
    progress: Float,
    arriveUtc: Long,
    times: List<StopTime> = emptyList(),
    modifier: Modifier = Modifier,
    follow: (suspend (Int) -> Unit)? = null,
) {
    val centres = remember(stops) { mutableStateListOf<Float>().also { c -> repeat(stops.size) { c.add(Float.NaN) } } }
    val tops = remember(stops) { mutableStateListOf<Int>().also { c -> repeat(stops.size) { c.add(-1) } } }
    val density = LocalDensity.current
    val cap = with(density) { 11.dp.toPx() }
    val passed = (progress.toInt() - 1).coerceIn(0, (stops.size - 1).coerceAtLeast(0))
    val passedTop = tops.getOrElse(passed) { -1 }
    if (follow != null) LaunchedEffect(passed, passedTop >= 0) {
        if (progress >= 1f && passedTop >= 0) follow(passedTop)
    }
    // The arrow where the ride has got to, pointing the way it goes: about the rail's 20 dp across on any screen, and
    // made again when the look changes, since its colours are drawn into it.
    val bg = K.bg
    val ink = K.text
    val stopArrowImageBitmap = remember(bg, ink, density.density) {
        val up = getArrowBitmap(density.density * .55f)
        Bitmap.createBitmap(up, 0, 0, up.width, up.height, Matrix().apply { postRotate(180f) }, true).asImageBitmap()
    }
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 10.dp.toPx() else 10.dp.toPx()
            val wide = 3.dp.toPx()
            var decidedSplit = Float.NaN
            for (i in 0 until stops.lastIndex) {
                val y0 = centres.getOrElse(i) { Float.NaN }
                val y1 = centres.getOrElse(i + 1) { Float.NaN }
                if (y0.isNaN() || y1.isNaN() || y1 <= y0) continue
                val f = (progress - (i + 1)).coerceIn(0f, 1f)
                val split = y0 + (y1 - y0) * f
                if (f > 0f) drawLine(K.routeIdle, Offset(x, y0), Offset(x, split), wide)
                if (f < 1f) drawLine(K.route, Offset(x, split), Offset(x, y1), wide)
                if (f > 0f && f < 1f) decidedSplit = split
            }
            stops.forEachIndexed { i, _ ->
                val cy = centres.getOrElse(i) { Float.NaN }
                if (cy.isNaN()) return@forEachIndexed
                val done = progress >= i + 1
                val rad = if (i == 0 || i == stops.lastIndex) 5.dp.toPx() else 3.5.dp.toPx()
                drawCircle(K.bg, rad + 2.dp.toPx(), Offset(x, cy))
                if (done) drawCircle(K.routeIdle, rad, Offset(x, cy))
                else drawCircle(K.route, rad, Offset(x, cy), style = Stroke(2.dp.toPx()))
            }
            if (!decidedSplit.isNaN()) {
                val r = stopArrowImageBitmap.width / 2f + .1f
                drawCircle(K.bg, radius = r, center = Offset(x, decidedSplit), alpha = .5f)
                drawCircle(K.text, radius = r, center = Offset(x, decidedSplit), style = Stroke(2.dp.toPx()))
                drawImage(stopArrowImageBitmap, topLeft = Offset(x - stopArrowImageBitmap.width / 2, decidedSplit - stopArrowImageBitmap.height / 2))
            }
        }
        Column(Modifier.fillMaxWidth()) {
            stops.forEachIndexed { i, id ->
                val last = i == stops.lastIndex
                val done = progress >= i + 1
                Row(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        centres[i] = it.positionInParent().y + cap.coerceAtMost(it.size.height * .5f)
                        tops[i] = it.positionInParent().y.toInt()
                    },
                ) {
                    Spacer(Modifier.width(20.dp + K.gap2))
                    Text(
                        names[id]?.shown() ?: "…",
                        style = K.type.subhead,
                        color = if (done) K.dim else K.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                    )
                    val time = times.getOrNull(i)
                    if (time != null && !done) LiveText(
                        hm.format(Date(time.utc * 1000)), style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp),
                        color = if (time.live) K.realtime else K.dim, modifier = Modifier.padding(vertical = 3.dp),
                    )
                    else if (times.isEmpty() && last && arriveUtc > 0) Text(
                        hm.format(Date(arriveUtc * 1000)),
                        fontSize = 12.sp, color = K.dim, modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineRow(ride: Transit.Leg, r: Transit.Resolved) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val plate = linePlate(rt, agency) { Operators.of(r, agency) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The strip look for buses, when chosen in Settings.
        if (K.lineBadgeAppearance == LineBadgeAppearance.STRIP && modeOf(rt) == Mode.BUS) {
            LinePlateBadge(ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…", Mode.BUS, plate)
        } else Column(
            Modifier.width(IntrinsicSize.Min).clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.plate)
                .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(K.rBadge)),
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…",
                    fontSize = 16.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        Spacer(Modifier.width(K.gap3))
        Text(
            info?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "לכיוון ${shownName(it)}") } ?: "",
            style = K.type.subhead, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Card(
    header: String,
    active: Boolean,
    trailing: String? = null,
    scroll: ScrollState = rememberScrollState(),
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
                header, style = K.type.subhead, fontWeight = FontWeight.Medium,
                color = if (active) K.live else K.text, modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(
                trailing, style = K.type.subhead, fontWeight = FontWeight.Medium,
                color = if (active) K.live else K.muted,
            )
        }
        // A long card stops scrolling at its ends instead of dragging the page.
        val atEnds = remember(scroll) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                    if (scroll.maxValue > 0) available.copy(x = 0f) else Offset.Zero
                override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                    if (scroll.maxValue > 0) available.copy(x = 0f) else Velocity.Zero
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false).nestedScroll(atEnds).verticalScroll(scroll)
                .padding(K.gap3),
            content = body,
        )
    }
}

private const val CAMERA_FIX_S = 15 * 60L

private fun followFor(
    step: Step?, chosenRide: Transit.Leg?, fix: Fix?, heading: Float?, now: Long,
    heldWalk: Boolean = false,
): Follow? {
    val recent = fix?.takeIf { now - it.at <= CAMERA_FIX_S }
    return when (step) {
        is Step.Walk -> {
            val at = recent ?: return null
            val path = step.leg.shape
            if (!onWalkNow(at.lat, at.lon, path, heldWalk)) return null
            val along = bearingAlong(at.lat, at.lon, path)
            Follow(at.lat, at.lon, heading ?: along ?: 0f, zoom = 19.1f)
        }
        // On board the map turns with the ride, not the phone: a seat facing backwards would point it the wrong way.
        is Step.Ride -> {
            val ride = chosenRide ?: return null
            val at = recent?.takeIf { it.isFresh(now) && it.aboard(ride.shape) } ?: return null
            Follow(at.lat, at.lon, bearingAlong(at.lat, at.lon, ride.shape) ?: heading ?: 0f, zoom = 18.3f)
        }
        else -> null
    }
}

// The bus shown for a ride: while it's being waited for, the soonest of its line still to come at the stop that reports
// where it is, which can be an earlier trip running late rather than the one the plan names; else the ride's own.
internal fun shownArrival(ride: Transit.Leg, waitingFor: Pair<Transit.Leg, Transit.Leg?>?, r: Transit.Resolved, now: Long): Transit.Arrival? {
    if (waitingFor?.first != ride) return r.arrival(ride)
    val soonest = r.departures(ride, waitingFor.second, now)
        .filter { d ->
            val live = r.live[Transit.ArrivalKey(ride.fromStop, d.tripId)]
            // Not one canceled for this stop: it won't stop for the rider.
            d.timeUtc >= now - 60 && d.state != Transit.TimeState.CANCELED && live?.hasLocation == true && live.status != 3
        }
        .minByOrNull { it.timeUtc }
    return soonest?.let { r.live[Transit.ArrivalKey(ride.fromStop, it.tripId)] } ?: r.arrival(ride)
}

@Composable
private fun NavigateMap(
    trip: Transit.Itinerary,
    r: Transit.Resolved,
    step: Step?,
    chosen: Map<Int, Int>,
    waitingFor: Pair<Transit.Leg, Transit.Leg?>?,
    here: Pair<Double, Double>?,
    fix: Fix?,
    heading: Float?,
    now: Long,
    following: Boolean = true,
    tapped: Long? = null,
    onTap: (Long?) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    attribution: Boolean = true,
) {
    val picked = chosenLegs(trip, chosen)
    val legs = picked.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val rideLegs = picked.filter { it.kind == Transit.LegKind.RIDE }
    val rideLegsShapes = legs.filter { it.kind != Transit.LegKind.WALK }
    val tints = routeTints(rideLegsShapes, r)
    val lineRoutes = rideLegs
        .map { l ->
            val a = shownArrival(l, waitingFor, r, now)
            val route = lineRoute(a, r)
            val off = l.shape.lastOrNull() ?: r.stop(l.toStop)?.point
            if (route.size < 2 || off == null) route
            else splitPath(route, off.first, off.second).first
        }
        .filter { it.size >= 2 }
    val vehicles = rideLegs.mapNotNull { shownArrival(it, waitingFor, r, now)?.takeIf { a -> a.hasLocation } }
    val vehicleModes = remember(rideLegs, r) {
        rideLegs.mapNotNull { leg ->
            shownArrival(leg, waitingFor, r, now)?.takeIf { it.hasLocation }
                ?.let { it.tripId to modeOf(r.routeType(r.line(leg.lineId)?.agencyId ?: -1)) }
        }.toMap()
    }
    val stopPoints = remember(rideLegsShapes, r.stops) { rideStopPoints(rideLegsShapes, r.stops) }

    val chosenRide = when (step) {
        is Step.Wait -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        is Step.Ride -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        else -> null
    }
    val focusedVehicle = chosenRide?.let { shownArrival(it, waitingFor, r, now) }?.takeIf { it.hasLocation }
    val chosenFocus = when (step) {
        is Step.Wait -> chosenRide?.shape?.take(1)
        is Step.Ride -> chosenRide?.shape
        is Step.Arrive -> step.focus.takeLast(1)
        else -> null
    }.orEmpty()
    val focus = chosenFocus.ifEmpty { step?.focus.orEmpty() }
        .ifEmpty { legs.flatMap { it.shape } }
    val framedPoints = focus + listOfNotNull(focusedVehicle?.let { it.lat to it.lon })
    val mePulse = animateFloatAsState(if (here == null) 0f else 1f, tween(350), label = "meReveal")
    val vehicleAlpha = animateFloatAsState(if (vehicles.isEmpty()) 0f else 1f, tween(350), label = "vehicleReveal")
    val heldWalk = remember(step) { mutableStateOf(false) }
    // A ride's card follows the rider whenever they are on it, even before the trip has moved on to it.
    val follow = if (following || step is Step.Ride) followFor(step, chosenRide, fix, heading, now, heldWalk.value) else null
    SideEffect { heldWalk.value = follow != null && step is Step.Walk }
    val fresh = fix?.takeIf { it.isFresh(now) }

    val ridingLeg = (step as? Step.Ride)?.let { chosenRide }
    val behind = remember(ridingLeg, fresh?.lat, fresh?.lon, focusedVehicle?.lat, focusedVehicle?.lon, step) {
        val shape = ridingLeg?.shape ?: return@remember emptyList<Pair<Double, Double>>()
        val at = when {
            fresh != null && fresh.aboard(shape) -> fresh.lat to fresh.lon
            focusedVehicle != null && distanceToPath(focusedVehicle.lat, focusedVehicle.lon, shape) < 80 &&
                focusedVehicle.nextStopIndex > focusedVehicle.stopIndex -> focusedVehicle.lat to focusedVehicle.lon
            fresh != null && distanceToPath(fresh.lat, fresh.lon, shape) < 80 -> fresh.lat to fresh.lon
            else -> return@remember emptyList<Pair<Double, Double>>()
        }
        splitPath(shape, at.first, at.second).first
    }

    val walkLegs = legs.filter { it.kind == Transit.LegKind.WALK }
    val marks = remember(picked, tints, r, K.light) { stationMarks(picked, rideLegsShapes, tints, r) }
    val entrance = rememberVectorPainter(Icons.AutoMirrored.Rounded.Login)
    val exit = rememberVectorPainter(Icons.AutoMirrored.Rounded.Logout)
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val geometry = remember(lineRoutes, legs, behind, stopPoints, tints, marks, K.look, K.accent, dir) {
        val station = tripMarks(marks, legs.last().shape.lastOrNull(), entrance, exit, density, dir)
        MapGeometry(
            lines = lineRoutes.map { MapLine(it, K.routeIdle, 3f, casing = 6f) } +
                walkLegs.map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                rideLegsShapes.mapIndexed { i, l -> MapLine(l.shape, tints[i], 4f, casing = 8f) } +
                listOf(MapLine(behind, K.routeIdle, 4f, casing = 8f)),
            dots = stopPoints.flatMap { (ride, at) ->
                val (lat, lon) = at
                listOf(MapDot(lat, lon, K.bg, 6f), MapDot(lat, lon, Color.Transparent, 3.5f, tints[ride], 2f))
            } + boardingMarkers(rideLegsShapes, tints, r, r.stops) + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
            ),
            markers = station.markers,
            images = station.images,
        )
    }

    // Walking, the arrow is the way the phone faces; riding, the way the vehicle goes.
    val arrow = if (follow == null) null else if (step is Step.Ride) follow.bearing else heading
    val nowSec = System.currentTimeMillis() / 1000
    fun tintOf(v: Transit.Arrival) = if (v.vehicleStatus == 2) K.problem else freshColour(freshnessOf(v.sampleUtc, nowSec))
    val vehicleMarks = vehicles.map { v -> v to ringedMark(vehicleModes[v.tripId] ?: Mode.BUS, tintOf(v), 12f, 10f, density) }
    val headings = vehicles.filter { it.bearing >= 0 }.map { v -> v to headingMark(tintOf(v), 12f, density) }
    val live = MapGeometry(
        dots = buildList {
            here?.let { (lat, lon) ->
                add(MapDot(lat, lon, K.text.copy(alpha = 0.16f * mePulse.value), 15f))
                if (arrow == null) {
                    add(MapDot(lat, lon, K.bg.copy(alpha = mePulse.value), 8f))
                    add(MapDot(lat, lon, K.text.copy(alpha = mePulse.value), 5f))
                }
            }
        },
        markers = (
            if (arrow != null) here?.let { (lat, lon) ->
                listOf(MapMarker(lat, lon, MAP_ARROW_ICON, arrow, mePulse.value, turns = true))
            }.orEmpty() else emptyList()
        ) + vehicleMarks.map { (v, mark) -> MapMarker(v.lat, v.lon, mark.first, alpha = vehicleAlpha.value) } +
            headings.map { (v, mark) ->
                MapMarker(v.lat, v.lon, mark.first, v.bearing.toFloat(), vehicleAlpha.value, turns = true)
            },
        halos = vehicles.map { v -> MapDot(v.lat, v.lon, tintOf(v).copy(alpha = 0.20f * vehicleAlpha.value), 20f) },
        images = vehicleMarks.associate { it.second } + headings.associate { it.second },
    )

    val reach = with(LocalDensity.current) { 36.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        TileMap(framedPoints, modifier, focusKey = step to chosenRide?.tripId,
            fitMaxZoom = if (step is Step.Walk || step is Step.Arrive) 18.4f else Geo.MAX_Z.toFloat(),
            recenterOn = remember(here, legs) { recentreOn(here, legs.map { it.shape }) },
            contentPadding = contentPadding, follow = follow,
            geometry = geometry, live = live, attribution = attribution,
            onTap = { at, proj ->
                onTap(vehicles.map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
                    .filter { it.second <= reach }.minByOrNull { it.second }?.first?.tripId
                    ?.takeIf { it != tapped })
            },
        )
    }
}

@Composable
private fun VehicleCard(
    a: Transit.Arrival, leg: Transit.Leg, r: Transit.Resolved, now: Long, modifier: Modifier,
    onReport: (ReportFor) -> Unit, onClose: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glassSurface(K.rCard).padding(K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        val line = r.line(leg.lineId)
        val number = leg.shortName.ifBlank { line?.number.orEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(number.ifBlank { null }, line?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") })
                    .joinToString(" · "),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            KavIconButton(Icons.Rounded.Close, T("Close", "סגירה"), onClose, size = 36.dp)
        }
        val (headline, tint) = when {
            a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            else -> locationHeadline(a.sampleUtc, now)
        }
        Text(headline, fontSize = 14.sp, color = tint)
        if (a.sampleUtc > 0) Text(
            updatedLabel(a),
            style = K.type.subhead,
        )
        val eta = a.rtUtc.takeIf { it > 0 } ?: a.staticUtc
        r.stopName(a.stopId)?.takeIf { it.isNotBlank() && eta > 0 }?.let {
            // Marked and coloured like the search results' departures.
            val d = a.departure()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("At $it: ", "ב$it: "), style = K.type.subhead)
                DepMarkGlyph(d, 12.dp); Spacer(Modifier.width(4.dp))
                LiveText(whenLabel(eta, now), style = K.type.subhead, color = depColour(d), fontWeight = FontWeight.SemiBold)
            }
        }
        if (a.platform.isNotBlank()) Text(T("Platform ${a.platform}", "רציף ${a.platform}"), style = K.type.subhead)
        KavLive.plateOf(a.vehicleId)?.let { Text(T("Number plate $it", "מספר רישוי $it"), style = K.type.subhead) }
        rideReport(leg, r, a, now)?.let { report ->
            Text(if (report.train) T("Report this train", "דיווח על הרכבת הזו") else T("Report this bus", "דיווח על האוטובוס הזה"),
                fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) { onReport(report) }.padding(vertical = K.gap1))
        }
    }
}

// Street View of a stop or place: a small round button at the end of its name, with Street View's own figure.
@Composable
internal fun StreetViewButton(look: StreetLook, name: String) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(K.fill)
            .semantics { contentDescription = T("See $name in Street View", "צפייה ב-$name ב-Street View") }
            .clickable(role = Role.Button) { StreetViewer.open = StopLook(look, name) },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(18.dp)) { streetGlyph(K.textSecondary) } }
}

// Reporting a problem on a ride to the ministry: a flag at the end of the ride's line.
@Composable
internal fun ReportButton(onClick: () -> Unit) =
    KavIconButton(Icons.Rounded.OutlinedFlag, T("Report a problem on this ride", "דיווח על בעיה בנסיעה הזו"), onClick, size = 36.dp)

// Changing trains where the ride ends: whether the next one leaves from the platform this one comes in at. The next
// train's platform whenever it is known, as the trip's details show it; the one this train comes in at only comes with
// the live times where the rider gets off, often not yet. Null when it isn't a change of trains there, or the next
// one's platform isn't known.
internal fun platformChange(step: Step.Ride, ride: Transit.Leg, r: Transit.Resolved, chosen: Map<Int, Int>): String? {
    val (next, nextWait) = step.change?.let { boardingChoice(it.ride, it.wait, chosen[it.legIndex] ?: 0) } ?: return null
    val code = r.stop(next.fromStop)?.code
    val alight = r.stop(ride.toStop)?.code
    val there = next.fromStop == ride.toStop || !code.isNullOrBlank() && code == alight
    val out = if (there && r.isTrain(ride) && r.isTrain(next)) r.platform(next, nextWait) else ""
    if (out.isBlank()) return null
    val inAt = r.alightPlatform(ride)
    return when {
        inAt.isBlank() -> T("Your next train leaves from platform $out", "הרכבת הבאה יוצאת מרציף $out")
        inAt == out -> T("Your next train leaves from the same platform", "הרכבת הבאה יוצאת מאותו רציף")
        else -> T("You arrive on platform $inAt · your next train leaves from platform $out",
            "מגיעים לרציף $inAt · הרכבת הבאה יוצאת מרציף $out")
    }
}

// Where Street View stands and which way it looks, in degrees clockwise from north; without a heading Google picks one,
// which tends to be down the road. target: what it looks at, so a panorama Google took a few metres off still faces it.
internal class StreetLook(
    val at: Pair<Double, Double>, val heading: Int? = null, val target: Pair<Double, Double>? = at.takeIf { heading == null },
)

// How far from the middle of the road a stop on it has its kerb, roughly.
private const val KERB_M = 6.0

// A stop as seen from the road its line takes: from the road's nearest point, facing the stop. One the road runs right
// over is on its right, where traffic drives here. Without the road, the stop itself, facing wherever Google likes.
internal fun lookAt(stop: Pair<Double, Double>, road: List<Pair<Double, Double>>): StreetLook {
    if (road.size < 2) return StreetLook(stop)
    val at = onRoute(stop, road)
    if (metres(at.first, at.second, stop.first, stop.second) >= 3) {
        return StreetLook(at, Math.floorMod(bearing(at.first, at.second, stop.first, stop.second).roundToInt(), 360), stop)
    }
    val heading = bearingAlong(at.first, at.second, road)?.plus(90f) ?: return StreetLook(stop)
    return StreetLook(at, Math.floorMod(heading.roundToInt(), 360), offsetBy(stop, heading, KERB_M))
}

// A stop of Kav's timetable, seen from the road its buses take there: all the timetable knows of that road is the stops
// before and after it on a trip that calls there. From the middle of the road, beside the stop, facing it on the right,
// where traffic drives here. A stop no trip leaves from, the stop itself.
internal fun lookAt(net: uk.noammm.kav.data.Net, stop: Int): StreetLook {
    val at = net.lat[stop].toDouble() to net.lon[stop].toDouble()
    for (i in net.dStart[stop] until net.dStart[stop + 1]) {
        val k = net.cST[net.dConn[i]]
        val t = net.tripOf(k)
        val before = (k - 1).takeIf { it >= net.tripStart[t] }?.let { net.stStop[it] } ?: stop
        val after = (k + 1).takeIf { it < net.tripStart[t + 1] }?.let { net.stStop[it] } ?: stop
        if (before == after) continue
        val way = bearing(net.lat[before].toDouble(), net.lon[before].toDouble(), net.lat[after].toDouble(), net.lon[after].toDouble())
        return StreetLook(offsetBy(at, way - 90f, KERB_M), Math.floorMod((way + 90f).roundToInt(), 360), at)
    }
    return StreetLook(at)
}

// A stop of a ride, seen from the road the ride takes. A train's line, or a cable car's, is no road to stand on: the
// station itself then.
internal fun lookAt(stop: Pair<Double, Double>, ride: Transit.Leg, r: Transit.Resolved): StreetLook =
    lookAt(stop, if (legMode(ride, r) in OFF_ROAD) emptyList() else ride.shape)

private val OFF_ROAD = setOf(Mode.SUBWAY, Mode.TRAIN, Mode.FERRY, Mode.CABLE, Mode.GONDOLA, Mode.FUNICULAR)

// A place at the end of a walk, seen from the walk a little before it, facing it.
internal fun lookAhead(walk: List<Pair<Double, Double>>): StreetLook? {
    val to = walk.lastOrNull() ?: return null
    if (walk.size < 2) return StreetLook(to)
    val stand = pointAlong(walk, pathLength(walk) - 20) ?: return StreetLook(to)
    if (metres(stand.first, stand.second, to.first, to.second) < 3) return StreetLook(to)
    return StreetLook(stand, Math.floorMod(bearing(stand.first, stand.second, to.first, to.second).roundToInt(), 360), to)
}

internal fun rideStopPoints(legs: List<Transit.Leg>, stops: Map<Int, Transit.StopInfo>): List<Pair<Int, Pair<Double, Double>>> =
    legs.indices.flatMap { i ->
        val leg = legs[i]
        (leg.stops.mapNotNull { stops[it]?.point }.map { onRoute(it, leg.shape) } + listOfNotNull(
            leg.shape.firstOrNull().takeIf { stops[leg.fromStop]?.point == null && stops[leg.stops.firstOrNull()]?.point == null },
            leg.shape.lastOrNull().takeIf { stops[leg.toStop]?.point == null && stops[leg.stops.lastOrNull()]?.point == null },
        )).map { i to it }
    }.distinct()
