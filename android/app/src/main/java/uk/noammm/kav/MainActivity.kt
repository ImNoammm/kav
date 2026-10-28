package uk.noammm.kav

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitLink
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.RecentRoutes
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.data.Updates
import uk.noammm.kav.ui.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

enum class Tab {
    Directions, Stations, Lines, Live;

    val label: String get() = when (this) {
        Directions -> T("Home", "בית")
        Stations -> T("Stops", "תחנות")
        Lines -> T("Lines", "קווים")
        Live -> T("Live", "בזמן אמת")
    }
}

object Loaded {
    // State, so what's drawn from the timetable (a bus station's platforms) comes in once it has loaded.
    private var net_ by mutableStateOf<Net?>(null)

    val net: Net? get() = net_

    fun store(net: Net) { net_ = net }

    fun clear() { net_ = null }
}

object Pip {
    var active by mutableStateOf(false)
    var wanted by mutableStateOf(false)
    // The rider's setting: navigation goes on in a small window when Kav is left, or doesn't.
    var allowed by mutableStateOf(true)
}

object PendingLink {
    var plan by mutableStateOf<MoovitLink.Plan?>(null)
}

// A line alert's stop, opened on the Stations tab when the alert is tapped.
object PendingStop {
    var key by mutableStateOf<String?>(null)
}

object PendingBackup {
    var uri by mutableStateOf<android.net.Uri?>(null)

    fun offer(ctx: android.content.Context, uri: android.net.Uri?): Boolean {
        if (uri == null || !Backup.looksLikeBackup(ctx, uri)) return false
        this.uri = uri
        return true
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Openings.count++
        enableEdgeToEdge()
        Prefs.upgrade(this)
        K.accent = Color(Prefs.accent(this))
        K.applyTheme(Prefs.look(this))
        K.liquid = Prefs.liquidGlass(this)
        Shown.co2 = Prefs.showCo2(this)
        Shown.payments = Prefs.showPayments(this)
        Pip.allowed = Prefs.pipNavigation(this)
        StopPhotos.chosen = Prefs.moovitPhotos(this)
        Shown.twelveHour = Prefs.twelveHour(this)
        Moovit.shareLocation = !Prefs.privateSearch(this)
        KavLive.chosen = Prefs.liveFromKav(this)
        KavLive.server = Prefs.kavServer(this)
        KavLive.key = Prefs.kavServerKey(this)
        StopNames.english = Prefs.stopNamesEnglish(this)
        KavPlan.chosen = Prefs.planWithKav(this)
        MapFile.type = Prefs.mapType(this)
        MapFile.init(this)
        StopPhotos.init(this)
        Online.init(this)
        KavPlan.init(this)
        Payer.init(this)
        // The trip notification's QUICK PAY button follows the ride kept with Asshole mode.
        lifecycleScope.launch {
            snapshotFlow { Triple(Payer.pending, Payer.payingLater, Payer.paidLater) }.collect { TripService.repost() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addOnPictureInPictureModeChangedListener { Pip.active = it.isInPictureInPictureMode }
        }
        T.lang = Prefs.lang(this)
        // Restored, or reopened from Recents: the link or backup was already handled.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh && !PendingBackup.offer(this, intent?.data)) {
            MoovitLink.parse(intent?.dataString)?.let { PendingLink.plan = it }
        }
        if (fresh) intent?.getStringExtra(LineAlerts.EXTRA_STOP)?.let { PendingStop.key = it }
        // A force stop drops the line alerts' alarm.
        LineAlerts.reschedule(this)
        setContent {
            val light = K.light
            val bg = K.bg
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val bars = androidx.core.view.WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = light
                bars.isAppearanceLightNavigationBars = light
                // Before Android 10 the navigation bar keeps a light scrim behind dark-theme icons.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) window.navigationBarColor = bg.toArgb()
            }
            KavTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { LanguageSwitch { Root() } }
            }
        }
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(Pip.wanted).setSeamlessResizeEnabled(false)
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    // Back to the front from another app: an opening too (Home's "Where to?" types itself once per opening).
    override fun onRestart() {
        super.onRestart()
        Openings.count++
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1)).build()) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(LineAlerts.EXTRA_STOP)?.let { PendingStop.key = it; return }
        if (PendingBackup.offer(this, intent.data)) return
        MoovitLink.parse(intent.dataString)?.let { PendingLink.plan = it }
    }
}

data class ActiveJourney(
    val trip: Moovit.Itinerary,
    val resolved: Moovit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val chosen: Map<Int, Int> = emptyMap(),
    // Per ride leg, the key of the ticket bought for it from its card.
    val paid: Map<Int, String> = emptyMap(),
) {
    val paymentKey get() = trip.paymentKey
}

// A search that found routes, like Moovit's Recent Journeys. The routes themselves are in RecentRoutes.
data class RecentTrip(
    val from: Moovit.Place?,
    val to: Moovit.Place,
    val at: Long,
    val stopovers: List<Moovit.Place> = emptyList(),
)

// What Moovit is told about where the user is while private search is on.
enum class Seen { NONE, CITY, PLACE }

class KavModel(net: Net? = null, ctx: Context? = null) : ViewModel() {
    var net by mutableStateOf(net)
    var netLoading by mutableStateOf(false)
    var netError by mutableStateOf<String?>(null)
    var netLoadAttempt by mutableIntStateOf(0)

    var tab by mutableStateOf(Tab.Directions)
    var settingsOpen by mutableStateOf(false)
    var payOpen by mutableStateOf(false)
    var payHistoryOpen by mutableStateOf(false)
    // A bus ride's pay row in a trip asking Pay to scan for that ride.
    internal var payFor by mutableStateOf<uk.noammm.kav.ui.PayFor?>(null)
    // The bus being reported to the ministry, while its report is open.
    internal var report by mutableStateOf<uk.noammm.kav.ui.ReportFor?>(null)
    var activeJourney by mutableStateOf<ActiveJourney?>(null)
    var returnHome by mutableStateOf(false)

    var navigating by mutableStateOf(false)
    var liveShow by mutableStateOf(LiveShow.ALL)

    var journeyStep by mutableIntStateOf(0)

    var pendingFrom by mutableStateOf<Moovit.Place?>(null)
    var pendingTo by mutableStateOf<Moovit.Place?>(null)
    var pendingLink by mutableStateOf<MoovitLink.Plan?>(null)
    var settingFavourite by mutableStateOf<uk.noammm.kav.ui.Favourite?>(null)

    var favourites by mutableStateOf(ctx?.let { Prefs.favourites(it) } ?: emptyList())
    fun saveFavourites(ctx: Context, list: List<uk.noammm.kav.ui.Favourite>) {
        favourites = list
        Prefs.saveFavourites(ctx, list)
    }

    var savedStops by mutableStateOf(ctx?.let { Prefs.savedStops(it) } ?: emptyList())
    fun saveStops(ctx: Context, keys: List<String>) { savedStops = keys; Prefs.setSavedStops(ctx, keys) }
    var savedLines by mutableStateOf(ctx?.let { Prefs.savedLines(it) } ?: emptyList())
    fun saveLines(ctx: Context, keys: List<String>) { savedLines = keys; Prefs.setSavedLines(ctx, keys) }
    var lineAlerts by mutableStateOf(ctx?.let { Prefs.lineAlerts(it) } ?: emptyList())
    fun saveAlerts(ctx: Context, alerts: List<LineAlert>) {
        lineAlerts = alerts
        Prefs.setLineAlerts(ctx, alerts)
        LineAlerts.reschedule(ctx)
    }

    var stationStop by mutableIntStateOf(-1)
    var lineRoute by mutableIntStateOf(-1)
    // The stop to open on the line shown, when it was opened from a trip.
    var lineFocusStop by mutableIntStateOf(-1)
    var moovitLine by mutableStateOf<Moovit.LineGroup?>(null)

    var stopQuery by mutableStateOf("")
    var lineQuery by mutableStateOf("")
    var placeQuery by mutableStateOf("")

