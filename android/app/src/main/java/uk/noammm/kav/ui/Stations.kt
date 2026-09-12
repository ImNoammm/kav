package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Net
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
    var q by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { locating = true; requestLocationOnce(ctx) { model.here = it; locating = false } }
    }

    var hits by remember(net) { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(net, q) {
        hits = withContext(Dispatchers.Default) { net.searchStops(q).toList() }
    }
    val here = model.here
    var near by remember(net) { mutableStateOf(emptyList<Pair<Int, Double>>()) }
    LaunchedEffect(net, here) {
        near = withContext(Dispatchers.Default) {
            if (here == null) emptyList() else net.nearestStops(here.first, here.second)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Find a", "stop", onSettings = { model.settingsOpen = true })
        KavField(q, { q = it }, "search stops…", Modifier.padding(horizontal = K.gap3).fillMaxWidth())
        Spacer(Modifier.height(K.gap3))

        if (q.isNotBlank()) {
            if (hits.isEmpty()) {
                Note("Nothing matches that.", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
            )) {
                items(hits, key = { it }) { s -> StopRow(net, s) { model.stationStop = s } }
            }
        } else {
            Sig("Nearby", "stops", Modifier.padding(horizontal = K.gap4, vertical = K.gap1))
            when {
                here == null -> Column(Modifier.padding(horizontal = K.gap4, vertical = K.gap3)) {
                    Note(if (locating) "Waiting for a fix…" else "Kav does not know where you are yet.")
                    Spacer(Modifier.height(K.gap3))
                    Chip(if (locating) "Locating…" else "Use my location", locating) {
                        if (hasLocationPermission(ctx)) {
                            locating = true
                            requestLocationOnce(ctx) { model.here = it; locating = false }
                        } else {
                            ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                    Spacer(Modifier.height(K.gap3))
                    Text(
                        "Coarse location, read once, used only to sort this list. " +
                            "It is never stored and never leaves the phone.",
                        fontSize = 11.sp, color = K.dim, lineHeight = 16.sp,
                    )
                }
                near.isEmpty() -> Note(
                    "No stops within 2.5 km of you.",
                    Modifier.padding(horizontal = K.gap4, vertical = K.gap4),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                    start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
                )) {
                    items(near, key = { it.first }) { (s, d) ->
                        StopRow(net, s, distanceLabel(d)) { model.stationStop = s }
                    }
                }
            }
        }
    }
}

/** Next departures at one stop. Grey, because Israel publishes no real-time feed
 *  and a fake live colour would be a lie. */
@Composable
private fun DepartureBoard(model: KavModel, net: Net, stop: Int, onBack: () -> Unit) {
    val t0 = remember(stop) { nowSec() }
    val rows = remember(stop, t0) {
        val out = ArrayList<Int>(60)
        var i = net.dStart[stop]
        while (i < net.dStart[stop + 1] && out.size < 60) {
            val c = net.dConn[i]
            if (net.stDep[net.cST[c]] >= t0) out.add(c)
            i++
        }
        out
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader("Next", "departures", back = onBack)
        Column(Modifier.padding(horizontal = K.gap4)) {
            Text(net.stops[stop].name, fontSize = 14.sp, color = K.text)
            val prefix = listOfNotNull(net.cityOf(stop).takeIf { it.isNotBlank() }, net.stopCode(stop)).joinToString(" · ")
            Text(
                if (prefix.isNotBlank()) "$prefix · scheduled times, no live feed available"
                else "scheduled times, no live feed available",
                fontSize = 11.sp, color = K.dim,
            )
            Spacer(Modifier.height(K.gap3))
            Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Chip("Start here", false) {
                    model.pendingFrom = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }
                Chip("End here", false) {
                    model.pendingTo = placeOf(net, stop); model.tab = uk.noammm.kav.Tab.Directions
                }
            }
        }
        Spacer(Modifier.height(K.gap3))
        if (rows.isEmpty()) {
            Note("Nothing more today.", Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(rows) { c ->
                val t = net.cTrip[c]
                val dep = net.stDep[net.cST[c]]
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            net.stops[last].name, fontSize = 13.sp, color = K.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        net.stopCode(last)?.let { Text(it, fontSize = 11.sp, color = K.dim, maxLines = 1) }
                    }
                    Text(relative(dep, t0) ?: hhmm(dep), style = Mono, color = K.scheduled)
                }
            }
        }
    }
}

/** An offline stop as a place Directions can actually plan with. */
private fun placeOf(net: Net, stop: Int) = uk.noammm.kav.data.Moovit.Place(
    name = net.stops.getOrNull(stop)?.name ?: "Stop",
    detail = net.cityOf(stop),
    lat = net.stops[stop].lat,
    lon = net.stops[stop].lon,
    type = 1,
)
