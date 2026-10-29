package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.representativeTrip
import uk.noammm.kav.data.lineTimesAt
import uk.noammm.kav.data.moovitLineEndsAt
import uk.noammm.kav.data.moovitLineStartsAt
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.shownName
import uk.noammm.kav.data.NAIM_NAME
import uk.noammm.kav.data.NAIM_WORDS
import uk.noammm.kav.data.searchRoutes
import uk.noammm.kav.data.trainLine
import uk.noammm.kav.data.trainLeaving
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState

private val FILTERS = listOf(
    "All" to intArrayOf(),
    "Bus" to intArrayOf(3, 11, 715, 8),
    "Light rail" to intArrayOf(0, 1, 12),
    "Israel Railways" to intArrayOf(2),
    "Carmelit / Rakavlit" to intArrayOf(7, 5, 6),
    "Shuttle" to intArrayOf(711),
)

private fun filterLabel(label: String): String = when (label) {
    "Bus" -> T("Bus", "אוטובוס")
    "Light rail" -> T("Light rail", "רכבת קלה")
    "Israel Railways" -> T("Israel Railways", "רכבת ישראל")
    "Carmelit / Rakavlit" -> T("Carmelit / Rakavlit", "כרמלית / רכבלית")
    "Shuttle" -> T("Shuttle", "שאטל")
    else -> T("All", "הכול")
}

@Composable
fun LinesScreen(model: KavModel) {
    WithTimetable(model) { net -> LinesBody(model, net) }
}

@Composable
private fun LinesBody(model: KavModel, net: Net) {
    androidx.activity.compose.BackHandler(model.lineRoute >= 0 || model.moovitLine != null) {
        model.lineRoute = -1; model.moovitLine = null
    }
    val list = remember(net) { lineListFor(net) }
    androidx.compose.animation.AnimatedContent(
        targetState = model.lineRoute to model.moovitLine,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState.first >= 0 || targetState.second != null) forward() else backward() },
        label = "line",
    ) { (route, online) ->
        when {
            route >= 0 -> LineDetail(model, net, route) { model.lineRoute = -1 }
            online != null -> OnlineLineDetail(online) { model.moovitLine = null }
            else -> LineList(model, net, list)
        }
    }
}

// Kept across tab switches, and filled as soon as the timetable loads, so Recent is there the moment Lines opens.
private var kept: Pair<Net, LineListState>? = null

private fun lineListFor(net: Net): LineListState =
    kept?.takeIf { it.first === net }?.second ?: LineListState().also { kept = net to it }

suspend fun warmLines(ctx: android.content.Context, net: Net, here: Pair<Double, Double>?) {
    val list = lineListFor(net)
    if (list.leadReady) return
    withContext(Dispatchers.Default) {
        val ends = lineEndpoints(net)
        val sections = foldLines(net, "", FILTERS[0].second, ends)
        val lead = leadSections(ctx, net, here, ends)
        withContext(Dispatchers.Main) {
            list.endpoints = ends; list.sections = sections; list.lead = lead; list.leadReady = true
        }
    }
}

// Kept while a line is open, so coming back lands where the list was.
private class LineListState {
    var types by mutableStateOf(FILTERS[0].second)
    val scroll = LazyListState()
    var scrolled by mutableStateOf(false)
    var sections by mutableStateOf(emptyList<Pair<String, List<LineRow>>>())
    var endpoints by mutableStateOf(emptyMap<Int, Pair<Int, Int>>())
    var lead by mutableStateOf(emptyList<Pair<String, List<LineRow>>>())
    var leadReady by mutableStateOf(false)
}