    var fix by mutableStateOf<Fix?>(null)
    var here by mutableStateOf<Pair<Double, Double>?>(null)
    private var hereAt by mutableLongStateOf(0L)
    var restored by mutableStateOf(false)
    var heading by mutableStateOf<Float?>(null)

    var filters by mutableStateOf(ctx?.let { Prefs.filters(it) } ?: ResultFilter.entries.toSet())
        private set

    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        filters = if (on) filters + f else filters - f
        Prefs.setFilter(ctx, f, on)
    }

    var prefsVersion by mutableIntStateOf(0)
        private set

    fun reloadPrefs(ctx: Context) {
        prefsVersion++
        favourites = Prefs.favourites(ctx)
        savedStops = Prefs.savedStops(ctx)
        savedLines = Prefs.savedLines(ctx)
        lineAlerts = Prefs.lineAlerts(ctx)
        LineAlerts.reschedule(ctx)
        filters = Prefs.filters(ctx)
        K.accent = Color(Prefs.accent(ctx))
        K.applyTheme(Prefs.look(ctx))
        K.liquid = Prefs.liquidGlass(ctx)
        Shown.co2 = Prefs.showCo2(ctx)
        Shown.payments = Prefs.showPayments(ctx)
        Pip.allowed = Prefs.pipNavigation(ctx)
        StopPhotos.chosen = Prefs.moovitPhotos(ctx)
        Shown.twelveHour = Prefs.twelveHour(ctx)
        Moovit.shareLocation = !Prefs.privateSearch(ctx)
        KavLive.chosen = Prefs.liveFromKav(ctx)
        KavLive.server = Prefs.kavServer(ctx)
        KavLive.key = Prefs.kavServerKey(ctx)
        StopNames.english = Prefs.stopNamesEnglish(ctx)
        KavPlan.chosen = Prefs.planWithKav(ctx)
        MapFile.type = Prefs.mapType(ctx)
        seen = Prefs.seen(ctx)
        seenPlace = Prefs.seenPlace(ctx)
        Online.reset()
        T.switchTo(Prefs.lang(ctx))
    }

    // One-off positions for maps and planning; only tracked fixes move a trip's steps on.
    fun locate(lat: Double, lon: Double) {
        here = lat to lon
        hereAt = System.currentTimeMillis() / 1000
        roughFix = Fix(lat, lon, hereAt)
    }

    fun track(lat: Double, lon: Double, speed: Float, at: Long) {
        here = lat to lon
        hereAt = at
        fix = Fix(lat, lon, at, speed)
    }

    // A one-off or rough position can guide the camera without advancing the trip's steps.
    var roughFix by mutableStateOf<Fix?>(null)
        private set

    fun glimpse(lat: Double, lon: Double, speed: Float, at: Long) {
        here = lat to lon
        hereAt = at
        roughFix = Fix(lat, lon, at, speed)
    }

    // Recent enough to plan a trip from.
    val hereFresh: Boolean get() = System.currentTimeMillis() / 1000 - hereAt <= 120

    var seen by mutableStateOf(ctx?.let { Prefs.seen(it) } ?: Seen.CITY)
        private set
    var seenPlace by mutableStateOf(ctx?.let { Prefs.seenPlace(it) })
        private set
    @Volatile private var city: Moovit.Place? = null

    fun setSeen(ctx: Context, how: Seen, place: Moovit.Place? = seenPlace) {
        seen = how
        seenPlace = place
        Prefs.setSeen(ctx, how, place)
        Online.reset()
    }

    fun standIn(ctx: Context): Pair<Double, Double>? = when (seen) {
        Seen.NONE -> null
        Seen.CITY -> cityAround(ctx)?.let { it.lat to it.lon }
        Seen.PLACE -> seenPlace?.let { it.lat to it.lon }
    }

    // The centre of the town or city the user is in, found on the phone from the nearest stops'
    // locality, or of the closest one when none of those has a locality. Needs the timetable loaded.
    fun cityAround(ctx: Context): Moovit.Place? {
        val net = Loaded.net
        val at = here
        if (net != null && at != null) {
            val own = net.nearestStops(at.first, at.second, k = 8)
                .firstOrNull { net.cityOf(it.first).isNotBlank() }?.first?.let { net.cityOf[it] }
            val c = own ?: net.cityCentre.indices.filter { net.cityCentre[it] != null }
                .minByOrNull { net.cityCentre[it]!!.let { p -> metres(at.first, at.second, p.first, p.second) } }
            val centre = c?.let { net.cityCentre.getOrNull(it) }
            if (c != null && centre != null) {
                val found = Moovit.Place(net.city[c], "", centre.first, centre.second)
                if (found.name != city?.name) Prefs.setSeenCity(ctx, found)
                city = found
                return found
            }
        }
        return lastCity(ctx)
    }

    fun lastCity(ctx: Context): Moovit.Place? = city ?: Prefs.seenCity(ctx).also { city = it }

    var update by mutableStateOf<Updates.Release?>(null)
    var updateDismissed by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateError by mutableStateOf<String?>(null)
    var updateChecked by mutableStateOf(false)
    var updateFailed by mutableStateOf(false)

    suspend fun checkForUpdate(ctx: Context) {
        try {
            val installed = Updates.installedVersion(ctx)
            val latest = withContext(Dispatchers.IO) { Updates.latest() }
            update = latest.takeIf { Updates.isNewer(it.version, installed) }
            updateFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("KavUpdate", "update check failed", e)
            updateFailed = true
        } finally {
            updateChecked = true
        }
    }

    // Outlives the dialog, so closing it doesn't drop the download.
    fun startUpdate(ctx: Context) {
        if (updateProgress != null) return
        val app = ctx.applicationContext
        viewModelScope.launch { installUpdate(app) }
    }

    private suspend fun installUpdate(ctx: Context) {
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

private val netLoadMutex = Mutex()

suspend fun loadNet(ctx: Context): Net = withContext(Dispatchers.Default) {
    netLoadMutex.withLock {
        Loaded.net ?: run {
            // The seven days from today from Kav's server when there's a copy, else the week Kav shipped with.
            val n = runCatching { uk.noammm.kav.data.Timetable.load(ctx) }.getOrNull()
                ?: ctx.assets.open("il.kav").use { Net.read(it) }
            Loaded.store(n)
            n
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var onboarded by remember { mutableStateOf(Prefs.onboarded(ctx)) }
    val app = ctx.applicationContext
    val model: KavModel = viewModel { KavModel(Loaded.net, app) }
    if (!onboarded) {
        OnboardingScreen(model) { Prefs.setOnboarded(ctx); onboarded = true }
        return
    }
    LaunchedEffect(model) { if (!model.updateChecked) model.checkForUpdate(app) }
    // Kav's recipe and Vela's: fixes for Moovit or Google that need no update. And the timetable for the seven days from
    // today, taken in at the next start.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { uk.noammm.kav.data.KavRecipe.refresh(app) }
            runCatching { uk.noammm.kav.data.GoogleRecipe.refresh(app) }
            runCatching { uk.noammm.kav.data.Timetable.refresh(app) }
        }
    }
    // Kav's server tells a bus on its way by the trip the timetable has it on, so the timetable loads with it.
    LaunchedEffect(KavLive.enabled) { if (KavLive.enabled) runCatching { loadNet(app) } }
    // Kav's server chosen but not answering: Moovit's live data meanwhile, said once when the app opens, and the
    // server's again as soon as it answers. Without internet it isn't the server, and nothing changes. The ministry can
    // also stop answering the server about stations while its snapshot is fine: those screens take Moovit's live times,
    // said once, until a station's answer comes again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(KavLive.chosen) {
        if (!KavLive.chosen) { KavLive.up = true; return@LaunchedEffect }
        var opening = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (hasNetwork(app)) {
                    val answering = withContext(Dispatchers.IO) { KavLive.answering() }
                    if (!answering && opening) android.widget.Toast.makeText(
                        app,
                        if (KavLive.own) T("Your server isn't answering · using Moovit", "השרת שלכם לא עונה · משתמשים ב-Moovit")
                        else T("Kav's server is offline · using Moovit", "השרת של Kav לא זמין · משתמשים ב-Moovit"),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    KavLive.up = answering
                    if (answering) KavPlan.offered = !KavLive.own || withContext(Dispatchers.IO) {
                        (KavLive.check(KavLive.server, KavLive.key) as? KavLive.Check.Answers)?.planner != null
                    }
                    opening = false
                    if (answering && !KavLive.stopsUp) withContext(Dispatchers.IO) { KavLive.stopsAnswering() }
                }
                kotlinx.coroutines.withTimeoutOrNull(if (KavLive.up) 30_000L else 15_000L) { KavLive.recheck.receive() }
            }
        }
    }
    var toldStops by remember { mutableStateOf(false) }
    LaunchedEffect(KavLive.stopsUp, KavLive.enabled) {
        if (KavLive.stopsUp || !KavLive.enabled || toldStops) return@LaunchedEffect
        toldStops = true
        android.widget.Toast.makeText(
            app, T("The ministry isn't answering · using Moovit", "משרד התחבורה לא עונה · משתמשים ב-Moovit"),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
    var pickLook by remember { mutableStateOf(Prefs.pickLook(ctx)) }
    var pickSupport by remember { mutableStateOf(Prefs.pickSupport(ctx)) }
    Box(Modifier.fillMaxSize()) {
        Shell(model)
        if (Pip.active) PipOverlay(model)
        else if (pickLook) LookPrompt { Prefs.lookPicked(ctx); pickLook = false }
        else if (pickSupport) SupportPrompt { Prefs.supportShown(ctx); pickSupport = false }
        else if (PendingBackup.uri != null) ImportPrompt(model)
        else {
            UpdatePrompt(model)
            if (model.update == null || model.updateDismissed) MapPrompt()
        }
    }
}

@Composable
private fun Shell(model: KavModel) {
    val ctx = LocalContext.current
    DisposableEffect(model) {
        val app = ctx.applicationContext
        Moovit.standIn = { model.standIn(app) }
        onDispose { Moovit.standIn = { null } }
    }
    // Keeps the town Moovit sees current. The timetable is only loaded for it when the last town
    // found is unknown or a few kilometres away.
    val roughly = model.here?.let { (it.first * 100).toInt() to (it.second * 100).toInt() }
    LaunchedEffect(model.seen, roughly) {
        val at = model.here ?: return@LaunchedEffect
        if (model.seen != Seen.CITY) return@LaunchedEffect
        val app = ctx.applicationContext
        val last = model.lastCity(app)
        if (Loaded.net == null && last != null && metres(at.first, at.second, last.lat, last.lon) < 3_000) return@LaunchedEffect
        runCatching { loadNet(app) }
        withContext(Dispatchers.Default) { model.cityAround(app) }
    }
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
    }
    LaunchedEffect(Unit) {
        if (!hasPreciseLocation(ctx)) askLocation.launch(LOCATION_PERMISSIONS)
    }
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(shellLifecycle) {
        shellLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            // The trip service can only start tracking while the app is in front.
            if (model.activeJourney != null) TripService.show(ctx)
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { model.tab to model.netLoadAttempt }.collect { (tab, _) ->
            if (tab != Tab.Stations && tab != Tab.Lines) return@collect
            if (model.net != null || model.netLoading || model.netError != null) return@collect
            model.netLoading = true
            try {
                model.net = loadNet(ctx).also { net -> launch { uk.noammm.kav.ui.warmLines(ctx, net, model.here) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.netError = e.message ?: e.javaClass.simpleName
            } finally {
                model.netLoading = false
            }
        }
    }

    LaunchedEffect(model) {
        withContext(Dispatchers.IO) { JourneyFile.load(ctx) }?.let { (journey, step) ->
            if (model.activeJourney == null) {
                val last = buildSteps(journey.trip, journey.fromLabel, journey.toLabel).size - 1
                model.journeyStep = step.coerceIn(0, last.coerceAtLeast(0))
                model.activeJourney = journey
            }
        }
        model.restored = true
        snapshotFlow { model.activeJourney to model.journeyStep }.collect { (journey, step) ->
            withContext(Dispatchers.IO) {
                if (journey == null) JourneyFile.clear(ctx) else JourneyFile.save(ctx, journey, step)
            }
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { PendingStop.key }.collect { key ->
            if (key == null) return@collect
            PendingStop.key = null
            // Tapped before any screen has loaded the timetable.
            val net = model.net ?: runCatching { withContext(Dispatchers.Default) { loadNet(ctx) } }.getOrNull() ?: return@collect
            val stop = net.stopOfKey(key).takeIf { it >= 0 } ?: return@collect
            model.settingsOpen = false; model.stationStop = stop; model.tab = Tab.Stations
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { PendingLink.plan }.collect { plan ->
            if (plan == null) return@collect
            PendingLink.plan = null
            if (plan.lineGroup != null || plan.stopId != null) {
                openMoovitEntity(ctx, model, plan)
                return@collect
            }
            val somewhere = plan.toLat != null && plan.toLon != null
            if (!somewhere && plan.sharedId == null && plan.shortUrl == null && plan.toName == null) return@collect
            model.pendingLink = plan
            model.settingsOpen = false
            model.tab = Tab.Directions
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, model.activeJourney?.trip) {
        while (true) {
            val journey = model.activeJourney ?: break
            // A trip Kav planned has its names already, and its live times come from Kav's server: no Moovit at all.
            if (KavPlan.owns(journey.trip)) {
                delay(journey.resolved.pollSecs.coerceIn(15, 120) * 1000L)
                val current = model.activeJourney?.takeIf { it.trip == journey.trip } ?: break
                val refreshed = withContext(Dispatchers.IO) { KavPlan.live(listOf(current.trip), current.resolved, ending = true) }
                val spots = withContext(Dispatchers.IO) { KavLive.tripSpots(current.trip, refreshed) }
                model.activeJourney?.takeIf { it.trip == journey.trip }?.let {
                    model.activeJourney = it.copy(resolved = refreshed.withLive(KavLive.fresher(refreshed.live, spots)))
                }
                continue
            }
            val session = try {
                Online.open(journey.trip.legs.firstOrNull { it.shape.isNotEmpty() }?.shape?.first() ?: model.here ?: (32.0759 to 34.7745))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (session == null) { delay(15_000L); continue }
            val needsNames = journey.trip.rides.flatMap { it.options }.any { ride ->
                (ride.lineId > 0 && journey.resolved.line(ride.lineId) == null) ||
                    (ride.fromStop > 0 && journey.resolved.stop(ride.fromStop) == null) ||
                    (ride.toStop > 0 && journey.resolved.stop(ride.toStop) == null)
            }
            if (needsNames) {
                val names = withContext(Dispatchers.IO) { Moovit.hydrate(session, listOf(journey.trip), ending = true) }
                model.activeJourney?.takeIf { it.trip == journey.trip }?.let { current ->
                    val previous = current.resolved
                    model.activeJourney = current.copy(resolved = Moovit.Resolved(
                        previous.lines + names.lines, previous.stops + names.stops,
                        previous.routeTypes + names.routeTypes, previous.live + names.live,
                        previous.shapes + names.shapes, names.pollSecs, previous.patterns + names.patterns,
                        previous.delays + names.delays,
                    ))
                }
            }
            delay(journey.resolved.pollSecs.coerceIn(15, 120) * 1000L)
            val current = model.activeJourney?.takeIf { it.trip == journey.trip } ?: break
            val refreshed = withContext(Dispatchers.IO) {
                Moovit.refreshLive(session, listOf(current.trip), current.resolved, ending = true)
            }
            // With the same refresh, so the notification and the floating window move with the server's positions too.
            val spots = withContext(Dispatchers.IO) { KavLive.tripSpots(current.trip, refreshed) }
            model.activeJourney?.takeIf { it.trip == journey.trip }?.let {
                model.activeJourney = it.copy(resolved = Moovit.Resolved(
                    refreshed.lines + it.resolved.lines, refreshed.stops + it.resolved.stops,
                    refreshed.routeTypes + it.resolved.routeTypes, KavLive.fresher(refreshed.live, spots),
                    refreshed.shapes + it.resolved.shapes, refreshed.pollSecs, refreshed.patterns,
                    it.resolved.delays + refreshed.delays,
                ))
            }
        }
    }


    val journeyActive = model.activeJourney != null
    LaunchedEffect(journeyActive, lifecycle) {
        if (!journeyActive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stop = trackLocation(ctx, onFix = { model.track(it.latitude, it.longitude, it.speed, it.fixTime()) },
                onRough = { model.glimpse(it.latitude, it.longitude, it.speed, it.fixTime()) })
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
                System.currentTimeMillis() / 1000, model.fix, canLocate(ctx),
            )
            if (next != model.journeyStep) model.journeyStep = next
            delay(2000)
        }
    }
    val askNotice = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(journeyActive, model.restored) {
        if (!model.restored) return@LaunchedEffect
        if (!journeyActive) { TripService.stop(ctx); return@LaunchedEffect }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotice.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {
            if (model.activeJourney != null) TripService.show(ctx)
            withTimeoutOrNull(30_000) {
                snapshotFlow { model.activeJourney to model.journeyStep }.drop(1).first()
            }
        }
    }
    DisposableEffect(model.restored) {
        if (model.restored) {
            TripBridge.end = { model.activeJourney = null }
            TripBridge.journey = { model.activeJourney?.let { it to model.journeyStep } }
            TripBridge.fix = { model.track(it.lat, it.lon, it.speed, it.at) }
            TripBridge.fixNow = { model.fix }
            TripBridge.paid = { leg, key ->
                model.activeJourney?.let { model.activeJourney = it.copy(paid = it.paid + (leg to key)) }
            }
        }
        onDispose {
            TripBridge.end = null
            TripBridge.journey = null
            TripBridge.fix = null
            TripBridge.fixNow = null
            TripBridge.paid = null
        }
    }
    LaunchedEffect(model.navigating, Pip.allowed, lifecycle) {
        Pip.wanted = model.navigating && Pip.allowed
        (ctx as? MainActivity)?.updatePipParams()
        if (!model.navigating) { model.heading = null; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stop = trackHeading(ctx) { model.heading = it }
            try { awaitCancellation() } finally { stop() }
        }
    }

    var exitAsk by remember { mutableStateOf(false) }
    BackHandler(enabled = !model.navigating) {
        when {
            model.payHistoryOpen -> model.payHistoryOpen = false
            model.payOpen -> model.payOpen = false
            model.settingsOpen -> { model.settingsOpen = false; model.tab = Tab.Directions }
            model.tab != Tab.Directions -> {
                model.stationStop = -1; model.lineRoute = -1; model.moovitLine = null
                model.tab = Tab.Directions
            }
            else -> exitAsk = true
        }
    }
    if (exitAsk) ExitPrompt(onStay = { exitAsk = false }) {
        exitAsk = false
        (ctx as? ComponentActivity)?.finish()
    }

    val liquid = rememberLiquidBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(K.bg))
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(K.bg))
        },
        bottomBar = {
            // The tab bar slides away while navigating and back up after, instead of popping in.
            Box {
                if (model.navigating) Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.safeDrawing))
                androidx.compose.animation.AnimatedVisibility(
                    !model.navigating,
                    enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 1f, stiffness = 400f)) { it } +
                        androidx.compose.animation.fadeIn(tween(200)),
                    exit = androidx.compose.animation.slideOutVertically(tween(200)) { it } +
                        androidx.compose.animation.fadeOut(tween(150)),
                ) { TabBar(model) }
            }
        },
    ) { pad ->
        val direction = LocalLayoutDirection.current
        CompositionLocalProvider(LocalBottomBarInset provides pad.calculateBottomPadding()) {
        Box(Modifier.padding(
            start = pad.calculateStartPadding(direction), top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(direction),
        ).fillMaxSize().clipToBounds()
            // Not while Street View is open: the bar is away, and glass must never record Google's moving frame.
            .then(if (K.liquid && liquid != null && StreetViewer.open == null) Modifier.liquidSource(liquid) else Modifier)) {
        CompositionLocalProvider(LocalLiquidBackdrop provides null) {
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
                enter = uk.noammm.kav.ui.modalIn(), exit = uk.noammm.kav.ui.modalOut(),
            ) {
                SettingsScreen(model) { model.settingsOpen = false }
            }
            androidx.compose.animation.AnimatedVisibility(
                model.payOpen,
                enter = uk.noammm.kav.ui.modalIn(), exit = uk.noammm.kav.ui.modalOut(),
            ) {
                PayScreen(model) { model.payOpen = false }
            }
            androidx.compose.animation.AnimatedVisibility(
                model.payHistoryOpen,
                enter = uk.noammm.kav.ui.modalIn(), exit = uk.noammm.kav.ui.modalOut(),
            ) {
                PayHistoryScreen(onClose = { model.payHistoryOpen = false }, onTicket = { key ->
                    Payer.showRef = key
                    model.payHistoryOpen = false
                    model.payOpen = true
                })
            }
            var lastReport by remember { mutableStateOf(model.report) }
            model.report?.let { lastReport = it }
            androidx.compose.animation.AnimatedVisibility(
                model.report != null,
                enter = uk.noammm.kav.ui.modalIn(), exit = uk.noammm.kav.ui.modalOut(),
            ) {
                lastReport?.let { key(it) { ReportScreen(it) { model.report = null } } }
            }
            // A stop in Street View or on the map, over whatever screen opened it.
            StreetViewHost()
        }
        }
        }
    }
    // Asshole mode: the kept purchase floats over every screen until it is paid or dropped.
    if (Payer.signedIn) PendingFloat(model, Modifier.align(Alignment.TopEnd)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
        .padding(top = 76.dp, end = K.gap3))
    }
    }
}

