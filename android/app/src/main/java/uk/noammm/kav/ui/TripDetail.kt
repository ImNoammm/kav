@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A trip map and summary followed by its steps, with navigation and live tracking. */

private val hm = SimpleDateFormat("HH:mm", Locale.US)

private enum class Node { ORIGIN, WALK, BOARD, RIDE, ALIGHT, DEST }

/**
 * The plan, navigation, and the live view of one vehicle, in one host.
 *
 * Back inside here never leaves for Home on its own: navigation goes back to the plan,
 * and a plan opened from navigation's own button goes back to navigation. Only the
 * plan's Back, reached the ordinary way, calls [onBack].
 */
@Composable
fun TripDetailScreen(
    model: KavModel,
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onBack: () -> Unit,
    startInNavigation: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onNavigating: (Boolean) -> Unit = {},
) {
    // a ride opened for tracking, or the whole trip started, takes over the screen
    var tracking by remember { mutableStateOf<Pair<Moovit.Leg, Int>?>(null) }
    var navigating by remember(trip) { mutableStateOf(startInNavigation) }
    // the plan was opened from navigation's own button, so back returns there
    var planFromNavigation by remember(trip) { mutableStateOf(false) }
    // The tab bar belongs to the app, not to a trip in progress. The host hides it
    // while this screen is navigating and gets it back however navigation ends,
    // including the back gesture and the screen being torn down under it.
    LaunchedEffect(navigating) { onNavigating(navigating) }
    DisposableEffect(Unit) { onDispose { onNavigating(false) } }
    // One host for both the plan and navigation: an alert row in either opens here.
    var alert by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun leavePlan() {
        if (planFromNavigation) { planFromNavigation = false; navigating = true } else onBack()
    }
    androidx.activity.compose.BackHandler {
        when {
            alert != null -> alert = null
            navigating -> { navigating = false; planFromNavigation = false }
            tracking != null -> tracking = null
            else -> leavePlan()
        }
    }
    CompositionLocalProvider(LocalServiceAlertOpener provides { group, label ->
        alert = group to label
    }) {
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(tracking != null, navigating, tracking),
        transitionSpec = {
            val goingDeeper = (targetState.first || targetState.second) &&
                !(initialState.first || initialState.second)
            if (goingDeeper) forward() else backward()
        },
        label = "trip",
    ) { (isTracking, isNavigating, target) ->
        when {
            isTracking && target != null ->
                LiveLocationScreen(target.first, target.second, r) { tracking = null }
            isNavigating -> NavigateScreen(
                model, trip, r, fromLabel, toLabel,
                onStop = { navigating = false; onEnd() },
                onExit = { navigating = false; planFromNavigation = false },
                onPlan = { navigating = false; planFromNavigation = true },
            )
            else -> TripDetailBody(trip, r, fromLabel, toLabel, onBack = ::leavePlan,
                onTrack = { leg, stopId -> tracking = leg to stopId },
                onStart = { navigating = true; planFromNavigation = false; onStart() })
        }
    }
    alert?.let { (group, label) ->
        ServiceAlertSheet(group, label) { alert = null }
    }
    }
}

@Composable
private fun TripDetailBody(
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onBack: () -> Unit,
    onTrack: (Moovit.Leg, Int) -> Unit,
    onStart: () -> Unit,
) {

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(K.gap3))
            Sig("Your", "trip", Modifier.weight(1f))
            StartButton(onStart)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current)) {
            RouteMap(trip, r, modifier = Modifier.padding(horizontal = K.gap3, vertical = K.gap2))
            Summary(trip, r)
            Spacer(Modifier.height(K.gap3))
            Timeline(trip, r, fromLabel, toLabel, onTrack)
            Spacer(Modifier.height(K.gap8))
        }
    }
}

/* summary sheet */