@Composable
private fun LineList(model: KavModel, net: Net, list: LineListState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by model::lineQuery
    var types by list::types
    val listState = list.scroll
    var sections by list::sections
    var endpoints by list::endpoints
    LaunchedEffect(net) {
        if (endpoints.isEmpty()) endpoints = withContext(Dispatchers.Default) { lineEndpoints(net) }
    }
    LaunchedEffect(net, q, types, endpoints) {
        sections = withContext(Dispatchers.Default) { foldLines(net, q, types, endpoints) }
    }
    var online by remember { mutableStateOf(onlineLines) }
    LaunchedEffect(net) {
        if (online == null) online = runCatching { missingLines(net) }.getOrNull()
            ?.also { if (it.isNotEmpty()) onlineLines = it }
    }
    val extra = remember(online, q, types) { shownOnline(online.orEmpty(), q, types) }
    var lead by list::lead
    LaunchedEffect(Unit) {
        if (model.here == null && uk.noammm.kav.hasLocationPermission(ctx)) {
            uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    LaunchedEffect(net, endpoints, model.here, q, types, model.savedLines) {
        lead = if (q.isNotBlank() || types.isNotEmpty()) emptyList()
        else withContext(Dispatchers.Default) { leadSections(ctx, net, model.here, endpoints) }
        list.leadReady = true
    }
    // The full list waits for Recent and Nearby, so it doesn't flash up first and then get pushed down.
    val listReady = list.leadReady || q.isNotBlank() || types.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Lines", "קווים"))
        var scrolled by list::scrolled
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) scrolled = true }
        }
        LaunchedEffect(lead) { if (lead.isNotEmpty() && !scrolled) listState.scrollToItem(0) }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = K.gap4, end = K.gap4, bottom = LocalBottomBarInset.current + K.gap4,
        )) {
            item(key = "search") {
                Column {
                    // The same card as the Stops tab's search.
                    Box(Modifier.fillMaxWidth().panel(K.rCard)) {
                        KavField(q, { q = it }, T("Line number or name", "מספר או שם קו"), Modifier.fillMaxWidth(), search = true, flat = true)
                    }
                    Spacer(Modifier.height(K.gap2))
                    Row(
                        Modifier
                            .bleed(K.gap4)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = K.gap4),
                        horizontalArrangement = Arrangement.spacedBy(K.gap2),
                    ) {
                        FILTERS.forEach { (label, t) -> Chip(filterLabel(label), types === t) { types = t } }
                    }
                    Spacer(Modifier.height(K.gap3))
                }
            }
            item(key = "count") {
                val total = sections.sumOf { it.second.size } + extra.sumOf { it.second.size }
                Text(
                    T.count(total, "1 line", "קו אחד", "lines", "קווים"), style = K.type.caption.tabular,
                    modifier = Modifier.padding(horizontal = K.gap4),
                )
            }
            lead.forEach { (label, rows) ->
                item(key = "lead-$label") { SectionLabel(label) }
                itemsIndexed(rows, key = { _, it -> "lead-$label-${it.route}" }) { i, row -> Box(Modifier.animateItem()) { LineRowCard(model, net, row, i == 0, i == rows.lastIndex) } }
            }
            if (listReady) sections.forEach { (operator, rows) ->
                if (operator.isNotBlank()) item(key = "op-$operator") {
                    SectionLabel(operatorLabel(operator))
                }
                itemsIndexed(rows, key = { _, it -> it.route }) { i, row -> Box(Modifier.animateItem()) { LineRowCard(model, net, row, i == 0, i == rows.lastIndex) } }
            }
            if (listReady) extra.forEach { (operator, lines) ->
                item(key = "online-op-$operator") { SectionLabel(operator) }
                itemsIndexed(lines, key = { _, it -> "online-${it.id}" }) { i, g -> Box(Modifier.animateItem()) { OnlineLineCard(g, i == 0, i == lines.lastIndex) { model.moovitLine = g } } }
            }
        }
        ScrollEdge(listState.canScrollBackward)
        }
    }
}

private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val side = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
}

@Composable
private fun SectionLabel(label: String) = SectionHeader(label)

@Composable
private fun LineRowCard(model: KavModel, net: Net, row: LineRow, first: Boolean, last: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last)
            .clickable(role = Role.Button) { model.lineRoute = row.route }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(net.rType[row.route], net.rShort[row.route], busOperator(net.rType[row.route], -1) { Operators.of(net, row.route) })
        LineSpan(net, row, Modifier.weight(1f))
        RowChevron()
    }
}

// The chevron at the end of a row that opens something.
@Composable
private fun RowChevron() = Icon(
    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp),
)

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

private data class LineRow(val route: Int, val a: Int, val b: Int, val both: Boolean)

private fun terminusName(net: Net, stop: Int): String = net.name[stop].substringBefore('/').trim()

internal fun lineKey(net: Net, r: Int) =
    "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|${net.rLong[r]}"

private fun namesakeEnds(net: Net, ends: Map<Int, Pair<Int, Int>>): Map<String, Pair<Int, Int>> {
    val byName = HashMap<String, Pair<Int, Int>>(ends.size * 2)
    for ((r, e) in ends) byName.putIfAbsent(lineKey(net, r), e)
    return byName
}

private fun foldRoutes(
    net: Net, routes: Iterable<Int>, ends: Map<Int, Pair<Int, Int>>,
    byName: Map<String, Pair<Int, Int>>,
): Collection<LineRow> {
    val rows = LinkedHashMap<String, LineRow>()
    for (r in routes) {
        val e = ends[r] ?: byName[lineKey(net, r)]
        if (e == null && ends.isNotEmpty()) continue
        val key = if (e == null) "lone-$r" else {
            val a = terminusName(net, e.first); val b = terminusName(net, e.second)
            val (lo, hi) = if (a <= b) a to b else b to a
            "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|$lo $hi"
        }
        val prev = rows[key]
        if (prev == null) rows[key] = LineRow(r, e?.first ?: -1, e?.second ?: -1, false)
        else if (!prev.both) rows[key] = prev.copy(both = true)
    }
    return rows.values
}

private fun foldLines(
    net: Net, q: String, types: IntArray, ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    val rows = foldRoutes(net, net.searchRoutes(q, types).asIterable(), ends, namesakeEnds(net, ends))
    return rows.groupBy { net.agencyOf(it.route) }.mapValues { (_, rs) ->
        rs.sortedWith(compareBy(
            { net.rShort[it.route].toIntOrNull() ?: Int.MAX_VALUE },
            { net.rShort[it.route] },
            { if (it.a >= 0) terminusName(net, it.a) else "" },
        ))
    }.toList()
}

