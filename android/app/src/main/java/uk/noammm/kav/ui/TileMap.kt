package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.flow.collectLatest
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

const val MAP_ATTRIBUTION = "Protomaps · © OpenStreetMap"

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

data class MapMarker(
    val lat: Double,
    val lon: Double,
    val icon: String,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val bottom: Boolean = false,
    val turns: Boolean = false,
)

// Drawn by the map itself, so it moves with the map. Markers stay upright unless they turn.
data class MapGeometry(
    val lines: List<MapLine> = emptyList(),
    val dots: List<MapDot> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
    val halos: List<MapDot> = emptyList(),
    val images: Map<String, ImageBitmap> = emptyMap(),
)

private const val SRC_LINES = "kav-lines"
private const val SRC_DOTS = "kav-dots"
private const val SRC_MARKS = "kav-marks"
private const val SRC_LIVE_HALOS = "kav-live-halos"
private const val SRC_LIVE_DOTS = "kav-live-dots"
private const val SRC_LIVE_MARKS = "kav-live-marks"
private const val HALO_LAYER = "kav-live-halo"

const val MAP_ARROW_ICON = "kav-arrow"

private fun rgba(c: Color): String =
    "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},${c.alpha})"

private fun Style.ensureKavLayers() {
    if (getSource(SRC_LINES) != null) return
    addSource(GeoJsonSource(SRC_LINES))
    addSource(GeoJsonSource(SRC_DOTS))
    addSource(GeoJsonSource(SRC_MARKS))
    addSource(GeoJsonSource(SRC_LIVE_HALOS))
    addSource(GeoJsonSource(SRC_LIVE_DOTS))
    addSource(GeoJsonSource(SRC_LIVE_MARKS))
    val colour = Expression.toColor(Expression.get("colour"))
    val width = Expression.toNumber(Expression.get("width"))
    val sort = Expression.toNumber(Expression.get("sort"))
    val round = PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    addLayer(LineLayer("kav-line-dash", SRC_LINES).withProperties(
        PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(width),
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
    // A route's own marks overlap at stations, so they keep the order they were listed in, later ones in front.
    addLayer(markLayer("kav-mark", SRC_MARKS, turns = false).withProperties(
        PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("order"))),
    ))
    addLayer(CircleLayer(HALO_LAYER, SRC_LIVE_HALOS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
    ))
    addLayer(CircleLayer("kav-live-dot", SRC_LIVE_DOTS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColour"))),
        PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
        PropertyFactory.circleSortKey(sort),
    ))
    addLayer(markLayer("kav-live-mark", SRC_LIVE_MARKS, turns = false))
    addLayer(markLayer("kav-live-turn", SRC_LIVE_MARKS, turns = true))
}

private fun markLayer(id: String, source: String, turns: Boolean) = SymbolLayer(id, source).withProperties(
    PropertyFactory.iconImage(Expression.get("icon")),
    PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rot"))),
    PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("alpha"))),
    PropertyFactory.iconAnchor(Expression.get("anchor")),
    PropertyFactory.iconRotationAlignment(
        if (turns) Property.ICON_ROTATION_ALIGNMENT_MAP else Property.ICON_ROTATION_ALIGNMENT_VIEWPORT,
    ),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true),
).withFilter(Expression.eq(Expression.get("turns"), Expression.literal(turns)))

private fun Style.addKavImages(images: Map<String, ImageBitmap>) {
    for ((name, image) in images) if (getImage(name) == null) addImage(name, image.asAndroidBitmap())
}

private fun markFeatures(marks: List<MapMarker>): List<Feature> = marks.mapIndexed { i, m ->
    Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
        addNumberProperty("order", i)
        addStringProperty("icon", m.icon)
        addNumberProperty("rot", m.rotation)
        addNumberProperty("alpha", m.alpha)
        addStringProperty("anchor", if (m.bottom) "bottom" else "center")
        addBooleanProperty("turns", m.turns)
    }
}

