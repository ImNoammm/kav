package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import uk.noammm.kav.data.Tiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A basemap under the route: pinch to zoom, drag to pan.
 *
 * Tiles are Esri's World Dark Gray Canvas, keyless, already dark, drawn to sit under
 * data. (CARTO's dark style now watermarks every tile "API KEY REQUIRED", and plain OSM
 * tiles are a pale paper map whose roads vanish once desaturated.)
 *
 * Camera position and zoom survive location polls. Explicit focus changes animate a
 * fit; a moving point only expands the view once it leaves the visible area. Animation
 * is read during drawing, while tile requests change only at tile boundaries. Previous
 * base tiles and cached ancestors stay underneath the progressively loaded new view.
 *
 * Given a [Follow], the map turns into the view from the road: the followed point sits
 * low in the frame, the direction of travel is up, and the ground leans away.
 */

/** Where the map is looking. World coordinates are the 0..1 web-mercator square. */
private data class Camera(val worldX: Double, val worldY: Double, val zoom: Float, val rotation: Float = 0f) {
    val pxPerWorld get() = Tiles.SIZE * 2.0.pow(zoom.toDouble())
}

/** A point to keep low in the frame with [bearing] up, where you are, the way you are going. */
class Follow(val lat: Double, val lon: Double, val bearing: Float, val zoom: Float = 16.6f)

/** Where the followed point sits on the screen, as fractions of the map's size. */
private const val ANCHOR_X = 0.5f
private const val ANCHOR_Y = 0.66f
private const val TILT_DEG = 40f

/** What the caller needs to place its own geometry on the map. */
class MapProjection(
    private val centerWorldX: Double,
    private val centerWorldY: Double,
    private val pxPerWorld: Double,
    private val width: Float,
    private val height: Float,
) {
    fun point(lat: Double, lon: Double): Offset {
        val wx = Tiles.tileX(lon, 0)
        val wy = Tiles.tileY(lat, 0)
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
}

/** Fits into the unobscured rectangle; projection coordinates still use the whole canvas. */
private fun fitCamera(points: List<WorldPoint>, viewport: Viewport, padFraction: Float, maxZoom: Float = Tiles.MAX_Z.toFloat()): Camera {
    val area = viewport.inset(padFraction)
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val scaleX = area.width / ((maxX - minX).coerceAtLeast(1e-12) * Tiles.SIZE)
    val scaleY = area.height / ((maxY - minY).coerceAtLeast(1e-12) * Tiles.SIZE)
    val zoom = (ln(minOf(scaleX, scaleY)) / ln(2.0)).toFloat().coerceIn(Tiles.MIN_Z.toFloat(), maxZoom)
    val scale = Tiles.SIZE * 2.0.pow(zoom.toDouble())
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
        val scale = Tiles.SIZE * 2.0.pow(target.zoom.toDouble())
        val tx = Tiles.tileX(target.lon, 0)
        val ty = Tiles.tileY(target.lat.coerceIn(-85.05112878, 85.05112878), 0)
        val anchorX = viewport.w * ANCHOR_X; val anchorY = viewport.h * ANCHOR_Y
        val goal = Camera(
            tx - (anchorX - viewport.w / 2) / scale,
            ty - (anchorY - viewport.h / 2) / scale,
            target.zoom,
            ((target.bearing % 360f) + 360f) % 360f,
        )
        manual = false
        moveTo(goal, scope, duration = 700)
    }

    /** Back on the rails after a manual pan: the next follow or fit takes over. */
    fun resume() { manual = false }

    private fun moveTo(target: Camera, scope: CoroutineScope, duration: Int = 520) {
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
            .coerceIn(Tiles.MIN_Z.toFloat(), Tiles.MAX_Z + 6f)
        val before = current.pxPerWorld
        val after = Tiles.SIZE * 2.0.pow(zoom.toDouble())
        // a drag is in screen directions; the map underneath may be turned
        val rad = Math.toRadians(current.rotation.toDouble())
        val panX = (pan.x * cos(rad) - pan.y * sin(rad)).toFloat()
        val panY = (pan.x * sin(rad) + pan.y * cos(rad)).toFloat()
        val wx = current.worldX + (centroid.x - viewport.w / 2) / before
        val wy = current.worldY + (centroid.y - viewport.h / 2) / before
        value = Camera(
            wx - (centroid.x - viewport.w / 2 + panX) / after,
            wy - (centroid.y - viewport.h / 2 + panY) / after,
            zoom,
            current.rotation,
        )
    }
}

