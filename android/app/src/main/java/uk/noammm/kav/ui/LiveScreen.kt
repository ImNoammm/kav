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
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.requestLocationOnce
import kotlin.math.abs
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

    // A fix for Moovit just arrived: the next call tries at once rather than waiting out earlier failures.
    fun retryNow() { failures = 0 }

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

private const val LOOK_REACH_KM = 3.5

// What the Live tab showed last, so opening it again shows the buses at once while they refresh.
private object LiveMemory {
    var savedAt = 0L
    var centre = 0.0 to 0.0
    var near: List<Moovit.Stop> = emptyList()
    var arrivals: Map<Moovit.ArrivalKey, Moovit.Arrival> = emptyMap()
    var lines: Map<Int, Moovit.LineInfo?> = emptyMap()
    var modes: Map<Int, Int> = emptyMap()
    var pollSecs = 20
    // When Moovit was last asked about all of them, and which, so coming straight back keeps to its interval.
    var roundAt = 0L
    var asked: Set<Int> = emptySet()

    // From Kav's server: every bus around, as last asked.
    var area: KavLive.Area? = null
    var areaAt = 0L
    var areaCentre = 0.0 to 0.0

    fun recentArea(here: Pair<Double, Double>) = area?.takeIf {
        System.currentTimeMillis() / 1000 - areaAt < 180 && metres(here.first, here.second, areaCentre.first, areaCentre.second) < 500
    }

    fun recent(here: Pair<Double, Double>) = arrivals.isNotEmpty() &&
        System.currentTimeMillis() / 1000 - savedAt < 180 && metres(here.first, here.second, centre.first, centre.second) < 500

    // The stops around are kept on the phone as well: after a restart Moovit is asked about them while the
    // timetable that finds them is still loading.
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "live-stops.json")

    fun keepStops(ctx: android.content.Context) = runCatching {
        val list = org.json.JSONArray()
        for (st in near) list.put(org.json.JSONArray().put(st.id).put(st.lat).put(st.lon).put(st.name))
        file(ctx).writeText(org.json.JSONObject().put("lat", centre.first).put("lon", centre.second).put("stops", list).toString())
    }

    fun keptStops(ctx: android.content.Context, here: Pair<Double, Double>): List<Moovit.Stop> = runCatching {
        val o = org.json.JSONObject(file(ctx).readText())
        if (metres(here.first, here.second, o.getDouble("lat"), o.getDouble("lon")) > 500) return emptyList()
        val list = o.getJSONArray("stops")
        (0 until list.length()).map { i ->
            val st = list.getJSONArray(i)
            Moovit.Stop(st.getInt(0), st.getDouble(1), st.getDouble(2), st.optString(3))
        }
    }.getOrDefault(emptyList())
}

// Moovit now and then answers without its live layer. Buses seen a moment ago stay where they were until a proper answer,
// with the new times.
private fun steadied(
    answer: Map<Moovit.ArrivalKey, Moovit.Arrival>, shown: Map<Moovit.ArrivalKey, Moovit.Arrival>, stops: Set<Int>,
): Map<Moovit.ArrivalKey, Moovit.Arrival> {
    val now = System.currentTimeMillis() / 1000
    val stripped = answer.values.none { it.hasLocation } &&
        shown.values.any { it.stopId in stops && it.hasLocation && now - it.sampleUtc < 90 }
    if (!stripped) return answer
    return answer.mapValues { (key, a) -> shown[key]?.takeIf { it.hasLocation }?.let { a.withVehicleOf(it) } ?: a }
}

private fun busCount(n: Int) =
    if (n == 0) T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב") else T("$n live vehicles", "$n כלי רכב בזמן אמת")