private fun Style.setKavGeometry(g: MapGeometry) {
    addKavImages(g.images)
    val lines = g.lines.filter { it.points.size >= 2 }.mapIndexed { i, l ->
        Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.second, it.first) })).apply {
            addStringProperty("colour", rgba(l.colour))
            addStringProperty("casingColour", rgba(K.bg.copy(alpha = l.colour.alpha)))
            addNumberProperty("width", l.width)
            addNumberProperty("casing", l.casing)
            addBooleanProperty("dashed", l.dashed)
            addNumberProperty("sort", i)
        }
    }
    getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))
    getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures(g.dots)))
    getSourceAs<GeoJsonSource>(SRC_MARKS)?.setGeoJson(FeatureCollection.fromFeatures(markFeatures(g.markers)))
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

private fun Style.setKavLive(g: MapGeometry) {
    addKavImages(g.images)
    getSourceAs<GeoJsonSource>(SRC_LIVE_HALOS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures(g.halos)))
    getSourceAs<GeoJsonSource>(SRC_LIVE_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures(g.dots)))
    getSourceAs<GeoJsonSource>(SRC_LIVE_MARKS)?.setGeoJson(FeatureCollection.fromFeatures(markFeatures(g.markers)))
}

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

internal object Geo {
    const val SIZE = 256
    const val MIN_Z = 8
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

private data class Camera(val worldX: Double, val worldY: Double, val zoom: Float, val rotation: Float = 0f) {
    val pxPerWorld get() = Geo.SIZE * 2.0.pow(zoom.toDouble())
}

data class Follow(val lat: Double, val lon: Double, val bearing: Float, val zoom: Float = 18.5f)

private const val ANCHOR_X = 0.5f
private const val ANCHOR_Y = 0.72f
private const val TILT_DEG = 40f

private fun unrotate(at: Offset, anchor: Offset, rotation: Float): Offset {
    if (rotation == 0f) return at
    val rad = Math.toRadians(rotation.toDouble())
    val c = kotlin.math.cos(rad); val sn = kotlin.math.sin(rad)
    val dx = (at.x - anchor.x).toDouble(); val dy = (at.y - anchor.y).toDouble()
    return Offset((dx * c - dy * sn + anchor.x).toFloat(), (dx * sn + dy * c + anchor.y).toFloat())
}

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

    fun latLon(at: Offset): Pair<Double, Double> {
        val wx = (at.x - width / 2) / pxPerWorld + centerWorldX
        val wy = (at.y - height / 2) / pxPerWorld + centerWorldY
        return Geo.lat(wy) to Geo.lon(wx)
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
    val anchor get() = Offset(left + width * ANCHOR_X, top + height * ANCHOR_Y)
}

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
    private var followed = false
    private var previousPoints = emptyList<WorldPoint>()
    private var previousViewport: Viewport? = null
    private var previousFocus: Any? = null
    private var previousPadding = 0f
    private var previousMaxZoom = Geo.MAX_Z.toFloat()

    fun update(
        points: List<WorldPoint>, viewport: Viewport, focus: Any?, padding: Float, maxZoom: Float, keepZoom: Boolean, scope: CoroutineScope,
    ) {
        if (points.isEmpty() || viewport.w <= 0 || viewport.h <= 0) return
        // Moved by hand, the map only reframes for something new to show, not for a resize.
        val reframe = value == null || followed || previousFocus != focus ||
            (!manual && (previousViewport != viewport || previousPadding != padding || previousMaxZoom != maxZoom))
        followed = false
        val current = destination ?: value
        val changed = points.filterIndexed { index, point -> previousPoints.getOrNull(index) != point }
        previousPoints = points
        previousFocus = focus
        previousViewport = viewport
        previousPadding = padding
        previousMaxZoom = maxZoom
        if (reframe) {
            reset(points, viewport, padding, if (keepZoom) current?.zoom ?: maxZoom else maxZoom, scope)
        } else if (!manual && current != null && changed.any { !viewport.contains(it, current) }) {
            val area = viewport.inset(padding)
            val scale = current.pxPerWorld
            val bounds = listOf(
                WorldPoint(current.worldX + (area.left - area.w / 2) / scale, current.worldY + (area.top - area.h / 2) / scale),
                WorldPoint(current.worldX + (area.right - area.w / 2) / scale, current.worldY + (area.bottom - area.h / 2) / scale),
            )
            moveTo(fitCamera(bounds + changed, viewport, padding, current.zoom), scope)
        }
    }

    fun reset(points: List<WorldPoint>, viewport: Viewport, padding: Float, maxZoom: Float, scope: CoroutineScope) {
        if (points.isEmpty()) return
        manual = false
        moveTo(fitCamera(points, viewport, padding, maxZoom), scope)
    }