@Composable
private fun ImportPrompt(model: KavModel) {
    val ctx = LocalContext.current
    val uri = PendingBackup.uri ?: return
    var failed by remember(uri) { mutableStateOf<String?>(null) }
    var busy by remember(uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) PendingBackup.uri = null }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Import this backup?", "לייבא את הגיבוי הזה?"), style = K.type.title)
            Text(
                failed ?: T(
                    "Your saved places, trip history and settings are replaced by what this file holds. " +
                        "The map stays where it is.",
                    "המקומות השמורים, היסטוריית הנסיעות וההגדרות שלכם יוחלפו במה שיש בקובץ הזה. " +
                        "המפה נשארת במקומה.",
                ),
                fontSize = 14.sp, color = if (failed != null) K.critical else K.dim, lineHeight = 20.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Cancel", "ביטול"), { if (!busy) PendingBackup.uri = null }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                KavButton(T("Import", "ייבוא"), {
                    busy = true
                    scope.launch {
                        Backup.read(ctx, uri)
                            .onSuccess { r ->
                                model.reloadPrefs(ctx)
                                if (PendingBackup.uri == uri) PendingBackup.uri = null
                                android.widget.Toast.makeText(
                                    ctx,
                                    T("Imported: ", "יובא: ") + listOf(
                                        T.count(r.favourites, "1 place", "מקום אחד", "places", "מקומות"),
                                        T.count(r.trips, "1 trip", "נסיעה אחת", "trips", "נסיעות"),
                                        T.count(r.recents, "1 search", "חיפוש אחד", "searches", "חיפושים"),
                                    ).joinToString(", "),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure { e -> failed = importError(e) }
                        busy = false
                    }
                }, Modifier.weight(1f), busy = busy)
            }
        }
    }
}