private data class TileId(val z: Int, val x: Int, val y: Int)
private data class TileRequest(val tile: TileId, val layer: Tiles.Layer)
private data class TileRegion(val z: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    fun tiles(): List<TileId> = buildList {
        for (x in x0..x1) for (y in y0..y1) add(TileId(z, x, y))
    }.sortedBy { (it.x - (x0 + x1) / 2.0).pow(2) + (it.y - (y0 + y1) / 2.0).pow(2) }

    fun overlaps(tile: TileId): Boolean {
        val scale = 2.0.pow((z - tile.z).toDouble())
        return tile.x * scale < x1 + 1 && (tile.x + 1) * scale > x0 &&
            tile.y * scale < y1 + 1 && (tile.y + 1) * scale > y0
    }
}

/**
 * The tiles a screen needs. A turned map shows a rectangle turned the other way in
 * tile space, so the region is the box round that; a tilted one shows past its own
 * edges, so the box grows by [spread] on every side.
 */
private fun tileRegion(camera: Camera, w: Float, h: Float, spread: Float = 0f): TileRegion {
    val z = camera.zoom.roundToInt().coerceIn(Tiles.MIN_Z, Tiles.MAX_Z)
    val n = 1 shl z
    val tilePx = camera.pxPerWorld / n
    val ax = w * ANCHOR_X; val ay = h * ANCHOR_Y
    val rad = Math.toRadians(camera.rotation.toDouble())
    var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
    for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
        val dx = cx - ax; val dy = cy - ay
        val x = (ax + dx * cos(rad) - dy * sin(rad)).toFloat()
        val y = (ay + dx * sin(rad) + dy * cos(rad)).toFloat()
        left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
    }
    val padX = (right - left) * spread; val padY = (bottom - top) * spread
    left -= padX; right += padX; top -= padY; bottom += padY
    fun tx(x: Float) = camera.worldX * n + (x - w / 2) / tilePx
    fun ty(y: Float) = camera.worldY * n + (y - h / 2) / tilePx
    return TileRegion(z,
        floor(tx(left)).toInt().coerceIn(0, n - 1), floor(ty(top)).toInt().coerceIn(0, n - 1),
        (ceil(tx(right)).toInt() - 1).coerceIn(0, n - 1),
        (ceil(ty(bottom)).toInt() - 1).coerceIn(0, n - 1))
}

private class TilePatch(val tile: TileId, val image: ImageBitmap, val source: IntOffset, val sourceSize: IntSize)
private class RenderTiles(
    val retained: List<TilePatch> = emptyList(),
    val base: List<TilePatch> = emptyList(),
    val labels: List<TilePatch> = emptyList(),
)

private fun cachedPatch(tile: TileId, layer: Tiles.Layer): TilePatch? {
    Tiles.cached(tile.z, tile.x, tile.y, layer)?.let {
        return TilePatch(tile, it, IntOffset.Zero, IntSize(it.width, it.height))
    }
    val fill = Tiles.parentOf(tile.z, tile.x, tile.y, layer) ?: return null
    return TilePatch(tile, fill.img,
        IntOffset((fill.img.width * fill.fx).roundToInt(), (fill.img.height * fill.fy).roundToInt()),
        IntSize((fill.img.width * fill.frac).roundToInt().coerceAtLeast(1), (fill.img.height * fill.frac).roundToInt().coerceAtLeast(1)))
}

private fun resolveTiles(region: TileRegion, tiles: List<TileId>, previous: RenderTiles): RenderTiles {
    val base = tiles.mapNotNull { cachedPatch(it, Tiles.Layer.BASE) }
    val covered = base.mapTo(HashSet()) { it.tile }
    val retained = (previous.retained + previous.base).associateBy { it.tile }.values
        .filter { it.tile !in covered && region.overlaps(it.tile) }.takeLast(96)
    return RenderTiles(retained, base, tiles.mapNotNull { cachedPatch(it, Tiles.Layer.LABELS) })
}

