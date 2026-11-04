package uk.noammm.kav.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.zIndex
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
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
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
            Modifier.fillMaxWidth().zIndex(1f).background(K.bg)
                .padding(horizontal = K.gap4, vertical = K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            BackButton(onClose)
            Column(Modifier.weight(1f)) {
                Text(shown.name, style = K.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Where it is, and when Google photographed it. The line keeps its place while Google is asked, so the name
                // above it doesn't jump when the date comes; the date fades in.
                val taken = found?.takeIf { it.year > 0 }?.let { "${monthName(it.month)} ${it.year}".trim() }
                ChangingText(listOfNotNull(shown.detail?.takeIf { it.isNotBlank() }, taken).joinToString(" · ")) {
                    Text(it.ifEmpty { " " }, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Full height: the tab bar steps away while Street View is open, so no glass refracts the moving picture.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().navigationBarsPadding()) {
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

// Google's panorama in a frame takes the frame for part of a page that scrolls, and turns only with two fingers. This
// script, run in Google's frame alone, keeps the panorama the embed makes with Google's public class and turns it with
// one finger, as a full-screen Street View does. Android supplies rotation directly: a WebView can report motion
// tracking enabled while its frame receives no orientation events.
internal const val ONE_FINGER = """(function () {
  if (window.top === window || location.pathname.indexOf('/maps/embed') !== 0) return;
  var pano = null, made = null, rearmed = false, motionTries = 0, tracking = true, orientation = null;
  function motionState() {
    if (window.KavHost && KavHost.motionChanged) KavHost.motionChanged(tracking);
  }
  function armMotion() {
    if (rearmed || !pano) return;
    if (pano.getStatus() !== 'OK') {
      if (++motionTries < 100) setTimeout(armMotion, 100);
      return;
    }
    rearmed = true;
    try {
      document.querySelectorAll('[aria-label="Keyboard shortcuts"],[aria-label="קיצורי מקשים"]').forEach(function (button) { button.style.setProperty('display', 'none', 'important'); });
      pano.setMotionTracking(false);
      motionState();
    } catch (e) {}
  }
  function wrap(Pano) {
    if (!Pano || Pano.kav) return Pano;
    if (made && made.base === Pano) return made;
    var Kept = function (div, options) {
      pano = new Pano(div, options); rearmed = false; motionTries = 0; orientation = null;
      pano.setOptions({disableDefaultUI: true, addressControl: false, fullscreenControl: false,
        zoomControl: false, panControl: false, motionTrackingControl: false, linksControl: true,
        imageDateControl: false, showRoadLabels: false, enableCloseButton: false});
      setTimeout(armMotion, 1000);
      return pano;
    };
    Kept.prototype = Pano.prototype;
    Object.setPrototypeOf(Kept, Pano);
    Kept.kav = true; Kept.base = Pano;
    return made = Kept;
  }
  function trapPano(maps) {
    if (!maps || typeof maps !== 'object') return;
    var Pano = maps.StreetViewPanorama;
    try {
      Object.defineProperty(maps, 'StreetViewPanorama', {configurable: true, enumerable: true,
        get: function () { return wrap(Pano); }, set: function (v) { Pano = v; }});
    } catch (e) {}
  }
  function trapMaps(google) {
    if (!google || typeof google !== 'object') return;
    var maps = google.maps;
    try {
      Object.defineProperty(google, 'maps', {configurable: true, enumerable: true,
        get: function () { return maps; }, set: function (v) { maps = v; trapPano(v); }});
    } catch (e) {}
    trapPano(maps);
  }
  var google = window.google;
  try {
    Object.defineProperty(window, 'google', {configurable: true, enumerable: true,
      get: function () { return google; }, set: function (v) { google = v; trapMaps(v); }});
  } catch (e) {}
  trapMaps(google);
  addEventListener('message', function (e) {
    if (e.origin !== 'https://kav.invalid' || !pano || !e.data) return;
    var action = e.data.kavStreet, pov = pano.getPov();
    if (action === 'zoomIn') pano.setZoom(Math.min(5, pano.getZoom() + 1));
    else if (action === 'zoomOut') pano.setZoom(Math.max(0, pano.getZoom() - 1));
    else if (action === 'north') pano.setPov({heading: 0, pitch: pov.pitch});
    else if (action === 'motion') { tracking = !tracking; orientation = null; motionState(); }
    else if (action === 'orientation' && tracking && Number.isFinite(e.data.heading) && Number.isFinite(e.data.pitch)) {
      var next = [e.data.heading, e.data.pitch];
      if (orientation) {
        var turn = ((next[0] - orientation[0]) % 360 + 540) % 360 - 180;
        pano.setPov({heading: (pov.heading + turn + 360) % 360,
          pitch: Math.max(-90, Math.min(90, pov.pitch - next[1] + orientation[1]))});
      }
      orientation = next;
    }
  });
  var last = null;
  addEventListener('touchstart', function (e) {
    last = e.touches.length === 1 ? [e.touches[0].clientX, e.touches[0].clientY] : null;
  }, {capture: true, passive: true});
  addEventListener('touchmove', function (e) {
    if (!pano || !last || e.touches.length !== 1) return;
    var t = e.touches[0], dx = t.clientX - last[0], dy = t.clientY - last[1];
    last = [t.clientX, t.clientY];
    var pov = pano.getPov(), zoom = pano.getZoom() || 1;
    var deg = 180 / Math.pow(2, zoom) / Math.max(1, innerWidth);
    pano.setPov({heading: pov.heading - dx * deg, pitch: Math.max(-90, Math.min(90, pov.pitch + dy * deg))});
    e.preventDefault();
    e.stopPropagation();
  }, {capture: true, passive: false});
})();"""

// Google's page, kept to itself: no location, no files, and any link out of it (Google Maps, terms, a problem report)
// opens outside Kav. Third-party cookies off, so Google's frame keeps none.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StreetWebView(page: String, ground: Int, onReady: () -> Unit) {
    val ready by rememberUpdatedState(onReady)
    var web by remember { mutableStateOf<WebView?>(null) }
    var tracking by remember { mutableStateOf(true) }
    var prepared by remember { mutableStateOf(false) }
    fun command(action: String) {
        web?.evaluateJavascript("document.querySelector('iframe').contentWindow.postMessage({kavStreet:'$action'},'https://www.google.com')", null)
    }
    DisposableEffect(web, tracking, prepared) {
        val view = web
        val sensors = view?.context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (view == null || sensors == null || sensor == null || !tracking || !prepared) return@DisposableEffect onDispose {}
        val rotation = FloatArray(9); val camera = FloatArray(9); val angles = FloatArray(3)
        var heading: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, camera)
                SensorManager.getOrientation(camera, angles)
                val pitch = Math.toDegrees(angles[1].toDouble())
                // Looking straight up or down has no stable compass heading.
                if (kotlin.math.abs(pitch) < 80) heading = Math.toDegrees(angles[0].toDouble())
                val azimuth = heading ?: return
                val js = String.format(Locale.US,
                    "document.querySelector('iframe').contentWindow.postMessage({kavStreet:'orientation',heading:%.5f,pitch:%.5f},'https://www.google.com')",
                    azimuth, pitch)
                view.evaluateJavascript(js, null)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                web = this
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, ONE_FINGER, setOf("https://www.google.com"))
                }
                setBackgroundColor(ground)
                // The page's word that Google's frame has loaded; it carries nothing else.
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun loaded() { post { ready() } }
                    @android.webkit.JavascriptInterface
                    fun motionChanged(on: Boolean) { post { tracking = on; prepared = true } }
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
        onRelease = { web = null; it.stopLoading(); it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
    AnimatedVisibility(prepared, Modifier.align(Alignment.BottomEnd).padding(end = K.gap3, bottom = 48.dp),
        enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(K.surfaceRaised).padding(4.dp)) {
            Row {
                IconButton({ command("zoomOut") }) { Icon(Icons.Rounded.Remove, T("Zoom out", "הקטנה"), tint = K.text) }
                IconButton({ command("zoomIn") }) { Icon(Icons.Rounded.Add, T("Zoom in", "הגדלה"), tint = K.text) }
            }
            Row {
                IconButton({ command("north") }) { Icon(Icons.Rounded.Explore, T("Face north", "פנייה לצפון"), tint = K.text) }
                IconButton({ command("motion") }) { Icon(Icons.Rounded.ScreenRotation,
                    if (tracking) T("Turn phone motion off", "כיבוי תנועת הטלפון") else T("Turn phone motion on", "הפעלת תנועת הטלפון"),
                    tint = if (tracking) K.accent else K.dim) }
            }
        }
    }
    }
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
