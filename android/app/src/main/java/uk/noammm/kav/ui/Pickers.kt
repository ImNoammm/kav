package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.stopsMatching
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Map

@Composable
fun KavField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    ime: ImeAction? = null,
    search: Boolean = false,
    floating: Boolean = false,
    // flat: no capsule of its own, for a field that is the first row of a card.
    flat: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // Opened again on an earlier search: all of it is selected, so what's typed replaces it.
            field = TextFieldValue(value, TextRange(0, value.length))
            runCatching { focus.requestFocus() }
        }
    }
    // Changed from outside (the clear button, a pick): the cursor goes to the end.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    BasicTextField(
        value = shown,
        onValueChange = { field = it; if (it.text != value) onValue(it.text) },
        singleLine = true,
        textStyle = K.type.body,
        cursorBrush = SolidColor(K.text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = ime ?: if (keyboard == KeyboardType.Text) ImeAction.Search else ImeAction.Done,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // A capsule with a quiet fill; glass where it floats over a list.
            .then(if (flat) Modifier else if (floating) Modifier.glassSurface(26.dp) else Modifier.clip(K.capsule).background(K.fill))
            .focusRequester(focus)
            .semantics { contentDescription = placeholder }
            .padding(horizontal = K.gap4, vertical = 14.dp),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (search) {
                    SearchGlyph(K.textTertiary, 20.dp)
                    Spacer(Modifier.width(K.gap2))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = K.type.body, color = K.textTertiary)
                    innerTextField()
                }
                // The same pop as the favourites' remove badge.
                androidx.compose.animation.AnimatedVisibility(
                    visible = value.isNotEmpty() && !secret,
                    enter = scaleIn(spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), initialScale = .5f) +
                        fadeIn(tween(120)),
                    exit = scaleOut(tween(140), targetScale = .5f) + fadeOut(tween(120)),
                ) {
                    Icon(
                        Icons.Rounded.Close, contentDescription = T("Clear", "ניקוי"), tint = K.dim,
                        modifier = Modifier.padding(start = K.gap2).size(20.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { onValue("") },
                    )
                }
            }
        },
    )
}

// The line under a stop's name: its platform first, which tells a station's platforms apart, then its code, as on
// its sign, and its town.
internal fun stopDetail(net: Net, stop: Int, town: Boolean = true): String = listOfNotNull(
    net.platform.getOrElse(stop) { "" }.takeIf { it.isNotEmpty() }?.let { T("Platform $it", "רציף $it") },
    net.code.getOrElse(stop) { 0 }.takeIf { it > 0 }?.let { T("Stop $it", "תחנה $it") },
    net.cityName(stop).takeIf { town && it.isNotBlank() },
).joinToString(" · ")

// A search field's magnifier, the shape of Home's.
@Composable
internal fun SearchGlyph(tint: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(tint, w * .29f, Offset(w * .40f, w * .40f), style = Stroke(w * .09f))
        drawLine(tint, Offset(w * .63f, w * .63f), Offset(w * .88f, w * .88f), w * .09f, StrokeCap.Round)
    }
}

