package uk.noammm.kav.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.KavModel
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.TripService
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.RavPass
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.locationProviders
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.roundToInt

// Where the phone is for paying, kept as HopOn's app keeps it while it is open: the newest fix with its time and
// accuracy, at any accuracy, never a mocked one. Pay listens while it is open; paying from elsewhere waits for a fix.
// Only the moment of paying sends it to HopOn.
internal object PayFix {
    @Volatile private var latest: Location? = null
    private var users = 0
    private var off: (() -> Unit)? = null

    fun start(ctx: Context) {
        if (users++ == 0) off = listen(ctx.applicationContext) { latest = it }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        off?.invoke(); off = null; latest = null
    }

    // The newest fix, without waiting.
    fun last(): RavPass.Fix? = latest?.let(::fixOf)

    // The newest fix, or the next one within 10 seconds.
    suspend fun now(ctx: Context): RavPass.Fix? {
        last()?.let { return it }
        if (!hasLocationPermission(ctx)) return null
        val got = CompletableDeferred<Location>()
        val stop = withContext(Dispatchers.Main) { listen(ctx.applicationContext) { got.complete(it) } }
        return try { withTimeoutOrNull(10_000) { got.await() }?.let(::fixOf) } finally { withContext(Dispatchers.Main) { stop() } }
    }

    private fun fixOf(l: Location) = RavPass.Fix(l.latitude, l.longitude, l.time, l.accuracy)

    // HopOn's app takes a fix that has an accuracy and a position and isn't mocked, from the fused provider it asks
    // through Google's. Without the platform's own fused provider, GPS and the network.
    private fun listen(ctx: Context, onFix: (Location) -> Unit): () -> Unit {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}
        if (!hasLocationPermission(ctx)) return {}
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
                if (location.hasAccuracy() && location.latitude != 0.0 && location.longitude != 0.0 && !mock) onFix(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val all = locationProviders(lm)
        val providers = if (LocationManager.FUSED_PROVIDER in all) listOf(LocationManager.FUSED_PROVIDER) else all
        val request = LocationRequestCompat.Builder(2_000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        var any = false
        for (p in providers) {
            try {
                LocationManagerCompat.requestLocationUpdates(lm, p, request, listener, Looper.getMainLooper())
                any = true
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        return if (any) ({ runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }) else ({})
    }
}

// No fix to pay from. HopOn's app doesn't pay without one either.
internal class NoFix : Exception("no location")

// The Rav-Pass account Kav pays with, and today's tickets. Its token can spend money, so it is kept sealed with a key
// that cannot leave the phone. Nothing else in Kav uses the account.
object Payer {
    private var store: android.content.SharedPreferences? = null
    @Volatile private var session: RavPass.Session? = null
    private val purchases = PayPurchase()
    private val accountState = Any()
    @Volatile private var sessionVersion = 0L
    val purchasing get() = purchases.busy

    var signedIn by mutableStateOf(false)
        private set

    // What paying still needs, in the order HopOn's app asks for it: the terms in force, the holder's name and email,
    // then a card. Null until HopOn is asked.
    enum class Setup { TERMS, DETAILS, CARD }
    var missing by mutableStateOf<List<Setup>?>(null)
        private set
    var settings by mutableStateOf<RavPass.Settings?>(null)
        private set
    var account by mutableStateOf<RavPass.Account?>(null)
        private set
    var cards by mutableStateOf<List<RavPass.Card>>(emptyList())
        private set
    // The card page asked for from Settings, with the account otherwise ready.
    var addingCard by mutableStateOf(false)
    private var skipped = false

    // Today's tickets, newest first: those bought here and the latest HopOn lists.
    var tickets by mutableStateOf<List<RavPass.Ticket>>(emptyList())
        private set

    // When the last ride's free transfer ends, while it lasts.
    fun transferUntil(now: Long) = tickets.maxOfOrNull { it.transferUntilUtc }?.takeIf { it > now }

    var showRef by mutableStateOf<String?>(null)

    // The bus code each ticket was paid on, to know the same bus when it is scanned again.
    private val codes = HashMap<Long, String>()
    fun codeOf(t: RavPass.Ticket) = synchronized(codes) { codes[t.id] }

    // Asshole mode: a bus ride kept until it is paid or dropped. qr is the bus's code, the ride its line's run (routeId,
    // officeLineId) from the stop `from` to the stop `to` as HopOn priced it, and guests the others riding along. leg
    // and journey identify the trip ride it was for.
    class Pending(
        val line: String, val qr: String, val routeId: String, val officeLineId: String?, val from: String, val to: String,
        val guests: Int, val leg: Int?, val journey: String? = null,
    )
    var pending by mutableStateOf<Pending?>(null)
        private set
    var payingLater by mutableStateOf(false)
        private set
    var laterError by mutableStateOf<String?>(null)
        private set
    // The kept ride just paid: its ticket's key and trip leg, while the square and the trip notification say "Paid".
    var paidLater by mutableStateOf<Pair<String, Int?>?>(null)
    private val later = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var app: Context? = null

    // Whether the phone's traffic goes through a VPN. HopOn's network blocks some VPN servers in Israel and its answer
    // then never comes, which HopOn's own app explains the same way.
    fun behindVpn(): Boolean {
        val cm = app?.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }

    // Once per process: by the app, or by the trip service paying from its notification with the app closed.
    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (store != null) return
        // Kav paid through Moovit before; that paying user's sealed tokens go.
        ctx.deleteSharedPreferences("moovit-pay")
        val s = ctx.getSharedPreferences("rav-pass", Context.MODE_PRIVATE)
        store = s
        session = s.getString("session", null)?.let(Seal::open)?.let { json ->
            runCatching { JSONObject(json).let { RavPass.Session(it.getString("token"), it.getString("uid")) } }.getOrNull()
        }
        signedIn = session != null
        pending = s.getString("pending", null)?.let(Seal::open)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                Pending(o.getString("line"), o.getString("qr"), o.getString("route"), o.optString("office").ifBlank { null },
                    o.getString("from"), o.getString("to"), o.optInt("guests"), o.optInt("leg", -1).takeIf { it >= 0 },
                    o.optString("journey").ifBlank { null })
            }.getOrNull()
        }
        s.getString("tickets", null)?.let(Seal::open)?.let { json ->
            runCatching {
                val a = JSONArray(json)
                val kept = (0 until a.length()).map { a.getJSONObject(it) }
                kept.forEach { o -> o.optString("kav_qr").ifBlank { null }?.let { codes[o.optLong("id")] = it } }
                tickets = kept.map(RavPass::ticketOf)
            }
        }
    }

    // A ticket joins today's, replacing an older copy of itself; a day later it goes, unless it is still valid.
    private fun note(t: RavPass.Ticket, qr: String? = null) {
        val now = System.currentTimeMillis()
        synchronized(codes) { if (qr != null) codes[t.id] = qr }
        tickets = (listOf(t) + tickets.filter { it.id != t.id })
            .filter { it.boughtUtc > now - 86_400_000L || it.valid(now) }
            .sortedByDescending { it.boughtUtc }
        val json = JSONArray().apply {
            tickets.forEach { k -> put(RavPass.json(k).apply { codeOf(k)?.let { put("kav_qr", it) } }) }
        }.toString()
        store?.edit()?.putString("tickets", Seal.close(json))?.apply()
    }

    // HopOn's latest ticket joins today's.
    suspend fun refresh() {
        if (!signedIn) return
        val version = sessionVersion
        val t = call { RavPass.latest(it) }
        if (t != null && version == sessionVersion) note(t)
    }

    // A payment whose answer is lost on the way back may still have gone through: before it is called failed, HopOn's
    // latest ticket is read again. The charge itself is never repeated automatically.
    suspend fun purchase(qr: String, prepare: suspend () -> Unit = {}, buy: suspend () -> RavPass.Ticket): RavPass.Ticket {
        val t = purchases.run(snapshot = { call { RavPass.latest(it) } }, buy = buy, prepare = prepare)
        note(t, qr)
        return t
    }

    // Asks HopOn what paying still needs. agreed: the rider has just agreed to the terms, so they are accepted now.
    suspend fun check(agreed: Boolean = false): List<Setup> {
        val version = sessionVersion
        val s = call { RavPass.settings(it.uid, it.token) }
        val a = call { RavPass.account(it) }
        val terms = s.terms
        var termsDue = terms != null && a.terms != terms
        if (termsDue && agreed && terms != null) {
            call { RavPass.acceptTerms(it, terms) }
            termsDue = false
        }
        val methods = call { RavPass.cards(it) }
        val out = buildList {
            if (termsDue) add(Setup.TERMS)
            if (!a.hasDetails) add(Setup.DETAILS)
            if (methods.all { it.expired(System.currentTimeMillis()) }) add(Setup.CARD)
        }
        if (version == sessionVersion) {
            settings = s; account = a; cards = methods
            missing = if (skipped) out - Setup.CARD else out
        }
        return out
    }

    // Paying without a card on file for now: HopOn says so in its own words if it needs one.
    fun skipCard() {
        skipped = true
        missing = missing?.minus(Setup.CARD)
    }

    fun signIn(s: RavPass.Session) {
        synchronized(accountState) {
            sessionVersion++
            session = s
            signedIn = true
            missing = null; skipped = false
            store?.edit()?.putString("session", Seal.close(JSONObject().put("token", s.token).put("uid", s.uid).toString()))?.apply()
        }
    }

    // A call as the account, off the main thread. HopOn no longer taking the token means signing in again.
    suspend fun <T> call(block: (RavPass.Session) -> T): T {
        val version = sessionVersion
        val s = session ?: throw RavPass.Unauthorized()
        val result = try {
            withContext(Dispatchers.IO) { block(s) }
        } catch (e: RavPass.Unauthorized) {
            withContext(Dispatchers.Main) { if (version == sessionVersion) signOut(force = true) }
            throw e
        }
        if (version != sessionVersion) throw CancellationException("Payment account changed")
        return result
    }

    // Whether HopOn's app counts others riding along as occasional passengers too, from HopOn's settings.
    suspend fun occasional(): Boolean = (settings ?: call { RavPass.settings(it.uid, it.token) }).occasional

    // HopOn's app asks before a second bus ticket from the same company within minutes of the last: 20 for its agency
    // 22, a light rail, and 5 for the rest.
    fun paidJustNow(agency: String?): Boolean {
        val s = store ?: return false
        return agency != null && agency == s.getString("again_agency", null) && System.currentTimeMillis() < s.getLong("again_until", 0L)
    }

    fun paidBus(agency: String?) {
        val minutes = if (agency == "22") 20 else 5
        store?.edit()?.putString("again_agency", agency)?.putLong("again_until", System.currentTimeMillis() + minutes * 60_000L)?.apply()
    }

    fun forgetPaidBus() { store?.edit()?.remove("again_agency")?.remove("again_until")?.apply() }

    fun hold(p: Pending) { if (payingLater || purchasing) return; pending = p; laterError = null; keepPending() }

    fun dropPending() { if (payingLater) return; pending = null; laterError = null; keepPending() }

    private fun keepPending() {
        val p = pending
        val edit = store?.edit() ?: return
        if (p == null) edit.remove("pending")
        else edit.putString("pending", Seal.close(JSONObject()
            .put("line", p.line).put("qr", p.qr).put("route", p.routeId).put("office", p.officeLineId)
            .put("from", p.from).put("to", p.to).put("guests", p.guests).put("leg", p.leg ?: -1).put("journey", p.journey)
            .toString()))
        edit.apply()
    }

    // Pays the kept ride now, as on the spot: HopOn reads the bus's code with the phone's fix as it is now, prices the
    // kept ride, and only then is it paid. onPaid gets the ticket's key and trip leg; onDone follows either way.
    fun payLater(ctx: Context, onPaid: (key: String, leg: Int?, journey: String?) -> Unit, onDone: () -> Unit = {}) {
        val p = pending ?: return
        if (payingLater || purchasing) return
        payingLater = true; laterError = null
        later.launch {
            try {
                lateinit var bus: RavPass.Bus
                var asOccasional = false
                val t = purchase(p.qr, prepare = {
                    val fix = PayFix.now(ctx) ?: throw NoFix()
                    val scanned = call { RavPass.scan(it, p.qr, fix) }
                    val to = RavPass.Stop(p.to, "", Double.NaN, Double.NaN, -1)
                    bus = scanned.with(RavPass.Suggestion(p.line, p.routeId, p.officeLineId,
                        RavPass.Stop(p.from, "", Double.NaN, Double.NaN, -1), to, "", ""))
                    asOccasional = occasional()
                    call { RavPass.quote(it, bus, to, p.guests) }
                }) { call { RavPass.buy(it, bus.qr, p.guests, asOccasional) } }
                paidBus(bus.agencyId)
                if (pending === p) { pending = null; keepPending() }
                val key = t.id.toString()
                paidLater = key to p.leg
                onPaid(key, p.leg, p.journey)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (pending === p) laterError = failure(e) }
            finally { payingLater = false; onDone() }
        }
    }

