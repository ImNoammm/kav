package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.nearestStops
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight

/** The shared rounded search field. */
@Composable
fun KavField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = K.text, fontSize = 15.sp),
        cursorBrush = SolidColor(K.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassSurface(24.dp)
            .focusRequester(focus)
            .semantics { contentDescription = placeholder }
            .padding(horizontal = K.gap4, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = K.dim)
                innerTextField()
            }
        },
    )
}

/**
 * One stop in a list: name, then city and whatever second fact the caller has.
 *
 * The number on the pole goes on that second line. Search already matches it
 * (Search.kt drops bare numbers to the stop code), so a rider could type 12345,
 * get the stop, and have nothing on screen to check it against.
 */
@Composable
fun StopRow(net: Net, stop: Int, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(K.rControl))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                net.stops[stop].name, fontSize = 15.sp, color = K.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val second = listOfNotNull(net.cityOf(stop).takeIf { it.isNotBlank() }, net.stopCode(stop))
                .joinToString(" · ")
            if (second.isNotBlank()) {
                Text(second, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(K.gap2))
            Text(trailing, fontSize = 14.sp, color = K.dim)
        }
    }
}

/**
 * Place search, backed by Moovit's own V4/CloudSearch/FullSearch, the same stations,
 * streets and sites the official app offers, in its order, with the straight-line
 * distance it prints beside each one. Laid out like Moovit's list: a type icon with
 * the distance under it, the name, then where it is.
 *
 * Above the search, the rider's own places: Home first, then whatever they have added.
 * A place that is set is one tap; one that is not yet turns the search into "where is
 * it?", and the next result picked becomes it, saved, nothing more. A long press
 * renames it, changes its icon or removes it.
 */
