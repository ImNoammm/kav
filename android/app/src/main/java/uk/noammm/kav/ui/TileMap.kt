package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import uk.noammm.kav.data.MapFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A basemap under the route: pinch to zoom, drag to pan.
 *
 * The map is rendered by MapLibre from a PMTiles archive of Israel that lives on the
 * phone (data/MapFile.kt), vector, so it stays crisp at every zoom, and offline, so
 * nothing about where you look leaves the device. The style is Protomaps' "black"
 * flavour, bundled with its fonts and sprites in the APK.
 *
 * MapLibre here is only a renderer. The camera, fit, pan, pinch, follow, the lean,
 * is Kav's own, exactly as it was over raster tiles: gestures land on a Compose layer
 * above the map and MapLibre's camera is driven to follow [MapCamera]. Overlay
 * geometry is drawn on Compose canvases with the same linear [MapProjection]; the two
 * agree because MapLibre is given the same centre, scale and rotation pivot (its
 * camera padding puts the focal point on [Viewport.anchor]).
 *
 * Camera position and zoom survive location polls. Explicit focus changes animate a
 * fit; a moving point only expands the view once it leaves the visible area.
 *
 * Given a [Follow], the map turns into the view from the road: the followed point sits
 * low in the frame, the direction of travel is up, and the ground leans away.
 */

const val MAP_ATTRIBUTION = "Protomaps · © OpenStreetMap"

/**
 * Geometry MapLibre draws itself, inside the basemap's own frame.
 *
 * A Compose canvas above the map draws at the camera the animation just asked for;
 * MapLibre renders on its own thread a frame or more later. At rest the two agree,
 * but a follow animation never rests, and the route slides against the streets by
 * however far the camera moved in the gap. Geometry handed to MapLibre is composed
 * with the ground in the same GL frame, so it cannot swim, and the fullscreen
 * per-frame path drawing it replaces was most of the overlay's frame budget.
 *
 * Sizes are dp, converted once when the features are written. Order is list order:
 * dashed lines go down first, then every casing, then every line, then dots.
 */
data class MapLine(
    val points: List<Pair<Double, Double>>,
    val colour: Color,
    val width: Float,
    val casing: Float = 0f,
    val dashed: Boolean = false,
)

data class MapDot(
    val lat: Double,
    val lon: Double,
    val fill: Color,
    val radius: Float,
    val stroke: Color = Color.Transparent,
    val strokeWidth: Float = 0f,
)

/** A bitmap marker MapLibre draws on the ground: an icon registered on the style, turned with the map. */
data class MapMarker(
    val lat: Double,
    val lon: Double,
    val icon: String,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
)

data class MapGeometry(
    val lines: List<MapLine> = emptyList(),
    val dots: List<MapDot> = emptyList(),
    /** Drawn on the `live` path only; the static `geometry` carries lines and dots. */
    val markers: List<MapMarker> = emptyList(),
)

private const val SRC_LINES = "kav-lines"
private const val SRC_DOTS = "kav-dots"
private const val SRC_LIVE_DOTS = "kav-live-dots"
private const val SRC_LIVE_MARKS = "kav-live-marks"

/** The compass arrow's icon name, registered on the style by [TileMap], usable in [MapMarker]. */
const val MAP_ARROW_ICON = "kav-arrow"

/** The style spec's colour strings; Compose colours carry alpha the same way. */
private fun rgba(c: Color): String =
    "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},${c.alpha})"

/**
 * Kav's own layers on top of whatever style is loaded, driven per feature: colour,
 * width and order come with the geometry, so one set of layers serves every screen.
 */
