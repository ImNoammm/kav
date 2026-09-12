package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.searchRoutes

/** GTFS route_type values as the Israeli MOT feed uses them. */
private val FILTERS = listOf(
    "All" to -1, "Bus" to 3, "Train" to 2, "Light rail" to 0, "Share taxi" to 715,
)

/** FILTERS' English labels are used only as lookup ids for [mode]; translate at render time
 *  instead of inside the top-level list, so switching [T.lang] actually recomposes the chips. */
private fun filterLabel(label: String): String = when (label) {
    "Bus" -> T("Bus", "אוטובוס")
    "Train" -> T("Train", "רכבת")
    "Light rail" -> T("Light rail", "רכבת קלה")
    "Share taxi" -> T("Share taxi", "מונית שירות")
    else -> T("All", "הכול")
}

@Composable
fun LinesScreen(model: KavModel) {
    WithTimetable(model) { net -> LinesBody(model, net) }
}

@Composable
private fun LinesBody(model: KavModel, net: Net) {
    androidx.activity.compose.BackHandler(model.lineRoute >= 0) { model.lineRoute = -1 }
    androidx.compose.animation.AnimatedContent(
        targetState = model.lineRoute,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState >= 0) forward() else backward() },
        label = "line",
    ) { route ->
        if (route >= 0) LineDetail(model, net, route) { model.lineRoute = -1 }
        else LineList(model, net)
    }
}

@Composable
private fun LineList(model: KavModel, net: Net) {
    // held by the model: opening a line disposes this list (see KavModel.lineQuery)
    var q by model::lineQuery
    var mode by remember { mutableIntStateOf(-1) }
    var hits by remember(net) { mutableStateOf(emptyList<Int>()) }
    val here = model.here
    LaunchedEffect(net, q, mode, here) {
        hits = withContext(Dispatchers.Default) { net.searchRoutes(q, mode, here).toList() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Browse the", "עיינו"), T("lines", "בקווים"), onSettings = { model.settingsOpen = true })
        KavField(
            q,
            { q = it },
            T("line number or name…", "מספר או שם קו…"),
            Modifier.padding(horizontal = K.gap3).fillMaxWidth()
        )
        Spacer(Modifier.height(K.gap3))
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            FILTERS.forEach { (label, t) -> Chip(filterLabel(label), mode == t) { mode = t } }
        }
        Spacer(Modifier.height(K.gap3))
        Text(
            T(
                "${hits.size} routes · choose a line to see its stops",
                "${hits.size} קווים · בחרו קו כדי לראות את התחנות שלו"
            ),
            fontSize = 12.sp, color = K.dim,
            modifier = Modifier.padding(horizontal = K.gap4),
        )
        Spacer(Modifier.height(K.gap1))
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
            )
        ) {
            items(hits, key = { it }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = K.gap1)
                        .clip(RoundedCornerShape(K.rControl)).background(K.surface1)
                        .clickable { model.lineRoute = r }
                        .padding(K.gap3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LineIdentity(net, r)
                    LineDirection(net, net.routes[r].stops[0], Modifier.weight(1f))
                    Text(T.onward, fontSize = 22.sp, color = K.dim)
                }
            }
        }
    }
}

/** Popup for choosing which direction of a line to view. Reports the picked
 *  direction's index into Route.stops; callers decide what that means. */
