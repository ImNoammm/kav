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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.requestLocationOnce
import java.util.Date
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

object Online {
    @Volatile var session: MoovitSession? = null
    private val opening = kotlinx.coroutines.sync.Mutex()
    private var failedAt = 0L
    private var failures = 0
    @Volatile private var generation = 0
    private var store: android.content.SharedPreferences? = null
    private var born = 0L
    private var rotate = false
    @Volatile private var asked = false
    private const val WEEK_MS = 7 * 86_400_000L

    fun init(ctx: android.content.Context) {
        val s = ctx.getSharedPreferences("moovit-session", android.content.Context.MODE_PRIVATE)
        store = s
        born = s.getLong("born", 0L)
        rotate = s.getBoolean("rotate", false)
        session = s.getString("user", null)?.let { user ->
            MoovitSession(
                user, s.getString("access", null).orEmpty(), s.getString("refresh", null).orEmpty(),
                s.getInt("metro", 1), s.getLong("expires", 0L),
            )
        }
    }

    // After a change to what Moovit may be told: the next call starts a new user, and a registration
    // already under way is not kept.
    fun reset() {
        generation++
        rotate = true
        asked = true
        store?.edit()?.putBoolean("rotate", true)?.apply()
    }

    // Renewed a minute before it expires.
    private fun fresh(s: MoovitSession?) = s != null && s.accessExpiresUtc - System.currentTimeMillis() / 1000 > 60

