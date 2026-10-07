@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.StrokeCap
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
import uk.noammm.kav.data.Moovit
import java.util.Date

private val hm get() = clockFormat()

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

internal fun buildSteps(trip: Moovit.Itinerary, fromLabel: String, toLabel: String): List<Step> {
    val all = trip.legs
    val whole = all.flatMap { it.shape }
    val out = ArrayList<Step>()
    out.add(Step.Start(fromLabel, trip.dep, all.firstOrNull { it.shape.isNotEmpty() }?.shape ?: whole))

    all.forEachIndexed { i, l ->
        when (l.kind) {
            Moovit.LegKind.WALK -> {
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

internal fun boardingChoice(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    pick: Int,
): Pair<Moovit.Leg, Moovit.Leg?> {
    val options = Moovit.boardingOptions(ride, wait)
    return options.getOrNull(pick) ?: options.first()
}

internal fun chosenLegs(trip: Moovit.Itinerary, chosen: Map<Int, Int>): List<Moovit.Leg> =
    trip.legs.mapIndexed { i, l ->
        if (l.kind != Moovit.LegKind.RIDE) l else boardingChoice(
            l, trip.legs.getOrNull(i - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }, chosen[i] ?: 0,
        ).first
    }

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
    // The vehicle tapped on the map: its card takes the step cards' place until closed.
    var tappedBus by remember(trip) { mutableStateOf<Long?>(null) }
    val bus = chosenLegs(trip, chosen).firstOrNull { it.kind == Moovit.LegKind.RIDE && r.arrival(it)?.tripId == tappedBus }
        ?.let { leg -> r.arrival(leg)?.takeIf { it.hasLocation }?.let { leg to it } }
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
            NavigateMap(trip, r, steps.getOrNull(pager.settledPage), chosen, here, seen, model.heading, now,
                following = following, tapped = tappedBus, onTap = { tappedBus = it },
                Modifier.fillMaxSize().glassBackdrop(liquid),
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
                            .semantics { contentDescription = T("Back to home", "חזרה לבית") }
                            .clickable(role = Role.Button, onClick = onExit),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(T.backward, fontSize = 28.sp, color = K.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(T("Your trip", "הנסיעה שלכם"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                        Text("${dur((trip.arr - now).toInt().coerceAtLeast(0))} · ${hm.format(Date(trip.arr * 1000))}",
                            fontSize = 12.sp, color = K.muted, maxLines = 1)
                    }
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                            .semantics { contentDescription = T("Show trip plan", "הצגת המסלול") }
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
                    VehicleCard(showing.second, showing.first, r, now, Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap3)) { tappedBus = null }
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
                            pay = { leg, ride -> TripPay(model, leg, ride, r) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(K.gap3).glassSurface()
                        .padding(horizontal = K.gap3, vertical = K.gap2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(T("End trip", "סיום נסיעה"), fontSize = 14.sp, color = K.text,
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rControl))
                            .clickable(role = Role.Button, onClick = onStop)
                            .padding(horizontal = K.gap3, vertical = K.gap3))
                    Spacer(Modifier.weight(1f))
                    Text(T.ltr("${pager.currentPage + 1} / ${steps.size}"), fontSize = 12.sp, color = K.dim)
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
            Modifier.glassSurface(K.rControl).heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = K.gap3, vertical = K.gap2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            if (back) Text(T.backward, color = K.text, fontSize = 22.sp)
            Text(T("Current step", "השלב הנוכחי"), color = K.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (!back) Text(T.onward, color = K.text, fontSize = 22.sp)
        }
    }
}

@Composable
private fun StepStrip(current: Int, count: Int, live: Int, goTo: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3).glassSurface(K.rControl)
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
                        .semantics { contentDescription = T("Step ${i + 1} of $count", "שלב ${i + 1} מתוך $count"); selected = on }
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
            .semantics { contentDescription = if (back) T("Previous step", "שלב קודם") else T("Next step", "שלב הבא") }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width; val h = size.height
            val x1 = mirrorX(if (back) .64f else .38f)
            val x2 = mirrorX(if (back) .34f else .68f)
            drawLine(tint, Offset(w * x1, h * .18f), Offset(w * x2, h * .5f), w * .13f, StrokeCap.Round)
            drawLine(tint, Offset(w * x2, h * .5f), Offset(w * x1, h * .82f), w * .13f, StrokeCap.Round)
        }
    }
}

@Composable
internal fun StepCard(
    step: Step,
    r: Moovit.Resolved,
    active: Boolean,
    now: Long,
    chosen: Map<Int, Int> = emptyMap(),
    fix: Fix? = null,
    onChoose: (leg: Int, option: Int) -> Unit = { _, _ -> },
    // The ride's payment, or its ticket once bought, on its riding card.
    pay: (@Composable (legIndex: Int, ride: Moovit.Leg) -> Unit)? = null,
) {
    when (step) {
        is Step.Start -> Card(T("Start from", "התחלה מ-"), active) {
            Text(step.label, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(T("Leave at ${hm.format(Date(step.time * 1000))}", "יציאה בשעה ${hm.format(Date(step.time * 1000))}"), fontSize = 12.sp, color = K.dim)
        }

        is Step.Arrive -> Card(T("Arrive", "הגעה"), active) {
            Text(step.label, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hm.format(Date(step.time * 1000)), fontSize = 12.sp, color = K.dim)
        }

        is Step.Walk -> {
            val stop = r.stop(step.toStop)
            Card(
                T("Walk ${step.leg.minutes} min to", "הליכה ${step.leg.minutes} דק׳ אל").takeIf { step.leg.minutes >= 1 } ?: T("Walk to", "הליכה אל"),
                active,
                trailing = if (step.leg.meters > 0) distanceLabel(step.leg.meters.toDouble()) else null,
            ) {
                StopLine(
                    stop?.name ?: T("your stop", "התחנה שלכם"), stop?.code,
                    step.toRide?.let { legMode(it, r) },
                    platform = step.toRide?.let { r.platform(it) }.orEmpty(),
                    stopId = step.toStop,
                )
            }
        }

        is Step.Taxi -> Card(T("Take a taxi", "נסיעה במונית"), active, trailing = T("${step.leg.minutes} min", "${step.leg.minutes} דק׳")) {
            Text(T("Continue with Gett", "המשיכו עם Gett"), fontSize = 15.sp, color = K.text)
            Spacer(Modifier.height(K.gap2))
            GettButton(step.leg)
        }

        is Step.Cycle -> Card(T("Cycle ${step.leg.minutes} min", "אופניים ${step.leg.minutes} דק׳"), active) {
            Text(T("Follow the route to your next stop", "המשיכו במסלול אל התחנה הבאה"), fontSize = 15.sp, color = K.text)
        }

        is Step.Wait -> {
            val options = Moovit.boardingOptions(step.ride, step.wait)
            val pick = (chosen[step.legIndex] ?: 0).coerceIn(0, options.lastIndex)
            Card(if (options.size > 1) T("Select a line", "בחרו קו") else T("Wait for", "המתנה ל-"), active) {
                options.forEachIndexed { index, (ride, wait) ->
                    if (index > 0) Spacer(Modifier.height(K.gap2))
                    val picked = index == pick
                    Column(
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
                        val platform = r.platform(ride, wait)
                        if (platform.isNotBlank()) {
                            PlatformTag(platform)
                            Spacer(Modifier.height(K.gap2))
                        }
                        DepartureTimes(r.departures(ride, wait), now)
                        wait?.let {
                            AlertRow(it.alertCategory, it.alertText, r.line(ride.lineId)?.groupId ?: 0)
                        }
                    }
                }
            }
        }

        is Step.Ride -> {
            val (ride, _) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0)
            val stops = ride.stops
            val names = r.stops + rememberStopNames(stops)
            val alight = r.stop(ride.toStop) ?: names[ride.toStop]
            val arrival = r.arrival(ride)
            Card(
                T("Ride ${stops.size - 1} stops to", "נסיעה ${stops.size - 1} תחנות אל").takeIf { stops.size > 1 } ?: T("Ride to", "נסיעה אל"),
                active,
                trailing = T("${ride.minutes} min", "${ride.minutes} דק׳"),
            ) {
                StopLine(alight?.name ?: T("your stop", "התחנה שלכם"), alight?.code, legMode(ride, r), stopId = ride.toStop)
                Spacer(Modifier.height(K.gap2))
                LineRow(ride, r)
                // Above the stops, so a long ride's payment is in sight without scrolling.
                pay?.invoke(step.legIndex, ride)
                if (stops.size > 1) {
                    Spacer(Modifier.height(K.gap2))
                    Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
                    Spacer(Modifier.height(K.gap2))
                    StopRail(stops, names, stopsProgress(ride, names, arrival, fix, now), ride.arr)
                }
            }
        }
    }
}

@Composable
private fun StopLine(name: String, code: String?, mode: Mode?, platform: String = "", stopId: Int = -1) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (mode != null || stopId > 0) {
            StopGlyphOrPhoto(stopId, mode)
            Spacer(Modifier.width(K.gap2))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!code.isNullOrBlank()) Text(T("Stop ", "תחנה ") + code, fontSize = 12.sp, color = K.dim)
            if (platform.isNotBlank()) {
                Spacer(Modifier.height(K.gap1))
                PlatformTag(platform)
            }
        }
    }
}

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