@Composable
fun PlacePicker(
    title: String,
    here: Pair<Double, Double>?,
    allowMyLocation: Boolean,
    onMyLocation: (Pair<Double, Double>) -> Unit,
    onPick: (Transit.Place) -> Unit,
    onDismiss: () -> Unit,
    initialSetting: Favourite? = null,
    favourites: List<Favourite>,
    onSaveFavourites: (List<Favourite>) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit = {},
    net: Net? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val q = query
    var places by remember { mutableStateOf<List<Transit.Place>>(emptyList()) }
    var stations by remember { mutableStateOf<List<Transit.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(uk.noammm.kav.Prefs.recents(ctx)) }
    var setting by remember { mutableStateOf(initialSetting) }
    var editing by remember { mutableStateOf<Favourite?>(null) }
    var creating by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locateFailed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(true) }
    var stopLocating by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { shown = false; stopLocating?.invoke() } }
    fun cancelLocation() { stopLocating?.invoke(); stopLocating = null; locating = false }
    fun save(list: List<Favourite>) = onSaveFavourites(list)
    val pick: (Transit.Place) -> Unit = { p ->
        cancelLocation()
        val f = setting
        if (f != null) {
            save(favourites.map { if (it.id == f.id) it.copy(place = p) else it })
            setting = null
            onQuery("")
            if (initialSetting != null) onDismiss()
        } else {
            uk.noammm.kav.Prefs.remember(ctx, p)
            onPick(p)
        }
    }
    // A picked Google place takes the point PlacePoint gives it, a town's hub or Google's own, and the trip, Recents and
    // saved trips all keep that one point.
    val scope = rememberCoroutineScope()
    var placing by remember { mutableStateOf(false) }
    fun pickPlace(p: Transit.Place) {
        if (placing) return
        if (p.googleId == null) return pick(p)
        placing = true
        scope.launch {
            val placed = uk.noammm.kav.data.PlacePoint.of(p, net = { net ?: uk.noammm.kav.loadNet(ctx) })
            placing = false
            pick(placed)
        }
    }

    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val n = net ?: uk.noammm.kav.loadNet(ctx)
                n.stopWords; n.stopType
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(q) {
        if (q.isBlank()) {
            places = emptyList(); stations = emptyList(); busy = false
            return@LaunchedEffect
        }
        // A point copied from a map is that very place.
        uk.noammm.kav.data.TripLink.coordinates(q)?.let { (lat, lon) ->
            places = listOf(Transit.Place("%.5f, %.5f".format(java.util.Locale.US, lat, lon), T("Coordinates", "קואורדינטות"), lat, lon))
            stations = emptyList(); busy = false
            return@LaunchedEffect
        }
        busy = true
        kotlinx.coroutines.delay(280)
        val at = here
        val near = async(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val n = net ?: uk.noammm.kav.loadNet(ctx)
                n.stopsMatching(q, at) { modeName(modeOf(it)) }.map { placeOf(n, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        val address = async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                uk.noammm.kav.data.Addresses.find(q, at)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        val google = async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                uk.noammm.kav.data.GoogleMaps.suggest(q, at)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        stations = near.await()
        busy = stations.isEmpty()
        val typed = kotlinx.coroutines.withTimeoutOrNull(3000) { google.await() }
        // A typo gets no places from Google, only the text it would correct it to, so its places come from that text.
        val suggested = typed?.queries?.firstOrNull()?.takeIf { typed.places.isEmpty() }?.let { fixed ->
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { uk.noammm.kav.data.GoogleMaps.suggest(fixed, at) }.getOrNull()
                }
            }?.let { again -> uk.noammm.kav.data.GoogleMaps.Suggestions(again.places, typed.queries) }
        } ?: typed
        // A typo Google corrects ("תחנת רכת חדרה") still finds the station, under the first correction that names one.
        val fixes = suggested?.queries.orEmpty().take(3)
        if (stations.isEmpty() && fixes.isNotEmpty()) stations = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val n = net ?: uk.noammm.kav.loadNet(ctx)
                fixes.asSequence().map { fixed -> n.stopsMatching(fixed, at) { modeName(modeOf(it)) } }
                    .firstOrNull { it.isNotEmpty() }.orEmpty().map { placeOf(n, it) }
            }.getOrDefault(emptyList())
        }
        val exact = kotlinx.coroutines.withTimeoutOrNull(3000) { address.await() }.orEmpty()
        // Google's places. A suggested address only knows its street's position, so it gives way to the house itself.
        val number = uk.noammm.kav.data.searchWords(q).firstOrNull { it.first().isDigit() }
        val fromGoogle = suggested?.places.orEmpty()
            .filter { p -> number == null || uk.noammm.kav.data.searchWords(p.name).none { it == number } }
        places = (exact + fromGoogle).take(5)
        busy = false
    }

    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, here, onPick = { p -> onMap = false; pick(p) }, onDismiss = { onMap = false },
            onLocate = onLocate, allowPin = true,
        )
        return
    }
    val leave: () -> Unit = {
        cancelLocation()
        if (setting != null && initialSetting == null) setting = null else onDismiss()
    }
    androidx.activity.compose.BackHandler(onBack = leave)
    fun startLocating() {
        stopLocating?.invoke()
        locating = true; locateFailed = false
        stopLocating = requestLocationOnce(ctx, requireFresh = true, onFail = {
            if (shown) { locating = false; locateFailed = true }
        }) {
            if (!shown) return@requestLocationOnce
            onLocate(it)
            locating = false
            onMyLocation(it)
        }
    }
    val askHere = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (shown && granted.values.any { it }) startLocating() else locating = false
    }
    LaunchedEffect(here == null) {
        if (allowMyLocation && here == null && hasLocationPermission(ctx)) requestLocationOnce(ctx, onResult = onLocate)
    }
    val showRecents = q.isBlank() && recents.isNotEmpty()
    val found = q.isNotBlank() && (places.isNotEmpty() || stations.isNotEmpty())

    Box(Modifier.fillMaxSize().background(K.bg)) {
    FloatingTop(top = {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            BackButton(leave)
            KavField(q, onQuery, setting?.let { T("where is ${it.label}?", "היכן נמצא ${it.label}?") } ?: title, Modifier.weight(1f), autoFocus = true, floating = true)
        }
    }, estimate = 72.dp) { topSpace, backdrop ->
    LazyColumn(
        Modifier.fillMaxSize().then(backdrop),
        contentPadding = PaddingValues(top = topSpace, bottom = LocalBottomBarInset.current),
    ) {
        if (q.isBlank() && setting == null) item(key = "favourites") {
            FavouriteStrip(
                favourites,
                onPick = { f -> f.place?.let { pick(f.named(it)) } ?: run { setting = f } },
                onAdd = { creating = true },
                onEdit = { editing = it },
            )
        }
        setting?.let { f ->
            item(key = "setting") {
                NoteCard(
                    T(
                        "Search for where ${f.label} is. The place you pick is kept as ${f.label}.",
                        "חפשו היכן נמצא ${f.label}. המקום שתבחרו יישמר בתור ${f.label}.",
                    ),
                    Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                )
            }
        }
        // Select on map and My location: two ways to place a point, on one card.
        item(key = "map") {
            Box(Modifier.padding(horizontal = K.gap4).groupItem(first = true, last = !allowMyLocation)) {
                SelectOnMapRow(sides = 0.dp, framed = false) { cancelLocation(); onMap = true }
            }
        }
        if (allowMyLocation) item(key = "here") {
            val ready = here != null && !locating && !locateFailed
            Row(
                Modifier.fillMaxWidth().padding(horizontal = K.gap4).groupItem(first = false, last = true).heightIn(min = 48.dp)
                    .clickable(enabled = !locating, role = Role.Button) {
                        if (hasLocationPermission(ctx)) startLocating()
                        else askHere.launch(LOCATION_PERMISSIONS)
                    }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = if (ready) K.textSecondary else K.textTertiary,
                    modifier = Modifier.size(20.dp))
                Text(
                    if (locating) T("Finding you…", "מאתרים אתכם…")
                    else if (locateFailed) T("Couldn't get an accurate location. Tap to retry", "לא התקבל מיקום מדויק. לחצו לנסות שוב")
                    else if (ready) T("My location", "המיקום שלי")
                    else T("Use my location", "השתמשו במיקום שלי"),
                    style = K.type.body, color = if (ready) K.text else K.textSecondary,
                )
                if (!ready && !locating && !locateFailed && !hasLocationPermission(ctx)) Text(
                    T("Location is off", "המיקום כבוי"),
                    style = K.type.caption, modifier = Modifier.padding(start = K.gap1),
                )
            }
        }
        when {
            q.isBlank() && recents.isEmpty() -> item {
                NoteCard(T("Search a station, street or place.", "חפשו תחנה, רחוב או מקום."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            }
            q.isBlank() -> Unit
            busy && places.isEmpty() && stations.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = K.gap8), contentAlignment = Alignment.Center) {
                    LoadingPulse(T("Searching", "מחפשים"))
                }
            }
            places.isEmpty() && stations.isEmpty() -> item {
                NoteCard(T("Nothing found.", "לא נמצאו תוצאות."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            }
        }
        if (showRecents) {
            item(key = "recentHead") {
                SectionHeader(T("Recent", "אחרונים"), Modifier.padding(horizontal = K.gap4), action = T("Clear", "ניקוי"),
                    onAction = { uk.noammm.kav.Prefs.clearRecents(ctx); recents = emptyList() })
            }
            itemsIndexed(recents) { i, p -> PlaceRow(p, here, i == 0, i == recents.lastIndex) { pick(p) } }
        } else if (found) {
            if (places.isNotEmpty()) item { SectionTitle(T("Places", "מקומות")) }
            itemsIndexed(places) { i, p -> Box(Modifier.popIn(i, places)) { PlaceRow(p, here, i == 0, i == places.lastIndex) { pickPlace(p) } } }
            if (stations.isNotEmpty()) item { SectionTitle(T("Stops", "תחנות")) }
            itemsIndexed(stations) { i, p -> Box(Modifier.popIn(places.size + i, stations)) { PlaceRow(p, here, i == 0, i == stations.lastIndex) { pick(p) } } }
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
            onRemove = if (f.id == Favourite.HOME) null else { { save(favourites.filter { it.id != f.id }); editing = null } },
            onDismiss = { editing = null },
            onChangePlace = { editing = null; setting = f; onQuery("") },
        )
    }
}

