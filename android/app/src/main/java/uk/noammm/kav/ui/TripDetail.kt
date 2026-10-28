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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.Date
import java.util.Locale
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontWeight

private suspend fun shareTrip(ctx: android.content.Context, trip: Moovit.Itinerary, fromLabel: String, toLabel: String) {
    val url = if (KavPlan.owns(trip)) checkNotNull(KavPlan.shareLink(trip, fromLabel, toLabel)) else {
        val session = Online.open()
        withContext(Dispatchers.IO) { Moovit.shareItinerary(session, trip) }
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(
            android.content.Intent.EXTRA_TEXT,
            T("$fromLabel → $toLabel\n$url", "$fromLabel ← $toLabel\n$url"),
        )
    ctx.startActivity(android.content.Intent.createChooser(send, T("Share trip", "שיתוף נסיעה")))
}

private val hm get() = clockFormat()


@Composable
fun TripDetailScreen(
    model: KavModel,
    trip: Moovit.Itinerary,
    resolved: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onBack: () -> Unit,
    startInNavigation: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onNavigating: (Boolean) -> Unit = {},
    onHome: () -> Unit = onBack,
) {
    val r = rememberFresher(trip, resolved)
    PlannedPlatforms(trip, r)
    var tracking by remember { mutableStateOf<Pair<Moovit.Leg, Int>?>(null) }
    var navigating by remember(trip) { mutableStateOf(startInNavigation) }
    var planFromNavigation by remember(trip) { mutableStateOf(false) }
    LaunchedEffect(navigating) { onNavigating(navigating) }
    DisposableEffect(Unit) { onDispose { onNavigating(false) } }
    var alert by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun leavePlan() {
        if (planFromNavigation) { planFromNavigation = false; navigating = true } else onBack()
    }
    androidx.activity.compose.BackHandler {
        when {
            alert != null -> alert = null
            navigating -> onHome()
            tracking != null -> tracking = null
            else -> leavePlan()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The timetable route with this ride's number that leaves its boarding stop, matched by the stop's public code.
    val openLine: (Moovit.Leg, Moovit.Resolved) -> Unit = { ride, res ->
        scope.launch {
            val code = res.stop(ride.fromStop)?.code?.toIntOrNull() ?: return@launch
            val number = ride.shortName.ifBlank { res.line(ride.lineId)?.number.orEmpty() }
            val net = model.net ?: withContext(Dispatchers.Default) { uk.noammm.kav.loadNet(ctx) }.also { model.net = it }
            val found = withContext(Dispatchers.Default) {
                val stop = net.code.indexOfFirst { it == code }.takeIf { it >= 0 } ?: return@withContext null
                val route = (net.dStart[stop] until net.dStart[stop + 1]).asSequence()
                    .map { net.tripRoute[net.tripOf(net.cST[net.dConn[it]])] }
                    .firstOrNull { net.rShort[it] == number } ?: return@withContext null
                route to stop
            } ?: return@launch
            model.lineFocusStop = found.second; model.lineRoute = found.first; model.tab = uk.noammm.kav.Tab.Lines
        }
    }
    CompositionLocalProvider(LocalServiceAlertOpener provides { group, label ->
        alert = group to label
    }, LocalLineOpener provides openLine) {
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
                onExit = onHome,
                onPlan = { navigating = false; planFromNavigation = true },
            )
            else -> TripDetailBody(trip, r, fromLabel, toLabel, onBack = ::leavePlan,
                onTrack = { leg, stopId -> tracking = leg to stopId },
                onStart = { navigating = true; planFromNavigation = false; onStart() },
                onReport = { model.report = it },
                pay = { leg, ride -> TripPay(model, leg, ride, r, trip) })
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
    onReport: (ReportFor) -> Unit,
    pay: (@Composable (legIndex: Int, ride: Moovit.Leg) -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember(trip) { mutableStateOf(false) }
    var shareError by remember(trip) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(K.gap3))
            Text(T("Trip", "נסיעה"), style = K.type.title, maxLines = 1, modifier = Modifier.weight(1f))
            ShareButton {
                if (!sharing) scope.launch {
                    sharing = true; shareError = null
                    try { shareTrip(ctx, trip, fromLabel, toLabel) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        shareError = if (trip.wire == null && !KavPlan.owns(trip)) T("Reopen this older route before sharing it.", "פתחו מחדש את המסלול הישן לפני שיתוף.")
                        else T("Couldn't share this trip. Try again.", "לא ניתן לשתף את הנסיעה. נסו שוב.")
                    }
                    finally { sharing = false }
                }
            }
            val taxiOnly = trip.legs.any { it.kind == Moovit.LegKind.TAXI } &&
                trip.legs.none { it.kind == Moovit.LegKind.RIDE }
            if (!taxiOnly) {
                Spacer(Modifier.width(K.gap2))
                StartButton(onStart)
            }
        }

        shareError?.let { Note(it, Modifier.padding(horizontal = K.gap4)) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current)) {
            RouteMap(trip, r, modifier = Modifier.padding(horizontal = K.gap3, vertical = K.gap2))
            Summary(trip, r)
            Spacer(Modifier.height(K.gap3))
            Timeline(trip, r, fromLabel, toLabel, onTrack, onReport, pay)
            Spacer(Modifier.height(K.gap8))
        }
    }
}

