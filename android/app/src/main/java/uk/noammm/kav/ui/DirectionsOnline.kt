@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Fallback
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Moovit.Place

/**
 * Home opens the existing planner after a destination is picked. A started journey
 * belongs to the app model, so going home or visiting another tab does not end it.
 * `backHome` marks a plan that Home opened directly, a recent trip, whose Back has
 * no results list to return to.
 */
private data class OpenTrip(
    val trip: Moovit.Itinerary,
    val resolved: Moovit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val resume: Boolean = false,
    val backHome: Boolean = false,
)

/**
 * Moovit's own sort options, from TripPlannerSortType in the decompiled app: the
 * default is NO_CLIENT_SORTING, the server's ranking, untouched, and each other
 * option replaces it with a single comparator.
 */
private enum class Sort(val label: String) {
    RECOMMENDED("Recommended"),
    FASTEST("Fastest"),
    EARLIEST_DEPARTURE("Departs first"),
    EARLIEST_ARRIVAL("Arrives first"),
    LEAST_TRANSFERS("Fewest transfers"),
    LEAST_WALKING("Least walking"),
    CHEAPEST("Cheapest"),
    LOWEST_CO2("Lowest CO2"),
}

/** Closer than this and there is nothing to plan: the planner answers 424 to it anyway. */
private const val TOO_CLOSE_M = 120.0
private const val TOO_CLOSE = "too-close"

