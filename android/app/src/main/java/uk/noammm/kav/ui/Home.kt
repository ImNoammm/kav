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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeScreen(
    model: KavModel,
    recentTrips: List<RecentTrip>,
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
    var allTrips by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(allTrips) { allTrips = false }
    androidx.compose.animation.AnimatedContent(
        targetState = allTrips, modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState) forward() else backward() }, label = "recentTrips",
    ) { history ->
    if (history) {
        Column(Modifier.fillMaxSize().background(K.bg)) {
            ScreenHeader(T("Recent", "נסיעות"), T("trips", "אחרונות"), back = { allTrips = false })
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = K.gap4, end = K.gap4, bottom = K.gap6 + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                items(recentTrips.size) { index ->
                    Box(Modifier.fillMaxWidth().panel(K.rCard)) {
                        RecentTripRow(recentTrips[index]) { allTrips = false; onTrip(recentTrips[index]) }
                    }
                }
            }
        }
        return@AnimatedContent
    }
    fun save(list: List<Favourite>) = model.saveFavourites(ctx, list)
    // AlertRow only works under a LocalServiceAlertOpener, so this screen hosts the sheet itself.
    var alert by remember { mutableStateOf<Pair<Int, String>?>(null) }
    androidx.activity.compose.BackHandler(alert != null) { alert = null }
    CompositionLocalProvider(LocalServiceAlertOpener provides { group, label -> alert = group to label }) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(T("Home", "בית"), "", onSettings = { model.settingsOpen = true }, badge = model.update != null)
            FloatingTop(top = {
                Row(
                    Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap2).fillMaxWidth().heightIn(min = 64.dp)
                        .glassSurface(24.dp)
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
            }, estimate = K.gap2 + 64.dp, fade = 28.dp) { topSpace, backdrop ->
            LazyColumn(
                Modifier.fillMaxSize().then(backdrop),
                contentPadding = PaddingValues(start = K.gap4, end = K.gap4, top = topSpace + K.gap4,
                    bottom = K.gap6 + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(K.gap4),
            ) {
                item {
                    FavouriteStrip(
                        favourites,
                        onPick = { f -> f.place?.let { onFavourite(it) } ?: onSetFavourite(f) },
                        onAdd = { creating = true },
                        onEdit = { editing = it },
                        horizontalPadding = 0.dp,
                        onReorder = { save(it) },
                        onRemove = { f -> save(favourites.filter { it.id != f.id }) },
                    )
                }
                item {
                    val journey = model.activeJourney
                    if (journey != null) JourneyCard(model, journey, onResume)
                    else Column(
                        Modifier.fillMaxWidth().panel(K.rCard)
                            .padding(K.gap5),
                        verticalArrangement = Arrangement.spacedBy(K.gap2),
                    ) {
                        Text(T("Ready when you are", "מוכנים כשתרצו"), fontSize = 21.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                        Note(T("Choose a destination to see your route and what to do next.", "בחרו יעד כדי לראות את המסלול ואת הצעד הבא."))
                    }
                }
                // Paying, the tickets still running and the history, in one card.
                item {
                    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
                        HomeShortcut(T("Pay for a ride", "תשלום על נסיעה"), { drawQr() }, Modifier.fillMaxWidth(), framed = false) {
                            model.payOpen = true
                        }
                        if (Payer.signedIn) PayTicketsCard(model)
                    }
                }
                if (recentTrips.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                        Text(T("Recent trips", "נסיעות אחרונות"), fontSize = 15.sp, color = K.muted, fontWeight = FontWeight.Medium)
                        Column(Modifier.panel(K.rCard)) {
                            recentTrips.take(if (model.activeJourney != null) 2 else 3).forEachIndexed { index, trip ->
                                if (index > 0) Box(
                                    Modifier.padding(start = 52.dp, end = K.gap4).fillMaxWidth()
                                        .height(1.dp).background(K.border),
                                )
                                RecentTripRow(trip) { onTrip(trip) }
                            }
                            Text(T("More", "עוד"), fontSize = 15.sp, color = K.accent,
                                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { allTrips = true }
                                    .padding(horizontal = K.gap4, vertical = K.gap3))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(K.gap3),
                        ) {
                            HomeShortcut(T("Found a bug?", "מצאתם באג?"), { drawBug() }, Modifier.weight(1f).fillMaxHeight()) {
                                openLink(ctx, "$REPO_URL/issues/new?labels=bug&title=bug%3A%20")
                            }
                            HomeShortcut(T("Request a feature?", "רוצים פיצ'ר חדש?"), { drawBulb() }, Modifier.weight(1f).fillMaxHeight()) {
                                openLink(ctx, "$REPO_URL/issues/new?labels=enhancement&title=feature%3A%20")
                            }
                        }
                        HomeShortcut(T("Buy me a coffee", "קנו לי קפה"), { drawCoffee() }, Modifier.fillMaxWidth()) {
                            openLink(ctx, COFFEE_URL)
                        }
                    }
                }
            }
            }
        }
        alert?.let { (group, label) -> ServiceAlertSheet(group, label) { alert = null } }
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

