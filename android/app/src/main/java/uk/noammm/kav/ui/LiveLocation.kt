package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import java.util.Date

private val hm get() = clockFormat()

fun whenLabel(t: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val m = ((t - now) / 60).toInt()
    return when {
        m < 0 -> hm.format(Date(t * 1000))
        m == 0 -> T("now", "עכשיו")
        m < 60 -> T("in $m min", "בעוד $m דק׳")
        else -> hm.format(Date(t * 1000))
    }
}

private suspend fun onlineSession(): MoovitSession? = runCatching { Online.open() }.getOrNull()

// Moovit names stops one lookup at a time, and on a weak connection some fail: those are asked again, less and less
// often, for as long as they're on screen.
@Composable
fun rememberStopNames(ids: List<Int>): Map<Int, Moovit.StopInfo> {
    val wanted = ids.filter { it > 0 }.distinct()
    val key = wanted.sorted().joinToString(",")
    var out by remember(key) { mutableStateOf(emptyMap<Int, Moovit.StopInfo>()) }
    LaunchedEffect(key) {
        var wait = 2_000L
        while (true) {
            val missing = wanted.filter { it !in out }
            if (missing.isEmpty()) break
            onlineSession()?.let { s ->
                for (batch in missing.chunked(6)) {
                    val resolved = coroutineScope {
                        batch.map { id -> async(Dispatchers.IO) {
                            runCatching { Moovit.stopInfo(s, id) }.getOrNull()?.let { id to it }
                        } }.awaitAll().filterNotNull().toMap()
                    }
                    out = out + resolved
                }
            }
            if (wanted.all { it in out }) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }
    return out
}

@Composable
fun rememberLineRoute(shapeId: Int): List<Pair<Double, Double>> =
    rememberLineRoutes(listOf(shapeId))[shapeId].orEmpty()

@Composable
fun rememberLineRoutes(shapeIds: List<Int>): Map<Int, List<Pair<Double, Double>>> {
    val wanted = shapeIds.filter { it > 0 }.distinct().sorted()
    val key = wanted.joinToString(",")
    var out by remember(key) { mutableStateOf(emptyMap<Int, List<Pair<Double, Double>>>()) }
    LaunchedEffect(key) {
        if (wanted.isEmpty()) return@LaunchedEffect
        val s = onlineSession() ?: return@LaunchedEffect
        out = withContext(Dispatchers.IO) {
            wanted.associateWith { runCatching { Moovit.tripShape(s, it) }.getOrDefault(emptyList()) }
                .filterValues { it.isNotEmpty() }
        }
    }
    return out
}

@Composable
fun LiveLocationButton(live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).glassSurface(K.rControl)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveGlyph(if (live) K.realtime else K.dim, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            T("Live location", "מיקום בזמן אמת"), fontSize = 14.sp,
            color = if (live) K.text else K.dim,
        )
    }
}

