@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.GoogleMaps
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Transit.Place
import uk.noammm.kav.data.TripLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import uk.noammm.kav.data.RailTrip
import kotlinx.coroutines.launch

private data class OpenTrip(
    val trip: Transit.Itinerary,
    val resolved: Transit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val resume: Boolean = false,
    val backHome: Boolean = false,
)

// A recent trip shown on the routes its search found, while the plan inputs are still the ones they answer.
private class SavedTrip(val trip: RecentTrip, val key: List<Any?>)

private enum class Sort(val labelText: () -> String) {
    RECOMMENDED({ T("Recommended", "מומלץ") }),
    FASTEST({ T("Fastest", "המהיר ביותר") }),
    EARLIEST_DEPARTURE({ T("Departs first", "יציאה מוקדמת") }),
    EARLIEST_ARRIVAL({ T("Arrives first", "הגעה מוקדמת") }),
    LEAST_TRANSFERS({ T("Fewest transfers", "פחות החלפות") }),
    LEAST_WALKING({ T("Least walking", "פחות הליכה") }),
    CHEAPEST({ T("Cheapest", "הזול ביותר") }),
    LOWEST_CO2({ T("Lowest CO2", "פליטת CO2 נמוכה") }),
}

private const val TOO_CLOSE_M = 120.0
private const val TOO_CLOSE = "too-close"

private val HERE = listOf("Current location", "המיקום הנוכחי")

private fun hereName() = T(HERE[0], HERE[1])

private fun herePlace(at: Pair<Double, Double>) = Place(hereName(), "", at.first, at.second)

private fun isHere(p: Place?) = p != null && p.name in HERE

private fun endpointName(p: Place?) = if (isHere(p)) hereName() else p?.name

// A shared trip's end by the name it came with. The sender's own location isn't the rider's: it shows as a pinned
// location. A point shared without a name shows as its coordinates.
internal fun sharedPlace(name: String?, lat: Double, lon: Double) = Place(
    when {
        name in HERE -> T("Pinned location", "מיקום מסומן")
        !name.isNullOrBlank() -> name
        else -> "%.5f, %.5f".format(java.util.Locale.US, lat, lon)
    },
    "", lat, lon,
)

