package uk.noammm.kav.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// A stop, or the place a trip ends, to look at in Kav: Google's Street View facing it, or Kav's own map where Google has
// none.
internal class StopLook(val look: StreetLook, val name: String, val detail: String? = null)

internal object StreetViewer {
    var open by mutableStateOf<StopLook?>(null)
}

// Google's own panorama nearest a spot, asked the way Google's maps script asks it: no key, no account, no cookies.
// Only the spot goes out, and only when someone opens Street View. Photospheres people upload are left out: they
// come back from anywhere in town and are seldom of the street.
internal object StreetPano {
    class Pano(val id: String, val lat: Double, val lon: Double, val year: Int = 0, val month: Int = 0)

    private const val SEARCH = "https://maps.googleapis.com/\$rpc/google.internal.maps.mapsjs.v1.MapsJsInternalService/SingleImageSearch"

    fun body(lat: Double, lon: Double, radius: Double): String = String.format(
        Locale.US,
        "[[\"apiv3\",null,null,null,\"US\"],[[null,null,%.6f,%.6f],%.1f],[null,[\"en\",\"US\"],null,null,null,null," +
            "null,null,[2],null,[[[2,true,2]]]],[[1,2,3,4,6,8,12],null,null,null,[],[]]]",
        lat, lon, radius,
    )

    // [[0],[[1],[2,id],...,[[[1],[[null,null,lat,lon],...]]],[...,[year,month]]...]] when there is one; [[5,...,"...no images."]] when not.
    fun parse(json: String): Pano? {
        val a = JSONArray(json)
        if (a.optJSONArray(0)?.optInt(0, -1) != 0) return null
        val p = a.optJSONArray(1) ?: return null
        val kind = p.optJSONArray(1) ?: return null
        if (kind.optInt(0) != 2) return null
        val id = kind.optString(1).takeIf { it.isNotBlank() } ?: return null
        val at = p.optJSONArray(5)?.optJSONArray(0)?.optJSONArray(1)?.optJSONArray(0) ?: return null
        val lat = at.optDouble(2); val lon = at.optDouble(3)
        if (lat.isNaN() || lon.isNaN()) return null
        val date = p.optJSONArray(6)?.optJSONArray(7)
        return Pano(id, lat, lon, date?.optInt(0) ?: 0, date?.optInt(1) ?: 0)
    }

