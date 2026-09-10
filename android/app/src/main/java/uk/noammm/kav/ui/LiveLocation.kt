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
import androidx.compose.ui.graphics.PathEffect
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
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vehicle position and service status from StopsArrivals, drawn over the journey leg. */

private val hm = SimpleDateFormat("HH:mm", Locale.US)

/** Show minutes for an upcoming arrival within the hour, otherwise its clock time. */
fun whenLabel(t: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val m = ((t - now) / 60).toInt()
    return when {
        m < 0 -> hm.format(Date(t * 1000))
        m == 0 -> "now"
        m < 60 -> "in $m min"
        else -> hm.format(Date(t * 1000))
    }
}

/** Stop names for a whole ride, resolved once through the shared API cache. */
@Composable
fun rememberStopNames(ids: List<Int>): Map<Int, Moovit.StopInfo> {
    val wanted = ids.filter { it > 0 }.distinct()
    val key = wanted.sorted().joinToString(",")
    var out by remember(key) { mutableStateOf(emptyMap<Int, Moovit.StopInfo>()) }
    LaunchedEffect(key) {
        if (wanted.isEmpty()) return@LaunchedEffect
        val s = Online.session ?: return@LaunchedEffect
        // Publish small parallel batches so circles and stop names appear progressively.
        for (batch in wanted.chunked(6)) {
            val resolved = coroutineScope {
                batch.map { id -> async(Dispatchers.IO) {
                    Moovit.stopInfo(s, id)?.let { id to it }
                } }.awaitAll().filterNotNull().toMap()
            }
            out = out + resolved
        }
    }
    return out
}

@Composable
fun rememberLineRoute(shapeId: Int): List<Pair<Double, Double>> =
    rememberLineRoutes(listOf(shapeId))[shapeId].orEmpty()

/**
 * Several routes at once, keyed on the ids themselves so the number of ids can change
 * between recompositions without breaking Compose's call ordering.
 */
@Composable
fun rememberLineRoutes(shapeIds: List<Int>): Map<Int, List<Pair<Double, Double>>> {
    val wanted = shapeIds.filter { it > 0 }.distinct().sorted()
    val key = wanted.joinToString(",")
    var out by remember(key) { mutableStateOf(emptyMap<Int, List<Pair<Double, Double>>>()) }
    LaunchedEffect(key) {
        if (wanted.isEmpty()) return@LaunchedEffect
        val s = Online.session ?: return@LaunchedEffect
        out = withContext(Dispatchers.IO) {
            wanted.associateWith { runCatching { Moovit.tripShape(s, it) }.getOrDefault(emptyList()) }
                .filterValues { it.isNotEmpty() }
        }
    }
    return out
}

/** Tracking is available only when the caller has a vehicle position and route. */
@Composable
fun LiveLocationButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).glassSurface(22.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveGlyph(if (enabled) K.live else K.dim, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "Live location", fontSize = 14.sp,
            color = if (enabled) K.text else K.dim,
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
    // the overlay is polled by the caller; read it fresh on every recomposition
    val arrival = r.arrival(leg)
    val info = r.line(leg.lineId)
    val now = System.currentTimeMillis() / 1000
    val fetched = rememberLineRoute(arrival?.tripShapeId ?: -1)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader("Live", "location", back = onBack)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val agency = info?.agencyId ?: -1
            val rt = if (info != null) r.routeType(agency) else 3
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(K.surface1)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, K.muted, 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    leg.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "#${leg.lineId}",
                    fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
            Spacer(Modifier.width(K.gap3))
            Text(
                info?.destination?.ifBlank { null }?.let { "to $it" } ?: "",
                fontSize = 14.sp, color = K.muted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }

        VehicleMap(
            leg, arrival,
            approach = lineRoute(arrival, r).ifEmpty { fetched },
            modifier = Modifier.padding(horizontal = K.gap3),
        )

        Spacer(Modifier.height(K.gap3))
        StatusBlock(leg, boardStopId, arrival, r, now)
    }
}

