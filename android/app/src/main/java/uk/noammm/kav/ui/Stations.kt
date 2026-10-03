package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
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
    androidx.compose.animation.AnimatedContent(
        targetState = model.stationStop,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState >= 0) forward() else backward() },
        label = "station",
    ) { stop ->
        if (stop >= 0) DepartureBoard(model, net, stop) { model.stationStop = -1 }
        else StationList(model, net)
    }
}

@Composable
private fun StationList(model: KavModel, net: Net) {
    val ctx = LocalContext.current
    var q by model::stopQuery
    var locating by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { locating = true; requestLocationOnce(ctx) { model.here = it; locating = false } }
    }
    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, model.here,
            onPick = {}, onDismiss = { onMap = false },
            onLocate = { model.here = it },
            onStop = { s -> onMap = false; model.stationStop = s },
        )
        return
    }

    var hits by remember(net) { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(net, q) {
        hits = withContext(Dispatchers.Default) { net.searchStops(q).toList() }
        StopPhotos.prefetchNet(net, hits.take(40))
    }
    val here = model.here
    var near by remember(net) { mutableStateOf(emptyList<Pair<Int, Double>>()) }
    LaunchedEffect(net, here) {
        near = withContext(Dispatchers.Default) {
            if (here == null) emptyList() else net.nearestStops(here.first, here.second)
        }
        StopPhotos.prefetchNet(net, near.map { it.first })
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Find a", "מצאו"), T("stop", "תחנה"))
        val listState = rememberLazyListState()
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
                                requestLocationOnce(ctx) { model.here = it; locating = false }
                            } else {
                                ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        }
                        Spacer(Modifier.height(K.gap3))
                        Text(
                            T(
                                "Coarse location, read once, used only to sort this list. " +
                                    "It is never stored and never leaves the phone.",
                                "מיקום גס, שנקרא פעם אחת, משמש רק למיון הרשימה הזו. הוא לעולם לא נשמר ולא יוצא מהטלפון.",
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
    val t0 = remember(stop) { nowSec() }
    var moovitId by remember(stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    LaunchedEffect(stop) { if (moovitId <= 0) moovitId = StopPhotos.idOf(net, stop) ?: -1 }
    val rows = remember(stop, t0) {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        net.departuresAt(stop, t0, today)
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Next", "היציאות"), T("departures", "הקרובות"), back = onBack)
        Column(Modifier.padding(horizontal = K.gap4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (moovitId > 0) {
                    StopGlyphOrPhoto(moovitId, null, thumb = 56.dp)
                    Spacer(Modifier.width(K.gap3))
                }
                Column(Modifier.weight(1f)) {
                    Text(net.name[stop], fontSize = 14.sp, color = K.text)
                    val city = net.cityOf(stop)
                    val code = net.code.getOrElse(stop) { 0 }
                    Text(
                        listOf(
                            city.takeIf { it.isNotBlank() },
                            code.takeIf { it > 0 }?.let { T("stop $it", "תחנה $it") },
                            T("scheduled times, no live feed available", "לוחות זמנים מתוכננים, אין זמינות בזמן אמת"),
                        ).filterNotNull().joinToString(" · "),
                        fontSize = 11.sp, color = K.dim,
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
            Note(T("Nothing more today.", "אין עוד יציאות היום."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(rows) { (c, dep) ->
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
                        .padding(horizontal = K.gap3, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LineBadge(net, net.tripRoute[t])
                    Text(
                        net.name[last], fontSize = 13.sp, color = K.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(relative(dep, t0) ?: hhmm(dep), style = Mono, color = K.scheduled)
                }
            }
        }
    }
}

@Composable
private fun StationThumb(net: Net, stop: Int) {
    var id by remember(stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    LaunchedEffect(stop) { if (id <= 0) id = StopPhotos.idOf(net, stop) ?: -1 }
    Box(
        Modifier.size(40.dp).panel(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (id > 0) StopGlyphOrPhoto(id, null)
    }
}

internal fun placeOf(net: Net, stop: Int, meters: Int = -1) = uk.noammm.kav.data.Moovit.Place(
    name = net.name.getOrElse(stop) { T("Stop", "תחנה") },
    detail = net.cityOf(stop),
    lat = net.lat[stop].toDouble(),
    lon = net.lon[stop].toDouble(),
    type = 1,
    meters = meters,
)