@Composable
private fun Summary(trip: Moovit.Itinerary, r: Moovit.Resolved) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .glassSurface(K.rCard).padding(K.gap4),
    ) {
        Text(
            dur((trip.arr - trip.dep).toInt()), fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold, color = K.text,
        )
        Spacer(Modifier.height(K.gap1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Text("Arrives ${hm.format(Date(trip.arr * 1000))}", fontSize = 14.sp, color = K.muted)
            if (trip.fare >= 0) {
                Text("%s%.2f".format(trip.currency, trip.fare / 100.0), fontSize = 14.sp, color = K.muted)
            }
        }
        Spacer(Modifier.height(K.gap3))
        TripStrip(trip, r)
        val chips = ArrayList<String>()
        if (trip.accessible) chips.add("Step-free")
        if (trip.co2g >= 0) chips.add(co2(trip.co2g))
        trip.tags.forEach { chips.add(it) }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(K.gap2))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { c ->
                    Text(
                        c, fontSize = 14.sp, color = K.muted,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(K.plate)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * The subtitle under a departure: Moovit's `TimePresentationType.textResId`, word for
 * word from its own strings.xml. A DELAYED or AHEAD_OF_TIME status is not part of that
 * table, so it is appended after, Moovit spells it out in the station schedule row
 * (Time.Status), never by recolouring the time.
 */
private fun depNote(deps: List<Moovit.Departure>): String? {
    val d = deps.firstOrNull() ?: return null
    val state = when (d.state) {
        Moovit.TimeState.CANCELED -> "Canceled for this station"
        Moovit.TimeState.FREQUENCY -> null
        Moovit.TimeState.OUT_OF_SHAPE -> "Deviated from route"
        Moovit.TimeState.REAL_TIME -> null
        Moovit.TimeState.REAL_TIME_HIGH -> "Arrival time is accurate"
        Moovit.TimeState.REAL_TIME_MEDIUM -> "Arrival time is fairly accurate"
        Moovit.TimeState.REAL_TIME_LOW -> "Arrival time may not be accurate"
        Moovit.TimeState.REAL_TIME_DROPPED -> "Real-Time unavailable"
        Moovit.TimeState.STATISTICAL -> "Based on previous arrivals"
        Moovit.TimeState.STATIC -> "Scheduled time"
    }
    val alert = when {
        d.alert == 4 -> "Service alert on this line"
        d.alert == 3 -> "Service change on this line"
        d.status == 2 -> "Running late"
        d.status == 4 -> "Running ahead of schedule"
        else -> null
    }
    return listOfNotNull(state, alert).joinToString(" · ").ifBlank { null }
}

/** Compact emissions label, using grams below one kilogram. */
fun co2(g: Int): String = if (g < 1000) "$g g CO2e" else "%.2f kg CO2e".format(g / 1000.0)

@Composable
private fun TripStrip(trip: Moovit.Itinerary, r: Moovit.Resolved) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var first = true
        for (l in trip.legs) {
            val glyph: @Composable () -> Unit = when (l.kind) {
                Moovit.LegKind.WALK -> { { WalkGlyph(K.muted, 16.dp) } }
                Moovit.LegKind.TAXI -> { { ModeGlyph(Mode.TAXI, K.muted, 17.dp) } }
                Moovit.LegKind.RIDE -> { { RouteChoices(l, r) } }
                else -> continue
            }
            if (!first) Text("›", fontSize = 13.sp, color = K.surface4)
            first = false
            glyph()
        }
    }
}

/* the timeline */

@Composable
private fun Timeline(
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onTrack: (Moovit.Leg, Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(vertical = K.gap2),
    ) {
        Rail(Node.ORIGIN, thick = false, top = true) {
            Endpoint(fromLabel, hm.format(Date(trip.dep * 1000)), "Leave at")
        }
        trip.legs.forEachIndexed { i, l ->
            when (l.kind) {
                // a street walk followed by a walk inside the station is one walk to a
                // rider, so only the first of a run gets a row and it carries the total
                Moovit.LegKind.WALK -> {
                    val prev = trip.legs.getOrNull(i - 1)
                    if (prev?.kind != Moovit.LegKind.WALK) {
                        var mins = 0; var metres = 0; var j = i
                        while (j < trip.legs.size && trip.legs[j].kind == Moovit.LegKind.WALK) {
                            mins += trip.legs[j].minutes; metres += trip.legs[j].meters; j++
                        }
                        if (mins >= 1 || metres > 30) Rail(Node.WALK, thick = false) {
                            Step(walkLabel(metres, mins), null)
                        }
                    }
                }
                Moovit.LegKind.TAXI -> Rail(Node.RIDE, thick = true) {
                    Step("Gett · ${l.minutes} min", null)
                    Spacer(Modifier.height(K.gap2))
                    GettButton(l)
                }
                Moovit.LegKind.BIKE -> Rail(Node.RIDE, thick = true) {
                    Step("Cycle ${l.minutes} min", null)
                }
                Moovit.LegKind.RIDE -> {
                    val wait = trip.legs.getOrNull(i - 1)?.takeIf { w -> w.kind == Moovit.LegKind.WAIT }
                    val board = r.stop(l.fromStop)
                    val alight = r.stop(l.toStop)
                    Rail(Node.BOARD, thick = false) {
                        StopRowDetail(
                            board?.name ?: "Board here", board?.code,
                            hm.format(Date(l.dep * 1000)), legMode(l, r),
                        )
                        Spacer(Modifier.height(K.gap2))
                        BoardCard(l, wait, r, onTrack)
                    }
                    Rail(Node.RIDE, thick = true) {
                        Step(rideLabel(l), if (l.fare >= 0) "%s%.2f".format(l.currency, l.fare / 100.0) else null)
                    }
                    Rail(Node.ALIGHT, thick = false) {
                        StopRowDetail(
                            alight?.name ?: "Get off here", alight?.code,
                            hm.format(Date(l.arr * 1000)), legMode(l, r),
                        )
                    }
                }
                else -> {}
            }
        }
        Rail(Node.DEST, thick = false, bottom = true) {
            Endpoint(toLabel, hm.format(Date(trip.arr * 1000)), "Arrive")
        }
    }
}

private fun walkLabel(metres: Int, mins: Int): String {
    val d = if (metres > 0) distanceLabel(metres.toDouble()) else null
    val m = if (mins >= 1) "$mins min" else null
    return listOfNotNull("Walk", d, m).let {
        if (it.size == 3) "Walk ${it[1]} · ${it[2]}" else it.joinToString(" ")
    }
}

private fun rideLabel(l: Moovit.Leg): String {
    // stopSequenceIds includes the stop you board at, so the ride is one fewer
    val n = (l.stops.size - 1).coerceAtLeast(0)
    val stops = if (n == 1) "1 stop" else "$n stops"
    return if (n > 0) "Ride $stops · ${l.minutes} min" else "Ride ${l.minutes} min"
}

/**
 * One row of the timeline: the rail is drawn behind the row at full height so the
 * segments meet, with the node sitting on it. `thick` marks the on-vehicle stretch.
 */
@Composable
private fun Rail(
    node: Node,
    thick: Boolean,
    top: Boolean = false,
    bottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(44.dp).fillMaxHeight()) {
            val x = size.width * .55f
            val w = if (thick) size.width * .16f else size.width * .045f
            val nodeY = 22.dp.toPx().coerceAtMost(size.height * .5f)
            val tint = if (thick) K.muted else K.surface4
            if (!top) drawLine(tint, Offset(x, 0f), Offset(x, nodeY), w, StrokeCap.Butt)
            if (!bottom) drawLine(tint, Offset(x, nodeY), Offset(x, size.height), w, StrokeCap.Butt)
            when (node) {
                Node.ORIGIN, Node.DEST ->
                    drawCircle(K.text, 7.dp.toPx(), Offset(x, nodeY), style = Stroke(2.5.dp.toPx()))
                Node.BOARD, Node.ALIGHT -> {
                    drawCircle(K.surface1, 6.dp.toPx(), Offset(x, nodeY))
                    drawCircle(K.text, 4.5.dp.toPx(), Offset(x, nodeY))
                }
                else -> {}
            }
        }
        Column(
            Modifier.weight(1f).padding(end = K.gap4, top = K.gap2, bottom = K.gap2),
            content = content,
        )
    }
}