// Under a search field, an action rather than a second field: no frame, its pin in the accent, its words in the
// text colour, lined up with the field's magnifier and words.
@Composable
// framed: a card of its own, as under the Stops field; off where it is a row of a card already.
internal fun SelectOnMapRow(sides: androidx.compose.ui.unit.Dp = K.gap3, framed: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = sides)
            .then(if (framed) Modifier.padding(top = K.gap2).heightIn(min = 52.dp).panel(K.rCard) else Modifier.heightIn(min = 48.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Icon(Icons.Rounded.Map, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(20.dp))
        Text(T("Select on map", "בחירה על המפה"), style = K.type.body, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// Rises in like search does, wherever it opens from.
@Composable
fun StopMapPicker(
    net: Net?,
    here: Pair<Double, Double>?,
    onPick: (Transit.Place) -> Unit,
    onDismiss: () -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit = {},
    allowPin: Boolean = false,
    onStop: ((Int) -> Unit)? = null,
) {
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 76.dp.roundToPx() }
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(shown, enter = searchIn(rise).targetContentEnter) {
        MapPickerScreen(net, here, onPick, onDismiss, onLocate, allowPin, onStop)
    }
}

@Composable
private fun MapPickerScreen(
    net: Net?,
    here: Pair<Double, Double>?,
    onPick: (Transit.Place) -> Unit,
    onDismiss: () -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit,
    allowPin: Boolean,
    onStop: ((Int) -> Unit)?,
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(here == null) {
        if (here == null && hasLocationPermission(ctx)) requestLocationOnce(ctx, onResult = onLocate)
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
            StopMapHeader(allowPin, onDismiss)
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
    StopMapBody(open, here, onPick, onDismiss, allowPin, onStop)
}

@Composable
private fun StopMapHeader(pin: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        BackButton(onDismiss)
        Box(
            Modifier.heightIn(min = 48.dp).glassSurface(24.dp).padding(horizontal = K.gap4),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pin) T("Tap a stop, or anywhere", "הקישו על תחנה, או על כל מקום") else T("Tap a stop", "הקישו על תחנה"),
                style = K.type.headline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed class MapPick {
    class Stop(val index: Int) : MapPick()
    class Point(val lat: Double, val lon: Double) : MapPick()
}

private fun nearestLabel(net: Net, lat: Double, lon: Double): String =
    net.nearestStops(lat, lon, k = 1, radius = 400.0).firstOrNull()?.let { (i, d) ->
        T("near ", "ליד ") + net.stopName(i) + " · " + distanceLabel(d)
    } ?: ""

@Composable
private fun StopMapBody(
    net: Net,
    here: Pair<Double, Double>?,
    onPick: (Transit.Place) -> Unit,
    onDismiss: () -> Unit,
    allowPin: Boolean,
    onStop: ((Int) -> Unit)?,
) {
    val centre = here ?: (32.0759 to 34.7745)
    val stops = remember(net, centre) {
        net.nearestStops(centre.first, centre.second, k = 500, radius = 6000.0).map { it.first }
    }
    var chosen by remember { mutableStateOf<MapPick?>(null) }
    val reach = with(LocalDensity.current) { 26.dp.toPx() }
    val points = remember(stops, centre) { listOf(centre) + stops.take(12).map { net.lat[it] to net.lon[it] } }
    val geometry = remember(stops, chosen, here, K.light) {
        val pick = chosen
        MapGeometry(
            dots = stops.flatMap { i ->
                val on = pick is MapPick.Stop && pick.index == i
                listOf(
                    MapDot(net.lat[i], net.lon[i], K.bg, if (on) 8f else 6f),
                    MapDot(
                        net.lat[i], net.lon[i], androidx.compose.ui.graphics.Color.Transparent,
                        if (on) 6f else 4.2f, if (on) K.accent else K.muted, if (on) 2.4f else 1.6f,
                    ),
                )
            } + (
                (pick as? MapPick.Point)?.let { p ->
                    listOf(MapDot(p.lat, p.lon, K.bg, 9f), MapDot(p.lat, p.lon, K.accent, 6f))
                } ?: emptyList()
                ) + (
                here?.let { (lat, lon) ->
                    listOf(
                        MapDot(lat, lon, K.live.copy(alpha = .18f), 13f, id = "here-halo"),
                        MapDot(lat, lon, K.bg, 6f, id = "here-ring"),
                        MapDot(lat, lon, K.live, 4f, id = "here-dot"),
                    )
                } ?: emptyList()
                ),
        )
    }
    val liquid = rememberLiquidBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(Modifier.fillMaxSize().background(K.bg)) {
            TileMap(
                points,
                Modifier.fillMaxSize().glassBackdrop(liquid),
                contentPadding = PaddingValues(top = 72.dp),
                recenterOn = here,
                geometry = geometry,
                onTap = { at, proj ->
                    val stop = stops
                        .map { it to (proj.point(net.lat[it], net.lon[it]) - at).getDistance() }
                        .filter { it.second <= reach }.minByOrNull { it.second }?.first
                    chosen = when {
                        stop != null -> MapPick.Stop(stop)
                        allowPin -> proj.latLon(at).let { MapPick.Point(it.first, it.second) }
                        else -> null
                    }
                },
            )
            StopMapHeader(allowPin, onDismiss)
            var last by remember { mutableStateOf<MapPick?>(null) }
            LaunchedEffect(chosen) { chosen?.let { last = it } }
            androidx.compose.animation.AnimatedVisibility(
                visible = chosen != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)) { it } +
                    fadeIn(tween(140)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                last?.let { pick ->
                val stopAt = (pick as? MapPick.Stop)?.index
                val detail = if (stopAt != null) listOfNotNull(
                    net.cityName(stopAt).takeIf { it.isNotBlank() },
                    net.code.getOrElse(stopAt) { 0 }.takeIf { it > 0 }?.toString(),
                ).joinToString(" · ")
                else (pick as MapPick.Point).let { nearestLabel(net, it.lat, it.lon) }
                Column(
                    Modifier.fillMaxWidth()
                        .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                        .padding(K.gap3)
                        .glassSurface(K.rCard).padding(K.gap4),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(
                        if (stopAt != null) (if (stopAt in net.name.indices) net.stopName(stopAt) else T("Stop", "תחנה"))
                        else T("Pinned location", "מיקום מסומן"),
                        style = K.type.headline, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) Text(detail, fontSize = 14.sp, color = K.dim, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        KavButton(
                            T("Not this one", "לא זו"), { chosen = null }, Modifier.heightIn(min = 52.dp),
                            style = ButtonStyle.Secondary,
                        )
                        Spacer(Modifier.weight(1f))
                        KavButton(if (stopAt != null) T("Choose this stop", "בחירת התחנה") else T("Go here", "לכאן"), {
                            when (pick) {
                                is MapPick.Stop ->
                                    if (onStop != null) onStop(pick.index) else onPick(placeOf(net, pick.index))
                                is MapPick.Point ->
                                    onPick(Transit.Place(
                                        T("Pinned location", "מיקום מסומן"),
                                        nearestLabel(net, pick.lat, pick.lon), pick.lat, pick.lon,
                                    ))
                            }
                        })
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = SectionHeader(text, Modifier.padding(horizontal = K.gap4))

@Composable
private fun PlaceRow(p: Transit.Place, here: Pair<Double, Double>?, first: Boolean, last: Boolean, onClick: () -> Unit) {
    // Measured on the phone, from where you are.
    val away = here?.let { metres(it.first, it.second, p.lat, p.lon) }?.takeIf { !it.isNaN() }
    ListRow(
        p.name,
        Modifier.padding(horizontal = K.gap4).groupItem(first, last, inset = K.gap4 + 24.dp + K.gap3),
        subtitle = p.detail.takeIf { it.isNotBlank() },
        value = away?.let { distanceLabel(it) },
        leading = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { PlaceGlyph(p.type, p.mode) } },
        onClick = onClick,
        chevron = false,
    )
}

@Composable
private fun PlaceGlyph(type: Int, mode: Int) {
    when (type) {
        1 -> ModeGlyph(if (mode >= 0) modeOf(mode) else Mode.BUS, K.muted, 17.dp)
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