private fun tileRequests(tiles: List<TileId>): List<TileRequest> {
    // A handful of coarse bases cover a cold view quickly. Detailed geometry and
    // labels follow, instead of the old unbounded three-level prefetch sweep.
    val parents = tiles.filter { cachedPatch(it, Tiles.Layer.BASE) == null && it.z > Tiles.MIN_Z }
        .map { val shift = minOf(2, it.z - Tiles.MIN_Z); TileId(it.z - shift, it.x shr shift, it.y shr shift) }
        .distinct().map { TileRequest(it, Tiles.Layer.BASE) }
    // Pinching out used to flash the background: the coarser level had never been
    // fetched, so neither it nor the four-deep ancestor walk had anything to scale
    // up, and everything outside the retained fine tiles fell through to bare
    // surface. Once the current view is served, warm the levels a pinch out lands
    // on. The ring matters, two levels up the screen spans four times the world,
    // so the ancestors of what is on screen now cover only the middle of it.
    val warm = buildList {
        for ((shift, ring) in listOf(2 to 1, 4 to 0)) {
            val coarse = tiles.filter { it.z - shift >= Tiles.MIN_Z }
                .map { TileId(it.z - shift, it.x shr shift, it.y shr shift) }
                .distinct()
            val span = 1 shl (coarse.firstOrNull() ?: continue).z
            for (t in coarse) for (dx in -ring..ring) for (dy in -ring..ring) {
                val x = t.x + dx; val y = t.y + dy
                if (x in 0 until span && y in 0 until span) add(TileId(t.z, x, y))
            }
        }
    }.distinct().map { TileRequest(it, Tiles.Layer.BASE) }
    // warm last: the view you are actually looking at is still served first
    return parents + Tiles.Layer.entries.flatMap { layer -> tiles.map { TileRequest(it, layer) } } + warm
}

