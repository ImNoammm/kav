package uk.noammm.kav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// A stop's photo: Google's Street View panorama nearest the stop, turned to face it (StreetPano, facing), as one still
// frame. Asked only while photos are on, and kept on the phone, so a stop is asked about once in a long while.
internal object StopPhotos {
    var enabled by mutableStateOf(true)

    class Photo(val bitmap: Bitmap, val year: Int, val month: Int)

    private const val THUMB_PX = 240
    private const val FULL_W = 1024
    private const val FULL_H = 640
    private const val FOV = 90
    private const val KEEP_DAYS = 60L
    private const val KEEP_NONE_DAYS = 7L

    private var dir: File? = null
    private val gate = Semaphore(3)
    private val thumbs = object : LruCache<String, Photo>(12 * 1024) {
        override fun sizeOf(key: String, value: Photo) = value.bitmap.byteCount / 1024
    }

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "stop-photos").apply { mkdirs() } }

    // One file name per place and way of looking.
    internal fun key(look: StreetLook): String {
        val t = look.target ?: look.at
        return String.format(Locale.US, "%.5f_%.5f_%.5f_%.5f_%d", look.at.first, look.at.second, t.first, t.second, look.heading ?: -1)
            .replace('-', 'm').replace('.', 'p')
    }

    internal fun frameUrl(pano: String, heading: Int, w: Int, h: Int): String = String.format(
        Locale.US,
        "https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=%s&cb_client=maps_sv.tactile.gps&w=%d&h=%d&yaw=%d&pitch=0&thumbfov=%d",
        java.net.URLEncoder.encode(pano, "UTF-8"), w, h, heading, FOV,
    )

    fun thumbNow(look: StreetLook): Photo? = thumbs.get(key(look))

    // The stop's photo, small; null where Google has no panorama near it. Throws when Google couldn't be asked.
    suspend fun thumb(look: StreetLook): Photo? = withContext(Dispatchers.IO) {
        val k = key(look)
        thumbs.get(k)?.let { return@withContext it }
        frame(look, k, THUMB_PX, THUMB_PX)?.also { thumbs.put(k, it) }
    }

    // The same, as large as the screen shows it.
    suspend fun full(look: StreetLook): Photo? = withContext(Dispatchers.IO) { frame(look, key(look) + "_full", FULL_W, FULL_H) }

    private suspend fun frame(look: StreetLook, k: String, w: Int, h: Int): Photo? {
        val folder = dir ?: throw IOException("Stop photos are not initialized")
        val file = File(folder, "$k.jpg")
        val none = File(folder, "$k.none")
        val now = System.currentTimeMillis()
        if (none.exists() && now - none.lastModified() < KEEP_NONE_DAYS * 86_400_000L) return null
        if (file.exists() && now - file.lastModified() < KEEP_DAYS * 86_400_000L) {
            read(file)?.let { return it }
        }
        return gate.withPermit {
            val pano = StreetPano.of(look)
            if (pano == null) {
                none.writeText("")
                return@withPermit null
            }
            val heading = facing(pano.lat to pano.lon, look) ?: 0
            val bytes = get(frameUrl(pano.id, heading, w, h))
            file.writeBytes(bytes)
            File(folder, "$k.date").writeText("${pano.year} ${pano.month}")
            none.delete()
            read(file) ?: throw IOException("Street View sent no image")
        }
    }

    private fun read(file: File): Photo? {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val date = File(file.parentFile, file.nameWithoutExtension + ".date").takeIf { it.exists() }?.readText()
            ?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        return Photo(bmp, date.getOrElse(0) { 0 }, date.getOrElse(1) { 0 })
    }

    private fun get(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
        try {
            if (c.responseCode != 200) throw IOException("Street View image: HTTP ${c.responseCode}")
            if (c.contentType?.startsWith("image/") != true) throw IOException("Street View sent no image")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
