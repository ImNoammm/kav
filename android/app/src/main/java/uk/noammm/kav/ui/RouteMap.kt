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
 * A ride's colour: the chosen accent, then a different hue for each change of vehicle,
 * so a trip reads as a sequence rather than as one unbroken line.
 *
 * This used to step the accent's brightness down a rung per ride and leave the hue
 * alone, one colour throughout on purpose. On a dark map it did not survive contact:
 * two rungs of the same hue a few percent of value apart read as the same colour, and
 * the sequence they were carrying was simply lost. Turning the hue is the reading
 * Moovit gets for free from operator branding, and it is unmistakable at a glance.
 * The first ride still takes the chosen accent exactly, so the trip is still in the
 * rider's colour; only the rides after it walk away from it.
 *
 * Saturation and value are floored for the turned hues rather than carried over: an
 * accent may be pale or muted and still read at its own hue, while its neighbours
 * have to hold up against the same dark ground without that help.
 *
 * It cycles rather than running out, so a sixth ride repeats the first instead of
 * flattening onto its neighbour.
 *
 * The index is a position in the caller's own ride list, not a hash of the line: the
 * whole point is that consecutive rides differ, and a hash cannot promise that. Every
 * caller must therefore index the same list it passes to [boardingMarkers], or a
 * boarding marker will be painted a different colour from the line it sits on.
 */
private const val ROUTE_HUE_STEP = 68f
private const val ROUTE_HUE_RUNGS = 5

internal fun routeTint(index: Int): Color {
    val rung = index.mod(ROUTE_HUE_RUNGS)
    if (rung == 0) return K.route
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(K.route.toArgb(), hsv)
    hsv[0] = (hsv[0] + ROUTE_HUE_STEP * rung).mod(360f)
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceAtLeast(0.82f)
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
 *
 * Ring and core part company at a change: there the ring stays the colour of the line
 * that brought the rider in and the core takes the colour of the one they leave on, so
 * a single marker says both halves of the change at once.
 */
internal fun boardingDots(at: Pair<Double, Double>, tint: Color, core: Color = tint): List<MapDot> = listOf(
    MapDot(at.first, at.second, K.bg, 9f),
    MapDot(at.first, at.second, Color.Transparent, 7f, tint, 2.5f),
    MapDot(at.first, at.second, core, 3.5f),
)

/**
 * Both ends of every ride, each in that ride's own shade of the accent.
 *
 * Taken from the stop's own coordinates, then projected onto the ride's drawn line.
 * The two are not the same point: the stop is Moovit's position for the shelter, at
 * the kerb, while the line is the plan's encoded polyline, which is coarser and cuts
 * the corner it turns. Marking the polyline's raw end instead put the marker a few
 * metres from the circle already drawn for that stop, and a transfer came out as two
 * rings side by side with only one of them in the right place; marking the stop
 * honestly left the ring floating off the route it belongs to, which is what a rider
 * actually sees. [onRoute] keeps where along the ride the stop sits, which is the
 * part that carries meaning, and gives up only the offset across it. [stops] must be
 * the same map the plain stop circles were placed from, and those are projected too.
 *
 * A change of vehicle at one stop gets one marker rather than two. Moovit gives that
 * change as a single stop id shared by the ride that ends there and the ride that
 * starts there, but as two leg shapes that need not quite meet, so drawing each ride's
 * ends independently put two rings a few metres apart on what the rider knows is one
 * place to stand. The rings are merged and set between the two lines they cap, and the
 * marker carries both colours instead of the second simply painting over the first.
 */
internal fun boardingMarkers(
    rides: List<Moovit.Leg>,
    r: Moovit.Resolved,
    stops: Map<Int, Moovit.StopInfo> = r.stops,
): List<MapDot> {
    val board = rides.map { l ->
        (stops[l.fromStop]?.point ?: l.stops.firstOrNull()?.let { stops[it] }?.point ?: l.shape.firstOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val alight = rides.map { l ->
        (stops[l.toStop]?.point ?: l.stops.lastOrNull()?.let { stops[it] }?.point ?: l.shape.lastOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val changes = rides.indices.map { i ->
        i < rides.lastIndex && rides[i].toStop > 0 && rides[i].toStop == rides[i + 1].fromStop
    }
    return rides.indices.flatMap { i ->
        val tint = routeTint(i)
        val getOn = if (i > 0 && changes[i - 1]) emptyList() else board[i]?.let { boardingDots(it, tint) }.orEmpty()
        val getOff = alight[i]?.let {
            if (!changes[i]) boardingDots(it, tint)
            else boardingDots(between(it, board[i + 1] ?: it), tint, routeTint(i + 1))
        }.orEmpty()
        getOn + getOff
    }
}

/** Halfway between two points, near enough over the few metres this is asked for. */
private fun between(a: Pair<Double, Double>, b: Pair<Double, Double>) =
    ((a.first + b.first) / 2) to ((a.second + b.second) / 2)

/**
 * A point pulled onto the polyline drawn beside it, keeping its position along that
 * line and dropping only its distance across it. Returns the point untouched when
 * there is no line to project onto.
 */
internal fun onRoute(at: Pair<Double, Double>, path: List<Pair<Double, Double>>): Pair<Double, Double> =
    if (path.size < 2) at else pointAlong(path, alongPath(at.first, at.second, path)) ?: at

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
                rides.mapIndexed { i, l -> MapLine(l.shape, routeTint(i), 4f, casing = 7f) },
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