    // Forget the account on this phone: its token, today's tickets and a kept ride. force: HopOn already let it go.
    fun signOut(force: Boolean = false) {
        synchronized(accountState) {
            if (!force && (purchasing || payingLater)) return
            sessionVersion++
            session = null
            signedIn = false
            missing = null; settings = null; account = null; cards = emptyList(); skipped = false; addingCard = false
            tickets = emptyList()
            synchronized(codes) { codes.clear() }
            pending = null; paidLater = null; laterError = null
            store?.edit()?.clear()?.apply()
        }
    }
}

@Composable
fun PayScreen(model: KavModel, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Pay", "תשלום"), T("for a ride", "על נסיעה"), back = onClose)
        if (Payer.signedIn) Ready(model) else Connect()
    }
}

private enum class ConnectStep { START, ABOUT, SIGN_IN }

// Until an account is connected: what paying with Rav-Pass means, what HopOn sees, then signing in or up.
@Composable
private fun Connect() {
    var step by rememberSaveable { mutableStateOf(ConnectStep.START) }
    androidx.activity.compose.BackHandler(enabled = step != ConnectStep.START) { step = ConnectStep.entries[step.ordinal - 1] }
    androidx.compose.animation.AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState < initialState) backward() else forward() },
        label = "connect",
    ) { s ->
        when (s) {
            ConnectStep.START -> ConnectStart { step = ConnectStep.ABOUT }
            ConnectStep.ABOUT -> ConnectAbout { step = ConnectStep.SIGN_IN }
            ConnectStep.SIGN_IN -> SignIn()
        }
    }
}

@Composable
private fun ConnectStart(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = K.gap5).padding(bottom = K.gap6 + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(K.gap4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(56.dp)) { drawQr() }
        Text(T("Pay for bus rides with Rav-Pass", "תשלום על נסיעות באוטובוס עם רב-פס"),
            fontSize = 22.sp, color = K.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(T("Scan the QR code on the bus and pay with your Rav-Pass account, right from Kav. No account yet? You " +
            "can open one here.",
            "סורקים את קוד ה-QR באוטובוס ומשלמים עם חשבון רב-פס שלכם, ישירות מ-Kav. אין לכם חשבון? אפשר לפתוח אחד כאן."),
            fontSize = 15.sp, lineHeight = 21.sp, color = K.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(K.gap2))
        PayButton(T("Continue", "המשך"), false, onClick = onNext)
    }
}

@Composable
private fun ConnectAbout(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        AboutCard(T("Your payments stay with Rav-Pass", "התשלומים נשארים ברב-פס")) {
            Note(T(
                "Rav-Pass is HopOn's payment service, licensed by the Ministry of Transport. Every payment is made by " +
                    "HopOn's servers, on your own Rav-Pass account. Kav has no server of its own, so your account, your " +
                    "card and your rides never reach Kav or its developer.",
                "רב-פס הוא שירות התשלום של הופ-און, ברישיון משרד התחבורה. כל תשלום מתבצע בשרתים של הופ-און, בחשבון " +
                    "רב-פס שלכם. ל-Kav אין שרת משלה, כך שהחשבון, הכרטיס והנסיעות שלכם לא מגיעים ל-Kav או למפתח שלה.",
            ), color = K.muted)
            Note(T(
                "Your card is typed only on CreditGuard's secure page for HopOn, never in Kav. Rides are charged to it " +
                    "once a month. Kav asks HopOn to pay only when you press pay.",
                "את הכרטיס מקלידים רק בדף המאובטח של CreditGuard עבור הופ-און, אף פעם לא ב-Kav. הנסיעות מחויבות בו " +
                    "פעם בחודש. Kav מבקשת מהופ-און לשלם רק כשאתם לוחצים על תשלום.",
            ), color = K.muted)
        }
        AboutCard(T("New to Rav-Pass?", "חדשים ברב-פס?")) {
            AboutStep(1, T("Enter your phone number and the code Rav-Pass sends by SMS. A new number opens an account.",
                "מזינים את מספר הטלפון ואת הקוד שרב-פס שולחת ב-SMS. מספר חדש פותח חשבון."))
            AboutStep(2, T("Add your name and email, then a credit card.", "מוסיפים שם ואימייל, ואז כרטיס אשראי."))
            AboutStep(3, T("That's all. A discount profile (student, senior and more) is added in the Rav-Pass app.",
                "זהו. פרופיל הנחה (סטודנט, אזרח ותיק ועוד) מוסיפים באפליקציית רב-פס."))
        }
        AboutCard(T("What HopOn sees", "מה הופ-און רואה")) {
            Note(T(
                "Everything else in Kav, from planning and live times to search, stations and lines, goes through an " +
                    "anonymous Moovit ID that isn't tied to you.",
                "כל השאר ב-Kav, מתכנון מסלול וזמנים בזמן אמת ועד חיפוש, תחנות וקווים, עובר דרך מזהה Moovit " +
                    "אנונימי שלא קשור אליכם.",
            ), color = K.muted)
            Note(T(
                "Only paying uses your Rav-Pass account. Then HopOn knows the bus you scanned, the stop you board at and " +
                    "how far you ride, or the station you pay at, because the fare needs them. Like HopOn's own app, Kav " +
                    "sends your location only at the moment you scan or pay, never in the background.",
                "רק תשלום משתמש בחשבון רב-פס שלכם. אז הופ-און יודעת באיזה אוטובוס סרקתם, באיזו תחנה עליתם וכמה רחוק " +
                    "אתם נוסעים, או באיזו תחנה אתם משלמים, כי המחיר צריך את זה. כמו האפליקציה של הופ-און, Kav שולחת " +
                    "את המיקום שלכם רק ברגע הסריקה או התשלום, אף פעם לא ברקע.",
            ), color = K.muted)
        }
        PayButton(T("Continue", "המשך"), false, onClick = onNext)
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Text(title, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun AboutStep(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
        Text("$n", fontSize = 15.sp, color = K.accent, fontWeight = FontWeight.SemiBold)
        Note(text, color = K.muted)
    }
}

internal fun failure(e: Throwable): String = when (e) {
    // HopOn's title says what went wrong and its reasons say why.
    is RavPass.Refused -> listOf(e.title, e.message).filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" ")
    is PayPurchase.Unconfirmed -> T("Couldn't confirm the payment. Check your tickets before trying again.",
        "לא ניתן לאשר אם התשלום בוצע. בדקו את הכרטיסים לפני ניסיון נוסף.")
    is RavPass.Unauthorized -> T("Rav-Pass signed you out. Sign in again.", "רב-פס ניתקה אתכם. התחברו שוב.")
    is NoFix -> T("Kav can't tell where you are. Turn on location and try again.",
        "Kav לא מצליחה לדעת איפה אתם. הפעילו את המיקום ונסו שוב.")
    else -> if (Payer.behindVpn()) T(
        "Rav-Pass didn't answer through your VPN. It blocks some VPN servers in Israel: switch to a server outside Israel, or pause the VPN while you pay.",
        "רב-פס לא ענתה דרך ה-VPN. היא חוסמת חלק משרתי ה-VPN בישראל: עברו לשרת מחוץ לישראל, או השהו את ה-VPN בזמן התשלום.",
    ) else T("Couldn't reach Rav-Pass. Try again.", "אין חיבור לרב-פס. נסו שוב.")
}

@Composable
private fun PayButton(text: String, busy: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val waiting = busy || Payer.purchasing || Payer.payingLater
    Box(
        modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(K.rPill))
            .background(if (waiting || !enabled) K.surface4 else K.accent)
            .clickable(enabled = !waiting && enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (waiting) T("One moment…", "רגע…") else text, fontSize = 16.sp,
            color = if (waiting || !enabled) K.muted else K.onAccent, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2))
    }
}

// Runs one step's call at a time, with its error shown under it.
private class Work(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
            busy = false
        }
    }
}

@Composable
private fun rememberWork(): Work {
    val scope = rememberCoroutineScope()
    return remember { Work(scope) }
}

private sealed interface SignInStep {
    data object Phone : SignInStep
    class Code(val phone: String) : SignInStep
}

// Signing in and signing up are one: the code HopOn sends by SMS opens an account for a number it doesn't know yet.
@Composable
private fun SignIn() {
    val w = rememberWork()
    val uid = rememberSaveable { RavPass.newUid() }
    var step by remember { mutableStateOf<SignInStep>(SignInStep.Phone) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Where HopOn's terms and privacy policy are read.
    var links by remember { mutableStateOf<RavPass.Settings?>(null) }
    LaunchedEffect(Unit) { links = runCatching { withContext(Dispatchers.IO) { RavPass.settings(uid) } }.getOrNull() }

    fun send(number: String) = w.run {
        withContext(Dispatchers.IO) { RavPass.sendCode(uid, number) }
        code = ""; step = SignInStep.Code(number)
    }

    // The box ticked before the code was sent is the agreement HopOn's app takes when signing up.
    fun confirm(number: String) = w.run {
        val session = withContext(Dispatchers.IO) { RavPass.verify(uid, number, code) }
        Payer.signIn(session)
        runCatching { Payer.check(agreed = true) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        when (val s = step) {
            SignInStep.Phone -> {
                Text(T("Sign in or sign up", "התחברות או הרשמה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                Note(T("Rav-Pass sends a code by SMS to this number. A number without an account gets a new one.",
                    "רב-פס שולחת קוד ב-SMS למספר הזה. מספר בלי חשבון מקבל חשבון חדש."), color = K.muted)
                KavField(phone, { phone = it.filter { c -> c.isDigit() || c == '-' || c == '+' } }, "050-123-4567",
                    keyboard = KeyboardType.Phone)
                TermsAgreement(agreed, links) { agreed = it }
                PayButton(T("Send code", "שליחת קוד"), w.busy) {
                    when {
                        phone.count(Char::isDigit) < 9 -> w.error = T("Enter your phone number.", "הזינו את מספר הטלפון.")
                        !agreed -> w.error = T("Please confirm the terms & conditions", "יש לאשר את תנאי השימוש")
                        else -> confirming = true
                    }
                }
            }
            is SignInStep.Code -> {
                Text(T("The code Rav-Pass sent to ${s.phone}", "הקוד שרב-פס שלחה ל-${s.phone}"), fontSize = 15.sp, color = K.muted)
                KavField(code, { v ->
                    code = v.filter(Char::isDigit).take(6)
                    // HopOn's codes have four digits, and its app checks one as soon as it is typed.
                    if (code.length == 4 && !w.busy) confirm(s.phone)
                }, "1234", keyboard = KeyboardType.NumberPassword, autoFocus = true)
                PayButton(T("Confirm", "אישור"), w.busy) { if (code.length >= 4) confirm(s.phone) }
                Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                    Text(T("Send it again", "שליחה מחדש"), fontSize = 14.sp, color = K.accent,
                        modifier = Modifier.clickable(enabled = !w.busy, role = Role.Button) { send(s.phone) }.padding(vertical = K.gap2))
                    Text(T("Change number", "שינוי מספר"), fontSize = 14.sp, color = K.muted,
                        modifier = Modifier.clickable(enabled = !w.busy, role = Role.Button) { code = ""; w.error = null; step = SignInStep.Phone }
                            .padding(vertical = K.gap2))
                }
            }
        }
        w.error?.let { Note(it, color = K.critical) }
    }
    if (confirming) {
        val (country, number) = RavPass.phoneParts(phone)
        val shown = if (number.length == 9) "+$country-${number.take(2)}-${number.substring(2, 5)} ${number.drop(5)}" else "+$country-$number"
        PayDialog(T("Is this your number?", "זה המספר שלך?"), T.ltr(shown), T("Correct", "זה המספר"),
            no = T("Edit", "עריכה"), onDismiss = { confirming = false }) { confirming = false; send(phone) }
    }
}

// HopOn's sign-up agreement: its terms of service and privacy policy, each opening its page.
@Composable
private fun TermsAgreement(agreed: Boolean, links: RavPass.Settings?, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(if (agreed) K.accent else K.surface4).border(1.dp, if (agreed) K.accent else K.border, RoundedCornerShape(6.dp))
                .clickable(role = Role.Checkbox) { onChange(!agreed) },
            contentAlignment = Alignment.Center,
        ) { if (agreed) Text("✓", fontSize = 14.sp, color = K.onAccent, fontWeight = FontWeight.SemiBold) }
        Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Note(T("By signing up, you agree to Rav-Pass's terms of service and privacy policy",
                "השימוש בשירות מותנה בהסכמתך לתנאי השימוש ומדיניות הפרטיות של רב-פס"),
                Modifier.clickable(role = Role.Checkbox) { onChange(!agreed) }, color = K.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                links?.termsUrl?.let { url ->
                    Text(T("Terms of service", "תנאי השימוש"), fontSize = 14.sp, color = K.accent,
                        modifier = Modifier.clickable(role = Role.Button) { openLink(ctx, url) }.padding(vertical = K.gap1))
                }
                links?.privacyUrl?.let { url ->
                    Text(T("Privacy policy", "מדיניות הפרטיות"), fontSize = 14.sp, color = K.accent,
                        modifier = Modifier.clickable(role = Role.Button) { openLink(ctx, url) }.padding(vertical = K.gap1))
                }
            }
        }
    }
}