@Composable
fun DirectionsOnline(model: KavModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val here = model.here
    var fromPlace by remember { mutableStateOf<Place?>(null) }
    var toPlace by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var showResults by remember { mutableStateOf(false) }

    var plan by remember { mutableStateOf(Moovit.Plan()) }
    var raw by remember { mutableStateOf<List<Moovit.Itinerary>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    /** planned on the device even though the network is up, say which, and why */
    var degraded by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(Moovit.Resolved()) }

    var open by remember { mutableStateOf<OpenTrip?>(null) }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    /** epoch millis chosen by Earlier / Later; 0 = leave now. */
    var departAt by remember { mutableLongStateOf(0L) }
    /** MVTimeType: what departAt means, leave at, arrive by, or last departure. */
    var timeType by remember { mutableIntStateOf(Moovit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
    /** a recent trip was tapped: open its own route straight away, never the list */
    var autoOpen by remember { mutableStateOf<RecentTrip?>(null) }
    val filters = model.filters

    LaunchedEffect(model.returnHome) {
        if (model.returnHome) {
            open = null; picking = null; showResults = false; autoOpen = null
            model.returnHome = false
        }
    }

    // a place handed over from the Stations tab
    LaunchedEffect(model.pendingFrom, model.pendingTo) {
        if (model.pendingFrom != null || model.pendingTo != null) showResults = true
        model.pendingFrom?.let { fromPlace = it; model.pendingFrom = null }
        model.pendingTo?.let { toPlace = it; model.pendingTo = null }
    }

    val fromLL = fromPlace?.let { it.lat to it.lon } ?: here
    val toLL = toPlace?.let { it.lat to it.lon }
    val fromIsHere = fromPlace == null && here != null

    LaunchedEffect(showResults, fromLL, toLL, departAt, timeType, filters) {
        if (!showResults) { planning = false; return@LaunchedEffect }
        raw = emptyList(); resolved = Moovit.Resolved(); error = null
        if (fromLL == null || toLL == null) { planning = false; return@LaunchedEffect }
        if (metres(fromLL.first, fromLL.second, toLL.first, toLL.second) < TOO_CLOSE_M) {
            error = TOO_CLOSE; planning = false; return@LaunchedEffect
        }
        planning = true
        try {
            val s = Online.session ?: withContext(Dispatchers.IO) { Moovit.register(fromLL.first, fromLL.second) }
                .also { Online.session = it }
            val res = withContext(Dispatchers.IO) {
                Moovit.planItineraries(
                    s, fromLL, toLL, departAt, timeType,
                    routeTypes = routeTypesFor(filters), skipTaxi = ResultFilter.TAXI !in filters,
                )
            }
            plan = res
            // the server's own section table decides order and how many of each to show
            raw = res.laidOut()
            offline = false; degraded = false
            planning = false
            // Line numbers and stop names are separate lookups; fill them in behind
            // the cards rather than making the whole screen wait on them.
            resolved = withContext(Dispatchers.IO) { Moovit.hydrate(s, raw) }
            model.activeJourney?.takeIf { active -> raw.any { it === active.trip } }?.let {
                model.activeJourney = it.copy(resolved = Moovit.Resolved(
                    it.resolved.lines + resolved.lines, it.resolved.stops + resolved.stops,
                    it.resolved.routeTypes + resolved.routeTypes,
                    resolved.live + it.resolved.live, resolved.shapes + it.resolved.shapes,
                    it.resolved.pollSecs, it.resolved.patterns + resolved.patterns,
                ))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Two different failures, and they must not print the same sentence: no
            // network at all, or a network that works and a call that still failed.
            // Claiming "no connection" on a phone with five bars is just a lie.
            android.util.Log.e("KavPlan", "online plan failed", e)
            // 424 is the planner's word for "nothing to plan between these two": it is
            // what a start and a destination a few doors apart get
            if (e.message?.contains("424") == true &&
                metres(fromLL.first, fromLL.second, toLL.first, toLL.second) < 600
            ) {
                error = TOO_CLOSE; planning = false; return@LaunchedEffect
            }
            val online = uk.noammm.kav.hasNetwork(ctx)
            planning = true
            val net = model.net ?: try {
                uk.noammm.kav.loadNet(ctx).also { model.net = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { null }
            if (net != null) {
                val (its, res) = withContext(Dispatchers.Default) { Fallback.plan(net, fromLL, toLL) }
                raw = its; plan = Moovit.Plan(its); resolved = res
                offline = its.isNotEmpty()
                degraded = its.isNotEmpty() && online
                onlineError = e.message ?: e.javaClass.simpleName
                error = if (its.isEmpty()) {
                    if (online) "Moovit's planner did not answer: ${e.message ?: e.javaClass.simpleName}"
                    else "No connection, and the offline timetable has no route for this trip."
                } else null
            } else {
                error = e.message ?: e.javaClass.simpleName
            }
            planning = false
        }
    }

    // Live arrivals go stale fast; refresh only that layer, on the server's own
    // interval, so the times keep their colour without re-planning the trip.
    LaunchedEffect(showResults, raw, resolved.pollSecs, open?.trip, model.activeJourney?.trip) {
        val active = model.activeJourney?.trip
        if (!showResults || raw.isEmpty() || (active != null && open?.trip === active)) return@LaunchedEffect
        val otherTrips = raw.filterNot { it === active }
        if (otherTrips.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(resolved.pollSecs.coerceIn(15, 120) * 1000L)
            val s = Online.session ?: break
            resolved = withContext(Dispatchers.IO) { Moovit.refreshLive(s, otherTrips, resolved) }
        }
    }

    // raw already arrives in the server's own layout; a sort chip replaces that order
    // wholesale, which is what Moovit's own NO_CLIENT_SORTING vs sorted chips do.
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

    // A trip taken before opens straight at its plan, the screen you read before
    // pressing Start, on the route it was taken by rather than whatever today's plan
    // ranks first. Only the route is remembered, so the times are this morning's and
    // not the ones the trip ended with. If that route is not running now the top
    // result stands in; with no result at all the list is left to say so.
    LaunchedEffect(autoOpen, showResults, planning, shown.firstOrNull(), error) {
        val taken = autoOpen ?: return@LaunchedEffect
        if (!showResults) { autoOpen = null; return@LaunchedEffect }
        if (planning) return@LaunchedEffect
        autoOpen = null
        if (error != null) return@LaunchedEffect
        val again = shown.firstOrNull { sameRoute(it, taken) }
            ?: shown.firstOrNull() ?: return@LaunchedEffect
        open = OpenTrip(
            again, resolved, fromPlace?.name ?: "Current location",
            toPlace?.name ?: "Destination", backHome = true,
        )
    }

    androidx.activity.compose.BackHandler(enabled = showResults && open == null && picking == null && autoOpen == null) {
        showResults = false
    }

    androidx.compose.animation.AnimatedContent(
        targetState = Triple(open, showResults, autoOpen != null),
        modifier = Modifier.fillMaxSize(),
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
                        model.activeJourney = ActiveJourney(chosen.trip, detailResolved, chosen.fromLabel, chosen.toLabel)
                    }
                },
                onNavigating = { model.navigating = it },
                onEnd = {
                    if (model.activeJourney?.trip === chosen.trip) model.activeJourney = null
                    // worth remembering once it has actually been taken, not before
                    toPlace?.let {
                        Prefs.rememberTrip(
                            ctx, fromPlace, it, System.currentTimeMillis(), chosen.trip,
                        )
                    }
                    open = null
                    showResults = false
                },
            )
            return@AnimatedContent
        }

    if (!displayingResults) {
        HomeScreen(
            model = model,
            // the active trip and the two before it, or three taken trips when idle
            recentTrips = Prefs.trips(ctx).take(if (model.activeJourney != null) 2 else 3),
            onSearch = {
                fromPlace = null; departAt = 0L; timeType = Moovit.TIME_DEPARTURE; picking = "to"
            },
            onTrip = { t ->
                fromPlace = t.from; toPlace = t.to
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                autoOpen = t; showResults = true
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
        LoadingScreen("Finding your route") { autoOpen = null; showResults = false }
        return@AnimatedContent
    }

    Column(Modifier.fillMaxSize()) {
        PlanHeader(
            from = fromPlace?.name ?: if (here != null) "Current location" else "Choose a start…",
            to = toPlace?.name ?: "Where do you want to go?…",
            fromIsHere = fromIsHere,
            onFrom = { picking = "from" },
            onTo = { picking = "to" },
            onSwap = {
                val a = fromPlace
                fromPlace = toPlace
                toPlace = a ?: here?.let { Place("Current location", "", it.first, it.second) }
            },
            onTune = { model.settingsOpen = true },
            onBack = { showResults = false },
        )
        DepartRow(
            whenLabel(departAt, timeType),
            onWhen = { whenOpen = true },
            onMap = null,
        )
        PreciseLocationNudge()

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = K.gap3, vertical = K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(sort.label, lit = sort != Sort.RECOMMENDED, caret = true) {
                sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
            }
            Box(Modifier.height(20.dp).width(1.dp).background(K.border))
            FilterChip("Fewest transfers", lit = sort == Sort.LEAST_TRANSFERS) {
                sort = if (sort == Sort.LEAST_TRANSFERS) Sort.RECOMMENDED else Sort.LEAST_TRANSFERS
            }
            FilterChip("Least walking", lit = sort == Sort.LEAST_WALKING) {
                sort = if (sort == Sort.LEAST_WALKING) Sort.RECOMMENDED else Sort.LEAST_WALKING
            }

        }

        when {
            error == TOO_CLOSE -> Note(
                "You're too close to your destination to plan a route.",
                Modifier.padding(K.gap4),
            )
            error != null -> Note("Could not reach Moovit: $error", Modifier.padding(K.gap4))
            toLL == null -> Note(
                if (here == null) "Choose where you are starting from, and where you are going."
                else "Where do you want to go?",
                Modifier.padding(K.gap4),
            )
            fromLL == null -> Note("Choose a start to find routes.", Modifier.padding(K.gap4))
            planning -> LoadingPulse("Finding routes", Modifier.padding(K.gap4))
            shown.isEmpty() -> Note(
                if (raw.isEmpty()) "No routes found for this trip."
                else "Every route found is switched off in your filters.",
                Modifier.padding(K.gap4),
            )
            // Moovit files its results under headings it sends with them ("Taxi &
            // Ride Hailing", "Walking & Biking Routes"); print each one where it first
            // appears rather than inventing a grouping of our own.
            else -> Column {
                // Moovit's Earlier / Later: re-plan from a shifted departure time
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val base = if (departAt > 0L) departAt else System.currentTimeMillis()
                    ShiftButton("‹ Earlier") {
                        // half an hour back can land before now, the same clamp
                        // Moovit's own Earlier goes through
                        val (ms, type) = clampDepart(
                            base - 30 * 60_000L, timeType, System.currentTimeMillis(),
                        )
                        departAt = ms; timeType = type
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        shown.firstOrNull()?.let {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                .format(java.util.Date(it.dep * 1000))
                        }.orEmpty(),
                        fontSize = 12.sp, color = K.dim,
                    )
                    Spacer(Modifier.weight(1f))
                    ShiftButton("Later ›") { departAt = base + 30 * 60_000L }
                }
                if (offline) Note(
                    if (degraded)
                        "Moovit's planner did not answer ($onlineError), planned on your " +
                            "phone instead. No live times, no fares."
                    else "No connection, planned on your phone from the timetable in the " +
                        "app. No live times, no fares.",
                    Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                )
                LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = K.gap3, end = K.gap3,
                    bottom = K.gap6 + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                items(shown.size) { i ->
                    val heading = plan.heading(shown[i])
                    if (heading.isNotBlank() && (i == 0 || plan.heading(shown[i - 1]) != heading)) {
                        Text(
                            heading, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                            modifier = Modifier.padding(start = K.gap1, top = K.gap3, bottom = 2.dp),
                        )
                    }
                    Box(Modifier.popIn(i, raw)) {
                        ItineraryCard(shown[i], resolved) {
                            // picking a route for a trip already in the recents makes it
                            // that trip's route, so tapping it there comes back here
                            toPlace?.let { to -> Prefs.noteTripRoute(ctx, fromPlace, to, shown[i]) }
                            open = OpenTrip(
                                shown[i], resolved, fromPlace?.name ?: "Current location",
                                toPlace?.name ?: "Destination",
                            )
                        }
                    }
                }
                }
            }
        }
    }

    }

    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) forward() else backward() },
        label = "placePicker",
    ) { which ->
        if (which != null) {
        PlacePicker(
            title = if (which == "from") "start…" else "destination…",
            here = here,
            // A destination can be "where I am" too, planning back to here from a
            // start you picked is an ordinary thing to want, and the origin picker
            // offered it while this one silently did not.
            allowMyLocation = here != null,
            onMyLocation = {
                if (which == "from") fromPlace = null
                else toPlace = here?.let { Place("Current location", "", it.first, it.second) }
                picking = null
                showResults = true
            },
            onPick = { p ->
                if (which == "from") fromPlace = p else toPlace = p
                picking = null
                showResults = true
            },
            onDismiss = { picking = null },
        )
        }
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

@Composable
private fun FilterChip(label: String, lit: Boolean, caret: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.glassSurface(K.rPill)
            .then(if (lit) Modifier.background(K.plateStrong) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = if (lit) K.text else K.muted)
        if (caret) { Spacer(Modifier.width(7.dp)); CaretSmall() }
    }
}