private fun Style.ensureKavLayers() {
    if (getSource(SRC_LINES) != null) return
    addSource(GeoJsonSource(SRC_LINES))
    addSource(GeoJsonSource(SRC_DOTS))
    addSource(GeoJsonSource(SRC_LIVE_DOTS))
    addSource(GeoJsonSource(SRC_LIVE_MARKS))
    val colour = Expression.toColor(Expression.get("colour"))
    val width = Expression.toNumber(Expression.get("width"))
    val sort = Expression.toNumber(Expression.get("sort"))
    val round = PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    addLayer(LineLayer("kav-line-dash", SRC_LINES).withProperties(
        PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(width),
        // dasharray is in line widths: 3dp on, 5dp off, at the walk hairline's 2dp
        PropertyFactory.lineDasharray(arrayOf(1.5f, 2.5f)),
        PropertyFactory.lineSortKey(sort), round, join,
    ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(true))))
    addLayer(LineLayer("kav-line-casing", SRC_LINES).withProperties(
        PropertyFactory.lineColor(Expression.toColor(Expression.get("casingColour"))),
        PropertyFactory.lineWidth(Expression.toNumber(Expression.get("casing"))),
        PropertyFactory.lineSortKey(sort), round, join,
    ).withFilter(Expression.all(
        Expression.eq(Expression.get("dashed"), Expression.literal(false)),
        Expression.gt(Expression.get("casing"), Expression.literal(0)),
    )))
    addLayer(LineLayer("kav-line", SRC_LINES).withProperties(
        PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(width),
        PropertyFactory.lineSortKey(sort), round, join,
    ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(false))))
    addLayer(CircleLayer("kav-dot", SRC_DOTS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColour"))),
        PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
        PropertyFactory.circleSortKey(sort),
    ))
    // The live markers, where you are, where the buses are, go above everything.
    // They are GL layers for the same reason the route is: a Compose canvas draws at
    // the camera the gesture just asked for, MapLibre presents a frame or more later,
    // and a marker that must sit on the ground slides against it by the difference.
    addLayer(CircleLayer("kav-live-dot", SRC_LIVE_DOTS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColour"))),
        PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
        PropertyFactory.circleSortKey(sort),
    ))
    addLayer(SymbolLayer("kav-live-mark", SRC_LIVE_MARKS).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rot"))),
        PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("alpha"))),
        // the arrow points a compass heading: a bearing over the ground, not the screen
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    ))
}

/** Style pixels are already density-scaled on Android, so the dp sizes go in as-is. */
private fun Style.setKavGeometry(g: MapGeometry) {
    val lines = g.lines.filter { it.points.size >= 2 }.mapIndexed { i, l ->
        Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.second, it.first) })).apply {
            addStringProperty("colour", rgba(l.colour))
            addStringProperty("casingColour", rgba(K.bg))
            addNumberProperty("width", l.width)
            addNumberProperty("casing", l.casing)
            addBooleanProperty("dashed", l.dashed)
            addNumberProperty("sort", i)
        }
    }
    getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))
    getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures(g.dots)))
}

private fun dotFeatures(dots: List<MapDot>): List<Feature> = dots.mapIndexed { i, d ->
    Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat)).apply {
        addStringProperty("colour", rgba(d.fill))
        addStringProperty("strokeColour", rgba(d.stroke))
        addNumberProperty("r", d.radius)
        addNumberProperty("strokeWidth", d.strokeWidth)
        addNumberProperty("sort", i)
    }
}

/** The frequently-moving markers, in their own sources so a GPS tick rewrites two features, not the route. */
private fun Style.setKavLive(g: MapGeometry) {
    val marks = g.markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
            addStringProperty("icon", m.icon)
            addNumberProperty("rot", m.rotation)
            addNumberProperty("alpha", m.alpha)
        }
    }
    getSourceAs<GeoJsonSource>(SRC_LIVE_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures(g.dots)))
    getSourceAs<GeoJsonSource>(SRC_LIVE_MARKS)?.setGeoJson(FeatureCollection.fromFeatures(marks))
}

