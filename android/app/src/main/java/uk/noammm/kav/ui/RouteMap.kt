package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import uk.noammm.kav.data.Moovit

private const val ROUTE_HUE_NUDGE = 34f
private const val ROUTE_HUE_RUNGS = 3

private fun routeBase(ride: Moovit.Leg, r: Moovit.Resolved): Color {
    val info = r.line(ride.lineId) ?: return K.route
    return plateFor(r.routeType(info.agencyId), info.agencyId)?.fill ?: K.route
}

private fun turned(base: Color, rung: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + ROUTE_HUE_NUDGE * rung).mod(360f)
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceAtLeast(0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun routeTints(rides: List<Moovit.Leg>, r: Moovit.Resolved): List<Color> {
    var run = 0
    var last: Color? = null
    return rides.map { ride ->
        val base = routeBase(ride, r)
        run = if (base == last) (run + 1).mod(ROUTE_HUE_RUNGS) else 0
        last = base
        if (run == 0) base else turned(base, run)
    }
}

internal fun boardingDots(at: Pair<Double, Double>, tint: Color, core: Color = tint): List<MapDot> = listOf(
    MapDot(at.first, at.second, K.bg, 9f),
    MapDot(at.first, at.second, Color.Transparent, 7f, tint, 2.5f),
    MapDot(at.first, at.second, core, 3.5f),
)

internal fun boardingMarkers(
    rides: List<Moovit.Leg>,
    tints: List<Color>,
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
        val tint = tints[i]
        val getOn = if (i > 0 && changes[i - 1]) emptyList() else board[i]?.let { boardingDots(it, tint) }.orEmpty()
        val getOff = alight[i]?.let {
            if (!changes[i]) boardingDots(it, tint)
            else boardingDots(between(it, board[i + 1] ?: it), tint, tints[i + 1])
        }.orEmpty()
        getOn + getOff
    }
}

// Moovit gives the walk inside a station no path, so each end gets a mark instead of a line.
internal enum class StationPoint { ENTRANCE, EXIT, PLATFORM }

internal class StationMark(
    val lat: Double,
    val lon: Double,
    val point: StationPoint,
    val mode: Mode = Mode.TRAIN,
    val tint: Color = K.route,
)

internal fun stationMarks(
    legs: List<Moovit.Leg>,
    rides: List<Moovit.Leg>,
    tints: List<Color>,
    r: Moovit.Resolved,
): List<StationMark> {
    val out = ArrayList<StationMark>()
    for (i in legs.indices) {
        if (!legs[i].pathway) continue
        val before = legs.subList(0, i).lastOrNull { !it.pathway && it.kind != Moovit.LegKind.WAIT }
        val after = legs.subList(i + 1, legs.size).firstOrNull { !it.pathway && it.kind != Moovit.LegKind.WAIT }
        for ((leg, ending) in listOf(before to true, after to false)) {
            if (leg == null) continue
            val (lat, lon) = (if (ending) leg.shape.lastOrNull() else leg.shape.firstOrNull()) ?: continue
            out.add(
                if (leg.kind == Moovit.LegKind.WALK) {
                    StationMark(lat, lon, if (ending) StationPoint.ENTRANCE else StationPoint.EXIT)
                } else StationMark(
                    lat, lon, StationPoint.PLATFORM, modeOf(r.routeType(r.line(leg.lineId)?.agencyId ?: -1)),
                    tints.getOrElse(rides.indexOfFirst { it === leg }) { K.route },
                ),
            )
        }
    }
    return out.distinctBy { Triple(it.lat, it.lon, it.point) }
}

internal class MapMarks(val dots: List<MapDot>, val markers: List<MapMarker>, val images: Map<String, ImageBitmap>)

// Station doors, platforms and the pin where the trip ends, drawn by the map itself.
internal fun tripMarks(
    marks: List<StationMark>,
    end: Pair<Double, Double>?,
    entrance: Painter,
    exit: Painter,
    density: Density,
    dir: LayoutDirection,
): MapMarks {
    val dots = ArrayList<MapDot>()
    val markers = ArrayList<MapMarker>()
    // Doors come after the platforms, so a door sharing a station with the next ride's platform stays in front of it.
    val doors = ArrayList<MapMarker>()
    val images = HashMap<String, ImageBitmap>()
    for (m in marks) {
        if (m.point == StationPoint.PLATFORM) {
            dots += MapDot(m.lat, m.lon, K.bg, 11f)
            dots += MapDot(m.lat, m.lon, m.tint, 9f)
            markers += MapMarker(m.lat, m.lon, modeIconName(m.mode))
            continue
        }
        val out = m.point == StationPoint.EXIT
        val (name, image) = doorIcon(if (out) exit else entrance, out, density, dir)
        images[name] = image
        doors += MapMarker(m.lat, m.lon, name)
    }
    markers += doors
    end?.let { (lat, lon) ->
        val (name, image) = pinIcon(density, dir)
        images[name] = image
        markers += MapMarker(lat, lon, name, bottom = true)
    }
    return MapMarks(dots, markers, images)
}

private val iconCache = HashMap<String, ImageBitmap>()

private fun icon(
    name: String, w: Float, h: Float, density: Density, dir: LayoutDirection, draw: DrawScope.() -> Unit,
): Pair<String, ImageBitmap> = name to iconCache.getOrPut(name) {
    val image = ImageBitmap(ceil(w).toInt(), ceil(h).toInt())
    CanvasDrawScope().draw(density, dir, androidx.compose.ui.graphics.Canvas(image), Size(w, h), draw)
    image
}

