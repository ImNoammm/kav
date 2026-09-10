package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.noammm.kav.data.Moovit

/**
 * The route on a map, with the vehicles that are on their way to it.
 *
 * The geometry is the plan's own: every leg carries an encoded polyline in
 * MVTripPlanShape, decoded by Moovit.decodePolyline. Under it are Esri's dark tiles
 * (ui/TileMap.kt) rather than Google's, because Moovit's map is the Google Maps SDK
 * driven by Moovit's own key and neither can ship here. Walking legs are a dotted
 * hairline, vehicle legs a solid rule, and the ends are the ring and dot the timeline
 * uses, so the map and the steps below it read as the same drawing. A ride whose
 * vehicle is tracked gets its dot too, the caller keeps the arrivals polled.
 */
@Composable
fun RouteMap(trip: Moovit.Itinerary, r: Moovit.Resolved = Moovit.Resolved(), height: Dp = 190.dp, modifier: Modifier = Modifier) {
    val legs = trip.legs.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val points = legs.flatMap { it.shape }
    val vehicles = trip.rides.flatMap { it.options }.mapNotNull { r.arrival(it)?.takeIf { a -> a.hasLocation } }
    val pulse = rememberLivePulse()
    val vehicleAlpha = animateFloatAsState(if (vehicles.isEmpty()) 0f else 1f, tween(350), label = "vehicleReveal")

    // the ride is yours, so it takes the accent; walking is context, and it is all
    // MapLibre's to draw, in the same frame as the streets it lies on
    val geometry = remember(legs) {
        MapGeometry(
            lines = legs.filter { it.kind == Moovit.LegKind.WALK }
                .map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                legs.filter { it.kind != Moovit.LegKind.WALK }
                    .map { MapLine(it.shape, K.route, 4f, casing = 7f) },
            dots = listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 6f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.text, 5f) },
            ) + legs.filter { it.kind != Moovit.LegKind.WALK }.flatMap { l ->
                // where you change vehicle
                l.shape.firstOrNull()?.let { (lat, lon) ->
                    listOf(MapDot(lat, lon, K.bg, 5f), MapDot(lat, lon, K.text, 3f))
                }.orEmpty()
            },
        )
    }

    TileMap(
        points,
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(K.surface1),
        geometry = geometry,
        animatedOverlay = { proj ->
            for (v in vehicles) {
                val p = proj.point(v.lat, v.lon)
                val tint = if (v.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f * vehicleAlpha.value), 15.dp.toPx() * pulse.value, p)
                drawCircle(K.bg.copy(alpha = vehicleAlpha.value), 8.dp.toPx(), p)
                drawCircle(tint.copy(alpha = vehicleAlpha.value), 5.dp.toPx(), p)
            }
        },
    )
}