/** The walk compass arrow, drawn once as a bitmap for the symbol layer: [MAP_ARROW_ICON]. */
private fun arrowBitmap(dp: Float): android.graphics.Bitmap {
    val s = 11f * dp
    val half = (s * 1.35f + 3f * dp).toInt() + 1
    val bmp = android.graphics.Bitmap.createBitmap(2 * half, 2 * half, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = half.toFloat()
    val cy = half.toFloat()
    val path = android.graphics.Path().apply {
        moveTo(cx, cy - s * 1.35f)
        lineTo(cx + s * .8f, cy + s * .75f)
        lineTo(cx, cy + s * .25f)
        lineTo(cx - s * .8f, cy + s * .75f)
        close()
    }
    val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f * dp
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = K.bg.toArgb()
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = K.text.toArgb() }
    canvas.drawPath(path, outline)
    canvas.drawPath(path, fill)
    return bmp
}

/** Web-mercator: the 0..1 world square, and the zoom range the camera works in. */
internal object Geo {
    /** Zoom is in 256px-tile units, as it always was; MapLibre's 512px zoom is one less. */
    const val SIZE = 256
    const val MIN_Z = 8
    /** Where a fit stops zooming in; gestures may go [OVER] past it, vector stays sharp. */
    const val MAX_Z = 16
    const val OVER = 6f
    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        return (1.0 - asinh(tan(r)) / PI) / 2.0
    }
    fun lon(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
}

/** Where the map is looking. World coordinates are the 0..1 web-mercator square. */
private data class Camera(val worldX: Double, val worldY: Double, val zoom: Float, val rotation: Float = 0f) {
    val pxPerWorld get() = Geo.SIZE * 2.0.pow(zoom.toDouble())
}

/** A point to keep low in the frame with [bearing] up, where you are, the way you are going. */
data class Follow(val lat: Double, val lon: Double, val bearing: Float, val zoom: Float = 18.5f)

/**
 * Where the followed point sits, as fractions of the part of the map nothing covers.
 * It used to be a fraction of the whole canvas, and the whole canvas runs under the
 * step cards: on a tall phone two thirds of the way down was behind the ride card,
 * and the arrow that is the point of following was hidden by the card describing it.
 */
private const val ANCHOR_X = 0.5f
private const val ANCHOR_Y = 0.72f
private const val TILT_DEG = 40f

/**
 * A screen point back into the space the overlays draw in. They are drawn through
 * `rotate(-rotation, anchor)`, so undoing a tap means turning it the other way about
 * the same pivot. A no-op on a map that is not following anything, which never turns.
 */
private fun unrotate(at: Offset, anchor: Offset, rotation: Float): Offset {
    if (rotation == 0f) return at
    val rad = Math.toRadians(rotation.toDouble())
    val c = kotlin.math.cos(rad); val sn = kotlin.math.sin(rad)
    val dx = (at.x - anchor.x).toDouble(); val dy = (at.y - anchor.y).toDouble()
    return Offset((dx * c - dy * sn + anchor.x).toFloat(), (dx * sn + dy * c + anchor.y).toFloat())
}

/** What the caller needs to place its own geometry on the map. */
class MapProjection(
    private val centerWorldX: Double,
    private val centerWorldY: Double,
    private val pxPerWorld: Double,
    private val width: Float,
    private val height: Float,
) {
    fun point(lat: Double, lon: Double): Offset {
        val wx = Geo.x(lon)
        val wy = Geo.y(lat)
        return Offset(
            ((wx - centerWorldX) * pxPerWorld + width / 2).toFloat(),
            ((wy - centerWorldY) * pxPerWorld + height / 2).toFloat(),
        )
    }
}

private data class WorldPoint(val x: Double, val y: Double)

private data class Viewport(val left: Float, val top: Float, val right: Float, val bottom: Float, val w: Float, val h: Float) {
    val width get() = (right - left).coerceAtLeast(1f)
    val height get() = (bottom - top).coerceAtLeast(1f)
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun inset(fraction: Float): Viewport {
        val pad = fraction.coerceIn(0f, 0.45f)
        return copy(left = left + width * pad, right = right - width * pad,
            top = top + height * pad, bottom = bottom - height * pad)
    }
    fun contains(point: WorldPoint, camera: Camera): Boolean {
        val x = (point.x - camera.worldX) * camera.pxPerWorld + w / 2
        val y = (point.y - camera.worldY) * camera.pxPerWorld + h / 2
        return x >= left && x <= right && y >= top && y <= bottom
    }
    /** The followed point's place on screen: low in the uncovered area, never under a card. */
    val anchor get() = Offset(left + width * ANCHOR_X, top + height * ANCHOR_Y)
}

