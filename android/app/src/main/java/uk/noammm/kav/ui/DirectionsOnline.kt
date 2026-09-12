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

/** Closer than this and there is nothing to plan: the planner answers 424 to it anyway. */
private const val TOO_CLOSE_M = 120.0
private const val TOO_CLOSE = "too-close"

/**
 * Where you are, as somewhere you can be sent.
 *
 * The origin says "here" by being null. The destination cannot: null there means
 * nowhere chosen yet, so swapping the two ends - or asking to travel back to where
 * you are - has to hand the destination a real place instead. It was built in two
 * places and recognised in none, which is why a swapped-in "Current location" landed
 * in the destination slot as an ordinary grey address rather than the lit one it is.
 *
 * Recognised by name against both languages, not the one in force: the place keeps
 * the word it was built with, and switching language mid-trip must not turn it back
 * into an address.
 */
private val HERE = listOf("Current location", "המיקום הנוכחי")

private fun hereName() = T(HERE[0], HERE[1])

private fun herePlace(at: Pair<Double, Double>) = Place(hereName(), "", at.first, at.second)

private fun isHere(p: Place?) = p != null && p.name in HERE

/** The name to print for an endpoint; the here-place re-reads its own, so a language
 *  change re-labels it instead of leaving the word it happened to be built with. */