@Composable
private fun LookPrompt(onDone: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
        ) {
            Text(T("Pick a look", "בחרו מראה"), style = K.type.title)
            Text(
                T(
                    "Kav 2.0 comes in OLED black, light and dark, with liquid glass or solid on top. " +
                        "You can change this later in Settings.",
                    "Kav 2.0 מגיעה בשחור OLED, בבהיר ובכהה, עם זכוכית נוזלית או משטחים אטומים. " +
                        "אפשר לשנות את זה אחר כך בהגדרות.",
                ),
                fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
            )
            LookChoices { Text(it, style = K.type.caption, modifier = Modifier.padding(top = K.gap4, bottom = K.gap2)) }
            KavButton(T("Done", "סיום"), onDone, Modifier.padding(top = K.gap5), wide = true)
        }
    }
}

// Shown once per install: after setup, or after the look prompt on an update.
@Composable
private fun SupportPrompt(onDone: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
        ) {
            Text(T("Enjoying Kav?", "נהנים מקו?"), style = K.type.title)
            Text(
                T(
                    "Kav is made to protect every user's privacy and to make riding the bus a little more " +
                        "bearable (as much as possible).",
                    "קו מפותחת במטרה לשמור על הפרטיות של כל משתמש ולהפוך את השימוש באוטובוסים לחוויה " +
                        "נסבלת יותר (כמה שאפשר).",
                ),
                fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
            )
            Text(
                buildAnnotatedString {
                    append(T(
                        "If you'd like to support Kav's development, you're welcome to tap the button below. " +
                            "If that's not an option for you, you can show your support with ",
                        "אם תרצו לתרום להמשך הפיתוח של האפליקציה, אתם מוזמנים ללחוץ על הכפתור למטה. " +
                            "אם אין לכם אפשרות, אתם מוזמנים להביע תמיכה דרך ",
                    ))
                    withLink(LinkAnnotation.Url(REPO_URL, TextLinkStyles(SpanStyle(color = K.accentInk)))) {
                        append(T("a star on GitHub", "כוכב בגיטהאב"))
                    }
                    append(".")
                },
                fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
            )
            Text(
                T(
                    "*Donating is just a thank-you and 100% optional. You won't lose any features if you can't :)",
                    "*תרומה היא אות הערכה בלבד, והיא אופציונלית לגמרי. לא תאבדו אף פיצ'ר אם אין ביכולתכם לתרום :)",
                ),
                style = K.type.caption, modifier = Modifier.padding(top = K.gap3),
            )
            KavButton(
                T("Buy me a coffee", "קנו לי קפה"), { openLink(ctx, COFFEE_URL); onDone() }, Modifier.padding(top = K.gap5), wide = true,
            )
            KavButton(
                T("Have you seen the economy??", "ראית את מצב האקונומיה??"), onDone, Modifier.padding(top = K.gap2),
                style = ButtonStyle.Secondary, wide = true,
            )
        }
    }
}

