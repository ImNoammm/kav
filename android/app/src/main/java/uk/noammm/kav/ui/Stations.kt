package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitTrain
import uk.noammm.kav.KavModel
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.TrainName
import uk.noammm.kav.data.TrainsToday
import uk.noammm.kav.data.departuresAt
import uk.noammm.kav.data.moovitTrains
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.searchStops
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.stopKey
import uk.noammm.kav.data.stopOfKey
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

    // The stops saved, and those with line alerts, each with its next departures, live where they can be.
    val yours = remember(net, model.savedStops, model.lineAlerts) {
        (model.savedStops + model.lineAlerts.map { it.stop }).distinct().map { net.stopOfKey(it) }.filter { it >= 0 }
    }
    var t0 by remember { mutableIntStateOf(nowSec()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                t0 = nowSec()
                delay(30_000)
            }
        }
    }
    val fromServer = KavLive.stopsEnabled
    val yoursLive = remember(net) { mutableStateMapOf<Int, List<LiveAt>>() }
    // Shown only while nothing is searched for.
    val listing = q.isBlank()
    LaunchedEffect(yours, t0, fromServer, listing) {
        if (!listing) return@LaunchedEffect
        // From Kav's server, one question for them all.
        val board = if (!fromServer) null else runCatching {
            withContext(Dispatchers.IO) {
                KavLive.stops(yours.map { net.code.getOrElse(it) { 0 } }.filter { it > 0 }.distinct().take(KavLive.STOPS_MAX))
            }
        }.getOrNull()
        if (fromServer && board == null) return@LaunchedEffect
        for (s in yours) {
            val id = if (fromServer) -1 else StopPhotos.idNow(net, s) ?: StopPhotos.idOf(net, s) ?: -1
            stopLive(net, s, id, fromServer, board)?.let { yoursLive[s] = it.first }
        }
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

    // Nearby, a stop's town is named only where it isn't the nearest town's.
    val homeTown = near.firstNotNullOfOrNull { net.cityName(it.first).ifBlank { null } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Stops", "תחנות"))
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = K.gap4, end = K.gap4, bottom = LocalBottomBarInset.current + K.gap4,
        )) {
            item(key = "search") {
                // The search and the map on one card.
                Column(Modifier.fillMaxWidth().panel(K.rCard)) {
                    KavField(q, { q = it }, T("Search stops", "חיפוש תחנות"), Modifier.fillMaxWidth(), search = true, flat = true)
                    Separator()
                    SelectOnMapRow(0.dp, framed = false) { onMap = true }
                }
            }
            if (q.isNotBlank()) {
                if (hits.isEmpty()) item {
                    Note(T("No stop by that name or code.", "אין תחנה בשם או במספר הזה."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
                } else item { Spacer(Modifier.height(K.gap2)) }
                itemsIndexed(hits, key = { _, s -> s }) { i, s ->
                    StopListRow(net, s, null, town = true, first = i == 0, last = i == hits.lastIndex) { model.stationStop = s }
                }
            } else {
                if (yours.isNotEmpty()) {
                    item(key = "saved") { SectionHeader(T("Saved", "שמורות")) }
                    itemsIndexed(yours, key = { _, s -> "saved-$s" }) { i, s ->
                        SavedStopRow(
                            net, s, yoursLive[s].orEmpty(), t0, model.lineAlerts.filter { it.stop == net.stopKey(s) },
                            first = i == 0, last = i == yours.lastIndex,
                        ) { model.stationStop = s }
                    }
                }
                item(key = "nearby") { SectionHeader(T("Nearby", "בסביבה")) }
                when {
                    here == null -> item {
                        Column(Modifier.padding(horizontal = K.gap4)) {
                            Note(if (locating) T("Waiting for a fix…", "ממתינים למיקום…") else T("Kav doesn't know where you are yet.", "Kav עדיין לא יודע איפה אתם."))
                            Spacer(Modifier.height(K.gap3))
                            KavButton(
                                T("Use my location", "השתמשו במיקום שלי"), style = ButtonStyle.Secondary, busy = locating,
                                onClick = {
                                    if (hasLocationPermission(ctx)) {
                                        locating = true
                                        requestLocationOnce(ctx, onFail = { locating = false }) { model.locate(it.first, it.second); locating = false }
                                    } else {
                                        ask.launch(LOCATION_PERMISSIONS)
                                    }
                                },
                            )
                            Spacer(Modifier.height(K.gap3))
                            Text(
                                T(
                                    "Your location, read once, used only to sort this list. " +
                                        "It is never stored and never leaves the phone.",
                                    "המיקום שלכם, שנקרא פעם אחת, משמש רק למיון הרשימה הזו. הוא לעולם לא נשמר ולא יוצא מהטלפון.",
                                ),
                                style = K.type.subhead, color = K.textTertiary,
                            )
                        }
                    }
                    near.isEmpty() -> item {
                        Note(
                            T("No stops within 2.5 km of you.", "אין תחנות במרחק של 2.5 ק״מ מכם."),
                            Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                        )
                    }
                    else -> itemsIndexed(near, key = { _, it -> it.first }) { i, (s, d) ->
                        StopListRow(net, s, distanceLabel(d), town = net.cityName(s) != homeTown, first = i == 0, last = i == near.lastIndex) {
                            model.stationStop = s
                        }
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
    // From Kav's server the board needs nothing of Moovit, so the stop isn't looked up there.
    val fromServer = KavLive.stopsEnabled
    var moovitId by remember(stop) { mutableStateOf(StopPhotos.idNow(net, stop) ?: -1) }
    var looked by remember(stop) { mutableStateOf(moovitId > 0 || fromServer) }
    // Live times from Kav's server need nothing of Moovit, but the stop's photo does, while photos are on.
    LaunchedEffect(stop, fromServer, StopPhotos.enabled) {
        if (!fromServer || StopPhotos.enabled) moovitId = findMoovitId(net, stop, moovitId) { looked = true }
    }
    var live by remember(stop) { mutableStateOf<List<LiveAt>>(emptyList()) }
    // At a train station, what the live source says of the timetable's trains today: where some end early, and from
    // Moovit which run. The ministry's answers can leave out a train that runs, so Kav's own server never says one doesn't.
    var trains by remember(stop) { mutableStateOf(TrainsToday()) }
    LaunchedEffect(moovitId, t0, fromServer) {
        stopLive(net, stop, moovitId, fromServer)?.let { (l, t) -> live = l; trains = t }
    }
    fun liveAt(trip: Int, dep: Int): LiveAt? = live.at(net, trip, dep)
    val rows = remember(stop, t0, live) { boardRows(net, stop, t0, live, 60) }

    var alerting by remember(stop) { mutableStateOf(false) }
    val numbered = remember(stop) { net.linesAt(stop).isNotEmpty() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Departures", "יציאות"), back = onBack)
        Column(Modifier.padding(horizontal = K.gap4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StopGlyphOrPhoto(moovitId, null, thumb = 56.dp, resolving = !looked, kind = stopMode(net, stop))
                Spacer(Modifier.width(K.gap3))
                Column(Modifier.weight(1f)) {
                    Text(net.stopName(stop), style = K.type.headline)
                    Text(stopDetail(net, stop), style = K.type.subhead)
                }
            }
            Spacer(Modifier.height(K.gap3))
            ActionStrip {
                ActionTile(T("From here", "מכאן"), T("Plan a trip from this stop", "תכנון נסיעה מהתחנה הזו"), onClick = {
                    model.pendingFrom = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }) { ink, _ -> fromHereGlyph(ink) }
                ActionDivider()
                ActionTile(T("To here", "לכאן"), T("Plan a trip to this stop", "תכנון נסיעה לתחנה הזו"), onClick = {
                    model.pendingTo = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }) { ink, _ -> toHereGlyph(ink) }
                ActionDivider()
                val ctx = LocalContext.current
                // Kept on the Stations tab with its next departures.
                val key = net.stopKey(stop)
                val saved = key in model.savedStops
                ActionTile(
                    if (saved) T("Saved", "שמורה") else T("Save", "שמירה"),
                    if (saved) T("Saved. Tap to remove it from your stops", "שמורה. הקישו כדי להסיר אותה מהתחנות שלכם")
                    else T("Save this stop", "שמירת התחנה"),
                    lit = saved,
                    onClick = { model.saveStops(ctx, if (saved) model.savedStops - key else model.savedStops + key) },
                ) { ink, fill -> starGlyph(ink, fill) }
                ActionDivider()
                val alerts = model.lineAlerts.count { it.stop == key }
                // Line alerts go by line number: a stop no numbered line leaves from (a train station) has none to set.
                if (alerts > 0 || numbered) {
                    ActionTile(
                        if (alerts > 0) T("Alerts", "התראות") else T("Alert", "התראה"),
                        if (alerts > 0) T.count(
                            alerts, "1 line alert at this stop", "התראת קו אחת בתחנה הזו",
                            "line alerts at this stop", "התראות קווים בתחנה הזו",
                        ) else T("Get line alerts at this stop", "התראות על קווים בתחנה הזו"),
                        lit = alerts > 0, badge = alerts, onClick = { alerting = true },
                    ) { ink, fill -> bellGlyph(ink, fill) }
                    ActionDivider()
                }
                ActionTile(T("Street View", "Street View"), T("See this stop in Street View", "צפייה בתחנה ב-Street View"), onClick = {
                    StreetViewer.open = StopLook(
                        StreetLook(net.lat[stop].toDouble() to net.lon[stop].toDouble()), net.stopName(stop), net.cityName(stop),
                    )
                }) { ink, _ -> streetGlyph(ink) }
            }
        }
        Spacer(Modifier.height(K.gap3))
        if (rows.isEmpty()) {
            Note(T("Nothing more today or tomorrow.", "אין עוד יציאות היום או מחר."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        val lines = remember(rows, trains, T.lang) { rows.map { boardLine(net, it, live, trains, t0) } }
        val columns = boardColumns(net, lines)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap1, end = K.gap1, bottom = LocalBottomBarInset.current + K.gap4,
        )) {
            lines.forEachIndexed { i, l ->
                if (l.tomorrow && (i == 0 || !lines[i - 1].tomorrow)) item(key = "tomorrow") {
                    Text(
                        T("Tomorrow", "מחר"), style = K.type.caption,
                        modifier = Modifier.padding(start = K.gap3, end = K.gap3, top = K.gap4, bottom = K.gap1),
                    )
                }
                item {
                    BoardLineRow(net, l, columns) {
                        // The line itself, open at this stop, as the Lines tab shows it.
                        model.lineFocusStop = stop
                        model.lineRoute = l.route
                        model.tab = uk.noammm.kav.Tab.Lines
                    }
                }
            }
        }
    }
    if (alerting) AlertSheet(model, net, stop, moovitId) { alerting = false }
    }
}

// A board row as it's shown, worked out before it's drawn so every row can share one width per column. own (a live bus
// or train the timetable has no trip for, as Kav's server names it) opens no line. note: why a train isn't coming.
private class BoardLine(
    val route: Int, val label: String?, val type: Int, val agency: Int, val to: String, val platform: String,
    val time: String, val live: Moovit.Departure?, val note: String?, val cancelled: Boolean, val tomorrow: Boolean,
    val opens: Boolean,
)

private fun boardLine(net: Net, row: BoardRow, live: List<LiveAt>, trains: TrainsToday, t0: Int): BoardLine {
    val (c, dep, shown, own) = row
    val soon = relative(shown, t0)
    val time = soon ?: hhmm(shown)
    val tomorrow = soon == null && shown >= 86_400
    if (own != null) return BoardLine(
        -1, own.number, own.type, own.agency, net.shown(own.to.orEmpty()), own.platform, time, own.dep, null, false,
        tomorrow, opens = false,
    )
    val t = net.tripOf(net.cST[c])
    val route = net.tripRoute[t]
    val at = live.at(net, t, dep)
    val train = if (net.rType[route] == 2) at?.let { TrainName.of(it.number, it.platform) } ?: trains.named[t] else null
    // A train the live source would list but doesn't, or has cancelled: the week's timetable has it, today hasn't.
    val gone = at == null && trains.missing(net, t, dep)
    val cancelled = gone && t in trains.cancelled
    return BoardLine(
        route, train?.number, net.rType.getOrElse(route) { 3 }, -1,
        net.shown(at?.to ?: trains.ends[t] ?: net.name[net.tripLast(t)]), if (gone) "" else train?.platform.orEmpty(),
        time, at?.dep,
        if (!gone) null else if (cancelled) T("Canceled", "בוטלה") else T("Not running today", "לא פועלת היום"),
        cancelled, tomorrow, opens = true,
    )
}

// The board's column widths, each its widest row's: the line plates, the platforms and the times.
private class BoardColumns(val plate: Dp, val platform: Dp, val time: Dp)

@Composable
private fun boardColumns(net: Net, lines: List<BoardLine>): BoardColumns {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(lines, density) {
        fun width(text: String, style: TextStyle): Dp = with(density) { measurer.measure(text, style).size.width.toDp() }
        val plate = lines.maxOfOrNull { l ->
            val n = l.label?.ifBlank { null } ?: net.rShort.getOrElse(l.route) { "" }
            linePlateWidth(if (n.isBlank()) 0.dp else width(n, K.type.bodyStrong.tabular)) + 2.dp
        } ?: 0.dp
        val platform = lines.filter { it.platform.isNotEmpty() }.maxOfOrNull {
            width(platformLabel(it.platform), K.type.caption) + 14.dp
        } ?: 0.dp
        val time = lines.maxOfOrNull { l -> width(l.time, K.type.bodyStrong.tabular) + 2.dp + if (l.live != null) 16.dp else 0.dp } ?: 0.dp
        BoardColumns(plate, platform, time)
    }
}

private fun platformLabel(platform: String) = T("Platform $platform", "רציף $platform")

@Composable
private fun BoardLineRow(net: Net, l: BoardLine, columns: BoardColumns, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (l.opens) Modifier.clickable(onClickLabel = T("Show the line", "הצגת הקו"), onClick = onOpen) else Modifier)
            .padding(horizontal = K.gap3, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineBadge(net, l.route, Modifier.widthIn(min = columns.plate), label = l.label, type = l.type, agency = l.agency)
        Column(Modifier.weight(1f)) {
            Text(
                l.to, style = K.type.body, color = if (l.note != null) K.textTertiary else K.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            l.note?.let { Text(it, style = K.type.subhead, color = if (l.cancelled) K.critical else K.textSecondary) }
        }
        // Platforms share one width, so they line up; a row without one gives its destination the room.
        if (l.platform.isNotEmpty()) Text(
            platformLabel(l.platform), style = K.type.caption, color = K.text, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.width(columns.platform).clip(RoundedCornerShape(K.rBadge)).background(K.fill)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Row(Modifier.width(columns.time), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            l.live?.let {
                DepMarkGlyph(it, 12.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                l.time, style = K.type.bodyStrong.tabular, maxLines = 1,
                color = when { l.note != null -> K.textTertiary; l.live != null -> depColour(l.live); else -> K.scheduled },
                textDecoration = if (l.note != null) TextDecoration.LineThrough else null,
            )
        }
    }
}

// A live time at a stop: line number, scheduled and live seconds of the day, and the departure that colours and marks it
// like the search results; from Kav's server, also the timetable trip it is, for a train that ends somewhere else
// than the timetable says, where, and a train's platform. own: one the timetable has no trip for, on a row of its own
// with its line's mode (type), operator (agency, the ministry's id) and where it goes (to).
private class LiveAt(
    val number: String, val static: Int, val rt: Int, val dep: Moovit.Departure, val trip: Int = -1, val to: String? = null,
    val platform: String = "", val own: Boolean = false, val type: Int = 3, val agency: Int = -1,
)

// What the live source says at a stop: its live times and, at a station, what it says of the timetable's trains today.
// From Kav's server, the ministry's buses due there that are under way, each on the timetable trip it is; else
// Moovit's, by the stop's Moovit id. Null when it can't be asked.
private suspend fun stopLive(
    net: Net, stop: Int, moovitId: Int, fromServer: Boolean, asked: KavLive.Board? = null,
): Pair<List<LiveAt>, TrainsToday>? {
    val station = net.stopType[stop] == 2
    if (fromServer) {
        val code = net.code.getOrElse(stop) { 0 }
        if (code <= 0) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis() / 1000
                val board = asked ?: KavLive.stops(listOf(code))
                KavLive.boardTimes(net, stop, board, now).map { b ->
                    LiveAt(b.due.number, b.static, b.rt, Moovit.Departure(
                        KavLive.tripIdOf(b.due.key), b.due.expected - (b.rt - b.static), rtUtc = b.due.expected,
                    ), b.trip, b.to, b.due.platform, own = b.trip < 0, type = board.lines[b.due.route]?.type ?: 3,
                        agency = board.lines[b.due.route]?.agency ?: -1)
                } to if (station) KavLive.trainsToday(net, stop, board, now) else TrainsToday()
            }
        }.getOrNull()
    }
    if (moovitId <= 0) return null
    return runCatching {
        withContext(Dispatchers.IO) {
            val s = Online.open(net.lat[stop] to net.lon[stop])
            val day = java.util.Calendar.getInstance(ISRAEL).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000
            // A station's whole list says which trains run today; elsewhere only the live ones are wanted.
            val named = Moovit.stopArrivals(s, listOf(moovitId)).first.values.filter { station || it.rtUtc > 0 }
                .mapNotNull { a -> Moovit.lineInfo(s, a.lineId)?.number?.let { a to it } }
            // Moovit names a train's line "from - to" and Kav's timetable gives trains no number: a train is the
            // timetable's leaving at its time, from and to where its line runs.
            val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
            val (rows, known) = if (station) net.moovitTrains(stop, today, nowSec(), named.map { (a, line) ->
                MoovitTrain((a.staticUtc - day).toInt(), if (a.rtUtc > 0) (a.rtUtc - day).toInt() else -1, line, a.status == 3)
            }) else named.map { null } to TrainsToday()
            named.mapIndexedNotNull { i, (a, number) ->
                if (a.rtUtc <= 0) return@mapIndexedNotNull null
                LiveAt(number, (a.staticUtc - day).toInt(), (a.rtUtc - day).toInt(), a.departure(), rows[i]?.trip ?: -1, rows[i]?.to)
            } to known
        }
    }.getOrNull()
}

// A row's live time: the trip itself where it's known, else the line's number, at about the same time (a train works
// have moved can be a few minutes off its row).
private fun List<LiveAt>.at(net: Net, trip: Int, dep: Int): LiveAt? = firstOrNull {
    if (it.own) false
    else if (it.trip >= 0) it.trip == trip && kotlin.math.abs(it.static - dep) <= 2 * uk.noammm.kav.data.MOVED_S
    else it.number == net.rShort[net.tripRoute[trip]] && kotlin.math.abs(it.static - dep) <= 120
}

// A row of a stop's board: the timetable's connection c, its own time and the time shown, the live one where there is
// one; or (own) a live bus or train the timetable has no trip for, c -1, at its live time.
private data class BoardRow(val c: Int, val dep: Int, val shown: Int, val own: LiveAt? = null)

// A stop's next departures from t0 (seconds of the day), soonest first by the time shown. From half an hour back: a
// late bus or train is still to come after its timetabled time.
private fun boardRows(net: Net, stop: Int, t0: Int, live: List<LiveAt>, limit: Int): List<BoardRow> {
    val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
    return (net.departuresAt(stop, t0 - 1800, today, limit = 120)
        .map { (c, dep) -> BoardRow(c, dep, live.at(net, net.tripOf(net.cST[c]), dep)?.rt ?: dep) } +
        live.filter { it.own }.map { BoardRow(-1, it.rt, it.rt, it) })
        .filter { it.shown >= t0 }.sortedBy { it.shown }.take(limit)
}

// A saved stop: its next three departures, live ones coloured as on its board, and its line alerts.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedStopRow(
    net: Net, stop: Int, live: List<LiveAt>, t0: Int, alerts: List<LineAlert>, first: Boolean, last: Boolean, onClick: () -> Unit,
) {
    val rows = remember(stop, t0, live) { boardRows(net, stop, t0, live, 3) }
    Column(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last, inset = RowInset)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StationThumb(net, stop)
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f)) {
                Text(net.stopName(stop), style = K.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stopDetail(net, stop), style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(K.gap2))
        if (rows.isEmpty()) Text(T("Nothing more today or tomorrow", "אין עוד יציאות היום או מחר"), style = K.type.subhead)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            rows.forEach { (c, dep, shown, own) ->
                if (own != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(net, -1, label = own.number, type = own.type, agency = own.agency, dense = true)
                        Spacer(Modifier.width(6.dp))
                        Text(relative(shown, t0) ?: hhmm(shown), style = K.type.subhead.tabular, color = depColour(own.dep), maxLines = 1)
                    }
                    return@forEach
                }
                val t = net.tripOf(net.cST[c])
                val at = live.at(net, t, dep)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val train = if (net.rType[net.tripRoute[t]] == 2) at?.let { TrainName.of(it.number, it.platform) } else null
                    LineBadge(net, net.tripRoute[t], label = train?.number, dense = true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        relative(shown, t0) ?: hhmm(shown), style = K.type.subhead.tabular,
                        color = if (at != null) depColour(at.dep) else K.scheduled, maxLines = 1,
                    )
                }
            }
        }
        alerts.forEach { a ->
            Text(T("Alert: ", "התראה: ") + alertSummary(a), style = K.type.caption, color = K.textTertiary, modifier = Modifier.padding(top = K.gap1))
        }
    }
}