/** Fits into the unobscured rectangle; projection coordinates still use the whole canvas. */
private fun fitCamera(points: List<WorldPoint>, viewport: Viewport, padFraction: Float, maxZoom: Float = Geo.MAX_Z.toFloat()): Camera {
    val area = viewport.inset(padFraction)
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val scaleX = area.width / ((maxX - minX).coerceAtLeast(1e-12) * Geo.SIZE)
    val scaleY = area.height / ((maxY - minY).coerceAtLeast(1e-12) * Geo.SIZE)
    val zoom = (ln(minOf(scaleX, scaleY)) / ln(2.0)).toFloat().coerceIn(Geo.MIN_Z.toFloat(), maxZoom)
    val scale = Geo.SIZE * 2.0.pow(zoom.toDouble())
    return Camera(
        (minX + maxX) / 2 - (area.centerX - area.w / 2) / scale,
        (minY + maxY) / 2 - (area.centerY - area.h / 2) / scale,
        zoom,
    )
}

private class MapCamera {
    var value by mutableStateOf<Camera?>(null)
        private set
    var manual by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var destination: Camera? = null
    private var previousPoints = emptyList<WorldPoint>()
    private var previousViewport: Viewport? = null
    private var previousFocus: Any? = null
    private var previousPadding = 0f

    fun update(points: List<WorldPoint>, viewport: Viewport, focus: Any?, padding: Float, scope: CoroutineScope) {
        if (points.isEmpty() || viewport.w <= 0 || viewport.h <= 0) return
        val reframe = value == null || previousFocus != focus || previousViewport != viewport || previousPadding != padding
        val current = destination ?: value
        val changed = points.filterIndexed { index, point -> previousPoints.getOrNull(index) != point }
        previousPoints = points
        previousFocus = focus
        previousViewport = viewport
        previousPadding = padding
        if (reframe) {
            reset(points, viewport, padding, scope)
        } else if (current != null && changed.any { !viewport.contains(it, current) }) {
            // Preserve the current visible area, and expand to include the moved points.
            // Static route points outside a manually panned view do not reset the camera.
            val area = viewport.inset(padding)
            val scale = current.pxPerWorld
            val bounds = listOf(
                WorldPoint(current.worldX + (area.left - area.w / 2) / scale, current.worldY + (area.top - area.h / 2) / scale),
                WorldPoint(current.worldX + (area.right - area.w / 2) / scale, current.worldY + (area.bottom - area.h / 2) / scale),
            )
            moveTo(fitCamera(bounds + changed, viewport, padding, current.zoom), scope)
        }
    }

    fun reset(points: List<WorldPoint>, viewport: Viewport, padding: Float, scope: CoroutineScope) {
        if (points.isEmpty()) return
        manual = false
        moveTo(fitCamera(points, viewport, padding), scope)
    }

    /** Put one point back under the middle of the map, at the zoom already in use. */
    fun centre(point: WorldPoint, scope: CoroutineScope) {
        val current = destination ?: value ?: return
        manual = false
        moveTo(current.copy(worldX = point.x, worldY = point.y, rotation = 0f), scope)
    }

