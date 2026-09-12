package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.Tab
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A destination first, then the steps of a journey the user started. */
@Composable
internal fun HomeScreen(
    model: KavModel,
    recentTrips: List<RecentTrip>,
    /** Where the trip starts, worded exactly as the results header words it. */
    onSearch: () -> Unit,
    onFavourite: (Moovit.Place) -> Unit,
    onSetFavourite: (Favourite) -> Unit,
    onTrip: (RecentTrip) -> Unit,
    onResume: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val favourites = model.favourites
    var editing by remember { mutableStateOf<Favourite?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun save(list: List<Favourite>) = model.saveFavourites(ctx, list)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Home", "בית"), "", onSettings = { model.settingsOpen = true }, badge = model.update != null)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = K.gap4, end = K.gap4, top = K.gap2,
                bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            item {
                // The one question Home asks. Where the trip starts, and swapping the
                // two ends, belong to the results header, which shows both of them.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).glassSurface(24.dp)
                        .clickable(role = Role.Button, onClickLabel = T("Search destination", "חיפוש יעד"), onClick = onSearch)
                        .padding(horizontal = K.gap5, vertical = K.gap4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val w = size.width
                        drawCircle(K.accent, w * .29f, Offset(w * .40f, w * .40f), style = Stroke(w * .08f))
                        drawLine(K.accent, Offset(w * .63f, w * .63f), Offset(w * .88f, w * .88f), w * .08f, StrokeCap.Round)
                    }
                    Text(T("Where to?", "לאן?"), fontSize = 19.sp, color = K.muted, modifier = Modifier.weight(1f))
                }
            }
            item {
                // The rider's own places, one tap from the front door. A place already
                // set goes straight to its route; one not set yet opens the search.
                FavouriteStrip(
                    favourites,
                    onPick = { f -> f.place?.let { onFavourite(it) } ?: onSetFavourite(f) },
                    onAdd = { creating = true },
                    onEdit = { editing = it },
                    horizontalPadding = 0.dp,
                    // this is the strip whose order the rider sees every launch, so
                    // this is where sorting and removing live
                    onReorder = { save(it) },
                    onRemove = { f -> save(favourites.filter { it.id != f.id }) },
                )
            }
            item {
                val journey = model.activeJourney
                if (journey != null) JourneyCard(model, journey, onResume)
                else Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1)
                        .padding(K.gap5),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(T("Ready when you are", "מוכנים כשתרצו"), fontSize = 21.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Note(T("Choose a destination to see your route and what to do next.", "בחרו יעד כדי לראות את המסלול ואת הצעד הבא."))
                }
            }
            if (recentTrips.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    Text(T("Recent trips", "נסיעות אחרונות"), fontSize = 15.sp, color = K.muted, fontWeight = FontWeight.Medium)
                    Column(Modifier.clip(RoundedCornerShape(K.rCard)).background(K.surface1)) {
                        recentTrips.forEachIndexed { index, trip ->
                            if (index > 0) Box(
                                Modifier.padding(start = 52.dp, end = K.gap4).fillMaxWidth()
                                    .height(1.dp).background(K.border),
                            )
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                    .clickable(role = Role.Button) { onTrip(trip) }
                                    .padding(K.gap4),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(K.gap4),
                            ) {
                                ClockGlyph(K.dim, 20.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        trip.to.name, fontSize = 15.sp, color = K.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        T("from ", "מ־") + (trip.from?.name ?: T("Current location", "המיקום הנוכחי")),
                                        fontSize = 13.sp, color = K.dim,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(tripWhen(trip.at), fontSize = 12.sp, color = K.dim)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                    HomeShortcut(T("Stations", "תחנות"), false, Modifier.weight(1f)) { model.tab = Tab.Stations }
                    HomeShortcut(T("Lines", "קווים"), true, Modifier.weight(1f)) { model.tab = Tab.Lines }
                }
            }
        }
    }

    if (creating) FavouriteEditor(
        existing = null,
        onSave = { name, icon ->
            val fresh = Favourite("f${System.currentTimeMillis()}", name, icon, null)
            save(favourites + fresh)
            creating = false
            // named here, placed next, the same two steps the search strip takes
            onSetFavourite(fresh)
        },
        onRemove = null,
        onDismiss = { creating = false },
    )
    editing?.let { f ->
        FavouriteEditor(
            existing = f,
            onSave = { name, icon ->
                save(favourites.map { if (it.id == f.id) it.copy(name = name, icon = icon) else it })
                editing = null
            },
            onRemove = if (f.id == Favourite.HOME) null else { { save(favourites.filter { it.id != f.id }); editing = null } },
            onDismiss = { editing = null },
            // the same two steps a new favourite takes, minus the naming: close the
            // editor, then ask the search where this one is now
            onChangePlace = { editing = null; onSetFavourite(f) },
        )
    }
}

/** When a trip was taken: the clock today, the day once it is older than that. */
private fun tripWhen(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    fun sameDay() = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay()) return SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameDay()) return T("Yesterday", "אתמול")
    return SimpleDateFormat("d MMM", T.locale).format(Date(at))
}

/**
 * The journey under way, as the same cards navigation shows, one per step, to swipe
 * through. The card in front is the step the journey is on; it moves on by itself as
 * the journey does, and Resume opens navigation right there.
 */
