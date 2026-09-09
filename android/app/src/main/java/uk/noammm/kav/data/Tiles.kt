package uk.noammm.kav.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/**
 * Slippy-map tiles for the route maps.
 *
 * Moovit's map is Google's, their SDK, their tiles, their billing. Kav cannot ship
 * that, so the basemap is Esri's World Dark Gray Canvas: no key, no Play Services, and
 * already dark enough to sit under this app without shouting. (An earlier revision used
 * CARTO's dark_all, which now stamps "API KEY REQUIRED" across every tile.)
 *
 * Good manners for a tile service, and cheap: a named User-Agent, a bounded zoom range,
 * a memory cache and a disk cache under the app's own filesDir. Attribution is drawn on
 * every map that uses these.
 */
object Tiles {
    const val ATTRIBUTION = "Esri · HERE · Garmin · © OpenStreetMap"

    /**
     * Esri splits its canvas into two rasters: the BASE has the geometry and no words on
     * it at all, and LABELS is a transparent overlay carrying the street and city names.
     * Drawing only the base is what makes a map look like a satellite photo of nothing,
     * you have to composite both, which is what every "dark canvas" map you have seen
     * actually is.
     */
    enum class Layer(val service: String) {
        BASE("World_Dark_Gray_Base"),
        LABELS("World_Dark_Gray_Reference"),
    }
    private const val UA = "Kav/0.3 (open-source transit client; github.com/ImNoammm)"
    const val SIZE = 256
    const val MIN_Z = 8

    /**
     * Esri's Dark Gray Base stops at 16 over Israel: ask for 17 and you get back a
     * 2.5 KB placeholder reading "Map data not yet available", which is worse than no
     * tile at all. Zooming past this is still allowed, [parentOf] supplies the tile
     * from the level above, upscaled, so it goes soft rather than blank.
     */
    const val MAX_Z = 16

    /** Fractional tile coordinates, the same projection the route is drawn in. */
    fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    fun tileY(lat: Double, z: Int): Double {
        val r = lat * PI / 180.0
        return (1.0 - asinh(tan(r)) / PI) / 2.0 * (1 shl z)
    }

    private val mem = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 220
    })

    const val RETRY_DELAY_MS = 30_000L
    private val misses = Collections.synchronizedMap(HashMap<String, Long>())
    // Shared by all maps: a cancelled camera move cannot start another unbounded
    // batch, and maps requesting the same tile cannot race its disk write.
    private val fetchSlots = Semaphore(4)
    private val fetchLocks = Array(32) { Mutex() }

    private fun key(l: Layer, z: Int, x: Int, y: Int) = "${l.name}/$z/$x/$y"

    fun cached(z: Int, x: Int, y: Int, layer: Layer = Layer.BASE): ImageBitmap? = mem[key(layer, z, x, y)]

    /** Back off after a failure, but allow a recovered connection to fill the map. */
    fun failed(z: Int, x: Int, y: Int, layer: Layer = Layer.BASE): Boolean {
        val key = key(layer, z, x, y)
        return synchronized(misses) {
            val until = misses[key] ?: return@synchronized false
            if (System.nanoTime() < until) true else { misses.remove(key); false }
        }
    }

    /** An ancestor tile standing in for a missing one: which image, and where in it. */
    class Fill(val img: ImageBitmap, val fx: Float, val fy: Float, val frac: Float)

    /**
     * The nearest cached ancestor of a tile, with the sub-rectangle of it that covers
     * that tile. Lets a map draw something immediately while the right tiles arrive,
     * and lets it keep drawing past the service's own maximum zoom, soft, not blank.
     */
    fun parentOf(z: Int, x: Int, y: Int, layer: Layer = Layer.BASE, maxUp: Int = 4): Fill? {
        var zz = z; var xx = x; var yy = y
        var fx = 0f; var fy = 0f; var frac = 1f
        repeat(maxUp) {
            if (zz <= MIN_Z) return null
            // where this tile sits inside its immediate parent, folded into the offset
            frac /= 2f
            fx = fx / 2f + (xx and 1) * 0.5f
            fy = fy / 2f + (yy and 1) * 0.5f
            zz--; xx /= 2; yy /= 2
            cached(zz, xx, yy, layer)?.let { return Fill(it, fx, fy, frac) }
        }
        return null
    }

    /** Disk first, then cancellable, bounded network IO. */
    suspend fun fetch(ctx: Context, z: Int, x: Int, y: Int, layer: Layer = Layer.BASE): ImageBitmap? {
        val key = key(layer, z, x, y)
        mem[key]?.let { return it }
        if (z !in MIN_Z..MAX_Z || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        if (failed(z, x, y, layer)) return null
        return fetchLocks[(key.hashCode() and Int.MAX_VALUE) % fetchLocks.size].withLock {
            mem[key]?.let { return@withLock it }
            if (failed(z, x, y, layer)) return@withLock null
            fetchSlots.withPermit {
                withContext(Dispatchers.IO) { fetchUncached(ctx, z, x, y, layer, key) }
            }
        }
    }

    private suspend fun fetchUncached(ctx: Context, z: Int, x: Int, y: Int, layer: Layer, key: String): ImageBitmap? {
        currentCoroutineContext().ensureActive()
        // filesDir survives Android clearing the cache under storage pressure.
        val dir = File(ctx.filesDir, "tiles-esri").apply { mkdirs() }
        val f = File(dir, "${layer.name}_${z}_${x}_$y.png")
        if (f.exists() && f.length() > 0) {
            val image = runCatching { decode(f.readBytes()) }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (image != null) { mem[key] = image; return image }
            f.delete()
        }

        val bytes = try {
            download(
                "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/" +
                    "${layer.service}/MapServer/tile/$z/$y/$x"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        currentCoroutineContext().ensureActive()
        if (bytes == null) {
            misses[key] = System.nanoTime() + RETRY_DELAY_MS * 1_000_000L
            return null
        }
        val img = decode(bytes) ?: run {
            misses[key] = System.nanoTime() + RETRY_DELAY_MS * 1_000_000L
            return null
        }
        currentCoroutineContext().ensureActive()
        runCatching { f.writeBytes(bytes) }
        mem[key] = img
        return img
    }

    private suspend fun download(url: String): ByteArray? = suspendCancellableCoroutine { continuation ->
        val connection = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection's blocking read does not reliably react to interruption.
        // Disconnect as soon as the requesting view is cancelled, including mid-read.
        continuation.invokeOnCancellation { connection.disconnect() }
        try {
            if (continuation.isActive) {
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", UA)
                val bytes = if (connection.responseCode == 200) connection.inputStream.use { it.readBytes() } else null
                continuation.resume(bytes)
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        } finally {
            connection.disconnect()
        }
    }

    /** Bytes currently held on disk, so Settings can report it honestly. */
    fun diskBytes(ctx: Context): Long =
        File(ctx.filesDir, "tiles-esri").listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDisk(ctx: Context) {
        File(ctx.filesDir, "tiles-esri").listFiles()?.forEach { it.delete() }
        mem.clear(); misses.clear()
    }

    private fun decode(b: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
}