private fun leadSections(
    ctx: android.content.Context, net: Net, here: Pair<Double, Double>?,
    ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    if (ends.isEmpty()) return emptyList()
    val byName = namesakeEnds(net, ends)
    val out = ArrayList<Pair<String, List<LineRow>>>()

    // Lines by their keys, in the keys' order.
    fun keyed(keys: List<String>): List<LineRow> {
        val slot = HashMap<String, Int>()
        keys.forEachIndexed { i, k -> slot.putIfAbsent(k, i) }
        val routes = arrayOfNulls<Int>(keys.size)
        for (r in 0 until net.nRoutes) {
            val i = slot[lineKey(net, r)] ?: continue
            if (routes[i] == null) routes[i] = r
        }
        return foldRoutes(net, routes.filterNotNull(), ends, byName).toList()
    }
    // Saved from a line's page.
    val saved = keyed(uk.noammm.kav.Prefs.savedLines(ctx))
    if (saved.isNotEmpty()) out.add(T("Saved", "שמורים") to saved)
    val recent = keyed(uk.noammm.kav.Prefs.recentLines(ctx))
    if (recent.isNotEmpty()) out.add(T("Recent", "אחרונים") to recent)

    if (here != null) {
        val near = net.nearestStops(here.first, here.second, k = 20, radius = 900.0)
        if (near.isNotEmpty()) {
            val rank = HashMap<Int, Int>(near.size * 2)
            near.forEachIndexed { i, (s, _) -> rank[s] = i }
            val best = HashMap<Int, Int>()
            for (t in net.tripRoute.indices) {
                val route = net.tripRoute[t]
                for (i in net.tripStart[t] until net.tripStart[t + 1]) {
                    val o = rank[net.stStop[i]] ?: continue
                    val held = best[route]
                    if (held == null || o < held) best[route] = o
                }
            }
            val routes = best.entries.sortedBy { it.value }.map { it.key }
            val rows = foldRoutes(net, routes, ends, byName).take(10)
            if (rows.isNotEmpty()) out.add(T("Nearby", "בקרבת מקום") to rows)
        }
    }
    return out
}

private fun operatorLabel(name: String): String = when (name) {
    "רכבת ישראל" -> T("Israel Railways", "רכבת ישראל")
    "כרמלית" -> T("Carmelit", "כרמלית")
    "כבל אקספרס" -> T("Rakavlit (Cable Express)", "רכבלית (כבל אקספרס)")
    "דן נתיבים בעמ" -> T("Dan Nativim", "דן נתיבים")
    else -> name
}