    // Null when Google has none within radius metres; throws when Google couldn't be asked.
    fun near(lat: Double, lon: Double, radius: Double): Pano? {
        val c = (URL(SEARCH).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json+protobuf")
        }
        try {
            c.outputStream.use { it.write(body(lat, lon, radius).toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException("Street View search: HTTP ${c.responseCode}")
            return parse(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }

    // From where the look stands; failing that, a little further out, for a station set back from the road.
    fun of(look: StreetLook): Pano? = near(look.at.first, look.at.second, 50.0) ?: near(look.at.first, look.at.second, 120.0)
}

// Which way to face from the panorama Google has: towards what the look is of, when the panorama isn't on top of it,
// else the look's own way (a stop on the road is faced from the side, where its kerb is).
internal fun facing(pano: Pair<Double, Double>, look: StreetLook): Int? {
    val t = look.target ?: return look.heading
    if (metres(pano.first, pano.second, t.first, t.second) < 3) return look.heading
    return Math.floorMod(bearing(pano.first, pano.second, t.first, t.second).roundToInt(), 360)
}

// Google's Street View embed, which needs no key: the panorama by its id, looking where Kav says, a little wider than
// Google's default so more of the street fits a phone held upright. Each message counts the fields under it. lang: the
// language Google labels the street in ("en", "he"); without it Google picks one itself, which can be Arabic on a phone
// in English.
internal fun streetEmbedUrl(pano: String?, at: Pair<Double, Double>, heading: Int?, lang: String? = null): String {
    val inner = listOfNotNull(
        pano?.let { "!1s$it" }, "!2m2",
        String.format(Locale.US, "!1d%.6f", at.first), String.format(Locale.US, "!2d%.6f", at.second),
        heading?.let { "!3f$it" }, "!4f0", "!5f0.8",
    )
    return "https://www.google.com/maps/embed?pb=!4v1!6m${inner.size + 1}!1m${inner.size}" + inner.joinToString("") +
        (lang?.let { "!5m2!1s$it!2sil" } ?: "")
}

// The embed only works inside a frame. No referrer goes to Google, nor the means to go full screen, which Kav already is.
// The frame may read the phone's motion and compass: without the compass Chrome gives a frame no absolute orientation.
// ground: the page's colour behind the frame, the screen's own, until the panorama paints. The frame's load tells Kav
// (KavHost), a second later, that the panorama is up.
internal fun streetEmbedPage(url: String, ground: String = "#000"): String =
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">" +
        "<style>html,body{margin:0;height:100%;background:$ground;overflow:hidden}" +
        "iframe{position:fixed;top:0;left:0;border:0;width:100%;height:100%}</style></head>" +
        "<body><iframe src=\"$url\" onload=\"setTimeout(function(){window.KavHost&&KavHost.loaded()},1000)\" " +
        "allow=\"accelerometer; gyroscope; magnetometer\" referrerpolicy=\"no-referrer\"></iframe></body></html>"

// A point m metres from at, the way bearing (degrees clockwise from north) points.
internal fun offsetBy(at: Pair<Double, Double>, bearing: Float, m: Double): Pair<Double, Double> {
    val r = Math.toRadians(bearing.toDouble())
    return (at.first + m * cos(r) / 111_320.0) to (at.second + m * sin(r) / (111_320.0 * cos(Math.toRadians(at.first))))
}

@Composable
fun StreetViewHost() {
    val open = StreetViewer.open
    var last by remember { mutableStateOf(open) }
    if (open != null) last = open
    AnimatedVisibility(
        open != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 10 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it / 10 },
    ) {
        last?.let { StopLookScreen(it) { StreetViewer.open = null } }
    }
}

private sealed interface PanoState {
    data object Looking : PanoState
    data object Failed : PanoState
    data object None : PanoState
    class Found(val pano: StreetPano.Pano) : PanoState
}

// Street View, straight away: the panorama fills the screen on black, as a photo would. Only where Google has none does
// Kav's own map show instead, with a word to say so; only when Google can't be reached is anything asked of the rider.
@Composable
private fun StopLookScreen(shown: StopLook, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var attempt by remember(shown) { mutableIntStateOf(0) }
    var state by remember(shown) { mutableStateOf<PanoState>(PanoState.Looking) }
    LaunchedEffect(shown, attempt) {
        state = PanoState.Looking
        state = withContext(Dispatchers.IO) {
            runCatching { StreetPano.of(shown.look) }.fold({ it?.let(PanoState::Found) ?: PanoState.None }, { PanoState.Failed })
        }
    }
    val found = (state as? PanoState.Found)?.pano
    Column(Modifier.fillMaxSize().background(K.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            BackButton(onClose)
            Column(Modifier.weight(1f)) {
                Text(shown.name, style = K.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Where it is, and when Google photographed it.
                val taken = found?.takeIf { it.year > 0 }?.let { "${monthName(it.month)} ${it.year}".trim() }
                listOfNotNull(shown.detail?.takeIf { it.isNotBlank() }, taken).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Full height: the tab bar steps away while Street View is open, so no glass refracts the moving picture.
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            var ready by remember(shown, attempt) { mutableStateOf(false) }
            when (val now = state) {
                PanoState.Looking -> Unit
                PanoState.Failed -> PaneNote(
                    T("Couldn't reach Google. Check the connection and try again.", "לא הצלחנו להגיע ל-Google. בדקו את החיבור ונסו שוב."),
                ) {
                    val ctx = LocalContext.current
                    KavButton(T("Try again", "נסו שוב"), { attempt++ }, style = ButtonStyle.Secondary)
                    KavButton(T("Open in another app", "פתיחה באפליקציה אחרת"), { openStreetView(ctx, shown.look) }, style = ButtonStyle.Plain)
                }
                PanoState.None -> {
                    StopLookMap(shown, null)
                    Text(
                        T("Google has no Street View here", "ל-Google אין כאן Street View"), style = K.type.subhead, color = K.text,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = K.gap3)
                            .glassSurface(20.dp).padding(horizontal = K.gap4, vertical = K.gap2),
                    )
                }
                is PanoState.Found -> StreetPane(shown, now.pano) { ready = true }
            }
            // One loading screen from the search to the panorama's first frames, so nothing flashes in between.
            LoadingOver(state == PanoState.Looking || (state is PanoState.Found && !ready))
        }
    }
}

@Composable
private fun LoadingOver(shown: Boolean) {
    AnimatedVisibility(shown, enter = fadeIn(tween(0)), exit = fadeOut(tween(300))) { Loading() }
}

// While Google is asked and its viewer opens: the screen's own ground and a small spinner, no frame.
@Composable
private fun Loading() {
    Box(
        Modifier.fillMaxSize().background(K.bg)
            .semantics { contentDescription = T("Loading Street View", "טוענים Street View") },
        contentAlignment = Alignment.Center,
    ) { Spinner(28.dp) }
}

private fun monthName(m: Int): String =
    if (m !in 1..12) "" else java.text.DateFormatSymbols(T.locale).months[m - 1]

@Composable
private fun StreetPane(shown: StopLook, pano: StreetPano.Pano, onReady: () -> Unit) {
    val lang = T.lang.code
    val ground = K.bg
    val page = remember(pano, shown, lang, ground) {
        streetEmbedPage(
            streetEmbedUrl(pano.id, shown.look.target ?: shown.look.at, facing(pano.lat to pano.lon, shown.look), lang),
            String.format(Locale.US, "#%06X", ground.toArgb() and 0xFFFFFF),
        )
    }
    // Should Google's frame never say it has loaded, the panorama is shown anyway after a while.
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(page) {
        kotlinx.coroutines.delay(8_000)
        ready()
    }
    StreetWebView(page, ground.toArgb()) { ready() }
}

@Composable
private fun PaneNote(text: String, actions: @Composable RowScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(K.gap5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(36.dp)) { streetGlyph(K.textTertiary) }
        Spacer(Modifier.height(K.gap3))
        Text(text, style = K.type.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(K.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2), content = actions)
    }
}

// Google's page, kept to itself: no location, no files, and any link out of it (Google Maps, terms, a problem report)
// opens outside Kav. Third-party cookies off, so Google's frame keeps none.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StreetWebView(page: String, ground: Int, onReady: () -> Unit) {
    val ready by rememberUpdatedState(onReady)
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(ground)
                // The page's word that Google's frame has loaded; it carries nothing else.
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun loaded() { post { ready() } }
                }, "KavHost")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        return true
                    }

                }
                loadDataWithBaseURL("https://kav.invalid/street-view", page, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun StopLookMap(shown: StopLook, pano: StreetPano.Pano?) {
    val stop = shown.look.target ?: shown.look.at
    val geometry = remember(shown, pano, K.look) {
        val dots = ArrayList<MapDot>()
        val lines = ArrayList<MapLine>()
        // Where Street View stands, and which way it looks.
        if (pano != null) {
            val heading = facing(pano.lat to pano.lon, shown.look)
            if (heading != null) lines += MapLine(listOf(pano.lat to pano.lon, offsetBy(pano.lat to pano.lon, heading.toFloat(), 14.0)), K.muted, 2.5f)
            dots += MapDot(pano.lat, pano.lon, K.bg, 6f)
            dots += MapDot(pano.lat, pano.lon, K.muted, 4f)
        }
        dots += MapDot(stop.first, stop.second, K.bg, 10f)
        dots += MapDot(stop.first, stop.second, androidx.compose.ui.graphics.Color.Transparent, 6.5f, K.accent, 2.6f)
        MapGeometry(lines = lines, dots = dots)
    }
    TileMap(
        listOfNotNull(stop, pano?.let { it.lat to it.lon }), Modifier.fillMaxSize(),
        fitMaxZoom = 18.4f, padFraction = .3f, geometry = geometry,
    )
}