// Signed in: what the account still needs first, as HopOn's app asks it, then paying.
@Composable
private fun Ready(model: KavModel) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try { Payer.check() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (Payer.missing == null) error = failure(e)
        }
    }
    val missing = Payer.missing
    when {
        missing == null && error != null -> PayStep {
            Note(error.orEmpty(), color = K.critical)
            PayButton(T("Try again", "ניסיון נוסף"), false) { attempt++ }
        }
        missing == null -> LoadingBlock(T("Asking Rav-Pass", "שואלים את רב-פס"), labelAbove = true)
        missing.isNotEmpty() -> when (missing.first()) {
            Payer.Setup.TERMS -> TermsStep()
            Payer.Setup.DETAILS -> DetailsStep()
            Payer.Setup.CARD -> CardStep(canSkip = true)
        }
        Payer.addingCard -> CardStep(canSkip = false)
        else -> Paying(model)
    }
}

// HopOn's terms changed since the rider agreed to them.
@Composable
private fun TermsStep() {
    val ctx = LocalContext.current
    val w = rememberWork()
    val s = Payer.settings
    PayStep {
        AboutCard(T("Rav-Pass terms of service", "תנאי השימוש של רב-פס")) {
            Note(T("Please confirm the terms & conditions to keep paying with Rav-Pass.",
                "יש לאשר את תנאי השימוש כדי להמשיך לשלם עם רב-פס."), color = K.muted)
            listOfNotNull(
                s?.termsUrl?.let { T("Terms of service", "תנאי השימוש") to it },
                s?.privacyUrl?.let { T("Privacy policy", "מדיניות הפרטיות") to it },
                s?.motTermsUrl?.let { T("Ministry of Transport terms", "תנאי משרד התחבורה") to it },
            ).forEach { (label, url) ->
                Text(label, fontSize = 14.sp, color = K.accent,
                    modifier = Modifier.clickable(role = Role.Button) { openLink(ctx, url) }.padding(vertical = K.gap1))
            }
        }
        PayButton(T("I agree", "אני מסכים/ה"), w.busy) {
            w.run {
                val version = s?.terms ?: return@run
                Payer.call { RavPass.acceptTerms(it, version) }
                Payer.check()
            }
        }
        w.error?.let { Note(it, color = K.critical) }
    }
}

// The holder's name and email, which HopOn's app asks for before a card is added.
@Composable
private fun DetailsStep() {
    val w = rememberWork()
    val a = Payer.account
    var first by rememberSaveable { mutableStateOf(a?.first.orEmpty()) }
    var last by rememberSaveable { mutableStateOf(a?.last.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(a?.email.orEmpty()) }
    PayStep {
        Text(T("Your details", "הפרטים שלכם"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
        Note(T("Rav-Pass asks for your name and email before a card is added. Its receipts go to this email.",
            "רב-פס מבקשת שם ואימייל לפני הוספת כרטיס. הקבלות שלה נשלחות לאימייל הזה."), color = K.muted)
        Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            Text(T("First name", "שם פרטי"), fontSize = 13.sp, color = K.dim)
            KavField(first, { first = it.take(40) }, T("First name", "שם פרטי"))
            Text(T("Last name", "שם משפחה"), fontSize = 13.sp, color = K.dim)
            KavField(last, { last = it.take(40) }, T("Last name", "שם משפחה"))
            Text(T("Email", "אימייל"), fontSize = 13.sp, color = K.dim)
            KavField(email, { email = it.trim().take(80) }, "name@example.com", keyboard = KeyboardType.Email)
        }
        PayButton(T("Save", "שמירה"), w.busy) {
            when {
                first.isBlank() || last.isBlank() -> w.error = T("Enter your first and last name.", "הזינו שם פרטי ושם משפחה.")
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> w.error = T("Enter a valid email.", "הזינו אימייל תקין.")
                else -> w.run {
                    Payer.call { RavPass.saveDetails(it, first.trim(), last.trim(), email.trim()) }
                    Payer.check()
                }
            }
        }
        w.error?.let { Note(it, color = K.critical) }
    }
}

// Adding a card on HopOn's card page, inside Kav, then the code the card's issuer sends by SMS when HopOn asks for it.
// canSkip: the account has no card yet, and paying can be tried anyway.
@Composable
private fun CardStep(canSkip: Boolean) {
    val w = rememberWork()
    var page by remember { mutableStateOf<String?>(null) }
    var askCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var notYet by remember { mutableStateOf(false) }

    // HopOn may take a moment to list a card just added.
    fun added() = w.run {
        notYet = false
        repeat(10) { i ->
            if (Payer.Setup.CARD !in Payer.check()) { Payer.addingCard = false; askCode = false; return@run }
            if (i < 9) delay(2_000)
        }
        notYet = true
    }

    fun confirm() = w.run {
        val digits = code
        Payer.call { RavPass.verifyCard(it, digits) }
        code = ""
        notYet = false
        repeat(10) { i ->
            if (Payer.Setup.CARD !in Payer.check()) { Payer.addingCard = false; askCode = false; return@run }
            if (i < 9) delay(2_000)
        }
        notYet = true
    }

    val url = page
    if (url != null) {
        CardPage(url, onClose = { page = null }) { end ->
            page = null
            when (end) {
                RavPass.CardEnd.ADDED -> added()
                RavPass.CardEnd.CODE -> { code = ""; askCode = true }
                RavPass.CardEnd.FAILED -> w.error = T("Rav-Pass couldn't add the card. Check its details and try again.",
                    "רב-פס לא הצליחה להוסיף את הכרטיס. בדקו את הפרטים ונסו שוב.")
                RavPass.CardEnd.CANCELLED -> {}
            }
        }
        return
    }
    PayStep {
        if (askCode) {
            AboutCard(T("Enter the 4-digit code", "מה קוד האימות שקיבלת?")) {
                Note(T("We have requested the credit card issuer to send an SMS with a 4 digits code to the owner of the card",
                    "לצורך אימות הפרטים ביקשנו מחברת האשראי לשלוח קוד בן 4 ספרות לבעל.ת הכרטיס"), color = K.muted)
            }
            KavField(code, { v ->
                code = v.filter(Char::isDigit).take(4)
                // As in HopOn's app, the code is checked as soon as its fourth digit is typed.
                if (code.length == 4 && !w.busy) confirm()
            }, "1234", keyboard = KeyboardType.NumberPassword, autoFocus = true)
            PayButton(T("Confirm", "אישור"), w.busy) { if (code.length == 4) confirm() }
        } else {
            AboutCard(T("Add a credit card", "הוספת כרטיס אשראי")) {
                Note(T(
                    "Rav-Pass charges your rides to a credit card once a month. You type it on CreditGuard's secure page " +
                        "for HopOn, shown here; Kav never sees it.",
                    "רב-פס מחייבת את הנסיעות בכרטיס אשראי פעם בחודש. מקלידים אותו בדף המאובטח של CreditGuard עבור " +
                        "הופ-און, שמוצג כאן; Kav לא רואה אותו.",
                ), color = K.muted)
            }
            // The cards HopOn lists but can't charge, and why.
            val now = rememberNow()
            Payer.cards.filter { it.expired(now) }.forEach { c ->
                Note(listOf(c.brand, "•••• ${c.last4}").filter { it.isNotBlank() }.joinToString(" ") + " · " +
                    if (c.expiresUtc != null && c.expiresUtc < now) T("expired", "פג תוקף") else T("not active", "לא פעיל"), color = K.problem)
            }
            PayButton(T("Add a card", "הוספת כרטיס"), w.busy) { w.run { page = Payer.call { RavPass.cardPage(it) } } }
        }
        if (notYet) Note(if (Payer.cards.isEmpty())
            T("Rav-Pass doesn't list the card yet. If you finished adding it, try again in a moment.",
                "רב-פס עוד לא מציגה את הכרטיס. אם סיימתם להוסיף אותו, נסו שוב בעוד רגע.")
            else T("Rav-Pass lists the card but can't charge it yet. Try again in a moment, or add another card.",
                "רב-פס מציגה את הכרטיס אבל עוד לא יכולה לחייב אותו. נסו שוב בעוד רגע, או הוסיפו כרטיס אחר."),
            color = K.problem)
        w.error?.let { Note(it, color = K.critical) }
        // The code can come after the page closed; and a card waiting for one can be added again for a new code.
        Text(if (askCode) T("Add the card again", "הוספת הכרטיס מחדש") else T("Got a code from your card company? Enter it",
            "קיבלתם קוד מחברת האשראי? הזינו אותו"), fontSize = 14.sp, color = K.accent,
            modifier = Modifier.clickable(enabled = !w.busy, role = Role.Button) { w.error = null; askCode = !askCode; code = "" }
                .padding(vertical = K.gap2))
        if (canSkip) Text(T("Skip for now", "דילוג בינתיים"), fontSize = 14.sp, color = K.muted,
            modifier = Modifier.clickable(role = Role.Button) { Payer.skipCard() }.padding(vertical = K.gap2))
        else CancelLink { Payer.addingCard = false }
    }
}

// CreditGuard's page for HopOn, where the card is typed. Every page loads, HopOn's return link among them, which is what
// adds the card; the marks it then sends the page to only say how it ended, so they are read instead of loaded, as in
// HopOn's app. No screenshots.
@Composable
private fun CardPage(url: String, onClose: () -> Unit, onEnd: (RavPass.CardEnd) -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val ended = rememberUpdatedState(onEnd)
    Column(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap2), verticalAlignment = Alignment.CenterVertically) {
            Text(T("Add a card", "הוספת כרטיס"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            CancelLink(onClose)
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var finished = false
                        private fun end(u: String?): Boolean {
                            val how = u?.let(RavPass::cardEnd) ?: return false
                            if (!finished) { finished = true; ended.value(how) }
                            return true
                        }
                        // Only web pages open here.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (end(request.url.toString())) return true
                            val scheme = request.url.scheme
                            return scheme != "http" && scheme != "https"
                        }
                        override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                            if (end(u)) view.stopLoading()
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.destroy() },
        )
    }
}

