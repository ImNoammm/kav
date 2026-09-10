package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.StopStore
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Process-wide Moovit online session + the stop DB as far as it has been paged. */
object Online {
    @Volatile var session: MoovitSession? = null
    @Volatile var stops: List<Moovit.Stop> = emptyList()
    /** the id the page walk continues from, until [stopsComplete] */
    @Volatile var stopsNext: Int = 1
    @Volatile var stopsComplete: Boolean = false
}

private val hm = SimpleDateFormat("HH:mm", Locale.US)

/**
 * A vehicle the Live tab is showing: the arrival that carries its position, at the
 * nearest of our stops it has yet to reach, and what is known about its line.
 */
private class Tracked(
    val arrival: Moovit.Arrival,
    val stop: Moovit.Stop?,
    val line: Moovit.LineInfo?,
    val looked: Boolean,
    val routeType: Int,
) {
    val tripId get() = arrival.tripId
    /** the number on the bus; an ellipsis while it is being looked up, the id if that failed */
    val number get() = line?.number?.ifBlank { null } ?: if (pending) "…" else "#${arrival.lineId}"
    val pending get() = line == null && !looked
    val eta get() = arrival.rtUtc.takeIf { it > 0 } ?: arrival.staticUtc
}

/**
 * Live vehicles on a monochrome map. This is ONLINE mode: it registers a throwaway
 * Moovit identity and polls Moovit's servers (~every 20 s, the cadence Moovit itself
 * uses). Every vehicle dot is a real GPS fix. The banner states the trade honestly.
 *
 * The stops around you are polled for arrivals; every arrival that carries a vehicle
 * position is a vehicle on the map and in the list, named by the number on the bus
 * rather than Moovit's internal line id, and each one opens on its own route.
 */