private fun endpointName(p: Place?) = if (isHere(p)) hereName() else p?.name

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

    // "Current location" is where you were when you searched, not an origin that
    // follows you around: the fix moves every few seconds, and a plan keyed to it
    // re-plans on every tick, searched mid-ride, the list never stops loading.
    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(showResults, fromPlace == null, here == null) {
        hereOrigin = if (showResults && fromPlace == null) hereOrigin ?: here else null
    }
    val fromLL = fromPlace?.let { it.lat to it.lon } ?: hereOrigin ?: here
    val toLL = toPlace?.let { it.lat to it.lon }
    val fromIsHere = if (fromPlace == null) here != null else isHere(fromPlace)

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
        } catch (e: Moovit.PlannerRefusal) {
            // The planner answered, and the answer was no. Say which no: its own
            // codes are exact, and reporting them as "the planner did not answer"
            // is what made a Saturday with nothing running look like a server fault.
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
                // an unknown code still has Moovit's own sentence, in the rider's
                // own language, which beats anything invented for it here
                else -> e.detail.ifBlank { e.title }.ifBlank {
                    T("Moovit would not plan this trip.", "Moovit לא תכנן את הנסיעה הזו.")
                }
            }
            planning = false
        } catch (e: Exception) {
            // The planner never answered at all. Two ways for that to happen, and
            // they must not print the same sentence: no network, or a network that
            // works and a call that still failed. Claiming "no connection" on a
            // phone with five bars is just a lie.
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
            again, resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
            toPlace?.name ?: T("Destination", "יעד"), backHome = true,
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
            // Home names only the destination, so it cannot show a start left over
            // from the last trip either: it plans from where you are, the way the
            // favourites below it already do, and the results header is where a
            // different start gets chosen and can be seen.
            onSearch = { fromPlace = null; departAt = 0L; timeType = Moovit.TIME_DEPARTURE; picking = "to" },
            onFavourite = { p ->
                fromPlace = null; toPlace = p
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                showResults = true
            },
            onSetFavourite = { f -> model.settingFavourite = f },
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
        LoadingScreen(T("Finding your route", "מוצאים לכם מסלול")) { autoOpen = null; showResults = false }
        return@AnimatedContent
    }

    Column(Modifier.fillMaxSize()) {
        PlanHeader(
            from = endpointName(fromPlace) ?: if (here != null) hereName() else T("Choose a start…", "בחרו נקודת התחלה…"),
            to = endpointName(toPlace) ?: T("Where do you want to go?…", "לאן תרצו להגיע?…"),
            fromIsHere = fromIsHere,
            toIsHere = isHere(toPlace),
            onFrom = { picking = "from" },
            onTo = { picking = "to" },
            onSwap = {
                val a = fromPlace
                fromPlace = toPlace
                toPlace = a ?: here?.let(::herePlace)
            },
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
            FilterChip(sort.labelText(), lit = sort != Sort.RECOMMENDED, caret = true) {
                sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
            }
            Box(Modifier.height(20.dp).width(1.dp).background(K.border))
            FilterChip(T("Fewest transfers", "פחות החלפות"), lit = sort == Sort.LEAST_TRANSFERS) {
                sort = if (sort == Sort.LEAST_TRANSFERS) Sort.RECOMMENDED else Sort.LEAST_TRANSFERS
            }
            FilterChip(T("Least walking", "פחות הליכה"), lit = sort == Sort.LEAST_WALKING) {
                sort = if (sort == Sort.LEAST_WALKING) Sort.RECOMMENDED else Sort.LEAST_WALKING
            }

        }

        when {
            error == TOO_CLOSE -> Note(
                T(
                    "You're too close to your destination to plan a route.",
                    "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                ),
                Modifier.padding(K.gap4),
            )
            // Printed as it stands. Whoever set it already knows whether Moovit
            // refused the trip or never answered, and wrapping a refusal in "could
            // not reach Moovit" is how "nothing is running tonight" came to read as
            // a broken server.
            error != null -> Note(error!!, Modifier.padding(K.gap4))
            toLL == null -> Note(
                if (here == null) T(
                    "Choose where you are starting from, and where you are going.",
                    "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                )
                else T("Where do you want to go?", "לאן תרצו להגיע?"),
                Modifier.padding(K.gap4),
            )
            fromLL == null -> Note(T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."), Modifier.padding(K.gap4))
            planning -> LoadingBlock(T("Finding routes", "מחפשים מסלולים"))
            shown.isEmpty() -> Note(
                if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
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
                    ShiftButton(T("‹ Earlier", "› מוקדם יותר")) {
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
                    ShiftButton(T("Later ›", "מאוחר יותר ‹")) { departAt = base + 30 * 60_000L }
                }
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

    }

    // The home strip asked to place a favourite: open the picker straight into
    // setting it, planning nothing. "fav" keeps it apart from a from/to pick.
    val settingFav = model.settingFavourite
    LaunchedEffect(settingFav) { if (settingFav != null) picking = "fav" }

    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) forward() else backward() },
        label = "placePicker",
    ) { which ->
        if (which != null) {
        PlacePicker(
            title = if (which == "from") T("start…", "התחלה…") else T("destination…", "יעד…"),
            here = here,
            // A destination can be "where I am" too, planning back to here from a
            // start you picked is an ordinary thing to want, and the origin picker
            // offered it while this one silently did not.
            // offered even with no fix yet: the row itself goes and asks for one
            allowMyLocation = which != "fav",
            initialSetting = if (which == "fav") settingFav else null,
            onMyLocation = {
                if (which == "from") fromPlace = null
                else toPlace = here?.let(::herePlace)
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onPick = { p ->
                if (which == "from") fromPlace = p else toPlace = p
                picking = null
                // Picking a start on Home with nowhere to go yet is half a trip; stay
                // on Home so the destination can be named, rather than planning to
                // nothing and showing an error for it.
                if (which != "from" || toPlace != null) showResults = true
                // that search is finished; the next one starts clean
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
    if (timeType == Moovit.TIME_LAST) return T("Latest departure", "יציאה אחרונה")
    if (departAt <= 0L) return T("Depart now", "יציאה עכשיו")
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val stamp = java.text.SimpleDateFormat(
        if (sameDay) "HH:mm" else "EEE HH:mm", java.util.Locale.getDefault(),
    ).format(java.util.Date(departAt))
    return when (timeType) {
        Moovit.TIME_ARRIVAL -> T("Arrive by ", "הגעה עד ") + stamp
        else -> T("Depart ", "יציאה ") + stamp
    }
}