    fun centre(point: WorldPoint, scope: CoroutineScope) {
        val current = destination ?: value ?: return
        manual = false
        moveTo(current.copy(worldX = point.x, worldY = point.y, rotation = 0f), scope)
    }

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
        followed = true
        moveTo(goal, scope, duration = 700)
    }

    fun resume() { manual = false }

    private fun moveTo(target: Camera, scope: CoroutineScope, duration: Int = 520) {
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

    fun gesture(centroid: Offset, pan: Offset, zoomChange: Float, twist: Float, viewport: Viewport) {
        val current = value ?: return
        animation?.cancel()
        destination = null
        manual = true
        val zoom = (current.zoom + ln(zoomChange.coerceAtLeast(0.01f)) / ln(2f))
            .coerceIn(Geo.MIN_Z.toFloat(), Geo.MAX_Z + Geo.OVER)
        val rotation = (((current.rotation - twist) % 360f) + 360f) % 360f
        val before = current.pxPerWorld
        val after = Geo.SIZE * 2.0.pow(zoom.toDouble())
        val anchor = viewport.anchor
        fun flat(s: Offset, degrees: Float): Pair<Double, Double> {
            val rad = Math.toRadians(degrees.toDouble())
            val dx = s.x - anchor.x
            val dy = s.y - anchor.y
            val ux = anchor.x + dx * cos(rad) - dy * sin(rad)
            val uy = anchor.y + dx * sin(rad) + dy * cos(rad)
            return (ux - viewport.w / 2) to (uy - viewport.h / 2)
        }
        val (cx, cy) = flat(centroid, current.rotation)
        val (px, py) = flat(centroid + pan, rotation)
        value = Camera(
            current.worldX + cx / before - px / after,
            current.worldY + cy / before - py / after,
            zoom,
            rotation,
        )
    }
}

internal fun focalPadding(anchor: Offset, w: Float, h: Float): DoubleArray = doubleArrayOf(
    (2 * anchor.x - w).coerceAtLeast(0f).toDouble(),
    (2 * anchor.y - h).coerceAtLeast(0f).toDouble(),
    (w - 2 * anchor.x).coerceAtLeast(0f).toDouble(),
    (h - 2 * anchor.y).coerceAtLeast(0f).toDouble(),
)