@Composable
fun LiveLocationScreen(
    leg: Moovit.Leg,
    boardStopId: Int,
    r: Moovit.Resolved,
    onBack: () -> Unit,
) {
    val arrival = r.arrival(leg)
    val info = r.line(leg.lineId)
    val now = System.currentTimeMillis() / 1000
    val fetched = rememberLineRoute(arrival?.tripShapeId ?: -1)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Live", "מיקום בזמן אמת"), "", back = onBack)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val agency = info?.agencyId ?: -1
            val rt = if (info != null) r.routeType(agency) else 3
            val plate = plateFor(rt, agency)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(plate?.fill ?: K.surface1)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    leg.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "#${leg.lineId}",
                    fontSize = 15.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") } ?: "",
                    fontSize = 14.sp, color = K.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                r.agencyName(agency)?.let {
                    Text(it, fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        VehicleMap(
            leg, arrival, modeOf(if (info != null) r.routeType(info.agencyId) else 3),
            approach = lineRoute(arrival, r).ifEmpty { fetched },
            modifier = Modifier.padding(horizontal = K.gap3),
        )

        Spacer(Modifier.height(K.gap3))
        StatusBlock(leg, boardStopId, arrival, r, now)
    }
}

fun lineRoute(a: Moovit.Arrival?, r: Moovit.Resolved): List<Pair<Double, Double>> {
    if (a == null || a.tripShapeId <= 0) return emptyList()
    return r.shapes[a.tripShapeId] ?: Moovit.cachedShape(a.tripShapeId)
}

@Composable
private fun VehicleMap(
    leg: Moovit.Leg,
    a: Moovit.Arrival?,
    mode: Mode,
    approach: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (leg.shape.size < 2) return
    val bus = a?.takeIf { it.hasLocation }
    val points = leg.shape + approach + listOfNotNull(bus?.let { it.lat to it.lon })
    val geometry = remember(approach, leg, K.look, bus?.lat, bus?.lon, bus?.vehicleStatus) {
        // Off its route, a dashed line ties the bus to the nearest point of the route.
        val astray = bus?.takeIf { it.vehicleStatus == 2 }?.let { b ->
            val near = leg.shape.minBy { (lat, lon) -> metres(lat, lon, b.lat, b.lon) }
            MapLine(listOf(near, b.lat to b.lon), K.problem, 1.5f, dashed = true)
        }
        MapGeometry(
            lines = listOfNotNull(
                MapLine(approach, K.routeIdle, 3f, casing = 7f),
                MapLine(leg.shape, K.route, 4f, casing = 8f),
                astray,
            ),
            dots = listOf(
                MapDot(leg.shape.first().first, leg.shape.first().second, K.bg, 6f),
                MapDot(leg.shape.first().first, leg.shape.first().second, Color.Transparent, 5f, K.text, 2f),
                MapDot(leg.shape.last().first, leg.shape.last().second, K.bg, 7f),
                MapDot(leg.shape.last().first, leg.shape.last().second, K.text, 5f),
            ),
        )
    }
    TileMap(
        points,
        modifier.fillMaxWidth().height(260.dp).panel(K.rCard),
        geometry = geometry,
        live = vehicleGeometry(listOfNotNull(bus?.let { it to mode }), LocalDensity.current),
    )
}

@Composable
private fun StatusBlock(
    leg: Moovit.Leg,
    boardStopId: Int,
    a: Moovit.Arrival?,
    r: Moovit.Resolved,
    now: Long,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .panel(K.rCard).padding(K.gap4),
    ) {
        val lineLive = r.liveFor(leg).any { it.hasLocation }
        val (headline, tint) = when {
            a?.status == 3 -> T("Canceled for this station", "מבוטל עבור תחנה זו") to K.critical
            a?.vehicleStatus == 3 -> T("Line not departed yet", "הקו טרם יצא") to K.dim
            (a == null || !a.hasLocation) && lineLive -> T("Your bus hasn't set out yet", "האוטובוס שלכם עוד לא יצא לדרך") to K.dim
            a == null || !a.hasLocation -> T("This line doesn’t have a live location", "לקו הזה אין מיקום בזמן אמת") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            now - a.sampleUtc <= 120 -> T("Location updated recently", "המיקום עודכן לאחרונה") to K.realtime
            else -> T("Location is estimated", "המיקום משוער") to K.problem
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a?.hasLocation == true) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
        }
        if (a != null && a.vehicleStatus == 2) {
            Text(
                T("This line has deviated from its planned route", "הקו הזה סטה מהמסלול המתוכנן"),
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }
        if (a != null && a.sampleUtc > 0) {
            Text(
                T("Location updated: ${hm.format(Date(a.sampleUtc * 1000))}", "המיקום עודכן: ${hm.format(Date(a.sampleUtc * 1000))}"),
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }

        Spacer(Modifier.height(K.gap4))
        val nextStop = nextStopOnLeg(leg, a)
        // The trip only carries the names of the stops it boards and leaves at.
        val asked = rememberStopNames(listOfNotNull(nextStop?.takeIf { r.stopName(it) == null }))
        if (nextStop != null) {
            Fact(T("Next stop", "התחנה הבאה"), r.stopName(nextStop) ?: asked[nextStop]?.name ?: "…")
        }
        val away = a?.stopsAway ?: -1
        if (away >= 0) Fact(if (away == 1) T("1 stop away", "תחנה אחת") else T("Stops away", "תחנות"), if (away == 1) "" else "$away")
        r.stopName(boardStopId)?.let { Fact(T("Your stop", "התחנה שלכם"), it) }
        if (a != null && a.rtUtc > 0) {
            Fact(T("Arriving", "הגעה"), whenLabel(a.rtUtc, now))
        }
    }
}

internal fun nextStopOnLeg(leg: Moovit.Leg, arrival: Moovit.Arrival?): Int? {
    if (arrival == null || arrival.nextStopIndex < 0 || arrival.stopIndex < 0) return null
    return leg.stops.getOrNull(arrival.nextStopIndex - arrival.stopIndex)
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(112.dp))
        Text(
            value, fontSize = 14.sp, color = K.text, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}