// Settings' payments group, once signed in: the account, its card, its history, and signing out.
@Composable
internal fun PaymentsSection(model: KavModel) {
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Payer.check() } }
    val a = Payer.account
    val now = System.currentTimeMillis()
    val card = Payer.cards.firstOrNull { !it.expired(now) && it.default } ?: Payer.cards.firstOrNull { !it.expired(now) }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(14.dp).padding(K.gap3).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Text(a?.name?.ifBlank { null } ?: T("Your Rav-Pass account", "חשבון רב-פס שלכם"), fontSize = 15.sp, color = K.text)
        a?.phone?.takeIf { it.isNotBlank() }?.let { LtrText(it, 13.sp, K.dim) }
        if (card != null) LtrText(listOf(card.brand, "•••• ${card.last4}").filter { it.isNotBlank() }.joinToString(" "), 13.sp, K.dim)
        else if (a != null) Text(T("No card on the account yet", "עוד אין כרטיס בחשבון"), fontSize = 13.sp, color = K.problem)
        a?.profiles?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), fontSize = 13.sp, color = K.dim) }
        Text(T(
            "Kav pays with your Rav-Pass account, and nothing else in Kav uses it.",
            "Kav משלמת עם חשבון רב-פס שלכם, ושום דבר אחר ב-Kav לא משתמש בו.",
        ), fontSize = 11.sp, color = K.dim, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
            Text(T("Payment history", "היסטוריית תשלומים"), fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clickable(role = Role.Button) { model.payHistoryOpen = true }.padding(vertical = K.gap1))
            Text(if (card == null) T("Add a card", "הוספת כרטיס") else T("Change card", "החלפת כרטיס"), fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clickable(role = Role.Button) { Payer.addingCard = true; model.payOpen = true }.padding(vertical = K.gap1))
            Text(T("Sign out", "התנתקות"), fontSize = 14.sp, color = K.muted,
                modifier = Modifier.clickable(enabled = !Payer.purchasing && !Payer.payingLater, role = Role.Button) { leaving = true }
                    .padding(vertical = K.gap1))
        }
    }
    if (leaving) PayDialog(
        T("Sign out of payments?", "להתנתק מהתשלומים?"),
        T("Kav forgets your Rav-Pass account on this phone. To pay again you sign in with a code by SMS. The account " +
            "itself stays as it is.",
            "Kav תשכח את חשבון רב-פס שלכם בטלפון הזה. כדי לשלם שוב מתחברים עם קוד ב-SMS. החשבון עצמו נשאר כמו שהוא."),
        T("Sign out", "התנתקות"), onDismiss = { leaving = false },
    ) { leaving = false; Payer.signOut() }
}

private enum class PayStage { HOME, BUS, STATION, EXIT, RIDE }