@Composable
fun DirectionsOnline(model: KavModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val here = model.here?.takeIf { model.hereFresh }
    var fromPlace by remember { mutableStateOf<Place?>(null) }
    var toPlace by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    // Places the rider wants to pass on the way, planned in one request.
    var stopovers by remember { mutableStateOf<List<Transit.Place>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }
    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var plan by remember { mutableStateOf(Transit.Plan()) }
    var raw by remember { mutableStateOf<List<Transit.Itinerary>>(emptyList()) }
    // A new search's old cards go with a short fade (their items) and slide down a little (this, 0 to 1).
    val leaving = remember { androidx.compose.animation.core.Animatable(0f) }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by Try again after a plan failed, to plan the same trip again.
    var planAgain by remember { mutableIntStateOf(0) }
    var resolved by remember { mutableStateOf(Transit.Resolved()) }

    var open by remember { mutableStateOf<OpenTrip?>(null) }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    var departAt by remember { mutableLongStateOf(0L) }
    var timeType by remember { mutableIntStateOf(Transit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedTrip?>(null) }
    var linkTrip by remember { mutableStateOf<List<TripLink.Ride>?>(null) }
    var openingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var lastLink by remember { mutableStateOf<TripLink.Plan?>(null) }
    val filters = model.filters

    LaunchedEffect(model.returnHome) {
        if (model.returnHome) {
            open = null; picking = null; showResults = false; saved = null; linkTrip = null
            model.pendingLink = null; linkError = null
            model.returnHome = false
        }
    }

    // Planning opened from a stop's board (From here, To here) goes back to that board, not Home.
    var backToBoard by remember { mutableStateOf(false) }
    fun leaveResults() {
        showResults = false
        if (backToBoard) { backToBoard = false; model.tab = uk.noammm.kav.Tab.Stations }
    }
    LaunchedEffect(model.pendingFrom, model.pendingTo) {
        if (model.pendingFrom != null || model.pendingTo != null) {
            hereOrigin = null; stopovers = emptyList(); saved = null; showResults = true
            backToBoard = model.stationStop >= 0
        }
        model.pendingFrom?.let { fromPlace = it; model.pendingFrom = null }
        model.pendingTo?.let { toPlace = it; model.pendingTo = null }
    }

    LaunchedEffect(model.pendingLink) {
        val incoming = model.pendingLink ?: return@LaunchedEffect
        // A place shared while one is being picked (a favourite's, say) goes into that search instead of starting a trip.
        if (picking != null && incoming.rides.isEmpty() && incoming.fromLat == null) {
            val point = if (incoming.toLat != null && incoming.toLon != null)
                "%.6f, %.6f".format(java.util.Locale.US, incoming.toLat, incoming.toLon) else null
            (point ?: incoming.toName?.takeIf { it.isNotBlank() })?.let {
                model.placeQuery = it
                model.pendingLink = null
                return@LaunchedEffect
            }
        }
        lastLink = incoming; openingLink = true; linkError = null
        hereOrigin = null; stopovers = emptyList()
        open = null; saved = null; picking = null; linkTrip = null
        fromPlace = null; toPlace = null; showResults = true
        try {
            val link = incoming
            // A geo: link may name a place without a point: Google's place search finds it.
            val found = if (link.toLat == null && link.toName != null) withContext(Dispatchers.IO) {
                GoogleMaps.suggest(link.toName, model.here).places.firstOrNull()
            } else null
            val toLat = found?.lat ?: link.toLat ?: throw IllegalArgumentException("Missing destination coordinates")
            val toLon = found?.lon ?: link.toLon ?: throw IllegalArgumentException("Missing destination coordinates")
            fromPlace = if (link.fromLat != null && link.fromLon != null) sharedPlace(link.fromName, link.fromLat, link.fromLon) else null
            toPlace = found ?: sharedPlace(link.toName, toLat, toLon)
            // A link that names its rides plans from a little before its time, so the planner still reaches that very
            // departure and the trip opens as it was shared.
            departAt = if (link.rides.isNotEmpty() && link.departMs > 0) link.departMs - 5 * 60_000 else link.departMs
            timeType = Transit.TIME_DEPARTURE
            linkTrip = link.rides.takeIf { it.isNotEmpty() && link.autoRun }
            showResults = link.autoRun
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            linkError = T("Couldn't open this shared trip. ${e.message.orEmpty()}", "לא ניתן לפתוח את הנסיעה המשותפת. ${e.message.orEmpty()}")
        } finally {
            openingLink = false
            if (model.pendingLink === incoming) model.pendingLink = null
        }
    }

    // A route pins the fresh position accepted for this search, never the map's cached preview.
    val fromLL = fromPlace?.let { it.lat to it.lon } ?: hereOrigin
    val toLL = toPlace?.let { it.lat to it.lon }
    val fromIsHere = fromPlace == null || isHere(fromPlace)

    var findingHere by remember { mutableStateOf(false) }
    var findHereFailed by remember { mutableStateOf(false) }
    var hereRetry by remember { mutableIntStateOf(0) }
    DisposableEffect(showResults, fromPlace, hereOrigin, picking, hereRetry, openingLink, linkError) {
        if (showResults && fromPlace == null && hereOrigin == null && picking == null && !openingLink && linkError == null) {
            findingHere = true; findHereFailed = false
            val stop = requestLocationOnce(ctx, requireFresh = true, onFail = {
                findingHere = false; findHereFailed = true
                linkTrip = null
            }) {
                hereOrigin = it
                model.locate(it.first, it.second)
                findingHere = false
            }
            onDispose { stop(); findingHere = false }
        } else {
            onDispose { }
        }
    }

    // The inputs the results on screen were planned for: anything else is still being planned, never "no routes".
    val planKey = listOf(fromLL, toLL, departAt, timeType, filters, stopovers)
    var plannedFor by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(showResults, fromLL, toLL, departAt, timeType, filters, stopovers, openingLink, saved, planAgain) {
        if (!showResults || openingLink) { planning = false; return@LaunchedEffect }
        if (departAt != 0L && departAt < System.currentTimeMillis() && timeType != Transit.TIME_LAST) {
            departAt = 0L; timeType = Transit.TIME_DEPARTURE
            return@LaunchedEffect
        }
        raw = emptyList(); resolved = Transit.Resolved(); error = null
        launch { leaving.snapTo(0f); leaving.animateTo(1f, androidx.compose.animation.core.tween(150)) }
        // A recent trip opens on the routes its search found, until anything about
        // the trip changes.
        saved?.let { keep ->
            if (keep.key != planKey) { saved = null; return@LaunchedEffect }
            val found = withContext(Dispatchers.IO) { Prefs.savedRoutes(ctx, keep.trip) }
            // A trip saved before its routes were kept plans afresh.
            if (found == null) { saved = null; return@LaunchedEffect }
            plan = Transit.Plan(found.routes, found.sections)
            raw = found.routes; resolved = found.resolved
            planning = false; plannedFor = planKey
            return@LaunchedEffect
        }
        if (fromLL == null || toLL == null) { planning = false; return@LaunchedEffect }
        if (metres(fromLL.first, fromLL.second, toLL.first, toLL.second) < TOO_CLOSE_M) {
            error = TOO_CLOSE; planning = false; linkTrip = null; return@LaunchedEffect
        }
        planning = true
        if (departAt != 0L) delay(250)
        try {
            // The server chosen to plan, Kav's or the rider's own, plans the trip; when it can't, the rider is told and
            // can try again. A shared trip Kav planned only a Kav planner can find again, whichever plans now.
            val link = linkTrip
            val planner = (if (link != null) KavPlan.linkPlanner() else KavPlan.planner())
                ?: throw java.io.IOException("no planner answering")
            val kav = withContext(Dispatchers.IO) {
                KavPlan.plan(fromLL, toLL, departAt, timeType, routeTypesFor(filters), taxi = ResultFilter.TAXI in filters, stopovers, planner)
            }
            val res = kav.plan
            plan = res
            raw = res.laidOut()
            linkTrip?.let { named ->
                if (raw.none { exactTrip(it, named) }) {
                    res.itineraries.firstOrNull { exactTrip(it, named) }
                        ?.let { raw = listOf(it) + raw }
                }
            }
            resolved = kav.resolved
            model.activeJourney?.takeIf { active -> raw.any { it === active.trip } }?.let {
                model.activeJourney = it.copy(resolved = Transit.Resolved(
                    it.resolved.lines + resolved.lines, it.resolved.stops + resolved.stops,
                    it.resolved.routeTypes + resolved.routeTypes,
                    resolved.live + it.resolved.live, resolved.shapes + it.resolved.shapes,
                    it.resolved.pollSecs, it.resolved.patterns + resolved.patterns,
                    it.resolved.delays + resolved.delays,
                ))
            }
            // Every search that found routes becomes a recent trip with them,
            // which leave out the taxi card.
            val dest = toPlace
            val found = raw.filterNot(res::isTaxiCard)
            if (dest != null && !isHere(dest) && found.isNotEmpty()) {
                val start = fromPlace?.takeUnless(::isHere)
                    ?: Place(T("Saved start", "נקודת ההתחלה השמורה"), "", fromLL.first, fromLL.second)
                val trip = RecentTrip(start, dest, System.currentTimeMillis(), stopovers)
                val details = resolved
                withContext(Dispatchers.IO) { runCatching { Prefs.rememberTrip(ctx, trip, found, details, res.sections) } }
            }
            // Open only after this search and its stop/line details have arrived. A separate effect
            // can otherwise see the previous search before this one has started.
            val named = linkTrip
            val match = named?.let { raw.firstOrNull { exactTrip(it, named) } ?: raw.firstOrNull { sameLines(it, named) } }
            if (match != null) {
                open = OpenTrip(match, resolved, fromPlace?.name ?: hereName(), toPlace?.name ?: T("Destination", "יעד"))
            }
            planning = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Transit.PlannerRefusal) {
            android.util.Log.i("KavPlan", "planner refused: ${e.code} ${e.title}")
            error = when (e.code) {
                Transit.PLAN_TOO_CLOSE -> TOO_CLOSE
                Transit.PLAN_NO_ROUTES -> T(
                    "Nothing is running for this trip at that time. Try another departure time.",
                    "אין קווים לנסיעה הזו בשעה הזו. נסו שעת יציאה אחרת.",
                )
                Transit.PLAN_TOO_FAR -> T(
                    "These two places are too far apart to plan a trip between.",
                    "שני המקומות האלה רחוקים מכדי לתכנן נסיעה ביניהם.",
                )
                Transit.PLAN_NO_COVERAGE -> T(
                    "Kav's planner has no timetable for this area.",
                    "לתכנון של Kav אין לוח זמנים לאזור הזה.",
                )
                else -> e.detail.ifBlank { e.title }.ifBlank {
                    T("Kav's planner would not plan this trip.", "התכנון של Kav לא תכנן את הנסיעה הזו.")
                }
            }
            planning = false
        } catch (e: Exception) {
            android.util.Log.e("KavPlan", "online plan failed", e)
            error = when {
                !uk.noammm.kav.hasNetwork(ctx) -> T(
                    "No connection. Kav needs one to plan a trip.",
                    "אין חיבור. Kav זקוק לחיבור כדי לתכנן נסיעה.",
                )
                e is KavPlan.NoTimetable -> T(
                    "Kav's planner has no timetable for that day yet. Try a nearer date.",
                    "לתכנון של Kav עוד אין לוח זמנים ליום הזה. נסו תאריך קרוב יותר.",
                )
                KavPlan.server != KavLive.KAV_SERVER -> T("Your server didn't answer.", "השרת שלכם לא ענה.")
                else -> T("Kav's server didn't answer.", "השרת של Kav לא ענה.")
            }
            planning = false
        }
        plannedFor = planKey
        linkTrip = null
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(showResults, raw, resolved.pollSecs, open?.trip, model.activeJourney?.trip, lifecycle) {
        val active = model.activeJourney?.trip
        if (!showResults || raw.isEmpty() || (active != null && open?.trip === active)) return@LaunchedEffect
        val otherTrips = raw.filterNot { it === active }
        if (otherTrips.isEmpty()) return@LaunchedEffect
        // A recent trip's saved routes have no live times yet: they come at once, as with a search.
        var wait = saved == null || resolved.live.isNotEmpty()
        var back = false
        // Only while Home is in front: behind another tab, or with Kav away, they wait, and come at once on coming back.
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (back) wait = false
            back = true
            while (true) {
                if (wait) kotlinx.coroutines.delay(resolved.pollSecs.coerceIn(15, 120) * 1000L)
                wait = true
                // From the server live locations come from; any other routes stay as they were kept.
                if (!otherTrips.all { KavPlan.owns(it) }) break
                val refreshed = withContext(Dispatchers.IO) { KavPlan.live(otherTrips, resolved) }
                resolved = Transit.Resolved(
                    refreshed.lines + resolved.lines, refreshed.stops + resolved.stops,
                    refreshed.routeTypes + resolved.routeTypes, refreshed.live,
                    refreshed.shapes + resolved.shapes, refreshed.pollSecs, refreshed.patterns + resolved.patterns,
                    resolved.delays + refreshed.delays,
                )
            }
        }
    }

    // On Shabbat: whether nothing to ride was found at all (Shabbat.kt).
    val nothingOnShabbat = remember(raw, departAt, timeType) {
        timeType == Transit.TIME_DEPARTURE &&
            nothingOnShabbat(if (departAt > 0) departAt / 1000 else System.currentTimeMillis() / 1000, raw)
    }
    val shown = remember(raw, sort, filters, resolved) {
        val kept = filterResults(raw, filters, resolved)
        when (sort) {
            Sort.RECOMMENDED -> kept
            Sort.FASTEST -> kept.sortedBy { it.durationMin }
            Sort.EARLIEST_DEPARTURE -> kept.sortedBy { it.dep }
            Sort.EARLIEST_ARRIVAL -> kept.sortedBy { it.arr }
            Sort.LEAST_TRANSFERS -> kept.sortedWith(compareBy({ it.transfers }, { it.durationMin }))
            Sort.LEAST_WALKING -> kept.sortedWith(
                compareBy({ i -> i.legs.filter { it.kind == Transit.LegKind.WALK }.sumOf { it.minutes } }, { it.durationMin }),
            )
            Sort.CHEAPEST -> kept.sortedWith(compareBy({ if (it.fare < 0) Int.MAX_VALUE else it.fare }, { it.durationMin }))
            Sort.LOWEST_CO2 -> kept.sortedWith(compareBy({ if (it.co2g < 0) Int.MAX_VALUE else it.co2g }, { it.durationMin }))
        }
    }
    // The first route's time, and the last one shown, which Earlier and Later keep while new routes load.
    val firstTime = shown.firstOrNull()?.let { clockFormat().format(java.util.Date(it.dep * 1000)) }
    var shiftTime by remember { mutableStateOf("") }
    SideEffect { firstTime?.let { shiftTime = it } }

    androidx.activity.compose.BackHandler(enabled = showResults && open == null && picking == null && linkTrip == null) {
        leaveResults()
    }

    val under by underSearch(picking != null)
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(open, showResults, linkTrip != null || openingLink),
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = under },
        transitionSpec = {
            if (targetState.first != null || (targetState.second && !initialState.second)) forward()
            else backward()
        },
        label = "directions",
    ) { (chosen, displayingResults, opening) ->
        if (chosen != null) {
            val active = model.activeJourney?.takeIf { it.trip === chosen.trip }
            val detailResolved = active?.resolved
                ?: if (!chosen.resume && raw.any { it === chosen.trip }) resolved else chosen.resolved
            TripDetailScreen(
                model,
                chosen.trip, detailResolved,
                fromLabel = chosen.fromLabel,
                toLabel = chosen.toLabel,
                onBack = { open = null; if (chosen.resume || chosen.backHome) showResults = false },
                startInNavigation = chosen.resume,
                onStart = {
                    if (active == null) {
                        model.journeyStep = 0
                        model.activeJourney = ActiveJourney(
                            chosen.trip, detailResolved, chosen.fromLabel, chosen.toLabel,
                        )
                    }
                },
                onNavigating = { model.navigating = it },
                onHome = { open = null; showResults = false },
                onEnd = {
                    if (model.activeJourney?.trip === chosen.trip) model.activeJourney = null
                    open = null
                    showResults = false
                },
            )
            return@AnimatedContent
        }

    if (!displayingResults) {
        HomeScreen(
            model = model,
            recentTrips = Prefs.trips(ctx),
            onSearch = { hereOrigin = null; stopovers = emptyList(); saved = null; linkError = null; fromPlace = null; departAt = 0L; timeType = Transit.TIME_DEPARTURE; picking = "to" },
            onFavourite = { p ->
                hereOrigin = null; stopovers = emptyList(); saved = null; linkError = null; fromPlace = null; toPlace = p
                departAt = 0L; timeType = Transit.TIME_DEPARTURE
                showResults = true
            },
            onSetFavourite = { f -> model.settingFavourite = f },
            onTrip = { t ->
                hereOrigin = null; stopovers = t.stopovers; linkError = null; fromPlace = t.from; toPlace = t.to
                departAt = 0L; timeType = Transit.TIME_DEPARTURE
                saved = SavedTrip(t, listOf(t.from?.let { it.lat to it.lon }, t.to.lat to t.to.lon, 0L,
                    Transit.TIME_DEPARTURE, filters, t.stopovers))
                showResults = true
            },
            onResume = {
                model.activeJourney?.let { journey ->
                    open = OpenTrip(
                        journey.trip, journey.resolved, journey.fromLabel, journey.toLabel, resume = true,
                    )
                }
            },
        )
        return@AnimatedContent
    }

    if (opening) {
        LoadingScreen(T("Finding your route", "מוצאים לכם מסלול")) {
            linkTrip = null; model.pendingLink = null; openingLink = false; showResults = false
        }
        return@AnimatedContent
    }

    if (linkError != null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(T("Shared trip", "נסיעה משותפת"), back = { linkError = null; showResults = false })
            Note(linkError.orEmpty(), Modifier.padding(K.gap4))
            KavButton(T("Try again", "נסו שוב"), { model.pendingLink = lastLink }, Modifier.padding(horizontal = K.gap2), style = ButtonStyle.Plain)
        }
        return@AnimatedContent
    }

    val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = resultsState,
            contentPadding = PaddingValues(bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            item(key = "trip") {
                PlanHeader(
                    // With no start picked the trip starts where you are, so it says so even before the fix arrives.
                    from = endpointName(fromPlace) ?: hereName(),
                    to = endpointName(toPlace),
                    fromIsHere = fromIsHere,
                    toIsHere = isHere(toPlace),
                    onFrom = { picking = "from" },
                    onTo = { picking = "to" },
                    stops = stopovers.map { it.name },
                    onAddStop = { picking = "stop" },
                    onStop = { i -> picking = "stop:$i" },
                    onRemoveStop = { i -> stopovers = stopovers.filterIndexed { j, _ -> j != i } },
                    // Places counted from the start, 0. The start moved away takes where you are as a place like the
                    // others, and a place moved to the start becomes it.
                    onMove = { a, b ->
                        val dest = toPlace
                        val start = fromPlace ?: (hereOrigin ?: here)?.let(::herePlace)
                        if (dest != null && (a > 0 && b > 0 || start != null)) {
                            val way = (listOfNotNull(start.takeIf { a == 0 || b == 0 }) + stopovers + dest).toMutableList()
                            val shift = if (a == 0 || b == 0) 0 else 1
                            way.add(b - shift, way.removeAt(a - shift))
                            if (shift == 0) { hereOrigin = null; fromPlace = way.removeAt(0) }
                            toPlace = way.last(); stopovers = way.dropLast(1)
                        }
                    },
                    onSwap = {
                        val a = fromPlace
                        fromPlace = toPlace
                        toPlace = a ?: hereOrigin?.let(::herePlace)
                    },
                    onBack = { leaveResults() },
                )
            }
            item(key = "when") {
                Column {
                    DepartRow(
                        whenLabel(departAt, timeType),
                        onWhen = { whenOpen = true },
                        order = sort.labelText(),
                        onOrder = { orderOpen = true },
                    )
                    PreciseLocationNudge()
                }
            }
            // Earlier and Later, with the first route's time between them. It stays while new routes load, so the
            // time rolls to theirs instead of the row going.
            fun androidx.compose.foundation.lazy.LazyListScope.shiftRow(time: String) = item(key = "shift") {
                Row(
                    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(150), placementSpec = null)
                        .fillMaxWidth().padding(horizontal = K.gap4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun nudge(delta: Long) {
                        val now = System.currentTimeMillis()
                        val base = if (departAt == 0L) now else departAt
                        departAt = (base + delta).let { if (it < now + 60_000L) 0L else it }
                        if (departAt == 0L) timeType = Transit.TIME_DEPARTURE
                    }
                    KavChip(T("Earlier", "מוקדם יותר"), selected = false, onClick = { nudge(-15 * 60_000L) },
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
                    Spacer(Modifier.weight(1f))
                    // The time rolls up for Later and down for Earlier.
                    RollingText(
                        time,
                        style = K.type.caption.tabular,
                        order = compareBy { t -> t.filter(Char::isDigit).toIntOrNull() ?: 0 },
                    )
                    Spacer(Modifier.weight(1f))
                    KavChip(T("Later", "מאוחר יותר"), selected = false, onClick = { nudge(15 * 60_000L) },
                        trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                }
            }
            when {
                error == TOO_CLOSE -> item {
                    NoteCard(
                        T(
                            "You're too close to your destination to plan a route.",
                            "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                        ),
                        Modifier.padding(K.gap4),
                    )
                }
                error != null -> item {
                    Column(Modifier.padding(K.gap4)) {
                        Note(error.orEmpty())
                        Text(T("Try again", "נסו שוב"), color = K.accentInk,
                            modifier = Modifier.clickable { planAgain++ }.padding(vertical = K.gap3))
                    }
                }
                toLL == null -> item {
                    NoteCard(
                        if (fromLL == null) T(
                            "Choose where you are starting from, and where you are going.",
                            "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                        )
                        else T("Where do you want to go?", "לאן תרצו להגיע?"),
                        Modifier.padding(K.gap4),
                    )
                }
                fromLL == null && !findHereFailed -> item(key = "finding") { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.tween(200, delayMillis = 60),
                    fadeOutSpec = androidx.compose.animation.core.tween(120), placementSpec = null,
                ).fillParentMaxHeight(.6f)) }
                fromLL == null -> item {
                    Column(Modifier.padding(K.gap4)) {
                        if (findHereFailed) Note(T("Couldn't get an accurate location. Try again or choose a start.",
                            "לא התקבל מיקום מדויק. נסו שוב או בחרו נקודת התחלה."))
                        else NoteCard(T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."))
                        if (findHereFailed && !findingHere) Text(T("Try again", "נסו שוב"), color = K.accentInk,
                            modifier = Modifier.clickable { hereRetry++ }.padding(vertical = K.gap3))
                    }
                }
                planning || plannedFor != planKey -> {
                    if (timeType != Transit.TIME_LAST && shiftTime.isNotEmpty()) shiftRow(shiftTime)
                    item(key = "finding") { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(200, delayMillis = 60),
                        fadeOutSpec = androidx.compose.animation.core.tween(120), placementSpec = null,
                    ).fillParentMaxHeight(.6f)) }
                }
                shown.isEmpty() -> item {
                    NoteCard(
                        if (raw.isEmpty() && nothingOnShabbat) SHABBAT_NOTHING
                        else if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                        else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
                        Modifier.padding(K.gap4),
                    )
                }
                else -> {
                    if (timeType != Transit.TIME_LAST) shiftRow(firstTime ?: shiftTime)
                    // Late at night or on Shabbat there may only be walking and cycling; routes the planner found
                    // after Shabbat or a holiday ends come under a line that says so.
                    if (raw.none { t -> t.legs.any { it.kind == Transit.LegKind.RIDE } }) item(key = "no-transit") {
                        NoteCard(
                            if (nothingOnShabbat) SHABBAT_NOTHING
                            else T("No public transport found for this time.", "לא נמצאה תחבורה ציבורית לשעה הזו."),
                            Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                        )
                    } else shown.first().after?.let { day ->
                        item(key = "shabbat") { NoteCard(afterDayOff(day), Modifier.padding(horizontal = K.gap4, vertical = K.gap2)) }
                    }
                    // "View schedules": the first route's main line and its next departures (ScheduleCard).
                    if (sort == Sort.RECOMMENDED) shown.firstOrNull { it.rides.isNotEmpty() }?.let { first ->
                        item(key = "schedule") { ScheduleCard(model, first, resolved, Modifier.padding(horizontal = K.gap4)) }
                    }
                    // Israel Railways' own trains for the first route that takes the train, under the timetable card.
                    railPair(shown, resolved)?.takeIf { sort == Sort.RECOMMENDED }?.let { rail ->
                        item(key = "rail") {
                            val scope = rememberCoroutineScope()
                            // A train journey opens as a trip of its own, like any route, in the railway's name and colours.
                            val railway = shown.firstNotNullOfOrNull { t ->
                                t.rides.firstOrNull(resolved::isTrain)?.let { resolved.line(it.lineId)?.agencyId }
                            } ?: KavPlan.agencyId(2, 2)
                            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(150), placementSpec = null)
                                .popIn(0, raw to sort)) {
                                RailCard(model.net, rail, Modifier.padding(horizontal = K.gap4)) { journey ->
                                    scope.launch {
                                        // Kav's timetable, loaded now if nothing has needed it yet.
                                        val net = model.net ?: runCatching { uk.noammm.kav.loadNet(ctx) }.getOrNull()?.also { model.net = it }
                                            ?: return@launch
                                        val (trip, r) = withContext(Dispatchers.Default) { RailTrip.of(net, journey, railway) }
                                            ?: return@launch
                                        open = OpenTrip(
                                            trip, r, r.stopName(trip.legs.first().fromStop).orEmpty(),
                                            r.stopName(trip.legs.last().toStop).orEmpty(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // A plain Taxi row where the plan brought no taxi card, in the taxi section's place: Gett for the
                    // search's own From and To.
                    val taxiLink = if (sort == Sort.RECOMMENDED && ResultFilter.TAXI in filters && shown.none(plan::isTaxiCard)) {
                        fromLL?.let { f -> toLL?.let { t -> gettLink(f, t, fromPlace?.name, toPlace?.name) } }
                    } else null
                    val taxiAt = if (taxiLink != null) taxiSlot(plan, shown) else -1
                    // Each route keeps its row through live updates, and a row that moves glides to its new place.
                    val keys = uniqueKeys(shown.map { "route:${it.guid}" })
                    items(shown.size, key = { keys[it] }) { i ->
                        val mine = raw
                        Column(
                            Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(150),
                                placementSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 1f, stiffness = 500f,
                                    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                                ),
                            ).graphicsLayer { if (raw !== mine) translationY = leaving.value * 16.dp.toPx() }
                                .padding(horizontal = K.gap4),
                        ) {
                            if (i == taxiAt && taxiLink != null) {
                                TaxiRow(taxiLink)
                                Spacer(Modifier.height(K.gap3))
                            }
                            val heading = plan.heading(shown[i])
                            if (sort == Sort.RECOMMENDED && heading.isNotBlank() &&
                                (i == 0 || plan.heading(shown[i - 1]) != heading)
                            ) {
                                SectionHeader(sentenceCase(heading))
                            }
                            // The taxi card orders its ride with Gett, between the search's own From and To.
                            val taxi = shown[i].legs.firstOrNull { it.kind == Transit.LegKind.TAXI }?.takeIf { plan.isTaxiCard(shown[i]) }
                            Box(Modifier.popIn(i, raw to sort)) {
                                ItineraryCard(shown[i], resolved, footer = taxi?.let { leg ->
                                    { OrderGett(gettLink(leg, fromPlace?.name, toPlace?.name), Modifier.fillMaxWidth()) }
                                }) {
                                    open = OpenTrip(
                                        shown[i], resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
                                        toPlace?.name ?: T("Destination", "יעד"),
                                    )
                                }
                            }
                        }
                    }
                    if (taxiLink != null && taxiAt == shown.size) item(key = "taxi") {
                        Column(Modifier.padding(horizontal = K.gap4)) { TaxiRow(taxiLink) }
                    }
                }
            }
        }
        ScrollEdge(resultsState.canScrollBackward)
    }


    }

    val settingFav = model.settingFavourite
    LaunchedEffect(settingFav) { if (settingFav != null) picking = "fav" }

    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 76.dp.roundToPx() }
    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) searchIn(rise) else searchOut(rise) },
        label = "placePicker",
    ) { which ->
        if (which != null) {
        PlacePicker(
            // "stop" adds a stop on the way, "stop:1" changes the second.
            title = when {
                which == "from" -> T("Start", "התחלה")
                which.startsWith("stop") -> T("Stop on the way", "עצירה בדרך")
                else -> T("Where to?", "לאן?")
            },
            here = here,
            allowMyLocation = which != "fav" && !which.startsWith("stop"),
            initialSetting = if (which == "fav") settingFav else null,
            onMyLocation = { at ->
                if (which == "from") { fromPlace = null; hereOrigin = at }
                else { toPlace = herePlace(at); if (fromPlace == null) hereOrigin = at }
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onPick = { p ->
                if (which.startsWith("stop:")) {
                    val i = which.substringAfter(':').toIntOrNull()
                    stopovers = stopovers.mapIndexed { j, s -> if (j == i) p else s }
                    picking = null; model.placeQuery = ""
                    return@PlacePicker
                }
                if (which == "stop") {
                    stopovers = stopovers + p
                    picking = null; model.placeQuery = ""
                    return@PlacePicker
                }
                hereOrigin = null
                if (which == "from") fromPlace = p else toPlace = p
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onDismiss = { picking = null; model.settingFavourite = null },
            net = model.net,
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = model.placeQuery,
            onQuery = { model.placeQuery = it },
            onLocate = { model.locate(it.first, it.second) },
        )
        }
    }

    if (orderOpen) {
        val orders = Sort.entries.filter { it != Sort.LOWEST_CO2 || Shown.co2 }
        ChoiceSheet(
            T("Order routes by", "סדר המסלולים"), orders.map { it.labelText() }, orders.indexOf(sort),
            onPick = { sort = orders[it]; orderOpen = false },
            onDismiss = { orderOpen = false },
        )
    }

    if (whenOpen) WhenSheet(
        departAt = departAt,
        timeType = timeType,
        onPick = { ms, type ->
            departAt = ms; timeType = type; whenOpen = false; showResults = true
        },
        onNow = { departAt = 0L; timeType = Transit.TIME_DEPARTURE; whenOpen = false },
        onDismiss = { whenOpen = false },
    )
}

internal fun exactTrip(candidate: Transit.Itinerary, named: List<TripLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i ->
        val want = named[i]
        val leg = rides[i]
        if (want.lineId !in leg.lineChoices) return@all false
        val byTrip = want.tripId != 0L &&
            (leg.tripId == want.tripId || leg.options.any { it.tripId == want.tripId })
        byTrip || kotlin.math.abs(leg.dep - want.depSec) <= 60
    }
}