@Composable
fun LiveScreen(model: KavModel) {
    val ctx = LocalContext.current
    val here = model.here ?: (32.0759 to 34.7745)   // fallback: central Tel Aviv until located
    val located = model.here != null
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) requestLocationOnce(ctx) { model.here = it }
    }
    // Ask for location the first time the Live tab is opened, so we can find the
    // stops actually near you (falls back to central Tel Aviv until then).
    LaunchedEffect(Unit) {
        if (model.here == null) {
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
            else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var status by remember { mutableStateOf("connecting to Moovit…") }
    var loading by remember { mutableStateOf(true) }
    var arrivals by remember { mutableStateOf<Map<Moovit.ArrivalKey, Moovit.Arrival>>(emptyMap()) }
    var near by remember { mutableStateOf<List<Moovit.Stop>>(emptyList()) }
    var lines by remember { mutableStateOf<Map<Int, Moovit.LineInfo?>>(emptyMap()) }
    var modes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pollSecs by remember { mutableIntStateOf(20) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() / 1000 } }

    LaunchedEffect(here) {
        try {
            val s = Online.session ?: withContext(Dispatchers.IO) { Moovit.register(here.first, here.second) }
                .also { Online.session = it }
            val app = ctx.applicationContext
            if (Online.stops.isEmpty()) {
                withContext(Dispatchers.IO) { StopStore.load(app) }?.let {
                    Online.stops = it.stops; Online.stopsNext = it.nextId; Online.stopsComplete = it.complete
                }
            }
            fun nearby() = Moovit.nearbyStops(Online.stops, here.first, here.second, k = 40)
            if (Online.stops.isNotEmpty()) near = withContext(Dispatchers.Default) { nearby() }
            // The database is paged by id, and the ids are nowhere in particular, so the
            // first pages are not the stops near you. Walk the whole range once, four
            // blocks of ten pages at a time, tightening the stops around you after every
            // block, and keep the result so the next launch starts complete.
            if (!Online.stopsComplete) launch(Dispatchers.IO) {
                var blocks = 0
                while (isActive && !Online.stopsComplete) {
                    val from = Online.stopsNext
                    val block = try {
                        // inside its own scope, so one failed page fails the block and is
                        // caught here, rather than taking the whole effect down with it
                        coroutineScope {
                            (0 until 4).map { i -> async { Moovit.stopPages(s, from + i * 1000, 10) } }.awaitAll().flatten()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Could not load stops · retrying"
                        delay(10_000); continue
                    }
                    val known = Online.stops.mapTo(HashSet()) { it.id }
                    Online.stops = Online.stops + block.filter { it.id !in known }
                    Online.stopsNext = from + 4000
                    Online.stopsComplete = Online.stopsNext >= Moovit.STOP_ID_CEILING
                    near = nearby()
                    blocks++
                    if (blocks % 3 == 0 || Online.stopsComplete) StopStore.save(app, Online.stops, Online.stopsNext, Online.stopsComplete)
                }
            }
            while (near.isEmpty()) { status = "loading stops…"; delay(300) }
            while (coroutineContext.isActive) {
                val ids = near.map { it.id }
                try {
                    val (found, poll) = withContext(Dispatchers.IO) { Moovit.stopArrivals(s, ids) }
                    arrivals = found; pollSecs = poll.coerceIn(10, 60); loading = false
                    val tracked = found.values.filter { it.hasLocation }
                    status = if (tracked.isEmpty()) "No tracked vehicles right now"
                        else "${tracked.map { it.tripId }.distinct().size} live vehicles · ${ids.size} stops" +
                            (if (Online.stopsComplete) "" else " · still loading stops")
                    // The number on the bus lives in its line group: one lookup per line,
                    // several at a time, soonest vehicles first, shown as each batch lands.
                    val missing = tracked.sortedBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
                        .map { it.lineId }.distinct().filter { it !in lines }
                    for (batch in missing.chunked(8)) {
                        val named = withContext(Dispatchers.IO) {
                            batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
                        }
                        lines = lines + named
                        val agencies = named.values.mapNotNull { it?.agencyId }.distinct().filter { it !in modes }
                        if (agencies.isNotEmpty()) modes = modes + withContext(Dispatchers.IO) {
                            agencies.associateWith { runCatching { Moovit.agencyRouteType(s, it) }.getOrDefault(3) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loading = false
                    status = "Could not refresh · retrying shortly"
                }
                delay(pollSecs * 1000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loading = false
            status = "online error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // One vehicle appears at every stop of ours it has yet to reach; show it once, at
    // the first of them, and in the order it will get there.
    val stopsById = remember(near) { near.associateBy { it.id } }
    val vehicles = remember(arrivals, lines, modes, stopsById) {
        arrivals.values.filter { it.hasLocation }.groupBy { it.tripId }.values.map { at ->
            val first = at.minBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
            val line = lines[first.lineId]
            Tracked(first, stopsById[first.stopId], line, first.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }.sortedBy { it.eta }
    }

    androidx.activity.compose.BackHandler(selected != null) { selected = null }
    androidx.compose.animation.AnimatedContent(
        targetState = selected,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) forward() else backward() },
        label = "live",
    ) { tripId ->
        if (tripId != null) {
            LiveVehicleScreen(vehicles.firstOrNull { it.tripId == tripId }, now) { selected = null }
            return@AnimatedContent
        }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Live", "vehicles", onSettings = { model.settingsOpen = true })
            Text(
                "Online mode: positions come from Moovit's servers, refreshed every ${pollSecs}s.",
                fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
            )
            if (!located) Row(
                Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap2),
                horizontalArrangement = Arrangement.spacedBy(K.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("Use my location", false) {
                    if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
                    else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                Text("showing central Tel Aviv", style = DisplayItalic, fontSize = 12.sp, color = K.dim)
            }
            Box(
                Modifier.padding(horizontal = K.gap3).fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(14.dp)).background(K.surface1),
            ) {
                LiveMap(here, near, vehicles)
                Text(
                    status, style = Mono, fontSize = 11.sp, color = K.muted,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(K.gap2).clip(RoundedCornerShape(6.dp)).background(K.glassPlate).padding(6.dp),
                )
            }
            if (loading) Box(
                Modifier.fillMaxWidth().height(200.dp + LocalBottomBarInset.current)
                    .padding(bottom = LocalBottomBarInset.current),
                contentAlignment = Alignment.Center,
            ) { LoadingPulse("Finding vehicles") }
            else LiveList(vehicles, now, Modifier.fillMaxWidth()
                .heightIn(max = 260.dp + LocalBottomBarInset.current).padding(K.gap2)) { selected = it.tripId }
        }
    }
}

private val K.glassPlate get() = androidx.compose.ui.graphics.Color(0xCC000000)

/**
 * The live map: real tiles, the stops around you as circles, and every tracked vehicle.
 *
 * This used to be a hand-rolled equirectangular canvas with no basemap at all, which is
 * why the tab read as a grey rectangle, there was nothing under the dots to be a map.
 * It now shares the same tiled projection as every other map in the app.
 */
@Composable
private fun LiveMap(
    center: Pair<Double, Double>,
    stops: List<Moovit.Stop>,
    vehicles: List<Tracked>,
) {
    val pulse = rememberLivePulse()
    // keep the frame steady while vehicles move: fit the stops and you, not the traffic
    val points = remember(stops, center) { stops.map { it.lat to it.lon } + center }
    // stops as circles, the way Moovit rings them on its own map, and you; both
    // handed to MapLibre so they sit still on the ground while the camera moves
    val geometry = remember(stops, center) {
        MapGeometry(dots = stops.flatMap { st ->
            listOf(
                MapDot(st.lat, st.lon, K.bg, 6f),
                MapDot(st.lat, st.lon, Color.Transparent, 4.2f, K.muted, 1.6f),
            )
        } + listOf(
            MapDot(center.first, center.second, K.text.copy(alpha = 0.18f), 13f),
            MapDot(center.first, center.second, K.bg, 6f),
            MapDot(center.first, center.second, K.text, 4f),
        ))
    }
    TileMap(points, Modifier.fillMaxSize(), geometry = geometry, animatedOverlay = { proj ->
        // tracked vehicles, breathing
        for (v in vehicles) {
            val a = v.arrival
            val o = proj.point(a.lat, a.lon)
            val tint = if (a.vehicleStatus == 2) K.problem else K.live
            drawCircle(tint.copy(alpha = 0.20f), 15.dp.toPx() * pulse.value, o)
            drawCircle(K.bg, 8.dp.toPx(), o)
            drawCircle(tint, 5.dp.toPx(), o)
        }
    })
}

/** The number on the bus in a plate, with the mark of the kind of vehicle it is. */
@Composable
private fun LinePlate(v: Tracked) {
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(K.plate)
            .border(1.dp, K.borderStrong, RoundedCornerShape(7.dp))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgencyMark(v.routeType, v.line?.agencyId ?: -1, K.muted, 15.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            v.number, fontSize = 16.sp, color = K.text, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

private fun stopName(stop: Moovit.Stop?, id: Int) = stop?.name?.ifBlank { null } ?: "stop $id"

@Composable
private fun LiveList(vehicles: List<Tracked>, now: Long, modifier: Modifier, onSelect: (Tracked) -> Unit) {
    if (vehicles.isEmpty()) {
        Note(
            "Nothing tracked near you right now. Vehicles appear here as soon as one of your stops has a bus reporting its position.",
            Modifier.padding(horizontal = K.gap4, vertical = K.gap3),
        )
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(
        start = K.gap1, top = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
    )) {
        items(vehicles, key = { it.tripId }) { v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl))
                    .clickable(role = Role.Button) { onSelect(v) }
                    .padding(horizontal = K.gap3, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LinePlate(v)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.line?.destination?.ifBlank { null }?.let { "to $it" } ?: if (v.pending) "Looking up the line…" else "Line ${v.number}",
                        fontSize = 14.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${stopName(v.stop, a.stopId)} · ${whenLabel(v.eta, now)}",
                        fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("${ageS}s ago", style = Mono, fontSize = 11.sp, color = if (a.vehicleStatus == 2) K.problem else K.live)
                Text("›", fontSize = 22.sp, color = K.dim)
            }
        }
    }
}

/**
 * One vehicle on its own: the whole route it is driving, where it is on it, and when
 * it reaches the stop near you. It keeps reading the tab's polls, so the dot moves.
 */
@Composable
private fun LiveVehicleScreen(v: Tracked?, now: Long, onBack: () -> Unit) {
    // the last one seen stays on screen if a poll drops it, rather than a blank page
    var last by remember { mutableStateOf(v) }
    if (v != null) last = v
    val shown = v ?: last
    val pulse = rememberLivePulse()
    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader("Line", shown?.number.orEmpty(), back = onBack)
        if (shown == null) {
            Note("This vehicle is no longer reporting.", Modifier.padding(K.gap4))
            return@Column
        }
        val a = shown.arrival
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LinePlate(shown)
            Column(Modifier.weight(1f)) {
                shown.line?.destination?.ifBlank { null }?.let {
                    Text("to $it", fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                shown.line?.origin?.ifBlank { null }?.let {
                    Text("from $it", fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val route = rememberLineRoute(a.tripShapeId)
        val stop = shown.stop
        val points = remember(route, a.lat, a.lon, stop) {
            listOf(a.lat to a.lon) + listOfNotNull(stop?.let { it.lat to it.lon }) + route
        }
        val geometry = remember(route, stop) {
            MapGeometry(
                lines = listOf(MapLine(route, K.route, 4f, casing = 8f)),
                dots = stop?.let {
                    listOf(MapDot(it.lat, it.lon, K.bg, 8f), MapDot(it.lat, it.lon, Color.Transparent, 5f, K.text, 2f))
                }.orEmpty(),
            )
        }
        TileMap(
            points,
            Modifier.padding(horizontal = K.gap3).fillMaxWidth().height(320.dp)
                .clip(RoundedCornerShape(K.rCard)).background(K.surface1),
            geometry = geometry,
            animatedOverlay = { proj ->
                val p = proj.point(a.lat, a.lon)
                val tint = if (a.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f), 18.dp.toPx() * pulse.value, p)
                drawCircle(K.bg, 9.dp.toPx(), p)
                drawCircle(tint, 6.dp.toPx(), p)
            },
        )
        Spacer(Modifier.height(K.gap3))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3)
                .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
        ) {
            val (headline, tint) = when {
                a.vehicleStatus == 3 -> "Not departed yet" to K.dim
                a.vehicleStatus == 2 -> "Out of route" to K.problem
                now - a.sampleUtc <= 120 -> "Location updated recently" to K.live
                else -> "Location is estimated" to K.problem
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp))
                Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
            }
            if (a.sampleUtc > 0) Text(
                "Location updated: ${hm.format(Date(a.sampleUtc * 1000))}",
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
            Spacer(Modifier.height(K.gap3))
            LiveFact("Next of your stops", stopName(stop, a.stopId))
            LiveFact("Arriving", whenLabel(shown.eta, now))
            val away = a.stopsAway
            if (away >= 0) LiveFact("Stops away", if (away == 0) "at the stop" else "$away")
            if (route.isEmpty() && a.tripShapeId > 0) LiveFact("Route", "loading…")
        }
        Spacer(Modifier.height(K.gap8))
    }
}

@Composable
private fun LiveFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
