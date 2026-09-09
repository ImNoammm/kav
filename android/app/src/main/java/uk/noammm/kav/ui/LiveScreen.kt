package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.LiveVehicle
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

/** Process-wide Moovit online session + cached stop DB (register/download once). */
object Online {
    @Volatile var session: MoovitSession? = null
    @Volatile var stops: List<Moovit.Stop> = emptyList()
}

/**
 * Live vehicles on a monochrome map. This is ONLINE mode: it registers a throwaway
 * Moovit identity and polls Moovit's servers (~every 20 s, the cadence Moovit itself
 * uses). Every vehicle dot is a real GPS fix. The banner states the trade honestly.
 */
@Composable
fun LiveScreen(model: KavModel) {
    val ctx = LocalContext.current
    val here = model.here ?: (32.0759 to 34.7745)   // fallback: central Tel Aviv until located
    val located = model.here != null
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) requestLocationOnce(ctx) { model.here = it }
    }
    // Ask for location the first time the Live tab is opened, so we can find the
    // stops actually near you (falls back to central Tel Aviv until then).
    LaunchedEffect(Unit) {
        if (model.here == null) {
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
            else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var status by remember { mutableStateOf("connecting to Moovit…") }
    var vehicles by remember { mutableStateOf<List<LiveVehicle>>(emptyList()) }
    var near by remember { mutableStateOf<List<Moovit.Stop>>(emptyList()) }
    var pollSecs by remember { mutableIntStateOf(20) }

    LaunchedEffect(here) {
        try {
            val s = Online.session ?: withContext(Dispatchers.IO) { Moovit.register(here.first, here.second) }
                .also { Online.session = it }
            if (Online.stops.isEmpty()) {
                status = "loading stops…"
                Online.stops = withContext(Dispatchers.IO) { Moovit.stopDatabase(s, maxPages = 40) }
            }
            near = withContext(Dispatchers.Default) { Moovit.nearbyStops(Online.stops, here.first, here.second, k = 20) }
            val ids = near.map { it.id }
            while (coroutineContext.isActive) {
                try {
                    val (vs, poll) = withContext(Dispatchers.IO) { Moovit.liveVehicles(s, ids) }
                    vehicles = vs; pollSecs = poll.coerceIn(10, 60)
                    status = if (vs.isEmpty()) "No tracked vehicles right now" else "${vs.size} live vehicle${if (vs.size == 1) "" else "s"}"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Could not refresh · retrying shortly"
                }
                delay(pollSecs * 1000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "online error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Live", "map", onSettings = { model.settingsOpen = true })
        Text(
            "Online mode: positions come from Moovit's servers, refreshed every ${pollSecs}s.",
            fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
        )
        if (!located) Row(
            Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap2),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("Use my location", false) {
                if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
                else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            Text("showing central Tel Aviv", style = DisplayItalic, fontSize = 12.sp, color = K.dim)
        }
        Box(
            Modifier.padding(horizontal = K.gap3).fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(14.dp)).background(K.surface1),
        ) {
            LiveMap(here, near, vehicles)
            Text(
                status, style = Mono, fontSize = 11.sp, color = K.muted,
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(K.gap2).clip(RoundedCornerShape(6.dp)).background(K.glassPlate).padding(6.dp),
            )
        }
        LiveList(vehicles, Modifier.fillMaxWidth()
            .heightIn(max = 220.dp + LocalBottomBarInset.current).padding(K.gap2))
    }
}

private val K.glassPlate get() = androidx.compose.ui.graphics.Color(0xCC000000)

/**
 * The live map: real tiles, the stops around you as circles, and every tracked vehicle.
 *
 * This used to be a hand-rolled equirectangular canvas with no basemap at all, which is
 * why the tab read as a grey rectangle, there was nothing under the dots to be a map.
 * It now shares the same tiled projection as every other map in the app.
 */
@Composable
private fun LiveMap(
    center: Pair<Double, Double>,
    stops: List<Moovit.Stop>,
    vehicles: List<LiveVehicle>,
) {
    val pulse = rememberLivePulse()
    // keep the frame steady while vehicles move: fit the stops and you, not the traffic
    val points = remember(stops, center) { stops.map { it.lat to it.lon } + center }
    TileMap(points, Modifier.fillMaxSize(), animatedOverlay = { proj ->
        // tracked vehicles, breathing
        for (v in vehicles) {
            val o = proj.point(v.lat, v.lon)
            drawCircle(K.live.copy(alpha = 0.20f), 15.dp.toPx() * pulse.value, o)
            drawCircle(K.bg, 8.dp.toPx(), o)
            drawCircle(K.live, 5.dp.toPx(), o)
        }
    }) { proj ->
        // stops as circles, the way Moovit rings them on its own map
        for (st in stops) {
            val p = proj.point(st.lat, st.lon)
            drawCircle(K.bg, 6.dp.toPx(), p)
            drawCircle(K.muted, 5.dp.toPx(), p, style = Stroke(1.6.dp.toPx()))
        }
        // you
        val me = proj.point(center.first, center.second)
        drawCircle(K.text.copy(alpha = 0.18f), 13.dp.toPx(), me)
        drawCircle(K.bg, 6.dp.toPx(), me)
        drawCircle(K.text, 4.dp.toPx(), me)
    }
}

@Composable
private fun LiveList(vehicles: List<LiveVehicle>, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(
        start = K.gap1, top = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
    )) {
        items(vehicles.size) { i ->
            val v = vehicles[i]
            val ageS = ((System.currentTimeMillis() / 1000) - v.sampleUtc).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(K.live))
                Text("line ${v.lineId}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.text)
                Text("%.5f, %.5f".format(v.lat, v.lon), style = Mono, fontSize = 11.sp, color = K.dim, modifier = Modifier.weight(1f))
                Text("${ageS}s ago", style = Mono, fontSize = 11.sp, color = K.live)
            }
        }
    }
}