    // One Moovit session for the whole app. Callers that arrive together share it, or its failure.
    suspend fun open(at: Pair<Double, Double>? = null): MoovitSession = session.takeIf { fresh(it) && !asked } ?: opening.withLock {
        session.takeIf { fresh(it) && !asked } ?: run {
            // Each failure waits twice as long, up to five minutes: Moovit refuses new sessions from
            // an address that keeps asking.
            val wait = 10_000L shl (failures - 1).coerceIn(0, 5)
            if (failures > 0 && System.currentTimeMillis() - failedAt < minOf(wait, 300_000L)) {
                throw java.io.IOException("Moovit is unreachable")
            }
            try {
                val gen = generation
                withContext(Dispatchers.IO) { obtain(at) }.also {
                    if (gen == generation) {
                        if (it.userKey != session?.userKey) { born = System.currentTimeMillis(); rotate = false }
                        keep(it)
                    }
                    failures = 0
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt = System.currentTimeMillis()
                failures++
                throw e
            }
        }
    }

    // Moovit's CDN has started refusing requests for a new user more than once, so a user is kept and
    // its access renewed each day. A new one is made weekly or when asked for, and while that fails
    // the old one carries on, trying again at the next renewal.
    private fun obtain(at: Pair<Double, Double>?): MoovitSession {
        asked = false
        val kept = session
        val due = kept == null || rotate || System.currentTimeMillis() - born > WEEK_MS
        if (due) {
            try {
                return if (at == null) Moovit.register() else Moovit.register(at.first, at.second)
            } catch (e: Exception) {
                if (kept == null) throw e
            }
        }
        if (fresh(kept)) return kept!!
        return try {
            Moovit.renew(kept!!)
        } catch (e: Exception) {
            if (due) throw e
            if (at == null) Moovit.register() else Moovit.register(at.first, at.second)
        }
    }

    private fun keep(s: MoovitSession) {
        session = s
        store?.edit()
            ?.putString("user", s.userKey)?.putString("access", s.accessToken)?.putString("refresh", s.refreshToken)
            ?.putInt("metro", s.metroId)?.putLong("expires", s.accessExpiresUtc)
            ?.putLong("born", born)?.putBoolean("rotate", rotate)
            ?.apply()
    }
}

private val hm get() = clockFormat()

private const val LOOK_REACH_KM = 3.5

private sealed interface LiveFocus {
    data class Vehicle(val tripId: Long, val from: Int? = null) : LiveFocus
    data class Stop(val id: Int) : LiveFocus
}

private class Tracked(
    val arrival: Moovit.Arrival,
    val stop: Moovit.Stop?,
    val line: Moovit.LineInfo?,
    val looked: Boolean,
    val routeType: Int,
) {
    val tripId get() = arrival.tripId
    val number get() = line?.number?.ifBlank { null } ?: if (pending) "…" else "#${arrival.lineId}"
    val pending get() = line == null && !looked
    val eta get() = arrival.rtUtc.takeIf { it > 0 } ?: arrival.staticUtc
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
    LaunchedEffect(locGranted, lifecycle) {
        if (!locGranted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                requestLocationOnce(ctx) { model.locate(it.first, it.second) }
                delay(120_000)
            }
        }
    }
    var look by remember { mutableStateOf<Pair<Pair<Double, Double>, Double>?>(null) }
    var zoomedOut by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(0 to 0) }
    val wake = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    var status by remember { mutableStateOf(T("connecting to Moovit…", "מתחברים ל-Moovit…")) }
    var loading by remember { mutableStateOf(true) }
    var arrivals by remember { mutableStateOf<Map<Moovit.ArrivalKey, Moovit.Arrival>>(emptyMap()) }
    var near by remember { mutableStateOf<List<Moovit.Stop>>(emptyList()) }
    var unmatched by remember { mutableStateOf<List<Int>>(emptyList()) }
    var lines by remember { mutableStateOf<Map<Int, Moovit.LineInfo?>>(emptyMap()) }
    var modes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pollSecs by remember { mutableIntStateOf(20) }
    var focus by remember { mutableStateOf<LiveFocus?>(null) }
    var picked by remember { mutableStateOf<Tracked?>(null) }
    var farStop by remember { mutableStateOf<Moovit.Stop?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(1000); now = System.currentTimeMillis() / 1000 }
        }
    }

    // Moovit's stop list misses about half the stops, so they come from the timetable.
    LaunchedEffect(look?.first ?: here, look?.second) {
        val net = model.net ?: runCatching { loadNet(ctx) }.getOrNull()?.also { model.net = it }
            ?: return@LaunchedEffect
        val (at, reach) = look ?: (here to 1.5)
        val inView = withContext(Dispatchers.Default) {
            net.nearestStops(at.first, at.second, k = 120, radius = reach.coerceIn(0.6, LOOK_REACH_KM) * 1000)
                .map { it.first }
        }
        fun stopOf(g: Int, id: Int) = Moovit.Stop(id, net.lat[g], net.lon[g], net.name[g])
        while (true) {
            val known = ArrayList<Moovit.Stop>()
            val unknown = ArrayList<Int>()
            for (g in inView) StopPhotos.idNow(net, g)?.let { known += stopOf(g, it) } ?: unknown.add(g)
            near = known.distinctBy { it.id }
            unmatched = emptyList()
            wake.trySend(Unit)
            matching = 0 to unknown.size
            val missed = ArrayList<Int>()
            for (batch in unknown.chunked(8)) {
                val found = coroutineScope { batch.map { g -> async { g to StopPhotos.idOf(net, g) } }.awaitAll() }
                val first = near.isEmpty()
                known += found.mapNotNull { (g, id) -> id?.let { stopOf(g, it) } }
                missed += found.filter { it.second == null }.map { it.first }
                matching = (matching.first + batch.size) to unknown.size
                near = known.distinctBy { it.id }
                if (first && near.isNotEmpty()) wake.trySend(Unit)
            }
            matching = 0 to 0
            unmatched = missed
            wake.trySend(Unit)
            if (near.isNotEmpty()) break
            loading = false
            // Every lookup failing, rather than coming back empty, means Moovit is out of reach.
            if (missed.isEmpty() || missed.any { StopPhotos.knownMissing(net, it) }) {
                status = T("No stops around here", "אין תחנות באזור הזה")
                break
            }
            status = T("Can't reach Moovit · trying again shortly", "אין חיבור ל-Moovit · ננסה שוב בקרוב")
            delay(30_000)
        }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (coroutineContext.isActive) {
                val ids = (near.map { it.id } + listOfNotNull(farStop?.id)).distinct()
                if (ids.isEmpty()) {
                    if (matching.second > 0 || model.net == null) status = T("loading stops…", "טוענים תחנות…")
                    wake.receive()
                    continue
                }
                try {
                    val s = Online.open(here)
                    val (found, poll) = withContext(Dispatchers.IO) { Moovit.stopArrivals(s, ids) }
                    arrivals = found; pollSecs = poll.coerceIn(10, 60); loading = false
                    // A name search can't tell same-named stops apart and finds no id for others. The lines
                    // through the stops already found list every stop on them, each with its code.
                    val net = model.net
                    if (net != null && unmatched.isNotEmpty()) {
                        val routes = found.values.map { it.patternId }.distinct().filter { it > 0 }
                        val learned = withContext(Dispatchers.IO) {
                            routes.chunked(8).flatMap { batch ->
                                batch.map { p -> async { runCatching { Moovit.patternStops(s, p) }.getOrDefault(emptyList()) } }
                                    .awaitAll().flatten()
                            }
                        }
                        StopPhotos.learn(learned)
                        val byCode = learned.associateBy { it.code }
                        val added = unmatched.mapNotNull { g ->
                            net.code.getOrElse(g) { 0 }.takeIf { it > 0 }?.let { byCode[it.toString()] }?.let { g to it.id }
                        }
                        if (added.isNotEmpty()) {
                            unmatched = unmatched - added.map { it.first }.toSet()
                            near = (near + added.map { (g, id) -> Moovit.Stop(id, net.lat[g], net.lon[g], net.name[g]) }).distinctBy { it.id }
                            wake.trySend(Unit)
                        }
                    }
                    val tracked = found.values.filter { it.hasLocation }
                    status = if (tracked.isEmpty()) {
                        T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב")
                    } else {
                        T(
                            "${tracked.map { it.tripId }.distinct().size} live vehicles · ${ids.size} stops",
                            "${tracked.map { it.tripId }.distinct().size} כלי רכב בזמן אמת · ${ids.size} תחנות",
                        )
                    }
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
                    status = T("Could not refresh · retrying shortly", "לא ניתן היה לרענן · ננסה שוב בקרוב")
                }
                kotlinx.coroutines.withTimeoutOrNull(pollSecs * 1000L) { wake.receive() }
            }
        }
    }

    val stopsById = remember(near, farStop) { (near + listOfNotNull(farStop)).associateBy { it.id } }
    val vehicles = remember(arrivals, lines, modes, stopsById) {
        arrivals.values.filter { it.hasLocation }.groupBy { it.tripId }.values.map { at ->
            val first = at.minBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
            val line = lines[first.lineId]
            Tracked(first, stopsById[first.stopId], line, first.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }.sortedBy { it.eta }
    }

    androidx.activity.compose.BackHandler(focus != null) {
        focus = when (val f = focus) {
            is LiveFocus.Vehicle -> f.from?.let { LiveFocus.Stop(it) }
            else -> null
        }
    }
    val focusStop = (focus as? LiveFocus.Stop)?.let { f -> stopsById[f.id] ?: farStop?.takeIf { it.id == f.id } }
    LaunchedEffect(focus) { if (focus == null) farStop = null }
    LaunchedEffect(farStop?.id) {
        val f = farStop ?: return@LaunchedEffect
        val s = runCatching { Online.open() }.getOrNull() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { Moovit.stopArrivals(s, listOf(f.id)).first } }
            .onSuccess { found -> arrivals = arrivals + found }
    }
    val focusVehicle = (focus as? LiveFocus.Vehicle)?.let { f ->
        vehicles.firstOrNull { it.tripId == f.tripId } ?: picked?.takeIf { it.tripId == f.tripId }?.let { p ->
            arrivals[p.arrival.key]?.let { Tracked(it, p.stop, p.line, p.looked, p.routeType) } ?: p
        }
    }
    val patternId = focusVehicle?.arrival?.patternId ?: -1
    val pattern by produceState(emptyList<Int>(), patternId) {
        value = emptyList()
        val s = runCatching { Online.open() }.getOrNull() ?: return@produceState
        if (patternId > 0) value = withContext(Dispatchers.IO) {
            runCatching { Moovit.tripPattern(s, patternId) }.getOrDefault(emptyList())
        }
    }
    val held = remember(pattern) { pattern.mapNotNull { stopsById[it] }.associateBy { it.id } }
    val asked = rememberStopNames(pattern.filter { it !in held })
    val lineStops = remember(pattern, held, asked) {
        pattern.mapNotNull { id ->
            held[id] ?: asked[id]?.let { info -> info.point?.let { (lat, lon) -> Moovit.Stop(id, lat, lon, info.name) } }
        }
    }
    val liveLiquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0.dp) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Live", "כלי רכב בזמן אמת"), "")
        Text(
            T(
                "Online mode: positions come from Moovit's servers, refreshed every ${pollSecs}s.",
                "מצב מקוון: המיקומים מגיעים משרתי Moovit, מתעדכן כל ${pollSecs} שניות.",
            ),
            fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
        )
        if (!located) Row(
            Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap2),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(T("Use my location", "השתמשו במיקום שלי"), false) {
                if (hasLocationPermission(ctx)) { locGranted = true; requestLocationOnce(ctx) { model.locate(it.first, it.second) } }
                else ask.launch(LOCATION_PERMISSIONS)
            }
            Text(T("showing central Tel Aviv", "מוצג מרכז תל אביב"), style = DisplayItalic, fontSize = 12.sp, color = K.dim)
        }
        Box(
            Modifier.padding(horizontal = K.gap3).fillMaxWidth().weight(1f).panel(14.dp),
        ) {
            CompositionLocalProvider(LocalLiquidBackdrop provides liveLiquid) {
                LiveMap(
                    here, near, vehicles, focus, focusStop, focusVehicle, lineStops, model.liveShow,
                    following = !moved,
                    onLook = { centre, reach ->
                        moved = centre != null
                        zoomedOut = centre != null && reach > LOOK_REACH_KM
                        if (centre == null) look = null
                        else if (!zoomedOut) look = centre to reach
                    },
                    bottomPadding = if (focus != null) cardHeight + K.gap2 else 0.dp,
                    modifier = Modifier.fillMaxSize().glassBackdrop(liveLiquid),
                    onVehicle = { focus = LiveFocus.Vehicle(it.tripId) },
                    onStop = { s -> if (s.id !in stopsById) farStop = s; focus = LiveFocus.Stop(s.id) },
                    status = {
                        Column(Modifier.panel(10.dp).padding(6.dp)) {
                            val (done, total) = matching
                            Text(
                                when {
                                    total > 0 -> T("Loading ${total - done} stops…", "טוענים ${total - done} תחנות…")
                                    zoomedOut && model.liveShow == LiveShow.TRAFFIC ->
                                        T("Zoom in to see the vehicles here", "התקרבו כדי לראות את כלי הרכב כאן")
                                    zoomedOut -> T("Zoom in to load the stops here", "התקרבו כדי לטעון את התחנות כאן")
                                    else -> status
                                },
                                style = Mono, fontSize = 11.sp, color = K.muted,
                            )
                            if (total > 0) {
                                val shown by animateFloatAsState(done.toFloat() / total, tween(300), label = "matching")
                                Box(
                                    Modifier.padding(top = 4.dp).width(140.dp).height(3.dp)
                                        .clip(RoundedCornerShape(2.dp)).background(K.text.copy(alpha = .15f)),
                                ) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(K.accent))
                                }
                            }
                        }
                    },
                    controls = { LiveShowPicker(model.liveShow) { model.liveShow = it } },
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
                        when (val f = shown) {
                            is LiveFocus.Stop -> LiveStopCard(
                                stopsById[f.id] ?: farStop?.takeIf { it.id == f.id }, f.id, arrivals, lines, modes, now,
                                onVehicle = { v -> picked = v; focus = LiveFocus.Vehicle(v.tripId, from = f.id) },
                                onClose = { focus = null },
                            )
                            is LiveFocus.Vehicle -> LiveVehicleCard(
                                focusVehicle?.takeIf { it.tripId == f.tripId }, now,
                            ) { focus = f.from?.let { LiveFocus.Stop(it) } }
                            else -> Unit
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
                1 -> Box(
                    Modifier.fillMaxWidth().height(200.dp + LocalBottomBarInset.current)
                        .padding(bottom = LocalBottomBarInset.current),
                    contentAlignment = Alignment.Center,
                ) { LoadingPulse(T("Finding vehicles", "מאתרים כלי רכב")) }
                else -> LiveList(vehicles, now, Modifier.fillMaxWidth()
                    .heightIn(max = 260.dp + LocalBottomBarInset.current).padding(K.gap2)) {
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
    tripId: Long, route: List<Pair<Double, Double>>, stops: List<Moovit.Stop>, tint: Color,
    bus: Moovit.Arrival?, from: Moovit.Stop?,
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
    stops: List<Moovit.Stop>,
    vehicles: List<Tracked>,
    focus: LiveFocus?,
    focusStop: Moovit.Stop?,
    focusVehicle: Tracked?,
    lineStops: List<Moovit.Stop>,
    show: LiveShow,
    following: Boolean,
    onLook: (Pair<Double, Double>?, Double) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier,
    onVehicle: (Tracked) -> Unit,
    onStop: (Moovit.Stop) -> Unit,
    status: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    val stopReach = with(LocalDensity.current) { 20.dp.toPx() }
    val route = rememberLineRoute(focusVehicle?.arrival?.tripShapeId ?: -1)
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
    val tint = focusVehicle?.let { v -> plateFor(v.routeType, v.line?.agencyId ?: -1)?.fill ?: K.route }
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
        dots += MapDot(center.first, center.second, K.text.copy(alpha = 0.18f), 13f)
        dots += MapDot(center.first, center.second, K.bg, 6f)
        dots += MapDot(center.first, center.second, K.text, 4f)
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
        live = vehicleGeometry(shown.map { it.arrival to modeOf(it.routeType) }, LocalDensity.current) { a ->
            if (a.tripId == (chosen ?: look?.tripId)) 1f else 1f - step
        },
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
private fun LiveShowPicker(show: LiveShow, onShow: (LiveShow) -> Unit) {
    Row(Modifier.panel(999.dp).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (option in LiveShow.entries) {
            val on = option == show
            val tint = if (on) K.text else K.dim
            val label = when (option) {
                LiveShow.STOPS -> T("Stops only", "תחנות בלבד")
                LiveShow.TRAFFIC -> T("Vehicles only", "כלי רכב בלבד")
                LiveShow.ALL -> T("Stops and vehicles", "תחנות וכלי רכב")
            }
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) K.plateStrong else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onShow(option) }
                    .semantics { contentDescription = label; selected = on }
                    .height(22.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (option) {
                    LiveShow.STOPS -> uk.noammm.kav.TabGlyph(uk.noammm.kav.Tab.Stations, tint, 15.dp)
                    LiveShow.TRAFFIC -> ModeGlyph(Mode.BUS, tint, 14.dp)
                    LiveShow.ALL -> Text(T("All", "הכל"), fontSize = 11.sp, color = tint)
                }
            }
        }
    }
}

@Composable
private fun LinePlate(v: Tracked) {
    val agency = v.line?.agencyId ?: -1
    val plate = plateFor(v.routeType, agency)
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(plate?.fill ?: K.plate)
            .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(7.dp))
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

private fun stopName(name: String?) = name?.ifBlank { null } ?: "…"

@Composable
private fun LiveList(vehicles: List<Tracked>, now: Long, modifier: Modifier, onSelect: (Tracked) -> Unit) {
    if (vehicles.isEmpty()) {
        Note(
            T(
                "Nothing tracked near you right now. Vehicles appear here as soon as one of your stops has a bus reporting its position.",
                "אין כרגע כלי רכב במעקב בסביבתכם. כלי רכב יופיעו כאן ברגע שאחת התחנות שלכם תקבל דיווח מיקום מאוטובוס.",
            ),
            Modifier.padding(horizontal = K.gap4, vertical = K.gap3)
                .padding(bottom = LocalBottomBarInset.current),
        )
        return
    }
    val named = rememberStopNames(vehicles.filter { it.stop == null }.map { it.arrival.stopId })
    LazyColumn(modifier, contentPadding = PaddingValues(
        start = K.gap1, top = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
    )) {
        items(vehicles, key = { it.tripId }) { v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().padding(vertical = K.gap1).panel(K.rControl)
                    .clickable(role = Role.Button) { onSelect(v) }
                    .padding(horizontal = K.gap3, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LinePlate(v)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.line?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") }
                            ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                        fontSize = 14.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${stopName(v.stop?.name ?: named[a.stopId]?.name)} · ${whenLabel(v.eta, now)}",
                        fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(T("${ageS}s ago", "לפני ${ageS} שנ׳"), style = Mono, fontSize = 11.sp, color = if (a.vehicleStatus == 2) K.problem else K.realtime)
                Text(T.onward, fontSize = 22.sp, color = K.dim)
            }
        }
    }
}

@Composable
private fun CardClose(onClose: () -> Unit) {
    Box(
        Modifier.size(36.dp).panel(12.dp)
            .clickable(role = Role.Button, onClickLabel = T("Close", "סגירה"), onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { Text("✕", fontSize = 13.sp, color = K.muted) }
}

@Composable
private fun LiveVehicleCard(v: Tracked?, now: Long, onClose: () -> Unit) {
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
                shown.line?.destination?.ifBlank { null }?.let {
                    Text(T("to $it", "אל $it"), fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                shown.line?.origin?.ifBlank { null }?.let {
                    Text(T("from $it", "מ-$it"), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CardClose(onClose)
        }
        val (headline, tint) = when {
            a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
            !a.hasLocation -> T("No live location right now", "אין מיקום בזמן אמת כרגע") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            now - a.sampleUtc <= 120 -> T("Location updated recently", "המיקום עודכן לאחרונה") to K.realtime
            else -> T("Location is estimated", "המיקום משוער") to K.problem
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.hasLocation) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
        }
        if (a.sampleUtc > 0) Text(
            T("Location updated: ${hm.format(Date(a.sampleUtc * 1000))}", "המיקום עודכן: ${hm.format(Date(a.sampleUtc * 1000))}"),
            fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
        )
        Spacer(Modifier.height(K.gap3))
        val asked = rememberStopNames(if (shown.stop == null) listOf(a.stopId) else emptyList())
        LiveFact(
            T("Next of your stops", "התחנה הבאה שלכם"),
            shown.stop?.name?.ifBlank { null } ?: asked[a.stopId]?.name?.ifBlank { null } ?: "…",
        )
        LiveFact(T("Arriving", "הגעה"), whenLabel(shown.eta, now))
        if (a.platform.isNotBlank()) LiveFact(T("Platform", "רציף"), a.platform)
        val away = a.stopsAway
        if (away >= 0) LiveFact(T("Stops away", "מרחק בתחנות"), if (away == 0) T("at the stop", "בתחנה") else "$away")
    }
}

@Composable
private fun LiveStopCard(
    stop: Moovit.Stop?,
    stopId: Int,
    arrivals: Map<Moovit.ArrivalKey, Moovit.Arrival>,
    lines: Map<Int, Moovit.LineInfo?>,
    modes: Map<Int, Int>,
    now: Long,
    onVehicle: (Tracked) -> Unit,
    onClose: () -> Unit,
) {
    val named = rememberStopNames(if (stop == null) listOf(stopId) else emptyList())
    val due = remember(arrivals, stopId) {
        arrivals.values.filter { it.stopId == stopId }
            .sortedBy { a -> a.rtUtc.takeIf { it > 0 } ?: a.staticUtc }
    }
    var extraLines by remember { mutableStateOf<Map<Int, Moovit.LineInfo?>>(emptyMap()) }
    var extraModes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    val lineIds = remember(due) { due.map { it.lineId }.distinct() }
    LaunchedEffect(lineIds) {
        val s = runCatching { Online.open() }.getOrNull() ?: return@LaunchedEffect
        for (batch in lineIds.filter { it !in lines && it !in extraLines }.chunked(8)) {
            val named = withContext(Dispatchers.IO) {
                batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
            }
            extraLines = extraLines + named
            val agencies = named.values.mapNotNull { it?.agencyId }.distinct()
                .filter { it !in modes && it !in extraModes }
            if (agencies.isNotEmpty()) extraModes = extraModes + withContext(Dispatchers.IO) {
                agencies.associateWith { runCatching { Moovit.agencyRouteType(s, it) }.getOrDefault(3) }
            }
        }
    }
    val info = lines + extraLines
    val kinds = modes + extraModes
    val rows = remember(due, info, kinds, stop) {
        due.map { a ->
            val line = info[a.lineId]
            Tracked(a, stop, line, a.lineId in info, kinds[line?.agencyId ?: -1] ?: 3)
        }
    }
    val moving = remember(due) { due.count { it.hasLocation } }
    val ctx = LocalContext.current
    // Moovit lists nothing at a stop where every line ends, which is not the same as nothing due.
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
            StopGlyphOrPhoto(stopId, null, thumb = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stopName(stop?.name ?: named[stopId]?.name), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rows.isEmpty() && endsHere) T("No departures · every line ends here", "אין יציאות · כל הקווים מסתיימים כאן")
                    else if (rows.isEmpty()) T("Nothing due right now", "אין יציאות כרגע")
                    else T("${rows.size} due · $moving reporting a position", "${rows.size} יציאות · $moving מדווחות מיקום"),
                    style = Mono, fontSize = 11.sp, color = K.dim,
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl))
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
                            v.line?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") }
                                ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                            fontSize = 14.sp, color = K.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (v.arrival.platform.isNotBlank()) {
                            Spacer(Modifier.height(K.gap1))
                            PlatformTag(v.arrival.platform)
                        }
                    }
                    if (live) LiveGlyph(if (v.arrival.vehicleStatus == 2) K.problem else K.realtime, 11.dp)
                    Text(
                        whenLabel(v.eta, now), style = Mono, fontSize = 13.sp,
                        color = if (live) K.text else K.scheduled,
                    )
                    Text(T.onward, fontSize = 22.sp, color = K.dim)
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