@Composable
private fun Paying(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraAsked by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(hasLocationPermission(ctx)) }
    var scanFailed by remember { mutableStateOf(false) }
    var cameraBroken by remember { mutableStateOf(false) }
    // The bus scanned and the ride on it: the line's runs HopOn lists and the one ridden, where the rider gets off, how
    // many ride along, and HopOn's price for that. fixedEnd: HopOn said where the ride ends, so it isn't chosen.
    var bus by remember { mutableStateOf<RavPass.Bus?>(null) }
    var runs by remember { mutableStateOf<List<RavPass.Run>?>(null) }
    var runIndex by remember { mutableIntStateOf(0) }
    var to by remember { mutableStateOf<RavPass.Stop?>(null) }
    var fixedEnd by remember { mutableStateOf(false) }
    var guests by remember { mutableIntStateOf(0) }
    var guestsBlocked by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<RavPass.Quote?>(null) }
    var priceError by remember { mutableStateOf<String?>(null) }
    // HopOn's suggested rides when the code alone doesn't place the rider on a line, then the company's lines around
    // them and those found by number, to choose the ride by hand.
    var suggestions by remember { mutableStateOf<List<RavPass.Suggestion>?>(null) }
    var lines by remember { mutableStateOf<List<RavPass.Nearby>?>(null) }
    var lineQuery by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<RavPass.Nearby>?>(null) }
    // A bus of the company paid for minutes ago, waiting for the rider to say it's a new ride.
    var again by remember { mutableStateOf<RavPass.Bus?>(null) }
    // The fare's reach, as Moovit's fare picker chooses it: 15 km unless the rider picks more. Kav turns it into the stop
    // HopOn prices the ride to.
    var band by remember { mutableIntStateOf(15) }
    // Paying at a station: the mode, the station HopOn placed the rider at, and the list to choose it from when HopOn
    // asks.
    var stationMode by remember { mutableStateOf<StationMode?>(null) }
    var station by remember { mutableStateOf<RavPass.Station?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var lastFix by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Leaving the train: the entrance ticket, the exit station HopOn found, and whether the rider confirms it.
    var exiting by remember { mutableStateOf<RavPass.Ticket?>(null) }
    var exitStation by remember { mutableStateOf<RavPass.Station?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    // A train entrance still open when a new ride is asked for.
    var openEntrance by remember { mutableStateOf<RavPass.Ticket?>(null) }
    // A train entrance about to be created, which HopOn can't cancel afterwards.
    var confirmEntry by remember { mutableStateOf(false) }
    // The ticket on screen, by key.
    var shownKey by remember { mutableStateOf<String?>(null) }
    // The leg of the trip whose card asked to pay, until its ticket is bought.
    var tripLeg by remember { mutableStateOf<Int?>(null) }
    var tripKey by remember { mutableStateOf<String?>(null) }
    fun forTrip(key: String) {
        val leg = tripLeg ?: return
        val journey = tripKey
        tripLeg = null
        tripKey = null
        model.activeJourney?.takeIf { it.paymentKey == journey }
            ?.let { model.activeJourney = it.copy(paid = it.paid + (leg to key)) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // HopOn asked about a bus or a station: its step shows it loading, and backing out stops it.
    var fetching by remember { mutableStateOf<Job?>(null) }

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { withContext(NonCancellable) { work() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = failure(e)
                // No card HopOn can charge: back to adding one.
                if (RavPass.needsCard(e)) runCatching { Payer.check() }
            }
            finally { busy = false }
        }
    }

    fun look(work: suspend () -> Unit) {
        fetching?.cancel(); error = null
        fetching = scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
            fetching = null
        }
    }

    fun clearRide() {
        runs = null; runIndex = 0; to = null; fixedEnd = false; guests = 0; guestsBlocked = false; quote = null; priceError = null
        suggestions = null; lines = null; lineQuery = ""; found = null; again = null; band = 15
    }

    // The bus's run as HopOn's app finds it once HopOn named the line or where the ride ends: the run HopOn named,
    // boarded where HopOn found the rider, or where the run says when HopOn already gave the end.
    suspend fun board(b: RavPass.Bus) {
        val fix = PayFix.now(ctx) ?: throw NoFix()
        val run = Payer.call { RavPass.runs(it, b, fix) }.firstOrNull { it.routeId == b.routeId }
            ?: throw RavPass.Refused("", T("HopOn couldn't find this line's stops. Scan the code again.",
                "הופ-און לא מצאה את התחנות של הקו. סרקו את הקוד שוב."))
        val end = b.to
        val from = if (end != null) run.stops.firstOrNull { it.code == run.boarding }
            else run.stops.firstOrNull { it.code == b.from?.code } ?: b.from
        bus = b.on(run, from)
        runs = listOf(run); runIndex = 0
        fixedEnd = end != null
        if (end != null) to = end
    }

    // The company's lines around the rider, to choose the ride by hand, as HopOn's app offers when it can't tell it.
    suspend fun around(b: RavPass.Bus) {
        val fix = PayFix.now(ctx) ?: throw NoFix()
        lines = Payer.call { RavPass.nearby(it, b, fix) }
        suggestions = null
    }

    // What HopOn's app does after the scan: the bus's run when HopOn named the line or where the ride ends, else the
    // rides it suggests, else the company's lines around the rider.
    suspend fun follow(b: RavPass.Bus) {
        when {
            b.to != null || (b.routeId != null && b.officeLineId != null && b.from != null) -> board(b)
            b.agencyId != null && b.meanOfTransport != null -> {
                val fix = PayFix.now(ctx)
                val picks = runCatching { Payer.call { RavPass.likelyRides(it, b, fix) } }.getOrDefault(emptyList())
                if (picks.isNotEmpty()) suggestions = picks else around(b)
            }
            else -> throw RavPass.Refused(T("Service not available.", "השירות לא זמין."),
                T("HopOn doesn't know this QR code. Try scanning a different one.", "הופ-און לא מכירה את קוד ה-QR הזה. נסו לסרוק קוד אחר."))
        }
    }

    // The bus's code, as HopOn's app takes it: the phone's fix goes with it, and HopOn names the line and the stop the
    // rider boards at from its own data. A company paid for minutes ago is asked about first, as its app does.
    fun priceFor(qr: String, fromScanner: Boolean = false) {
        fetching?.cancel(); error = null
        scanFailed = false
        fetching = scope.launch {
            try {
                val fix = PayFix.now(ctx) ?: throw NoFix()
                val b = Payer.call { RavPass.scan(it, qr, fix) }
                clearRide()
                bus = b
                if (Payer.paidJustNow(b.agencyId)) again = b else follow(b)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = failure(e); scanFailed = fromScanner
            }
            fetching = null
        }
    }

    // A ride picked from HopOn's suggestions, taken as HopOn's app takes it: from where it starts to where it ends.
    fun pick(s: RavPass.Suggestion) {
        val chosen = bus?.with(s) ?: return
        suggestions = null; quote = null
        if (s.to == null) look { board(chosen) } else { bus = chosen; runs = null; fixedEnd = true; to = s.to }
    }

    // A line chosen by hand: HopOn's runs of it around the rider, the first one (or the next when the stop HopOn found
    // isn't on it), boarded where HopOn reckons on it, as its app takes them.
    fun pickLine(n: RavPass.Nearby) = look {
        val b = bus?.onLine(n) ?: return@look
        val fix = PayFix.now(ctx) ?: throw NoFix()
        val all = Payer.call { RavPass.runs(it, b, fix) }
        if (all.isEmpty()) throw RavPass.Refused("", T("HopOn has no rides of line ${n.line} around you now.",
            "להופ-און אין עכשיו נסיעות של קו ${n.line} סביבכם."))
        val i = if (b.stopOnly && all[0].stops.none { it.code == b.from?.code } && all.size > 1) 1 else 0
        val run = all[i]
        val from = run.stops.firstOrNull { b.stopOnly && it.code == b.from?.code } ?: run.stops.firstOrNull { it.code == run.boarding }
        bus = b.on(run, from); runs = all; runIndex = i; fixedEnd = false; to = null
        lines = null; lineQuery = ""; found = null
    }

    // The line's other run, as HopOn's app switches direction: boarded where HopOn reckons on it.
    fun switchRun() {
        val all = runs?.takeIf { it.size > 1 } ?: return
        val b = bus ?: return
        val i = (runIndex + 1) % all.size
        bus = b.on(all[i], all[i].stops.firstOrNull { it.code == all[i].boarding }); runIndex = i; to = null
    }

    // The company's lines by the number typed, as HopOn's app searches them.
    LaunchedEffect(lineQuery) {
        val b = bus ?: return@LaunchedEffect
        val q = lineQuery.trim()
        found = null
        if (q.isEmpty()) return@LaunchedEffect
        delay(400)
        found = runCatching { Payer.call { RavPass.searchLines(it, b, q) } }.getOrDefault(emptyList())
    }

    // Where a ride of the chosen reach ends on the run, when HopOn didn't say.
    LaunchedEffect(bus, runs, runIndex, band, fixedEnd) {
        if (fixedEnd) return@LaunchedEffect
        val run = runs?.getOrNull(runIndex) ?: return@LaunchedEffect
        val from = bus?.from ?: return@LaunchedEffect
        to = reach(run.stops, from.code, band)
    }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraAllowed = ok
        // Refused twice, Android stops asking and the button would do nothing: Kav's settings page is the way back.
        val activity = ctx as? android.app.Activity
        if (!ok && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            runCatching {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", ctx.packageName, null)))
            }
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        located = hasLocationPermission(ctx)
    }

    fun scan() {
        cameraAsked = true
        cameraAllowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraAllowed) askCamera.launch(Manifest.permission.CAMERA)
        else if (!hasLocationPermission(ctx)) askLocation.launch(LOCATION_PERMISSIONS)
    }

    LaunchedEffect(Unit) { runCatching { Payer.refresh() } }
    // The timetable that lists the stations takes seconds to read; start while the rider scans.
    LaunchedEffect(Unit) { runCatching { loadNet(ctx.applicationContext) } }
    // The phone's fix, kept while Pay is open as HopOn's app keeps it while it is.
    DisposableEffect(located) {
        val on = located
        if (on) PayFix.start(ctx)
        onDispose { if (on) PayFix.stop() }
    }

    // Opened on a ticket from the home screen's card or a trip's: closing it returns there.
    var returnOnClose by remember { mutableStateOf(false) }
    fun leave() { returnOnClose = false; model.payOpen = false }
    LaunchedEffect(Payer.showRef) {
        Payer.showRef?.let { shownKey = it; Payer.showRef = null; returnOnClose = true }
    }

    fun clearBus() {
        fetching?.cancel(); fetching = null
        bus = null; clearRide()
    }

    fun clearStation() {
        fetching?.cancel(); fetching = null
        stationMode = null; station = null; choosing = false; quote = null; guests = 0; guestsBlocked = false
        exiting = null; exitStation = null; confirmExit = false
    }

    // The station HopOn places the rider at from the phone's fix, as its app asks, else the list, so paying at a station
    // works from anywhere. picked: chosen from the list or the trip's own, sent by its own position.
    fun stationAtPosition(m: StationMode, picked: Pair<Double, Double>?) = look {
        choosing = false; station = null; quote = null
        if (picked != null) { station = Payer.call { RavPass.station(it, m.kind, RavPass.spot(picked)) }; return@look }
        val fix = PayFix.now(ctx)
        if (fix == null) { choosing = true; return@look }
        lastFix = fix.lat to fix.lon
        try { station = Payer.call { RavPass.station(it, m.kind, fix) } }
        catch (e: RavPass.Refused) { if (e.code == RavPass.NO_STATION || e.code == RavPass.CHOOSE_STATION) choosing = true else throw e }
    }

    // Paying at a station of mode m. at: a trip's own station, sent as one chosen by hand.
    fun startStation(m: StationMode, at: Pair<Double, Double>? = null) {
        if (busy) return
        clearBus(); clearStation()
        stationMode = m; guests = 0
        stationAtPosition(m, at)
    }

    // The exit station the same way: HopOn finds it from the phone's fix, else it's chosen from the list.
    fun exitAtPosition(picked: Pair<Double, Double>?) = look {
        choosing = false; exitStation = null; quote = null
        if (picked != null) { exitStation = Payer.call { RavPass.checkout(it, RavPass.spot(picked)) }; return@look }
        val fix = PayFix.now(ctx)
        if (fix == null) { choosing = true; return@look }
        lastFix = fix.lat to fix.lon
        try { exitStation = Payer.call { RavPass.checkout(it, fix) } }
        catch (e: RavPass.Refused) { if (e.code == RavPass.NO_STATION || e.code == RavPass.CHOOSE_STATION) choosing = true else throw e }
    }

    // Leaving the train: the exit station HopOn finds, else chosen from the list.
    fun startExit(t: RavPass.Ticket) {
        if (busy) return
        clearBus(); clearStation()
        exiting = t; stationMode = StationMode.TRAIN
        exitAtPosition(null)
    }

    // The entrance's own station, first in the list of exit stations: leaving where the ride began closes it with no
    // distance travelled. HopOn's station codes are the timetable's own.
    val entranceStation by produceState<Spot?>(null, exiting) {
        val code = exiting?.fromCode?.toIntOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val net = loadNet(ctx.applicationContext)
                net.lat.indices.firstOrNull { net.code[it] == code && net.stopType[it] == StationMode.TRAIN.gtfs }
                    ?.let { Spot(net.name[it], net.lat[it], net.lon[it], null) }
            }.getOrNull()
        }
    }

    // HopOn's price for the ride to the exit station, as its app asks before the exit ticket.
    LaunchedEffect(exitStation) {
        val e = exitStation ?: return@LaunchedEffect
        val t = exiting ?: return@LaunchedEffect
        quote = null
        quote = runCatching { Payer.call { RavPass.exitQuote(it, e, t) } }.getOrNull()
    }

    // Before a new ride, as HopOn's app does: a train entrance still open is ended first.
    fun beforeNew(go: () -> Unit) {
        val now = System.currentTimeMillis()
        val open = Payer.tickets.firstOrNull { it.valid(now) && it.needsExit }
        if (open != null) openEntrance = open else go()
    }

    // Backing out of leaving the train returns to that ticket; backing out of a trip's ride, to the trip.
    fun backOut() {
        val ride = exiting?.id?.toString()
        scanFailed = false; clearBus(); clearStation()
        shownKey = ride
        if (ride == null && returnOnClose) { tripLeg = null; leave() }
    }

    // A trip card's pay row: straight to that ride's way of paying, and the ticket goes back to that ride's card.
    LaunchedEffect(model.payFor) {
        val p = model.payFor ?: return@LaunchedEffect
        model.payFor = null
        tripLeg = p.leg; tripKey = p.journey; returnOnClose = true
        beforeNew { val m = p.station; if (m == null) scan() else startStation(m, p.at) }
    }

    // The code of a bus whose ticket is still valid shows that ticket, not a new payment.
    fun onScanned(qr: String) {
        val now = System.currentTimeMillis()
        val same = Payer.tickets.firstOrNull { it.valid(now) && Payer.codeOf(it) == qr }
        if (same != null) { shownKey = same.id.toString(); forTrip(same.id.toString()) } else priceFor(qr, fromScanner = true)
    }

    // HopOn's price for the ride and the others riding along, which its app has before it offers to pay.
    LaunchedEffect(bus, to, guests) {
        val b = bus ?: return@LaunchedEffect
        quote = null; priceError = null
        val end = to ?: return@LaunchedEffect
        if (b.from == null || b.routeId == null) return@LaunchedEffect
        try { quote = Payer.call { RavPass.quote(it, b, end, guests) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { priceError = failure(e) }
    }
    // And at a station, for the rider and the others riding along.
    LaunchedEffect(station, guests) {
        val st = station ?: return@LaunchedEffect
        val m = stationMode ?: return@LaunchedEffect
        quote = null
        quote = runCatching { Payer.call { RavPass.stationQuote(it, m.kind, st, null, guests) } }.getOrNull()
    }
    // With every price, HopOn's app checks the account's payment methods and takes the others riding along off when one
    // of them blocks them: on a bus with the ride's price, at a light rail or Carmelit station with 1.
    LaunchedEffect(quote) {
        val q = quote ?: return@LaunchedEffect
        val b = bus
        val st = station?.takeIf { stationMode?.kind == RavPass.Kind.OTHER && exiting == null }
        val qr = b?.qr ?: st?.qr ?: return@LaunchedEffect
        val allowed = runCatching { Payer.call { RavPass.guestsAllowed(it, qr, if (b != null) q.total else null) } }.getOrDefault(true)
        guestsBlocked = !allowed
        if (!allowed && guests > 0) guests = 0
    }

    // Pays once HopOn has priced the ride. HopOn's app prices the others riding along once more right before paying for
    // them, and pays only if that works.
    fun pay() {
        val b = bus ?: return
        val end = to ?: return
        if (quote?.total == null) return
        val count = guests
        run {
            var asOccasional = false
            val t = Payer.purchase(b.qr, prepare = {
                asOccasional = Payer.occasional()
                if (count > 0) Payer.call { RavPass.quote(it, b, end, count) }
            }) { Payer.call { RavPass.buy(it, b.qr, count, asOccasional) } }
            Payer.paidBus(b.agencyId)
            val key = t.id.toString()
            shownKey = key
            forTrip(key)
            clearBus()
        }
    }

    fun payStation() {
        val st = station ?: return
        val m = stationMode ?: return
        val count = if (m.kind == RavPass.Kind.OTHER) guests else 0
        run {
            val t = Payer.purchase(st.qr, prepare = {
                // HopOn's app prices the train ride, and other rides with others along, right before paying.
                if (m.kind == RavPass.Kind.TRAIN || count > 0)
                    Payer.call { RavPass.stationQuote(it, m.kind, st, null, count) }
            }) { Payer.call { RavPass.enter(it, m.kind, st, count) } }
            val key = t.id.toString()
            shownKey = key
            forTrip(key)
            clearStation()
        }
    }

    val stage = when {
        shownKey != null -> PayStage.RIDE
        exiting != null -> PayStage.EXIT
        stationMode != null -> PayStage.STATION
        bus != null || fetching != null -> PayStage.BUS
        else -> PayStage.HOME
    }
    LaunchedEffect(stage, openEntrance) {
        // A ticket or station opened directly can change this before the effect runs.
        if (stage == PayStage.HOME && shownKey == null && stationMode == null && openEntrance == null && !cameraAsked) scan()
    }
    androidx.activity.compose.BackHandler(enabled = stage == PayStage.BUS || stage == PayStage.STATION || stage == PayStage.EXIT) { backOut() }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(error != null && stage != PayStage.RIDE) {
            error?.let { Note(it, Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap3), color = K.critical) }
        }
        androidx.compose.animation.AnimatedContent(
            targetState = stage,
            modifier = Modifier.weight(1f),
            transitionSpec = { if (targetState == PayStage.HOME) backward() else forward() },
            label = "pay",
        ) { s ->
            when (s) {
                PayStage.RIDE -> {
                    val shown = shownKey?.let { key -> Payer.tickets.firstOrNull { it.id.toString() == key } }
                    if (shown != null) TicketView(shown, onExit = { shownKey = null; startExit(shown) }, onAgain = { shownKey = null; error = null }) {
                        shownKey = null; if (returnOnClose) leave()
                    } else {
                        // The ticket is gone from today's: back to the start rather than a blank page.
                        LaunchedEffect(Unit) { shownKey = null }
                        Box(Modifier.fillMaxSize())
                    }
                }
                PayStage.BUS -> {
                    val b = bus
                    val run = runs?.getOrNull(runIndex)
                    val picks = suggestions
                    val around = lines
                    when {
                        b == null || fetching != null -> LoadingBlock(T("Finding your bus…", "מאתרים את האוטובוס…"))
                        again != null -> PayStep { LineCard(b) }
                        picks != null -> PayStep {
                            LineCard(b)
                            RidePicks(picks) { pick(it) }
                            Text(T("Another line? See the lines around you", "קו אחר? הקווים סביבכם"), fontSize = 14.sp, color = K.accent,
                                modifier = Modifier.clickable(role = Role.Button) { look { around(b) } }.padding(vertical = K.gap1))
                            CancelLink { backOut() }
                        }
                        around != null -> PayStep {
                            LineCard(b)
                            LinePicks(around, found, lineQuery, { lineQuery = it }) { pickLine(it) }
                            CancelLink { backOut() }
                        }
                        b.routeId == null -> PayStep {
                            LineCard(b)
                            Note(T("HopOn couldn't tell which line this bus is on. Scan its code again once you're on it.",
                                "הופ-און לא זיהתה באיזה קו האוטובוס הזה. סרקו את הקוד שוב כשאתם בתוכו."), color = K.problem)
                            CancelLink { backOut() }
                        }
                        else -> PinnedStep(top = {
                            LineCard(b)
                            if (run == null || fixedEnd) StopCard(T("Boarding stop", "תחנת עלייה"), b.from?.name ?: T("Not found", "לא נמצאה"))
                            else StopDropdown(T("Boarding stop", "תחנת עלייה"), run.stops, b.from) { s -> bus = b.boardingAt(s) }
                            if (fixedEnd) StopCard(T("Getting off at", "תחנת ירידה"), to?.name?.ifBlank { null } ?: T("Where HopOn says", "לפי הופ-און"))
                            if ((runs?.size ?: 0) > 1) Text(T("Other direction", "לכיוון השני"), fontSize = 14.sp, color = K.accent,
                                modifier = Modifier.clickable(role = Role.Button) { switchRun() }.padding(vertical = K.gap1))
                            val choices = if (fixedEnd || run == null) emptyList() else b.from?.let { bandsFor(b.bands, run.stops, it.code) }.orEmpty()
                            if (choices.isNotEmpty()) BandDropdown(choices, band) { band = it }
                            Passengers(guests, if (guestsBlocked) 0 else Payer.settings?.guestsMax ?: 10) { guests = it }
                            FareNotes(quote)
                        }) {
                            PriceLine(quote, to != null, priceError)
                            PayButton(T("Validate ride and charge me", "צרו לי כרטיס נסיעה וחייבו אותי בתשלום"), busy,
                                enabled = quote?.total != null) { pay() }
                            AddButton(T("Asshole mode", "מצב מנייאק"), detail = T(
                                "have a popup in the app/a \"PAY QUICK\" button in the notification so you can pay quickly when you see an inspector on the line",
                                "חלונית באפליקציה / כפתור \"תשלום מהיר!\" בהתראה, כדי שתוכלו לשלם במהירות כשרואים פקח בקו",
                            ), modifier = Modifier.border(1.dp, K.accent, RoundedCornerShape(K.rPill))) {
                                val route = b.routeId ?: return@AddButton
                                val from = b.from ?: return@AddButton
                                val end = to ?: return@AddButton
                                if (quote?.total == null) return@AddButton
                                Payer.hold(Payer.Pending(b.line, b.qr, route, b.officeLineId, from.code, end.code, guests, tripLeg, tripKey))
                                clearBus(); tripLeg = null
                                leave()
                            }
                            CancelLink { backOut() }
                        }
                    }
                }
                PayStage.STATION -> {
                    val m = stationMode ?: StationMode.TRAIN
                    val st = station
                    when {
                        choosing -> PayStep {
                            Text(m.label, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                            StationList(m, lastFix) { stationAtPosition(m, it) }
                            CancelLink { backOut() }
                        }
                        st == null -> LoadingBlock(T("Finding your station…", "מאתרים את התחנה…"))
                        else -> PinnedStep(top = {
                            Text(m.label, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                            StopCard(T("Entry station", "תחנת כניסה"), st.stop?.name ?: T("Not found", "לא נמצאה"))
                            Text(T("Not this station? Choose it", "לא התחנה הזו? בחרו אותה"), fontSize = 14.sp, color = K.accent,
                                modifier = Modifier.clickable(role = Role.Button) { choosing = true }.padding(vertical = K.gap1))
                            if (m.kind == RavPass.Kind.OTHER) Passengers(guests, if (guestsBlocked) 0 else Payer.settings?.guestsMax ?: 10) { guests = it }
                            FareNotes(quote)
                        }) {
                            StationPrice(m, st, quote)
                            PayButton(if (m == StationMode.TRAIN) T("Create entry ticket", "יצירת כרטיס כניסה")
                                else T("Validate ride and charge me", "צרו לי כרטיס נסיעה וחייבו אותי בתשלום"), busy) {
                                if (m == StationMode.TRAIN) confirmEntry = true else payStation()
                            }
                            CancelLink { backOut() }
                        }
                    }
                }
                PayStage.EXIT -> {
                    val e = exitStation
                    when {
                        choosing -> PayStep {
                            Text(T("Exit station", "תחנת יציאה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                            entranceStation?.let { s ->
                                Column(Modifier.fillMaxWidth().panel(K.rCard).clickable(role = Role.Button) { exitAtPosition(s.lat to s.lon) }
                                    .padding(horizontal = K.gap4, vertical = K.gap3)) {
                                    Text(s.name, fontSize = 15.sp, color = K.text)
                                    Text(T("Where you entered: leaving here closes the ride with no distance travelled",
                                        "התחנה שבה נכנסתם: יציאה כאן סוגרת את הנסיעה בלי מרחק נסיעה"), fontSize = 12.sp, color = K.dim)
                                }
                            }
                            StationList(StationMode.TRAIN, lastFix) { exitAtPosition(it) }
                            CancelLink { backOut() }
                        }
                        e == null -> LoadingBlock(T("Finding your station…", "מאתרים את התחנה…"))
                        else -> PayStep {
                            Text(T("Exit station", "תחנת יציאה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                            StopCard(T("Exit station", "תחנת יציאה"), e.stop?.name ?: T("Not found", "לא נמצאה"))
                            Text(T("Not this station? Choose it", "לא התחנה הזו? בחרו אותה"), fontSize = 14.sp, color = K.accent,
                                modifier = Modifier.clickable(role = Role.Button) { choosing = true }.padding(vertical = K.gap1))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(T("Estimated fare for this ride:", "המחיר המשוער לנסיעה:"), fontSize = 15.sp, color = K.muted,
                                    modifier = Modifier.weight(1f))
                                LtrText(quote?.total?.text ?: "…", 18.sp, K.text, FontWeight.Medium)
                            }
                            PayButton(T("Create exit ticket", "יצירת כרטיס יציאה"), busy) { confirmExit = true }
                            CancelLink { backOut() }
                        }
                    }
                }
                PayStage.HOME -> PayStep {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(K.rCard)).background(K.surface4),
                        contentAlignment = Alignment.Center) {
                        if (stage == PayStage.HOME && openEntrance == null && !busy && !Payer.purchasing && !Payer.payingLater) {
                            when {
                                !cameraAllowed -> Column(Modifier.padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                                    Note(T("Allow camera access to scan the bus QR code.", "אפשרו גישה למצלמה כדי לסרוק את קוד ה-QR באוטובוס."))
                                    AddButton(T("Allow camera", "אישור גישה למצלמה")) { scan() }
                                }
                                scanFailed -> AddButton(T("Scan again", "סריקה מחדש"), modifier = Modifier.padding(K.gap4)) {
                                    scanFailed = false; error = null
                                }
                                cameraBroken -> Note(T(
                                    "The camera wouldn't start. Close other apps using it and open this page again.",
                                    "המצלמה לא נפתחה. סגרו אפליקציות אחרות שמשתמשות בה ופתחו את הדף שוב.",
                                ), modifier = Modifier.padding(K.gap4))
                                else -> QrScanner(Modifier.matchParentSize(), onFail = { cameraBroken = true }) { qr ->
                                    beforeNew { onScanned(qr) }
                                }
                            }
                        }
                    }
                    Note(T("Scan the QR code by the driver or the doors.", "סורקים את הברקוד שליד הנהג או הדלתות."), color = K.muted)
                    Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Text(T("At a station", "בתחנה"), fontSize = 14.sp, color = K.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                            StationMode.entries.forEach { m -> Chip(m.label, false) { beforeNew { startStation(m) } } }
                        }
                    }
                    if (!located) Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Note(T("Kav needs your location to pay. HopOn gets it only at the moment you scan or pay, as in its own app.",
                            "Kav צריכה את המיקום שלכם כדי לשלם. הופ-און מקבלת אותו רק ברגע הסריקה או התשלום, כמו באפליקציה שלה."),
                            color = K.problem)
                        AddButton(T("Allow location", "אישור גישה למיקום")) { askLocation.launch(LOCATION_PERMISSIONS) }
                    }
                }
            }
        }
    }

    openEntrance?.let { t ->
        PayDialog(
            T("Important!", "חשוב!"),
            T("Your train ride is still open. End it at your exit station before paying for a new ride.",
                "הנסיעה ברכבת עדיין פתוחה. סיימו אותה בתחנת היציאה לפני תשלום על נסיעה חדשה."),
            T("End the train ride", "סיום הנסיעה ברכבת"), onDismiss = { openEntrance = null },
        ) { openEntrance = null; startExit(t) }
    }
    // HopOn's app stops here when the same company was paid for minutes ago: a new ride needs a new ticket, and others
    // riding along can't join a ticket already made.
    again?.let { b ->
        PayDialog(
            T("Hold on!", "רגע!"),
            if (b.agencyId == "22") T(
                "You already have a light rail ride from the last few minutes, on the home screen. If you've changed trains since, make a new ticket. Others riding with you can't be added to a ticket already made; next time, choose how many before paying.",
                "כבר יש לכם נסיעה ברכבת הקלה מהדקות האחרונות, במסך הבית. אם החלפתם רכבת מאז, צרו כרטיס חדש. אי אפשר לצרף נוסעים לכרטיס שכבר נוצר; בפעם הבאה בחרו כמה נוסעים לפני התשלום.")
            else T(
                "You paid for a ride with this company in the last few minutes; the ticket is on the home screen. If you've changed buses since, make a new ticket. Others riding with you can't be added to a ticket already made; next time, choose how many before paying.",
                "כבר שילמתם על נסיעה עם החברה הזו בדקות האחרונות, והכרטיס במסך הבית. אם החלפתם אוטובוס מאז, צרו כרטיס חדש. אי אפשר לצרף נוסעים לכרטיס שכבר נוצר; בפעם הבאה בחרו כמה נוסעים לפני התשלום."),
            T("Make a new ticket", "ליצור כרטיס חדש"), no = T("Got it", "הבנתי"),
            onDismiss = { again = null; backOut() },
        ) { again = null; Payer.forgetPaidBus(); look { follow(b) } }
    }
    if (confirmEntry) PayDialog(
        T("Start a train ride?", "להתחיל נסיעה ברכבת?"),
        T("Rav-Pass can't cancel a train entrance. Once created, the ride ends only with an exit ticket at your exit " +
            "station, or through Rav-Pass's customer service.",
            "רב-פס לא יכולה לבטל כרטיס כניסה לרכבת. אחרי שנוצר, הנסיעה מסתיימת רק בכרטיס יציאה בתחנת היציאה, " +
                "או דרך שירות הלקוחות של רב-פס."),
        T("Create entry ticket", "יצירת כרטיס כניסה"), onDismiss = { confirmEntry = false },
    ) { confirmEntry = false; payStation() }
    if (confirmExit) PayDialog(
        T("Is this your exit station?", "זו תחנת היציאה שלכם?"),
        T("The exit ticket is valid only at this station.", "כרטיס היציאה תקף רק בתחנה הזו."),
        T("Yes, this is my station", "כן, זו התחנה שלי"), onDismiss = { confirmExit = false },
    ) {
        confirmExit = false
        val t = exiting
        val e = exitStation
        if (t != null && e != null) run {
            val out = Payer.purchase(e.qr, prepare = { Payer.call { RavPass.exitQuote(it, e, t) } }) { Payer.call { RavPass.exit(it, e, t.id) } }
            shownKey = out.id.toString()
            clearStation()
        }
    }
}