@Composable
private fun Summary(trip: Moovit.Itinerary, r: Moovit.Resolved) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap4)
            .glassSurface(K.rCard).padding(K.gap4),
    ) {
        Text(dur((trip.arr - trip.dep).toInt()), style = K.type.largeTitle.tabular)
        // When it arrives, what it costs and the rest of what's true of it, in one line: facts, not buttons.
        val facts = ArrayList<String>()
        facts.add(T("Arrives ${hm.format(Date(trip.arr * 1000))}", "הגעה ב-${hm.format(Date(trip.arr * 1000))}"))
        if (trip.fare >= 0) facts.add("%s%.2f".format(Locale.US, trip.currency, trip.fare / 100.0))
        if (trip.accessible) facts.add(T("Step-free", "נגיש"))
        if (Shown.co2 && trip.co2g >= 0) facts.add(co2(trip.co2g))
        facts.addAll(trip.tags)
        Text(facts.joinToString(" · "), style = K.type.subhead.tabular)
        Spacer(Modifier.height(K.gap3))
        RouteStrip(trip, r)
    }
}

private fun depNote(deps: List<Moovit.Departure>): String? {
    val d = deps.firstOrNull() ?: return null
    val state = when (d.state) {
        Moovit.TimeState.CANCELED -> T("Canceled for this station", "מבוטל לתחנה זו")
        Moovit.TimeState.FREQUENCY -> null
        Moovit.TimeState.OUT_OF_SHAPE -> T("Deviated from route", "סוטה מהמסלול")
        Moovit.TimeState.REAL_TIME -> null
        Moovit.TimeState.REAL_TIME_HIGH -> T("Arrival time is accurate", "זמן ההגעה מדויק")
        Moovit.TimeState.REAL_TIME_MEDIUM -> T("Arrival time is fairly accurate", "זמן ההגעה מדויק למדי")
        Moovit.TimeState.REAL_TIME_LOW -> T("Arrival time may not be accurate", "ייתכן שזמן ההגעה אינו מדויק")
        Moovit.TimeState.REAL_TIME_DROPPED -> T("Real-Time unavailable", "אין מידע בזמן אמת")
        Moovit.TimeState.STATISTICAL -> T("Based on previous arrivals", "מבוסס על הגעות קודמות")
        Moovit.TimeState.STATIC -> T("Scheduled time", "לפי לוח זמנים")
    }
    val alert = when {
        d.alert == 4 -> T("Service alert on this line", "התרעת שירות בקו זה")
        d.alert == 3 -> T("Service change on this line", "שינוי שירות בקו זה")
        d.status == 2 -> T("Running late", "באיחור")
        d.status == 4 -> T("Running ahead of schedule", "מקדים את הלוח")
        else -> null
    }
    return listOfNotNull(state, alert).joinToString(" · ").ifBlank { null }
}

fun co2(g: Int): String = if (g < 1000) T("$g g CO2e", "$g גרם CO2e") else T("%.2f kg CO2e", "%.2f ק\"ג CO2e").format(Locale.US, g / 1000.0)

