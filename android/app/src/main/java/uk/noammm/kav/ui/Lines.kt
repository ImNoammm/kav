package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.representativeTrip
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
    var endpoints by remember(net) { mutableStateOf(emptyMap<Int, Pair<Int, Int>>()) }
    LaunchedEffect(net) {
        endpoints = withContext(Dispatchers.Default) { lineEndpoints(net) }
    }
    LaunchedEffect(net, q, mode) {
        hits = withContext(Dispatchers.Default) { net.searchRoutes(q, mode).toList() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Browse the", "עיינו"), T("lines", "בקווים"), onSettings = { model.settingsOpen = true })
        KavField(q, { q = it }, T("line number or name…", "מספר או שם קו…"), Modifier.padding(horizontal = K.gap3).fillMaxWidth())
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
            T("${hits.size} routes · choose a line to see its stops", "${hits.size} קווים · בחרו קו כדי לראות את התחנות שלו"),
            fontSize = 12.sp, color = K.dim,
            modifier = Modifier.padding(horizontal = K.gap4),
        )
        Spacer(Modifier.height(K.gap1))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
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
                    LineDirection(net, endpoints[r], Modifier.weight(1f))
                    Text(T.onward, fontSize = 22.sp, color = K.dim)
                }
            }
        }
    }
}

/** One pass over trips, using the same longest-run policy as the stop list. */
private fun lineEndpoints(net: Net): Map<Int, Pair<Int, Int>> {
    val longest = IntArray(net.nRoutes) { -1 }
    for (t in net.tripRoute.indices) {
        val route = net.tripRoute[t]
        val previous = longest[route]
        if (previous < 0 || net.tripStart[t + 1] - net.tripStart[t] >
            net.tripStart[previous + 1] - net.tripStart[previous]) longest[route] = t
    }
    return buildMap {
        longest.forEachIndexed { route, t ->
            if (t >= 0 && net.tripStart[t + 1] > net.tripStart[t])
                put(route, net.stStop[net.tripStart[t]] to net.stStop[net.tripStart[t + 1] - 1])
        }
    }
}

@Composable
private fun LineIdentity(net: Net, route: Int) {
    Column(Modifier.widthIn(min = 54.dp, max = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(net.rShort[route].ifBlank { "—" }, fontSize = 23.sp, color = K.text,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(modeName(modeOf(net.rType[route])), fontSize = 11.sp, color = K.dim)
    }
}

@Composable
private fun LineDirection(net: Net, endpoints: Pair<Int, Int>?, modifier: Modifier = Modifier) {
    fun name(stop: Int): String = net.name[stop] + net.cityOf(stop).takeIf { it.isNotBlank() }
        ?.let { " · $it" }.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        if (endpoints == null) Text(T("Route stops", "\u05ea\u05d7\u05e0\u05d5\u05ea \u05d4\u05de\u05e1\u05dc\u05d5\u05dc"), fontSize = 15.sp, color = K.muted)
        else {
            Text(T("To \u2068${name(endpoints.second)}\u2069", "\u05d0\u05dc \u2068${name(endpoints.second)}\u2069"), fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(T("From \u2068${name(endpoints.first)}\u2069", "\u05de\u05be\u2068${name(endpoints.first)}\u2069"), fontSize = 12.sp, lineHeight = 17.sp,
                color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    // scanning 123k trips for the longest one is milliseconds, but not on the
    // frame that draws the screen
    var stops by remember(route) { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(route) {
        stops = withContext(Dispatchers.Default) {
            val t = net.representativeTrip(route)
            if (t < 0) emptyList()
            else (net.tripStart[t] until net.tripStart[t + 1]).map { net.stStop[it] }
        }
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line", "קו"), net.rShort[route], back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LineIdentity(net, route)
            LineDirection(net, stops?.takeIf { it.isNotEmpty() }?.let { it.first() to it.last() }, Modifier.weight(1f))
        }
        val list = stops
        if (list == null) LoadingBlock(T("Loading stops", "טוען תחנות…"))
        else Text(
            if (list.isEmpty()) T("no trips on this line in the loaded timetable", "אין נסיעות בקו הזה בלוח הזמנים הטעון")
            else T("${list.size} stops · full route", "${list.size} תחנות · המסלול המלא"),
            fontSize = 11.sp, color = K.dim,
            modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
        )
        if (list != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
            )) {
                items(list.size) { i ->
                    val s = list[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { model.stationStop = s; model.tab = uk.noammm.kav.Tab.Stations }
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
                                net.name[s], fontSize = 13.sp,
                                color = if (i == 0 || i == list.lastIndex) K.text else K.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val c = net.cityOf(s)
                            if (c.isNotBlank()) Text(c, fontSize = 11.sp, color = K.dim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