@Composable
private fun DirectionPicker(net: Net, route: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val stopsByDirection = net.routes[route].stops
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text("Choose a direction", fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold)
            stopsByDirection.forEachIndexed { i, stops ->
                if (stops.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(K.rControl)).background(K.surface2)
                            .clickable(role = Role.Button) { onPick(i) }
                            .padding(K.gap3),
                    ) {
                        val last = stops.last()
                        val place = net.stops[last].name + net.cityOf(last).takeIf { it.isNotBlank() }?.let { " · $it" }
                            .orEmpty()
                        Text("To $place", fontSize = 15.sp, color = K.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineIdentity(net: Net, route: Int) {
    Column(Modifier.widthIn(min = 54.dp, max = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            net.routes[route].short.ifBlank { "—" }, fontSize = 23.sp, color = K.text,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(modeName(modeOf(net.routes[route].type)), fontSize = 11.sp, color = K.dim)
    }
}

private fun stopLabel(net: Net, stop: Int): String {
    val detail = listOfNotNull(net.cityOf(stop).takeIf { it.isNotBlank() }, net.stopCode(stop)).joinToString(" · ")
    return net.stops[stop].name + detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

/** From/to summary for a stop sequence, "From" on top since that's the order it's travelled. */
@Composable
private fun LineDirection(net: Net, stops: IntArray, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        if (stops.isEmpty()) Text("Route stops", fontSize = 15.sp, color = K.muted)
        else {
            Text(
                "From \u2068${stopLabel(net, stops.first())}\u2069", fontSize = 12.sp, lineHeight = 17.sp,
                color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                "To \u2068${stopLabel(net, stops.last())}\u2069", fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    val perDirectionStops = net.routes[route].stops
    var direction by remember(route) { mutableIntStateOf(0) }
    var picking by remember(route) { mutableStateOf(false) }
    val list = perDirectionStops[direction]
    // The trip whose own stop_times produced `list`, so each row can show when
    // this specific line reaches that stop instead of sending you to Stations.
    val underlyingRoute = net.routes[route].directions.getOrNull(direction) ?: route
    val tripIdx = net.routes[underlyingRoute].bestTrip

    val here = model.here
    var nearest by remember(route, direction) { mutableStateOf<Int?>(null) }
    var nearestDeps by remember(route, direction) { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(route, direction, here) {
        if (here == null || list.isEmpty()) {
            nearest = null; nearestDeps = emptyList(); return@LaunchedEffect
        }
        val t0 = nowSec()
        val (s, deps) = withContext(Dispatchers.Default) {
            var best = -1;
            var bestD = Double.MAX_VALUE
            for (candidate in list) {
                val st = net.stops[candidate]
                val d = metres(here.first, here.second, st.lat, st.lon)
                if (d < bestD) {
                    bestD = d; best = candidate
                }
            }
            val out = ArrayList<Int>(5)
            var i = net.dStart[best]
            while (i < net.dStart[best + 1] && out.size < 5) {
                val c = net.dConn[i]
                if (net.tripRoute[net.cTrip[c]] == underlyingRoute && net.stDep[net.cST[c]] >= t0) out.add(c)
                i++
            }
            best to out
        }
        nearest = s; nearestDeps = deps
    }

    // The selected stop is the one showing its time on the row; it starts out as
    // the nearest one once located, but a tap can move it to any other stop.
    var selectedStop by remember(route, direction) { mutableStateOf<Int?>(null) }
    LaunchedEffect(nearest) { if (selectedStop == null) selectedStop = nearest }

    val listState = rememberLazyListState()
    LaunchedEffect(nearest, list) {
        val idx = nearest?.let { list.indexOf(it) } ?: return@LaunchedEffect
        if (idx < 0) return@LaunchedEffect
        listState.scrollToItem(idx)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.find { it.index == idx } ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        val itemCenter = item.offset + item.size / 2
        listState.animateScrollBy((itemCenter - viewportCenter).toFloat())
    }

    val sheetState =
        rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 96.dp,
        containerColor = K.bg,
        sheetContainerColor = K.surface1,
        sheetContentColor = K.text,
        sheetDragHandle = {
            Box(
                Modifier.padding(vertical = K.gap2).size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp)).background(K.surface4)
            )
        },
        sheetContent = {
            Column(Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap5)) {
                when {
                    here == null -> Text("Kav does not know where you are yet.", fontSize = 13.sp, color = K.dim)
                    nearest == null -> Text(
                        "This line has no stops in the loaded timetable.",
                        fontSize = 13.sp,
                        color = K.dim
                    )

                    else -> {
                        Text("Nearest stop on this line", fontSize = 11.sp, color = K.dim)
                        Text(
                            stopLabel(net, nearest!!),
                            fontSize = 16.sp,
                            color = K.text,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(K.gap3))
                        if (nearestDeps.isEmpty()) {
                            Text("No more departures today.", fontSize = 13.sp, color = K.muted)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                                nearestDeps.forEach { c ->
                                    Text(
                                        hhmm(net.stDep[net.cST[c]]),
                                        style = Mono,
                                        color = K.scheduled
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(K.bg)) {
            ScreenHeader(T("Line", "קו"), net.routes[route].short, back = onBack)
            Row(
                Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LineIdentity(net, route)
                LineDirection(net, list, Modifier.weight(1f))
                if (perDirectionStops.size > 1) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(K.rPill)).background(K.plate)
                            .clickable(role = Role.Button) { picking = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("⇄", fontSize = 16.sp, color = K.text) }
                }
            }
            if (picking) {
                DirectionPicker(net, route, onPick = { direction = it; picking = false }) { picking = false }
            }
            if (list.isEmpty()) LoadingBlock(T("Loading stops", "טוען תחנות…"))
            else {
                val points = remember(route, direction) { list.map { net.stops[it].lat to net.stops[it].lon } }
                val geometry = remember(route, direction, nearest) {
                    MapGeometry(
                        lines = listOf(MapLine(points, K.route, 4f, casing = 7f)),
                        dots = list.map { s ->
                            val st = net.stops[s]
                            if (s == nearest) MapDot(st.lat, st.lon, K.accent, 7f, K.bg, 2f)
                            else MapDot(st.lat, st.lon, K.bg, 4f, K.text, 1.5f)
                        },
                    )
                }
                TileMap(
                    points,
                    Modifier.fillMaxWidth().padding(horizontal = K.gap4).height(160.dp)
                        .clip(RoundedCornerShape(K.rControl)).background(K.surface1),
                    geometry = geometry,
                )
                Spacer(Modifier.height(K.gap2))

                Text(
                    if (list.isEmpty()) "no trips on this line in the loaded timetable"
                    else "${list.size} stops · full route",
                    fontSize = 11.sp, color = K.dim,
                    modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                )
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
                        start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
                    )
                ) {
                    items(list.size) { i ->
                        val s = list[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedStop = s }
                                .padding(horizontal = K.gap3, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // the rail: a continuous line with a node at every stop
                            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(7.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (i == 0 || i == list.lastIndex) K.text else K.surface4),
                                )
                            }
                            Spacer(Modifier.width(K.gap3))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    net.stops[s].name, fontSize = 13.sp,
                                    color = if (i == 0 || i == list.lastIndex) K.text else K.muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val detail = listOfNotNull(
                                    net.cityOf(s).takeIf { it.isNotBlank() },
                                    net.stopCode(s)
                                ).joinToString(" · ")
                                if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, color = K.dim, maxLines = 1)
                            }
                            if (tripIdx >= 0 && s == selectedStop) {
                                Text(hhmm(net.stDep[net.tripStart[tripIdx] + i]), style = Mono, color = K.scheduled)
                            }
                        }
                    }
                }
            }
        }
    }
}