@Composable
private fun ExitPrompt(onStay: () -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Exit Kav?", "לצאת מ־Kav?"), style = K.type.title)
            Text(T("A trip in progress is kept until you end it.", "נסיעה שמתבצעת נשמרת עד שתסיימו אותה."), fontSize = 14.sp, color = K.dim, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Stay", "השארות"), onStay, Modifier.weight(1f).heightIn(min = 52.dp), style = ButtonStyle.Secondary,
                )
                KavButton(T("Exit", "יציאה"), onExit, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabBar(model: KavModel) {
    // A glass capsule floating above the gesture area, which stays outside it. The chosen tab sits on the accent, as a
    // segmented control's choice does, and that one highlight slides from tab to tab; no ripple, so nothing flashes.
    val tabs = Tab.entries
    // Street View is a full-screen view of its own: the bar steps down while it's open, and no glass refracts the
    // moving picture.
    androidx.compose.animation.AnimatedVisibility(
        StreetViewer.open == null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
    BoxWithConstraints(
        Modifier.navigationBarsPadding().padding(horizontal = K.gap4, vertical = K.gap2)
            .fillMaxWidth().height(64.dp).glassSurface(32.dp).padding(4.dp),
    ) {
        val each = maxWidth / tabs.size
        val at by androidx.compose.animation.core.animateDpAsState(
            each * tabs.indexOf(model.tab).coerceAtLeast(0),
            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), label = "tabThumb",
        )
        Box(Modifier.offset(x = at).width(each).fillMaxHeight().clip(K.capsule).background(K.accent))
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val on = model.tab == t
                val ink by animateColorAsState(
                    if (on) K.onAccent else K.textSecondary, androidx.compose.animation.core.tween(150), label = "tabInk",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(K.capsule).selectable(
                        on, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null, role = Role.Tab,
                    ) {
                        model.settingsOpen = false
                        if (on) { model.stationStop = -1; model.lineRoute = -1; model.moovitLine = null }
                        if (t == Tab.Directions) model.returnHome = true
                        model.tab = t
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TabGlyph(t, ink, 22.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = K.type.caption, color = ink, maxLines = 1)
                }
            }
        }
    }
}
}