@Composable
private fun Timeline(
    trip: Moovit.Itinerary,
    r: Moovit.Resolved,
    fromLabel: String,
    toLabel: String,
    onTrack: (Moovit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    pay: (@Composable (legIndex: Int, ride: Moovit.Leg) -> Unit)? = null,
) {
    val shaped = trip.legs.filter { it.shape.size >= 2 && it.kind != Moovit.LegKind.WALK }
    val fares = remember(trip) { rideFares(trip) }
    val tints = routeTints(shaped, r)
    fun tintOf(l: Moovit.Leg) = tints.getOrNull(shaped.indexOf(l)) ?: K.route
    val walk = Seg(K.dim, walk = true)
    val lastRide = trip.legs.lastOrNull { it.kind == Moovit.LegKind.RIDE }
    fun nextFromSameStop(i: Int): Int {
        var j = i + 1
        while (j < trip.legs.size) {
            val l = trip.legs[j]
            when {
                l.kind == Moovit.LegKind.RIDE -> return if (l.fromStop > 0 && l.fromStop == trip.legs[i].toStop) j else -1
                l.kind == Moovit.LegKind.WAIT -> j++
                l.kind == Moovit.LegKind.WALK && (l.pathway || (l.minutes < 1 && l.meters <= 30)) -> j++
                else -> return -1
            }
        }
        return -1
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .panel(K.rCard).padding(vertical = K.gap2),
    ) {
        Rail(Mark.START, null, walk) {
            Endpoint(fromLabel, hm.format(Date(trip.dep * 1000)), T("Leave at", "יציאה בשעה"))
        }
        var boarded = -1
        trip.legs.forEachIndexed { i, l ->
            when (l.kind) {
                Moovit.LegKind.WALK -> {
                    val prev = trip.legs.getOrNull(i - 1)
                    if (prev?.kind != Moovit.LegKind.WALK) {
                        var mins = 0; var metres = 0; var j = i
                        while (j < trip.legs.size && trip.legs[j].kind == Moovit.LegKind.WALK) {
                            mins += trip.legs[j].minutes; metres += trip.legs[j].meters; j++
                        }
                        if (mins >= 1 || metres > 30) Rail(Mark.NONE, walk, walk) {
                            Step(walkLabel(metres, mins), null)
                        }
                    }
                }
                Moovit.LegKind.TAXI -> Rail(Mark.NONE, Seg(K.muted), Seg(K.muted)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        GettPill(l)
                        Text(durMinutes(l.minutes), style = K.type.subhead)
                    }
                }
                Moovit.LegKind.BIKE -> Rail(Mark.NONE, Seg(K.muted), Seg(K.muted)) {
                    Step(cycleLabel(l), null)
                }
                Moovit.LegKind.RIDE -> {
                    val wait = trip.legs.getOrNull(i - 1)?.takeIf { w -> w.kind == Moovit.LegKind.WAIT }
                    val tint = tintOf(l)
                    val ride = Seg(tint)
                    val mode = legMode(l, r)
                    if (boarded != i) {
                        val board = r.stop(l.fromStop)
                        Rail(Mark.STOP, walk, ride, tint) {
                            StopRowDetail(
                                board?.name ?: T("Board here", "עלייה כאן"), board?.code,
                                hm.format(Date(l.dep * 1000)), mode,
                                platform = r.platform(l, wait),
                                stopId = l.fromStop,
                                streetView = (board?.point ?: l.shape.firstOrNull())?.let { lookAt(it, l, r) },
                            )
                            Spacer(Modifier.height(K.gap2))
                            BoardCard(l, wait, r, onTrack, onReport) { RideSummary(l, r, i, fares[i], pay) }
                        }
                    }
                    val alight = r.stop(l.toStop)
                    val n = nextFromSameStop(i)
                    if (n >= 0) {
                        val next = trip.legs[n]
                        val nextWait = trip.legs.getOrNull(n - 1)?.takeIf { w -> w.kind == Moovit.LegKind.WAIT }
                        val arrive = hm.format(Date(l.arr * 1000))
                        val out = r.platform(next, nextWait)
                        // From train to train: whether the next one leaves from the platform this one comes in at.
                        val inAt = if (r.isTrain(l) && r.isTrain(next) && out.isNotBlank()) r.alightPlatform(l) else ""
                        Rail(Mark.STOP, ride, Seg(tintOf(next)), tintOf(next)) {
                            StopRowDetail(
                                alight?.name ?: T("Change here", "החלפה כאן"), alight?.code,
                                hm.format(Date(next.dep * 1000)), legMode(next, r),
                                platform = out,
                                stopId = l.toStop,
                                streetView = alight?.point?.let { lookAt(it, l, r) },
                                note = when {
                                    inAt.isBlank() -> T("Change here · you arrive $arrive", "החלפה כאן · מגיעים ב-$arrive")
                                    inAt == out -> T("Change here · you arrive $arrive · same platform", "החלפה כאן · מגיעים ב-$arrive · באותו רציף")
                                    else -> T("Change here · you arrive $arrive · from platform $inAt to $out",
                                        "החלפה כאן · מגיעים ב-$arrive · מרציף $inAt לרציף $out")
                                },
                            )
                            Spacer(Modifier.height(K.gap2))
                            BoardCard(next, nextWait, r, onTrack, onReport) { RideSummary(next, r, n, fares[n], pay) }
                        }
                        boarded = n
                    } else {
                        // Getting off one train to walk to another: the platform it comes in at.
                        val walkingToTrain = r.isTrain(l) &&
                            trip.legs.drop(i + 1).firstOrNull { it.kind == Moovit.LegKind.RIDE }?.let(r::isTrain) == true
                        Rail(Mark.STOP, ride, walk, tint) {
                            StopRowDetail(
                                alight?.name ?: T("Get off here", "ירידה כאן"), alight?.code,
                                hm.format(Date(l.arr * 1000)), mode,
                                platform = if (walkingToTrain) r.alightPlatform(l) else "",
                                stopId = if (l === lastRide) l.toStop else -1,
                                streetView = alight?.point?.let { lookAt(it, l, r) },
                            )
                        }
                    }
                }
                else -> {}
            }
        }
        Rail(Mark.END, walk, null) {
            // The place itself, as the way there reaches it, when the trip ends with a walk: a bus stop at the end has its own.
            Endpoint(toLabel, hm.format(Date(trip.arr * 1000)), T("Arrive", "הגעה"),
                lookAhead(trip.legs.takeLastWhile { it.kind == Moovit.LegKind.WALK }.flatMap { it.shape }))
        }
    }
}

