package uk.noammm.kav.data

import uk.noammm.kav.ui.T
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object MapFile {
    const val TAG = "map-1"
    const val URL = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download/$TAG/israel.pmtiles"

    const val BYTES = 185_001_087L

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data class Failed(val why: String) : State
        data object Ready : State
    }

    var state by mutableStateOf<State>(State.Missing)
        private set

    // The map Kav draws: its own, from the file on the phone; satellite photos with its names on them; or its own with
    // the hills shaded. The photos and the hills load online, as the map is looked at.
    enum class Type { STANDARD, SATELLITE, TERRAIN }

    var type by mutableStateOf(Type.STANDARD)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(ctx: Context) = File(File(ctx.filesDir, "map"), "israel.pmtiles")

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (state !is State.Downloading) state = if (file(app).length() == BYTES) State.Ready else State.Missing
        scope.launch {
            File(app.filesDir, "tiles-esri").listFiles()?.forEach { it.delete() }
            File(app.filesDir, "tiles-esri").delete()
        }
    }

    // OLED draws the map like a battery saver: black ground, grey roads, no buildings or land colours. Satellite keeps
    // the dark map's names, light with a dark edge, which read on any photo.
    fun styleJson(ctx: Context, light: Boolean = false, oled: Boolean = false, type: Type = Type.STANDARD): String {
        val name = when {
            type == Type.SATELLITE -> "map/style.json"
            light -> "map/style-light.json"
            oled -> "map/style-oled.json"
            else -> "map/style.json"
        }
        val json = ctx.assets.open(name).reader().use { it.readText() }
            .replace("__MAP__", "pmtiles://file://" + file(ctx).absolutePath)
        return when (type) {
            Type.STANDARD -> json
            Type.SATELLITE -> satellite(JSONObject(json)).toString()
            Type.TERRAIN -> terrain(JSONObject(json), light).toString()
        }
    }

    fun attribution(type: Type): String = when (type) {
        Type.STANDARD -> "Protomaps · © OpenStreetMap"
        Type.SATELLITE -> "Esri, Vantor, Earthstar Geographics · Protomaps · © OpenStreetMap"
        Type.TERRAIN -> "Protomaps · © OpenStreetMap · terrain USGS, NOAA, Mapzen"
    }

    // Esri's World Imagery needs no key and has photos to zoom 19 over Israel.
    private const val PHOTOS = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    // Mapzen's terrain on AWS's open data: heights to zoom 15, from USGS (SRTM, GMTED2010) and NOAA (ETOPO1) here.
    private const val HEIGHTS = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    // The photos under the map's names: the land, water, buildings and roads drawn on top would hide what the photo
    // shows, so only the background and the names stay.
    internal fun satellite(style: JSONObject): JSONObject {
        style.getJSONObject("sources").put("satellite", JSONObject()
            .put("type", "raster").put("tiles", JSONArray().put(PHOTOS)).put("tileSize", 256).put("maxzoom", 19))
        val layers = style.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            when (layer.optString("type")) {
                "background" -> {
                    kept.put(layer)
                    kept.put(JSONObject().put("id", "satellite").put("type", "raster").put("source", "satellite"))
                }
                "symbol" -> if (layer.optString("id") != "roads_oneway") kept.put(onPhotos(layer))
            }
        }
        return style.put("layers", kept)
    }

    // The black map's names are dark grey, which a photo swallows: white with a dark edge instead. Road shields keep
    // their own plate.
    private fun onPhotos(layer: JSONObject): JSONObject {
        val paint = layer.optJSONObject("paint")?.takeIf { it.has("text-halo-color") } ?: return layer
        paint.put("text-color", "rgba(255,255,255,0.95)").put("text-halo-color", "rgba(0,0,0,0.75)").put("text-halo-width", 1.5)
        return layer
    }

    // Shaded hills over the map's own land, under its water, roads and names.
    internal fun terrain(style: JSONObject, light: Boolean): JSONObject {
        style.getJSONObject("sources").put("terrain", JSONObject()
            .put("type", "raster-dem").put("tiles", JSONArray().put(HEIGHTS)).put("tileSize", 256).put("maxzoom", 15)
            .put("encoding", "terrarium"))
        val hills = JSONObject().put("id", "hills").put("type", "hillshade").put("source", "terrain").put("paint", JSONObject()
            .put("hillshade-exaggeration", if (light) 0.35 else 0.5)
            .put("hillshade-shadow-color", if (light) "rgba(0,0,0,0.35)" else "rgba(0,0,0,0.85)")
            .put("hillshade-highlight-color", if (light) "rgba(255,255,255,0.5)" else "rgba(255,255,255,0.12)")
            .put("hillshade-accent-color", if (light) "rgba(0,0,0,0.15)" else "rgba(0,0,0,0.4)"))
        val layers = style.getJSONArray("layers")
        val at = (0 until layers.length()).firstOrNull { layers.getJSONObject(it).optString("id") == "water" }
            ?: minOf(1, layers.length())
        val out = JSONArray()
        for (i in 0 until layers.length()) {
            if (i == at) out.put(hills)
            out.put(layers.getJSONObject(i))
        }
        if (at >= layers.length()) out.put(hills)
        return style.put("layers", out)
    }

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
        if (have == BYTES && part.renameTo(file)) return
        if (have >= BYTES) { part.delete(); have = 0 }

        var c = URL(URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kav")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
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
            200 -> { have = 0; part.delete() }
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

    fun remove(ctx: Context) {
        file(ctx).delete()
        File(file(ctx).path + ".part").delete()
        state = State.Missing
    }
}
