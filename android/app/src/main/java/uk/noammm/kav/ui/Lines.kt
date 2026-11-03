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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.shownName
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.representativeTrip
import uk.noammm.kav.data.lineTimesAt
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.searchRoutes
import uk.noammm.kav.data.trainLine
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
    // A provider's line stays open only while it's still the timetable picked.
    val line = model.scheduleLine?.takeIf { model.scheduleSource == Schedules.source }
    androidx.activity.compose.BackHandler(model.lineRoute >= 0 || line != null) { model.backFromLine() }
    val list = remember(net) { lineListFor(net) }
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(model.lineRoute, line, Schedules.source),
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState.first >= 0 || targetState.second != null) forward() else backward() },
        label = "line",
    ) { (route, open, source) ->
        when {
            open != null -> ScheduleLineDetail(model, net, open) { model.backFromLine() }
            route >= 0 -> TimetableLineDetail(model, net, route) { model.backFromLine() }
            source.provider -> ScheduleLineList(model, net, list)
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

// note: why these are the week's lines, when the timetable's provider couldn't be asked.
@Composable
private fun LineList(model: KavModel, net: Net, list: LineListState, note: String? = null) {
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
            if (note != null) item(key = "note") { NoteCard(note, Modifier.padding(bottom = K.gap3)) }
            item(key = "search") { LineSearch(q, { q = it }, types) { types = it } }
            item(key = "count") {
                val total = sections.sumOf { it.second.size }
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
        }
        ScrollEdge(listState.canScrollBackward)
        }
    }
}