@Composable
fun PlacePicker(
    title: String,
    here: Pair<Double, Double>?,
    allowMyLocation: Boolean,
    onMyLocation: () -> Unit,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    initialSetting: Favourite? = null,
    favourites: List<Favourite>,
    onSaveFavourites: (List<Favourite>) -> Unit,
    /** Held by the caller, so dismissing the picker and opening it again comes back
     *  to what was typed rather than to an empty box. */
    query: String,
    onQuery: (String) -> Unit,
    /** Where a fix goes when this screen asks for one itself. */
    onLocate: (Pair<Double, Double>) -> Unit = {},
    /** The offline timetable, when it is open: what "select on map" points at. */
    net: Net? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val q = query
    var results by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(uk.noammm.kav.Prefs.recents(ctx)) }
    /** a favourite whose place is being chosen: the next pick is saved as it */
    var setting by remember { mutableStateOf(initialSetting) }
    var editing by remember { mutableStateOf<Favourite?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun save(list: List<Favourite>) = onSaveFavourites(list)
    val pick: (Moovit.Place) -> Unit = { p ->
        val f = setting
        if (f != null) {
            // named by the rider, placed by the search: the favourite keeps its own
            // name and takes the result's coordinates and description. Placing it is
            // all that happens, no trip is planned to a place just being saved.
            save(favourites.map { if (it.id == f.id) it.copy(place = p) else it })
            setting = null
            onQuery("")
            // opened only to place a favourite (from the home strip), that done, leave
            if (initialSetting != null) onDismiss()
        } else {
            uk.noammm.kav.Prefs.remember(ctx, p)
            onPick(p)
        }
    }

    LaunchedEffect(q) {
        results = emptyList(); error = null; busy = q.isNotBlank()
        if (q.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(280)   // debounce
        try {
            results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val s = Online.session ?: Moovit.register(here?.first ?: 32.0759, here?.second ?: 34.7745)
                    .also { Online.session = it }
                Moovit.searchPlaces(s, q, here?.first ?: 32.0759, here?.second ?: 34.7745)
            }
            busy = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Every keystroke cancels the previous lookup. That is NOT a failure and
            // it must not clear `busy`: the effect that replaced this one owns the
            // spinner now, and turning it off here is what left the screen stuck on
            // "Searching…" forever when a later request finished first.
            throw e
        } catch (e: Exception) {
            results = emptyList(); error = e.message ?: e.javaClass.simpleName
            busy = false
        }
    }

    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, here, onPick = { p -> onMap = false; pick(p) }, onDismiss = { onMap = false },
            onLocate = onLocate,
        )
        return
    }
    // Back out of "where is X?" into a plain search only when the picker itself asked
    // the question. Opened from Home to place a favourite, this whole screen IS that
    // question: stepping back from it means Home, and dropping into a bare search
    // instead is what left a rider who tapped Home, or who had just made a new
    // favourite there, staring at a search box they never asked for.
    val leave: () -> Unit = { if (setting != null && initialSetting == null) setting = null else onDismiss() }
    androidx.activity.compose.BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(K.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            BackButton(leave)
            KavField(q, onQuery, setting?.let { T("where is ${it.label}?", "היכן נמצא ${it.label}?") } ?: title, Modifier.weight(1f), autoFocus = true)
        }
        if (q.isBlank() && setting == null) FavouriteStrip(
            favourites,
            onPick = { f -> f.place?.let(pick) ?: run { setting = f } },
            onAdd = { creating = true },
            onEdit = { editing = it },
        )
        setting?.let { f ->
            Note(
                T(
                    "Search for where ${f.label} is. The place you pick is kept as ${f.label}.",
                    "חפשו היכן נמצא ${f.label}. המקום שתבחרו יישמר בתור ${f.label}.",
                ),
                Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
            )
        }
        // Some stops cannot be typed: you know the shelter, not the name printed on
        // it. Pointing at it on the map asks the same question the other way. Offered
        // whatever the picker was opened for, and whether or not the timetable has
        // been parsed yet: gating the row on `net` is what hid it on every launch
        // that had not been through Stops or Lines first, which is most of them.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3)
                .heightIn(min = 48.dp).glassSurface(24.dp)
                .clickable(role = Role.Button) { onMap = true }
                .padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                drawCircle(K.muted, w * .30f, Offset(w * .5f, w * .38f), style = Stroke(w * .13f))
                drawLine(K.muted, Offset(w * .5f, w * .68f), Offset(w * .5f, w * .98f), w * .13f, StrokeCap.Round)
            }
            Text(T("Select on map", "בחירה על המפה"), fontSize = 15.sp, color = K.muted)
        }
        // Offered whether or not there is a fix yet. Hiding it until one arrives is
        // what left a rider who opened Kav with location switched off unable to start
        // from where they are at all; now the row is the thing that goes and gets it.
        if (allowMyLocation) {
            var locating by remember { mutableStateOf(false) }
            val askHere = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ok ->
                if (ok) { locating = true; requestLocationOnce(ctx) { onLocate(it); locating = false } }
                else locating = false
            }
            val ready = here != null
            Row(
                Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap2).heightIn(min = 48.dp)
                    .glassSurface(24.dp)
                    .clickable(role = Role.Button) {
                        if (ready) onMyLocation()
                        else if (hasLocationPermission(ctx)) {
                            locating = true
                            requestLocationOnce(ctx) { onLocate(it); locating = false }
                        } else {
                            askHere.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (ready) K.live else K.dim),
                )
                Text(
                    if (ready) T("My location", "המיקום שלי") else if (locating) T("Finding you…", "מאתרים אתכם…") else T("Use my location", "השתמשו במיקום שלי"),
                    fontSize = 15.sp, color = if (ready) K.live else K.muted,
                )
                if (!ready && !locating) Text(
                    T("location is off", "המיקום כבוי"),
                    fontSize = 12.sp, color = K.dim, modifier = Modifier.padding(start = K.gap1),
                )
            }
        }
        when {
            error != null -> Note(T("Search failed: $error", "החיפוש נכשל: $error"), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() && recents.isEmpty() ->
                Note(T("Search a station, street or place.", "חפשו תחנה, רחוב או מקום."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() -> Unit
            // centred in what the keyboard leaves, not in the whole height behind it
            busy && results.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomCover()),
                contentAlignment = Alignment.Center,
            ) {
                LoadingPulse(T("Searching", "מחפשים"))
            }
            results.isEmpty() -> Note(T("Nothing found.", "לא נמצאו תוצאות."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        val showRecents = q.isBlank() && recents.isNotEmpty()
        if (results.isNotEmpty() || showRecents) {
            Row(
                Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap3, top = K.gap2, bottom = K.gap1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showRecents) T("Recent", "אחרונים") else T("Places", "מקומות"),
                    fontSize = 14.sp, color = K.dim, modifier = Modifier.weight(1f),
                )
                if (showRecents) Chip(T("Clear", "ניקוי"), false) {
                    uk.noammm.kav.Prefs.clearRecents(ctx); recents = emptyList()
                }
            }
        }
        // Only when there is a list: a column that fills the page would take the
        // space the searching mark is centred in.
        if (results.isNotEmpty() || showRecents) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(if (showRecents) recents else results) { p -> PlaceRow(p) { pick(p) } }
        }
    }

    if (creating) FavouriteEditor(
        existing = null,
        onSave = { name, icon ->
            val fresh = Favourite("f${System.currentTimeMillis()}", name, icon, null)
            save(favourites + fresh)
            creating = false
            setting = fresh
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
            // Home stays, so its place can only be moved; the rest can go
            onRemove = if (f.id == Favourite.HOME) null else { { save(favourites.filter { it.id != f.id }); editing = null } },
            onDismiss = { editing = null },
            // already on the search: turn it into "where is X?" rather than leaving
            // and coming back to the same screen
            onChangePlace = { editing = null; setting = f; onQuery("") },
        )
    }
}