private fun DrawScope.paintTiles(patches: List<TilePatch>, camera: Camera, filter: ColorFilter, alpha: Float = 1f, margin: Float = 0f) {
    val scale = camera.pxPerWorld
    for (patch in patches) {
        val tile = patch.tile
        val n = (1 shl tile.z).toDouble()
        val tilePx = scale / n
        val left = (tile.x / n - camera.worldX) * scale + size.width / 2
        val top = (tile.y / n - camera.worldY) * scale + size.height / 2
        if (left >= size.width + margin || top >= size.height + margin || left + tilePx <= -margin || top + tilePx <= -margin) continue
        val x = floor(left).toInt(); val y = floor(top).toInt()
        drawImage(patch.image, srcOffset = patch.source, srcSize = patch.sourceSize,
            dstOffset = IntOffset(x, y), dstSize = IntSize(ceil(left + tilePx).toInt() - x, ceil(top + tilePx).toInt() - y),
            alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.Low)
    }
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
    animatedOverlay: DrawScope.(MapProjection) -> Unit = {},
    overlay: DrawScope.(MapProjection) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val camera = remember { MapCamera() }
    var rendered by remember { mutableStateOf(RenderTiles()) }
    val filter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val worldPoints = remember(points) {
        points.filter { it.first.isFinite() && it.second.isFinite() }.map {
            WorldPoint(Tiles.tileX(it.second, 0), Tiles.tileY(it.first.coerceIn(-85.05112878, 85.05112878), 0))
        }
    }
    val following = follow != null && !camera.manual
    // the lean: on while following, flat the moment a finger takes over
    val tilt by animateFloatAsState(if (following) TILT_DEG else 0f, tween(600, easing = FastOutSlowInEasing), label = "tilt")

    BoxWithConstraints(modifier.clipToBounds().background(K.surface1)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val viewport = with(density) {
            val left = contentPadding.calculateLeftPadding(layoutDirection).toPx().coerceIn(0f, (w - 1).coerceAtLeast(0f))
            val top = contentPadding.calculateTopPadding().toPx().coerceIn(0f, (h - 1).coerceAtLeast(0f))
            Viewport(left, top,
                (w - contentPadding.calculateRightPadding(layoutDirection).toPx()).coerceAtLeast(left + 1),
                (h - contentPadding.calculateBottomPadding().toPx()).coerceAtLeast(top + 1), w, h)
        }
        val liveViewport by rememberUpdatedState(viewport)

        LaunchedEffect(worldPoints, viewport, focusKey, padFraction, follow == null) {
            if (follow == null) camera.update(worldPoints, viewport, focusKey, padFraction, scope)
        }
        LaunchedEffect(follow, viewport, camera.manual) {
            if (follow != null && !camera.manual) camera.follow(follow, viewport, scope)
        }

        LaunchedEffect(ctx, w, h) {
            snapshotFlow { camera.value?.let { tileRegion(it, w, h, if (tilt > 1f) 0.45f else 0f) } }.filterNotNull().collectLatest { region ->
                val tiles = region.tiles()
                fun refresh() { rendered = resolveTiles(region, tiles, rendered) }
                refresh()
                do {
                    val requests = ArrayDeque(tileRequests(tiles).filter { (t, layer) ->
                        Tiles.cached(t.z, t.x, t.y, layer) == null && !Tiles.failed(t.z, t.x, t.y, layer)
                    })
                    coroutineScope {
                        repeat(minOf(4, requests.size)) {
                            launch {
                                while (true) {
                                    ensureActive()
                                    val (t, layer) = requests.removeFirstOrNull() ?: break
                                    if (Tiles.fetch(ctx, t.z, t.x, t.y, layer) != null) refresh()
                                }
                            }
                        }
                    }
                    val missing = tiles.any { t -> Tiles.Layer.entries.any { Tiles.cached(t.z, t.x, t.y, it) == null } }
                    if (!missing) break
                    delay(Tiles.RETRY_DELAY_MS)
                } while (true)
            }
        }

        // Retain tiles and route geometry while only the live marker layer pulses.
        // Each layer observes camera changes in draw, without recomposing the map.
        // The three layers share one turn and one lean, so what is drawn on the map
        // stays on the map.
        val margin = if (tilt > 1f) max(w, h) else 0f
        fun DrawScope.turned(block: DrawScope.() -> Unit) {
            val current = camera.value ?: return
            rotate(-current.rotation, Offset(size.width * ANCHOR_X, size.height * ANCHOR_Y)) { block() }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                rotationX = tilt
                cameraDistance = 14f
                transformOrigin = TransformOrigin(ANCHOR_X, ANCHOR_Y)
                val grow = 1f + (tilt / TILT_DEG) * 0.45f
                scaleX = grow; scaleY = grow
            },
        ) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer().pointerInput(camera) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        camera.gesture(centroid, panChange, zoomChange, liveViewport)
                    }
                },
            ) {
                val current = camera.value ?: return@Canvas
                val tiles = rendered
                turned {
                    paintTiles(tiles.retained, current, filter, margin = margin)
                    paintTiles(tiles.base, current, filter, margin = margin)
                    paintTiles(tiles.labels, current, filter, 0.85f, margin = margin)
                }
            }

            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                val current = camera.value ?: return@Canvas
                turned { overlay(MapProjection(current.worldX, current.worldY, current.pxPerWorld, size.width, size.height)) }
            }

            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                val current = camera.value ?: return@Canvas
                turned { animatedOverlay(MapProjection(current.worldX, current.worldY, current.pxPerWorld, size.width, size.height)) }
            }
        }

        if (camera.manual) {
            Text(
                if (follow != null) "Follow" else "Reset",
                fontSize = 11.sp, color = K.text,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = contentPadding.calculateTopPadding() + K.gap2,
                        end = contentPadding.calculateEndPadding(layoutDirection) + K.gap2)
                    .clip(RoundedCornerShape(999.dp)).background(K.plateStrong)
                    .clickable {
                        val me = recenterOn?.takeIf { it.first.isFinite() && it.second.isFinite() }
                        when {
                            follow != null -> camera.resume()
                            me != null -> camera.centre(
                                WorldPoint(
                                    Tiles.tileX(me.second, 0),
                                    Tiles.tileY(me.first.coerceIn(-85.05112878, 85.05112878), 0),
                                ),
                                scope,
                            )
                            else -> camera.reset(worldPoints, viewport, padFraction, scope)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Text(
            Tiles.ATTRIBUTION,
            fontSize = 8.sp, color = K.dim,
            modifier = Modifier.align(Alignment.BottomEnd).alpha(0.8f)
                .padding(end = contentPadding.calculateEndPadding(layoutDirection) + 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 3.dp),
        )
    }
}