// HopOn's words on how the fare is reached, under the price.
@Composable
private fun FareNotes(q: RavPass.Quote?) {
    val notes = q?.notes?.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        Text(T("Fare information:", "כיצד המחיר מחושב?"), fontSize = 13.sp, color = K.dim)
        notes.forEach { Note(it, color = K.muted) }
    }
}

// What a ride from the station costs: HopOn's price when it gives one, and for the train the most it can cost until the
// exit station sets it.
@Composable
private fun StationPrice(m: StationMode, st: RavPass.Station, q: RavPass.Quote?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        // A train entrance is priced at nothing until its exit station is known.
        q?.total?.takeIf { m != StationMode.TRAIN || it.agorot > 0 }?.let { total ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(q.km?.let { T("Up to $it km ride price", "מחיר לנסיעה עד $it ק\"מ") } ?: T("Ride price", "מחיר הנסיעה"),
                    fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f))
                LtrText(total.text, 22.sp, K.text, FontWeight.Medium)
            }
        }
        if (m == StationMode.TRAIN) Note(st.maxPrice?.let {
            T("The fare is set by your exit station, up to ${it.text}.", "המחיר נקבע לפי תחנת היציאה, עד ${it.text}.")
        } ?: T("The fare is set by your exit station.", "המחיר נקבע לפי תחנת היציאה."))
    }
}

@Composable
private fun PayStep(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
        content = content,
    )
}

// A step whose end stays at the bottom of the screen, the price and the pay button. Only what is above it scrolls, so
// the stop list opening or closing moves that part alone.
@Composable
private fun PinnedStep(top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = K.gap6 + LocalBottomBarInset.current)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
            content = top,
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(top = K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
            content = bottom,
        )
    }
}

@Composable
private fun CancelLink(onClick: () -> Unit) {
    Text(T("Cancel", "ביטול"), fontSize = 15.sp, color = K.muted,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(K.gap2))
}

// The agency's colour, or Kav's accent.
private fun agencyColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(if (hex!!.startsWith("#")) hex else "#$hex")) }.getOrDefault(K.accent)

// The line the code belongs to, as HopOn names it: its number and where it goes.
@Composable
private fun LineCard(b: RavPass.Bus) {
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(Modifier.clip(RoundedCornerShape(K.rControl)).background(agencyColor(b.color)).padding(horizontal = K.gap3, vertical = K.gap1)) {
            LtrText(b.line.ifBlank { "?" }, 18.sp, Color.White, FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Text(T("Towards", "לכיוון"), fontSize = 12.sp, color = K.dim)
            Text(b.towards.ifBlank { "-" }, fontSize = 15.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StopCard(label: String, name: String) {
    Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        Text(label, fontSize = 12.sp, color = K.dim)
        Text(name, fontSize = 15.sp, color = K.text)
    }
}

// The fare's reach, one line that opens into HopOn's others, as Moovit's fare picker: "Up to 15 km · ₪8".
@Composable
private fun BandDropdown(bands: List<RavPass.Band>, chosen: Int, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "bandChevron")
    val current = bands.firstOrNull { it.km == chosen } ?: bands.first()
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.DropdownList) { open = !open }
                .padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(T("Fare", "תעריף"), fontSize = 12.sp, color = K.dim)
                Text(T("Up to ${current.km} km", "עד ${current.km} ק\"מ"), fontSize = 15.sp, color = K.text)
            }
            LtrText(current.price.text, 15.sp, K.text, FontWeight.Medium)
            Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = turn }) {
                val w = size.width
                drawLine(K.muted, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), w * .12f, StrokeCap.Round)
                drawLine(K.muted, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), w * .12f, StrokeCap.Round)
            }
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column {
                bands.filter { it.km != current.km }.forEach { band ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onChoose(band.km); open = false }
                            .padding(horizontal = K.gap4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(T("Up to ${band.km} km", "עד ${band.km} ק\"מ"), fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f))
                        LtrText(band.price.text, 15.sp, K.text)
                    }
                }
            }
        }
    }
}

