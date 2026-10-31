package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Transit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat

@Composable
internal fun HomeScreen(
    model: KavModel,
    recentTrips: List<RecentTrip>,
    onSearch: () -> Unit,
    onFavourite: (Transit.Place) -> Unit,
    onSetFavourite: (Favourite) -> Unit,
    onTrip: (RecentTrip) -> Unit,
    onResume: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val favourites = model.favourites
    var editing by remember { mutableStateOf<Favourite?>(null) }
    var creating by remember { mutableStateOf(false) }
    var allTrips by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(allTrips) { allTrips = false }
    androidx.compose.animation.AnimatedContent(
        targetState = allTrips, modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState) forward() else backward() }, label = "recentTrips",
    ) { history ->
    if (history) {
        Column(Modifier.fillMaxSize().background(K.bg)) {
            ScreenHeader(T("Recent trips", "נסיעות אחרונות"), back = { allTrips = false })
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = K.gap4, end = K.gap4, top = K.gap2, bottom = K.gap6 + LocalBottomBarInset.current)) {
                items(recentTrips.size) { index ->
                    Box(Modifier.groupItem(first = index == 0, last = index == recentTrips.lastIndex, inset = RECENT_INSET)) {
                        RecentTripRow(recentTrips[index]) { allTrips = false; onTrip(recentTrips[index]) }
                    }
                }
            }
        }
        return@AnimatedContent
    }
    fun save(list: List<Favourite>) = model.saveFavourites(ctx, list)
    val recentNames = remember(recentTrips) {
        recentTrips.map { it.to.name.substringBefore("/").trim() }.filter { it.isNotBlank() }.distinct().take(2)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // No title: where to is what this screen is for, so it leads, with Settings beside it.
            FloatingTop(top = {
                Row(
                    Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap3).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 52.dp)
                            .glassSurface(26.dp)
                            .clickable(role = Role.Button, onClickLabel = T("Search destination", "חיפוש יעד"), onClick = onSearch)
                            .padding(horizontal = K.gap4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(K.gap3),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
                        WhereTo(recentNames, Modifier.weight(1f))
                    }
                    SettingsButton(model.update != null && Shown.updatePrompts) { model.settingsOpen = true }
                }
            }, estimate = K.gap3 + 52.dp, fade = 28.dp) { topSpace, backdrop ->
            LazyColumn(
                Modifier.fillMaxSize().then(backdrop),
                contentPadding = PaddingValues(start = K.gap4, end = K.gap4, top = topSpace + K.gap4,
                    bottom = K.gap6 + LocalBottomBarInset.current),
            ) {
                item {
                    FavouriteStrip(
                        favourites,
                        // A trip to a saved place goes by the name it was saved under, with where it is under that.
                        onPick = { f -> f.place?.let { onFavourite(f.named(it)) } ?: onSetFavourite(f) },
                        onAdd = { creating = true },
                        onEdit = { editing = it },
                        horizontalPadding = 0.dp,
                        onReorder = { save(it) },
                        onRemove = { f -> save(favourites.filter { it.id != f.id }) },
                    )
                }
                model.activeJourney?.let { journey -> item { Box(Modifier.padding(top = K.gap4)) { JourneyCard(model, journey, onResume) } } }
                // Paying, the tickets still running and the history, in one card, unless the rider has hidden paying.
                if (Shown.payments) item {
                    Column(Modifier.padding(top = K.gap4).fillMaxWidth().panel(K.rCard)) {
                        ListRow(
                            T("Pay for a ride", "תשלום על נסיעה"),
                            leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(24.dp)) },
                            onClick = { model.payOpen = true },
                        )
                        if (Payer.signedIn) {
                            PayTicketsCard(model)
                            Separator()
                            SpendSummary { model.payHistoryOpen = true }
                        }
                    }
                }
                if (recentTrips.isNotEmpty()) item {
                    Column {
                        SectionHeader(
                            T("Recent trips", "נסיעות אחרונות"),
                            action = if (recentTrips.size > 3) T("See all", "הכול") else null, onAction = { allTrips = true },
                        )
                        ListGroup(inset = RECENT_INSET) {
                            recentTrips.take(if (model.activeJourney != null) 2 else 3).forEach { trip ->
                                row { RecentTripRow(trip) { onTrip(trip) } }
                            }
                        }
                    }
                }
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
            onChangePlace = { editing = null; onSetFavourite(f) },
        )
    }
    }
}

// The name a trip planned from the rider's own position is saved with (DirectionsOnline), in either language.
private val SAVED_STARTS = setOf("Saved start", "נקודת ההתחלה השמורה")

// A recent trip's row: its separator starts under the text, past the clock.
private val RECENT_INSET = K.gap4 + 24.dp + K.gap3

@Composable
private fun RecentTripRow(trip: RecentTrip, onClick: () -> Unit) {
    ListRow(
        trip.to.name,
        subtitle = trip.from?.name?.takeUnless { it in SAVED_STARTS }?.let { T("from ", "מ־") + it }
            ?: T("from Current location", "מהמיקום הנוכחי"),
        value = tripWhen(trip.at),
        leading = { Icon(Icons.Rounded.Schedule, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(24.dp)) },
        onClick = onClick,
        chevron = false,
    )
}

private fun tripWhen(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    fun sameDay() = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay()) return SimpleDateFormat(CLOCK, Locale.US).format(Date(at))
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameDay()) return T("Yesterday", "אתמול")
    return SimpleDateFormat("d MMM", T.locale).format(Date(at))
}