@Composable
private fun Endpoint(label: String, time: String, when_: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label, fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(K.gap2))
        Column(horizontalAlignment = Alignment.End) {
            Text(time, fontSize = 14.sp, color = K.text)
            Text(when_, fontSize = 14.sp, color = K.dim)
        }
    }
}

/** The vehicle a ride leg is on, for the station mark beside its two stops. */
internal fun legMode(ride: Moovit.Leg, r: Moovit.Resolved): Mode =
    modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1))

@Composable
private fun StopRowDetail(name: String, code: String?, time: String, mode: Mode? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Moovit puts img_general_station_* beside a station's name; without it a rail
        // stop was only its text, and "רכבת ראש העין צפון" read like any bus stop.
        if (mode != null) {
            StationMark(mode, 17.dp)
            Spacer(Modifier.width(K.gap2))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!code.isNullOrBlank()) Text("Stop $code", fontSize = 14.sp, color = K.dim)
        }
        Spacer(Modifier.width(K.gap2))
        Text(time, fontSize = 14.sp, color = K.text)
    }
}

/** A plain step on the rail: "Walk 350 m · 4 min", with an optional trailing chip. */
@Composable
private fun Step(label: String, trailing: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = K.muted, modifier = Modifier.weight(1f))
        if (trailing != null) Text(
            trailing, fontSize = 14.sp, color = K.muted,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(K.plate)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/** Boarding details: line, destination, departures and tracking availability. */
@Composable
private fun BoardCard(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    r: Moovit.Resolved,
    onTrack: (Moovit.Leg, Int) -> Unit,
) {
    val options = Moovit.boardingOptions(ride, wait)
    Column(Modifier.fillMaxWidth()) {
        if (options.size > 1) Text("Take one of these lines", fontSize = 14.sp, color = K.dim)
        options.forEachIndexed { index, (option, boarding) ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = K.gap2).height(1.dp).background(K.border))
            BoardOption(option, boarding, r, onTrack)
        }
    }
}