internal fun sameLines(candidate: Transit.Itinerary, named: List<TripLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i -> named[i].lineId in rides[i].lineChoices }
}

// English section names come in Title Case; Kav writes sentence case. Hebrew has no case to change.
private fun sentenceCase(name: String): String =
    if (name.any { it in 'א'..'ת' }) name else name.lowercase(java.util.Locale.US).replaceFirstChar { it.titlecase(java.util.Locale.US) }


private fun whenLabel(departAt: Long, timeType: Int): String {
    if (timeType == Transit.TIME_LAST) return T("Latest departure", "יציאה אחרונה")
    if (departAt <= 0L) return T("Depart now", "יציאה עכשיו")
    val now = java.util.Calendar.getInstance(ISRAEL)
    val then = java.util.Calendar.getInstance(ISRAEL).apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    // The time as Kav writes it everywhere ("2:15 PM"), the day in Kav's language.
    val time = clockFormat().format(java.util.Date(departAt))
    val stamp = if (sameDay) time
        else java.text.SimpleDateFormat("EEE", T.locale).apply { timeZone = ISRAEL }.format(java.util.Date(departAt)) + " " + time
    return when (timeType) {
        Transit.TIME_ARRIVAL -> T("Arrive by ", "הגעה עד ") + stamp
        else -> T("Depart ", "יציאה ") + stamp
    }
}
