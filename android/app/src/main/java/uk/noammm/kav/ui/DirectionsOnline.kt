@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Moovit.Place
import uk.noammm.kav.data.MoovitLink

private data class OpenTrip(
    val trip: Moovit.Itinerary,
    val resolved: Moovit.Resolved,
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

@Composable
fun DirectionsOnline(model: KavModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val here = model.here?.takeIf { model.hereFresh }
    var fromPlace by remember { mutableStateOf<Place?>(null) }
    var toPlace by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    // Places the rider wants to pass on the way, planned by Moovit in one request.
    var stopovers by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }
    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var plan by remember { mutableStateOf(Moovit.Plan()) }
    var raw by remember { mutableStateOf<List<Moovit.Itinerary>>(emptyList()) }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(Moovit.Resolved()) }

    var open by remember { mutableStateOf<OpenTrip?>(null) }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    var departAt by remember { mutableLongStateOf(0L) }
    var timeType by remember { mutableIntStateOf(Moovit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedTrip?>(null) }
    var linkTrip by remember { mutableStateOf<List<MoovitLink.Ride>?>(null) }
    var openingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var lastLink by remember { mutableStateOf<MoovitLink.Plan?>(null) }
    val filters = model.filters

    LaunchedEffect(model.returnHome) {
        if (model.returnHome) {
            open = null; picking = null; showResults = false; saved = null; linkTrip = null
            model.pendingLink = null; linkError = null
            model.returnHome = false
        }
    }

    LaunchedEffect(model.pendingFrom, model.pendingTo) {
        if (model.pendingFrom != null || model.pendingTo != null) {
            hereOrigin = null; stopovers = emptyList(); saved = null; showResults = true
        }
        model.pendingFrom?.let { fromPlace = it; model.pendingFrom = null }
        model.pendingTo?.let { toPlace = it; model.pendingTo = null }
    }

    LaunchedEffect(model.pendingLink) {
        val incoming = model.pendingLink ?: return@LaunchedEffect
        lastLink = incoming; openingLink = true; linkError = null
        hereOrigin = null; stopovers = emptyList()
        open = null; saved = null; picking = null; linkTrip = null
        fromPlace = null; toPlace = null; showResults = true
        fun place(name: String?, lat: Double, lon: Double) = Moovit.Place(
            name?.takeIf { it.isNotBlank() } ?: "%.5f, %.5f".format(java.util.Locale.US, lat, lon), "", lat, lon,
        )
        try {
            val link = withContext(Dispatchers.IO) { MoovitLink.resolve(incoming) }
            if (link.sharedId != null) {
                val session = Online.open()
                val (shared, details) = withContext(Dispatchers.IO) {
                    val shared = Moovit.sharedItinerary(session, link.sharedId)
                    shared to Moovit.hydrate(session, listOf(shared.trip))
                }
                val start = shared.from ?: shared.trip.legs.firstOrNull { it.shape.isNotEmpty() }?.shape?.firstOrNull()
                    ?.let { place(T("Start", "התחלה"), it.first, it.second) }
                val end = shared.to ?: shared.trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull()
                    ?.let { place(T("Destination", "יעד"), it.first, it.second) }
                fromPlace = start; toPlace = end
                showResults = false
                open = OpenTrip(shared.trip, details,
                    start?.name?.takeIf { it.isNotBlank() } ?: T("Start", "התחלה"),
                    end?.name?.takeIf { it.isNotBlank() } ?: T("Destination", "יעד"), backHome = true)
            } else {
                // A geo: link may name a place without a point: Kav's own place search finds it.
                val found = if (link.toLat == null && link.toName != null) withContext(Dispatchers.IO) {
                    Moovit.searchPlaces(Online.open(model.here ?: (32.0759 to 34.7745)), link.toName, model.here).firstOrNull()
                } else null
                val toLat = found?.lat ?: link.toLat ?: throw IllegalArgumentException("Missing destination coordinates")
                val toLon = found?.lon ?: link.toLon ?: throw IllegalArgumentException("Missing destination coordinates")
                fromPlace = if (link.fromLat != null && link.fromLon != null) place(link.fromName, link.fromLat, link.fromLon) else null
                toPlace = found ?: place(link.toName, toLat, toLon)
                departAt = link.departMs; timeType = Moovit.TIME_DEPARTURE
                linkTrip = link.rides.takeIf { it.isNotEmpty() && link.autoRun }
                showResults = link.autoRun
            }
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
    LaunchedEffect(showResults, fromLL, toLL, departAt, timeType, filters, stopovers, openingLink, saved) {
        if (!showResults || openingLink) { planning = false; return@LaunchedEffect }
        if (departAt != 0L && departAt < System.currentTimeMillis() && timeType != Moovit.TIME_LAST) {
            departAt = 0L; timeType = Moovit.TIME_DEPARTURE
            return@LaunchedEffect
        }
        raw = emptyList(); resolved = Moovit.Resolved(); error = null
        // A recent trip opens on the routes its search found, as Moovit's Recent Journeys do, until anything about
        // the trip changes.
        saved?.let { keep ->
            if (keep.key != planKey) { saved = null; return@LaunchedEffect }
            val found = withContext(Dispatchers.IO) { Prefs.savedRoutes(ctx, keep.trip) }
            // A trip saved before its routes were kept plans afresh.
            if (found == null) { saved = null; return@LaunchedEffect }
            plan = Moovit.Plan(found.routes, found.sections)
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
            val s = Online.open(fromLL)
            val res = withContext(Dispatchers.IO) {
                Moovit.planItineraries(
                    s, fromLL, toLL, departAt, timeType,
                    routeTypes = routeTypesFor(filters),
                    skipTaxi = stopovers.isNotEmpty() || ResultFilter.TAXI !in filters,
                    stopovers = stopovers, toName = toPlace?.name,
                )
            }
            plan = res
            raw = res.laidOut()
            linkTrip?.let { named ->
                if (raw.none { exactTrip(it, named) }) {
                    res.itineraries.firstOrNull { exactTrip(it, named) }
                        ?.let { raw = listOf(it) + raw }
                }
            }
            StopPhotos.prefetchIds(raw.flatMap { t -> t.rides.flatMap { r -> r.options.flatMap { listOf(it.fromStop, it.toStop) } } })
            val toHydrate = (raw + listOfNotNull(res.schedule())).distinct()
            resolved = withContext(Dispatchers.IO) { Moovit.hydrate(s, toHydrate) }
            model.activeJourney?.takeIf { active -> raw.any { it === active.trip } }?.let {
                model.activeJourney = it.copy(resolved = Moovit.Resolved(
                    it.resolved.lines + resolved.lines, it.resolved.stops + resolved.stops,
                    it.resolved.routeTypes + resolved.routeTypes,
                    resolved.live + it.resolved.live, resolved.shapes + it.resolved.shapes,
                    it.resolved.pollSecs, it.resolved.patterns + resolved.patterns,
                ))
            }
            // Every search that found routes becomes a recent trip with them, as in Moovit's Recent Journeys,
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
        } catch (e: Moovit.PlannerRefusal) {
            android.util.Log.i("KavPlan", "planner refused: ${e.code} ${e.title}")
            error = when (e.code) {
                Moovit.PLAN_TOO_CLOSE -> TOO_CLOSE
                Moovit.PLAN_NO_ROUTES -> T(
                    "Nothing is running for this trip at that time. Try another departure time.",
                    "אין קווים לנסיעה הזו בשעה הזו. נסו שעת יציאה אחרת.",
                )
                Moovit.PLAN_TOO_FAR -> T(
                    "These two places are too far apart to plan a trip between.",
                    "שני המקומות האלה רחוקים מכדי לתכנן נסיעה ביניהם.",
                )
                Moovit.PLAN_NO_COVERAGE -> T(
                    "Moovit has no timetable for this area.",
                    "ל-Moovit אין לוח זמנים לאזור הזה.",
                )
                else -> e.detail.ifBlank { e.title }.ifBlank {
                    T("Moovit would not plan this trip.", "Moovit לא תכנן את הנסיעה הזו.")
                }
            }
            planning = false
        } catch (e: Exception) {
            android.util.Log.e("KavPlan", "online plan failed", e)
            val reason = e.message ?: e.javaClass.simpleName
            error = if (uk.noammm.kav.hasNetwork(ctx)) T(
                "Moovit's planner did not answer: $reason",
                "התכנון של Moovit לא הגיב: $reason",
            ) else T(
                "No connection. Kav needs one to plan a trip.",
                "אין חיבור. Kav זקוק לחיבור כדי לתכנן נסיעה.",
            )
            planning = false
        }
        plannedFor = planKey
        linkTrip = null
    }

    LaunchedEffect(showResults, raw, resolved.pollSecs, open?.trip, model.activeJourney?.trip) {
        val active = model.activeJourney?.trip
        if (!showResults || raw.isEmpty() || (active != null && open?.trip === active)) return@LaunchedEffect
        val otherTrips = raw.filterNot { it === active }
        if (otherTrips.isEmpty()) return@LaunchedEffect
        // A recent trip's saved routes have no live times yet: they come at once, as with a search.
        var wait = saved == null || resolved.live.isNotEmpty()
        while (true) {
            if (wait) kotlinx.coroutines.delay(resolved.pollSecs.coerceIn(15, 120) * 1000L)
            wait = true
            val s = runCatching { Online.open() }.getOrNull() ?: break
            val refreshed = withContext(Dispatchers.IO) { Moovit.refreshLive(s, otherTrips, resolved) }
            resolved = Moovit.Resolved(
                refreshed.lines + resolved.lines, refreshed.stops + resolved.stops,
                refreshed.routeTypes + resolved.routeTypes, refreshed.live,
                refreshed.shapes + resolved.shapes, refreshed.pollSecs, refreshed.patterns + resolved.patterns,
            )
        }
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
                compareBy({ i -> i.legs.filter { it.kind == Moovit.LegKind.WALK }.sumOf { it.minutes } }, { it.durationMin }),
            )
            Sort.CHEAPEST -> kept.sortedWith(compareBy({ if (it.fare < 0) Int.MAX_VALUE else it.fare }, { it.durationMin }))
            Sort.LOWEST_CO2 -> kept.sortedWith(compareBy({ if (it.co2g < 0) Int.MAX_VALUE else it.co2g }, { it.durationMin }))
        }
    }

    androidx.activity.compose.BackHandler(enabled = showResults && open == null && picking == null && linkTrip == null) {
        showResults = false
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
            onSearch = { hereOrigin = null; stopovers = emptyList(); saved = null; linkError = null; fromPlace = null; departAt = 0L; timeType = Moovit.TIME_DEPARTURE; picking = "to" },
            onFavourite = { p ->
                hereOrigin = null; stopovers = emptyList(); saved = null; linkError = null; fromPlace = null; toPlace = p
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                showResults = true
            },
            onSetFavourite = { f -> model.settingFavourite = f },
            onTrip = { t ->
                hereOrigin = null; stopovers = t.stopovers; linkError = null; fromPlace = t.from; toPlace = t.to
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                saved = SavedTrip(t, listOf(t.from?.let { it.lat to it.lon }, t.to.lat to t.to.lon, 0L,
                    Moovit.TIME_DEPARTURE, filters, t.stopovers))
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
            ScreenHeader(T("Shared", "נסיעה"), T("trip", "משותפת"), back = { linkError = null; showResults = false })
            Note(linkError.orEmpty(), Modifier.padding(K.gap4))
            Text(T("Try again", "נסו שוב"), color = K.accent,
                modifier = Modifier.clickable { model.pendingLink = lastLink }.padding(K.gap4))
        }
        return@AnimatedContent
    }

    val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = resultsState,
            contentPadding = PaddingValues(bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            item(key = "trip") {
                PlanHeader(
                    // With no start picked the trip starts where you are, so it says so even before the fix arrives.
                    from = endpointName(fromPlace) ?: hereName(),
                    to = endpointName(toPlace) ?: T("Where do you want to go?…", "לאן תרצו להגיע?…"),
                    fromIsHere = fromIsHere,
                    toIsHere = isHere(toPlace),
                    onFrom = { picking = "from" },
                    onTo = { picking = "to" },
                    stops = stopovers.map { it.name },
                    onAddStop = { picking = "stop" },
                    onRemoveStop = { i -> stopovers = stopovers.filterIndexed { j, _ -> j != i } },
                    onSwap = {
                        val a = fromPlace
                        fromPlace = toPlace
                        toPlace = a ?: hereOrigin?.let(::herePlace)
                    },
                    onBack = { showResults = false },
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
            when {
                error == TOO_CLOSE -> item {
                    Note(
                        T(
                            "You're too close to your destination to plan a route.",
                            "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                        ),
                        Modifier.padding(K.gap4),
                    )
                }
                error != null -> item { Note(error.orEmpty(), Modifier.padding(K.gap4)) }
                toLL == null -> item {
                    Note(
                        if (fromLL == null) T(
                            "Choose where you are starting from, and where you are going.",
                            "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                        )
                        else T("Where do you want to go?", "לאן תרצו להגיע?"),
                        Modifier.padding(K.gap4),
                    )
                }
                fromLL == null && !findHereFailed -> item { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.fillParentMaxHeight(.6f)) }
                fromLL == null -> item {
                    Column(Modifier.padding(K.gap4)) {
                        Note(
                            if (findHereFailed) T("Couldn't get an accurate location. Try again or choose a start.",
                                "לא התקבל מיקום מדויק. נסו שוב או בחרו נקודת התחלה.")
                            else T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."),
                        )
                        if (findHereFailed && !findingHere) Text(T("Try again", "נסו שוב"), color = K.accent,
                            modifier = Modifier.clickable { hereRetry++ }.padding(vertical = K.gap3))
                    }
                }
                planning || plannedFor != planKey -> item { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.fillParentMaxHeight(.6f)) }
                shown.isEmpty() -> item {
                    Note(
                        if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                        else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
                        Modifier.padding(K.gap4),
                    )
                }
                else -> {
                    if (timeType != Moovit.TIME_LAST) item(key = "shift") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap1),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            fun nudge(delta: Long) {
                                val now = System.currentTimeMillis()
                                val base = if (departAt == 0L) now else departAt
                                departAt = (base + delta).let { if (it < now + 60_000L) 0L else it }
                                if (departAt == 0L) timeType = Moovit.TIME_DEPARTURE
                            }
                            ShiftButton(T("‹ Earlier", "› מוקדם יותר")) { nudge(-15 * 60_000L) }
                            Spacer(Modifier.weight(1f))
                            Text(
                                shown.firstOrNull()?.let {
                                    clockFormat().format(java.util.Date(it.dep * 1000))
                                }.orEmpty(),
                                fontSize = 12.sp, color = K.dim,
                            )
                            Spacer(Modifier.weight(1f))
                            ShiftButton(T("Later ›", "מאוחר יותר ‹")) { nudge(15 * 60_000L) }
                        }
                    }
                    // Late at night or on Shabbat Moovit may only offer walking and cycling.
                    if (raw.none { t -> t.legs.any { it.kind == Moovit.LegKind.RIDE } }) item(key = "no-transit") {
                        Note(
                            T("No public transport found for this time.", "לא נמצאה תחבורה ציבורית לשעה הזו."),
                            Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                        )
                    }
                    // Moovit's "View schedules": the train between the two stations, opened for its departures.
                    plan.schedule()?.takeIf { sort == Sort.RECOMMENDED }?.let { sched ->
                        val ride = sched.rides.firstOrNull()
                        val from = ride?.let { resolved.stopName(it.fromStop) }
                        val to = ride?.let { resolved.stopName(it.toStop) }
                        if (from != null && to != null) item(key = "schedule") {
                            Column(
                                Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(K.rCard)
                                    .clickable(role = Role.Button) { open = OpenTrip(sched, resolved, from, to) }
                                    .padding(K.gap4),
                                verticalArrangement = Arrangement.spacedBy(K.gap1),
                            ) {
                                Text(T("View schedules", "לוחות זמנים"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                                Text(T("From $from to $to", "מ$from אל $to"), fontSize = 14.sp, color = K.muted)
                            }
                        }
                    }
                    items(shown.size) { i ->
                        Column(Modifier.padding(horizontal = K.gap3)) {
                            val heading = plan.heading(shown[i])
                            if (sort == Sort.RECOMMENDED && heading.isNotBlank() &&
                                (i == 0 || plan.heading(shown[i - 1]) != heading)
                            ) {
                                Text(
                                    heading, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                                    modifier = Modifier.padding(start = K.gap1, top = K.gap3, bottom = 2.dp),
                                )
                            }
                            Box(Modifier.popIn(i, raw to sort)) {
                                ItineraryCard(shown[i], resolved) {
                                    open = OpenTrip(
                                        shown[i], resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
                                        toPlace?.name ?: T("Destination", "יעד"),
                                    )
                                }
                            }
                        }
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
            title = when (which) {
                "from" -> T("start…", "התחלה…")
                "stop" -> T("stop on the way…", "עצירה בדרך…")
                else -> T("destination…", "יעד…")
            },
            here = here,
            allowMyLocation = which != "fav" && which != "stop",
            initialSetting = if (which == "fav") settingFav else null,
            onMyLocation = { at ->
                if (which == "from") { fromPlace = null; hereOrigin = at }
                else { toPlace = herePlace(at); if (fromPlace == null) hereOrigin = at }
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onPick = { p ->
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
        onNow = { departAt = 0L; timeType = Moovit.TIME_DEPARTURE; whenOpen = false },
        onDismiss = { whenOpen = false },
    )
}

internal fun exactTrip(candidate: Moovit.Itinerary, named: List<MoovitLink.Ride>): Boolean {
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

internal fun sameLines(candidate: Moovit.Itinerary, named: List<MoovitLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i -> named[i].lineId in rides[i].lineChoices }
}

@Composable
private fun ShiftButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = K.text,
        modifier = Modifier.panel(K.rPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun whenLabel(departAt: Long, timeType: Int): String {
    if (timeType == Moovit.TIME_LAST) return T("Latest departure", "יציאה אחרונה")
    if (departAt <= 0L) return T("Depart now", "יציאה עכשיו")
    val now = java.util.Calendar.getInstance(ISRAEL)
    val then = java.util.Calendar.getInstance(ISRAEL).apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val stamp = java.text.SimpleDateFormat(
        if (sameDay) CLOCK else "EEE $CLOCK", T.locale,
    ).apply { timeZone = ISRAEL }.format(java.util.Date(departAt))
    return when (timeType) {
        Moovit.TIME_ARRIVAL -> T("Arrive by ", "הגעה עד ") + stamp
        else -> T("Depart ", "יציאה ") + stamp
    }
}