// Under a ride's line: how many stops and minutes it rides and its own fare (rideFares), and paying for it.
@Composable
private fun RideSummary(
    l: Moovit.Leg, r: Moovit.Resolved, legIndex: Int, fare: Int?,
    pay: (@Composable (legIndex: Int, ride: Moovit.Leg) -> Unit)?,
) {
    Separator(0.dp)
    Box(Modifier.padding(vertical = K.gap1)) {
        RideStops(l, r) {
            Step(rideLabel(l), fare?.let { "%s%.2f".format(Locale.US, l.currency, it / 100.0) })
        }
    }
    pay?.let { Box(Modifier.padding(bottom = K.gap2)) { it(legIndex, l) } }
}

// Each ride's own fare, by leg index. Moovit gives every ride of a stretch one ticket covers the whole ticket's price
// (three rides of 42.00 on a 42.00 trip), so a price goes on the first ride of each such stretch only; and none goes on
// any ride when the prices don't add up to the trip's, as a wrong price is worse than none.
internal fun rideFares(trip: Moovit.Itinerary): Map<Int, Int> {
    val rides = trip.legs.withIndex().filter { it.value.kind == Moovit.LegKind.RIDE && it.value.fare >= 0 }
    if (rides.isEmpty()) return emptyMap()
    if (rides.sumOf { it.value.fare } == trip.fare) return rides.associate { it.index to it.value.fare }
    val stretches = rides.filterIndexed { n, ride -> n == 0 || rides[n - 1].value.fare != ride.value.fare }
    if (trip.fare >= 0 && stretches.sumOf { it.value.fare } != trip.fare) return emptyMap()
    return stretches.associate { it.index to it.value.fare }
}