private fun MapLibreMap.driveTo(cam: Camera, anchor: Offset, w: Float, h: Float, pitch: Float, screenDensity: Float) {
    val wx = cam.worldX + (anchor.x - w / 2) / cam.pxPerWorld
    val wy = cam.worldY + (anchor.y - h / 2) / cam.pxPerWorld
    val pad = focalPadding(anchor, w, h)
    moveCamera(CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(LatLng(Geo.lat(wy), Geo.lon(wx)))
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
    fitMaxZoom: Float = Geo.MAX_Z.toFloat(),
    focusKey: Any? = Unit,
    recenterOn: Pair<Double, Double>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    follow: Follow? = null,
    geometry: MapGeometry? = null,
    live: MapGeometry? = null,
    onTap: ((Offset, MapProjection) -> Unit)? = null,
    onLook: ((centre: Pair<Double, Double>?, reachKm: Double) -> Unit)? = null,
    moved: Boolean = false,
    keepZoom: Boolean = false,
    status: (@Composable () -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
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
    val tilt by animateFloatAsState(if (following) TILT_DEG else 0f,
        tween(if (following) 600 else 280, easing = FastOutSlowInEasing), label = "tilt")

    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx)
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
    LaunchedEffect(map, mapReady, K.look) {
        if (mapReady) map?.setStyle(Style.Builder().fromJson(MapFile.styleJson(ctx, K.light, K.look == Look.OLED))) { style = it }
    }
    fun Style.ensureKavIcons() {
        val px = with(density) { 1.dp.toPx() }
        if (getImage(MAP_ARROW_ICON) == null) addImage(MAP_ARROW_ICON, arrowBitmap(px))
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
    if (live?.halos?.isNotEmpty() == true) {
        val pulse = rememberLivePulse()
        LaunchedEffect(style) {
            val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
            snapshotFlow { pulse.value }.collect { p ->
                if (!s.isFullyLoaded) return@collect
                s.getLayer(HALO_LAYER)?.setProperties(
                    PropertyFactory.circleRadius(Expression.product(Expression.toNumber(Expression.get("r")), Expression.literal(p))),
                )
            }
        }
    }

    BoxWithConstraints(modifier.clipToBounds().background(K.surface1)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val padL = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }.coerceIn(0f, (w - 1).coerceAtLeast(0f))
        val padT = with(density) { contentPadding.calculateTopPadding().toPx() }.coerceIn(0f, (h - 1).coerceAtLeast(0f))
        val padR = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
        val padB = with(density) { contentPadding.calculateBottomPadding().toPx() }
        val viewport = Viewport(padL, padT,
            (w - padR).coerceAtLeast(padL + 1),
            (h - padB).coerceAtLeast(padT + 1), w, h)
        val liveViewport by rememberUpdatedState(viewport)

        LaunchedEffect(worldPoints, viewport, focusKey, padFraction, fitMaxZoom, follow == null, keepZoom) {
            if (follow == null) camera.update(worldPoints, viewport, focusKey, padFraction, fitMaxZoom, keepZoom, scope)
        }
        LaunchedEffect(focusKey) { camera.resume() }
        LaunchedEffect(follow, viewport, camera.manual) {
            if (follow != null && !camera.manual) camera.follow(follow, viewport, scope)
        }
        val anchor = viewport.anchor
        val look by rememberUpdatedState(onLook)
        if (onLook != null) LaunchedEffect(w, h) {
            snapshotFlow { camera.value?.takeIf { camera.manual } }.collectLatest { c ->
                if (c == null) return@collectLatest
                kotlinx.coroutines.delay(600)
                val proj = MapProjection(c.worldX, c.worldY, c.pxPerWorld, w, h)
                val mid = proj.latLon(Offset(w / 2, h / 2))
                val corner = proj.latLon(Offset(0f, 0f))
                look?.invoke(mid, metres(mid.first, mid.second, corner.first, corner.second) / 1000.0)
            }
        }

        LaunchedEffect(map, w, h, anchor) {
            val m = map ?: return@LaunchedEffect
            snapshotFlow { camera.value?.let { it to tilt } }.filterNotNull()
                .collect { (cam, pitch) -> m.driveTo(cam, anchor, w, h, pitch, density.density) }
        }

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        val tap by rememberUpdatedState(onTap)
        Box(
            Modifier.fillMaxSize()
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
                    detectTransformGestures(panZoomLock = true) { centroid, panChange, zoomChange, twist ->
                        camera.gesture(centroid, panChange, zoomChange, twist, liveViewport)
                    }
                },
        )

        if (!mapReady) MapDownloadCard(
            Modifier.align(Alignment.TopStart)
                .padding(start = with(density) { padL.toDp() }, top = with(density) { padT.toDp() })
                .size(with(density) { (w - padL - padR).coerceAtLeast(1f).toDp() },
                    with(density) { (h - padT - padB).coerceAtLeast(1f).toDp() }),
        )

        // One row, so a long status wraps instead of running under the buttons.
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(start = contentPadding.calculateStartPadding(layoutDirection) + K.gap2,
                    top = contentPadding.calculateTopPadding() + K.gap2,
                    end = contentPadding.calculateEndPadding(layoutDirection) + K.gap2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) { status?.invoke() }
            controls?.invoke()
            if (camera.manual || moved) Text(
                if (follow != null) T("Follow", "עקבו") else T("Reset", "איפוס"),
                fontSize = 11.sp, color = K.text,
                modifier = Modifier.panel(999.dp)
                    .clickable {
                        look?.invoke(null, 0.0)
                        val me = recenterOn?.takeIf { it.first.isFinite() && it.second.isFinite() }
                        when {
                            follow != null -> camera.resume()
                            me != null -> camera.centre(WorldPoint(Geo.x(me.second), Geo.y(me.first)), scope)
                            else -> camera.reset(worldPoints, viewport, padFraction, fitMaxZoom, scope)
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

@Composable
private fun MapDownloadCard(modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(K.gap4).panel(14.dp).padding(K.gap4),
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
                    ) { Text(if (s is MapFile.State.Failed) T("Try again", "נסו שוב") else T("Download", "הורדה"), fontSize = 13.sp, color = K.onAccent, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}