@Composable
private fun StopRail(
    stops: List<Int>,
    names: Map<Int, Moovit.StopInfo>,
    progress: Float,
    arriveUtc: Long,
) {
    val centres = remember(stops) { mutableStateListOf<Float>().also { c -> repeat(stops.size) { c.add(Float.NaN) } } }
    val density = LocalDensity.current
    val cap = with(density) { 11.dp.toPx() }
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 10.dp.toPx() else 10.dp.toPx()
            val wide = 3.dp.toPx()
            for (i in 0 until stops.lastIndex) {
                val y0 = centres.getOrElse(i) { Float.NaN }
                val y1 = centres.getOrElse(i + 1) { Float.NaN }
                if (y0.isNaN() || y1.isNaN() || y1 <= y0) continue
                val f = (progress - (i + 1)).coerceIn(0f, 1f)
                val split = y0 + (y1 - y0) * f
                if (f > 0f) drawLine(K.routeIdle, Offset(x, y0), Offset(x, split), wide)
                if (f < 1f) drawLine(K.route, Offset(x, split), Offset(x, y1), wide)
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
        }
        Column(Modifier.fillMaxWidth()) {
            stops.forEachIndexed { i, id ->
                val last = i == stops.lastIndex
                val done = progress >= i + 1
                Row(
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        centres[i] = it.positionInParent().y + cap.coerceAtMost(it.size.height * .5f)
                    },
                ) {
                    Spacer(Modifier.width(20.dp + K.gap2))
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
}

@Composable
private fun LineRow(ride: Moovit.Leg, r: Moovit.Resolved) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val plate = plateFor(rt, agency)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(IntrinsicSize.Min).clip(RoundedCornerShape(7.dp)).background(plate?.fill ?: K.plate)
                .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(7.dp)),
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "#${ride.lineId}",
                    fontSize = 16.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        Spacer(Modifier.width(K.gap3))
        Text(
            info?.destination?.ifBlank { null }?.let { T("to $it", "לכיוון $it") } ?: "",
            fontSize = 13.sp, color = K.muted, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

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
        // A long card stops scrolling at its ends instead of dragging the page.
        val scroll = rememberScrollState()
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
    step: Step?, chosenRide: Moovit.Leg?, fix: Fix?, heading: Float?, now: Long,
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
        // On board, the same view as walking: the map turns with the phone.
        is Step.Ride -> {
            val ride = chosenRide ?: return null
            val at = recent?.takeIf { it.isFresh(now) && it.aboard(ride.shape) } ?: return null
            Follow(at.lat, at.lon, heading ?: bearingAlong(at.lat, at.lon, ride.shape) ?: 0f, zoom = 18.3f)
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
    following: Boolean = true,
    tapped: Long? = null,
    onTap: (Long?) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val picked = chosenLegs(trip, chosen)
    val legs = picked.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val rideLegs = picked.filter { it.kind == Moovit.LegKind.RIDE }
    val rideLegsShapes = legs.filter { it.kind != Moovit.LegKind.WALK }
    val tints = routeTints(rideLegsShapes, r)
    val fetched = rememberLineRoutes(rideLegs.map { r.arrival(it)?.tripShapeId ?: -1 })
    val lineRoutes = rideLegs
        .map { l ->
            val a = r.arrival(l)
            val route = lineRoute(a, r).ifEmpty { fetched[a?.tripShapeId ?: -1].orEmpty() }
            val off = l.shape.lastOrNull() ?: r.stop(l.toStop)?.point
            if (route.size < 2 || off == null) route
            else splitPath(route, off.first, off.second).first
        }
        .filter { it.size >= 2 }
    val vehicles = rideLegs.mapNotNull { r.arrival(it)?.takeIf { a -> a.hasLocation } }
    val vehicleModes = remember(rideLegs, r) {
        rideLegs.mapNotNull { leg ->
            r.arrival(leg)?.takeIf { it.hasLocation }
                ?.let { it.tripId to modeOf(r.routeType(r.line(leg.lineId)?.agencyId ?: -1)) }
        }.toMap()
    }
    val stopNames = rememberStopNames(remember(rideLegs) { rideLegs.flatMap { it.stops } })
    val stopPoints = remember(rideLegsShapes, r.stops, stopNames) {
        rideStopPoints(rideLegsShapes, r.stops + stopNames)
    }

    val chosenRide = when (step) {
        is Step.Wait -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        is Step.Ride -> boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
        else -> null
    }
    val focusedVehicle = chosenRide?.let { r.arrival(it) }?.takeIf { it.hasLocation }
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

    val walkLegs = legs.filter { it.kind == Moovit.LegKind.WALK }
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
            } + boardingMarkers(rideLegsShapes, tints, r, r.stops + stopNames) + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
            ) + station.dots,
            markers = station.markers,
            images = station.images,
        )
    }

    val walkArrow = follow != null && heading != null
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
                add(MapDot(v.lat, v.lon, K.bg.copy(alpha = vehicleAlpha.value), 12f))
                add(MapDot(v.lat, v.lon, tint.copy(alpha = vehicleAlpha.value), 10f))
            }
        },
        markers = (
            if (walkArrow) here?.let { (lat, lon) ->
                listOf(MapMarker(lat, lon, MAP_ARROW_ICON, heading ?: 0f, mePulse.value, turns = true))
            }.orEmpty() else emptyList()
        ) + vehicles.map { v ->
            MapMarker(v.lat, v.lon, modeIconName(vehicleModes[v.tripId] ?: Mode.BUS), alpha = vehicleAlpha.value)
        },
        halos = vehicles.map { v ->
            val tint = if (v.vehicleStatus == 2) K.problem else K.live
            MapDot(v.lat, v.lon, tint.copy(alpha = 0.20f * vehicleAlpha.value), 20f)
        },
    )

    val reach = with(LocalDensity.current) { 36.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        TileMap(framedPoints, modifier, focusKey = step to chosenRide?.tripId,
            fitMaxZoom = if (step is Step.Walk || step is Step.Arrive) 18.4f else Geo.MAX_Z.toFloat(),
            recenterOn = here, contentPadding = contentPadding, follow = follow,
            geometry = geometry, live = live,
            onTap = { at, proj ->
                onTap(vehicles.map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
                    .filter { it.second <= reach }.minByOrNull { it.second }?.first?.tripId
                    ?.takeIf { it != tapped })
            },
        )
    }
}