@Composable
private fun JourneyCard(model: KavModel, journey: ActiveJourney, onResume: () -> Unit) {
    PlannedPlatforms(journey.trip, journey.resolved)
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(current) { pager.animateScrollToPage(current) }
    Column(
        Modifier.fillMaxWidth().panel(K.rCard).padding(vertical = K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(T("Current trip", "הנסיעה הנוכחית"), style = K.type.caption)
                Text(T("To ${journey.toLabel}", "אל ${journey.toLabel}"), style = K.type.headline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Just the play mark, no circle around it; still a full 48 to the touch.
            Box(
                Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = T("Resume trip", "המשך נסיעה") }
                    .clickable(role = Role.Button, onClick = onResume),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = K.accentInk, modifier = Modifier.size(32.dp)) }
        }
        val ceiling = with(LocalDensity.current) { 230.dp.roundToPx() }
        val pageHeights = remember(steps) { mutableStateMapOf<Int, Int>() }
        HorizontalPager(
            state = pager,
            modifier = Modifier.pageSized(pager, pageHeights, ceiling, bottom = false),
            pageSpacing = K.gap4,
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
        ) { page ->
            Column(Modifier.fillMaxWidth().padding(horizontal = K.gap4).onSizeChanged { pageHeights[page] = it.height }.animateContentSize()) {
                StepCard(steps[page], journey.resolved, active = page == current, now = now, chosen = journey.chosen,
                    fix = model.fix,
                    onChoose = { leg, option ->
                        model.activeJourney?.takeIf { it.trip === journey.trip }
                            ?.let { model.activeJourney = it.copy(chosen = it.chosen + (leg to option)) }
                    },
                    pay = if (Shown.payments) { leg, ride -> TripPay(model, leg, ride, journey.resolved) } else null,
                    report = { model.report = it })
            }
        }
        // The dots in the middle; back to the step on now, from another, at the end of the same row.
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = K.gap3), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in steps.indices) StepDot(looked = i == pager.currentPage, now = i == current)
            }
            androidx.compose.animation.AnimatedVisibility(
                pager.currentPage != current, Modifier.align(Alignment.CenterEnd),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                KavIconButton(
                    Icons.Rounded.MyLocation, T("Back to the current step", "חזרה לשלב הנוכחי"),
                    { scope.launch { pager.animateScrollToPage(current) } }, tint = K.accentInk, size = 36.dp,
                )
            }
        }
    }
}

internal fun DrawScope.drawQr() {
    val w = size.width
    for ((x, y) in listOf(.10f to .10f, .58f to .10f, .10f to .58f)) {
        drawRoundRect(K.accent, Offset(w * x, w * y), Size(w * .32f, w * .32f), CornerRadius(w * .06f), style = Stroke(w * .08f))
    }
    drawRect(K.accent, Offset(w * .62f, w * .62f), Size(w * .12f, w * .12f))
    drawRect(K.accent, Offset(w * .80f, w * .80f), Size(w * .10f, w * .10f))
}

// How many times Kav has come to the front: counted by MainActivity, so the hint below plays once per opening and not
// on every return to the Home tab.
internal object Openings {
    var count by mutableIntStateOf(0)
}

private object WhereToHint {
    var playedFor = -1
}

// "Where to?" types itself once each time Kav opens: it writes the words, erases them letter by letter, writes the
// two latest destinations the same way, then writes "Where to?" again and stays. Never while animations are off.
@Composable
private fun WhereTo(recent: List<String>, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val whereTo = T("Where to?", "לאן?")
    val opening = Openings.count
    var shown by remember { mutableStateOf(if (WhereToHint.playedFor == opening) whereTo else "") }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(opening) {
        if (WhereToHint.playedFor == opening) { shown = whereTo; return@LaunchedEffect }
        WhereToHint.playedFor = opening
        val still = android.provider.Settings.Global.getFloat(
            ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
        if (still) { shown = whereTo; return@LaunchedEffect }
        suspend fun write(words: String) {
            for (n in 1..words.length) { shown = words.take(n); kotlinx.coroutines.delay(70) }
        }
        suspend fun erase() {
            while (shown.isNotEmpty()) { shown = shown.dropLast(1); kotlinx.coroutines.delay(35) }
        }
        typing = true
        try {
            shown = ""
            kotlinx.coroutines.delay(300)
            write(whereTo)
            kotlinx.coroutines.delay(900)
            for (name in recent) {
                erase(); kotlinx.coroutines.delay(250)
                write(name); kotlinx.coroutines.delay(1100)
            }
            if (recent.isNotEmpty()) { erase(); kotlinx.coroutines.delay(250); write(whereTo) }
        } finally {
            shown = whereTo
            typing = false
        }
    }
    Row(modifier.clearAndSetSemantics { contentDescription = whereTo }, verticalAlignment = Alignment.CenterVertically) {
        // At rest it's the words in Kav's language now, which can change while Home stays up.
        Text(
            if (typing || shown.isEmpty()) shown else whereTo,
            style = K.type.body, color = K.textTertiary, maxLines = 1, overflow = TextOverflow.Clip,
        )
        if (typing) TypingCaret()
    }
}

// A thin blinking caret after the words while they type.
@Composable
private fun TypingCaret() {
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
    val on by blink.animateFloat(
        1f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(500, easing = { if (it < .5f) 0f else 1f }),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "caretBlink",
    )
    Box(Modifier.padding(start = 1.dp).size(1.5.dp, 20.dp).graphicsLayer { alpha = on }.background(K.accentInk))
}
