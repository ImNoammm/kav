package uk.noammm.kav

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Updates
import uk.noammm.kav.ui.*

enum class Tab {
    Directions, Stations, Lines, Live;

    /** Computed rather than held: an enum constant is built once, and a label stored
     *  in it would freeze at whichever language happened to be loaded first. */
    val label: String get() = when (this) {
        Directions -> T("Home", "בית")
        Stations -> T("Stations", "תחנות")
        Lines -> T("Lines", "קווים")
        Live -> T("Live", "בזמן אמת")
    }
}

/** Parsing the national bundle costs ~1 s and ~110 MB; an activity restart must
 *  not pay it twice. */
object Loaded {
    @Volatile private var net_: Net? = null

    val net: Net? get() = net_

    fun store(net: Net) { net_ = net }

    fun clear() { net_ = null }
}

/** Whether the activity is showing as a small window over other apps, and whether it should. */
object Pip {
    var active by mutableStateOf(false)
    var wanted by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        K.accent = Color(Prefs.accent(this))
        MapFile.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addOnPictureInPictureModeChangedListener { Pip.active = it.isInPictureInPictureMode }
        }
        T.lang = Prefs.lang(this)
        setContent {
            KavTheme {
                // Hebrew is read right to left, and Compose mirrors a whole tree from
                // this one local: rows reverse, start/end padding swaps sides, text
                // aligns to the right. The system locale cannot drive it here because
                // Kav's language is its own setting, not the phone's.
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { LanguageSwitch { Root() } }
            }
        }
    }

    /**
     * A trip being navigated keeps a small window over whatever you switch to. On
     * Android 12 and later the system enters it on its own from the params below; older
     * versions are told on the way out.
     */
    fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(Pip.wanted).setSeamlessResizeEnabled(false)
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1)).build()) }
        }
    }
}

/* model */

data class ActiveJourney(
    val trip: Moovit.Itinerary,
    val resolved: Moovit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    /** Which line the rider is taking where a leg offers several: ride leg index → option. */
    val chosen: Map<Int, Int> = emptyMap(),
)

/**
 * A trip the rider actually took: its endpoints, and the route it went by.
 *
 * The times are not kept, they are stale the moment the trip ends, so offering one
 * back means planning it again for now. The route is worth keeping: someone who takes
 * the 472 into town wants the 472 again, not whatever the server happens to rank first
 * this morning. `lines` is each ride leg's line id in order, and `group` separates a
 * transit route from a taxi or a walk, which have no ride legs to name. Both together
 * identify the route without pinning any of its times. Empty `lines` and a negative
 * `group` mean an entry saved before routes were recorded.
 */
data class RecentTrip(
    val from: Moovit.Place?,
    val to: Moovit.Place,
    val at: Long,
    val lines: List<Int> = emptyList(),
    val group: Int = -1,
)

class KavModel(net: Net? = null, ctx: Context? = null) : ViewModel() {
    /**
     * The offline timetable. Null until something actually needs it: parsing the
     * national bundle costs about a second and a hundred megabytes, and paying that
     * at every launch, for a fallback that only matters with no signal, is what
     * made the app stutter before it had shown anything.
     */
    var net by mutableStateOf(net)
    var netLoading by mutableStateOf(false)
    var netError by mutableStateOf<String?>(null)
    var netLoadAttempt by mutableIntStateOf(0)

    var tab by mutableStateOf(Tab.Directions)
    var settingsOpen by mutableStateOf(false)
    var activeJourney by mutableStateOf<ActiveJourney?>(null)
    var returnHome by mutableStateOf(false)

    /** A trip being navigated owns the whole screen; the tabs step aside for it. */
    var navigating by mutableStateOf(false)

    /**
     * How far along the active journey the rider is, as an index into its steps. Moved
     * by the clock, by the live vehicle and by the phone's own position, never by
     * swiping the cards, which is reading ahead, not travelling.
     */
    var journeyStep by mutableIntStateOf(0)

    /**
     * A place handed to Directions from another tab ("Start here" on a station).
     * Directions is online, so it works in coordinates rather than the offline
     * timetable's stop ids, passing one of those across did nothing at all before.
     */
    var pendingFrom by mutableStateOf<Moovit.Place?>(null)
    var pendingTo by mutableStateOf<Moovit.Place?>(null)
    /** A favourite the home strip asked to place: the picker opens straight into setting it. */
    var settingFavourite by mutableStateOf<uk.noammm.kav.ui.Favourite?>(null)