@Composable
private fun VehicleCard(a: Moovit.Arrival, leg: Moovit.Leg, r: Moovit.Resolved, now: Long, modifier: Modifier, onClose: () -> Unit) {
    Column(
        modifier.fillMaxWidth().glassSurface(K.rCard).padding(K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        val line = r.line(leg.lineId)
        val number = leg.shortName.ifBlank { line?.number.orEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(number.ifBlank { null }, line?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") })
                    .joinToString(" · "),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(K.rControl))
                    .semantics { contentDescription = T("Close", "סגירה") }
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("✕", fontSize = 16.sp, color = K.muted) }
        }
        val (headline, tint) = when {
            a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            now - a.sampleUtc <= 120 -> T("Location updated recently", "המיקום עודכן לאחרונה") to K.live
            else -> T("Location is estimated", "המיקום משוער") to K.problem
        }
        Text(headline, fontSize = 14.sp, color = tint)
        if (a.sampleUtc > 0) Text(
            T("Location updated: ${hm.format(Date(a.sampleUtc * 1000))}", "המיקום עודכן: ${hm.format(Date(a.sampleUtc * 1000))}"),
            fontSize = 13.sp, color = K.dim,
        )
        val eta = a.rtUtc.takeIf { it > 0 } ?: a.staticUtc
        r.stopName(a.stopId)?.takeIf { it.isNotBlank() && eta > 0 }?.let {
            // Marked and coloured like the search results' departures.
            val d = a.departure()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("At $it: ", "ב$it: "), fontSize = 13.sp, color = K.muted)
                DepMarkGlyph(d, 12.dp); Spacer(Modifier.width(4.dp))
                Text(whenLabel(eta, now), fontSize = 13.sp, color = depColour(d), fontWeight = FontWeight.SemiBold)
            }
        }
        if (a.platform.isNotBlank()) Text(T("Platform ${a.platform}", "רציף ${a.platform}"), fontSize = 13.sp, color = K.muted)
    }
}

internal fun rideStopPoints(legs: List<Moovit.Leg>, stops: Map<Int, Moovit.StopInfo>): List<Pair<Int, Pair<Double, Double>>> =
    legs.indices.flatMap { i ->
        val leg = legs[i]
        (leg.stops.mapNotNull { stops[it]?.point }.map { onRoute(it, leg.shape) } + listOfNotNull(
            leg.shape.firstOrNull().takeIf { stops[leg.fromStop]?.point == null && stops[leg.stops.firstOrNull()]?.point == null },
            leg.shape.lastOrNull().takeIf { stops[leg.toStop]?.point == null && stops[leg.stops.lastOrNull()]?.point == null },
        )).map { i to it }
    }.distinct()