private fun liveCount(arrivals: Collection<Moovit.Arrival>, stops: Int): String {
    val tracked = arrivals.filter { it.hasLocation }.map { it.tripId }.distinct().size
    return if (tracked == 0) T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב")
        else T("$tracked live vehicles · $stops stops", "$tracked כלי רכב בזמן אמת · $stops תחנות")
}

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
    // With Kav's server chosen, the buses and arrivals here come from it and the stops from Kav's timetable. Moovit isn't
    // asked anything.
    val fromServer = KavLive.enabled
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
    val remembered = remember { LiveMemory.takeIf { !fromServer && it.recent(here) } }
    var area by remember { mutableStateOf(if (fromServer) LiveMemory.recentArea(here) else null) }
    var board by remember { mutableStateOf<Pair<Int, KavLive.Board>?>(null) }
    var status by remember {
        mutableStateOf(when {
            area != null -> busCount(area!!.buses.size)
            remembered != null -> liveCount(remembered.arrivals.values, remembered.near.size)
            fromServer -> T("connecting…", "מתחברים…")
            else -> T("connecting to Moovit…", "מתחברים ל-Moovit…")
        })
    }
    var loading by remember { mutableStateOf(remembered == null && area == null) }
    var arrivals by remember { mutableStateOf(remembered?.arrivals ?: emptyMap()) }
    var near by remember { mutableStateOf(remembered?.near ?: LiveMemory.keptStops(ctx, here)) }
    var unmatched by remember { mutableStateOf<List<Int>>(emptyList()) }
    var lines by remember { mutableStateOf(remembered?.lines ?: emptyMap()) }
    var modes by remember { mutableStateOf(remembered?.modes ?: emptyMap()) }
    var pollSecs by remember { mutableIntStateOf(remembered?.pollSecs ?: 20) }
    var focus by remember { mutableStateOf<LiveFocus?>(null) }
    var picked by remember { mutableStateOf<Tracked?>(null) }
    var farStop by remember { mutableStateOf<Moovit.Stop?>(null) }
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

    // Moovit's stop list misses about half the stops, so they come from the timetable.
    LaunchedEffect(look?.first ?: stopsAt, look?.second, fromServer) {
        val net = model.net ?: runCatching { loadNet(ctx) }.getOrNull()?.also { model.net = it }
            ?: return@LaunchedEffect
        val (at, reach) = look ?: (stopsAt to 1.5)
        val inView = withContext(Dispatchers.Default) {
            net.nearestStops(at.first, at.second, k = 120, radius = reach.coerceIn(0.6, LOOK_REACH_KM) * 1000)
                .map { it.first }
        }
        // The timetable's own stops, by their place in it: nothing to look up anywhere.
        if (fromServer) {
            near = inView.map { g -> Moovit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
            unmatched = emptyList(); matching = 0 to 0
            return@LaunchedEffect
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

    if (!fromServer) LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Moovit says how often to ask. Each stop is asked once a round: all of them every pollSecs, and a stop that
            // turns up in between on its own.
            var roundAt = remembered?.roundAt ?: 0L
            var asked = remembered?.asked ?: emptySet()
            while (coroutineContext.isActive) {
                val ids = (near.map { it.id } + listOfNotNull(farStop?.id)).distinct()
                if (ids.isEmpty()) {
                    if (matching.second > 0 || model.net == null) status = T("loading stops…", "טוענים תחנות…")
                    wake.receive()
                    continue
                }
                val due = System.currentTimeMillis() - roundAt >= pollSecs * 1000L
                val newcomers = ids.filter { it !in asked }
                val ask = when {
                    // Moovit answers a few stops in a fraction of the time all of them take: the nearest come first.
                    due && arrivals.isEmpty() && ids.size > 15 -> ids.take(15)
                    due -> ids
                    newcomers.isNotEmpty() -> newcomers
                    else -> null
                }
                if (ask == null) {
                    kotlinx.coroutines.withTimeoutOrNull(roundAt + pollSecs * 1000L - System.currentTimeMillis()) { wake.receive() }
                    continue
                }
                try {
                    val s = Online.open(here)
                    val (answer, poll) = withContext(Dispatchers.IO) { Moovit.stopArrivals(s, ask) }
                    val askSet = ask.toSet()
                    if (due) { roundAt = System.currentTimeMillis(); asked = askSet } else asked = asked + askSet
                    if (look == null) { LiveMemory.roundAt = roundAt; LiveMemory.asked = asked }
                    val found = steadied(answer, arrivals, askSet)
                    arrivals = if (due && ask.size == ids.size) found else arrivals.filterKeys { it.stopId !in askSet } + found
                    pollSecs = poll.coerceIn(10, 60); loading = false
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
                    status = liveCount(arrivals.values, asked.size)
                    val missing = tracked.sortedBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
                        .map { it.lineId }.distinct().filter { it !in lines }
                    // A line that didn't load stays missing and is asked again on the next refresh.
                    for (batch in missing.chunked(8)) {
                        val named = withContext(Dispatchers.IO) {
                            batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
                        }
                        lines = lines + named.filterValues { it != null }
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
                    delay(10_000)
                }

            }
        }
    }

    // Every bus in the box around where the map looks, asked again when the server has a newer snapshot.
    if (fromServer) LaunchedEffect(lifecycle, look?.first ?: stopsAt, look?.second) {
        val (at, reach) = look ?: (stopsAt to 1.5)
        val box = KavLive.box(at.first, at.second, reach.coerceIn(0.6, LOOK_REACH_KM))
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val wait = try {
                    val answer = withContext(Dispatchers.IO) { KavLive.area(box) }
                    area = answer; loading = false
                    if (look == null) {
                        LiveMemory.area = answer; LiveMemory.areaCentre = stopsAt
                        LiveMemory.areaAt = System.currentTimeMillis() / 1000
                    }
                    answer.next - System.currentTimeMillis() / 1000
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loading = false
                    status = T("Could not refresh · retrying shortly", "לא ניתן היה לרענן · ננסה שוב בקרוב")
                    15L
                }
                delay(wait.coerceIn(5, 30) * 1000L)
            }
        }
    }
    // As Moovit's map does, the stops in view are asked about every round. The ministry answers for them live, a minute
    // or so fresher than its snapshot of every bus, which fills in the rest.
    var stopBuses by remember { mutableStateOf<Map<String, KavLive.Bus>>(emptyMap()) }
    var stopLines by remember { mutableStateOf<Map<Int, KavLive.Line>>(emptyMap()) }
    if (fromServer) LaunchedEffect(lifecycle, near) {
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
    val boardStop = (focus as? LiveFocus.Stop)?.id?.takeIf { fromServer && it < 0 }
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
        if (fromServer) area?.buses?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
            ?: board?.second?.due?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
        else arrivals.values.firstOrNull { it.tripId == f.tripId && it.hasLocation }?.let { KavLive.routeOf(it.vehicleId) }
    }
    val followed = rememberKavSpots(listOfNotNull(followedRoute))
    val seen = remember(arrivals, followed) { KavLive.fresher(arrivals, followed) }

    // Only the view around you is kept: a place looked at by hand is somewhere else next time.
    LaunchedEffect(arrivals, lines, modes) {
        if (arrivals.isEmpty() || look != null) return@LaunchedEffect
        val moved = LiveMemory.near != near || LiveMemory.centre != stopsAt
        LiveMemory.savedAt = System.currentTimeMillis() / 1000
        LiveMemory.centre = stopsAt
        LiveMemory.near = near; LiveMemory.arrivals = arrivals; LiveMemory.lines = lines; LiveMemory.modes = modes
        LiveMemory.pollSecs = pollSecs
        if (moved) withContext(Dispatchers.IO) { LiveMemory.keepStops(ctx.applicationContext) }
    }

    val stopsById = remember(near, farStop) { (near + listOfNotNull(farStop)).associateBy { it.id } }
    val vehicles = remember(seen, lines, modes, stopsById, area, stopBuses, stopLines, followed, fromServer) {
        if (fromServer) {
            // Each bus where it was seen last, from the snapshot or the stops in view. Nearest first: there's no stop
            // of yours to time them at.
            val known = area?.lines.orEmpty() + stopLines
            (area?.buses.orEmpty() + stopBuses.values).groupBy { it.key }.values.map { same -> same.maxBy { it.spot.at } }
                .map { bus ->
                    val line = known[bus.route]
                    Tracked(KavLive.fresher(KavLive.busArrival(bus), followed), null, KavLive.lineInfo(line, ""), true, line?.type ?: 3)
                }.sortedBy { metres(stopsAt.first, stopsAt.second, it.arrival.lat, it.arrival.lon) }
        } else seen.values.filter { it.hasLocation }.groupBy { it.tripId }.values.map { at ->
            val first = at.minBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
            val line = lines[first.lineId]
            Tracked(first, stopsById[first.stopId], line, first.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }.sortedBy { it.eta }
    }
    if (fromServer) LaunchedEffect(vehicles.size, area != null || stopBuses.isNotEmpty()) {
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
    LaunchedEffect(farStop?.id) {
        val f = farStop?.takeIf { !fromServer } ?: return@LaunchedEffect
        val s = runCatching { Online.open() }.getOrNull() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { Moovit.stopArrivals(s, listOf(f.id)).first } }
            .onSuccess { found -> arrivals = arrivals + found }
    }
    val focusVehicle = (focus as? LiveFocus.Vehicle)?.let { f ->
        vehicles.firstOrNull { it.tripId == f.tripId } ?: picked?.takeIf { it.tripId == f.tripId }?.let { p ->
            seen[p.arrival.key]?.let { Tracked(it, p.stop, p.line, p.looked, p.routeType) } ?: p
        }
    }
    val patternId = focusVehicle?.arrival?.patternId ?: -1
    val pattern by produceState(emptyList<Int>(), patternId) {
        value = emptyList()
        if (patternId <= 0) return@produceState
        val s = runCatching { Online.open() }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Moovit.tripPattern(s, patternId) }.getOrDefault(emptyList())
        }
    }
    val held = remember(pattern) { pattern.mapNotNull { stopsById[it] }.associateBy { it.id } }
    val asked = rememberStopNames(pattern.filter { it !in held })
    val patternStops = remember(pattern, held, asked) {
        pattern.mapNotNull { id ->
            held[id] ?: asked[id]?.let { info -> info.point?.let { (lat, lon) -> Moovit.Stop(id, lat, lon, info.name) } }
        }
    }
    // From Kav's server a bus's stops are those of its trip in Kav's own timetable, looked up again as soon as what it
    // takes is in: the timetable, and the bus's line from the snapshot, the stops in view or the stop that's open.
    val tripKey = focusVehicle?.arrival?.vehicleId?.takeIf { fromServer }.orEmpty()
    val tripLine = KavLive.routeOf(tripKey)?.let { route ->
        area?.lines?.get(route) ?: stopLines[route] ?: board?.second?.lines?.get(route)
    }
    val tripNet = model.net ?: uk.noammm.kav.Loaded.net
    val tripStops by produceState(emptyList<Moovit.Stop>(), tripKey, tripLine != null, tripNet != null) {
        value = emptyList()
        val a = focusVehicle?.arrival ?: return@produceState
        val first = KavLive.firstOf(tripKey) ?: return@produceState
        val net = tripNet ?: return@produceState
        val line = tripLine ?: return@produceState
        value = withContext(Dispatchers.Default) {
            KavLive.tripStops(net, line.number, line.name, first, a.lat, a.lon)
                .map { g -> Moovit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
        }
    }
    val lineStops = if (fromServer) tripStops else patternStops
    // From Kav's server the road comes from the ministry's timetable, by the bus's route.
    val moovitRoute = rememberLineRoute(focusVehicle?.arrival?.tripShapeId ?: -1)
    val serverRoute = rememberKavShape(focusVehicle?.arrival?.vehicleId?.takeIf { fromServer }?.let(KavLive::routeOf) ?: -1)
    val route = if (fromServer) serverRoute else moovitRoute
    // How far along its stops the focused bus is, for the rail on its card.
    val focusBus = focusVehicle?.arrival?.takeIf { it.hasLocation }
    val progress = remember(lineStops, route, focusBus?.lat, focusBus?.lon) { busProgress(lineStops, route, focusBus) }
    val liveLiquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0.dp) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Live", "כלי רכב בזמן אמת"), "")
        Text(
            if (fromServer) T(
                "Online mode: buses and times come straight from the Ministry of Transport through Kav's server, about every 15s.",
                "מצב מקוון: האוטובוסים והזמנים מגיעים ישירות ממשרד התחבורה דרך השרת של Kav, בערך כל 15 שניות.",
            ) else T(
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
                            is LiveFocus.Stop -> if (fromServer) {
                                // The ministry's arrivals at the stop, in the shape the card takes.
                                val b = board?.takeIf { it.first == f.id }?.second
                                val due = remember(b) {
                                    b?.due.orEmpty().associate { d -> Moovit.ArrivalKey(f.id, KavLive.tripIdOf(d.key)) to KavLive.dueArrival(d, f.id) }
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
                            } else LiveStopCard(
                                stopsById[f.id] ?: farStop?.takeIf { it.id == f.id }, f.id, seen, lines, modes, now,
                                onVehicle = { v -> picked = v; focus = LiveFocus.Vehicle(v.tripId, from = f.id) },
                                onClose = { focus = null },
                            )
                            is LiveFocus.Vehicle -> LiveVehicleCard(
                                focusVehicle?.takeIf { it.tripId == f.tripId }, now, lineStops, progress,
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
                // One height whatever is below, so the map above never resizes with the list.
                1 -> Box(
                    Modifier.fillMaxWidth().height(260.dp + LocalBottomBarInset.current)
                        .padding(bottom = LocalBottomBarInset.current),
                    contentAlignment = Alignment.Center,
                ) { LoadingPulse(T("Finding vehicles", "מאתרים כלי רכב")) }
                else -> LiveList(vehicles, now, stopsAt, Modifier.fillMaxWidth()
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
    route: List<Pair<Double, Double>>,
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
private fun LiveList(vehicles: List<Tracked>, now: Long, from: Pair<Double, Double>, modifier: Modifier, onSelect: (Tracked) -> Unit) {
    if (vehicles.isEmpty()) {
        Box(modifier) {
            Note(
                T(
                    "Nothing tracked near you right now. Vehicles appear here as soon as one of your stops has a bus reporting its position.",
                    "אין כרגע כלי רכב במעקב בסביבתכם. כלי רכב יופיעו כאן ברגע שאחת התחנות שלכם תקבל דיווח מיקום מאוטובוס.",
                ),
                Modifier.padding(horizontal = K.gap4, vertical = K.gap3),
            )
        }
        return
    }
    val named = rememberStopNames(vehicles.filter { it.stop == null }.map { it.arrival.stopId })
    LazyColumn(modifier, contentPadding = PaddingValues(
        start = K.gap1, top = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
    )) {
        // The freshest positions first: green, then yellow, then red, each kept in its own order.
        items(vehicles.sortedBy { freshnessOf(it.arrival.sampleUtc, now).ordinal }, key = { it.tripId }) { v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            val tint = if (a.vehicleStatus == 2) K.problem else freshColour(freshnessOf(a.sampleUtc, now))
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
                        if (a.stopId < 0 && v.stop == null) {
                            val far = distanceLabel(metres(from.first, from.second, a.lat, a.lon))
                            T("$far from you", "$far ממך")
                        } else "${stopName(v.stop?.name ?: named[a.stopId]?.name)} · ${whenLabel(v.eta, now)}",
                        fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveGlyph(tint, 11.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(T("${ageS}s ago", "לפני ${ageS} שנ׳"), style = Mono, fontSize = 11.sp, color = tint)
                }
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
private fun LiveVehicleCard(v: Tracked?, now: Long, stops: List<Moovit.Stop>, progress: Float, onClose: () -> Unit) {
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
            else -> locationHeadline(a.sampleUtc, now)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.hasLocation) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
        }
        if (a.sampleUtc > 0) Text(
            updatedLabel(a),
            fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
        )
        Spacer(Modifier.height(K.gap3))
        KavLive.plateOf(a.vehicleId)?.let { LiveFact(T("Number plate", "מספר רישוי"), it) }
        if (a.platform.isNotBlank()) LiveFact(T("Platform", "רציף"), a.platform)
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
private fun BusRail(stops: List<Moovit.Stop>, progress: Float) {
    val from = (progress.toInt() - 1).coerceAtLeast(0)
    val ahead = remember(stops, from) { stops.drop(from) }
    val ids = remember(ahead) { ahead.map { it.id } }
    val names = remember(ahead) { ahead.associate { it.id to Moovit.StopInfo(it.id, it.name, "") } }
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
private fun busProgress(stops: List<Moovit.Stop>, route: List<Pair<Double, Double>>, bus: Moovit.Arrival?): Float {
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
    // A line that didn't load is asked again, less and less often, while the card is open.
    LaunchedEffect(lineIds) {
        var wait = 2_000L
        while (true) {
            val missing = lineIds.filter { it !in lines && it !in extraLines }
            if (missing.isEmpty()) break
            runCatching { Online.open() }.getOrNull()?.let { s ->
                for (batch in missing.chunked(8)) {
                    val named = withContext(Dispatchers.IO) {
                        batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
                    }
                    extraLines = extraLines + named.filterValues { it != null }
                    val agencies = named.values.mapNotNull { it?.agencyId }.distinct()
                        .filter { it !in modes && it !in extraModes }
                    if (agencies.isNotEmpty()) extraModes = extraModes + withContext(Dispatchers.IO) {
                        agencies.associateWith { runCatching { Moovit.agencyRouteType(s, it) }.getOrDefault(3) }
                    }
                }
            }
            if (lineIds.all { it in lines || it in extraLines }) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
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
                    if (live) LiveGlyph(
                        if (v.arrival.vehicleStatus == 2) K.problem else freshColour(freshnessOf(v.arrival.sampleUtc, now)), 11.dp,
                    )
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