/**
 * Pick a stop by pointing at it. The search wants a name, and a stop you can see out
 * of the window is exactly the one whose name you do not have: this asks the same
 * question on the ground instead. Tapping a stop names it and offers it; nothing is
 * chosen until that offer is taken, so a mis-aimed thumb costs one more tap.
 *
 * The stops are the ones around where the map opens rather than all thirty thousand in
 * the country: every stop in Israel at once is a screen of circles nobody can aim at,
 * and the ones worth pointing at are the ones you could reach.
 *
 * [net] is the timetable if the app has already parsed it; this screen parses it
 * itself when it has not, rather than being unreachable until some other tab has.
 */
@Composable
fun StopMapPicker(
    net: Net?,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    /** Where a fix goes when this screen asks for one itself. */
    onLocate: (Pair<Double, Double>) -> Unit = {},
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The map is aimed at where the rider is, so it should also say where that is.
    // With no fix yet, go and ask for one: the permission has either been granted
    // already, and this costs a moment, or it has not, and the map still works with
    // no dot on it. No permission prompt is raised here; the row that opened this
    // screen is where that question belongs.
    LaunchedEffect(here == null) {
        if (here == null && hasLocationPermission(ctx)) requestLocationOnce(ctx, onLocate)
    }
    var loaded by remember { mutableStateOf(net) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(net) {
        if (net != null) { loaded = net; return@LaunchedEffect }
        if (loaded != null) return@LaunchedEffect
        try {
            loaded = uk.noammm.kav.loadNet(ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }
    val open = loaded
    if (open == null) {
        Column(Modifier.fillMaxSize().background(K.bg)) {
            StopMapHeader(onDismiss)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadError == null) LoadingPulse(T("Opening the map", "פותחים את המפה"))
                else Note(
                    T("The stop list could not be opened: $loadError", "לא ניתן היה לפתוח את רשימת התחנות: $loadError"),
                    Modifier.padding(K.gap4), K.problem,
                )
            }
        }
        return
    }
    StopMapBody(open, here, onPick, onDismiss)
}

@Composable
private fun StopMapHeader(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        BackButton(onDismiss)
        Text(T("Tap a stop", "הקישו על תחנה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StopMapBody(
    net: Net,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val centre = here ?: (32.0759 to 34.7745)   // central Tel Aviv, until located
    val stops = remember(net, centre) {
        net.nearestStops(centre.first, centre.second, k = 500, radius = 6000.0).map { it.first }
    }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val reach = with(LocalDensity.current) { 26.dp.toPx() }
    // framed on what is near, not on the furthest stop the list happens to reach
    val points = remember(stops, centre) { listOf(centre) + stops.take(12).map { net.stops[it].lat to net.stops[it].lon } }
    val geometry = remember(stops, chosen, here) {
        MapGeometry(
            dots = stops.flatMap { i ->
                val on = i == chosen
                listOf(
                    MapDot(net.stops[i].lat, net.stops[i].lon, K.bg, if (on) 8f else 6f),
                    MapDot(
                        net.stops[i].lat, net.stops[i].lon, androidx.compose.ui.graphics.Color.Transparent,
                        if (on) 6f else 4.2f, if (on) K.accent else K.muted, if (on) 2.4f else 1.6f,
                    ),
                )
            } + (
                // Where the rider is, so the stops around it can be read against it.
                // A filled disc under a halo, never the ring a stop wears, because the
                // chosen stop is accent-coloured too and two accent rings would be
                // one question: which of these is me?
                here?.let { (lat, lon) ->
                    listOf(
                        MapDot(lat, lon, K.live.copy(alpha = .18f), 13f),
                        MapDot(lat, lon, K.bg, 6f),
                        MapDot(lat, lon, K.live, 4f),
                    )
                } ?: emptyList()
                ),
        )
    }
    Column(Modifier.fillMaxSize().background(K.bg)) {
        StopMapHeader(onDismiss)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TileMap(
                points,
                Modifier.fillMaxSize(),
                recenterOn = here,
                geometry = geometry,
                onTap = { at, proj ->
                    chosen = stops
                        .map { it to (proj.point(net.stops[it].lat, net.stops[it].lon) - at).getDistance() }
                        .filter { it.second <= reach }.minByOrNull { it.second }?.first
                },
            )
            // The card rides up from the edge it is pinned to and drops back through it,
            // on the spring the When sheet uses, so opening a stop and dismissing one
            // are the same gesture run in two directions.
            //
            // `last` outlives `chosen` by one exit. Read straight from `chosen`, the
            // card emptied itself the instant the stop was cleared and then slid away
            // blank, which reads as the card breaking rather than leaving.
            var last by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(chosen) { chosen?.let { last = it } }
            // qualified: inside a Box the bare name resolves to the enclosing
            // ColumnScope overload, which cannot be called on an outer receiver
            androidx.compose.animation.AnimatedVisibility(
                visible = chosen != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)) { it } +
                    fadeIn(tween(140)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                last?.let { i ->
                val detail = listOfNotNull(
                    net.cityOf(i).takeIf { it.isNotBlank() }, net.stopCode(i),
                ).joinToString(" · ")
                Column(
                    Modifier.fillMaxWidth()
                        // The same bottom edge the When sheet and the alert panel have
                        // to clear: this screen draws behind the floating tab bar, so a
                        // card pinned to BottomCenter ends up with its name, its detail
                        // and both its buttons under Home/Stations/Lines/Live.
                        .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                        .padding(K.gap3)
                        .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(
                        net.stops.getOrNull(i)?.name ?: T("Stop", "תחנה"), fontSize = 17.sp, color = K.text,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) Text(detail, fontSize = 14.sp, color = K.dim, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                                .background(K.plateStrong).clickable(role = Role.Button) { chosen = null }
                                .padding(horizontal = K.gap4),
                            contentAlignment = Alignment.Center,
                        ) { Text(T("Not this one", "לא זו"), fontSize = 14.sp, color = K.text) }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                                .background(K.accent).clickable(role = Role.Button) { onPick(placeOf(net, i)) }
                                .padding(horizontal = K.gap5),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(T("Choose this stop", "בחירת התחנה"), fontSize = 14.sp, color = K.bg, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Moovit.Place, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(K.rControl))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaceGlyph(p.type)
            if (p.meters >= 0) Text(
                distanceLabel(p.meters.toDouble()), fontSize = 14.sp, color = K.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(K.gap2))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 15.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.detail.isNotBlank()) Text(
                p.detail, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A stop gets a vehicle, a street a road sign, everything else a map pin. */
@Composable
private fun PlaceGlyph(type: Int) {
    when (type) {
        1 -> ModeGlyph(Mode.BUS, K.muted, 17.dp)
        2 -> androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width; val h = size.height; val sw = w * .10f
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .30f, h * .12f),
                androidx.compose.ui.geometry.Offset(w * .18f, h * .88f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .70f, h * .12f),
                androidx.compose.ui.geometry.Offset(w * .82f, h * .88f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .50f, h * .28f),
                androidx.compose.ui.geometry.Offset(w * .50f, h * .48f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .50f, h * .62f),
                androidx.compose.ui.geometry.Offset(w * .50f, h * .82f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
        }
        else -> androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width; val h = size.height
            drawCircle(K.muted, w * .22f, androidx.compose.ui.geometry.Offset(w * .5f, h * .38f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w * .11f))
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .5f, h * .58f),
                androidx.compose.ui.geometry.Offset(w * .5f, h * .90f), w * .11f,
                androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