// HopOn's suggested rides on a bus it couldn't place the rider on, as its app lists them: the line, from where, to where.
@Composable
private fun RidePicks(picks: List<RavPass.Suggestion>, onPick: (RavPass.Suggestion) -> Unit) {
    Text(T("Which ride is this?", "באיזו נסיעה מדובר?"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        picks.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(s) }.padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LtrText(s.line.ifBlank { "?" }, 17.sp, K.text, FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text(listOf(s.from.name, s.fromCity).filter { it.isNotBlank() }.joinToString(", "), fontSize = 14.sp, color = K.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    s.to?.let { end ->
                        Text("→ " + listOf(end.name, s.toCity).filter { it.isNotBlank() }.joinToString(", "), fontSize = 13.sp,
                            color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// A stop on the line: one line that opens into the stops to choose from.
@Composable
private fun StopDropdown(label: String, stops: List<RavPass.Stop>, chosen: RavPass.Stop?, onChoose: (RavPass.Stop) -> Unit) {
    var open by remember { mutableStateOf(chosen == null) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "stopChevron")
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.DropdownList) { open = !open }
                .padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = K.dim)
                Text(chosen?.name ?: T("Choose your stop", "בחרו את התחנה שלכם"), fontSize = 15.sp,
                    color = if (chosen == null) K.accent else K.text)
            }
            Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = turn }) {
                val w = size.width
                drawLine(K.muted, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), w * .12f, StrokeCap.Round)
                drawLine(K.muted, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), w * .12f, StrokeCap.Round)
            }
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column {
                stops.forEach { s ->
                    Text(s.name, fontSize = 15.sp, color = if (s.code == chosen?.code) K.accent else K.muted,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onChoose(s); open = false }
                            .padding(horizontal = K.gap4, vertical = K.gap3))
                }
            }
        }
    }
}

// The number of passengers, as HopOn's app counts them: the rider and up to `max` more.
@Composable
private fun Passengers(guests: Int, max: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Row(
            Modifier.fillMaxWidth().panel(K.rCard).padding(horizontal = K.gap4, vertical = K.gap2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(T("Number of passengers", "מספר נוסעים"), fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f))
            if (max > 0) Chip("−", false) { if (guests > 0) onChange(guests - 1) }
            androidx.compose.animation.AnimatedContent(guests + 1, label = "passengers") { Text("$it", fontSize = 16.sp, color = K.text) }
            if (max > 0) Chip("+", false) { if (guests < max) onChange(guests + 1) }
        }
        if (guests > 0) Note(T("Occasional passengers pay full price, according to the Ministry of Transportation",
            "נוסעים מזדמנים משלמים מחיר מלא, בהתאם להנחיית משרד התחבורה"))
    }
}

// HopOn's price for the ride: what it costs everyone, under the distance its fare reaches. failed: why HopOn didn't
// price it.
@Composable
private fun PriceLine(q: RavPass.Quote?, chosen: Boolean, failed: String? = null) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        if (failed != null && q == null) Note(failed, color = K.critical)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Text(q?.km?.let { T("Up to $it km ride price", "מחיר לנסיעה עד $it ק\"מ") }
                ?: if (chosen) T("Pricing…", "מחשבים מחיר…") else T("Choose your stop to see the price", "בחרו תחנה כדי לראות את המחיר"),
                fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f))
            q?.total?.let { LtrText(it.text, 22.sp, K.text, FontWeight.Medium) }
        }
        Payer.settings?.pricesUrl?.let { url ->
            Text(T("How is the price calculated?", "כיצד המחיר מחושב?"), fontSize = 13.sp, color = K.accent,
                modifier = Modifier.clickable(role = Role.Button) { openLink(ctx, url) }.padding(vertical = K.gap1))
        }
    }
}

@Composable
private fun AddButton(text: String, detail: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(K.rPill)).panel(K.rPill)
            .clickable(enabled = !Payer.purchasing && !Payer.payingLater, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(horizontal = K.gap3, vertical = K.gap2), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 15.sp, color = K.accent, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = K.muted,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = K.gap1))
        }
    }
}

@Composable
private fun PayDialog(
    title: String, text: String, yes: String, no: String = T("Cancel", "ביטול"), onDismiss: () -> Unit, onYes: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
            Text(title, fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold)
            Note(text, color = K.muted)
            PayButton(yes, false, onClick = onYes)
            Text(no, fontSize = 15.sp, color = K.muted,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button, onClick = onDismiss)
                    .padding(K.gap2))
        }
    }
}

private fun clockOf(ms: Long): String = clockFormat().format(java.util.Date(ms))

private val good get() = if (K.light) Color(0xFF2E7D4F) else Color(0xFF8FD8A0)

// "Line 18 · Tel Aviv": a ticket's line and where it goes.
internal fun ticketName(t: RavPass.Ticket): String =
    listOf(t.line.takeIf { it.isNotBlank() }?.let { T("Line $it", "קו $it") }, t.towards.ifBlank { null })
        .filterNotNull().joinToString(" · ").ifBlank { t.agency.ifBlank { T("Ride", "נסיעה") } }

// Whether a ticket still counts, and until when: a time today, else its day too (a train entrance lasts until tomorrow).
internal fun ticketStatus(t: RavPass.Ticket, now: Long): String = when {
    t.valid(now) -> T("Valid until ", "בתוקף עד ") + whenOf(t.validUntilUtc, now)
    else -> T("No longer valid", "כבר לא בתוקף")
}

private fun whenOf(ms: Long, now: Long): String {
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = ISRAEL }
    val today = day.format(java.util.Date(now))
    val tomorrow = day.format(java.util.Date(now + 86_400_000L))
    return when (day.format(java.util.Date(ms))) {
        today -> clockOf(ms)
        tomorrow -> T("tomorrow ", "מחר ב-") + clockOf(ms)
        else -> java.text.SimpleDateFormat("d.M", java.util.Locale.US).apply { timeZone = ISRAEL }.format(java.util.Date(ms)) + " " + clockOf(ms)
    }
}

// Prices and counts laid out left to right, also in Hebrew.
@Composable
internal fun LtrText(
    text: String, size: TextUnit, color: Color, weight: FontWeight? = null, decoration: TextDecoration? = null,
) = Text(text, fontSize = size, color = color, fontWeight = weight, textDecoration = decoration, maxLines = 1,
    softWrap = false, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))

@Composable
internal fun rememberNow(): Long = produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000) }
}.value

// The free transfer after a ride, while it lasts.
@Composable
private fun TransferLine(until: Long) {
    Text(T("Free transfers until ${clockOf(until)}", "מעברים ללא תשלום עד ${clockOf(until)}"), fontSize = 14.sp, color = K.dim,
        modifier = Modifier.fillMaxWidth().padding(K.gap4))
}

@Composable
private fun TicketRow(t: RavPass.Ticket, now: Long, inset: Dp = K.gap4, onClick: () -> Unit) {
    val valid = t.valid(now)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = inset, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) { drawQr() }
        Column(Modifier.weight(1f)) {
            Text(ticketName(t) + if (t.guests > 0) "  ×${t.guests + 1}" else "", fontSize = 15.sp,
                color = if (valid) K.text else K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(T("Bought ", "נקנה ב-") + clockOf(t.boughtUtc), fontSize = 13.sp, color = K.dim)
        }
        Text(ticketStatus(t, now), fontSize = 13.sp, color = if (valid) good else K.dim, maxLines = 1)
    }
}

// Under "Pay for a ride" on the home screen: every ticket still valid, newest first, then More for the payment history.
// HopOn is asked again each minute while the card shows.
@Composable
internal fun PayTicketsCard(model: KavModel) {
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Payer.refresh() }
            delay(60_000)
        }
    }
    val now = rememberNow()
    val live = Payer.tickets.filter { it.valid(now) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Payer.transferUntil(now)?.let { TransferLine(it) }
        live.forEachIndexed { i, t ->
            Box(Modifier.popIn(i, t.id)) { TicketRow(t, now) { Payer.showRef = t.id.toString(); model.payOpen = true } }
        }
        Text(T("More", "עוד"), fontSize = 15.sp, color = K.accent,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { model.payHistoryOpen = true }
                .padding(horizontal = K.gap4, vertical = K.gap3))
    }
}