@Composable
private fun CaretSmall() {
    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
        val w = size.width; val h = size.height
        drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .18f, h * .38f),
            androidx.compose.ui.geometry.Offset(w * .5f, h * .66f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .5f, h * .66f),
            androidx.compose.ui.geometry.Offset(w * .82f, h * .38f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/**
 * The same route as a trip taken before: the same lines, in the same order, whatever
 * the clock says today. A leg offering a choice of lines ("472 / 473") can promote
 * either of them between plans, so a remembered id counts wherever it appears in that
 * leg's choices. Comparing `group` as well keeps a remembered taxi from reopening as
 * a walk, since neither has a ride leg to name. An entry from before routes were
 * recorded matches nothing, and falls back to the top result.
 */
internal fun sameRoute(candidate: Moovit.Itinerary, taken: RecentTrip): Boolean {
    if (taken.group < 0) return false
    if (candidate.group != taken.group) return false
    val rides = candidate.rides
    if (rides.size != taken.lines.size) return false
    return taken.lines.indices.all { i -> taken.lines[i] in rides[i].lineChoices }
}

/** The Earlier / Later pair Moovit puts under a set of results. */
@Composable
private fun ShiftButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = K.text,
        modifier = Modifier.clip(RoundedCornerShape(K.rPill)).background(K.surface1)
            .border(1.dp, K.border, RoundedCornerShape(K.rPill))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** What the chip says: the plan the rider chose, then the time they gave it. */
private fun whenLabel(departAt: Long, timeType: Int): String {
    // a latest departure names no clock time, the mode is the whole answer
    if (timeType == Moovit.TIME_LAST) return "Latest departure"
    if (departAt <= 0L) return "Depart now"
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val stamp = java.text.SimpleDateFormat(
        if (sameDay) "HH:mm" else "EEE HH:mm", java.util.Locale.getDefault(),
    ).format(java.util.Date(departAt))
    return when (timeType) {
        Moovit.TIME_ARRIVAL -> "Arrive by " + stamp
        else -> "Depart " + stamp
    }
}