// The search card and the mode chips over a list of lines.
@Composable
private fun LineSearch(q: String, onQ: (String) -> Unit, types: IntArray, onTypes: (IntArray) -> Unit) {
    Column {
        // The same card as the Stops tab's search.
        Box(Modifier.fillMaxWidth().panel(K.rCard)) {
            KavField(q, onQ, T("Line number or name", "מספר או שם קו"), Modifier.fillMaxWidth(), search = true, flat = true)
        }
        Spacer(Modifier.height(K.gap2))
        Row(
            Modifier
                .bleed(K.gap4)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = K.gap4),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            FILTERS.forEach { (label, t) -> Chip(filterLabel(label), types === t) { onTypes(t) } }
        }
        Spacer(Modifier.height(K.gap3))
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

// A line's end as its row shows it, up to any "/": in English by the whole stop's translation first, as the part before
// "/" seldom has one of its own.
internal fun shownEnd(net: Net, stop: Int): String =
    net.nameEn.getOrNull(stop)?.takeIf { StopNames.english && it.isNotEmpty() }?.substringBefore('/')?.trim()
        ?: net.shown(terminusName(net, stop))

// The same for a provider's line, whose ends are the ministry's names. A mark after one (Moovit's ✈ on trains to the
// airport) stays after the name as shown.
internal fun providerEnd(name: String): String {
    val core = name.trim().trimEnd { it.isWhitespace() || it.category == CharCategory.OTHER_SYMBOL || it == '\uFE0E' || it == '\uFE0F' }
    val mark = name.trim().substring(core.length).trim()
    val shown = shownName(core).takeIf { it != core }?.substringBefore('/')?.trim() ?: shownName(core.substringBefore('/').trim())
    return if (mark.isEmpty()) shown else "$shown $mark"
}

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
    val a = shownEnd(net, row.a); val b = shownEnd(net, row.b)
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

// note: why this is the week's line, when the timetable's provider couldn't be asked.
@Composable
internal fun LineDetail(model: KavModel, net: Net, route: Int, note: String? = null, onBack: () -> Unit) {
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
        if (note != null) NoteCard(note, Modifier.padding(horizontal = K.gap4).padding(top = K.gap3))
        val list = stops
        // The stop tapped on this line opens under it with the line's next buses there, and
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
                                model.openStation(s)
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
        LiveText(
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

// shape: the line's road from Kav's server.
private class LineLive(
    val checked: Boolean,
    val failed: Boolean = false,
    val vehicles: List<Transit.Arrival> = emptyList(),
    val shape: List<Pair<Double, Double>> = emptyList(),
)

@Composable
private fun rememberLineLive(net: Net, route: Int, stops: List<Int>): LineLive {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live by produceState(LineLive(checked = false), route, stops, lifecycle, KavLive.source, KavLive.server) {
        value = LineLive(checked = false)
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
                        if (train) {
                            val on = KavLive.trainsOn(net, trains, stops)
                            val track = on.map { -it.lineId }.distinct().firstNotNullOfOrNull { r ->
                                tracks.getOrPut(r) { runCatching { KavLive.shape(r) }.getOrDefault(emptyList()) }.takeIf { it.size >= 2 }
                            }
                            LineLive(checked = true, vehicles = on, shape = track.orEmpty())
                        } else serverLineLive(number, net.rLong[route].trim(), stops.lastOrNull()?.let { net.name[it] }, stops.map { net.code[it] })
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
private fun serverLineLive(number: String, name: String, last: String?, codes: List<Int>): LineLive {
    val area = KavLive.line(number, codes)
    val exact = area.lines.filterValues { name.isNotBlank() && it.name == name }.keys
    val routes = exact.ifEmpty { area.lines.filterValues { last != null && it.to == last }.keys }
    val shape = exact.minOrNull()?.let { runCatching { KavLive.shape(it) }.getOrNull() }.orEmpty()
    return LineLive(checked = true, vehicles = area.buses.filter { it.route in routes }.map { KavLive.busArrival(it) }, shape = shape)
}

@Composable
private fun LineMap(
    net: Net, route: Int, stops: List<Int>, live: LineLive, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier,
) {
    val calls = remember(stops) { stops.map { net.lat[it] to net.lon[it] } }
    // A train line's track comes with a train of it under way, which the next look may not have.
    var road by remember(route, KavLive.source, KavLive.server) { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    val now = live.shape
    LaunchedEffect(now) { if (now.isNotEmpty()) road = now }
    LineRouteMap(calls, now.ifEmpty { road }, net.rType[route], live.vehicles, modifier, selected, onSelect, Operators.of(net, route))
}

@Composable
private fun LineRouteMap(
    calls: List<Pair<Double, Double>>, shape: List<Pair<Double, Double>>, rt: Int,
    vehicles: List<Transit.Arrival>, modifier: Modifier, selected: Int = -1, onSelect: ((Int) -> Unit)? = null,
    operator: Color? = null, trains: Boolean = rt == 2,
) {
    val tint = plateFor(rt)?.fill ?: operator?.takeIf(::hued)?.let { onScreen(it) } ?: K.route
    val path = shape.ifEmpty { calls }
    // A train line's trains are where their times put them (KavLive.withTrainsPlaced), which the ministry's positions
    // for them don't change: those can stay where a train stood, with fresh times, while it runs on.
    val spots = rememberKavSpots(remember(vehicles, trains) { if (trains) emptyList() else KavLive.routesOf(vehicles) })
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
        live = vehicleGeometry(shown.map { it to mode }, LocalDensity.current, track = { path }),
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

// The Lines tab from the timetable's provider (Schedules): its lines, and a line's ways with their stops and times,
// asked as they open. Saved, recent and nearby lines are the week on the phone's, found among the provider's by the
// ministry's names. Without the provider, the week's lines and line pages show, and say why.

// The provider's list, kept while it's the one picked, so coming back from a line lands where the list was.
private class ScheduleListState(val source: Source) {
    var lines by mutableStateOf<List<Schedules.Line>?>(null)
    var failed by mutableStateOf(false)
    var types by mutableStateOf(FILTERS[0].second)
    val scroll = LazyListState()
}

private var keptSchedule: ScheduleListState? = null

private fun unreachable(source: Source, what: String) = T(
    "Couldn't reach ${sourceName(source)}. $what",
    "${sourceName(source)} לא זמין. $what",
)

@Composable
private fun ScheduleLineList(model: KavModel, net: Net, week: LineListState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val source = Schedules.source
    val state = remember(source) { keptSchedule?.takeIf { it.source == source } ?: ScheduleListState(source).also { keptSchedule = it } }
    // Asked again each time the tab opens while it couldn't be.
    LaunchedEffect(state) {
        if (state.lines != null) return@LaunchedEffect
        state.failed = false
        state.lines = try {
            withContext(Dispatchers.IO) { Schedules.lines(ctx) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state.failed = true; null
        }
    }
    val lines = state.lines
    when {
        lines != null -> ScheduleLines(model, net, state, lines)
        state.failed -> LineList(model, net, week, unreachable(source,
            T("These are the lines in the timetable on your phone.", "אלה הקווים שבלוח הזמנים בטלפון.")))
        else -> LineList(model, net, week)
    }
}

@Composable
private fun ScheduleLines(model: KavModel, net: Net, state: ScheduleListState, lines: List<Schedules.Line>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by model::lineQuery
    var types by state::types
    var sections by remember(lines) { mutableStateOf(emptyList<Pair<String, List<Schedules.Line>>>()) }
    var lead by remember(lines) { mutableStateOf(emptyList<Pair<String, List<Schedules.Line>>>()) }
    var leadReady by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(lines, q, types) { sections = withContext(Dispatchers.Default) { foldSchedule(lines, q, types) } }
    LaunchedEffect(Unit) {
        if (model.here == null && uk.noammm.kav.hasLocationPermission(ctx)) {
            uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    LaunchedEffect(lines, model.here, q, types, model.savedLines) {
        lead = if (q.isNotBlank() || types.isNotEmpty()) emptyList()
        else withContext(Dispatchers.Default) { scheduleLead(ctx, net, model.here, lines) }
        leadReady = true
    }
    // As the week's list: the full list waits for Recent and Nearby.
    val listReady = leadReady || q.isNotBlank() || types.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Lines", "קווים"))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = state.scroll, contentPadding = PaddingValues(
                start = K.gap4, end = K.gap4, bottom = LocalBottomBarInset.current + K.gap4,
            )) {
                item(key = "search") { LineSearch(q, { q = it }, types) { types = it } }
                item(key = "count") {
                    Text(
                        T.count(sections.sumOf { it.second.size }, "1 line", "קו אחד", "lines", "קווים") +
                            T(" · from ${sourceName(Schedules.source)}", " · מ־${sourceName(Schedules.source)}"),
                        style = K.type.caption.tabular, modifier = Modifier.padding(horizontal = K.gap4),
                    )
                }
                lead.forEach { (label, rows) ->
                    item(key = "lead-$label") { SectionLabel(label) }
                    itemsIndexed(rows, key = { _, it -> "lead-$label-${it.id}" }) { i, l ->
                        Box(Modifier.animateItem()) { ScheduleLineRow(l, i == 0, i == rows.lastIndex) { model.openSchedule(l, -1) } }
                    }
                }
                if (listReady) sections.forEach { (operator, rows) ->
                    if (operator.isNotBlank()) item(key = "op-$operator") { SectionLabel(operatorLabel(operator)) }
                    itemsIndexed(rows, key = { _, it -> "line-${it.id}" }) { i, l ->
                        Box(Modifier.animateItem()) { ScheduleLineRow(l, i == 0, i == rows.lastIndex) { model.openSchedule(l, -1) } }
                    }
                }
            }
            ScrollEdge(state.scroll.canScrollBackward)
        }
    }
}

// The provider's lines as the list shows them: of the modes picked and with every word searched for (a number starting
// the line's), in their operators' sections, the biggest first, and by number in each.
private fun foldSchedule(lines: List<Schedules.Line>, q: String, types: IntArray): List<Pair<String, List<Schedules.Line>>> {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return lines.asSequence().distinctBy { it.id }
        .filter { l -> types.isEmpty() || l.type in types }
        .filter { l ->
            val h = (l.number + " " + l.names.joinToString(" ") + " " + l.from + " " + l.to + " " + l.operator + " " +
                shownName(l.from) + " " + shownName(l.to)).lowercase()
            need.all { if (it.all(Char::isDigit)) l.number.lowercase().startsWith(it) else h.contains(it) }
        }
        .groupBy { it.operator }.toList()
        .sortedByDescending { it.second.size }
        .map { (operator, ls) -> operator to ls.sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }, { it.from })) }
}

// The week's routes among a provider's lines: by the ministry's name with the number, else the one line of that
// operator with that number.
internal class ScheduleMatch(private val net: Net, lines: List<Schedules.Line>) {
    private val byName = HashMap<String, Schedules.Line>()
    private val byNumber = HashMap<String, MutableList<Schedules.Line>>()

    init {
        for (l in lines) {
            for (n in l.names) byName.putIfAbsent("${l.number}|$n", l)
            byNumber.getOrPut("${l.operator}|${l.type}|${l.number}") { ArrayList() }.add(l)
        }
    }

    fun of(route: Int): Schedules.Line? {
        val number = if (net.rType[route] == 2) "" else net.rShort[route]
        return byName["$number|${net.rLong[route]}"] ?: byNumber["${net.agencyOf(route)}|${net.rType[route]}|$number"]?.singleOrNull()
    }
}

// A provider's line in the week, by the ministry's names of its routes: for its key (saved and recent lines), live
// locations as the week's line page has them, and its page when the provider can't be asked. -1 when the week hasn't it.
private fun weekRouteOf(net: Net, line: Schedules.Line): Int {
    val names = line.names.toHashSet()
    return (0 until net.nRoutes).firstOrNull { r -> net.rLong[r] in names && (line.type == 2 || net.rShort[r] == line.number) } ?: -1
}

// Saved, recent and nearby lines, as the week's list has them, among the provider's.
private fun scheduleLead(
    ctx: android.content.Context, net: Net, here: Pair<Double, Double>?, lines: List<Schedules.Line>,
): List<Pair<String, List<Schedules.Line>>> {
    val match = ScheduleMatch(net, lines)
    val byKey = HashMap<String, Int>()
    for (r in 0 until net.nRoutes) byKey.putIfAbsent(lineKey(net, r), r)
    fun keyed(keys: List<String>) = keys.mapNotNull { k -> byKey[k]?.let(match::of) }.distinctBy { it.id }
    val out = ArrayList<Pair<String, List<Schedules.Line>>>()
    keyed(uk.noammm.kav.Prefs.savedLines(ctx)).takeIf { it.isNotEmpty() }?.let { out.add(T("Saved", "שמורים") to it) }
    keyed(uk.noammm.kav.Prefs.recentLines(ctx)).takeIf { it.isNotEmpty() }?.let { out.add(T("Recent", "אחרונים") to it) }
    if (here != null) {
        val near = net.nearestStops(here.first, here.second, k = 20, radius = 900.0)
        val rank = HashMap<Int, Int>(near.size * 2)
        near.forEachIndexed { i, (s, _) -> rank[s] = i }
        val best = HashMap<Int, Int>()
        if (rank.isNotEmpty()) for (t in net.tripRoute.indices) {
            val route = net.tripRoute[t]
            for (i in net.tripStart[t] until net.tripStart[t + 1]) {
                val o = rank[net.stStop[i]] ?: continue
                val held = best[route]
                if (held == null || o < held) best[route] = o
            }
        }
        val rows = best.entries.sortedBy { it.value }.mapNotNull { match.of(it.key) }.distinctBy { it.id }.take(10)
        if (rows.isNotEmpty()) out.add(T("Nearby", "בקרבת מקום") to rows)
    }
    return out
}

@Composable
private fun ScheduleLineRow(line: Schedules.Line, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(line.type, line.number, busOperator(line.type, -1) { Operators.ofName(line.operator) })
        val a = providerEnd(line.from); val b = providerEnd(line.to)
        Text(
            when {
                b.isEmpty() || a == b -> "⁨$a⁩"
                line.both -> "⁨$a⁩ ↔ ⁨$b⁩"
                else -> T("⁨$a⁩ → ⁨$b⁩", "⁨$a⁩ ← ⁨$b⁩")
            },
            style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        RowChevron()
    }
}

// A local line opens before the provider's catalogue is read. Keep its stop focus through the background lookup.
@Composable
private fun TimetableLineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    val source = Schedules.source
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val code = remember(route, source) { model.lineFocusStop.takeIf { it >= 0 }?.let { net.code[it] } ?: -1 }
    var line by remember(route, source) { mutableStateOf<Schedules.Line?>(null) }
    var failed by remember(route, source) { mutableStateOf(false) }
    LaunchedEffect(route, source) {
        if (source.provider) try {
            line = withContext(Dispatchers.IO) { ScheduleMatch(net, Schedules.lines(ctx, source)).of(route) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { failed = true }
    }
    val found = line
    if (found != null) ScheduleLineDetail(model, net, found, code, onBack)
    else LineDetail(model, net, route, if (failed) unreachable(source,
        T("This is the line in the timetable on your phone.", "זה הקו שבלוח הזמנים בטלפון.")) else null, onBack)
}

// A provider's line, with the local week shown while its ways load. Its stops open to the provider's times today.
@Composable
private fun ScheduleLineDetail(model: KavModel, net: Net, line: Schedules.Line, code: Int = model.scheduleStop, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val source = Schedules.source
    val route = remember(line) { weekRouteOf(net, line) }
    LaunchedEffect(line) { if (route >= 0) uk.noammm.kav.Prefs.rememberLine(ctx, lineKey(net, route)) }
    var ways by remember(line, source) { mutableStateOf<List<Schedules.Way>?>(null) }
    var failed by remember(line, source) { mutableStateOf(false) }
    LaunchedEffect(line, source) {
        if (code > 0) model.lineFocusStop = KavLive.stopOfCode(net, code)
        ways = try {
            withContext(Dispatchers.IO) { Schedules.ways(line) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true; null
        }
    }
    if (ways == null && route >= 0) {
        LineDetail(model, net, route, if (failed) unreachable(source,
            T("This is the line in the timetable on your phone.", "זה הקו שבלוח הזמנים בטלפון.")) else null, onBack)
        return
    }
    // The way with the stop it was opened at, one riders get on there before one they only get off; else the first.
    var pick by remember(line) { mutableIntStateOf(0) }
    var focus by remember(line) { mutableIntStateOf(code) }
    LaunchedEffect(ways) {
        if (focus > 0) ways?.let { list ->
            val boards = list.indexOfFirst { w -> w.stops.indices.any { w.stops[it].code == focus && it !in w.alight && it < w.stops.lastIndex } }
            (boards.takeIf { it >= 0 } ?: list.indexOfFirst { w -> w.stops.any { it.code == focus } }).takeIf { it >= 0 }?.let { pick = it }
        }
        model.scheduleStop = -1
    }
    val all = ways.orEmpty()
    val way = all.getOrNull(pick)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line ", "קו ") + line.number, back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LineIdentity(line.type, line.number, busOperator(line.type, -1) { Operators.ofName(line.operator) })
            if (way == null) Text(line.operator, style = K.type.body, color = K.muted, modifier = Modifier.weight(1f))
            // The way's ends move with its stops below when another way is picked.
            else AnimatedContent(
                pick to way, Modifier.weight(1f),
                transitionSpec = { sideways(targetState.first > initialState.first).using(SizeTransform(clip = false)) },
                contentKey = { it.second.id }, label = "wayEnds",
            ) { (_, w) ->
                Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                    fun name(s: Schedules.Stop) = providerStopName(net, s) +
                        providerStopCity(net, s).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    Text(T("To ⁨${name(w.stops.last())}⁩", "אל ⁨${name(w.stops.last())}⁩"),
                        style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(T("From ⁨${name(w.stops.first())}⁩", "מ־⁨${name(w.stops.first())}⁩"),
                        fontSize = 12.sp, lineHeight = 17.sp, color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Kept at the top of the Lines tab, by the week's line it is.
            if (route >= 0) {
                val key = lineKey(net, route)
                val saved = key in model.savedLines
                KavIconButton(
                    if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (saved) T("Saved, tap to remove", "שמור, הקישו להסרה") else T("Save this line", "שמירת הקו"),
                    { model.saveLines(ctx, if (saved) model.savedLines - key else listOf(key) + model.savedLines) },
                    floating = true, tint = if (saved) K.accentInk else K.textSecondary,
                )
            }
        }
        if (all.size > 1) Row(
            Modifier.padding(top = K.gap3).horizontalScroll(rememberScrollState()).padding(horizontal = K.gap4),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            val labels = remember(all) { wayLabels(net, all) }
            all.forEachIndexed { i, _ -> Chip(labels[i], i == pick) { pick = i; focus = -1 } }
        }
        when {
            ways == null && !failed -> LoadingBlock(T("Loading the line from ${sourceName(source)}", "טוענים את הקו מ${sourceName(source)}"))
            failed -> NoteCard(unreachable(source, T("Try again in a moment.", "נסו שוב בעוד רגע.")), Modifier.padding(K.gap4))
            way == null -> NoteCard(T("This line has no trips in the coming days.", "אין לקו הזה נסיעות בימים הקרובים."), Modifier.padding(K.gap4))
            else -> AnimatedContent(
                pick to way, Modifier.fillMaxSize(),
                transitionSpec = { sideways(targetState.first > initialState.first) },
                contentKey = { it.second.id }, label = "way",
            ) { (_, w) -> Column(Modifier.fillMaxSize()) { ScheduleWayView(model, net, line, w, focus) } }
        }
    }
}

// Each way's button: where it ends, most often its last stop's name and then named as that stop is. The first way to a
// place (each end's main one, Schedules.arrange) is just that; a later one to the same place also says where it starts,
// or, starting at the same place too, how many stops it calls at.
internal fun wayLabels(net: Net, ways: List<Schedules.Way>): List<String> {
    val ends = ways.map { w ->
        val last = w.stops.last()
        if (last.name.trim() == w.to.trim()) providerStopName(net, last) else shownName(w.to)
    }
    val starts = ways.map { providerStopName(net, it.stops.first()).substringBefore('/').trim() }
    return ways.indices.map { i ->
        val to = T("To ⁨${ends[i]}⁩", "אל ⁨${ends[i]}⁩")
        val same = ways.indices.filter { ends[it] == ends[i] }
        when {
            same.first() == i -> to
            same.count { starts[it] == starts[i] } == 1 -> to + T(" · from ⁨${starts[i]}⁩", " · מ־⁨${starts[i]}⁩")
            else -> to + " · " + T.count(ways[i].stops.size, "1 stop", "תחנה אחת", "stops", "תחנות")
        }
    }
}

// A provider's stop and town as shown. In English, by the week's names for its code first: a provider can name a stop its
// own way, and the week's translations, found by name, then miss it. A stop the week has is in the week's town, as the
// week's line pages show it: none for a train station, which Bus Nearby puts in the town "תחנת רכבת".
internal fun providerStopName(net: Net, s: Schedules.Stop): String =
    weekStopOf(net, s)?.let { net.nameEn.getOrNull(it)?.ifEmpty { null } } ?: shownName(s.name)

internal fun providerStopCity(net: Net, s: Schedules.Stop): String =
    KavLive.stopOfCode(net, s.code).takeIf { it >= 0 }?.let { net.cityName(it) } ?: shownName(s.city)

private fun weekStopOf(net: Net, s: Schedules.Stop): Int? =
    if (StopNames.english) KavLive.stopOfCode(net, s.code).takeIf { it >= 0 } else null

// A way of a provider's line: its live note, map and stops. Live locations come from the live choice, as on the week's
// line page, by the week's route for this way when the provider names it and its stops are all the week's; else by the
// line's number and where the way ends.
@Composable
private fun ScheduleWayView(model: KavModel, net: Net, line: Schedules.Line, way: Schedules.Way, focus: Int) {
    val stops = remember(way) { way.stops.map { KavLive.stopOfCode(net, it.code) } }
    val route = remember(way) {
        line.names.getOrNull(line.routes.indexOf(way.id))?.let { name ->
            (0 until net.nRoutes).firstOrNull { net.rLong[it] == name && (line.type == 2 || net.rShort[it] == line.number) }
        } ?: -1
    }
    val live = if (route >= 0 && stops.all { it >= 0 }) rememberLineLive(net, route, stops) else rememberScheduleLive(net, line, way)
    var next by remember(way) { mutableStateOf<String?>(null) }
    LaunchedEffect(way) { next = withContext(Dispatchers.IO) { runCatching { scheduleNextStart(net, line, way) }.getOrNull() } }
    var open by remember(way) { mutableIntStateOf(-1) }
    val stopsState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(way) {
        val at = way.stops.indices.firstOrNull { way.stops[it].code == focus && it !in way.alight } ?: way.stops.indexOfFirst { it.code == focus }
        if (focus > 0 && at >= 0) { open = at; stopsState.scrollToItem(at) }
    }
    LineLiveNote(live, next)
    // The stops the provider placed, and which of the way's each is on the map.
    val placed = remember(way) { way.stops.indices.filter { way.stops[it].lat.isFinite() && way.stops[it].lon.isFinite() } }
    if (placed.size >= 2) LineRouteMap(
        placed.map { way.stops[it].lat to way.stops[it].lon }, way.shape.ifEmpty { live.shape }, line.type, live.vehicles,
        Modifier.padding(horizontal = K.gap4).padding(top = K.gap3).fillMaxWidth().height(210.dp).panel(K.rCard),
        placed.indexOf(open), { i -> open = placed[i]; scope.launch { stopsState.animateScrollToItem(placed[i]) } },
        Operators.ofName(line.operator),
    )
    SectionHeader(
        T.count(way.stops.size, "1 stop", "תחנה אחת", "stops", "תחנות") + T(" · from ${sourceName(Schedules.source)}", " · מ־${sourceName(Schedules.source)}"),
        Modifier.padding(horizontal = K.gap4),
    )
    LazyColumn(Modifier.fillMaxSize(), state = stopsState, contentPadding = PaddingValues(
        start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
    )) {
        items(way.stops.size) { i ->
            val s = way.stops[i]
            val chosen = open == i
            val ends = i == 0 || i == way.stops.lastIndex
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
                            Modifier.size(13.dp).clip(K.capsule).background(K.accent).padding(3.5.dp).clip(K.capsule).background(K.bg),
                        ) else Box(Modifier.size(7.dp).clip(K.capsule).background(if (ends) K.text else K.surface4))
                    }
                    Spacer(Modifier.width(K.gap3))
                    Column(Modifier.weight(1f)) {
                        Text(
                            providerStopName(net, s).ifBlank { s.code.toString() }, fontSize = 16.sp,
                            color = if (chosen || ends) K.text else K.muted, fontWeight = if (chosen) FontWeight.Medium else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val c = providerStopCity(net, s)
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
                    ScheduleAtStop(line, way, i, last = i == way.stops.lastIndex, alight = i in way.alight, board = stops[i].takeIf { it >= 0 }?.let { at ->
                        { model.openStation(at) }
                    })
                }
            }
        }
    }
}

// The line's buses from the live choice, for a provider's line the week hasn't: by its number and where the way ends.
@Composable
private fun rememberScheduleLive(net: Net, line: Schedules.Line, way: Schedules.Way): LineLive {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live by produceState(LineLive(checked = false), line.id, way.id, lifecycle, KavLive.source, KavLive.server) {
        value = LineLive(checked = false)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = try {
                    if (line.type == 2 || line.number.isBlank()) LineLive(checked = true)
                    else withContext(Dispatchers.IO) {
                        val name = line.names.getOrNull(line.routes.indexOf(way.id)).orEmpty()
                        // Live providers use the ministry's Hebrew names even when the timetable's page is in English.
                        val last = way.stops.lastOrNull()
                        val end = last?.let { KavLive.stopOfCode(net, it.code) } ?: -1
                        serverLineLive(line.number.trim(), name, if (end >= 0) net.name[end] else last?.name, way.stops.map { it.code })
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

// The way's next departure from its first stop riders get on at (a line can set out from a depot where none do), today
// or tomorrow, as the week's line page says it.
private fun scheduleNextStart(net: Net, line: Schedules.Line, way: Schedules.Way): String? {
    val first = way.stops.indices.firstOrNull { it !in way.alight && it < way.stops.lastIndex } ?: return null
    val today = Schedules.today()
    val midnight = Schedules.start(today)
    val now = System.currentTimeMillis() / 1000
    val at = (0..1).firstNotNullOfOrNull { k -> Schedules.times(line, way, first, today.plusDays(k.toLong())).firstOrNull { it >= now } }
        ?: return null
    val from = providerStopName(net, way.stops[first])
    return T("Next departure from $from: ${startLabel(at - midnight)}", "היציאה הבאה מ-$from: ${startLabel(at - midnight)}")
}

// A provider's line at one of its way's stops: its next times there and today's all, as the week's line page has them.
// alight: riders only get off it there, so it has no times to give.
@Composable
private fun ScheduleAtStop(line: Schedules.Line, way: Schedules.Way, at: Int, last: Boolean, alight: Boolean, board: (() -> Unit)?) {
    val now = nowSec()
    var times by remember(way.id, at) { mutableStateOf<List<Int>?>(if (last || alight) emptyList() else null) }
    var failed by remember(way.id, at) { mutableStateOf(false) }
    LaunchedEffect(way.id, at) {
        if (last || alight) return@LaunchedEffect
        times = try {
            withContext(Dispatchers.IO) {
                // Today's, from midnight; and before dawn, last night's still to leave.
                val today = Schedules.today()
                val midnight = Schedules.start(today)
                val night = if (now < 4 * 3600) Schedules.times(line, way, at, today.minusDays(1)) else emptyList()
                (night + Schedules.times(line, way, at, today)).map { (it - midnight).toInt() }.filter { it >= 0 }.distinct().sorted()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true; emptyList()
        }
    }
    val train = line.type == 2
    var all by remember(way.id, at) { mutableStateOf(false) }
    val list = times.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = K.gap3 + 18.dp + K.gap3, end = K.gap3, bottom = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        val next = list.filter { it >= now }.take(3)
        LiveText(
            when {
                last -> T("This line ends here.", "הקו מסתיים כאן.")
                alight -> T("Drop-off only. This line doesn't pick up riders here.", "הורדה בלבד. הקו לא אוסף כאן נוסעים.")
                times == null -> T("Checking its times…", "בודקים את הזמנים…")
                failed -> unreachable(Schedules.source, T("Its times here didn't load.", "הזמנים כאן לא נטענו."))
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
            Text(list.joinToString("   ") { hhmm(it) }, style = K.type.subhead.tabular, lineHeight = 22.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            if (list.isNotEmpty()) KavButton(
                if (all) T("Hide schedule", "הסתרת לוח הזמנים") else T("Today's schedule", "לוח הזמנים להיום"),
                { all = !all }, style = ButtonStyle.Secondary,
            )
            if (board != null) KavButton(T("Stop board", "לוח התחנה"), board, style = ButtonStyle.Secondary)
        }
    }
}

// A line opened from elsewhere (a stop's board or a trip): the provider's when the timetable is a
// provider's that lists it, else the week's. Opens the Lines tab.
internal fun openTimetableLine(model: KavModel, route: Int, stop: Int) {
    model.rememberReturn(uk.noammm.kav.Tab.Lines)
    model.scheduleLine = null
    model.lineFocusStop = stop; model.lineRoute = route; model.tab = uk.noammm.kav.Tab.Lines
}
