package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net

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

/** One stop in a list: name, then city and whatever second fact the caller has. */
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
                net.name[stop], fontSize = 15.sp, color = K.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val city = net.cityOf(stop)
            if (city.isNotBlank()) {
                Text(city, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 */
@Composable
fun PlacePicker(
    title: String,
    here: Pair<Double, Double>?,
    allowMyLocation: Boolean,
    onMyLocation: () -> Unit,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(uk.noammm.kav.Prefs.recents(ctx)) }
    val pick: (Moovit.Place) -> Unit = { p ->
        uk.noammm.kav.Prefs.remember(ctx, p)
        onPick(p)
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

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(K.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            BackButton(onDismiss)
            KavField(q, { q = it }, title, Modifier.weight(1f), autoFocus = true)
        }
        if (allowMyLocation) Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3).heightIn(min = 48.dp)
                .glassSurface(24.dp).clickable(role = Role.Button, onClick = onMyLocation)
                .padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(K.live))
            Text("My location", fontSize = 15.sp, color = K.live)
        }
        when {
            error != null -> Note("Search failed: $error", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() && recents.isEmpty() ->
                Note("Search a station, street or place.", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() -> Unit
            busy && results.isEmpty() -> Note("Searching…", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            results.isEmpty() -> Note("Nothing found.", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        val showRecents = q.isBlank() && recents.isNotEmpty()
        if (results.isNotEmpty() || showRecents) {
            Row(
                Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap3, top = K.gap2, bottom = K.gap1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showRecents) "Recent" else "Places",
                    fontSize = 14.sp, color = K.dim, modifier = Modifier.weight(1f),
                )
                if (showRecents) Chip("Clear", false) {
                    uk.noammm.kav.Prefs.clearRecents(ctx); recents = emptyList()
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(if (showRecents) recents else results) { p -> PlaceRow(p) { pick(p) } }
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
