package uk.noammm.kav.data

import uk.noammm.kav.ui.T
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The basemap is a single PMTiles archive of Israel, Protomaps' OpenStreetMap build,
 * cut to the country, rendered on the phone by MapLibre. Once this file is here the
 * map asks the network for nothing, ever: no tile service sees where you look.
 *
 * The archive is too big to put inside the APK, so it rides the same channel updates
 * do: an asset on a GitHub release. The release is marked as a pre-release, which
 * keeps it out of `releases/latest` and therefore out of the updater's way.
 */
object MapFile {
    /** The release tag holding the archive; a new map is a new tag and a new [BYTES]. */
    const val TAG = "map-1"
    const val URL = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download/$TAG/israel.pmtiles"

    /** Exact size of the archive, so progress and "is it whole" need no server call. */
    const val BYTES = 185_001_087L

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data class Failed(val why: String) : State
        data object Ready : State
    }

    var state by mutableStateOf<State>(State.Missing)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(ctx: Context) = File(File(ctx.filesDir, "map"), "israel.pmtiles")

    /** Look once at launch; also sweep away the raster tile cache earlier builds left behind. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        // A recreated activity calls this while a download the process kept may still
        // be streaming; overwriting its state would offer a second, interleaved writer.
        if (state !is State.Downloading) state = if (file(app).length() == BYTES) State.Ready else State.Missing
        scope.launch {
            File(app.filesDir, "tiles-esri").listFiles()?.forEach { it.delete() }
            File(app.filesDir, "tiles-esri").delete()
        }
    }

    /** The bundled style, pointed at wherever this phone keeps the archive. */
    fun styleJson(ctx: Context): String =
        ctx.assets.open("map/style.json").reader().use { it.readText() }
            .replace("__MAP__", "pmtiles://file://" + file(ctx).absolutePath)

    /**
     * Fetch the archive, resuming a part file if one was left. Runs in MapFile's own
     * scope: leaving the screen that started a 185 MB download must not abandon it.
     */
    fun startDownload(ctx: Context) {
        if (state is State.Downloading || state is State.Ready) return
        val app = ctx.applicationContext
        state = State.Downloading(0f)
        scope.launch {
            try {
                download(app)
                state = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavMap", "map download failed", e)
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun download(ctx: Context) {
        val file = file(ctx)
        file.parentFile?.mkdirs()
        if (file.length() == BYTES) return
        val part = File(file.path + ".part")
        var have = part.length()
        // a whole part is a finished download whose rename was interrupted, keep it
        if (have == BYTES && part.renameTo(file)) return
        if (have >= BYTES) { part.delete(); have = 0 }

        var c = URL(URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kav")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        // GitHub hands assets off to another host; follow within https by hand, the
        // way Updates.download does, keeping the Range header on the hop.
        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(URL(URL), next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Kav")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            hops++
        }
        when (c.responseCode) {
            200 -> { have = 0; part.delete() } // the server ignored the range
            206 -> {}
            else -> throw RuntimeException(T("Download HTTP ${c.responseCode}", "הורדה נכשלה, שגיאת HTTP ${c.responseCode}"))
        }
        c.inputStream.use { input ->
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = have
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    state = State.Downloading((done.toFloat() / BYTES).coerceIn(0f, 1f))
                }
            }
        }
        if (part.length() != BYTES) throw RuntimeException(T("Interrupted at ${part.length() / (1 shl 20)} MB, try again", "ההורדה נקטעה אחרי ${part.length() / (1 shl 20)} MB, נסו שוב"))
        if (!part.renameTo(file)) throw RuntimeException(T("Could not keep the download", "לא ניתן היה לשמור את ההורדה"))
    }

    /** Free the space; the map goes back to offering its download. */
    fun remove(ctx: Context) {
        file(ctx).delete()
        File(file(ctx).path + ".part").delete()
        state = State.Missing
    }
}