private fun doorIcon(glyph: Painter, out: Boolean, density: Density, dir: LayoutDirection) = with(density) {
    val outer = 23.dp.toPx(); val inner = 19.dp.toPx(); val g = 14.dp.toPx()
    val name = "kav-door-${if (out) "out" else "in"}-${K.bg.toArgb()}-${K.text.toArgb()}-$dir"
    icon(name, outer, outer, density, dir) {
        drawRoundRect(K.bg, Offset.Zero, Size(outer, outer), CornerRadius(7.dp.toPx()))
        drawRoundRect(K.text, Offset((outer - inner) / 2, (outer - inner) / 2), Size(inner, inner), CornerRadius(5.dp.toPx()))
        translate((outer - g) / 2, (outer - g) / 2) { with(glyph) { draw(Size(g, g), colorFilter = ColorFilter.tint(K.bg)) } }
    }
}

// Moovit's end of trip pin, in 0.8dp units. The image ends at the tip so it stands on the spot.
private fun pinIcon(density: Density, dir: LayoutDirection) = with(density) {
    val u = .8f.dp.toPx()
    icon("kav-pin-${K.accent.toArgb()}-${K.bg.toArgb()}", 31.5f * u, 36.75f * u, density, dir) {
        val pin = Path().apply {
            moveTo(0f, 14f)
            cubicTo(0f, 24.5f, 14f, 35f, 14f, 35f)
            cubicTo(14f, 35f, 28f, 24.5f, 28f, 14f)
            cubicTo(28f, 6.268f, 21.732f, 0f, 14f, 0f)
            cubicTo(6.268f, 0f, 0f, 6.268f, 0f, 14f)
            close()
        }
        withTransform({
            scale(u, u, pivot = Offset.Zero)
            translate(1.75f, 1.75f)
        }) {
            drawPath(pin, K.bg, style = Stroke(3.5f))
            drawPath(pin, K.accent)
            drawCircle(Color.White, 5f, Offset(14f, 14f))
        }
    }
}

// Vehicles as the Live tab draws them. alpha fades one out.
internal fun vehicleGeometry(vehicles: List<Pair<Moovit.Arrival, Mode>>, alpha: (Moovit.Arrival) -> Float = { 1f }): MapGeometry {
    val halos = ArrayList<MapDot>()
    val dots = ArrayList<MapDot>()
    val markers = ArrayList<MapMarker>()
    for ((a, mode) in vehicles) {
        val k = alpha(a)
        if (k <= .01f) continue
        val tint = if (a.vehicleStatus == 2) K.problem else K.realtime
        halos += MapDot(a.lat, a.lon, tint.copy(alpha = .2f * k), 18f)
        dots += MapDot(a.lat, a.lon, K.bg.copy(alpha = k), 11f)
        dots += MapDot(a.lat, a.lon, tint.copy(alpha = k), 9f)
        if (k > .5f) markers += MapMarker(a.lat, a.lon, modeIconName(mode), alpha = k)
    }
    return MapGeometry(dots = dots, markers = markers, halos = halos)
}

private fun between(a: Pair<Double, Double>, b: Pair<Double, Double>) =
    ((a.first + b.first) / 2) to ((a.second + b.second) / 2)

internal fun onRoute(at: Pair<Double, Double>, path: List<Pair<Double, Double>>): Pair<Double, Double> =
    if (path.size < 2) at else pointAlong(path, alongPath(at.first, at.second, path)) ?: at

@Composable
fun RouteMap(trip: Moovit.Itinerary, r: Moovit.Resolved = Moovit.Resolved(), height: Dp = 190.dp, modifier: Modifier = Modifier) {
    val legs = trip.legs.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val points = legs.flatMap { it.shape }
    val vehicles = trip.rides.flatMap { it.options }.mapNotNull { o ->
        r.arrival(o)?.takeIf { it.hasLocation }?.let { it to modeOf(r.routeType(r.line(o.lineId)?.agencyId ?: -1)) }
    }.distinctBy { it.first.tripId }
    val vehicleAlpha = animateFloatAsState(if (vehicles.isEmpty()) 0f else 1f, tween(350), label = "vehicleReveal")

    val rides = legs.filter { it.kind != Moovit.LegKind.WALK }
    val tints = routeTints(rides, r)
    val marks = remember(trip, tints, r, K.light) { stationMarks(trip.legs, rides, tints, r) }
    val entrance = rememberVectorPainter(Icons.AutoMirrored.Rounded.Login)
    val exit = rememberVectorPainter(Icons.AutoMirrored.Rounded.Logout)
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val geometry = remember(legs, r.stops, tints, marks, K.look, K.accent, dir) {
        val station = tripMarks(marks, legs.last().shape.lastOrNull(), entrance, exit, density, dir)
        MapGeometry(
            lines = legs.filter { it.kind == Moovit.LegKind.WALK }
                .map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                rides.mapIndexed { i, l -> MapLine(l.shape, tints[i], 4f, casing = 7f) },
            dots = boardingMarkers(rides, tints, r) + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 6f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
            ) + station.dots,
            markers = station.markers,
            images = station.images,
        )
    }
    TileMap(
        points,
        modifier.fillMaxWidth().height(height).panel(12.dp),
        geometry = geometry,
        live = vehicleGeometry(vehicles) { vehicleAlpha.value },
    )
}