@Composable
private fun BoardOption(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    r: Moovit.Resolved,
    onTrack: (Moovit.Leg, Int) -> Unit,
) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val now = System.currentTimeMillis() / 1000
    Column(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(K.surface2)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, K.muted, 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "#${ride.lineId}",
                    fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            Spacer(Modifier.width(K.gap3))
            Text(
                info?.destination?.ifBlank { null }?.let { "to $it" } ?: "",
                fontSize = 14.sp, color = K.muted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        val deps = r.departures(ride, wait).filter { it.timeUtc >= now - 60 }.take(3)
        if (deps.isNotEmpty()) {
            Spacer(Modifier.height(K.gap2))
            Text("Departures", fontSize = 14.sp, color = K.dim)
            Spacer(Modifier.height(K.gap1))
            DepartureTimes(deps, now)
            depNote(deps)?.let {
                Text(it, fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1))
            }
        }
        wait?.let { AlertRow(it.alertCategory, it.alertText, r.line(ride.lineId)?.groupId ?: 0) }
        // Tracking requires both a vehicle position and a drawable journey leg.
        val trackable = r.arrival(ride)?.hasLocation == true && ride.shape.size >= 2
        Spacer(Modifier.height(K.gap2))
        LiveLocationButton(trackable) { onTrack(ride, ride.fromStop) }
    }
}