@Composable
private fun LineSpan(net: Net, row: LineRow, modifier: Modifier = Modifier) {
    if (row.a < 0) {
        Text(T("Route stops", "תחנות המסלול"), style = K.type.body, color = K.muted, modifier = modifier)
        return
    }
    val a = net.shown(terminusName(net, row.a)); val b = net.shown(terminusName(net, row.b))
    val text = when {
        a == b -> "⁨$a⁩"
        row.both -> "⁨$a⁩ ↔ ⁨$b⁩"
        else -> T("⁨$a⁩ → ⁨$b⁩", "⁨$a⁩ ← ⁨$b⁩")
    }
    Text(
        text, style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

@Composable
// operator: a bus line's operator colour, as a short stroke under its number.
private fun LineIdentity(rt: Int, number: String, operator: Color? = null) {
    val marked = rt == 711 || isRail(rt, -1) || modeOf(rt) == Mode.FUNICULAR ||
        modeOf(rt) == Mode.CABLE || modeOf(rt) == Mode.GONDOLA
    val logoOnly = modeOf(rt) == Mode.FUNICULAR || modeOf(rt) == Mode.CABLE ||
        modeOf(rt) == Mode.GONDOLA
    Column(Modifier.widthIn(min = 54.dp, max = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (marked) {
            AgencyMark(rt, -1, K.muted, if (number.isBlank() || logoOnly) 26.dp else 17.dp)
            Spacer(Modifier.height(2.dp))
        }
        if ((number.isNotBlank() && !logoOnly) || !marked) {
            val plate = if (marked) null else operatorPlate(operator)
            Text(number.ifBlank { "-" }, fontSize = 23.sp, color = plate?.ink ?: K.text,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (plate == null) Modifier
                    else Modifier.padding(bottom = 2.dp).clip(RoundedCornerShape(K.rBadge)).background(plate.fill).padding(horizontal = 7.dp))
        }
        Text(typeName(rt), style = K.type.caption)
    }
}

@Composable
private fun LineDirection(net: Net, endpoints: Pair<Int, Int>?, modifier: Modifier = Modifier) {
    fun name(stop: Int): String = net.stopName(stop) + net.cityName(stop).takeIf { it.isNotBlank() }
        ?.let { " · $it" }.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        if (endpoints == null) Text(T("Route stops", "\u05ea\u05d7\u05e0\u05d5\u05ea \u05d4\u05de\u05e1\u05dc\u05d5\u05dc"), style = K.type.body, color = K.muted)
        else {
            Text(T("To \u2068${name(endpoints.second)}\u2069", "\u05d0\u05dc \u2068${name(endpoints.second)}\u2069"), style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(T("From \u2068${name(endpoints.first)}\u2069", "\u05de\u05be\u2068${name(endpoints.first)}\u2069"), fontSize = 12.sp, lineHeight = 17.sp,
                color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(route) { uk.noammm.kav.Prefs.rememberLine(ctx, lineKey(net, route)) }
    var stops by remember(route) { mutableStateOf<List<Int>?>(null) }
    // A train's line is every train between its two stations the same way, each of which the timetable keeps as a
    // route of its own.
    val routes = remember(route) { if (net.rType[route] == 2 && net.rShort[route].isBlank()) net.trainLine(route) else setOf(route) }
    LaunchedEffect(route) {
        stops = withContext(Dispatchers.Default) {
            val t = if (routes.size == 1) net.representativeTrip(route)
            else net.tripRoute.indices.filter { net.tripRoute[it] in routes }.maxByOrNull { net.tripStart[it + 1] - net.tripStart[it] } ?: -1
            if (t < 0) emptyList()
            else (net.tripStart[t] until net.tripStart[t + 1]).map { net.stStop[it] }
        }
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line ", "קו ") + net.rShort[route], back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LineIdentity(net.rType[route], net.rShort[route], busOperator(net.rType[route], -1) { Operators.of(net, route) })
            LineDirection(net, stops?.takeIf { it.isNotEmpty() }?.let { it.first() to it.last() }, Modifier.weight(1f))
            // Kept at the top of the Lines tab.
            val key = lineKey(net, route)
            val saved = key in model.savedLines
            KavIconButton(
                if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (saved) T("Saved, tap to remove", "שמור, הקישו להסרה") else T("Save this line", "שמירת הקו"),
                { model.saveLines(ctx, if (saved) model.savedLines - key else listOf(key) + model.savedLines) },
                floating = true, tint = if (saved) K.accentInk else K.textSecondary,
            )
        }
        val list = stops
        // The stop tapped on this line opens under it with the line's next buses there, like Moovit's line view, and
        // stands out on the map; a stop tapped on the map is opened in the list.
        var open by remember(route) { mutableIntStateOf(-1) }
        val stopsState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = rememberCoroutineScope()
        if (!list.isNullOrEmpty()) {
            val live = rememberLineLive(net, route, list)
            val next = remember(route) { nextStart(net, routes) }?.let { (t, at) ->
                val from = net.stopName(net.stStop[net.tripStart[t]])
                T("Next departure from $from: ${startLabel(at)}", "היציאה הבאה מ-$from: ${startLabel(at)}")
            }
            // Whether the line is live leads, under its name, before the map it explains.
            LineLiveNote(live, next)
            LineMap(
                net, route, list, live, open,
                onSelect = { i -> open = i; scope.launch { stopsState.animateScrollToItem(i) } },
                modifier = Modifier.padding(horizontal = K.gap4).padding(top = K.gap3).fillMaxWidth().height(210.dp).panel(K.rCard),
            )
        }
        if (list == null) LoadingBlock(T("Loading stops", "טוען תחנות…"))
        else SectionHeader(
            if (list.isEmpty()) T("No trips on this line in the loaded timetable", "אין נסיעות בקו הזה בלוח הזמנים הטעון")
            else T.count(list.size, "1 stop", "תחנה אחת", "stops", "תחנות") + T(" · full route", " · המסלול המלא"),
            Modifier.padding(horizontal = K.gap4),
        )
        LaunchedEffect(list) {
            val focus = model.lineFocusStop
            val at = list?.indexOf(focus) ?: -1
            if (focus >= 0 && list != null) model.lineFocusStop = -1
            if (at >= 0) { open = at; stopsState.scrollToItem(at) }
        }
        if (list != null) {
            LazyColumn(Modifier.fillMaxSize(), state = stopsState, contentPadding = PaddingValues(
                start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
            )) {
                items(list.size) { i ->
                    val s = list[i]
                    val chosen = open == i
                    val shade by androidx.compose.animation.animateColorAsState(
                        if (chosen) K.plateStrong else K.plateStrong.copy(alpha = 0f), androidx.compose.animation.core.tween(220), label = "chosen",
                    )
                    Column(Modifier.padding(vertical = if (chosen) K.gap1 else 0.dp).clip(RoundedCornerShape(K.rTile)).background(shade)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(K.rTile))
                                .clickable { open = if (chosen) -1 else i }
                                .padding(horizontal = K.gap3, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                                if (chosen) Box(
                                    Modifier.size(13.dp).clip(K.capsule).background(K.accent)
                                        .padding(3.5.dp).clip(K.capsule).background(K.bg),
                                ) else Box(
                                    Modifier.size(7.dp)
                                        .clip(K.capsule)
                                        .background(if (i == 0 || i == list.lastIndex) K.text else K.surface4),
                                )
                            }
                            Spacer(Modifier.width(K.gap3))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    net.stopName(s), fontSize = 16.sp,
                                    color = if (chosen || i == 0 || i == list.lastIndex) K.text else K.muted,
                                    fontWeight = if (chosen) FontWeight.Medium else null,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val c = net.cityName(s)
                                if (c.isNotBlank()) Text(c, style = K.type.subhead, maxLines = 1)
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            open == i,
                            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
                            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                                androidx.compose.animation.fadeOut(tween(120)),
                        ) {
                            LineAtStop(net, routes, s, last = i == list.lastIndex) {
                                model.stationStop = s; model.tab = uk.noammm.kav.Tab.Stations
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineAtStop(net: Net, routes: Set<Int>, stop: Int, last: Boolean, onBoard: () -> Unit) {
    val now = nowSec()
    val times = remember(routes, stop) {
        if (last) emptyList()
        else {
            val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
            routes.flatMap { net.lineTimesAt(stop, it, today) }.distinct().sorted()
        }
    }
    val train = routes.any { net.rType[it] == 2 }
    var all by remember(routes, stop) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(start = K.gap3 + 18.dp + K.gap3, end = K.gap3, bottom = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        val next = times.filter { it >= now }.take(3)
        Text(
            when {
                last -> T("This line ends here.", "הקו מסתיים כאן.")
                next.isEmpty() && train -> T("No more trains on this line today.", "אין עוד רכבות בקו הזה היום.")
                next.isEmpty() -> T("No more buses on this line today.", "אין עוד יציאות בקו הזה היום.")
                else -> T("Next: ", "הבאים: ") + next.joinToString(" · ") { relative(it, now) ?: hhmm(it) }
            },
            style = K.type.body.tabular,
        )
        androidx.compose.animation.AnimatedVisibility(
            all,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            Text(times.joinToString("   ") { hhmm(it) }, style = K.type.subhead.tabular, lineHeight = 22.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            if (times.isNotEmpty()) KavButton(
                if (all) T("Hide schedule", "הסתרת לוח הזמנים") else T("Today's schedule", "לוח הזמנים להיום"),
                { all = !all }, style = ButtonStyle.Secondary,
            )
            KavButton(T("Stop board", "לוח התחנה"), onBoard, style = ButtonStyle.Secondary)
        }
    }
}

// shape: the line's road from Kav's server; shapeId: Moovit's, for one of its trips.
private class LineLive(
    val checked: Boolean,
    val failed: Boolean = false,
    val vehicles: List<Moovit.Arrival> = emptyList(),
    val shapeId: Int = -1,
    val shape: List<Pair<Double, Double>> = emptyList(),
)

@Composable
private fun rememberLineLive(net: Net, route: Int, stops: List<Int>): LineLive {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fromServer = KavLive.enabled
    val stopsFromServer = KavLive.stopsEnabled
    val live by produceState(LineLive(checked = false), route, stops, lifecycle, fromServer, stopsFromServer) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val number = net.rShort[route].trim()
            // The timetable gives a train no number to look its line up by, and each train a route of its own.
            val train = net.rType[route] == 2 && number.isEmpty()
            val trains = if (train) net.trainLine(route) else emptySet()
            // A train line's track from Kav's server, by the route of a train of it under way: the timetable gives the
            // line no number to ask by. Each route is asked once.
            val tracks = HashMap<Int, List<Pair<Double, Double>>>()
            while (true) {
                value = try {
                    withContext(Dispatchers.IO) {
                        when {
                            stopsFromServer && train -> {
                                val on = KavLive.trainsOn(net, trains, stops)
                                val track = on.map { -it.lineId }.distinct().firstNotNullOfOrNull { r ->
                                    tracks.getOrPut(r) { runCatching { KavLive.shape(r) }.getOrDefault(emptyList()) }.takeIf { it.size >= 2 }
                                }
                                LineLive(checked = true, vehicles = on, shape = track.orEmpty())
                            }
                            fromServer && !train -> serverLineLive(number, net.rLong[route].trim(), stops.lastOrNull()?.let { net.name[it] })
                            train -> trainLive(net, trains, stops)
                            else -> lineLive(net, stops, number)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (value.checked && !value.failed) value else LineLive(checked = true, failed = true)
                }
                delay(20_000)
            }
        }
    }
    return live
}

// The line's buses from Kav's server: the routes with its number that are this one, by the ministry's name for it, or
// for a timetable a little older than the ministry's, by where they end. With the name, the road the route takes too,
// whether or not a bus is on it now: the line is drawn on its streets, not stop to stop.
private fun serverLineLive(number: String, name: String, last: String?): LineLive {
    val area = KavLive.line(number)
    val exact = area.lines.filterValues { name.isNotBlank() && it.name == name }.keys
    val routes = exact.ifEmpty { area.lines.filterValues { last != null && it.to == last }.keys }
    val shape = exact.minOrNull()?.let { runCatching { KavLive.shape(it) }.getOrNull() }.orEmpty()
    return LineLive(checked = true, vehicles = area.buses.filter { it.route in routes }.map { KavLive.busArrival(it) }, shape = shape)
}

private suspend fun lineLive(net: Net, stops: List<Int>, number: String): LineLive = coroutineScope {
    val ids = stops.chunked(8).flatMap { batch -> batch.map { async { StopPhotos.idOf(net, it) } }.awaitAll() }
        .filterNotNull().distinct()
    if (ids.isEmpty()) return@coroutineScope LineLive(checked = true)
    val s = Online.open(net.lat[stops[0]] to net.lon[stops[0]])
    val arrivals = Moovit.stopArrivals(s, ids).first.values
    val ours = arrivals.map { it.lineId }.distinct().chunked(8).flatMap { batch ->
        batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll()
    }.filter { (_, info) -> info?.number?.trim() == number }.map { it.first }.toSet()
    val candidates = arrivals.filter { it.lineId in ours }
    val order = ids.withIndex().associate { (i, id) -> id to i }
    val sameWay = candidates.map { it.patternId }.filter { it > 0 }.distinct().map { id ->
        async { id to runCatching { Moovit.tripPattern(s, id) }.getOrDefault(emptyList()) }
    }.awaitAll().filter { (_, pattern) ->
        val at = pattern.mapNotNull { order[it] }
        at.size >= 2 && at.zipWithNext().all { (a, b) -> a < b }
    }.map { it.first }.toSet()
    val mine = candidates.filter { it.patternId in sameWay }
    val vehicles = mine.filter { it.hasLocation }.groupBy { it.tripId }.values
        .map { at -> at.minBy { it.departure().timeUtc } }
    LineLive(checked = true, vehicles = vehicles, shapeId = (vehicles.firstOrNull() ?: mine.firstOrNull())?.tripShapeId ?: -1)
}

// A train line's trains from Moovit: of Moovit's trains due at the line's stations, those on one of its routes, by
// when the timetable has them there and where Moovit's line for them runs.
private suspend fun trainLive(net: Net, routes: Set<Int>, stops: List<Int>): LineLive = coroutineScope {
    val stopOf = stops.chunked(8).flatMap { batch -> batch.map { s -> async { StopPhotos.idOf(net, s)?.let { it to s } } }.awaitAll() }
        .filterNotNull().toMap()
    if (stopOf.isEmpty()) return@coroutineScope LineLive(checked = true)
    val s = Online.open(net.lat[stops[0]] to net.lon[stops[0]])
    val arrivals = Moovit.stopArrivals(s, stopOf.keys.toList()).first.values.filter { it.hasLocation }
    val lines = arrivals.map { it.lineId }.distinct().chunked(8).flatMap { batch ->
        batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull()?.number.orEmpty() } }.awaitAll()
    }.toMap()
    val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
    val midnight = System.currentTimeMillis() / 1000 - nowSec()
    val vehicles = arrivals.filter { a ->
        val at = stopOf[a.stopId] ?: return@filter false
        val line = lines[a.lineId].orEmpty()
        val row = net.trainLeaving(at, (a.staticUtc - midnight).toInt(), today, { moovitLineStartsAt(line, it) }) {
            moovitLineEndsAt(line, it)
        }
        row != null && net.tripRoute[row.trip] in routes
    }.groupBy { it.tripId }.values.map { at -> at.minBy { it.departure().timeUtc } }
    LineLive(checked = true, vehicles = vehicles, shapeId = vehicles.firstOrNull()?.tripShapeId ?: -1)
}

@Composable
private fun LineMap(
    net: Net, route: Int, stops: List<Int>, live: LineLive, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier,
) {
    val calls = remember(stops) { stops.map { net.lat[it] to net.lon[it] } }
    val moovit = rememberLineRoute(live.shapeId)
    // Moovit's road comes with a trip of the line due somewhere on it, which the next look may not have.
    var road by remember(route) { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    val now = live.shape.ifEmpty { moovit }
    LaunchedEffect(now) { if (now.isNotEmpty()) road = now }
    LineRouteMap(calls, now.ifEmpty { road }, net.rType[route], live.vehicles, modifier, selected, onSelect, Operators.of(net, route))
}

@Composable
private fun LineRouteMap(
    calls: List<Pair<Double, Double>>, shape: List<Pair<Double, Double>>, rt: Int,
    vehicles: List<Moovit.Arrival>, modifier: Modifier, selected: Int = -1, onSelect: ((Int) -> Unit)? = null,
    operator: Color? = null,
) {
    val tint = plateFor(rt)?.fill ?: operator?.takeIf(::hued)?.let { onScreen(it) } ?: K.route
    val path = shape.ifEmpty { calls }
    val spots = rememberKavSpots(remember(vehicles) { KavLive.routesOf(vehicles) })
    val shown = remember(vehicles, spots) { vehicles.map { KavLive.fresher(it, spots) } }
    val placed = remember(path, calls) { calls.map { onRoute(it, path) } }
    val geometry = remember(path, placed, tint, K.light, selected) {
        val start = placed.first()
        val end = placed.last()
        MapGeometry(
            lines = listOf(MapLine(path, tint, 4f, casing = 8f)),
            dots = placed.drop(1).dropLast(1).flatMap { (lat, lon) ->
                listOf(MapDot(lat, lon, K.bg, 6f), MapDot(lat, lon, Color.Transparent, 3.5f, tint, 2f))
            } + listOf(
                MapDot(start.first, start.second, K.bg, 7f),
                MapDot(start.first, start.second, Color.Transparent, 5f, K.text, 2f),
                MapDot(end.first, end.second, K.bg, 8f),
                MapDot(end.first, end.second, K.text, 5f),
            ) + listOfNotNull(placed.getOrNull(selected)).flatMap { (lat, lon) ->
                listOf(MapDot(lat, lon, K.bg, 11f), MapDot(lat, lon, Color.Transparent, 7f, K.accent, 3f), MapDot(lat, lon, K.text, 2.5f))
            },
        )
    }
    val mode = modeOf(rt)
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    // The open stop with the stops either side of it, so it's seen where it is on the line.
    val framed = if (selected in placed.indices) placed.subList((selected - 1).coerceAtLeast(0), (selected + 2).coerceAtMost(placed.size)) else path
    TileMap(
        framed, modifier, geometry = geometry, focusKey = selected,
        live = vehicleGeometry(shown.map { it to mode }, LocalDensity.current),
        onTap = onSelect?.let { pick ->
            { at, proj ->
                placed.indices.map { it to (proj.point(placed[it].first, placed[it].second) - at).getDistance() }
                    .filter { it.second <= reach }.minByOrNull { it.second }?.let { pick(it.first) }
            }
        },
    )
}

@Composable
private fun LineLiveNote(live: LineLive, next: String?) {
    val count = live.vehicles.size
    val text = when {
        !live.checked -> T("Checking for a live location…", "בודקים מיקום בזמן אמת…")
        live.failed -> T("Couldn't check for a live location", "לא ניתן היה לבדוק מיקום בזמן אמת")
        count == 0 -> T("No live location right now", "אין כרגע מיקום בזמן אמת")
        else -> T("Live location · $count on the road", "מיקום בזמן אמת · $count בדרך")
    }
    // What's found fades in over "Checking...", and the next departure slides open under it, instead of popping in.
    var shownNext by remember { mutableStateOf(next) }
    if (next != null) shownNext = next
    val tint by animateColorAsState(if (count > 0) K.realtime else K.textTertiary, tween(200), label = "lineLiveTint")
    Column(Modifier.padding(horizontal = K.gap4).padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            LiveGlyph(tint, 14.dp)
            AnimatedContent(
                text to (count > 0),
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "lineLive",
            ) { (t, on) -> Text(t, style = K.type.subhead, color = if (on) K.realtime else K.textSecondary) }
        }
        AnimatedVisibility(
            live.checked && count == 0 && next != null,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) { Text(shownNext.orEmpty(), style = K.type.subhead) }
    }
}

// Seconds from today's midnight.
private fun nextStart(net: Net, routes: Set<Int>): Pair<Int, Long>? {
    val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
    val now = nowSec()
    var best = -1
    var bestAt = Long.MAX_VALUE
    for (t in net.tripRoute.indices) {
        if (net.tripRoute[t] !in routes || net.tripStart[t + 1] <= net.tripStart[t]) continue
        val dep = net.stDep[net.tripStart[t]].toLong()
        for (k in -1..7) {
            if (!net.runsOn(t, ((today + k) % 7 + 7) % 7)) continue
            val at = k * 86_400L + dep
            if (at >= now && at < bestAt) { bestAt = at; best = t }
        }
    }
    return if (best < 0) null else best to bestAt
}

private fun startLabel(at: Long): String {
    val time = hhmm((at % 86_400).toInt())
    return when (val days = (at / 86_400).toInt()) {
        0 -> T("today $time", "היום $time")
        1 -> T("tomorrow $time", "מחר $time")
        else -> {
            val day = java.util.Calendar.getInstance(ISRAEL).apply { add(java.util.Calendar.DAY_OF_YEAR, days) }
            java.text.SimpleDateFormat("EEEE", T.locale).apply { timeZone = ISRAEL }.format(day.time) + " " + time
        }
    }
}

// Lines Moovit has and the national timetable does not: municipal services, and shuttles. Na'im
// BaSofash is in the timetable once it was built with the city's own (NAIM_NAME), and Moovit's
// copy then stays out. Kept for the run, since the file only changes with Moovit's data.
private var onlineLines: List<Moovit.LineGroup>? = null

private const val NAIM = 2910830

private fun flat(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

// An operator is in the timetable when its name, or its English alias in brackets, is one
// of the timetable's, or one of those appears in it as whole words ("... - תבל - ...").
private fun inTimetable(name: String, flats: Set<String>, runs: List<List<String>>): Boolean {
    val alias = name.substringAfter("(", "").substringBefore(")")
    if (listOf(name, name.substringBefore(" ("), alias).any { flat(it).let { f -> f.isNotEmpty() && f in flats } }) return true
    val w = words(name)
    return runs.any { k -> k.isNotEmpty() && w.windowed(k.size).any { it == k } }
}

private suspend fun missingLines(net: Net): List<Moovit.LineGroup> = withContext(Dispatchers.IO) {
    val all = Moovit.lineCatalogue(Online.open())
    val flats = net.agency.map(::flat).toSet()
    val runs = net.agency.map(::words)
    val known = HashMap<Int, Boolean>()
    all.filter { g ->
        val name = (if (g.agencyId == NAIM) NAIM_NAME else Moovit.agencyName(g.agencyId)) ?: return@filter false
        !known.getOrPut(g.agencyId) { inTimetable(name, flats, runs) }
    }
}

// Moovit files these shuttles as type 7, which in the timetable is the Carmelit.
private fun onlineType(g: Moovit.LineGroup) = if (g.routeType == 7) 711 else g.routeType

private fun operatorOf(g: Moovit.LineGroup): String =
    if (g.agencyId == NAIM) T("Na'im BaSofash", NAIM_NAME) else Moovit.agencyName(g.agencyId).orEmpty()

// Found by either name in either language, typed with or without the gershayim.
private fun aliases(g: Moovit.LineGroup): String =
    if (g.agencyId == NAIM) NAIM_WORDS else Moovit.agencyName(g.agencyId).orEmpty()

private fun shownOnline(lines: List<Moovit.LineGroup>, q: String, types: IntArray): List<Pair<String, List<Moovit.LineGroup>>> {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return lines.filter { g ->
        (types.isEmpty() || onlineType(g) in types) &&
            "${g.number} ${g.name} ${g.cities} ${operatorOf(g)} ${aliases(g)}".lowercase().let { h -> need.all { it in h } }
    }.groupBy(::operatorOf).mapValues { (_, gs) ->
        gs.sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }, { it.cities }))
    }.toList()
}

@Composable
private fun OnlineLineCard(g: Moovit.LineGroup, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(onlineType(g), g.number, busOperator(onlineType(g), g.agencyId) { Operators.ofName(Moovit.agencyName(g.agencyId)) })
        Column(Modifier.weight(1f)) {
            Text(
                g.cities.ifBlank { g.name }, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (g.cities.isNotBlank() && g.name.isNotBlank()) Text(
                g.name, style = K.type.subhead, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        RowChevron()
    }
}

private class OnlineDirection(
    val lineId: Int,
    val stops: List<Moovit.StopInfo>,
    val shape: List<Pair<Double, Double>>,
    val departures: List<Long>,
)

private class OnlineLine(val directions: List<OnlineDirection>, val failed: Boolean = false)

// The line's trips on the first day within a week that still has a departure to come,
// one direction per Moovit line, with the stops and shape of its usual trip.
private suspend fun loadOnlineLine(g: Moovit.LineGroup): OnlineLine = withContext(Dispatchers.IO) {
    val s = Online.open()
    val now = System.currentTimeMillis() / 1000
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem") }
    var trips = emptyList<Moovit.LineTrips>()
    var fallback = emptyList<Moovit.LineTrips>()
    for (k in -1..7) {
        val found = Moovit.lineGroupTrips(s, g.id, day.format(java.util.Date((now + k * 86_400L) * 1000)))
        if (found.isEmpty()) continue
        if (k >= 0 && fallback.isEmpty()) fallback = found
        if (found.any { t -> t.departures.any { it >= now } }) { trips = found; break }
    }
    if (trips.isEmpty()) trips = fallback
    val directions = trips.groupBy { it.lineId }.map { (lineId, ts) ->
        async {
            val usual = ts.maxBy { it.departures.size }
            val ids = Moovit.tripPattern(s, usual.patternId)
            val stops = ids.chunked(6).flatMap { batch ->
                batch.map { id -> async { runCatching { Moovit.stopInfo(s, id) }.getOrNull() } }.awaitAll()
            }.filterNotNull().filter { it.point != null }
            val shape = runCatching { Moovit.tripShape(s, usual.shapeId) }.getOrDefault(emptyList())
            OnlineDirection(lineId, stops, shape, ts.flatMap { it.departures }.sorted())
        }
    }.awaitAll().filter { it.stops.size >= 2 }
    OnlineLine(directions)
}

@Composable
internal fun OnlineLineDetail(g: Moovit.LineGroup, onBack: () -> Unit) {
    val line by produceState<OnlineLine?>(null, g.id) {
        value = try { loadOnlineLine(g) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            OnlineLine(emptyList(), failed = true)
        }
    }
    var pick by remember(g.id) { mutableIntStateOf(0) }
    val dir = line?.directions?.getOrNull(pick)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line ", "קו ") + g.number, back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LineIdentity(onlineType(g), g.number, busOperator(onlineType(g), g.agencyId) { Operators.ofName(Moovit.agencyName(g.agencyId)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                val to = dir?.stops?.lastOrNull()?.name
                Text(
                    if (to != null) T("To \u2068$to\u2069", "אל \u2068$to\u2069") else g.cities.ifBlank { g.name },
                    style = K.type.bodyStrong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(operatorOf(g), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val directions = line?.directions.orEmpty()
        if (directions.size > 1) Row(
            Modifier.padding(horizontal = K.gap3).padding(top = K.gap3).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            directions.forEachIndexed { i, d ->
                Chip(T("To ${d.stops.last().name}", "אל ${d.stops.last().name}"), i == pick) { pick = i }
            }
        }
        when {
            line == null -> LoadingBlock(T("Loading the line", "טוענים את הקו…"))
            dir == null && line?.failed == true ->
                Note(T("Couldn't load this line from Moovit.", "לא ניתן היה לטעון את הקו הזה מ-Moovit."), Modifier.padding(K.gap4))
            dir == null ->
                NoteCard(T("This line has no trips in the coming week.", "אין לקו הזה נסיעות בשבוע הקרוב."), Modifier.padding(K.gap4))
            else -> OnlineDirectionView(dir, g.number, train = onlineType(g) == 2)
        }
    }
}

@Composable
private fun OnlineDirectionView(dir: OnlineDirection, number: String, train: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fromServer = KavLive.enabled
    val stopsFromServer = KavLive.stopsEnabled
    val live by produceState(LineLive(checked = false), dir.lineId, lifecycle, fromServer, stopsFromServer) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = try {
                    // Moovit names a train line "from - to", no number the server knows: its trains are found by when they set out.
                    if (stopsFromServer && train) withContext(Dispatchers.IO) {
                        val where = dir.stops.mapNotNull { s -> s.code.toIntOrNull()?.let { it to s.point } }.toMap()
                        LineLive(checked = true, vehicles = KavLive.trainsStarting(where.keys.toList(), dir.departures) { where[it] })
                    }
                    else if (fromServer && !train) withContext(Dispatchers.IO) { serverLineLive(number.trim(), "", dir.stops.lastOrNull()?.name) }
                    else withContext(Dispatchers.IO) {
                        val arrivals = Moovit.stopArrivals(Online.open(), dir.stops.map { it.id }.take(60)).first.values
                        LineLive(
                            checked = true,
                            vehicles = arrivals.filter { it.lineId == dir.lineId && it.hasLocation }
                                .groupBy { it.tripId }.values.map { at -> at.minBy { it.departure().timeUtc } },
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (value.checked && !value.failed) value else LineLive(checked = true, failed = true)
                }
                delay(20_000)
            }
        }
    }
    val calls = remember(dir) { dir.stops.mapNotNull { it.point } }
    LineRouteMap(
        calls, dir.shape, 3, live.vehicles,
        Modifier.padding(horizontal = K.gap3).padding(top = K.gap3).fillMaxWidth().height(210.dp).panel(K.rCard),
    )
    val next = remember(dir) {
        val now = System.currentTimeMillis() / 1000
        dir.departures.firstOrNull { it >= now }?.let { at ->
            val from = dir.stops.first().name
            val label = startLabel(at - (now - nowSec()))
            T("Next departure from $from: $label", "היציאה הבאה מ-$from: $label")
        }
    }
    LineLiveNote(live, next)
    Text(
        T.count(dir.stops.size, "1 stop", "תחנה אחת", "stops", "תחנות") + T(" · full route", " · המסלול המלא"),
        style = K.type.caption, modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
    )) {
        items(dir.stops.size) { i ->
            val ends = i == 0 || i == dir.stops.lastIndex
            Row(
                Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(7.dp).clip(K.capsule).background(if (ends) K.text else K.surface4))
                }
                Spacer(Modifier.width(K.gap3))
                Text(
                    shownName(dir.stops[i].name), style = K.type.subhead, color = if (ends) K.text else K.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