private fun walkLabel(metres: Int, mins: Int): String {
    val d = if (metres > 0) distanceLabel(metres.toDouble()) else null
    val m = if (mins >= 1) durMinutes(mins) else null
    val walk = T("Walk", "הליכה")
    return listOfNotNull(walk, d, m).let {
        if (it.size == 3) "$walk ${it[1]} · ${it[2]}" else it.joinToString(" ")
    }
}

// The ride's step opens to every stop it passes on the way; names the trip doesn't have yet come from Moovit.
@Composable
private fun RideStops(l: Moovit.Leg, r: Moovit.Resolved, label: @Composable () -> Unit) {
    var open by remember(l) { mutableStateOf(false) }
    val between = l.stops.drop(1).dropLast(1)
    val asked = rememberStopNames(if (open) between.filter { r.stopName(it) == null } else emptyList())
    Column(Modifier.fillMaxWidth().animateContentSize()
        .then(if (between.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) { open = !open })) {
        label()
        if (open) Column(Modifier.padding(start = 23.dp, top = K.gap2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            between.forEach { id ->
                Text(r.stopName(id) ?: asked[id]?.name ?: "…", fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun rideLabel(l: Moovit.Leg): String {
    val n = (l.stops.size - 1).coerceAtLeast(0)
    val stops = if (n == 1) T("1 stop", "תחנה 1") else T("$n stops", "$n תחנות")
    return if (n > 0) T("Ride $stops · ", "נסיעה $stops · ") + durMinutes(l.minutes)
        else T("Ride ", "נסיעה ") + durMinutes(l.minutes)
}

private class Seg(val tint: Color, val walk: Boolean = false)

private enum class Mark { START, END, STOP, NONE }

@Composable
private fun Rail(
    mark: Mark,
    above: Seg?,
    below: Seg?,
    tint: Color = K.text,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(44.dp).fillMaxHeight()) {
            val x = size.width * .5f
            val nodeY = 21.dp.toPx().coerceAtMost(size.height * .5f)
            val ring = 7.dp.toPx()
            fun stretch(s: Seg?, from: Float, to: Float) {
                if (s == null || to <= from) return
                if (!s.walk) {
                    drawLine(s.tint, Offset(x, from), Offset(x, to), 5.dp.toPx(), StrokeCap.Butt)
                    return
                }
                val step = 6.dp.toPx()
                var y = from + step / 2
                while (y < to) { drawCircle(s.tint, 1.7.dp.toPx(), Offset(x, y)); y += step }
            }
            val gap = if (mark == Mark.NONE) 0f else ring
            stretch(above, 0f, nodeY - gap)
            stretch(below, nodeY + gap, size.height)
            val at = Offset(x, nodeY)
            when (mark) {
                Mark.START -> drawCircle(K.text, ring - 1.5.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
                Mark.END -> drawCircle(K.text, ring - 1.dp.toPx(), at)
                Mark.STOP -> drawCircle(tint, ring - 1.5.dp.toPx(), at, style = Stroke(3.dp.toPx()))
                Mark.NONE -> {}
            }
        }
        Column(
            Modifier.weight(1f).padding(end = K.gap4, top = K.gap2, bottom = K.gap2),
            content = content,
        )
    }
}

@Composable
private fun Endpoint(label: String, time: String, when_: String, streetView: StreetLook? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = K.type.body, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (streetView != null) {
            Spacer(Modifier.width(K.gap2))
            StreetViewButton(streetView, label)
        }
        Spacer(Modifier.width(K.gap2))
        TimeColumn(time, when_)
    }
}

internal fun legMode(ride: Moovit.Leg, r: Moovit.Resolved): Mode =
    modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1))

@Composable
private fun StopRowDetail(
    name: String,
    code: String?,
    time: String,
    mode: Mode? = null,
    platform: String = "",
    stopId: Int = -1,
    note: String? = null,
    // Where the stop is, for a look at it in Street View: one that's hidden or has no sign is easier to find.
    streetView: StreetLook? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(name, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!code.isNullOrBlank()) Text(T("Stop $code", "תחנה $code"), style = K.type.subhead)
            if (note != null) Text(note, style = K.type.subhead)
            if (platform.isNotBlank()) {
                Spacer(Modifier.height(K.gap1))
                PlatformTag(platform)
            }
        }
        if (streetView != null) {
            Spacer(Modifier.width(K.gap2))
            StreetViewButton(streetView, name)
        }
        Spacer(Modifier.width(K.gap2))
        TimeColumn(time)
    }
}