@Composable
internal fun TabGlyph(tab: Tab, tint: Color, side: Dp = 18.dp) {
    Canvas(Modifier.size(side)) {
        val w = size.width; val h = size.height; val sw = w * 0.09f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
        when (tab) {
            Tab.Directions -> {
                line(.10f, .45f, .50f, .12f); line(.50f, .12f, .90f, .45f)
                line(.23f, .40f, .23f, .86f); line(.23f, .86f, .77f, .86f)
                line(.77f, .86f, .77f, .40f); line(.43f, .86f, .43f, .62f)
                line(.43f, .62f, .59f, .62f); line(.59f, .62f, .59f, .86f)
            }
            Tab.Stations -> {
                drawCircle(tint, w * .20f, Offset(w * .50f, h * .34f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                line(.50f, .54f, .50f, .86f); line(.32f, .86f, .68f, .86f)
            }
            Tab.Lines -> listOf(.22f, .50f, .78f).forEachIndexed { i, y ->
                drawCircle(tint, w * .085f, Offset(w * .18f, h * y))
                line(.34f, y, if (i == 1) .86f else .70f, y)
            }
            Tab.Live -> {
                drawCircle(tint, w * .14f, Offset(w * .5f, h * .5f))
                drawCircle(tint, w * .30f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawCircle(tint, w * .46f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw * .7f))
            }
        }
    }
}

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun canLocate(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return hasLocationPermission(ctx) && androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
}

fun hasPreciseLocation(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private const val GOOD_FIX_M = 50f
private const val ROUGH_FIX_M = 75f
private const val VIEW_FIX_M = 200f

internal fun Location.fixTime(): Long =
    System.currentTimeMillis() / 1000 - (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000_000

internal fun locationProviders(lm: LocationManager): List<String> {
    val available = runCatching { lm.allProviders }.getOrDefault(emptyList())
    return buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in available }
}

private fun lastKnown(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    return try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        (locationProviders(lm) + LocationManager.PASSIVE_PROVIDER)
            .asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    } catch (e: SecurityException) { null }
}

// Map previews may start with a cached fix. Route endpoints require an accurate fix measured after this request.
// Cancelling removes the listener and timeout without reporting failure.
fun requestLocationOnce(
    ctx: Context,
    onFail: () -> Unit = {},
    requireFresh: Boolean = false,
    onResult: (Pair<Double, Double>) -> Unit,
): () -> Unit {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null || !hasLocationPermission(ctx)) { onFail(); return {} }
    val started = SystemClock.elapsedRealtimeNanos()
    var reported = false
    var live = false
    var bestAccuracy = Float.MAX_VALUE
    fun offer(l: Location, fresh: Boolean) {
        val accuracy = if (l.hasAccuracy()) l.accuracy else 1_000f
        if (fresh && !live) {
            live = true
        } else if (accuracy >= bestAccuracy) {
            return
        }
        bestAccuracy = accuracy
        reported = true
        onResult(l.latitude to l.longitude)
    }
    if (!requireFresh) {
        lastKnown(ctx)?.takeIf { System.currentTimeMillis() / 1000 - it.fixTime() in 0..120 }?.let { offer(it, false) }
    } else {
        // A precise fix from the last two minutes is as good as a new one, and plans without waiting for the GPS,
        // the way Moovit plans from where the phone already knows you are.
        lastKnown(ctx)?.takeIf {
            System.currentTimeMillis() / 1000 - it.fixTime() in 0..120 && it.hasAccuracy() && it.accuracy in 0f..GOOD_FIX_M
        }?.let { onResult(it.latitude to it.longitude); return {} }
    }

    var done = false
    val handler = Handler(Looper.getMainLooper())
    var timeout: Runnable? = null
    var listener: LocationListenerCompat? = null
    fun stop(failed: Boolean = false) {
        if (done) return
        done = true
        listener?.let { runCatching { LocationManagerCompat.removeUpdates(lm, it) } }
        timeout?.let { handler.removeCallbacks(it) }
        if (failed && !reported) onFail()
    }
    listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (done || location.elapsedRealtimeNanos !in started..SystemClock.elapsedRealtimeNanos()) return
            val accurate = location.hasAccuracy() && location.accuracy in 0f..GOOD_FIX_M
            if (requireFresh && !accurate) return
            if (accurate) stop()
            offer(location, true)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    var any = false
    val request = LocationRequestCompat.Builder(1000L)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
    for (provider in locationProviders(lm)) {
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
        try {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, Looper.getMainLooper())
            any = true
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }
    if (!any) stop(failed = true)
    else {
        timeout = Runnable { stop(failed = true) }
        handler.postDelayed(timeout, 30_000)
    }
    return { stop() }
}

// Rough fixes, and network ones while a precise provider is talking, would move the steps on, so a rough one goes to onRough instead,
// only to show where you are. One rougher than VIEW_FIX_M is no use at all.
fun trackLocation(ctx: Context, onFix: (Location) -> Unit, onRough: ((Location) -> Unit)? = null): () -> Unit {
    if (!hasLocationPermission(ctx)) return {}
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}
    var active = true
    var lastPrecise = 0L
    var lastAt = 0L
    val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            val at = location.elapsedRealtimeNanos
            val age = SystemClock.elapsedRealtimeNanos() - at
            if (!active || at < lastAt || age !in 0..15_000_000_000L) return
            if (!location.hasAccuracy() || location.accuracy !in 0f..VIEW_FIX_M) return
            val rough = location.accuracy > ROUGH_FIX_M
            val now = SystemClock.elapsedRealtime()
            if (location.provider == LocationManager.NETWORK_PROVIDER && now - lastPrecise < 10_000) return
            if (!rough && location.provider != LocationManager.NETWORK_PROVIDER) lastPrecise = now
            lastAt = at
            if (rough) onRough?.invoke(location) else onFix(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    var any = false
    for (provider in locationProviders(lm)) {
        try {
            val interval = if (provider == LocationManager.NETWORK_PROVIDER) 4000L else 1000L
            val request = LocationRequestCompat.Builder(interval)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, Looper.getMainLooper())
            any = true
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }
    if (!any) return {}
    return { active = false; runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }
}

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

fun hasNetwork(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val n = cm.activeNetwork
    val caps = n?.let { cm.getNetworkCapabilities(it) }
    caps != null &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (e: Exception) { false }

object Prefs {
    private const val FILE = "kav"

    private fun store(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
            .distinctBy { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lon) }
            .take(MAX_RECENTS)
        val arr = org.json.JSONArray()
        for (r in kept) arr.put(json(r))
        store(ctx).edit().putString(RECENTS, arr.toString()).apply()
    }

    private const val TRIPS = "trips"
    private const val MAX_TRIPS = 100
    private const val MAX_ROUTES = 20

    private fun place(o: org.json.JSONObject) = Moovit.Place(
        o.optString("n"), o.optString("d"), o.optDouble("lat"), o.optDouble("lon"),
        o.optInt("t", 5), o.optInt("m", -1),
    )

    private fun json(p: Moovit.Place): org.json.JSONObject = org.json.JSONObject()
        .put("n", p.name).put("d", p.detail).put("lat", p.lat).put("lon", p.lon)
        .put("t", p.type).put("m", p.mode)

    fun trips(ctx: Context): List<RecentTrip> = try {
        val raw = store(ctx).getString(TRIPS, "[]")
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val to = o.optJSONObject("to") ?: return@mapNotNull null
            val stopovers = o.optJSONArray("stopovers")
            RecentTrip(
                o.optJSONObject("from")?.let { place(it) }, place(to), o.optLong("at"),
                stopovers = (0 until (stopovers?.length() ?: 0)).mapNotNull { j -> stopovers?.optJSONObject(j)?.let { place(it) } },
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun tripKey(t: RecentTrip) =
        "%.4f,%.4f>%.4f,%.4f".format(java.util.Locale.US, t.from?.lat ?: 0.0, t.from?.lon ?: 0.0, t.to.lat, t.to.lon) +
            t.stopovers.joinToString("") { "+%.4f,%.4f".format(java.util.Locale.US, it.lat, it.lon) }

    private fun saveTrips(ctx: Context, trips: List<RecentTrip>) {
        val arr = org.json.JSONArray()
        for (t in trips) {
            arr.put(
                org.json.JSONObject()
                    .put("from", t.from?.let { json(it) }).put("to", json(t.to)).put("at", t.at)
                    .put("stopovers", org.json.JSONArray().apply { t.stopovers.forEach { put(json(it)) } }),
            )
        }
        store(ctx).edit().putString(TRIPS, arr.toString()).apply()
    }

    // Every search that found routes, newest first, with the routes it found: Moovit's Recent Journeys.
    fun rememberTrip(
        ctx: Context, trip: RecentTrip, routes: List<Moovit.Itinerary>, resolved: Moovit.Resolved,
        sections: List<Moovit.Section>,
    ) {
        val kept = (listOf(trip) + trips(ctx)).distinctBy(::tripKey).take(MAX_TRIPS)
        saveTrips(ctx, kept)
        RecentRoutes.save(ctx, tripKey(trip), routes, resolved, sections)
        RecentRoutes.keep(ctx, kept.take(MAX_ROUTES).map(::tripKey))
    }

    fun savedRoutes(ctx: Context, trip: RecentTrip): RecentRoutes.Saved? = RecentRoutes.load(ctx, tripKey(trip))

    fun clearRecents(ctx: Context) = store(ctx).edit().remove(RECENTS).apply()

    private const val RECENT_LINES = "recentLines"
    private const val MAX_RECENT_LINES = 8

    fun recentLines(ctx: Context): List<String> = try {
        val arr = org.json.JSONArray(store(ctx).getString(RECENT_LINES, "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    fun rememberLine(ctx: Context, key: String) {
        val kept = (listOf(key) + recentLines(ctx)).distinct().take(MAX_RECENT_LINES)
        store(ctx).edit().putString(RECENT_LINES, org.json.JSONArray(kept).toString()).apply()
    }

    // Stops (Net.stopKey) and lines (lineKey) the rider saved, and their line alerts.
    private fun strings(ctx: Context, key: String): List<String> = try {
        val arr = org.json.JSONArray(store(ctx).getString(key, "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    fun savedStops(ctx: Context): List<String> = strings(ctx, "savedStops")
    fun setSavedStops(ctx: Context, keys: List<String>) =
        store(ctx).edit().putString("savedStops", org.json.JSONArray(keys).toString()).apply()

    fun savedLines(ctx: Context): List<String> = strings(ctx, "savedLines")
    fun setSavedLines(ctx: Context, keys: List<String>) =
        store(ctx).edit().putString("savedLines", org.json.JSONArray(keys).toString()).apply()

    fun lineAlerts(ctx: Context): List<LineAlert> = LineAlert.list(store(ctx).getString("lineAlerts", null))
    fun setLineAlerts(ctx: Context, alerts: List<LineAlert>) =
        store(ctx).edit().putString("lineAlerts", LineAlert.json(alerts)).apply()

    // An alert swiped away stays quiet until its window ends.
    fun alertQuietUntil(ctx: Context, id: Long): Long =
        runCatching { org.json.JSONObject(store(ctx).getString("alertsQuiet", "{}")!!).optLong(id.toString()) }.getOrDefault(0L)
    fun quietAlert(ctx: Context, id: Long, until: Long) {
        val o = runCatching { org.json.JSONObject(store(ctx).getString("alertsQuiet", "{}")!!) }.getOrDefault(org.json.JSONObject())
        val now = System.currentTimeMillis() / 1000
        o.keys().asSequence().toList().forEach { if (o.optLong(it) < now) o.remove(it) }
        store(ctx).edit().putString("alertsQuiet", o.put(id.toString(), until).toString()).apply()
    }

    fun accent(ctx: Context): Int = store(ctx).getInt("accent", android.graphics.Color.rgb(0x9A, 0xBE, 0xFF))
    fun setAccent(ctx: Context, argb: Int) = store(ctx).edit().putInt("accent", argb).apply()

    fun lang(ctx: Context): Lang =
        Lang.entries.firstOrNull { it.code == store(ctx).getString("lang", null) } ?: Lang.EN
    fun setLang(ctx: Context, lang: Lang) = store(ctx).edit().putString("lang", lang.code).apply()

    // Stops', stations' and towns' names in English rather than the ministry's Hebrew (StopNames), Hebrew until chosen.
    fun stopNamesEnglish(ctx: Context): Boolean = store(ctx).getString("stopNames", null) == "en"
    fun setStopNamesEnglish(ctx: Context, on: Boolean) = store(ctx).edit().putString("stopNames", if (on) "en" else "he").apply()

    fun showCo2(ctx: Context): Boolean = store(ctx).getBoolean("showCo2", false)
    fun setShowCo2(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("showCo2", on).apply()

    // Navigation in a small window when Kav is left, paying for rides shown, and stop photos from Moovit, which are
    // on until the rider turns them off (StopPhotos.enabled).
    fun pipNavigation(ctx: Context): Boolean = store(ctx).getBoolean("pipNavigation", true)
    fun setPipNavigation(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("pipNavigation", on).apply()
    fun showPayments(ctx: Context): Boolean = store(ctx).getBoolean("showPayments", true)
    fun setShowPayments(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("showPayments", on).apply()
    fun moovitPhotos(ctx: Context): Boolean? = store(ctx).takeIf { it.contains("moovitPhotos") }?.getBoolean("moovitPhotos", true)
    fun setMoovitPhotos(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("moovitPhotos", on).apply()

    fun spokenAlerts(ctx: Context): Boolean = store(ctx).getBoolean("spokenAlerts", false)
    fun setSpokenAlerts(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("spokenAlerts", on).apply()

    fun twelveHour(ctx: Context): Boolean = store(ctx).getBoolean("twelveHour", false)
    fun setTwelveHour(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("twelveHour", on).apply()

    private val looks = Look.entries.map { it.name.lowercase() }

    fun look(ctx: Context): Look =
        Look.entries.firstOrNull { it.name.lowercase() == store(ctx).getString("look", null) } ?: Look.OLED
    fun setLook(ctx: Context, look: Look) = store(ctx).edit().putString("look", look.name.lowercase()).apply()

    fun liquidGlass(ctx: Context): Boolean = liquidGlassReady && store(ctx).getBoolean("liquidGlass", true)
    fun setLiquidGlass(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("liquidGlass", on).apply()

    fun privateSearch(ctx: Context): Boolean = store(ctx).getBoolean("privateSearch", true)
    fun setPrivateSearch(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("privateSearch", on).apply()

    fun liveFromKav(ctx: Context): Boolean = store(ctx).getBoolean("liveFromKav", false)
    fun setLiveFromKav(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("liveFromKav", on).apply()

    // Kav's own planner rather than Moovit's, while Kav's server is chosen.
    fun planWithKav(ctx: Context): Boolean = store(ctx).getBoolean("planWithKav", false)
    fun setPlanWithKav(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("planWithKav", on).apply()

    // The timetable for the seven days from today from Kav's server, rather than the week Kav shipped with.
    fun timetableUpdates(ctx: Context): Boolean = store(ctx).getBoolean("timetableUpdates", false)
    fun setTimetableUpdates(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("timetableUpdates", on).apply()

    // The rider's own copy of Kav's server, else Kav's; the key it takes requests signed with (its KAV_APP_KEY), and the
    // public half of the key its timetable is signed with, as it gave it.
    fun kavServer(ctx: Context): String =
        store(ctx).getString("kavServer", null)?.let(KavLive::serverAddress) ?: KavLive.KAV_SERVER
    fun kavServerKey(ctx: Context): String = store(ctx).getString("kavServerKey", null).orEmpty()
    fun setKavServer(ctx: Context, address: String, key: String, bundleKey: String) = store(ctx).edit()
        .putString("kavServer", address).putString("kavServerKey", key).putString("kavServerBundleKey", bundleKey).apply()
    fun kavServerBundleKey(ctx: Context): String = store(ctx).getString("kavServerBundleKey", null).orEmpty()
    fun setKavServerBundleKey(ctx: Context, key: String) = store(ctx).edit().putString("kavServerBundleKey", key).apply()

    private val mapTypes = MapFile.Type.entries.map { it.name.lowercase() }
    fun mapType(ctx: Context): MapFile.Type =
        MapFile.Type.entries.firstOrNull { it.name.lowercase() == store(ctx).getString("mapType", null) } ?: MapFile.Type.STANDARD
    fun setMapType(ctx: Context, type: MapFile.Type) = store(ctx).edit().putString("mapType", type.name.lowercase()).apply()

    fun seen(ctx: Context): Seen = Seen.entries.firstOrNull { it.name == store(ctx).getString("seen", null) } ?: Seen.CITY
    fun seenPlace(ctx: Context): Moovit.Place? = savedPlace(ctx, "seenPlace")
    fun setSeen(ctx: Context, how: Seen, place: Moovit.Place?) {
        val e = store(ctx).edit().putString("seen", how.name)
        if (place != null) e.putString("seenPlace", json(place).toString()) else e.remove("seenPlace")
        e.apply()
    }
    fun seenCity(ctx: Context): Moovit.Place? = savedPlace(ctx, "seenCity")
    fun setSeenCity(ctx: Context, city: Moovit.Place) = store(ctx).edit().putString("seenCity", json(city).toString()).apply()
    private fun savedPlace(ctx: Context, key: String): Moovit.Place? =
        store(ctx).getString(key, null)?.let { runCatching { place(org.json.JSONObject(it)) }.getOrNull() }
            ?.takeIf(::inIsrael)
    private fun inIsrael(p: Moovit.Place) = p.lat in 29.0..34.0 && p.lon in 34.0..36.5

    fun onboarded(ctx: Context): Boolean = store(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = store(ctx).edit().putBoolean("onboarded", true).putBoolean("pickSupport", true).apply()

    fun pickLook(ctx: Context): Boolean = store(ctx).getBoolean("pickLook", false)
    fun lookPicked(ctx: Context) {
        val e = store(ctx).edit().remove("pickLook").putString("look", K.look.name.lowercase())
        if (liquidGlassReady) e.putBoolean("liquidGlass", K.liquid)
        e.apply()
    }

    fun pickSupport(ctx: Context): Boolean = store(ctx).getBoolean("pickSupport", false)
    fun supportShown(ctx: Context) = store(ctx).edit().remove("pickSupport").apply()

    // Runs once, the first time 2.0 and then 3.0 open on an install that had an older version.
    fun upgrade(ctx: Context) {
        val s = store(ctx)
        val version = s.getInt("prefsVersion", 0)
        if (version >= 3) return
        val e = s.edit()
        if (version < 2) {
            s.getStringSet("filtersOff", null)?.let { off ->
                val cable = if ("CARMELIT" in off && "RAKAVLIT" in off) setOf("CARMELIT_RAKAVLIT") else emptySet()
                e.putStringSet("filtersOff", off - setOf("CARMELIT", "RAKAVLIT", "SHARE_TAXI") + cable)
            }
            if (s.getBoolean("onboarded", false)) {
                e.putBoolean("privateSearch", true).putBoolean("pickLook", true).putBoolean("pickSupport", true)
            }
        }
        // Stop names have a language of their own, Hebrew unless chosen. A rider who had Kav in English keeps English
        // names; after this the two settings never move each other.
        if (s.getBoolean("onboarded", false) && !s.contains("stopNames")) e.putString("stopNames", if (lang(ctx) == Lang.EN) "en" else "he")
        e.putInt("prefsVersion", 3).apply()
        if (version < 2) java.io.File(ctx.filesDir, "moovit-stops.bin").delete()
    }

    fun filters(ctx: Context): Set<ResultFilter> {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty()
        return ResultFilter.entries.filter { it.name !in off }.toSet()
    }
    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(f.name) else off.add(f.name)
        store(ctx).edit().putStringSet("filtersOff", off).apply()
    }

    private val oldFilters = setOf(
        ResultFilter.BUS, ResultFilter.TRAIN, ResultFilter.LIGHT_RAIL, ResultFilter.TAXI, ResultFilter.BIKE, ResultFilter.WALK,
    )

    fun backupJson(ctx: Context): org.json.JSONObject {
        val s = store(ctx)
        fun arr(key: String) =
            runCatching { org.json.JSONArray(s.getString(key, "[]")) }.getOrElse { org.json.JSONArray() }
        val on = filters(ctx)
        // 1.5 reads filtersOff, where Carmelit and Rakavlit are apart and share taxis have their own switch.
        val off = ResultFilter.entries.filter { it !in on }.map { it.name } +
            (if (ResultFilter.CARMELIT_RAKAVLIT in on) emptyList() else listOf("CARMELIT", "RAKAVLIT")) +
            (if (ResultFilter.BUS in on) emptyList() else listOf("SHARE_TAXI"))
        return org.json.JSONObject()
            .put("favourites", arr("favourites"))
            .put("trips", arr("trips"))
            .put("recents", arr("recents"))
            .put("recentLines", arr(RECENT_LINES))
            .put("savedStops", arr("savedStops"))
            .put("savedLines", arr("savedLines"))
            .put("lineAlerts", arr("lineAlerts"))
            .put("lang", lang(ctx).code)
            .put("stopNames", if (stopNamesEnglish(ctx)) "en" else "he")
            .put("accent", accent(ctx))
            .put("look", look(ctx).name.lowercase())
            .put("liquidGlass", s.getBoolean("liquidGlass", true))
            .put("privateSearch", privateSearch(ctx))
            .put("liveFromKav", liveFromKav(ctx))
            .put("planWithKav", planWithKav(ctx))
            .put("timetableUpdates", timetableUpdates(ctx))
            .put("kavServer", kavServer(ctx))
            .put("kavServerKey", kavServerKey(ctx))
            .put("mapType", mapType(ctx).name.lowercase())
            .put("seen", seen(ctx).name)
            .apply { seenPlace(ctx)?.let { put("seenPlace", json(it)) } }
            .put("filters", org.json.JSONObject().apply { ResultFilter.entries.forEach { put(it.name, it in on) } })
            .put("filtersOff", org.json.JSONArray(off))
            .put("showCo2", showCo2(ctx))
            .put("pipNavigation", pipNavigation(ctx))
            .put("showPayments", showPayments(ctx))
            .apply { moovitPhotos(ctx)?.let { put("moovitPhotos", it) } }
            .put("spokenAlerts", spokenAlerts(ctx))
            .put("twelveHour", twelveHour(ctx))
    }

    // Only what the file holds is restored, so settings an older backup lacks stay as they are.
    fun restoreBackup(ctx: Context, o: org.json.JSONObject) {
        val on = filters(ctx)
        val e = store(ctx).edit()
        for (key in listOf("favourites", "trips", "recents", RECENT_LINES, "savedStops", "savedLines", "lineAlerts")) {
            o.optJSONArray(key)?.let { e.putString(key, it.toString()) }
        }
        if (o.has("lang")) e.putString("lang", o.optString("lang"))
        o.optString("stopNames").takeIf { it == "en" || it == "he" }?.let { e.putString("stopNames", it) }
        if (o.has("accent")) e.putInt("accent", o.optInt("accent"))
        o.optString("look").takeIf { it in looks }?.let { e.putString("look", it) }
        if (o.has("liquidGlass")) e.putBoolean("liquidGlass", o.optBoolean("liquidGlass"))
        if (o.has("privateSearch")) e.putBoolean("privateSearch", o.optBoolean("privateSearch"))
        if (o.has("liveFromKav")) e.putBoolean("liveFromKav", o.optBoolean("liveFromKav"))
        if (o.has("planWithKav")) e.putBoolean("planWithKav", o.optBoolean("planWithKav"))
        if (o.has("timetableUpdates")) e.putBoolean("timetableUpdates", o.optBoolean("timetableUpdates"))
        KavLive.serverAddress(o.optString("kavServer"))?.let {
            // The server's timetable key is asked of it again (Timetable.refresh).
            if (it != kavServer(ctx)) e.remove("kavServerBundleKey")
            e.putString("kavServer", it)
        }
        if (o.has("kavServerKey")) e.putString("kavServerKey", o.optString("kavServerKey"))
        o.optString("mapType").takeIf { it in mapTypes }?.let { e.putString("mapType", it) }
        o.optString("seen").takeIf { n -> Seen.entries.any { it.name == n } }?.let { e.putString("seen", it) }
        o.optJSONObject("seenPlace")?.let { place(it) }?.takeIf(::inIsrael)?.let { e.putString("seenPlace", json(it).toString()) }
        if (o.has("showCo2")) e.putBoolean("showCo2", o.optBoolean("showCo2"))
        for (key in listOf("pipNavigation", "showPayments", "moovitPhotos")) if (o.has(key)) e.putBoolean(key, o.optBoolean(key))
        if (o.has("spokenAlerts")) e.putBoolean("spokenAlerts", o.optBoolean("spokenAlerts"))
        if (o.has("twelveHour")) e.putBoolean("twelveHour", o.optBoolean("twelveHour"))
        val chosen = o.optJSONObject("filters")
        val oldOff = o.optJSONArray("filtersOff")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        val off = ResultFilter.entries.filter { f ->
            when {
                chosen != null && chosen.has(f.name) -> !chosen.optBoolean(f.name)
                chosen == null && oldOff != null && f in oldFilters -> f.name in oldOff
                else -> f !in on
            }
        }
        e.putStringSet("filtersOff", off.map { it.name }.toSet())
        e.apply()
    }

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

// A Moovit line link opens that line's page; a stop link opens the stop's board, found by its public code.
private suspend fun openMoovitEntity(ctx: Context, model: KavModel, plan: MoovitLink.Plan) {
    try {
        val session = Online.open(model.here ?: (32.0759 to 34.7745))
        plan.lineGroup?.let { id ->
            val line = withContext(Dispatchers.IO) { Moovit.lineCatalogue(session) }.firstOrNull { it.id == id } ?: return
            model.settingsOpen = false; model.lineRoute = -1; model.moovitLine = line; model.tab = Tab.Lines
        }
        plan.stopId?.let { id ->
            val code = withContext(Dispatchers.IO) { Moovit.stopInfo(session, id) }?.code?.toIntOrNull() ?: return
            // A link that starts Kav arrives before any screen has loaded the timetable.
            val net = model.net ?: withContext(Dispatchers.Default) { loadNet(ctx) }
            val stop = net.code.indexOfFirst { it == code }.takeIf { it >= 0 } ?: return
            model.settingsOpen = false; model.stationStop = stop; model.tab = Tab.Stations
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { }
}
