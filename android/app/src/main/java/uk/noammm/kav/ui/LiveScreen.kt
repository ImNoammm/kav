package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.shownName
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.trackLocation
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import uk.noammm.kav.data.Net
import androidx.compose.animation.animateContentSize

private const val LOOK_REACH_KM = 3.5

// What the Live tab showed last, so opening it again shows the buses at once while they refresh.
private object LiveMemory {
    // Every bus around, as last asked.
    var area: KavLive.Area? = null
    var areaAt = 0L
    var areaCentre = 0.0 to 0.0

    fun recentArea(here: Pair<Double, Double>) = area?.takeIf {
        System.currentTimeMillis() / 1000 - areaAt < 180 && metres(here.first, here.second, areaCentre.first, areaCentre.second) < 500
    }
}

private fun busCount(n: Int) =
    if (n == 0) T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב")
    else T.count(n, "1 live vehicle", "כלי רכב אחד בזמן אמת", "live vehicles", "כלי רכב בזמן אמת")

private sealed interface LiveFocus {
    data class Vehicle(val tripId: Long, val from: Int? = null) : LiveFocus
    data class Stop(val id: Int) : LiveFocus
}

// A vehicle's name on its plate: its line's number. A train's line has none: it goes by its run number, which the
// ministry names its vehicle by (route_first_641), as the trip pages show it. Else "…" while its line is looked up, or
// its line's id.
internal fun plateNumber(number: String?, vehicleId: String, routeType: Int, pending: Boolean, lineId: Int): String =
    number?.ifBlank { null }
        ?: vehicleId.substringAfterLast('_', "").takeIf { routeType == 2 && it.isNotEmpty() && it.all(Char::isDigit) }
        ?: if (pending) "…" else "#$lineId"

private class Tracked(
    val arrival: Transit.Arrival,
    val stop: Transit.Stop?,
    val line: Transit.LineInfo?,
    val looked: Boolean,
    val routeType: Int,
    // The line as Kav's server has it, from the ministry's own data.
    val kav: KavLive.Line? = null,
) {
    val tripId get() = arrival.tripId
    val number get() = plateNumber(line?.number, arrival.vehicleId, routeType, pending, arrival.lineId)
    val pending get() = line == null && !looked
    val eta get() = arrival.rtUtc.takeIf { it > 0 } ?: arrival.staticUtc

    // A bus's operator colour, by the ministry's id from Kav's server.
    fun operator(): Color? = busOperator(routeType, line?.agencyId ?: -1) { kav?.agency?.let(Operators::ofGtfs) }
}