    /**
     * The followed point goes to the anchor and the bearing goes up. Rotation is about
     * the anchor, so the point stays put while the world turns around it.
     */
    fun follow(target: Follow, viewport: Viewport, scope: CoroutineScope) {
        val scale = Geo.SIZE * 2.0.pow(target.zoom.toDouble())
        val tx = Geo.x(target.lon)
        val ty = Geo.y(target.lat)
        val anchor = viewport.anchor
        val goal = Camera(
            tx - (anchor.x - viewport.w / 2) / scale,
            ty - (anchor.y - viewport.h / 2) / scale,
            target.zoom,
            ((target.bearing % 360f) + 360f) % 360f,
        )
        manual = false
        moveTo(goal, scope, duration = 700)
    }

    /** Back on the rails after a manual pan: the next follow or fit takes over. */
    fun resume() { manual = false }

    private fun moveTo(target: Camera, scope: CoroutineScope, duration: Int = 520) {
        // A jittering compass retargets a follow on every sensor tick, and an ease
        // restarted every tick lives forever in its slow start, the camera crawls
        // and never arrives. A target this close to where the camera is already
        // going is the same place; let the flight that is under way land.
        (destination ?: value)?.let { d ->
            var spin = target.rotation - d.rotation
            if (spin > 180f) spin -= 360f
            if (spin < -180f) spin += 360f
            if (abs(target.worldX - d.worldX) * d.pxPerWorld < 2.0 &&
                abs(target.worldY - d.worldY) * d.pxPerWorld < 2.0 &&
                abs(target.zoom - d.zoom) < 0.05f && abs(spin) < 2.5f
            ) return
        }
        animation?.cancel()
        val start = value
        destination = target
        if (start == null || start == target) {
            value = target
            destination = null
            return
        }
        // turn the short way round
        var spin = target.rotation - start.rotation
        if (spin > 180f) spin -= 360f
        if (spin < -180f) spin += 360f
        animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(duration, easing = FastOutSlowInEasing)) { fraction, _ ->
                value = Camera(
                    start.worldX + (target.worldX - start.worldX) * fraction,
                    start.worldY + (target.worldY - start.worldY) * fraction,
                    start.zoom + (target.zoom - start.zoom) * fraction,
                    ((start.rotation + spin * fraction) + 360f) % 360f,
                )
            }
            value = target
            destination = null
        }
    }

    fun gesture(centroid: Offset, pan: Offset, zoomChange: Float, viewport: Viewport) {
        val current = value ?: return
        animation?.cancel()
        destination = null
        manual = true
        val zoom = (current.zoom + ln(zoomChange.coerceAtLeast(0.01f)) / ln(2f))
            .coerceIn(Geo.MIN_Z.toFloat(), Geo.MAX_Z + Geo.OVER)
        val before = current.pxPerWorld
        val after = Geo.SIZE * 2.0.pow(zoom.toDouble())
        // Fingers act in screen directions; the drawn world is turned about the
        // anchor. Undo that turn to find what the fingers are actually holding, or a
        // pinch on a turned map zooms about a point off to one side and drifts.
        val rad = Math.toRadians(current.rotation.toDouble())
        val anchor = viewport.anchor
        fun world(s: Offset): Pair<Double, Double> {
            val dx = s.x - anchor.x
            val dy = s.y - anchor.y
            val ux = anchor.x + dx * cos(rad) - dy * sin(rad)
            val uy = anchor.y + dx * sin(rad) + dy * cos(rad)
            return (ux - viewport.w / 2) to (uy - viewport.h / 2)
        }
        val (cx, cy) = world(centroid)
        val (px, py) = world(centroid + pan)
        value = Camera(
            current.worldX + cx / before - px / after,
            current.worldY + cy / before - py / after,
            zoom,
            current.rotation,
        )
    }
}

/**
 * Camera padding that puts MapLibre's focal point on [anchor]: left, top, right,
 * bottom. MapLibre centres the target in the padded viewport, so the padded centre
 * must land exactly on the anchor, pinned by GeometryRegressionTest.
 */
internal fun focalPadding(anchor: Offset, w: Float, h: Float): DoubleArray = doubleArrayOf(
    (2 * anchor.x - w).coerceAtLeast(0f).toDouble(),
    (2 * anchor.y - h).coerceAtLeast(0f).toDouble(),
    (w - 2 * anchor.x).coerceAtLeast(0f).toDouble(),
    (h - 2 * anchor.y).coerceAtLeast(0f).toDouble(),
)