/**
 * The whole route the vehicle is driving, its MVArrival.tripShapeId resolved through
 * V5/Entities/Entity (entity_type 15, MVSyncedEntity.mvTripShape). Drawn grey under
 * your own leg, because most of it is the line's journey rather than your trip: what
 * it did before it reached you, and where it carries on after you get off.
 */
fun lineRoute(a: Moovit.Arrival?, r: Moovit.Resolved): List<Pair<Double, Double>> {
    if (a == null || a.tripShapeId <= 0) return emptyList()
    return r.shapes[a.tripShapeId] ?: Moovit.cachedShape(a.tripShapeId)
}

/** The line's shape with the vehicle on it, the picture the feature exists for. */
@Composable
private fun VehicleMap(
    leg: Moovit.Leg,
    a: Moovit.Arrival?,
    approach: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (leg.shape.size < 2) return
    val pulse = rememberLivePulse()
    val points = leg.shape + approach +
        listOfNotNull(a?.takeIf { it.hasLocation }?.let { it.lat to it.lon })
    // The line's whole route goes down first, not your trip, so it is grey, and
    // your own leg sits on top of it, all drawn by MapLibre in the ground's frame.
    val geometry = remember(approach, leg) {
        MapGeometry(
            lines = listOf(
                MapLine(approach, K.routeIdle, 3f, casing = 7f),
                // The journey leg shares the trip map's subdued route accent.
                MapLine(leg.shape, K.route, 4f, casing = 8f),
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
        modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(K.rCard)).background(K.surface1),
        geometry = geometry,
        animatedOverlay = { proj ->
            if (a != null && a.hasLocation) {
                val p = proj.point(a.lat, a.lon)
                // OUT_OF_SHAPE means the vehicle has left its planned route; show the gap
                // rather than snapping it onto the line and pretending otherwise.
                if (a.vehicleStatus == 2) {
                    var nearest = proj.point(leg.shape[0].first, leg.shape[0].second)
                    var best = Float.MAX_VALUE
                    for ((lat, lon) in leg.shape) {
                        val q = proj.point(lat, lon)
                        val d = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
                        if (d < best) { best = d; nearest = q }
                    }
                    drawLine(
                        K.problem, nearest, p, 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                }
                val tint = if (a.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f), 18.dp.toPx() * pulse.value, p)
                drawCircle(K.bg, 9.dp.toPx(), p)
                drawCircle(tint, 6.dp.toPx(), p)
            }
        },
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
            .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
    ) {
        val (headline, tint) = when {
            a == null -> "This line doesn’t have a live location" to K.dim
            a.status == 3 -> "Canceled for this station" to K.critical
            a.vehicleStatus == 3 -> "Line not departed yet" to K.dim
            !a.hasLocation -> "This line doesn’t have a live location" to K.dim
            a.vehicleStatus == 2 -> "Out of route" to K.problem
            now - a.sampleUtc <= 120 -> "Location updated recently" to K.live
            else -> "Location is estimated" to K.problem
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a?.hasLocation == true) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
        }
        if (a != null && a.vehicleStatus == 2) {
            Text(
                "This line has deviated from its planned route",
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }
        if (a != null && a.sampleUtc > 0) {
            Text(
                "Location updated: ${hm.format(Date(a.sampleUtc * 1000))}",
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }

        Spacer(Modifier.height(K.gap4))
        val nextStop = nextStopOnLeg(leg, a)
        if (nextStop != null) {
            Fact("Next stop", r.stopName(nextStop) ?: "#$nextStop")
        }
        val away = a?.stopsAway ?: -1
        if (away >= 0) Fact(if (away == 1) "1 stop away" else "Stops away", if (away == 1) "" else "$away")
        r.stopName(boardStopId)?.let { Fact("Your stop", it) }
        if (a != null && a.rtUtc > 0) {
            Fact("Arriving", whenLabel(a.rtUtc, now))
        }
    }
}

/** Arrival indexes refer to the whole line; a journey leg begins at the boarding stop. */
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
