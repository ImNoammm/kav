package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.KavModel
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.departuresAt
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.searchStops
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce

@Composable
fun StationsScreen(model: KavModel) {
    WithTimetable(model) { net -> StationsBody(model, net) }
}

@Composable
private fun StationsBody(model: KavModel, net: Net) {
    androidx.activity.compose.BackHandler(model.stationStop >= 0) { model.stationStop = -1 }
    val list = remember(net) { StationListState() }
    androidx.compose.animation.AnimatedContent(
        targetState = model.stationStop,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState >= 0) forward() else backward() },
        label = "station",
    ) { stop ->
        if (stop >= 0) DepartureBoard(model, net, stop) { model.stationStop = -1 }
        else StationList(model, net, list)
    }
}

// Kept while a stop's board is open, so coming back lands where the list was.
private class StationListState {
    val scroll = LazyListState()
    var hits by mutableStateOf(emptyList<Int>())
    var near by mutableStateOf(emptyList<Pair<Int, Double>>())
}

@Composable
private fun StationList(model: KavModel, net: Net, list: StationListState) {
    val listState = list.scroll
    val ctx = LocalContext.current
    var q by model::stopQuery
    var locating by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) { locating = true; requestLocationOnce(ctx, onFail = { locating = false }) { model.locate(it.first, it.second); locating = false } }
    }
    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, model.here,
            onPick = {}, onDismiss = { onMap = false },
            onLocate = { model.locate(it.first, it.second) },
            onStop = { s -> onMap = false; model.stationStop = s },
        )
        return
    }

    var hits by list::hits
    LaunchedEffect(net, q) {
        hits = withContext(Dispatchers.Default) { net.searchStops(q).toList() }
        delay(400)
        StopPhotos.prefetchNet(net, hits.take(40))
    }
    val here = model.here
    var near by list::near
    LaunchedEffect(net, here) {
        near = withContext(Dispatchers.Default) {
            if (here == null) emptyList() else net.nearestStops(here.first, here.second)
        }
        StopPhotos.prefetchNet(net, near.map { it.first })
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Find a", "מצאו"), T("stop", "תחנה"))
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            item(key = "search") {
                Column {
                    KavField(q, { q = it }, T("search stops…", "חיפוש תחנות…"), Modifier.padding(horizontal = K.gap1).fillMaxWidth())
                    Spacer(Modifier.height(K.gap2))
                    SelectOnMapRow(K.gap1) { onMap = true }
                    Spacer(Modifier.height(K.gap3))
                }
            }
            if (q.isNotBlank()) {
                if (hits.isEmpty()) item {
                    Note(T("Nothing matches that.", "שום דבר לא תואם."), Modifier.padding(horizontal = K.gap3, vertical = K.gap4))
                }
                items(hits, key = { it }) { s ->
                    StopRow(net, s, leading = { StationThumb(net, s) }) { model.stationStop = s }
                }
            } else {
                item {
                    Sig(T("Nearby", "תחנות"), T("stops", "בסביבה"), Modifier.padding(horizontal = K.gap3, vertical = K.gap1))
                }
                when {
                    here == null -> item {
                        Column(Modifier.padding(horizontal = K.gap4, vertical = K.gap3)) {
                        Note(if (locating) T("Waiting for a fix…", "ממתינים למיקום…") else T("Kav does not know where you are yet.", "Kav עדיין לא יודע איפה אתם."))
                        Spacer(Modifier.height(K.gap3))
                        Chip(if (locating) T("Locating…", "מאתרים מיקום…") else T("Use my location", "השתמשו במיקום שלי"), locating) {
                            if (hasLocationPermission(ctx)) {
                                locating = true
                                requestLocationOnce(ctx, onFail = { locating = false }) { model.locate(it.first, it.second); locating = false }
                            } else {
                                ask.launch(LOCATION_PERMISSIONS)
                            }
                        }
                        Spacer(Modifier.height(K.gap3))
                        Text(
                            T(
                                "Your location, read once, used only to sort this list. " +
                                    "It is never stored and never leaves the phone.",
                                "המיקום שלכם, שנקרא פעם אחת, משמש רק למיון הרשימה הזו. הוא לעולם לא נשמר ולא יוצא מהטלפון.",
                            ),
                            fontSize = 11.sp, color = K.dim, lineHeight = 16.sp,
                        )
                    }
                    }
                    near.isEmpty() -> item {
                        Note(
                            T("No stops within 2.5 km of you.", "אין תחנות במרחק של 2.5 ק״מ מכם."),
                            Modifier.padding(horizontal = K.gap3, vertical = K.gap4),
                        )
                    }
                    else -> items(near, key = { it.first }) { (s, d) ->
                        StopRow(net, s, distanceLabel(d), leading = { StationThumb(net, s) }) { model.stationStop = s }
                    }
                }
            }
        }
        ScrollEdge(listState.canScrollBackward)
        }
    }
}