@Composable
private fun Step(label: String, trailing: String?, glyph: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { glyph(); Spacer(Modifier.width(K.gap2)) }
        Text(label, style = K.type.subhead, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = K.type.subhead.tabular)
    }
}

@Composable
private fun BoardCard(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    r: Moovit.Resolved,
    onTrack: (Moovit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    summary: @Composable ColumnScope.() -> Unit = {},
) {
    val options = Moovit.boardingOptions(ride, wait)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(horizontal = K.gap3, vertical = K.gap1)) {
        if (options.size > 1) Text(T("Take one of these lines", "בחרו אחד מהקווים האלה"), fontSize = 14.sp, color = K.dim)
        options.forEachIndexed { index, (option, boarding) ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = K.gap2).height(1.dp).background(K.border))
            BoardOption(option, boarding, r, onTrack, onReport, showPlatform = options.size > 1)
        }
        summary()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardOption(
    ride: Moovit.Leg,
    wait: Moovit.Leg?,
    r: Moovit.Resolved,
    onTrack: (Moovit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    showPlatform: Boolean = false,
) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val plate = linePlate(rt, agency) { Operators.of(r, agency) }
    val now = System.currentTimeMillis() / 1000
    Column(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modeOf(rt)!=Mode.BUS || K.lineBadgeAppearance== LineBadgeAppearance.KAV){
                Row(
                    Modifier.clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.surface2)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…",
                        style = K.type.bodyStrong, color = plate?.ink ?: K.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
            else {
                LinePlateBadge(info?.number, mode=modeOf(rt), plate=plate)
            }
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "לכיוון ${shownName(it)}") } ?: "",
                    fontSize = 14.sp, color = K.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                r.agencyName(agency)?.let {
                    Text(it, fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showPlatform) {
                val platform = r.platform(ride, wait)
                if (platform.isNotBlank()) {
                    Spacer(Modifier.width(K.gap2))
                    PlatformTag(platform)
                }
            }
            // The flag beside the line it reports; a ride that's still ahead is reported as of now.
            rideReport(ride, r, null, minOf(ride.dep, now))?.let { report ->
                Spacer(Modifier.width(K.gap2))
                ReportButton { onReport(report) }
            }
        }
        val deps = r.departures(ride, wait).filter { it.timeUtc >= now - 60 }.take(3)
        if (deps.isNotEmpty()) {
            Spacer(Modifier.height(K.gap2))
            Text(T("Departures", "יציאות"), fontSize = 14.sp, color = K.dim)
            Spacer(Modifier.height(K.gap1))
            DepartureTimes(deps, now)
            depNote(deps)?.let {
                Text(it, fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1))
            }
        }
        wait?.let { AlertRow(it.alertCategory, it.alertText, r.line(ride.lineId)?.groupId ?: 0) }
        val bus = r.arrival(ride)?.takeIf { it.hasLocation }
        Spacer(Modifier.height(K.gap2))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            LiveLocationButton(bus != null, bus?.let { freshColour(freshnessOf(it.sampleUtc, now)) } ?: K.realtime, compact = true) {
                onTrack(ride, ride.fromStop)
            }
            LocalLineOpener.current?.let { open -> CardButton(T("All departures", "כל היציאות")) { open(ride, r) } }
        }
    }
}

// A small button inside a card: the secondary look at the size of the card's own lines, 40 high (48 to the touch).
@Composable
private fun CardButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp).clip(K.capsule).background(K.fill)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = K.gap4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = K.type.subhead, color = K.text, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// Every time down the timeline in one column: centred, the same width, with what it means (Leave at, Arrive) under it.
@Composable
private fun TimeColumn(time: String, label: String? = null) {
    Column(Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time, style = K.type.subhead.tabular, color = K.text)
        label?.let { Text(it, style = K.type.caption, color = K.textTertiary) }
    }
}