    /**
     * The rider's favourite places, one source for every screen that shows them: the
     * home strip and the search strip both read and write this, so a place set in one
     * shows up in the other at once rather than after the tab is rebuilt.
     */
    var favourites by mutableStateOf(ctx?.let { Prefs.favourites(it) } ?: emptyList())
    fun saveFavourites(ctx: Context, list: List<uk.noammm.kav.ui.Favourite>) {
        favourites = list
        Prefs.saveFavourites(ctx, list)
    }

    // stations / lines
    var stationStop by mutableIntStateOf(-1)
    var lineRoute by mutableIntStateOf(-1)

    /**
     * What the rider last typed into each search box, kept here rather than in the
     * list that draws it. Opening a stop or a line swaps the screen's AnimatedContent
     * branch, which disposes that list and everything remembered inside it, so coming
     * back after one tap used to land on an empty box and the whole nearby list again.
     * The picker's own query is cleared once a place is actually picked: that search
     * is finished, the other two are still where the rider was looking.
     */
    var stopQuery by mutableStateOf("")
    var lineQuery by mutableStateOf("")
    var placeQuery by mutableStateOf("")

    /** Where the phone is: the latest fix, and the same point for screens that only want a point. */
    var fix by mutableStateOf<Fix?>(null)
    var here by mutableStateOf<Pair<Double, Double>?>(null)
    /** Which way the phone is facing, in degrees from north, while a trip is navigated. */
    var heading by mutableStateOf<Float?>(null)