@Composable
private fun RecentTripRow(trip: RecentTrip, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        ClockGlyph(K.dim, 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(trip.to.name, fontSize = 15.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(T("from ", "מ־") + (trip.from?.name ?: T("Current location", "המיקום הנוכחי")),
                fontSize = 13.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(tripWhen(trip.at), fontSize = 12.sp, color = K.dim)
    }
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
            Column(Modifier.fillMaxWidth().onSizeChanged { pageHeights[page] = it.height }.animateContentSize()) {
                CurrentStepButton(page, current) { scope.launch { pager.animateScrollToPage(current) } }
                StepCard(steps[page], journey.resolved, active = page == current, now = now, chosen = journey.chosen,
                    fix = model.fix,
                    onChoose = { leg, option ->
                        model.activeJourney?.takeIf { it.trip === journey.trip }
                            ?.let { model.activeJourney = it.copy(chosen = it.chosen + (leg to option)) }
                    },
                    pay = { leg, ride -> TripPay(model, leg, ride, journey.resolved) })
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

@Composable
private fun HomeShortcut(label: String, icon: DrawScope.() -> Unit, modifier: Modifier, framed: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).then(if (framed) Modifier.panel(K.rCard) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) { icon() }
        Text(label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
    }
}

private fun DrawScope.drawBug() {
    val w = size.width
    drawCircle(K.accent, w * .26f, Offset(w * .5f, w * .56f), style = Stroke(w * .08f))
    drawLine(K.accent, Offset(w * .5f, w * .30f), Offset(w * .5f, w * .82f), w * .08f, StrokeCap.Round)
    for (s in listOf(-1f, 1f)) {
        drawLine(K.accent, Offset(w * (.5f + s * .19f), w * .38f), Offset(w * (.5f + s * .40f), w * .28f), w * .08f, StrokeCap.Round)
        drawLine(K.accent, Offset(w * (.5f + s * .26f), w * .58f), Offset(w * (.5f + s * .45f), w * .58f), w * .08f, StrokeCap.Round)
        drawLine(K.accent, Offset(w * (.5f + s * .19f), w * .76f), Offset(w * (.5f + s * .40f), w * .86f), w * .08f, StrokeCap.Round)
    }
}

private fun DrawScope.drawBulb() {
    val w = size.width
    drawCircle(K.accent, w * .24f, Offset(w * .5f, w * .38f), style = Stroke(w * .08f))
    drawLine(K.accent, Offset(w * .38f, w * .72f), Offset(w * .62f, w * .72f), w * .08f, StrokeCap.Round)
    drawLine(K.accent, Offset(w * .42f, w * .86f), Offset(w * .58f, w * .86f), w * .08f, StrokeCap.Round)
}

internal fun DrawScope.drawQr() {
    val w = size.width
    for ((x, y) in listOf(.10f to .10f, .58f to .10f, .10f to .58f)) {
        drawRoundRect(K.accent, Offset(w * x, w * y), Size(w * .32f, w * .32f), CornerRadius(w * .06f), style = Stroke(w * .08f))
    }
    drawRect(K.accent, Offset(w * .62f, w * .62f), Size(w * .12f, w * .12f))
    drawRect(K.accent, Offset(w * .80f, w * .80f), Size(w * .10f, w * .10f))
}

private fun DrawScope.drawCoffee() {
    val w = size.width
    drawRoundRect(
        K.accent, topLeft = Offset(w * minOf(mirrorX(.18f), mirrorX(.62f)), w * .42f),
        size = Size(w * .44f, w * .40f), cornerRadius = CornerRadius(w * .10f), style = Stroke(w * .08f),
    )
    drawCircle(K.accent, w * .12f, Offset(w * mirrorX(.72f), w * .58f), style = Stroke(w * .08f))
    for (x in listOf(.30f, .50f)) {
        drawLine(K.accent, Offset(w * mirrorX(x), w * .14f), Offset(w * mirrorX(x), w * .30f), w * .08f, StrokeCap.Round)
    }
}