/** MapLibre's camera, told to look exactly where [Camera] is looking. */
private fun MapLibreMap.driveTo(cam: Camera, anchor: Offset, w: Float, h: Float, pitch: Float, screenDensity: Float) {
    // The anchor becomes MapLibre's focal point via camera padding, so bearing and
    // tilt pivot where the overlay's turned{} pivots.
    val wx = cam.worldX + (anchor.x - w / 2) / cam.pxPerWorld
    val wy = cam.worldY + (anchor.y - h / 2) / cam.pxPerWorld
    val pad = focalPadding(anchor, w, h)
    moveCamera(CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(LatLng(Geo.lat(wy), Geo.lon(wx)))
            // 256px-tile zoom → MapLibre's 512px zoom, minus log2(density): MapLibre's
            // zoom is defined against density-independent pixels while Camera works in
            // screen pixels, and without this every fit, pan and pinch lands density×
            // off even though the anchor (the pivot, immune to scale) registers.
            .zoom(cam.zoom.toDouble() - 1.0 - ln(screenDensity.toDouble()) / ln(2.0))
            .bearing(cam.rotation.toDouble())
            .tilt(pitch.toDouble())
            .padding(pad[0], pad[1], pad[2], pad[3])
            .build(),
    ))
}

@Composable
fun TileMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    padFraction: Float = 0.10f,
    focusKey: Any? = Unit,
    /**
     * What the reset control goes back to. Framing the whole route is right on a map
     * you are reading; while you are being navigated it is not, you want to be put
     * back on yourself, not on the end of the journey.
     */
    recenterOn: Pair<Double, Double>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    follow: Follow? = null,
    geometry: MapGeometry? = null,
    /**
     * Ground-locked markers that move often, you, the buses. Drawn by MapLibre in the
     * same GL frame as the ground, because a Compose overlay lags the map by a frame or
     * more and anything that must sit on the ground visibly slides during pans and
     * pinches. Use [animatedOverlay] only for effects that tolerate that slide.
     */
    live: MapGeometry? = null,
    animatedOverlay: DrawScope.(MapProjection) -> Unit = {},
    overlay: DrawScope.(MapProjection) -> Unit = {},
    /**
     * A tap on the ground, given in the same coordinates the overlays draw in: a
     * caller that placed a marker with [MapProjection.point] can compare the two and
     * decide whether the tap landed on it. Flat projection, like the overlays, so it
     * means nothing while the map is leaning.
     */
    onTap: ((Offset, MapProjection) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val camera = remember { MapCamera() }
    val worldPoints = remember(points) {
        points.filter { it.first.isFinite() && it.second.isFinite() }.map {
            WorldPoint(Geo.x(it.second), Geo.y(it.first))
        }
    }
    val following = follow != null && !camera.manual
    // The lean is MapLibre's own pitch: the GPU draws the tilted ground across the
    // whole viewport and fetches the far field at coarser zoom, where a flat frame
    // tilted after the fact runs out of pixels short of the horizon. Leaning in is
    // slow and cinematic; flattening is quick, because the gesture math reads the
    // screen as flat and should not be wrong for longer than it takes to get there.
    val tilt by animateFloatAsState(if (following) TILT_DEG else 0f,
        tween(if (following) 600 else 280, easing = FastOutSlowInEasing), label = "tilt")

    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx)
            // TextureView, not SurfaceView: the map composes like any other layer,
            // so panes above it and clipped corners behave without surface holes.
            .textureMode(true)
            .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            .foregroundLoadColor(K.surface1.toArgb()))
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            // Kav's own gestures drive the camera; MapLibre must not fight them.
            m.uiSettings.setAllGesturesEnabled(false)
            val densityShift = ln(ctx.resources.displayMetrics.density.toDouble()) / ln(2.0)
            m.setMinZoomPreference(Geo.MIN_Z - 1.0 - densityShift)
            m.setMaxZoomPreference(Geo.MAX_Z + Geo.OVER - 1.0 - densityShift)
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            map = null
            style = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    val mapReady = MapFile.state is MapFile.State.Ready
    LaunchedEffect(map, mapReady) {
        if (mapReady) map?.setStyle(Style.Builder().fromJson(MapFile.styleJson(ctx))) { style = it }
    }
    fun Style.ensureKavIcons() {
        val px = with(density) { 1.dp.toPx() }
        if (getImage(MAP_ARROW_ICON) == null) addImage(MAP_ARROW_ICON, arrowBitmap(px))
        // one vehicle mark per mode: a train on the map is the train Moovit draws,
        // not a bus standing in for everything that is not a bus
        val span = (12f * px).toInt().coerceAtLeast(8)
        for (m in Mode.entries) {
            val name = modeIconName(m)
            if (getImage(name) == null) addImage(name, modeMark(m, span).asAndroidBitmap())
        }
    }
    LaunchedEffect(style, geometry) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        if (geometry != null) {
            s.ensureKavLayers()
            s.ensureKavIcons()
            s.setKavGeometry(geometry)
        }
    }
    LaunchedEffect(style, live) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        if (live != null) {
            s.ensureKavLayers()
            s.ensureKavIcons()
            s.setKavLive(live)
        }
    }

    BoxWithConstraints(modifier.clipToBounds().background(K.surface1)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val padL = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }.coerceIn(0f, (w - 1).coerceAtLeast(0f))
        val padT = with(density) { contentPadding.calculateTopPadding().toPx() }.coerceIn(0f, (h - 1).coerceAtLeast(0f))
        val padR = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
        val padB = with(density) { contentPadding.calculateBottomPadding().toPx() }
        // what the cards leave uncovered
        val viewport = Viewport(padL, padT,
            (w - padR).coerceAtLeast(padL + 1),
            (h - padB).coerceAtLeast(padT + 1), w, h)
        val liveViewport by rememberUpdatedState(viewport)

        LaunchedEffect(worldPoints, viewport, focusKey, padFraction, follow == null) {
            if (follow == null) camera.update(worldPoints, viewport, focusKey, padFraction, scope)
        }
        // Moving to a new step card is an explicit "take me there": it ends a manual
        // pan, so the camera re-engages, the follow for that step, or its fit,
        // instead of staying wherever the fingers left it.
        LaunchedEffect(focusKey) { camera.resume() }
        LaunchedEffect(follow, viewport, camera.manual) {
            if (follow != null && !camera.manual) camera.follow(follow, viewport, scope)
        }
        val anchor = viewport.anchor

        LaunchedEffect(map, w, h, anchor) {
            val m = map ?: return@LaunchedEffect
            snapshotFlow { camera.value?.let { it to tilt } }.filterNotNull()
                .collect { (cam, pitch) -> m.driveTo(cam, anchor, w, h, pitch, density.density) }
        }

        // Route geometry and the live marker layer draw above the map. Each layer
        // observes camera changes in draw, without recomposing the map, and turns
        // with it: MapLibre turns about the same anchor its padding pins. The flat
        // projection knows nothing of pitch, so the canvases fade out as the map
        // leans, everything ground-locked lives in GL layers and leans with it.
        fun DrawScope.turned(block: DrawScope.() -> Unit) {
            val current = camera.value ?: return
            rotate(-current.rotation, anchor) { block() }
        }
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        val tap by rememberUpdatedState(onTap)
        Canvas(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - tilt / TILT_DEG }
                // The tap detector sits OUTSIDE the transform one, so the transform
                // gets every event first: a pan past slop is consumed there and never
                // reaches here, and only a press that went nowhere is read as a tap.
                // Attached only where a caller wants taps, so the maps that only want
                // to be panned keep exactly the gesture handling they always had.
                .then(if (onTap == null) Modifier else Modifier.pointerInput(anchor) {
                    detectTapGestures { at ->
                        val current = camera.value ?: return@detectTapGestures
                        tap?.invoke(
                            unrotate(at, anchor, current.rotation),
                            MapProjection(current.worldX, current.worldY, current.pxPerWorld,
                                size.width.toFloat(), size.height.toFloat()),
                        )
                    }
                })
                .pointerInput(camera) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        camera.gesture(centroid, panChange, zoomChange, liveViewport)
                    }
                },
        ) {
            val current = camera.value ?: return@Canvas
            turned { overlay(MapProjection(current.worldX, current.worldY, current.pxPerWorld, size.width, size.height)) }
        }

        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - tilt / TILT_DEG }) {
            val current = camera.value ?: return@Canvas
            turned { animatedOverlay(MapProjection(current.worldX, current.worldY, current.pxPerWorld, size.width, size.height)) }
        }

        if (!mapReady) MapDownloadCard(
            Modifier.align(Alignment.TopStart)
                .padding(start = with(density) { padL.toDp() }, top = with(density) { padT.toDp() })
                .size(with(density) { (w - padL - padR).coerceAtLeast(1f).toDp() },
                    with(density) { (h - padT - padB).coerceAtLeast(1f).toDp() }),
        )

        if (camera.manual) {
            Text(
                if (follow != null) T("Follow", "עקבו") else T("Reset", "איפוס"),
                fontSize = 11.sp, color = K.text,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = contentPadding.calculateTopPadding() + K.gap2,
                        end = contentPadding.calculateEndPadding(layoutDirection) + K.gap2)
                    .clip(RoundedCornerShape(999.dp)).background(K.plateStrong)
                    .clickable {
                        val me = recenterOn?.takeIf { it.first.isFinite() && it.second.isFinite() }
                        when {
                            follow != null -> camera.resume()
                            me != null -> camera.centre(WorldPoint(Geo.x(me.second), Geo.y(me.first)), scope)
                            else -> camera.reset(worldPoints, viewport, padFraction, scope)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Text(
            MAP_ATTRIBUTION,
            fontSize = 8.sp, color = K.dim,
            modifier = Modifier.align(Alignment.BottomEnd).alpha(0.8f)
                .padding(end = contentPadding.calculateEndPadding(layoutDirection) + 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 3.dp),
        )
    }
}

/** The offer, in the space the map would fill: the ground under Kav, fetched once. */
@Composable
private fun MapDownloadCard(modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(K.gap4).clip(RoundedCornerShape(14.dp)).background(K.plateStrong).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = MapFile.state) {
                is MapFile.State.Downloading -> {
                    Box(Modifier.width(160.dp).height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(s.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                    }
                    Text(T("Downloading… ${(s.progress * 100).toInt()}%", "מורידים… ${(s.progress * 100).toInt()}%"), fontSize = 12.sp, color = K.dim)
                }
                else -> {
                    Text(T("Download the map", "הורדת המפה"), fontSize = 14.sp, color = K.text, fontWeight = FontWeight.Medium)
                    Text(
                        T(
                            "About ${MapFile.BYTES shr 20} MB for all of Israel, once. Then it works with no signal.",
                            "כ-${MapFile.BYTES shr 20} מגה-בייט לכל ישראל, פעם אחת. אחר כך היא עובדת גם בלי קליטה.",
                        ),
                        fontSize = 12.sp, color = K.dim,
                    )
                    if (s is MapFile.State.Failed) Text(T("Couldn't download it. ${s.why}", "ההורדה נכשלה. ${s.why}"), fontSize = 12.sp, color = K.critical)
                    Box(
                        Modifier.clip(RoundedCornerShape(K.rPill)).background(K.accent)
                            .clickable { MapFile.startDownload(ctx) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) { Text(if (s is MapFile.State.Failed) T("Try again", "נסו שוב") else T("Download", "הורדה"), fontSize = 13.sp, color = K.bg, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}