    /** What a plan may contain; kept by Prefs, applied to every request. */
    var filters by mutableStateOf(ctx?.let { Prefs.filters(it) } ?: ResultFilter.entries.toSet())
        private set

    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        filters = if (on) filters + f else filters - f
        Prefs.setFilter(ctx, f, on)
    }

    fun locate(lat: Double, lon: Double, speed: Float = 0f) {
        here = lat to lon
        fix = Fix(lat, lon, System.currentTimeMillis() / 1000, speed)
    }

    /** A newer release on GitHub than the one running, once checked this launch. */
    var update by mutableStateOf<Updates.Release?>(null)
    var updateDismissed by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateError by mutableStateOf<String?>(null)
    var updateChecked by mutableStateOf(false)

    suspend fun checkForUpdate(ctx: Context) {
        try {
            val installed = Updates.installedVersion(ctx)
            val latest = withContext(Dispatchers.IO) { Updates.latest() }
            update = latest.takeIf { Updates.isNewer(it.version, installed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("KavUpdate", "update check failed", e)
        } finally {
            updateChecked = true
        }
    }

    /** Fetch the release's APK and hand it to the system installer. */
    suspend fun installUpdate(ctx: Context) {
        val release = update ?: return
        if (!Updates.canInstall(ctx)) { Updates.askInstallPermission(ctx); return }
        updateError = null
        updateProgress = 0f
        try {
            val file = withContext(Dispatchers.IO) {
                Updates.download(ctx, release) { done, total ->
                    updateProgress = if (total > 0) done.toFloat() / total else 0f
                }
            }
            updateProgress = 1f
            Updates.install(ctx, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("KavUpdate", "update failed", e)
            updateError = e.message ?: e.javaClass.simpleName
        } finally {
            updateProgress = null
        }
    }
}

/* root: first launch, then the shell */

private val netLoadMutex = Mutex()

/** Parse on demand, sharing one parse between every screen that browses it. */
suspend fun loadNet(ctx: Context): Net = withContext(Dispatchers.Default) {
    netLoadMutex.withLock {
        Loaded.net ?: run {
            val n = ctx.assets.open("il.kav").use { Net.read(it) }
            // Net.read is synchronous: keep its completed result even if its
            // original caller left, so the next caller need not parse it again.
            Loaded.store(n)
            n
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var onboarded by remember { mutableStateOf(Prefs.onboarded(ctx)) }
    if (!onboarded) {
        OnboardingScreen { Prefs.setOnboarded(ctx); onboarded = true }
        return
    }
    val app = ctx.applicationContext
    val model: KavModel = viewModel { KavModel(Loaded.net, app) }
    LaunchedEffect(model) { if (!model.updateChecked) model.checkForUpdate(app) }
    Box(Modifier.fillMaxSize()) {
        Shell(model)
        if (Pip.active) PipOverlay(model)
        else {
            UpdatePrompt(model)
            // the map's offer waits its turn behind an update's
            if (model.update == null || model.updateDismissed) MapPrompt()
        }
    }
}

@Composable
private fun Shell(model: KavModel) {
    val ctx = LocalContext.current
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
    }
    // Ask once at startup so nearby stops / live vehicles work without hunting for a
    // button, and ask for BOTH, because a coarse-only grant is fuzzed to a grid cell
    // a kilometre or more across, which plans your trip from the wrong town.
    LaunchedEffect(Unit) {
        if (!hasPreciseLocation(ctx)) askLocation.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    // And ask AGAIN on every resume until there is a fix. requestLocationOnce gives up
    // immediately when every provider is switched off, so opening Kav with location off
    // used to leave "My location" missing for the rest of the process: turning location
    // on changed nothing, because nothing asked a second time. Coming back from the
    // system toggle is a resume, and this is what notices.
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(shellLifecycle) {
        shellLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (model.here == null && hasLocationPermission(ctx)) {
                requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            }
        }
    }

    // The shell outlives both browser tabs. collect (not collectLatest) lets a
    // parse finish through rapid switches; a real failure waits for Retry.
    LaunchedEffect(model) {
        snapshotFlow { model.tab to model.netLoadAttempt }.collect { (tab, _) ->
            if (tab != Tab.Stations && tab != Tab.Lines) return@collect
            if (model.net != null || model.netLoading || model.netError != null) return@collect
            model.netLoading = true
            try {
                model.net = loadNet(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.netError = e.message ?: e.javaClass.simpleName
            } finally {
                model.netLoading = false
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, model.activeJourney?.trip, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val journey = model.activeJourney ?: break
                val session = Online.session ?: break
                val needsNames = journey.trip.rides.flatMap { it.options }.any { ride ->
                    (ride.lineId > 0 && journey.resolved.line(ride.lineId) == null) ||
                        (ride.fromStop > 0 && journey.resolved.stop(ride.fromStop) == null) ||
                        (ride.toStop > 0 && journey.resolved.stop(ride.toStop) == null)
                }
                if (needsNames) {
                    val names = withContext(Dispatchers.IO) { Moovit.hydrate(session, listOf(journey.trip)) }
                    model.activeJourney?.takeIf { it.trip == journey.trip }?.let { current ->
                        val previous = current.resolved
                        model.activeJourney = current.copy(resolved = Moovit.Resolved(
                            previous.lines + names.lines, previous.stops + names.stops,
                            previous.routeTypes + names.routeTypes, previous.live + names.live,
                            previous.shapes + names.shapes, names.pollSecs, previous.patterns + names.patterns,
                        ))
                    }
                }
                delay(journey.resolved.pollSecs.coerceIn(15, 120) * 1000L)
                val current = model.activeJourney?.takeIf { it.trip == journey.trip } ?: break
                val refreshed = withContext(Dispatchers.IO) {
                    Moovit.refreshLive(session, listOf(current.trip), current.resolved)
                }
                model.activeJourney?.takeIf { it.trip == journey.trip }?.let {
                    model.activeJourney = it.copy(resolved = Moovit.Resolved(
                        refreshed.lines + it.resolved.lines, refreshed.stops + it.resolved.stops,
                        refreshed.routeTypes + it.resolved.routeTypes, refreshed.live,
                        refreshed.shapes + it.resolved.shapes, refreshed.pollSecs, refreshed.patterns,
                    ))
                }
            }
        }
    }

    // While a trip is under way the phone keeps its position current, in the
    // foreground and in the small window, never once the app is put away, and the
    // journey's step is worked out from it every couple of seconds.
    val journeyActive = model.activeJourney != null
    LaunchedEffect(journeyActive, lifecycle) {
        if (!journeyActive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stop = trackLocation(ctx) { model.locate(it.latitude, it.longitude, it.speed) }
            try { awaitCancellation() } finally { stop() }
        }
    }
    LaunchedEffect(model.activeJourney?.trip) {
        val journey = model.activeJourney ?: return@LaunchedEffect
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        while (true) {
            val current = model.activeJourney?.takeIf { it.trip === journey.trip } ?: break
            val next = journeyProgress(
                steps, model.journeyStep, current.resolved, current.chosen,
                System.currentTimeMillis() / 1000, model.fix,
            )
            if (next != model.journeyStep) model.journeyStep = next
            delay(2000)
        }
    }
    // The compass only matters while the map is following you.
    LaunchedEffect(model.navigating, lifecycle) {
        Pip.wanted = model.navigating
        (ctx as? MainActivity)?.updatePipParams()
        if (!model.navigating) { model.heading = null; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stop = trackHeading(ctx) { model.heading = it }
            try { awaitCancellation() } finally { stop() }
        }
    }

    // Back, anywhere the page itself has no use for it: another tab or Settings goes
    // to Home, and Home asks before leaving the app. Pages register their own handlers
    // after this one, so theirs win while they have something to close.
    var exitAsk by remember { mutableStateOf(false) }
    BackHandler(enabled = !model.navigating) {
        when {
            model.settingsOpen -> { model.settingsOpen = false; model.tab = Tab.Directions }
            model.tab != Tab.Directions -> {
                model.stationStop = -1; model.lineRoute = -1
                model.tab = Tab.Directions
            }
            else -> exitAsk = true
        }
    }
    if (exitAsk) ExitPrompt(onStay = { exitAsk = false }) {
        exitAsk = false
        (ctx as? ComponentActivity)?.finish()
    }

    val backdrop = remember { HazeState() }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
    Box(Modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize().haze(backdrop)) {
        drawRect(K.bg)
        drawRect(Brush.radialGradient(listOf(K.surface2, Color.Transparent),
            center = Offset(size.width * .95f, size.height * .05f), radius = size.width * 1.3f))
        drawRect(Brush.radialGradient(listOf(K.surface2, Color.Transparent),
            center = Offset(size.width * .05f, size.height * .78f), radius = size.width))
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(K.bg))
        },
        bottomBar = {
            // still reserve the system inset, or the navigate card would sit under
            // the gesture bar the moment the tabs went away
            if (model.navigating) Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.safeDrawing))
            else TabBar(model)
        },
    ) { pad ->
        val direction = LocalLayoutDirection.current
        CompositionLocalProvider(LocalBottomBarInset provides pad.calculateBottomPadding()) {
        // Pages draw behind the floating tabs; only their controls/scroll content avoid them.
        Box(Modifier.padding(
            start = pad.calculateStartPadding(direction), top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(direction),
        ).fillMaxSize().clipToBounds()) {
            androidx.compose.animation.Crossfade(model.tab, label = "tab") { tab ->
                when (tab) {
                    Tab.Directions -> DirectionsOnline(model)
                    Tab.Stations -> StationsScreen(model)
                    Tab.Lines -> LinesScreen(model)
                    Tab.Live -> LiveScreen(model)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                model.settingsOpen,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(240)) { it / 10 },
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
            ) {
                SettingsScreen(model) { model.settingsOpen = false }
            }
        }
        }
    }
    }
    }
}

