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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.noammm.kav.data.Moovit

/**
 * A ride's colour: the chosen accent, stepped down a rung for each change of vehicle,
 * so a trip reads as a sequence rather than as one unbroken line.
 *
 * Moovit paints every leg in its line group's own brand colour and lifts it for the
 * dark theme, which is why two buses look like two shades there: they ARE two
 * colours. Kav has one colour on purpose, so it buys the same reading a different
 * way. The leg boarded first takes the accent at full value and each leg after it
 * steps down, hue and saturation untouched, so every rung is plainly the colour that
 * was chosen rather than a second colour arriving from an operator.
 *
 * Two things the ladder has to respect. It never descends past [ROUTE_SHADE_FLOOR],
 * because below that it arrives at [K.routeIdle], which is what this map already
 * means by "not your ride". And it cycles rather than saturating at the bottom, so a
 * fifth ride repeats the first instead of flattening onto its neighbour.
 *
 * The index is a position in the caller's own ride list, not a hash of the line: the
 * whole point is that consecutive rides differ, and a hash cannot promise that. Every
 * caller must therefore index the same list it passes to [boardingMarkers], or a
 * boarding marker will be painted a different shade from the line it sits on.
 */
private const val ROUTE_SHADE_STEP = 0.13f
private const val ROUTE_SHADE_RUNGS = 4
private const val ROUTE_SHADE_FLOOR = 0.62f

internal fun routeShade(index: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(K.route.toArgb(), hsv)
    hsv[2] = (hsv[2] * (1f - ROUTE_SHADE_STEP * index.mod(ROUTE_SHADE_RUNGS)))
        .coerceIn(ROUTE_SHADE_FLOOR, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Where you get on and off, as against a stop the bus merely calls at.
 *
 * Moovit marks both ends of every ride with the stop's own icon and leaves the ones
 * between them as plain dots on the line. Kav says it with size and a filled core
 * rather than a bitmap, because a boarding is the one place on this map where the
 * rider has to do something: it should read as an event on the line, not as another
 * stop along it. The core takes the ride's own shade, so which of the trip's vehicles
 * you are getting onto is legible from the marker by itself.
 */
internal fun boardingDots(at: Pair<Double, Double>, tint: Color): List<MapDot> = listOf(
    MapDot(at.first, at.second, K.bg, 9f),
    MapDot(at.first, at.second, Color.Transparent, 7f, tint, 2.5f),
    MapDot(at.first, at.second, tint, 3.5f),
)

/**
 * Both ends of every ride, each in that ride's own shade of the accent.
 *
 * Placed at the stop's own coordinates, not at the end of the drawn line. They are
 * not the same point: the stop is Moovit's own position for it, exact to a
 * microdegree, while the line is the plan's encoded polyline, which is coarser and
 * cuts the corner it turns. Marking the polyline's end put the marker a few metres
 * from the circle already drawn for that stop, and a transfer came out as two rings
 * side by side with only one of them in the right place. [stops] must be the same
 * map those circles were placed from, or they will disagree again.
 */
internal fun boardingMarkers(
    rides: List<Moovit.Leg>,
    r: Moovit.Resolved,
    stops: Map<Int, Moovit.StopInfo> = r.stops,
): List<MapDot> =
    rides.mapIndexed { i, l ->
        val tint = routeShade(i)
        listOfNotNull(
            stops[l.fromStop]?.point ?: l.stops.firstOrNull()?.let { stops[it] }?.point ?: l.shape.firstOrNull(),
            stops[l.toStop]?.point ?: l.stops.lastOrNull()?.let { stops[it] }?.point ?: l.shape.lastOrNull(),
        ).flatMap { boardingDots(it, tint) }
    }.flatten()

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
    val rides = legs.filter { it.kind != Moovit.LegKind.WALK }
    val geometry = remember(legs, r.stops, K.accent) {
        MapGeometry(
            lines = legs.filter { it.kind == Moovit.LegKind.WALK }
                .map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                rides.mapIndexed { i, l -> MapLine(l.shape, routeShade(i), 4f, casing = 7f) },
            // the ends of the trip go on last, so they win the corner a boarding
            // marker shares with them when the trip opens on a ride
            dots = boardingMarkers(rides, r) + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 6f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.text, 5f) },
            ),
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