@Composable
private fun JourneyCard(model: KavModel, journey: ActiveJourney, onResume: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(journey.trip) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(15_000)
        }
    }
    val steps = remember(journey.trip, journey.fromLabel, journey.toLabel) {
        buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    }
    val current = model.journeyStep.coerceIn(0, steps.lastIndex)
    val pager = rememberPagerState(initialPage = current) { steps.size }
    LaunchedEffect(current) { pager.animateScrollToPage(current) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(vertical = K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(T("Current trip", "הנסיעה הנוכחית"), fontSize = 13.sp, color = K.dim)
                Text(T("To ${journey.toLabel}", "אל ${journey.toLabel}"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                T("Resume", "המשך"), fontSize = 14.sp, color = K.accent, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                    .clickable(role = Role.Button, onClickLabel = T("Resume trip", "המשך נסיעה"), onClick = onResume)
                    .padding(horizontal = K.gap3, vertical = K.gap2),
            )
        }
        // Each card is as tall as its own step, not as tall as the tallest one, and a
        // change of height, the pager moving on, a stop list filling in, slides
        // rather than cuts.
        val ceiling = with(LocalDensity.current) { 230.dp.roundToPx() }
        val pageHeights = remember(steps) { mutableStateMapOf<Int, Int>() }
        HorizontalPager(
            state = pager,
            modifier = Modifier.pageSized(pager, pageHeights, ceiling, bottom = false),
            contentPadding = PaddingValues(horizontal = K.gap4),
            pageSpacing = K.gap2,
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
        ) { page ->
            Box(Modifier.fillMaxWidth().onSizeChanged { pageHeights[page] = it.height }.animateContentSize()) {
                StepCard(steps[page], journey.resolved, active = page == current, now = now, chosen = journey.chosen,
                    fix = model.fix)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in steps.indices) {
                val on = i == pager.currentPage
                Box(
                    Modifier.padding(horizontal = 3.dp).size(if (on) 7.dp else 5.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (i == current) K.accent else if (on) K.text else K.surface4),
                )
            }
        }
    }
}

/** One line of guidance for a step, for the small window and anything else that has room for one line. */
internal fun stepInstruction(step: Step, journey: ActiveJourney, lastLeg: Boolean, now: Long): Pair<String, String> {
    val r = journey.resolved
    fun time(utc: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(utc * 1000))
    fun stop(id: Int) = r.stopName(id)?.takeIf { it.isNotBlank() }
    fun line(ride: Moovit.Leg): String = ride.shortName.ifBlank {
        r.line(ride.lineId)?.number.orEmpty()
    }.ifBlank {
        T("the ", "ה") + modeName(modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1))).lowercase(Locale.US)
    }
    fun nextStop(id: Int) = stop(id) ?: if (lastLeg) journey.toLabel else T("your next stop", "התחנה הבאה שלכם")
    return when (step) {
        is Step.Start -> T("Leave at ${time(step.time)}", "יציאה בשעה ${time(step.time)}") to T("Start from ${step.label}", "התחלה מ${step.label}")
        is Step.Walk -> T("Walk to ${nextStop(step.toStop)}", "הליכה אל ${nextStop(step.toStop)}") to listOfNotNull(
            T("${step.leg.minutes} min", "${step.leg.minutes} דק׳").takeIf { step.leg.minutes > 0 },
            distanceLabel(step.leg.meters.toDouble()).takeIf { step.leg.meters > 0 },
        ).joinToString(" · ").ifBlank { T("Follow the walking route.", "עקבו אחרי מסלול ההליכה.") }
        is Step.Wait -> {
            val (ride, wait) = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0)
            val departure = r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }
                ?: Moovit.Departure(ride.tripId, ride.dep)
            if (departure.status == 3) T("${line(ride)} is cancelled", "${line(ride)} מבוטל") to T("Find another route before continuing.", "מצאו מסלול אחר לפני שתמשיכו.")
            else T("Wait for ${line(ride)}", "המתנה ל${line(ride)}") to listOfNotNull(
                stop(ride.fromStop),
                (if (departure.live) T("Live · ", "בזמן אמת · ") else T("Scheduled · ", "מתוזמן · ")) + whenLabel(departure.timeUtc, now),
            ).joinToString(" · ")
        }
        is Step.Ride -> {
            val (ride, _) = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0)
            T("Ride ${line(ride)}", "נסיעה ב${line(ride)}") to T("Get off at ${nextStop(ride.toStop)}", "ירידה ב${nextStop(ride.toStop)}")
        }
        is Step.Taxi -> T("Take a taxi", "קחו מונית") to T("Continue to ${nextStop(step.leg.toStop)}", "המשיכו אל ${nextStop(step.leg.toStop)}")
        is Step.Cycle -> T("Cycle to ${nextStop(step.leg.toStop)}", "רכיבה אל ${nextStop(step.leg.toStop)}") to T("Follow the cycling route.", "עקבו אחרי מסלול הרכיבה.")
        is Step.Arrive -> T("Arrive at ${step.label}", "הגעה אל ${step.label}") to T("Planned arrival ${time(step.time)}", "הגעה מתוכננת בשעה ${time(step.time)}")
    }
}

@Composable
private fun HomeShortcut(label: String, lines: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(K.rCard)).background(K.surface1)
            .clickable(role = Role.Button, onClick = onClick).padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            if (lines) listOf(.23f, .5f, .77f).forEach { y ->
                drawCircle(K.accent, w * .065f, Offset(w * mirrorX(.15f), w * y))
                drawLine(K.accent, Offset(w * mirrorX(.35f), w * y), Offset(w * mirrorX(.86f), w * y), w * .08f, StrokeCap.Round)
            } else {
                drawCircle(K.accent, w * .22f, Offset(w * .5f, w * .32f), style = Stroke(w * .08f))
                drawLine(K.accent, Offset(w * .5f, w * .56f), Offset(w * .5f, w * .88f), w * .08f, StrokeCap.Round)
            }
        }
        Text(label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
    }
}
