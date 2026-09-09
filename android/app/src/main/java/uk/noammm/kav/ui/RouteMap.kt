package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

    TileMap(
        points,
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(K.surface1),
        animatedOverlay = { proj ->
            for (v in vehicles) {
                val p = proj.point(v.lat, v.lon)
                val tint = if (v.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f * vehicleAlpha.value), 15.dp.toPx() * pulse.value, p)
                drawCircle(K.bg.copy(alpha = vehicleAlpha.value), 8.dp.toPx(), p)
                drawCircle(tint.copy(alpha = vehicleAlpha.value), 5.dp.toPx(), p)
            }
        },
    ) { proj ->
        fun draw(l: Moovit.Leg) {
            val path = Path()
            l.shape.forEachIndexed { i, (lat, lon) ->
                val p = proj.point(lat, lon)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val walking = l.kind == Moovit.LegKind.WALK
            // a dark casing under the ride, so the route stays legible over streets
            if (!walking) drawPath(
                path, K.bg,
                style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                // the ride is yours, so it takes the accent; walking is context
                path, if (walking) K.muted else K.route,
                style = Stroke(
                    width = if (walking) 2.dp.toPx() else 4.dp.toPx(),
                    cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (walking)
                        PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())) else null,
                ),
            )
        }

        legs.filter { it.kind == Moovit.LegKind.WALK }.forEach(::draw)
        legs.filter { it.kind != Moovit.LegKind.WALK }.forEach(::draw)

        legs.first().shape.firstOrNull()?.let { (lat, lon) ->
            val p = proj.point(lat, lon)
            drawCircle(K.bg, 6.dp.toPx(), p)
            drawCircle(K.text, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        legs.last().shape.lastOrNull()?.let { (lat, lon) ->
            val p = proj.point(lat, lon)
            drawCircle(K.bg, 7.dp.toPx(), p)
            drawCircle(K.text, 5.dp.toPx(), p)
        }
        // where you change vehicle
        for (l in legs) if (l.kind != Moovit.LegKind.WALK) {
            l.shape.firstOrNull()?.let { (lat, lon) ->
                val p = proj.point(lat, lon)
                drawCircle(K.bg, 5.dp.toPx(), p)
                drawCircle(K.text, 3.dp.toPx(), p)
            }
        }
    }
}