// Asshole mode floating over every screen: a compact rounded popup with the kept ride, paid with one tap or dropped
// with the red cross; then "Paid", which opens the ticket. It can be dragged out of the way.
@Composable
internal fun PendingFloat(model: KavModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = Payer.pending
    val paid = Payer.paidLater
    LaunchedEffect(paid) {
        if (paid == null) return@LaunchedEffect
        delay(5_000)
        if (Payer.paidLater == paid) Payer.paidLater = null
    }
    var dropping by remember { mutableStateOf(false) }
    if (dropping && p != null) PayDialog(
        T("Close quick pay?", "לסגור את התשלום המהיר?"),
        T("To pay for this ride after closing it, you'll have to scan the bus's code again.",
            "כדי לשלם על הנסיעה אחרי הסגירה, תצטרכו לסרוק שוב את הקוד של האוטובוס."),
        T("Yes, close", "כן, לסגור"), no = T("No, keep it", "לא, להשאיר"), onDismiss = { dropping = false },
    ) { dropping = false; Payer.dropPending() }
    var moved by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(Rect.Zero) }
    var room by remember { mutableStateOf(Size.Zero) }
    val shape = RoundedCornerShape(K.rCard)
    androidx.compose.animation.AnimatedVisibility(
        p != null || paid != null,
        modifier = modifier.absoluteOffset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) }
            .onGloballyPositioned { box = it.boundsInRoot(); room = it.findRootCoordinates().size.toSize() },
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(
            Modifier.width(140.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val d = Offset(drag.x.coerceIn(-box.left, room.width - box.right), drag.y.coerceIn(-box.top, room.height - box.bottom))
                        moved += d; box = box.translate(d)
                    }
                }
                .shadow(12.dp, shape).clip(shape).background(K.surface1).border(1.dp, K.border, shape),
        ) {
            if (p != null) {
                Column(Modifier.fillMaxWidth().padding(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1),
                        modifier = Modifier.padding(end = 26.dp).heightIn(min = 26.dp)) {
                        Canvas(Modifier.size(14.dp)) { drawQr() }
                        Text(T("Bus", "אוטובוס") + " · " + T("Line ${p.line}", "קו ${p.line}"), fontSize = 13.sp, color = K.text,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Payer.laterError?.let {
                        Text(it, fontSize = 11.sp, lineHeight = 13.sp, color = K.critical,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(K.rPill))
                            .background(if (Payer.payingLater) K.surface4 else K.accent)
                            .clickable(enabled = !Payer.payingLater, role = Role.Button) {
                                Payer.payLater(ctx, { key, leg, journey -> TripService.notePaid(ctx, key, leg, journey) })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (Payer.payingLater) T("Paying…", "משלמים…")
                            else T("PAY QUICK", "תשלום מהיר!"),
                            fontSize = 13.sp, color = if (Payer.payingLater) K.muted else K.onAccent, fontWeight = FontWeight.Medium,
                            maxLines = 1)
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(RoundedCornerShape(K.rPill))
                        .background(K.critical).clickable(enabled = !Payer.payingLater, role = Role.Button) { dropping = true },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
            } else if (paid != null) {
                Column(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        Payer.showRef = paid.first; Payer.paidLater = null; model.payOpen = true
                    }.padding(K.gap2),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("✓", fontSize = 28.sp, color = good, fontWeight = FontWeight.SemiBold)
                    Text(T("Paid", "שולם"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
                    Text(T("Show ticket", "הצגת הכרטיס"), fontSize = 12.sp, color = K.accent)
                }
            }
        }
    }
}

// What a trip card's pay row asks Pay for: its leg, so the ticket goes back to that ride's card; the station mode (none
// for a bus, paid by its code); and the trip's own boarding station, sent by its own position.
internal class PayFor(val leg: Int, val station: StationMode?, val at: Pair<Double, Double>?, val journey: String?)

// A ride's own payment inside its trip's cards, paid the way its mode is: the code on board a bus; light rail, train and
// Carmelit at the station. Once bought, its ticket, as on the home screen.
@Composable
internal fun TripPay(model: KavModel, legIndex: Int, ride: Moovit.Leg, r: Moovit.Resolved) {
    val agency = r.line(ride.lineId)?.agencyId ?: -1
    val type = r.routeType(agency)
    val station = when {
        isRail(type, agency) -> StationMode.TRAIN
        else -> when (modeOf(type)) {
            Mode.BUS -> null
            Mode.TRAM, Mode.SUBWAY -> StationMode.LIGHT_RAIL
            Mode.FUNICULAR, Mode.CABLE -> StationMode.CARMELIT
            else -> return
        }
    }
    val board = r.stop(ride.fromStop) ?: r.stop(ride.stops.firstOrNull() ?: -1)
    val key = model.activeJourney?.paid?.get(legIndex)
    val now = rememberNow()
    val bought = key?.let { k -> Payer.tickets.firstOrNull { it.id.toString() == k } }
    LaunchedEffect(key) { if (key != null) runCatching { Payer.refresh() } }
    // Paying gets a card of its own under the ride's line.
    Spacer(Modifier.height(K.gap3))
    Column(Modifier.fillMaxWidth().panel(K.rControl)) {
        if (bought != null) TicketRow(bought, now, inset = K.gap3) { Payer.showRef = bought.id.toString(); model.payOpen = true }
        if (bought == null || (!bought.valid(now) && !bought.needsExit)) Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    model.payFor = PayFor(legIndex, station, if (station != null) board?.point else null, model.activeJourney?.paymentKey)
                    model.payOpen = true
                }
                // The title's font keeps room above its capitals, so half a dp less on top centres the text you see.
                .padding(start = K.gap3, end = K.gap3, top = 11.5.dp, bottom = 12.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Canvas(Modifier.size(20.dp)) { drawQr() }
            Column(Modifier.weight(1f)) {
                Text(when (station) {
                    null -> if (bought == null) T("Pay for this ride", "תשלום על הנסיעה") else T("Pay again", "תשלום מחדש")
                    StationMode.TRAIN -> T("Pay at the train station", "תשלום בתחנת הרכבת")
                    StationMode.LIGHT_RAIL -> T("Pay at the light rail station", "תשלום בתחנת הרכבת הקלה")
                    StationMode.CARMELIT -> T("Pay at the Carmelit station", "תשלום בתחנת הכרמלית")
                }, fontSize = 15.sp, color = K.accent, fontWeight = FontWeight.Medium)
                Text(if (station == null) T("Scan the QR code on the bus", "סורקים את קוד ה-QR באוטובוס")
                    else board?.name ?: T("Kav finds the station you're at", "Kav מאתרת את התחנה שבה אתם נמצאים"),
                    fontSize = 12.sp, color = K.dim)
            }
        }
    }
}

// Modes paid at a station: HopOn's kind of ride, and the timetable's own route type to find the station on the phone.
internal enum class StationMode(val kind: RavPass.Kind, val gtfs: Int) {
    LIGHT_RAIL(RavPass.Kind.OTHER, 0), TRAIN(RavPass.Kind.TRAIN, 2), CARMELIT(RavPass.Kind.OTHER, 7);

    val label get() = when (this) {
        LIGHT_RAIL -> T("Light rail", "רכבת קלה")
        TRAIN -> T("Train", "רכבת")
        CARMELIT -> T("Carmelit", "כרמלית")
    }
}

// A station of a mode from the timetable, and how far it is from the last fix when there is one.
private class Spot(val name: String, val lat: Double, val lon: Double, val metres: Double?)

// Every station of the mode, one per name, nearest first when the phone's position is known, else by name.
private fun stationsOf(net: Net, m: StationMode, near: Pair<Double, Double>?): List<Spot> {
    val best = HashMap<String, Spot>()
    for (i in net.lat.indices) {
        if (net.stopType[i] != m.gtfs) continue
        val d = near?.let { metres(it.first, it.second, net.lat[i], net.lon[i]) }
        val old = best[net.name[i]]
        if (old == null || (d != null && d < (old.metres ?: Double.MAX_VALUE))) best[net.name[i]] = Spot(net.name[i], net.lat[i], net.lon[i], d)
    }
    return best.values.sortedWith(compareBy<Spot>({ it.metres ?: 0.0 }, { it.name }))
}

// Choosing the station by hand. Its own position goes to HopOn, which names it back.
@Composable
private fun StationList(m: StationMode, near: Pair<Double, Double>?, onPick: (Pair<Double, Double>) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val all by produceState<List<Spot>?>(null, m, near) {
        value = withContext(Dispatchers.Default) { runCatching { stationsOf(loadNet(ctx.applicationContext), m, near) }.getOrNull() }
    }
    Note(T("Choose your station", "בחרו את התחנה שלכם"))
    KavField(query, { query = it }, T("Search stations", "חיפוש תחנות"))
    val shown = all?.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    if (shown == null) Note(T("Loading…", "טוען…"))
    else Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        shown.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(s.lat to s.lon) }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                Text(s.name, fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f))
                s.metres?.let { LtrText(distanceLabel(it), 13.sp, K.dim) }
            }
        }
    }
}

// The company's lines around the rider, as HopOn's app lists them to choose the ride by hand, with its search by number.
@Composable
private fun LinePicks(
    around: List<RavPass.Nearby>, found: List<RavPass.Nearby>?, query: String, onQuery: (String) -> Unit,
    onPick: (RavPass.Nearby) -> Unit,
) {
    Text(T("Which line are you on?", "באיזה קו אתם?"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
    KavField(query, onQuery, T("Line number", "מספר קו"), keyboard = KeyboardType.Number)
    val shown = if (query.isBlank()) around else found
    when {
        shown == null -> Note(T("Searching…", "מחפשים…"))
        shown.isEmpty() && query.isBlank() -> Note(T("HopOn has no lines around you right now. Search for yours by its number.",
            "להופ-און אין עכשיו קווים סביבכם. חפשו את הקו שלכם לפי המספר."))
        shown.isEmpty() -> Note(T("HopOn has no line by that number.", "להופ-און אין קו במספר הזה."))
        else -> Column(Modifier.fillMaxWidth().panel(K.rCard)) {
            shown.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(n) }.padding(horizontal = K.gap4, vertical = K.gap3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LtrText(n.line, 17.sp, K.text, FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        if (n.from.isNotBlank()) Text(n.from, fontSize = 14.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.to.isNotBlank()) Text("→ " + n.to, fontSize = 13.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// How far a stop is from where the ride starts, as HopOn's app measures it: as the crow flies, with Android's
// Location.distanceBetween, and nothing for a stop HopOn gives no position for.
private fun crow(a: RavPass.Stop, b: RavPass.Stop): Double {
    if (a.lat.isNaN() || a.lon.isNaN() || b.lat.isNaN() || b.lon.isNaN()) return 0.0
    val out = FloatArray(1)
    Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out)
    return out[0].toDouble()
}

// The run's stops from the boarding one on, by their place on the line, as HopOn's app takes them.
private fun ahead(run: List<RavPass.Stop>, start: RavPass.Stop) = run.filter { maxOf(it.sequence, 0) >= maxOf(start.sequence, 0) }

// HopOn's app lets the run's farthest stop decide: within 15 km of the boarding stop it prices the ride to the run's
// last stop without asking, and beyond that it asks where the ride ends. Kav asks how far instead, as Moovit does:
// HopOn's fares up to the first that reaches the farthest stop, or none when HopOn's app wouldn't ask.
internal fun bandsFor(
    bands: List<RavPass.Band>, run: List<RavPass.Stop>, from: String,
    dist: (RavPass.Stop, RavPass.Stop) -> Double = ::crow,
): List<RavPass.Band> {
    val start = run.firstOrNull { it.code == from } ?: return emptyList()
    val far = ahead(run, start).maxOfOrNull { dist(start, it) } ?: 0.0
    if (far < 15_000.0) return emptyList()
    return bands.filterIndexed { i, _ -> i == 0 || bands[i - 1].km * 1000.0 <= far }
}

// Where a ride of up to `km` ends on the run, by HopOn's app's own rule: the run's last stop when every stop from the
// boarding one on is within reach, else the last stop before the first one beyond it, or that one when it is the next.
internal fun reach(
    run: List<RavPass.Stop>, from: String, km: Int, dist: (RavPass.Stop, RavPass.Stop) -> Double = ::crow,
): RavPass.Stop? {
    val start = run.firstOrNull { it.code == from } ?: return null
    val stops = ahead(run, start)
    val beyond = stops.indexOfFirst { dist(start, it) >= km * 1000.0 }
    if (beyond < 0) return run.lastOrNull()
    return stops.take(beyond).lastOrNull { it.code != start.code } ?: stops[beyond]
}

// A ticket for the inspector, as HopOn's app shows it: each passenger's code, one a page, with its reference under it,
// bright and with no screenshots.
@Composable
private fun TicketView(t: RavPass.Ticket, onExit: () -> Unit, onAgain: () -> Unit, onClose: () -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val w = activity?.window
        val before = w?.attributes?.screenBrightness ?: -1f
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            w.attributes = w.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                w.attributes = w.attributes.apply { screenBrightness = before }
            }
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val now = rememberNow()
    val valid = t.valid(now)
    val pager = rememberPagerState { t.codes.size.coerceAtLeast(1) }
    val day = java.text.SimpleDateFormat("d.M.yyyy", java.util.Locale.US).apply { timeZone = ISRAEL }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(K.gap4)
            .padding(bottom = LocalBottomBarInset.current).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (valid) Note(if (t.needsExit) T("Place the QR code on the scanner at the gate", "הניחו את קוד ה-QR על הסורק בשער")
            else T("Present this QR code for review, whenever needed", "הציגו את קוד ה-QR הזה לביקורת, בכל פעם שיידרש"),
            color = K.muted)
        HorizontalPager(pager, Modifier.fillMaxWidth()) { i ->
            val code = t.codes.getOrNull(i)
            if (valid && code != null) QrCode(code, Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl)))
            else Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).panel(K.rControl), contentAlignment = Alignment.Center) {
                Text(T("This ticket is no longer valid", "הכרטיס הזה כבר לא בתוקף"), fontSize = 16.sp, color = K.dim)
            }
        }
        if (t.codes.size > 1) LtrText("${pager.currentPage + 1} / ${t.codes.size}", 14.sp, K.muted)
        if (t.utn.isNotBlank()) LtrText(t.utn, 14.sp, K.muted)
        Text(ticketName(t), fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(ticketStatus(t, now), fontSize = 16.sp, color = if (valid) good else K.dim, fontWeight = FontWeight.Medium)
        t.transferUntilUtc.takeIf { it > now }?.let { Note(T("Free transfers until ${clockOf(it)}", "מעברים ללא תשלום עד ${clockOf(it)}"), color = K.muted) }
        listOfNotNull(
            t.from.ifBlank { null }?.let { T("Boarded at $it", "עלייה ב$it") },
            t.passenger.ifBlank { null },
            t.profile.ifBlank { null },
            t.guests.takeIf { it > 0 }?.let { T("${it + 1} passengers", "${it + 1} נוסעים") },
            day.format(java.util.Date(t.boughtUtc)) + "  " + clockOf(t.boughtUtc),
        ).forEach { Note(it, color = K.muted) }
        t.cost?.let { LtrText(it.text, 14.sp, K.muted) }
        if (valid && t.needsExit) {
            Note(T("Important: at your destination station, tap “Create exit ticket” so the fare is set by where you got off.",
                "חשוב: בתחנת היעד הקישו על “יצירת כרטיס יציאה” כדי שהמחיר ייקבע לפי התחנה שבה ירדתם."), color = K.problem)
            PayButton(T("Create exit ticket", "יצירת כרטיס יציאה"), false, onClick = onExit)
        }
        if (!valid) AddButton(T("Pay for another ride", "תשלום על נסיעה נוספת"), onClick = onAgain)
    }
}

private object Seal {
    private const val ALIAS = "kav-pay"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun close(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