// A stop in a list: its photo, its name over its platform, code and town, and how far it is. The rows of a section
// share one card, the hairlines between them starting where the text does.
@Composable
private fun StopListRow(net: Net, stop: Int, distance: String?, town: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    ListRow(
        net.stopName(stop), Modifier.groupItem(first, last, inset = RowInset),
        subtitle = stopDetail(net, stop, town).ifBlank { null }, value = distance,
        leading = { StationThumb(net, stop) }, onClick = onClick,
    )
}

// Where a row's text starts, past its 40 dp photo.
private val RowInset = 68.dp

@Composable
private fun StationThumb(net: Net, stop: Int) = StopGlyphOrPhoto(net, stop, null, kind = stopMode(net, stop))

// The mode a stop serves, for its glyph.
internal fun stopMode(net: Net, stop: Int): Mode = net.stopType.getOrElse(stop) { 3 }.let { if (it >= 0) modeOf(it) else Mode.BUS }



internal fun placeOf(net: Net, stop: Int) = uk.noammm.kav.data.Moovit.Place(
    name = if (stop in net.name.indices) net.stopName(stop) else T("Stop", "תחנה"),
    detail = net.cityName(stop),
    lat = net.lat[stop].toDouble(),
    lon = net.lon[stop].toDouble(),
    type = 1,
    mode = net.stopType[stop],
)

