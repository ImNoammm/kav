package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.Tab
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A destination first, then the steps of a journey the user started. */
@Composable
internal fun HomeScreen(
    model: KavModel,
    recentTrips: List<RecentTrip>,
    onSearch: () -> Unit,
    onTrip: (RecentTrip) -> Unit,
    onResume: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Home", "", onSettings = { model.settingsOpen = true }, badge = model.update != null)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = K.gap4, end = K.gap4, top = K.gap2,
                bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).glassSurface(24.dp)
                        .clickable(role = Role.Button, onClickLabel = "Search destination", onClick = onSearch)
                        .padding(horizontal = K.gap5, vertical = K.gap4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val w = size.width
                        drawCircle(K.accent, w * .29f, Offset(w * .40f, w * .40f), style = Stroke(w * .08f))
                        drawLine(K.accent, Offset(w * .63f, w * .63f), Offset(w * .88f, w * .88f), w * .08f, StrokeCap.Round)
                    }
                    Text("Where to?", fontSize = 19.sp, color = K.muted, modifier = Modifier.weight(1f))
                }
            }
            item {
                val journey = model.activeJourney
                if (journey != null) JourneyCard(model, journey, onResume)
                else Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1)
                        .padding(K.gap5),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text("Ready when you are", fontSize = 21.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Note("Choose a destination to see your route and what to do next.")
                }
            }
            if (recentTrips.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    Text("Recent trips", fontSize = 15.sp, color = K.muted, fontWeight = FontWeight.Medium)
                    Column(Modifier.clip(RoundedCornerShape(K.rCard)).background(K.surface1)) {
                        recentTrips.forEachIndexed { index, trip ->
                            if (index > 0) Box(
                                Modifier.padding(start = 52.dp, end = K.gap4).fillMaxWidth()
                                    .height(1.dp).background(K.border),
                            )
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                    .clickable(role = Role.Button) { onTrip(trip) }
                                    .padding(K.gap4),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(K.gap4),
                            ) {
                                ClockGlyph(K.dim, 20.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        trip.to.name, fontSize = 15.sp, color = K.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "from " + (trip.from?.name ?: "Current location"),
                                        fontSize = 13.sp, color = K.dim,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(tripWhen(trip.at), fontSize = 12.sp, color = K.dim)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                    HomeShortcut("Stations", false, Modifier.weight(1f)) { model.tab = Tab.Stations }
                    HomeShortcut("Lines", true, Modifier.weight(1f)) { model.tab = Tab.Lines }
                }
            }
        }
    }
}

/** When a trip was taken: the clock today, the day once it is older than that. */
private fun tripWhen(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    fun sameDay() = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay()) return SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameDay()) return "Yesterday"
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
}

/**
 * The journey under way, as the same cards navigation shows, one per step, to swipe
 * through. The card in front is the step the journey is on; it moves on by itself as
 * the journey does, and Resume opens navigation right there.
 */
@Composable
private fun JourneyCard(model: KavModel, journey: ActiveJourney, onResume: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(journey.trip) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(15_000)
        }
    }
    val steps = remember(journey.trip, journey.fromLabel, journey.toLabel) {
        buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    }
    val current = model.journeyStep.coerceIn(0, steps.lastIndex)
    val pager = rememberPagerState(initialPage = current) { steps.size }
    LaunchedEffect(current) { pager.animateScrollToPage(current) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(vertical = K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Current trip", fontSize = 13.sp, color = K.dim)
                Text("To ${journey.toLabel}", fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "Resume", fontSize = 14.sp, color = K.accent, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                    .clickable(role = Role.Button, onClickLabel = "Resume trip", onClick = onResume)
                    .padding(horizontal = K.gap3, vertical = K.gap2),
            )
        }
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = K.gap4),
            pageSpacing = K.gap2,
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
        ) { page ->
            Box(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
                StepCard(steps[page], journey.resolved, active = page == current, now = now, chosen = journey.chosen,
                    fix = model.fix)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in steps.indices) {
                val on = i == pager.currentPage
                Box(
                    Modifier.padding(horizontal = 3.dp).size(if (on) 7.dp else 5.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (i == current) K.accent else if (on) K.text else K.surface4),
                )
            }
        }
    }
}

/** One line of guidance for a step, for the small window and anything else that has room for one line. */
internal fun stepInstruction(step: Step, journey: ActiveJourney, lastLeg: Boolean, now: Long): Pair<String, String> {
    val r = journey.resolved
    fun time(utc: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(utc * 1000))
    fun stop(id: Int) = r.stopName(id)?.takeIf { it.isNotBlank() }
    fun line(ride: Moovit.Leg): String = ride.shortName.ifBlank {
        r.line(ride.lineId)?.number.orEmpty()
    }.ifBlank {
        "the " + modeName(modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1))).lowercase(Locale.US)
    }
    fun nextStop(id: Int) = stop(id) ?: if (lastLeg) journey.toLabel else "your next stop"
    return when (step) {
        is Step.Start -> "Leave at ${time(step.time)}" to "Start from ${step.label}"
        is Step.Walk -> "Walk to ${nextStop(step.toStop)}" to listOfNotNull(
            "${step.leg.minutes} min".takeIf { step.leg.minutes > 0 },
            distanceLabel(step.leg.meters.toDouble()).takeIf { step.leg.meters > 0 },
        ).joinToString(" · ").ifBlank { "Follow the walking route." }
        is Step.Wait -> {
            val (ride, wait) = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0)
            val departure = r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }
                ?: Moovit.Departure(ride.tripId, ride.dep)
            if (departure.status == 3) "${line(ride)} is cancelled" to "Find another route before continuing."
            else "Wait for ${line(ride)}" to listOfNotNull(
                stop(ride.fromStop),
                (if (departure.live) "Live · " else "Scheduled · ") + whenLabel(departure.timeUtc, now),
            ).joinToString(" · ")
        }
        is Step.Ride -> {
            val (ride, _) = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0)
            "Ride ${line(ride)}" to "Get off at ${nextStop(ride.toStop)}"
        }
        is Step.Taxi -> "Take a taxi" to "Continue to ${nextStop(step.leg.toStop)}"
        is Step.Cycle -> "Cycle to ${nextStop(step.leg.toStop)}" to "Follow the cycling route."
        is Step.Arrive -> "Arrive at ${step.label}" to "Planned arrival ${time(step.time)}"
    }
}

@Composable
private fun HomeShortcut(label: String, lines: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(K.rCard)).background(K.surface1)
            .clickable(role = Role.Button, onClick = onClick).padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            if (lines) listOf(.23f, .5f, .77f).forEach { y ->
                drawCircle(K.accent, w * .065f, Offset(w * .15f, w * y))
                drawLine(K.accent, Offset(w * .35f, w * y), Offset(w * .86f, w * y), w * .08f, StrokeCap.Round)
            } else {
                drawCircle(K.accent, w * .22f, Offset(w * .5f, w * .32f), style = Stroke(w * .08f))
                drawLine(K.accent, Offset(w * .5f, w * .56f), Offset(w * .5f, w * .88f), w * .08f, StrokeCap.Round)
            }
        }
        Text(label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
    }
}