/** "Exit Kav?", the last back press on Home, so a stray swipe cannot close the app. */
@Composable
private fun ExitPrompt(onStay: () -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Exit Kav?", "לצאת מ־Kav?"), fontSize = 19.sp, color = K.text, fontWeight = FontWeight.SemiBold)
            Text(T("A trip in progress is kept until you end it.", "נסיעה שמתבצעת נשמרת עד שתסיימו אותה."), fontSize = 14.sp, color = K.dim, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button, onClick = onStay),
                    contentAlignment = Alignment.Center,
                ) { Text(T("Stay", "השארות"), fontSize = 15.sp, color = K.text) }
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill)).background(K.accent)
                        .clickable(role = Role.Button, onClick = onExit),
                    contentAlignment = Alignment.Center,
                ) { Text(T("Exit", "יציאה"), fontSize = 15.sp, color = K.bg, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/* The three tabs: Directions, Stations, Lines. What is deliberately absent is
   the account, the upgrade prompt and the ad slots. */

@Composable
private fun TabBar(model: KavModel) {
    NavigationBar(modifier = Modifier.padding(horizontal = K.gap3, vertical = K.gap2).glassSurface(28.dp),
        containerColor = Color.Transparent, contentColor = K.muted, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            val on = model.tab == t
            NavigationBarItem(
                selected = on,
                onClick = {
                    // a tab tapped over Settings is a way out of Settings too
                    model.settingsOpen = false
                    if (on) { model.stationStop = -1; model.lineRoute = -1 }
                    if (t == Tab.Directions) model.returnHome = true
                    model.tab = t
                },
                icon = { TabGlyph(t, if (on) K.text else K.dim) },
                label = { Text(t.label, fontSize = 11.sp, color = if (on) K.text else K.dim) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = K.plateStrong,
                    selectedIconColor = K.text, unselectedIconColor = K.dim,
                    selectedTextColor = K.text, unselectedTextColor = K.dim,
                ),
            )
        }
    }
}

@Composable
private fun TabGlyph(tab: Tab, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height; val sw = w * 0.09f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
        when (tab) {
            // home
            Tab.Directions -> {
                line(.10f, .45f, .50f, .12f); line(.50f, .12f, .90f, .45f)
                line(.23f, .40f, .23f, .86f); line(.23f, .86f, .77f, .86f)
                line(.77f, .86f, .77f, .40f); line(.43f, .86f, .43f, .62f)
                line(.43f, .62f, .59f, .62f); line(.59f, .62f, .59f, .86f)
            }
            // a stop node on a post
            Tab.Stations -> {
                drawCircle(tint, w * .20f, Offset(w * .50f, h * .34f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                line(.50f, .54f, .50f, .86f); line(.32f, .86f, .68f, .86f)
            }
            // an index of lines, each with its own stop node
            Tab.Lines -> listOf(.22f, .50f, .78f).forEachIndexed { i, y ->
                drawCircle(tint, w * .085f, Offset(w * .18f, h * y))
                line(.34f, y, if (i == 1) .86f else .70f, y)
            }
            // a live signal: a dot with a broadcast ring
            Tab.Live -> {
                drawCircle(tint, w * .14f, Offset(w * .5f, h * .5f))
                drawCircle(tint, w * .30f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawCircle(tint, w * .46f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw * .7f))
            }
        }
    }
}

/* location: coarse, one shot, never stored, never sent
   Last-known alone is not enough. A provider only produces a fix once some
   client asks for updates, so on a phone that has not been located recently,
   and on any fresh emulator, getLastKnownLocation returns null forever and
   "Nearby stops" silently stays empty. Ask for one real update, and use the
   cached fix meanwhile if there is one. */

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** True only with the precise grant. Coarse alone is fuzzed to about a kilometre. */
fun hasPreciseLocation(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun lastKnownLocation(ctx: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(ctx)) return null
    return try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) { null }
}

/** Reports the cached fix immediately when there is one, then the first fresh
 *  fix. Unregisters after one update or 25 s, whichever comes first, nothing
 *  keeps listening in the background. */
fun requestLocationOnce(ctx: Context, onResult: (Pair<Double, Double>) -> Unit) {
    if (!hasLocationPermission(ctx)) return
    lastKnownLocation(ctx)?.let(onResult)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return

    var done = false
    var listener: LocationListener? = null
    fun stop() {
        if (done) return
        done = true
        listener?.let { runCatching { lm.removeUpdates(it) } }
    }
    // every method spelled out: LocationListener only gained default
    // implementations in API 30, and minSdk here is 26
    listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (done) return
            stop()
            onResult(location.latitude to location.longitude)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("required below API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    try {
        providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
    } catch (e: SecurityException) { stop(); return }
    Handler(Looper.getMainLooper()).postDelayed({ stop() }, 25_000)
}

/**
 * Continuous fixes for a trip under way: GPS every second or two metres, the network
 * provider as a stand-in indoors. Returns the call that stops listening; the caller
 * ties it to the screen being visible, so nothing follows you once the app is away.
 */
fun trackLocation(ctx: Context, onFix: (Location) -> Unit): () -> Unit {
    if (!hasLocationPermission(ctx)) return {}
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFix(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("required below API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    val wanted = listOf(LocationManager.GPS_PROVIDER to 1000L, LocationManager.NETWORK_PROVIDER to 4000L)
    var any = false
    for ((provider, interval) in wanted) {
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
        try {
            lm.requestLocationUpdates(provider, interval, 2f, listener, Looper.getMainLooper())
            any = true
        } catch (e: SecurityException) {}
    }
    if (!any) return {}
    lastKnownLocation(ctx)?.let { (lat, lon) -> onFix(Location("cached").apply { latitude = lat; longitude = lon }) }
    return { runCatching { lm.removeUpdates(listener) } }
}

/**
 * Which way the phone points, from the rotation vector, corrected for how the
 * screen is turned and smoothed so the map does not twitch with every step.
 */
fun trackHeading(ctx: Context, onHeading: (Float) -> Unit): () -> Unit {
    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return {}
    val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return {}
    val rotation = FloatArray(9); val remapped = FloatArray(9); val orientation = FloatArray(3)
    var smoothed: Float? = null
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.display?.rotation
                else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay?.rotation
            val (ax, ay) = when (display) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, ax, ay, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val raw = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            val previous = smoothed
            val next = if (previous == null) raw else {
                var delta = raw - previous
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                ((previous + delta * 0.25f) + 360f) % 360f
            }
            smoothed = next
            onHeading(next)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    return { sm.unregisterListener(listener) }
}

/** Is there actually a usable network? Used to tell "offline" apart from "the online
 *  call failed", which are different things and must not print the same sentence. */
fun hasNetwork(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val n = cm.activeNetwork
    val caps = n?.let { cm.getNetworkCapabilities(it) }
    caps != null &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (e: Exception) { false }

/* preferences */

object Prefs {
    private const val FILE = "kav"

    private fun store(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The places you have picked before. Moovit's search screen opens on these, and it
     * is the difference between typing your own street every morning and tapping it.
     * Kept on the device in the app's own preferences, nothing is synced, because
     * there is no account to sync it to.
     */
    private const val RECENTS = "recents"
    private const val MAX_RECENTS = 8

    fun recents(ctx: Context): List<Moovit.Place> = try {
        val raw = store(ctx).getString(RECENTS, "[]")
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            place(o)
        }
    } catch (e: Exception) { emptyList() }

    fun remember(ctx: Context, p: Moovit.Place) {
        val kept = (listOf(p) + recents(ctx))
            .distinctBy { "%.5f,%.5f".format(it.lat, it.lon) }
            .take(MAX_RECENTS)
        val arr = org.json.JSONArray()
        for (r in kept) arr.put(json(r))
        store(ctx).edit().putString(RECENTS, arr.toString()).apply()
    }

    /** The trips you have taken, newest first, what Home offers back. */
    private const val TRIPS = "trips"
    private const val MAX_TRIPS = 6

    private fun place(o: org.json.JSONObject) = Moovit.Place(
        o.optString("n"), o.optString("d"), o.optDouble("lat"), o.optDouble("lon"),
        o.optInt("t", 5), o.optInt("m", -1),
    )

    private fun json(p: Moovit.Place): org.json.JSONObject = org.json.JSONObject()
        .put("n", p.name).put("d", p.detail).put("lat", p.lat).put("lon", p.lon)
        .put("t", p.type).put("m", p.meters)

    fun trips(ctx: Context): List<RecentTrip> = try {
        val raw = store(ctx).getString(TRIPS, "[]")
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val to = o.optJSONObject("to") ?: return@mapNotNull null
            val lines = o.optJSONArray("lines")
            RecentTrip(
                o.optJSONObject("from")?.let { place(it) }, place(to), o.optLong("at"),
                lines = (0 until (lines?.length() ?: 0)).map { j -> lines!!.optInt(j) },
                group = o.optInt("group", -1),
            )
        }
    } catch (e: Exception) { emptyList() }

    /** One entry per pair of places, to four decimals, about eleven metres. */
    private fun tripKey(t: RecentTrip) =
        "%.4f,%.4f>%.4f,%.4f".format(t.from?.lat ?: 0.0, t.from?.lon ?: 0.0, t.to.lat, t.to.lon)

    private fun saveTrips(ctx: Context, trips: List<RecentTrip>) {
        val arr = org.json.JSONArray()
        for (t in trips) {
            arr.put(
                org.json.JSONObject()
                    .put("from", t.from?.let { json(it) }).put("to", json(t.to)).put("at", t.at)
                    .put("lines", org.json.JSONArray(t.lines)).put("group", t.group),
            )
        }
        store(ctx).edit().putString(TRIPS, arr.toString()).apply()
    }

    /** A null origin is "wherever I am", which is what it will mean next time too. */
    fun rememberTrip(
        ctx: Context,
        from: Moovit.Place?,
        to: Moovit.Place,
        at: Long,
        trip: Moovit.Itinerary? = null,
    ) {
        val fresh = RecentTrip(
            from, to, at,
            lines = trip?.rides?.map { it.lineId } ?: emptyList(),
            group = trip?.group ?: -1,
        )
        saveTrips(ctx, (listOf(fresh) + trips(ctx)).distinctBy(::tripKey).take(MAX_TRIPS))
    }

    /**
     * The rider picked a route for a trip they have taken before: remember that one as
     * the way they go, so the entry reopens it rather than today's top result. It only
     * updates an entry that is already there, reading a plan is not the same as having
     * taken the trip, and the list stays a list of trips taken.
     */
    fun noteTripRoute(ctx: Context, from: Moovit.Place?, to: Moovit.Place, trip: Moovit.Itinerary) {
        val want = tripKey(RecentTrip(from, to, 0L))
        val trips = trips(ctx)
        if (trips.none { tripKey(it) == want }) return
        saveTrips(
            ctx,
            trips.map {
                if (tripKey(it) != want) it
                else it.copy(lines = trip.rides.map { r -> r.lineId }, group = trip.group)
            },
        )
    }

    fun clearRecents(ctx: Context) = store(ctx).edit().remove(RECENTS).apply()

    /** The accent, as ARGB. */
    fun accent(ctx: Context): Int = store(ctx).getInt("accent", android.graphics.Color.rgb(0x9A, 0xBE, 0xFF))
    fun setAccent(ctx: Context, argb: Int) = store(ctx).edit().putInt("accent", argb).apply()

    /**
     * Kav's own language. Deliberately not the phone's: Moovit answers in Hebrew
     * whatever the phone is set to, so a rider with an English phone was reading
     * Hebrew stop names inside English sentences with no way to fix either half.
     */
    fun lang(ctx: Context): Lang =
        Lang.entries.firstOrNull { it.code == store(ctx).getString("lang", null) } ?: Lang.EN
    fun setLang(ctx: Context, lang: Lang) = store(ctx).edit().putString("lang", lang.code).apply()

    /** Set once the first-launch screens have been through. */
    fun onboarded(ctx: Context): Boolean = store(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = store(ctx).edit().putBoolean("onboarded", true).apply()

    /** The result filters switched OFF are what is stored, so a new filter starts on. */
    fun filters(ctx: Context): Set<ResultFilter> {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty()
        return ResultFilter.entries.filter { it.name !in off }.toSet()
    }
    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(f.name) else off.add(f.name)
        store(ctx).edit().putStringSet("filtersOff", off).apply()
    }

    /** Named places: Home is always first, the rest are the rider's own. */
    fun favourites(ctx: Context): List<Favourite> = try {
        val arr = org.json.JSONArray(store(ctx).getString("favourites", "[]"))
        val saved = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Favourite(
                o.optString("id"), o.optString("name"), o.optString("icon"),
                o.optJSONObject("place")?.let { place(it) },
            )
        }
        if (saved.any { it.id == Favourite.HOME }) saved else listOf(Favourite.home()) + saved
    } catch (e: Exception) { listOf(Favourite.home()) }

    fun saveFavourites(ctx: Context, list: List<Favourite>) {
        val arr = org.json.JSONArray()
        for (f in list) {
            arr.put(
                org.json.JSONObject().put("id", f.id).put("name", f.name).put("icon", f.icon)
                    .put("place", f.place?.let { json(it) }),
            )
        }
        store(ctx).edit().putString("favourites", arr.toString()).apply()
    }
}