@Composable
fun LiveScreen(model: KavModel) {
    val ctx = LocalContext.current
    val here = model.here ?: (32.0759 to 34.7745)
    val located = model.here != null
    var locGranted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locGranted = true
            requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        if (!locGranted && model.here == null) ask.launch(LOCATION_PERMISSIONS)
    }
    // Where you are at once, from what the phone already knows, then every fix while the map is on screen: one fix
    // every two minutes left you where you had been as you walked or rode. A trip under way follows you already.
    val tripTracks = model.activeJourney != null
    LaunchedEffect(locGranted, lifecycle, tripTracks) {
        if (!locGranted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val first = requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            val follow = if (tripTracks) ({}) else trackLocation(
                ctx, onFix = { model.locate(it.latitude, it.longitude) }, onRough = { model.locate(it.latitude, it.longitude) },
            )
            try { kotlinx.coroutines.awaitCancellation() } finally { first(); follow() }
        }
    }
    var look by remember { mutableStateOf<Pair<Pair<Double, Double>, Double>?>(null) }
    var zoomedOut by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    val wake = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    var area by remember { mutableStateOf(LiveMemory.recentArea(here)) }
    var board by remember { mutableStateOf<Pair<Int, KavLive.Board>?>(null) }
    var status by remember {
        mutableStateOf(when {
            area != null -> busCount(area!!.buses.size)
            else -> T("Connecting…", "מתחברים…")
        })
    }
    var loading by remember { mutableStateOf(area == null) }
    // The last round couldn't reach the server: no vehicles then means none known, not none there.
    var unreached by remember { mutableStateOf(false) }
    var near by remember { mutableStateOf(emptyList<Transit.Stop>()) }
    var focus by remember { mutableStateOf<LiveFocus?>(null) }
    var picked by remember { mutableStateOf<Tracked?>(null) }
    var farStop by remember { mutableStateOf<Transit.Stop?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(1000); now = System.currentTimeMillis() / 1000 }
        }
    }

    // Where the stops around were last looked up. A trip's tracking moves `here` every second; they're looked up again
    // once you're 300 m away.
    var stopsAt by remember { mutableStateOf(here) }
    LaunchedEffect(here) { if (metres(here.first, here.second, stopsAt.first, stopsAt.second) > 300) stopsAt = here }

    // The stops around come from the timetable, by their place in it: nothing to look up anywhere.
    LaunchedEffect(look?.first ?: stopsAt, look?.second) {
        val net = model.net ?: runCatching { loadNet(ctx) }.getOrNull()?.also { model.net = it }
            ?: return@LaunchedEffect
        val (at, reach) = look ?: (stopsAt to 1.5)
        val inView = withContext(Dispatchers.Default) {
            net.nearestStops(at.first, at.second, k = 120, radius = reach.coerceIn(0.6, LOOK_REACH_KM) * 1000)
                .map { it.first }
        }
        near = inView.map { g -> Transit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
    }

    // Every bus in the box around where the map looks, asked again when the server has a newer snapshot, or at once
    // when the rider refreshes.
    LaunchedEffect(lifecycle, look?.first ?: stopsAt, look?.second) {
        val (at, reach) = look ?: (stopsAt to 1.5)
        val box = KavLive.box(at.first, at.second, reach.coerceIn(0.6, LOOK_REACH_KM))
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val wait = try {
                    val answer = withContext(Dispatchers.IO) { KavLive.area(box) }
                    area = answer; loading = false; unreached = false
                    if (look == null) {
                        LiveMemory.area = answer; LiveMemory.areaCentre = stopsAt
                        LiveMemory.areaAt = System.currentTimeMillis() / 1000
                    }
                    answer.next - System.currentTimeMillis() / 1000
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loading = false; unreached = true
                    status = T("Could not refresh · retrying shortly", "לא ניתן היה לרענן · ננסה שוב בקרוב")
                    15L
                }
                kotlinx.coroutines.withTimeoutOrNull(wait.coerceIn(5, 30) * 1000L) { wake.receive() }
            }
        }
    }
    // The stops in view are asked about every round. The ministry answers for them live, a minute
    // or so fresher than its snapshot of every bus, which fills in the rest.
    var stopBuses by remember { mutableStateOf<Map<String, KavLive.Bus>>(emptyMap()) }
    var stopLines by remember { mutableStateOf<Map<Int, KavLive.Line>>(emptyMap()) }
    LaunchedEffect(lifecycle, near) {
        val net = model.net ?: return@LaunchedEffect
        val codes = near.mapNotNull { s -> net.code.getOrNull(-s.id - 1)?.takeIf { it > 0 } }.distinct()
        if (codes.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val boards = withContext(Dispatchers.IO) {
                    codes.chunked(KavLive.STOPS_MAX).map { part -> async { runCatching { KavLive.stops(part) }.getOrNull() } }
                        .awaitAll().filterNotNull()
                }
                if (boards.isNotEmpty()) {
                    stopBuses = boards.flatMap { it.due }.mapNotNull { d -> d.spot?.let { KavLive.Bus(d.key, d.route, it) } }
                        .groupBy { it.key }.mapValues { (_, same) -> same.maxBy { it.spot.at } }
                    stopLines = boards.fold(emptyMap()) { all, b -> all + b.lines }
                }
                delay(15_000)
            }
        }
    }
    // The arrivals at the stop that's open, from the ministry.
    val boardStop = (focus as? LiveFocus.Stop)?.id?.takeIf { it < 0 }
    if (boardStop != null) LaunchedEffect(boardStop, lifecycle) {
        val net = model.net ?: return@LaunchedEffect
        val code = net.code.getOrElse(-boardStop - 1) { 0 }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { withContext(Dispatchers.IO) { KavLive.stops(listOf(code)) } }
                    .onSuccess { board = boardStop to it }
                delay(15_000)
            }
        }
    }

    // The bus being followed is asked about on its own, straight from the ministry, fresher than the rest.
    val followedRoute = (focus as? LiveFocus.Vehicle)?.let { f ->
        area?.buses?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
            ?: board?.second?.due?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
    }
    val followed = rememberKavSpots(listOfNotNull(followedRoute))

    val stopsById = remember(near, farStop) { (near + listOfNotNull(farStop)).associateBy { it.id } }
    val vehicles = remember(area, stopBuses, stopLines, followed) {
        // Each bus where it was seen last, from the snapshot or the stops in view. Nearest first: there's no stop of
        // yours to time them at.
        val known = area?.lines.orEmpty() + stopLines
        (area?.buses.orEmpty() + stopBuses.values).groupBy { it.key }.values.map { same -> same.maxBy { it.spot.at } }
            .map { bus ->
                val line = known[bus.route]
                Tracked(KavLive.fresher(KavLive.busArrival(bus), followed), null, KavLive.lineInfo(line, ""), true, line?.type ?: 3, line)
            }.sortedBy { metres(stopsAt.first, stopsAt.second, it.arrival.lat, it.arrival.lon) }
    }
    LaunchedEffect(vehicles.size, area != null || stopBuses.isNotEmpty()) {
        if (area != null || stopBuses.isNotEmpty()) status = busCount(vehicles.size)
    }

    androidx.activity.compose.BackHandler(focus != null) {
        focus = when (val f = focus) {
            is LiveFocus.Vehicle -> f.from?.let { LiveFocus.Stop(it) }
            else -> null
        }
    }
    val focusStop = (focus as? LiveFocus.Stop)?.let { f -> stopsById[f.id] ?: farStop?.takeIf { it.id == f.id } }
    LaunchedEffect(focus) { if (focus == null) farStop = null }
    val focusVehicle = (focus as? LiveFocus.Vehicle)?.let { f ->
        vehicles.firstOrNull { it.tripId == f.tripId } ?: picked?.takeIf { it.tripId == f.tripId }
    }
    // A bus's stops are those of its trip in Kav's own timetable, looked up again as soon as what it takes is in: the
    // timetable, and the bus's line from the snapshot, the stops in view or the stop that's open.
    val tripKey = focusVehicle?.arrival?.vehicleId.orEmpty()
    val tripLine = KavLive.routeOf(tripKey)?.let { route ->
        area?.lines?.get(route) ?: stopLines[route] ?: board?.second?.lines?.get(route)
    }
    val tripNet = model.net ?: uk.noammm.kav.Loaded.net
    val lineStops by produceState(emptyList<Transit.Stop>(), tripKey, tripLine != null, tripNet != null) {
        value = emptyList()
        val a = focusVehicle?.arrival ?: return@produceState
        val first = KavLive.firstOf(tripKey) ?: return@produceState
        val net = tripNet ?: return@produceState
        val line = tripLine ?: return@produceState
        value = withContext(Dispatchers.Default) {
            KavLive.tripStops(net, line.number, line.name, first, a.lat, a.lon, line.type)
                .map { g -> Transit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
        }
    }
    // The bus's stops for a report: from Kav's timetable, with the codes on their signs.
    val reportStops = remember(lineStops, tripNet) {
        lineStops.map { s ->
            ReportStop(s.id, s.name, if (s.id < 0) tripNet?.code?.getOrNull(-s.id - 1)?.takeIf { it > 0 }?.toString() else null)
        }
    }
    // The road comes from the ministry's timetable, by the bus's route.
    val route = rememberKavShape(focusVehicle?.arrival?.vehicleId?.let(KavLive::routeOf) ?: -1)
    // How far along its stops the focused bus is, for the rail on its card.
    val focusBus = focusVehicle?.arrival?.takeIf { it.hasLocation }
    val progress = remember(lineStops, route, focusBus?.lat, focusBus?.lon) { busProgress(lineStops, route, focusBus) }
    val liveLiquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0.dp) }
    Column(Modifier.fillMaxSize()) {
        // Where the live data comes from, under an info button instead of on every visit.
        var about by rememberSaveable { mutableStateOf(false) }
        ScreenHeader(T("Live", "בזמן אמת")) {
            KavIconButton(Icons.Rounded.Info, T("Where live data comes from", "מאיפה המידע בזמן אמת"), { about = !about },
                floating = true, tint = if (about) K.accentInk else K.textSecondary)
        }
        androidx.compose.animation.AnimatedVisibility(
            about,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
        Row(
            Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap3).fillMaxWidth()
                .clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
        LiveGlyph(K.realtime, 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Text(
            T(
                "Buses and times come straight from the Ministry of Transport through Kav's server, about every 15s. " +
                    "A vehicle shows here once one of the stops around you has a bus reporting its position.",
                "האוטובוסים והזמנים מגיעים ישירות ממשרד התחבורה דרך השרת של Kav, בערך כל 15 שניות. " +
                    "כלי רכב מופיע כאן ברגע שאחת התחנות סביבכם מקבלת דיווח מיקום מאוטובוס.",
            ),
            style = K.type.subhead,
        )
        FreshnessKey()
        }
        }
        }
        // What the map shows: words, one control, in reach above the map.
        val shows = listOf(LiveShow.ALL, LiveShow.TRAFFIC, LiveShow.STOPS)
        KavSegmented(
            listOf(T("All", "הכול"), T("Vehicles", "כלי רכב"), T("Stops", "תחנות")),
            selected = shows.indexOf(model.liveShow),
            onSelect = { model.liveShow = shows[it] },
            modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap3),
        )
        if (!located) Row(
            Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KavButton(T("Use my location", "השתמשו במיקום שלי"), {
                if (hasLocationPermission(ctx)) { locGranted = true; requestLocationOnce(ctx) { model.locate(it.first, it.second) } }
                else ask.launch(LOCATION_PERMISSIONS)
            }, style = ButtonStyle.Secondary, icon = Icons.Rounded.MyLocation)
            Text(T("Showing central Tel Aviv", "מוצג מרכז תל אביב"), style = K.type.caption)
        }
        Box(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth().weight(1f).panel(K.rCard),
        ) {
            CompositionLocalProvider(LocalLiquidBackdrop provides liveLiquid) {
                LiveMap(
                    here, near, vehicles, focus, focusStop, focusVehicle, lineStops, route, model.liveShow,
                    following = !moved,
                    onLook = { centre, reach ->
                        moved = centre != null
                        zoomedOut = centre != null && reach > LOOK_REACH_KM
                        val was = look
                        when {
                            centre == null -> look = null
                            zoomedOut -> Unit
                            // A nudge isn't somewhere new: the stops are looked up again past 150 m or a real zoom.
                            was == null || metres(was.first.first, was.first.second, centre.first, centre.second) > 150 ||
                                abs(reach - was.second) > was.second * .25 -> look = centre to reach
                        }
                    },
                    bottomPadding = if (focus != null) cardHeight + K.gap2 else 0.dp,
                    modifier = Modifier.fillMaxSize().glassBackdrop(liveLiquid),
                    onVehicle = { focus = LiveFocus.Vehicle(it.tripId) },
                    onStop = { s -> if (s.id !in stopsById) farStop = s; focus = LiveFocus.Stop(s.id) },
                    status = {
                        Column(Modifier.panel(K.rTile).padding(6.dp)) {
                            // One message fades into the next as the chip eases to its size; a count in the same one rolls.
                            val said = when {
                                zoomedOut && model.liveShow == LiveShow.TRAFFIC ->
                                    1 to T("Zoom in to see the vehicles here", "התקרבו כדי לראות את כלי הרכב כאן")
                                zoomedOut -> 2 to T("Zoom in to load stops and vehicles here", "התקרבו כדי לטעון כאן תחנות וכלי רכב")
                                else -> 3 to status
                            }
                            androidx.compose.animation.AnimatedContent(
                                said, contentKey = { it.first },
                                transitionSpec = {
                                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)
                                        })
                                },
                                label = "liveStatus",
                            ) { (_, text) -> LiveText(text, style = K.type.caption.tabular) }
                        }
                    },
                    controls = {},
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = focus != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.spring(
                            dampingRatio = .9f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                    ) { it } + fadeIn(tween(140)),
                    exit = androidx.compose.animation.slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
                ) {
                    var shown by remember { mutableStateOf(focus) }
                    if (focus != null) shown = focus
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(start = K.gap2, end = K.gap2, bottom = K.gap2)
                            .onSizeChanged { cardHeight = with(density) { it.height.toDp() } }
                            .glassSurface(K.rCard),
                    ) {
                        // Another stop, or a line picked on the card: the card fades to it and springs to its size;
                        // what loads into a card after that grows into it the same way.
                        AnimatedContent(
                            shown,
                            transitionSpec = {
                                (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                            },
                            contentKey = { f ->
                                when (f) {
                                    is LiveFocus.Stop -> "stop ${f.id}"
                                    is LiveFocus.Vehicle -> "vehicle ${f.tripId}"
                                    else -> "none"
                                }
                            },
                            label = "liveCard",
                        ) { f ->
                            Box(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                                when (f) {
                                    is LiveFocus.Stop -> {
                                        // The ministry's arrivals at the stop, in the shape the card takes.
                                        val b = board?.takeIf { it.first == f.id }?.second
                                        val due = remember(b) {
                                            b?.due.orEmpty().associate { d -> Transit.ArrivalKey(f.id, KavLive.tripIdOf(d.key)) to KavLive.dueArrival(d, f.id) }
                                        }
                                        val dueLines = remember(b) {
                                            b?.due.orEmpty().associate { d -> -d.route to KavLive.lineInfo(b?.lines?.get(d.route), d.number) }
                                        }
                                        val kinds = remember(b) {
                                            (b?.lines?.values.orEmpty().map { it.type } + 3).associateBy { KavLive.agencyOf(it) }
                                        }
                                        LiveStopCard(
                                            stopsById[f.id], f.id, KavLive.fresher(due, followed), dueLines, kinds, now,
                                            onVehicle = { v -> picked = v; focus = LiveFocus.Vehicle(v.tripId, from = f.id) },
                                            onClose = { focus = null },
                                        )
                                    }
                                    is LiveFocus.Vehicle -> LiveVehicleCard(
                                        focusVehicle?.takeIf { it.tripId == f.tripId }, now, lineStops, progress, reportStops,
                                        onReport = { model.report = it },
                                        // The positions asked again now rather than at the next round.
                                        onRefresh = { wake.trySend(Unit) },
                                    ) { focus = f.from?.let { LiveFocus.Stop(it) } }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        val below = when {
            focus != null -> 0
            loading -> 1
            vehicles.isEmpty() -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = below,
            transitionSpec = {
                (fadeIn(tween(200, delayMillis = 90)) togetherWith fadeOut(tween(140)))
                    .using(SizeTransform { _, _ -> tween(300, easing = FastOutSlowInEasing) })
            },
            label = "live-below",
        ) { state ->
            when (state) {
                0 -> Spacer(Modifier.fillMaxWidth().height(LocalBottomBarInset.current + K.gap2))
                // One height whatever is below, so the map above never resizes with the list.
                1 -> Box(
                    Modifier.fillMaxWidth().height(260.dp + LocalBottomBarInset.current)
                        .padding(bottom = LocalBottomBarInset.current),
                    contentAlignment = Alignment.Center,
                ) { LoadingPulse(T("Finding vehicles", "מאתרים כלי רכב")) }
                else -> LiveList(vehicles, now, stopsAt, unreached, Modifier.fillMaxWidth()
                    .height(260.dp + LocalBottomBarInset.current).padding(K.gap2)) {
                    focus = LiveFocus.Vehicle(it.tripId)
                }
            }
        }
    }
}

private class LineLook(
    val tripId: Long,
    val tint: Color,
    val ahead: List<Pair<Double, Double>>,
    val driven: List<Pair<Double, Double>>,
    val stops: List<Pair<Pair<Double, Double>, Boolean>>,
    val from: Pair<Double, Double>?,
)

private fun lineLook(
    tripId: Long, route: List<Pair<Double, Double>>, stops: List<Transit.Stop>, tint: Color,
    bus: Transit.Arrival?, from: Transit.Stop?,
): LineLook {
    val shaped = route.size >= 2
    val (driven, ahead) = if (bus != null && shaped) splitPath(route, bus.lat, bus.lon)
        else emptyList<Pair<Double, Double>>() to route
    val busAlong = if (bus != null && shaped) alongPath(bus.lat, bus.lon, route) else -1.0
    val placed = stops.map { st ->
        if (!shaped) return@map (st.lat to st.lon) to false
        val along = alongPath(st.lat, st.lon, route)
        (pointAlong(route, along) ?: (st.lat to st.lon)) to (busAlong >= 0 && along < busAlong)
    }
    return LineLook(tripId, tint, ahead, driven, placed, from?.let { onRoute(it.lat to it.lon, route) })
}

@Composable
private fun LiveMap(
    center: Pair<Double, Double>,
    stops: List<Transit.Stop>,
    vehicles: List<Tracked>,
    focus: LiveFocus?,
    focusStop: Transit.Stop?,
    focusVehicle: Tracked?,
    lineStops: List<Transit.Stop>,
    route: List<Pair<Double, Double>>,
    show: LiveShow,
    following: Boolean,
    onLook: (Pair<Double, Double>?, Double) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier,
    onVehicle: (Tracked) -> Unit,
    onStop: (Transit.Stop) -> Unit,
    status: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    val stopReach = with(LocalDensity.current) { 20.dp.toPx() }
    val from = (focus as? LiveFocus.Vehicle)?.from?.let { id ->
        stops.firstOrNull { it.id == id } ?: lineStops.firstOrNull { it.id == id }
    }
    val spot = (center.first * 300).roundToInt() to (center.second * 300).roundToInt()
    // Once moved by hand the map keeps its key, so nothing reframes it until Reset.
    val pinned = remember { arrayOf(spot) }
    if (following) pinned[0] = spot
    val where = pinned[0]
    val points = remember(focus, route.isNotEmpty(), lineStops.isNotEmpty(), stops.isEmpty(), following, where, show) {
        when {
            focusVehicle != null -> listOfNotNull(focusVehicle.arrival.takeIf { it.hasLocation }?.let { it.lat to it.lon }) +
                listOfNotNull(from?.let { it.lat to it.lon }) + route.ifEmpty { lineStops.map { it.lat to it.lon } }
            focusStop != null && show == LiveShow.STOPS -> listOf(focusStop.lat to focusStop.lon)
            focusStop != null -> listOf(focusStop.lat to focusStop.lon) + vehicles
                .filter { it.arrival.stopId == focusStop.id && it.arrival.hasLocation }
                .map { it.arrival.lat to it.arrival.lon }
            following -> stops.map { it.lat to it.lon } + center
            else -> stops.map { it.lat to it.lon }
        }
    }
    val chosen = focusVehicle?.tripId
    // Stops only still draws a vehicle picked from the list.
    val shown = if (show == LiveShow.STOPS) vehicles.filter { it.tripId == chosen } else vehicles
    val tint = focusVehicle?.let { v ->
        plateFor(v.routeType, v.line?.agencyId ?: -1)?.fill ?: v.operator()?.takeIf(::hued)?.let { onScreen(it) } ?: K.route
    }
    val bus = focusVehicle?.arrival?.takeIf { it.hasLocation }
    val current = remember(chosen, route, lineStops, tint, bus, from) {
        if (chosen == null || tint == null) null else lineLook(chosen, route, lineStops, tint, bus, from)
    }
    var last by remember { mutableStateOf<LineLook?>(null) }
    SideEffect { if (current != null) last = current }
    val look = current ?: last
    val lineShown = animateFloatAsState(if (chosen != null) 1f else 0f, tween(320), label = "lineView")
    val step by remember { derivedStateOf { (lineShown.value * 10).roundToInt() / 10f } }
    val geometry = remember(stops, center, K.light, look, step, focusStop, show) {
        val lines = ArrayList<MapLine>()
        val dots = ArrayList<MapDot>()
        val near = 1f - step
        if (near > 0f && show != LiveShow.TRAFFIC) stops.forEach { st ->
            dots += MapDot(st.lat, st.lon, K.bg.copy(alpha = near), 6f)
            dots += MapDot(st.lat, st.lon, Color.Transparent, 4.2f, K.muted.copy(alpha = near), 1.6f)
        }
        if (look != null && step > 0f) {
            val tint = look.tint.copy(alpha = step)
            val idle = K.routeIdle.copy(alpha = step)
            look.ahead.takeIf { it.size >= 2 }?.let { lines += MapLine(it, tint, 5f, casing = 9f) }
            look.driven.takeIf { it.size >= 2 }?.let { lines += MapLine(it, idle, 5f, casing = 9f) }
            look.stops.forEach { (at, passed) ->
                dots += MapDot(at.first, at.second, K.bg.copy(alpha = step), 6f)
                dots += MapDot(at.first, at.second, Color.Transparent, 3.5f, if (passed) idle else tint, 2f)
            }
            look.from?.let { f ->
                dots += boardingDots(f, look.tint).map {
                    it.copy(fill = it.fill.copy(alpha = it.fill.alpha * step), stroke = it.stroke.copy(alpha = it.stroke.alpha * step))
                }
            }
        }
        // The rider's own place, the same dots as it moves.
        dots += MapDot(center.first, center.second, K.text.copy(alpha = 0.18f), 13f, id = "here-halo")
        dots += MapDot(center.first, center.second, K.bg, 6f, id = "here-ring")
        dots += MapDot(center.first, center.second, K.text, 4f, id = "here-dot")
        focusStop?.let {
            dots += MapDot(it.lat, it.lon, K.bg, 10f)
            dots += MapDot(it.lat, it.lon, Color.Transparent, 6.5f, K.accent, 2.6f)
        }
        MapGeometry(lines = lines, dots = dots)
    }
    TileMap(
        points, modifier, focusKey = focus ?: where,
        recenterOn = center,
        contentPadding = PaddingValues(bottom = bottomPadding),
        geometry = geometry,
        onLook = onLook,
        moved = !following,
        live = vehicleGeometry(shown.map { it.arrival to modeOf(it.routeType) }, LocalDensity.current, alpha = { a ->
            if (a.tripId == (chosen ?: look?.tripId)) 1f else 1f - step
        }),
        onTap = { at, proj ->
            val tappable = if (chosen == null) shown else shown.filter { it.tripId == chosen }
            val vehicle = tappable.map { it to (proj.point(it.arrival.lat, it.arrival.lon) - at).getDistance() }
                .filter { it.second <= reach }.minByOrNull { it.second }?.first
            if (vehicle != null) onVehicle(vehicle)
            else (if (chosen != null) lineStops else if (show == LiveShow.TRAFFIC) emptyList() else stops)
                .map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
                .filter { it.second <= stopReach }.minByOrNull { it.second }?.first
                ?.let(onStop)
        },
        keepZoom = show == LiveShow.STOPS && focusStop != null,
        status = status,
        controls = controls,
    )
}

enum class LiveShow { STOPS, TRAFFIC, ALL }

@Composable
private fun LinePlate(v: Tracked) {
    val agency = v.line?.agencyId ?: -1
    val plate = plateFor(v.routeType, agency) ?: operatorPlate(v.operator())
    Row(
        Modifier.clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.plate)
            .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(K.rBadge))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgencyMark(v.routeType, agency, plate?.ink ?: K.muted, 15.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            v.number, fontSize = 16.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

private fun stopName(name: String?) = name?.ifBlank { null }?.let(::shownName) ?: "…"

@Composable
private fun LiveList(
    vehicles: List<Tracked>, now: Long, from: Pair<Double, Double>, unreached: Boolean, modifier: Modifier, onSelect: (Tracked) -> Unit,
) {
    if (vehicles.isEmpty()) {
        // Nothing near: a quiet line in the middle of the space, the why behind the (i) at the top.
        Column(
            modifier.fillMaxWidth().padding(K.gap6),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            LiveGlyph(K.textTertiary, 28.dp)
            Text(
                if (unreached) T("Live locations can't be reached right now", "אין כרגע גישה למיקומים בזמן אמת")
                else T("No vehicles nearby right now", "אין כרגע כלי רכב בסביבה"),
                style = K.type.body, color = K.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }
    // The freshest positions first: green, then yellow, then red, each kept in its own order.
    val sorted = vehicles.sortedBy { freshnessOf(it.arrival.sampleUtc, now).ordinal }
    // The list keeps its place as vehicles change order. Kept by vehicle, it would follow the first one down the list
    // as its position aged, and scroll on by itself.
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val order = sorted.map { it.tripId }
    val shownOrder = remember { arrayOfNulls<List<Long>>(1) }
    SideEffect {
        if (shownOrder[0] != order && !state.isScrollInProgress) {
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
        shownOrder[0] = order
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(
        top = K.gap1, bottom = K.gap4 + LocalBottomBarInset.current,
    )) {
        itemsIndexed(sorted, key = { _, it -> it.tripId }) { i, v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            val tint = if (a.vehicleStatus == 2) K.problem else freshColour(freshnessOf(a.sampleUtc, now))
            // A vehicle that comes into the list fades in, one that leaves fades out, and the rest glide to their places.
            Row(
                Modifier.animateItem(
                    fadeInSpec = tween(220, delayMillis = 60),
                    placementSpec = spring(
                        dampingRatio = 1f, stiffness = 500f,
                        visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                    ),
                    fadeOutSpec = tween(150),
                ).fillMaxWidth().groupItem(i == 0, i == sorted.lastIndex)
                    .clickable(role = Role.Button) { onSelect(v) }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LinePlate(v)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.line?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") }
                            ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                        style = K.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (a.stopId < 0 && v.stop == null) {
                            val far = distanceLabel(metres(from.first, from.second, a.lat, a.lon))
                            T("$far from you", "$far ממך")
                        } else "${stopName(v.stop?.name)} · ${whenLabel(v.eta, now)}",
                        style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
                    LiveGlyph(tint, 12.dp)
                    Text(T("${ageS}s ago", "לפני ${ageS} שנ׳"), style = K.type.caption.tabular, color = K.textSecondary)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CardClose(onClose: () -> Unit) {
    KavIconButton(Icons.Rounded.Close, T("Close", "סגירה"), onClose, size = 36.dp)
}

@Composable
private fun LiveVehicleCard(
    v: Tracked?, now: Long, stops: List<Transit.Stop>, progress: Float, reportStops: List<ReportStop>,
    onReport: (ReportFor) -> Unit, onRefresh: () -> Unit, onClose: () -> Unit,
) {
    var last by remember { mutableStateOf(v) }
    if (v != null) last = v
    val shown = v ?: last
    Column(Modifier.fillMaxWidth().padding(K.gap4)) {
        if (shown == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note(T("This vehicle is no longer reporting.", "כלי הרכב הזה כבר לא משדר מיקום."), Modifier.weight(1f))
                CardClose(onClose)
            }
            return@Column
        }
        val a = shown.arrival
        Row(
            Modifier.fillMaxWidth().padding(bottom = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LinePlate(shown)
            Column(Modifier.weight(1f)) {
                shown.line?.destination?.ifBlank { null }?.let(::shownName)?.let {
                    Text(T("to $it", "אל $it"), fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                shown.line?.origin?.ifBlank { null }?.let(::shownName)?.let {
                    Text(T("from $it", "מ-$it"), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CardClose(onClose)
        }
        val (headline, tint) = when {
            a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
            !a.hasLocation -> T("No live location right now", "אין מיקום בזמן אמת כרגע") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            else -> locationHeadline(a.sampleUtc, now)
        }
        // Refreshing asks again at once; it rests a few seconds after, as the answer takes a moment.
        var asked by remember { mutableLongStateOf(0L) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.hasLocation) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, style = K.type.bodyStrong, color = tint, modifier = Modifier.weight(1f))
            KavIconButton(Icons.Rounded.Refresh, T("Refresh the location", "רענון המיקום"), {
                if (now - asked >= 5) { asked = now; onRefresh() }
            }, size = 36.dp, tint = if (now - asked >= 5) K.textSecondary else K.textTertiary)
        }
        if (a.sampleUtc > 0) Text(
            updatedLabel(a),
            fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
        )
        Spacer(Modifier.height(K.gap3))
        KavLive.plateOf(a.vehicleId)?.let { LiveFact(T("Number plate", "מספר רישוי"), it) }
        if (a.platform.isNotBlank()) LiveFact(T("Platform", "רציף"), a.platform)
        // A train goes to the railway's channels (ReportFor.train).
        shown.line?.takeIf { modeOf(shown.routeType) == Mode.TRAIN }?.let { line ->
            Text(T("Report this train", "דיווח על הרכבת הזו"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) {
                    onReport(ReportFor(
                        line = shown.number, agency = "",
                        origin = line.origin, destination = line.destination, at = now, train = true,
                    ))
                }.padding(vertical = K.gap2))
        }
        shown.line?.takeIf { modeOf(shown.routeType) == Mode.BUS }?.let { line ->
            Text(T("Report this bus", "דיווח על האוטובוס הזה"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) {
                    val kav = shown.kav
                    onReport(ReportFor(
                        line = shown.number, agency = "",
                        origin = line.origin, destination = line.destination,
                        stops = reportStops,
                        plate = KavLive.plateOf(a.vehicleId), at = now,
                        near = if (a.hasLocation) a.lat to a.lon else null,
                        towns = kav?.let { it.fromCity to it.toCity },
                        operatorId = kav?.agency, direction = kav?.name?.let(::ministryDirection),
                    ))
                }.padding(vertical = K.gap2))
        }
        // Only once it's known where the bus is, so the rail opens on it.
        androidx.compose.animation.AnimatedVisibility(
            stops.size > 1 && progress >= 0f,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
        ) { BusRail(stops, progress) }
    }
}

private val RAIL_HEIGHT = 120.dp

// The bus's stops on a rail, as the card for the bus you're on draws them, from the last one it passed.
@Composable
private fun BusRail(stops: List<Transit.Stop>, progress: Float) {
    val from = (progress.toInt() - 1).coerceAtLeast(0)
    val ahead = remember(stops, from) { stops.drop(from) }
    val ids = remember(ahead) { ahead.map { it.id } }
    val names = remember(ahead) { ahead.associate { it.id to Transit.StopInfo(it.id, it.name, "") } }
    Column {
        Spacer(Modifier.height(K.gap2))
        Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
        Spacer(Modifier.height(K.gap2 - 5.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = RAIL_HEIGHT).verticalScroll(rememberScrollState())) {
            StopRail(ids, names, progress - from, 0)
        }
    }
}

// How far along its stops the bus is, as the rail counts it: the stops it passed and the part of the way to the next,
// measured along its road. -1 without one.
private fun busProgress(stops: List<Transit.Stop>, route: List<Pair<Double, Double>>, bus: Transit.Arrival?): Float {
    if (bus == null || route.size < 2 || stops.isEmpty()) return -1f
    val along = alongPath(bus.lat, bus.lon, route)
    var passed = 0
    var prev = 0.0
    var next = Double.NaN
    for (s in stops) {
        val a = alongPath(s.lat, s.lon, route)
        if (a <= along) { passed++; prev = a } else { next = a; break }
    }
    val frac = if (!next.isNaN() && next > prev) ((along - prev) / (next - prev)).toFloat().coerceIn(0f, 1f) else 0f
    return (passed + frac).coerceAtMost(stops.size.toFloat())
}

@Composable
private fun LiveStopCard(
    stop: Transit.Stop?,
    stopId: Int,
    arrivals: Map<Transit.ArrivalKey, Transit.Arrival>,
    lines: Map<Int, Transit.LineInfo?>,
    modes: Map<Int, Int>,
    now: Long,
    onVehicle: (Tracked) -> Unit,
    onClose: () -> Unit,
) {
    val due = remember(arrivals, stopId) {
        arrivals.values.filter { it.stopId == stopId }
            .sortedBy { a -> a.rtUtc.takeIf { it > 0 } ?: a.staticUtc }
    }
    val rows = remember(due, lines, modes, stop) {
        due.map { a ->
            val line = lines[a.lineId]
            Tracked(a, stop, line, a.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }
    }
    val moving = remember(due) { due.count { it.hasLocation } }
    val ctx = LocalContext.current
    // The board lists nothing at a stop where every line ends, which is not the same as nothing due.
    val endsHere by produceState(false, stop?.id, rows.isEmpty()) {
        value = false
        val st = stop ?: return@produceState
        if (rows.isNotEmpty()) return@produceState
        val net = runCatching { loadNet(ctx) }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val same = net.nearestStops(st.lat, st.lon, k = 6, radius = 60.0).map { it.first }
                .filter { net.name[it] == st.name }.toHashSet()
            same.isNotEmpty() && same.none { net.dStart[it + 1] > net.dStart[it] } &&
                net.tripRoute.indices.any { net.tripLast(it) in same }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = K.gap3)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            val name = stop?.name
            // The timetable's own stop here.
            val inNet by produceState<Pair<Net, Int>?>(null, stop?.id, stopId) {
                val st = stop ?: return@produceState
                val net = runCatching { loadNet(ctx) }.getOrNull() ?: return@produceState
                value = withContext(Dispatchers.Default) {
                    net.nearestStops(st.lat, st.lon, k = 6, radius = 60.0).map { it.first }.firstOrNull { net.name[it] == st.name }
                }?.let { net to it }
            }
            // Street View faces the stop from its road where the timetable has it, else from wherever Google stands.
            val look = inNet?.let { (net, at) -> StopLook(lookAt(net, at), stopName(name)) }
                ?: stop?.let { StopLook(StreetLook(it.lat to it.lon), stopName(name)) }
            inNet?.let { (net, at) -> StopGlyphOrPhoto(net, at, null, thumb = 44.dp, look = look, opened = true) }
                ?: StopGlyphOrPhoto(stopId, null, thumb = 44.dp, look = look, opened = true)
            Column(Modifier.weight(1f)) {
                Text(
                    stopName(name), style = K.type.headline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rows.isEmpty() && endsHere) T("No departures · every line ends here", "אין יציאות · כל הקווים מסתיימים כאן")
                    else if (rows.isEmpty()) T("Nothing due right now", "אין יציאות כרגע")
                    else T.count(rows.size, "1 due", "יציאה אחת", "due", "יציאות") + " · " +
                        if (moving == 1) T("1 reporting a position", "אחת מדווחת מיקום") else T("$moving reporting a position", "$moving מדווחות מיקום"),
                    style = K.type.subhead.tabular,
                )
            }
            CardClose(onClose)
        }
        if (rows.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 300.dp),
            contentPadding = PaddingValues(horizontal = K.gap1),
        ) {
            items(rows, key = { it.tripId }) { v ->
                val live = v.arrival.hasLocation
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (live) T("Follow this vehicle", "מעקב אחר כלי הרכב")
                            else T("See where line ${v.number} goes", "המסלול של קו ${v.number}"),
                        ) { onVehicle(v) }
                        .padding(horizontal = K.gap3, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LinePlate(v)
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.line?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") }
                                ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                            fontSize = 14.sp, color = K.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (v.arrival.platform.isNotBlank()) {
                            Spacer(Modifier.height(K.gap1))
                            PlatformTag(v.arrival.platform)
                        }
                    }
                    if (live) LiveGlyph(
                        if (v.arrival.vehicleStatus == 2) K.problem else freshColour(freshnessOf(v.arrival.sampleUtc, now)), 11.dp,
                    )
                    Text(
                        whenLabel(v.eta, now), style = K.type.subhead.tabular,
                        color = if (live) K.text else K.scheduled,
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// What a vehicle's colour on the map says (freshColour): how old its position is.
@Composable
private fun FreshnessKey() {
    Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        for ((f, label) in listOf(
            Freshness.LIVE to T("Position from the last 1.5 min", "מיקום מהדקה וחצי האחרונה"),
            Freshness.ESTIMATED to T("Position 1.5 to 3.5 min old", "מיקום בן דקה וחצי עד שלוש וחצי דקות"),
            Freshness.OFF to T("Older: it may be off", "ישן יותר: ייתכן שאינו מדויק"),
        )) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(freshColour(f)))
            Text(label, style = K.type.subhead)
        }
    }
}