@Composable
private fun DepartureBoard(model: KavModel, net: Net, stop: Int, onBack: () -> Unit) {
    var t0 by remember(stop) { mutableIntStateOf(nowSec()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stop, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                t0 = nowSec()
                delay(30_000)
            }
        }
    }
    var moovitId by remember(stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    var looked by remember(stop) { mutableStateOf(moovitId > 0) }
    LaunchedEffect(stop) {
        if (moovitId <= 0) moovitId = StopPhotos.idOf(net, stop) ?: -1
        looked = true
    }
    // Moovit's live arrivals at this stop: line number, scheduled and live seconds of the day, and the departure that
    // colours and marks it like the search results. Refreshed with t0.
    class LiveAt(val number: String, val static: Int, val rt: Int, val dep: Moovit.Departure)
    var live by remember(stop) { mutableStateOf<List<LiveAt>>(emptyList()) }
    LaunchedEffect(moovitId, t0) {
        if (moovitId <= 0) return@LaunchedEffect
        live = runCatching {
            withContext(Dispatchers.IO) {
                val s = Online.open(net.lat[stop] to net.lon[stop])
                val day = java.util.Calendar.getInstance(ISRAEL).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis / 1000
                Moovit.stopArrivals(s, listOf(moovitId)).first.values.filter { it.rtUtc > 0 }.mapNotNull { a ->
                    val number = Moovit.lineInfo(s, a.lineId)?.number ?: return@mapNotNull null
                    LiveAt(number, (a.staticUtc - day).toInt(), (a.rtUtc - day).toInt(), a.departure())
                }
            }
        }.getOrDefault(live)
    }
    fun liveAt(route: Int, dep: Int): LiveAt? = live.firstOrNull {
        it.number == net.rShort[route] && kotlin.math.abs(it.static - dep) <= 120
    }
    fun liveFor(route: Int, dep: Int): Int? = liveAt(route, dep)?.rt
    val rows = remember(stop, t0) {
        val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
        net.departuresAt(stop, t0, today)
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Next", "היציאות"), T("departures", "הקרובות"), back = onBack)
        Column(Modifier.padding(horizontal = K.gap4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StopGlyphOrPhoto(moovitId, null, thumb = 56.dp, resolving = !looked)
                Spacer(Modifier.width(K.gap3))
                Column(Modifier.weight(1f)) {
                    Text(net.name[stop], fontSize = 18.sp, lineHeight = 24.sp, color = K.text)
                    val city = net.cityOf(stop)
                    val code = net.code.getOrElse(stop) { 0 }
                    Text(
                        listOf(
                            city.takeIf { it.isNotBlank() },
                            code.takeIf { it > 0 }?.let { T("stop $it", "תחנה $it") },
                            if (live.isEmpty()) T("scheduled times, no live feed available", "לוחות זמנים מתוכננים, אין זמינות בזמן אמת")
                            else T("coloured times are live", "זמנים צבועים הם בזמן אמת"),
                        ).filterNotNull().joinToString(" · "),
                        fontSize = 13.sp, lineHeight = 18.sp, color = K.dim,
                    )
                }
            }
            Spacer(Modifier.height(K.gap3))
            Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Chip(T("Start here", "התחלה כאן"), false) {
                    model.pendingFrom = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }
                Chip(T("End here", "סיום כאן"), false) {
                    model.pendingTo = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }
            }
        }
        Spacer(Modifier.height(K.gap3))
        if (rows.isEmpty()) {
            Note(T("Nothing more today or tomorrow.", "אין עוד יציאות היום או מחר."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(rows.sortedBy { (c, dep) -> liveFor(net.tripRoute[net.tripOf(net.cST[c])], dep) ?: dep }) { (c, dep) ->
                val t = net.tripOf(net.cST[c])
                val last = net.tripLast(t)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            model.pendingFrom = placeOf(net, stop)
                            model.pendingTo = placeOf(net, last)
                            model.tab = uk.noammm.kav.Tab.Directions
                        }
                        .padding(horizontal = K.gap3, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LineBadge(net, net.tripRoute[t])
                    Text(
                        net.name[last], fontSize = 16.sp, color = K.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val at = liveAt(net.tripRoute[t], dep)
                    val shown = at?.rt ?: dep
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (at != null) { DepMarkGlyph(at.dep, 12.dp); Spacer(Modifier.width(4.dp)) }
                    Text(
                        relative(shown, t0)
                            ?: if (shown >= 86_400 && shown - 86_400 >= t0) T("tomorrow ${hhmm(shown)}", "מחר ${hhmm(shown)}") else hhmm(shown),
                        style = Mono, fontSize = 15.sp, color = if (at != null) depColour(at.dep) else K.scheduled,
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationThumb(net: Net, stop: Int) {
    var id by remember(stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    var looked by remember(stop) { mutableStateOf(id > 0) }
    LaunchedEffect(stop) {
        if (id <= 0) id = StopPhotos.idOf(net, stop) ?: -1
        looked = true
    }
    StopGlyphOrPhoto(id, null, resolving = !looked)
}

internal fun placeOf(net: Net, stop: Int) = uk.noammm.kav.data.Moovit.Place(
    name = net.name.getOrElse(stop) { T("Stop", "תחנה") },
    detail = net.cityOf(stop),
    lat = net.lat[stop].toDouble(),
    lon = net.lon[stop].toDouble(),
    type = 1,
)

