package uk.noammm.kav.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import uk.noammm.kav.loadNet
import uk.noammm.kav.data.Net
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.KavModel
import uk.noammm.kav.TripService
import uk.noammm.kav.Seen
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitPay
import uk.noammm.kav.data.MoovitSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The Moovit user Kav pays with. Made the first time the rider signs in to pay and renewed like the
// anonymous one, but never replaced: the payment account is tied to it. Its tokens can spend money,
// so they are kept sealed with a key that cannot leave the phone.
object Payer {
    @Volatile private var user: MoovitSession? = null
    private var store: android.content.SharedPreferences? = null
    private val lock = Mutex()
    private val purchases = PayPurchase()
    private val refreshLock = Mutex()
    private val accountState = Any()
    @Volatile private var sessionVersion = 0L
    val purchasing get() = purchases.busy

    var signedIn by mutableStateOf(false)
        private set

    var wallet by mutableStateOf<MoovitPay.Wallet?>(null)
        private set

    var showRef by mutableStateOf<String?>(null)

    // Kept on the phone only, per purchase: the bus's QR or the station's position, to add passengers to the
    // same ride without scanning again. key joins the purchases of one ride; fare and region repeat its fare;
    // mode is the station route type, or -1 on a bus. cancelled: a train entrance cancelled from Kav, which Moovit
    // then lists like any finished ride.
    class Ride(
        val qr: String, val lat: Double, val lon: Double, val atUtc: Long,
        val key: String, val fare: Int = 0, val region: Int = 0, val mode: Int = -1, val cancelled: Boolean = false,
    )
    private val rides = LinkedHashMap<String, Ride>()
    @Volatile var lastFare: Pair<Int, Int>? = null

    // Asshole mode: a bus ride kept until it is paid or dropped, by the bus's QR and fare; lat and lon are where Moovit is
    // told it is paid from. title is the fare's reach; leg and journey identify the trip ride it was for.
    class Pending(
        val title: String, val qr: String, val fare: Int, val region: Int,
        val lat: Double, val lon: Double, val count: Int, val leg: Int?,
        val journey: String? = null,
    )
    var pending by mutableStateOf<Pending?>(null)
        private set
    var payingLater by mutableStateOf(false)
        private set
    var laterError by mutableStateOf<String?>(null)
        private set
    // The kept purchase just paid: its ride's key and trip leg, while the square and the trip notification say "Paid".
    var paidLater by mutableStateOf<Pair<String, Int?>?>(null)
    private val later = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Once per process: by the app, or by the trip service paying from its notification with the app closed.
    fun init(ctx: Context) {
        if (store != null) return
        val s = ctx.getSharedPreferences("moovit-pay", Context.MODE_PRIVATE)
        store = s
        user = s.getString("user", null)?.let(Seal::open)?.let { runCatching { sessionOf(JSONObject(it)) }.getOrNull() }
        signedIn = user != null && s.getBoolean("signed-in", false)
        pending = s.getString("pending", null)?.let(Seal::open)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                Pending(o.getString("title"), o.getString("qr"), o.getInt("fare"), o.getInt("region"),
                    o.getDouble("lat"), o.getDouble("lon"), o.getInt("count"),
                    o.optInt("leg", -1).takeIf { it >= 0 }, o.optString("journey").takeIf { it.isNotBlank() })
            }.getOrNull()
        }
        s.getString("rides", null)?.let(Seal::open)?.let { json ->
            runCatching {
                val a = JSONArray(json)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val ref = o.getString("ref")
                    rides[ref] = Ride(o.getString("qr"), o.optDouble("lat", Double.NaN), o.optDouble("lon", Double.NaN),
                        o.getLong("at"), o.optString("key", ref), o.optInt("fare"), o.optInt("region"), o.optInt("mode", -1),
                        o.optBoolean("cancelled"))
                }
            }
        }
    }

    fun ride(ref: String): Ride? = synchronized(rides) { rides[ref] }

    // The ride a ticket belongs to: its first purchase's reference.
    fun rideKey(t: MoovitPay.Ticket): String = ride(t.ref)?.key ?: t.ref

    fun noteRide(ref: String, ride: Ride) {
        val json = synchronized(rides) {
            rides[ref] = ride
            // A purchase is kept for the day, a cancelled ride for the history.
            val now = System.currentTimeMillis()
            rides.entries.removeAll { it.value.atUtc < now - if (it.value.cancelled) 90 * 86_400_000L else 86_400_000L }
            JSONArray().apply {
                rides.forEach { (k, r) ->
                    val o = JSONObject().put("ref", k).put("qr", r.qr).put("at", r.atUtc).put("key", r.key)
                        .put("fare", r.fare).put("region", r.region).put("mode", r.mode)
                    if (!r.lat.isNaN()) o.put("lat", r.lat).put("lon", r.lon)
                    if (r.cancelled) o.put("cancelled", true)
                    put(o)
                }
            }.toString()
        }
        store?.edit()?.putString("rides", Seal.close(json))?.apply()
    }

    // Kept under the ticket's own time, the one Moovit's history shows it at.
    fun noteCancelled(t: MoovitPay.Ticket) {
        val r = ride(t.ref)
        noteRide(t.ref, Ride(r?.qr.orEmpty(), r?.lat ?: Double.NaN, r?.lon ?: Double.NaN, t.boughtUtc,
            r?.key ?: t.ref, r?.fare ?: 0, r?.region ?: 0, r?.mode ?: -1, cancelled = true))
    }

    // Whether the ride Moovit lists at `atUtc` is one cancelled from Kav: its history has only the time to go by, and its
    // ticket list can show a cancelled entrance under another reference than the one cancelled.
    fun cancelledAt(atUtc: Long) = synchronized(rides) {
        rides.values.minByOrNull { kotlin.math.abs(it.atUtc - atUtc) }
            ?.let { it.cancelled && kotlin.math.abs(it.atUtc - atUtc) < 30_000 } == true
    }

    fun cancelled(t: MoovitPay.Ticket) = ride(t.ref)?.cancelled == true || (t.mode == 7 && cancelledAt(t.boughtUtc))

    fun hold(p: Pending) { if (payingLater || purchasing) return; pending = p; laterError = null; keepPending() }

    fun dropPending() { if (payingLater) return; pending = null; laterError = null; keepPending() }

    private fun keepPending() {
        val p = pending
        val edit = store?.edit() ?: return
        if (p == null) edit.remove("pending")
        else edit.putString("pending", Seal.close(JSONObject()
            .put("title", p.title).put("qr", p.qr).put("fare", p.fare).put("region", p.region)
            .put("lat", p.lat).put("lon", p.lon).put("count", p.count).put("leg", p.leg ?: -1).put("journey", p.journey).toString()))
        edit.apply()
    }

    // Pays the kept ride now. Moovit is asked again for the code's fare, so a price kept for a while is never stale and
    // the purchase is the same as paying on the spot. onPaid gets the ride's key and trip leg; onDone follows either way.
    fun payLater(onPaid: (key: String, leg: Int?, journey: String?) -> Unit, onDone: () -> Unit = {}) {
        val p = pending ?: return
        if (payingLater || purchasing) return
        payingLater = true; laterError = null
        later.launch {
            try {
                val at = p.lat to p.lon
                lateinit var offer: MoovitPay.Offer
                lateinit var fare: MoovitPay.Fare
                val bought = Payer.purchase(prepare = {
                    offer = call { MoovitPay.price(it, p.qr, at) }
                    fare = offer.fares.firstOrNull { it.code == p.fare && it.regionId == p.region }
                        ?: throw MoovitPay.Refused("", T("Moovit has no fare for this code.", "ל-Moovit אין תעריף לקוד הזה."))
                }) {
                    call { MoovitPay.buy(it, offer, fare, at, p.count) }
                }
                val key = bought.first().ref
                lastFare = p.fare to p.region
                runCatching {
                    bought.map { it.ref }.distinct().forEach { ref ->
                        noteRide(ref, Ride(p.qr, Double.NaN, Double.NaN, System.currentTimeMillis(), key, p.fare, p.region))
                    }
                }
                if (pending === p) { pending = null; keepPending() }
                paidLater = key to p.leg
                onPaid(key, p.leg, p.journey)
                runCatching { refresh() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (pending === p) laterError = failure(e) }
            finally { payingLater = false; onDone() }
        }
    }

    // A purchase whose answer is lost on the way back may still have gone through. Before calling it failed, Moovit's
    // list is read again for tickets that were not there just before. The charge itself is never automatically retried.
    suspend fun purchase(
        allowOpenTrain: Boolean = false, prepare: suspend () -> Unit = {}, buy: suspend () -> List<MoovitPay.Ticket>,
    ): List<MoovitPay.Ticket> {
        return purchases.run(snapshot = { refresh().tickets }, buy = buy, prepare = {
            if (!allowOpenTrain && wallet?.tickets.orEmpty().any { it.active && it.needsExit && !cancelled(it) })
                throw MoovitPay.Refused(T("Important!", "חשוב!"), endTrainRide)
            prepare()
        })
    }

    // Today's tickets and the free-ride window, kept for the home screen as well.
    suspend fun refresh(): MoovitPay.Wallet = refreshLock.withLock {
        if (!signedIn) throw MoovitPay.Unauthorized()
        val version = sessionVersion
        val w = call { MoovitPay.tickets(it) }
        withContext(Dispatchers.Main) { if (version == sessionVersion && signedIn) wallet = w }
        w
    }

    private fun sessionOf(o: JSONObject) = MoovitSession(
        o.getString("user"), o.getString("access"), o.getString("refresh"), o.getInt("metro"), o.getLong("expires"),
    )

    private fun keep(s: MoovitSession) {
        user = s
        val o = JSONObject().put("user", s.userKey).put("access", s.accessToken).put("refresh", s.refreshToken)
            .put("metro", s.metroId).put("expires", s.accessExpiresUtc)
        store?.edit()?.putString("user", Seal.close(o.toString()))?.apply()
    }

    private suspend fun current(renew: Boolean, version: Long): MoovitSession = lock.withLock {
        if (version != sessionVersion) throw CancellationException("Payment account changed")
        val kept = user
        val now = System.currentTimeMillis() / 1000
        val next = when {
            kept == null -> Moovit.register()
            renew || kept.accessExpiresUtc - now < 60 -> Moovit.renew(kept)
            else -> kept
        }
        synchronized(accountState) {
            if (version != sessionVersion) throw CancellationException("Payment account changed")
            if (next !== kept) keep(next)
        }
        next
    }

    // A call made as the paying user, off the main thread. If Moovit stops taking its access, renew once.
    suspend fun <T> call(block: (MoovitSession) -> T): T {
        val version = sessionVersion
        val result = withContext(Dispatchers.IO) {
            try {
                block(current(false, version))
            } catch (e: MoovitPay.Unauthorized) {
                block(current(true, version))
            }
        }
        if (version != sessionVersion) throw CancellationException("Payment account changed")
        return result
    }

    fun markSignedIn() {
        signedIn = true
        store?.edit()?.putBoolean("signed-in", true)?.apply()
    }

    // Forget the paying user. Signing in again makes a new one and moves the account to it.
    fun signOut() {
        synchronized(accountState) {
            if (purchasing || payingLater) return
            sessionVersion++
            user = null
            signedIn = false
            wallet = null
            lastFare = null
            pending = null; paidLater = null; laterError = null
            synchronized(rides) { rides.clear() }
            store?.edit()?.clear()?.apply()
        }
    }
}

@Composable
fun PayScreen(model: KavModel, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Pay", "תשלום"), T("for a ride", "על נסיעה"), back = onClose)
        if (Payer.signedIn) Paying(model) else Connect(model)
    }
}

private enum class ConnectStep { START, ABOUT, SIGN_IN }

// Until a payment account is connected: what connecting means, how to set payments up in Moovit's app first, what
// Moovit sees, and then signing in.
@Composable
private fun Connect(model: KavModel) {
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
            ConnectStep.ABOUT -> ConnectAbout(model) { step = ConnectStep.SIGN_IN }
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
        Text(T("Connect a Moovit account for bus payments", "חיבור חשבון Moovit לתשלום על נסיעות באוטובוס"),
            fontSize = 22.sp, color = K.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(T("Scan the QR code on the bus, or pay at a train, light rail or Carmelit station, right from Kav.",
            "סורקים את קוד ה-QR באוטובוס, או משלמים בתחנת רכבת, רכבת קלה או כרמלית, ישירות מ-Kav."),
            fontSize = 15.sp, lineHeight = 21.sp, color = K.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(K.gap2))
        PayButton(T("Connect", "התחברות"), false, onClick = onNext)
    }
}

@Composable
private fun ConnectAbout(model: KavModel, onNext: () -> Unit) {
    // Where Moovit is told a payment is made from, as chosen under Private search.
    val place = model.seenPlace?.name
    val location = when (model.seen) {
        Seen.CITY -> T(
            "For your location it gets the centre of your town, never your GPS. You can change this under Private " +
                "search in Settings.",
            "כמיקום שלכם היא מקבלת את מרכז היישוב שלכם, אף פעם לא את ה-GPS. אפשר לשנות את זה תחת חיפוש פרטי בהגדרות.",
        )
        Seen.PLACE -> T(
            "For your location it gets ${place ?: "the place you chose"}, as you set in Settings, never your GPS.",
            "כמיקום שלכם היא מקבלת את ${place ?: "המקום שבחרתם"}, כפי שהגדרתם בהגדרות, אף פעם לא את ה-GPS.",
        )
        Seen.NONE -> T(
            "It gets no location of yours at all, as you set in Settings.",
            "היא לא מקבלת שום מיקום שלכם, כפי שהגדרתם בהגדרות.",
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        AboutCard(T("Your payments stay with Moovit", "התשלומים נשארים אצל Moovit")) {
            Note(T(
                "Every payment is made by Moovit's servers, on your own Moovit payment account. Kav has no server of " +
                    "its own, so your account, your card and your rides never reach Kav or its developer.",
                "כל תשלום מתבצע בשרתים של Moovit, בחשבון התשלום שלכם ב-Moovit. ל-Kav אין שרת משלה, כך שהחשבון, " +
                    "הכרטיס והנסיעות שלכם לא מגיעים ל-Kav או למפתח שלה.",
            ), color = K.muted)
            Note(T(
                "Your card is never in Kav. Moovit may ask you once to confirm its CVV; it goes straight to Moovit and " +
                    "isn't kept. Kav asks Moovit to pay only when you press pay.",
                "הכרטיס שלכם אף פעם לא נמצא ב-Kav. ייתכן ש-Moovit תבקש פעם אחת לאשר את ה-CVV שלו; הוא נשלח ישר " +
                    "ל-Moovit ולא נשמר. Kav מבקשת מ-Moovit לשלם רק כשאתם לוחצים על תשלום.",
            ), color = K.muted)
        }
        AboutCard(T("No Moovit payments yet?", "עוד אין לכם תשלומים ב-Moovit?")) {
            AboutStep(1, T("Open the Moovit app and tap \"Pay for a new ride\", then \"Join service\".",
                "פתחו את אפליקציית Moovit, בחרו בתשלום על נסיעה חדשה והצטרפו לשירות."))
            AboutStep(2, T("Register: your details, a payment method (a credit card), and a discount profile if " +
                "you're entitled to one.",
                "הירשמו: פרטים אישיים, אמצעי תשלום (כרטיס אשראי) ופרופיל הנחה אם אתם זכאים לו."))
            AboutStep(3, T("That's all. You can uninstall Moovit if you like, and connect here.",
                "זהו. אפשר להסיר את Moovit אם רוצים, ולהתחבר כאן."))
            Note(T(
                "The account works on one phone at a time: connecting here moves it from Moovit's app, and paying in " +
                    "Moovit's app moves it back.",
                "החשבון עובד בטלפון אחד בכל פעם: התחברות כאן מעבירה אותו מהאפליקציה של Moovit, ותשלום באפליקציה " +
                    "של Moovit מחזיר אותו לשם.",
            ))
        }
        AboutCard(T("What Moovit sees", "מה Moovit רואה")) {
            Note(T(
                "Everything else in Kav, from planning and live times to search, stations and lines, goes through an " +
                    "anonymous Moovit ID that isn't tied to you.",
                "כל השאר ב-Kav, מתכנון מסלול וזמנים בזמן אמת ועד חיפוש, תחנות וקווים, עובר דרך מזהה Moovit " +
                    "אנונימי שלא קשור אליכם.",
            ), color = K.muted)
            Note(T(
                "Only paying uses your account. Then Moovit knows the bus you scanned or the station you pay at, and " +
                    "when, because the ticket needs them.",
                "רק תשלום משתמש בחשבון שלכם. אז Moovit יודעת באיזה אוטובוס סרקתם או באיזו תחנה אתם משלמים, ומתי, " +
                    "כי הכרטיס צריך את זה.",
            ), color = K.muted)
            Note(location, color = K.muted)
            Note(T(
                "The two IDs are kept apart, though both come from this phone and its internet connection.",
                "שני המזהים נפרדים, אבל שניהם מגיעים מהטלפון הזה ומאותו חיבור לאינטרנט.",
            ))
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

// Moovit's words when a new ride is asked for while a train ride is still open.
private val endTrainRide get() = T("To avoid being charged the maximum daily rate, please end your train ride.",
    "כדי לא לחויב בתעריף היומי המרבי, סיימו את הנסיעה ברכבת.")

internal fun failure(e: Throwable): String = when (e) {
    // Moovit's title says what went wrong; its message is often only "Please try again."
    is MoovitPay.Refused -> listOf(e.title, e.message).filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" ")
    is PayPurchase.Unconfirmed -> T("Couldn't confirm the payment. Check your tickets before trying again.",
        "לא ניתן לאשר אם התשלום בוצע. בדקו את הכרטיסים לפני ניסיון נוסף.")
    else -> T("Couldn't reach Moovit. Try again.", "אין חיבור ל-Moovit. נסו שוב.")
}

@Composable
private fun PayButton(text: String, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val waiting = busy || Payer.purchasing || Payer.payingLater
    Box(
        modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(K.rPill))
            .background(if (waiting) K.surface4 else K.accent)
            .clickable(enabled = !waiting, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (waiting) T("One moment…", "רגע…") else text, fontSize = 16.sp,
            color = if (waiting) K.muted else K.onAccent, fontWeight = FontWeight.Medium)
    }
}

private sealed interface SignInStep {
    data object Loading : SignInStep
    class Terms(val terms: MoovitPay.Terms) : SignInStep
    data object Phone : SignInStep
    class Code(val phone: String) : SignInStep
    class Cvv(val card: MoovitPay.Card) : SignInStep
    class Unfinished(val missing: List<Int>) : SignInStep
    data object NoAccount : SignInStep
}

@Composable
private fun SignIn() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<SignInStep>(SignInStep.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
            busy = false
        }
    }

    // Once this phone holds the account: confirm the card if Moovit asks, else ready when Moovit lists the
    // account as connected for paying.
    suspend fun finish(missing: List<Int>, card: MoovitPay.Card?) {
        if (card != null && MoovitPay.STEP_PAYMENT_METHOD in missing) step = SignInStep.Cvv(card)
        else if (Payer.call { MoovitPay.account(it) }?.connected == true) Payer.markSignedIn()
        else step = SignInStep.Unfinished(missing)
    }

    // Tried again after registering in Moovit's app: from the start, as a new paying user.
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            // Moovit's own app signs in as a brand new user, and so does Kav.
            if (!Payer.signedIn) Payer.signOut()
            MoovitPay.newFlow()
            val s = Payer.call { MoovitPay.steps(it) }
            if (MoovitPay.STEP_PHONE !in s.missing) finish(s.missing, s.card)
            else step = s.terms?.takeIf { MoovitPay.STEP_TERMS in s.missing }?.let { SignInStep.Terms(it) } ?: SignInStep.Phone
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = failure(e); step = SignInStep.Phone
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        when (val s = step) {
            SignInStep.Loading -> Note(T("Asking Moovit…", "שואלים את Moovit…"))
            is SignInStep.Terms -> {
                val t = s.terms
                Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    Text(t.title, fontSize = 19.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Note(t.text, color = K.muted)
                    var agree = t.agree
                    t.links.forEachIndexed { i, l -> agree = agree.replace("%${i + 1}\$s", l.first) }
                    if (agree.isNotBlank()) Note(agree)
                    t.links.forEach { (label, url) ->
                        Text(label, fontSize = 14.sp, color = K.accent,
                            modifier = Modifier.clickable(role = Role.Button) { openLink(ctx, url) }.padding(vertical = K.gap1))
                    }
                }
                PayButton(t.button.ifBlank { T("Let's begin", "בואו נתחיל") }, busy) {
                    run { Payer.call { MoovitPay.acceptTerms(it, t.version) }; step = SignInStep.Phone }
                }
            }
            SignInStep.Phone -> {
                Text(T("Your phone number", "מספר הטלפון שלכם"), fontSize = 15.sp, color = K.muted)
                KavField(phone, { phone = it.filter { c -> c.isDigit() || c == '-' || c == '+' } }, "050-123-4567",
                    keyboard = KeyboardType.Phone)
                PayButton(T("Send code", "שליחת קוד"), busy) {
                    if (phone.count(Char::isDigit) >= 9) run {
                        Payer.call { MoovitPay.sendCode(it, phone) }
                        code = ""; step = SignInStep.Code(phone)
                    }
                }
            }
            is SignInStep.Code -> {
                Text(T("The code Moovit sent to ${s.phone}", "הקוד ש-Moovit שלחה ל-${s.phone}"), fontSize = 15.sp, color = K.muted)
                KavField(code, { code = it.filter(Char::isDigit).take(6) }, "123456",
                    keyboard = KeyboardType.NumberPassword, autoFocus = true)
                PayButton(T("Confirm", "אישור"), busy) {
                    if (code.length == 6) run {
                        val v = Payer.call { MoovitPay.verify(it, code, takeOver = false) }
                        // Kav can't add a card, so a number without an account is registered in Moovit's app first. The
                        // paying user that asked is dropped, and trying again starts afresh.
                        if (!v.exists) { Payer.signOut(); step = SignInStep.NoAccount }
                        else if (v.moved) finish(v.missing, v.card)
                        else Payer.call { MoovitPay.verify(it, code, takeOver = true) }.let { finish(it.missing, it.card) }
                    }
                }
                Text(T("Send it again", "שליחה מחדש"), fontSize = 14.sp, color = K.accent,
                    modifier = Modifier.clickable(role = Role.Button) { run { Payer.call { MoovitPay.sendCode(it, s.phone) } } }
                        .padding(vertical = K.gap2))
            }
            is SignInStep.Cvv -> {
                Text(T("Confirm your card", "אישור הכרטיס"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                Note(T(
                    "Moovit asks a newly connected phone to confirm the card on the account. Enter the CVV of " +
                        "the card ending in ${s.card.last4}, the digits on its back.",
                    "Moovit מבקשת מטלפון שהתחבר עכשיו לאשר את הכרטיס שבחשבון. הקלידו את ה-CVV של הכרטיס " +
                        "שמסתיים ב-${s.card.last4}, הספרות שבגב הכרטיס.",
                ), color = K.muted)
                KavField(cvv, { cvv = it.filter(Char::isDigit).take(4) }, "CVV",
                    keyboard = KeyboardType.NumberPassword, autoFocus = true, secret = true)
                PayButton(T("Confirm card", "אישור הכרטיס"), busy) {
                    if (cvv.length >= 3) run {
                        val digits = cvv
                        cvv = ""
                        Payer.call { MoovitPay.confirmCard(it, digits) }
                        finish(emptyList(), null)
                    }
                }
            }
            SignInStep.NoAccount -> {
                Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Text(T("No payment account", "אין חשבון תשלום"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Note(T("Please register a payment account in the Moovit app and try again.",
                        "נא לרשום חשבון תשלום באפליקציה של Moovit ולנסות שוב."), color = K.muted)
                }
                PayButton(T("Try again", "ניסיון נוסף"), busy) { phone = ""; code = ""; error = null; step = SignInStep.Loading; attempt++ }
            }
            is SignInStep.Unfinished -> Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5),
                verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                Text(T("Not ready to pay yet", "עוד לא מוכן לתשלום"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                Note(if (MoovitPay.STEP_PAYMENT_METHOD in s.missing) T(
                    "Moovit needs a card on this account. Add one in Moovit's app, then sign in here again and " +
                        "Kav will ask for its CVV.",
                    "Moovit צריכה כרטיס אשראי בחשבון. הוסיפו אחד באפליקציה של Moovit, ואז התחברו כאן שוב ו-Kav " +
                        "תבקש את ה-CVV שלו.",
                ) else T(
                    "Finish setting up payments in Moovit's app, then sign in here again.",
                    "סיימו להגדיר תשלומים באפליקציה של Moovit, ואז התחברו כאן שוב.",
                ))
            }
        }
        error?.let { Note(it, color = K.critical) }
    }
}

// Settings' payments group, once paying is set up: the account, its history, and signing out.
@Composable
internal fun PaymentsSection(model: KavModel) {
    var account by remember { mutableStateOf<MoovitPay.Account?>(null) }
    var linked by remember { mutableStateOf(true) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { Payer.call { MoovitPay.account(it) } }.onSuccess { account = it; linked = it?.connected == true }
    }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(14.dp).padding(K.gap3).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Text(account?.name?.ifBlank { null } ?: T("Your Moovit account", "חשבון ה-Moovit שלכם"), fontSize = 15.sp, color = K.text)
        account?.phone?.takeIf { it.isNotBlank() }?.let { LtrText(it, 13.sp, K.dim) }
        if (!linked) Text(T(
            "Your Moovit account isn't connected to Kav any more, for example after paying in Moovit's app. Sign " +
                "out and sign in again to bring it back.",
            "חשבון ה-Moovit שלכם כבר לא מחובר ל-Kav, למשל אחרי תשלום באפליקציה של Moovit. התנתקו והתחברו " +
                "מחדש כדי להחזיר אותו.",
        ), fontSize = 11.sp, color = K.problem, lineHeight = 16.sp)
        Text(T(
            "Kav pays through a Moovit user of its own, used for nothing else. The account can be on one phone " +
                "at a time, so paying in Moovit's app moves it back there.",
            "Kav משלמת דרך משתמש Moovit משלה, שלא משמש לשום דבר אחר. החשבון יכול להיות בטלפון אחד בכל פעם, " +
                "ולכן תשלום באפליקציה של Moovit מעביר אותו בחזרה לשם.",
        ), fontSize = 11.sp, color = K.dim, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
            Text(T("Payment history", "היסטוריית תשלומים"), fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clickable(role = Role.Button) { model.payHistoryOpen = true }.padding(vertical = K.gap1))
            Text(T("Sign out", "התנתקות"), fontSize = 14.sp, color = K.muted,
                modifier = Modifier.clickable(enabled = !Payer.purchasing && !Payer.payingLater, role = Role.Button) { leaving = true }
                    .padding(vertical = K.gap1))
        }
    }
    if (leaving) MoovitDialog(
        T("Sign out of payments?", "להתנתק מהתשלומים?"),
        T("Kav forgets its paying user on this phone. To pay again you sign in with a code by SMS and confirm " +
            "your card's CVV. Your Moovit account itself stays as it is.",
            "Kav תשכח את משתמש התשלום שלה בטלפון הזה. כדי לשלם שוב מתחברים עם קוד ב-SMS ומאשרים את ה-CVV של " +
                "הכרטיס. חשבון ה-Moovit שלכם עצמו נשאר כמו שהוא."),
        T("Sign out", "התנתקות"), onDismiss = { leaving = false },
    ) { leaving = false; Payer.signOut() }
}

private fun fareLabel(f: MoovitPay.Fare): String {
    val km = km(f.radius.toDouble())
    val from = f.from; val to = f.to
    return when {
        from != null && to != null && from == to -> T("Up to $km km in $from", "עד $km ק\"מ ב$from")
        from != null && to != null -> T("Up to $km km, $from to $to", "עד $km ק\"מ, מ$from ל$to")
        from != null -> T("Up to $km km from $from", "עד $km ק\"מ מ$from")
        to != null -> T("Up to $km km to $to", "עד $km ק\"מ ל$to")
        else -> T("Up to $km km", "עד $km ק\"מ")
    }
}

@Composable
private fun Paying(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<MoovitPay.Account?>(null) }
    val wallet = Payer.wallet
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraAsked by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    var cameraBroken by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<MoovitPay.Offer?>(null) }
    var chosen by remember { mutableStateOf<MoovitPay.Fare?>(null) }
    // The other passengers on this purchase, as Moovit's guest picker counts them, and whether it is open.
    var guests by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    // The ride on screen, by key, and the tickets just bought, shown until Moovit's list has them.
    var shownKey by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf<List<MoovitPay.Ticket>>(emptyList()) }
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
    // Passengers being added to the ride on screen, in a sheet over it: the ride, its prices once Moovit answers, how
    // many guests, and whether they were bought.
    var adding by remember { mutableStateOf<RideGroup?>(null) }
    var again by remember { mutableStateOf<Again?>(null) }
    var addGuests by remember { mutableIntStateOf(1) }
    var addError by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // The scanned code's fares while Moovit is asked for them: the fare step shows them loading, and backing out stops it.
    var fetching by remember { mutableStateOf<Job?>(null) }
    var stationMode by remember { mutableStateOf<StationMode?>(null) }
    var step by remember { mutableStateOf<MoovitPay.StationStep?>(null) }
    var stationAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var origin by remember { mutableIntStateOf(0) }
    var picks by remember { mutableStateOf<List<Moovit.StopInfo>>(emptyList()) }
    var exiting by remember { mutableStateOf<MoovitPay.Ticket?>(null) }
    var exit by remember { mutableStateOf<MoovitPay.Exit?>(null) }
    var exitAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var manualExit by remember { mutableStateOf(false) }
    // Moovit's questions before acting: a train entrance still open, the exit station, cancelling an entrance.
    var openEntrance by remember { mutableStateOf<MoovitPay.Ticket?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<MoovitPay.Ticket?>(null) }

    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { withContext(NonCancellable) { work() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = failure(e) }
            finally { busy = false }
        }
    }

    fun priceFor(qr: String, fromScanner: Boolean = false) {
        fetching?.cancel(); error = null
        scanFailed = false
        fetching = scope.launch {
            try {
                val got = Payer.call { MoovitPay.price(it, qr, model.payAt(ctx)) }
                if (got.fares.isEmpty()) {
                    error = T("Moovit has no fare for this code.", "ל-Moovit אין תעריף לקוד הזה.")
                    scanFailed = fromScanner
                }
                else {
                    scanned = qr
                    val last = Payer.lastFare
                    guests = 0
                    offer = got
                    chosen = got.fares.firstOrNull { it.radius == 15_000 }
                        ?: got.fares.firstOrNull { last != null && it.code == last.first && it.regionId == last.second }
                        ?: got.fares.firstOrNull()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = failure(e); scanFailed = fromScanner
            }
            fetching = null
        }
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

    fun scan() {
        cameraAsked = true
        cameraAllowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraAllowed) askCamera.launch(Manifest.permission.CAMERA)
    }

    // Finding the station waits for a fresh fix; backing out stops it.
    var lookup by remember { mutableStateOf<Job?>(null) }
    fun look(work: suspend () -> Unit) {
        lookup?.cancel(); error = null
        lookup = scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = failure(e); stationMode = null; exiting = null
            }
        }
    }

    // The station chosen from the list, when the fix can't tell or found the wrong one; and the last fix, to sort it.
    var choosing by remember { mutableStateOf(false) }
    var lastFix by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    fun clearStation() {
        lookup?.cancel(); lookup = null
        choosing = false
        stationMode = null; step = null; stationAt = null; origin = 0; picks = emptyList()
        exiting = null; exit = null; exitAt = null; manualExit = false
    }

    // The station of that mode nearest the phone. Only the station's own position is sent, never the fix.
    suspend fun nearStation(m: StationMode): Pair<Double, Double>? = coroutineScope {
        val net = async { runCatching { loadNet(ctx.applicationContext) }.getOrNull() }
        val fix = freshFix(ctx)
        lastFix = fix
        if (fix == null) {
            error = T("Kav needs your location to find the station.", "Kav צריכה את המיקום שלכם כדי למצוא את התחנה.")
            return@coroutineScope null
        }
        val near = net.await()?.let { nearestStation(it, fix, m) }
        if (near == null) error = m.noneNear
        near
    }

    suspend fun showStep(s: MoovitPay.StationStep) {
        step = s
        val ids = s.pickOrigin.ifEmpty { s.pickDestination }
        picks = if (ids.isEmpty()) emptyList() else stopNames(ids)
    }

    // Moovit's station purchase: find the station, confirm it (or pick from Moovit's list), then the fare summary.
    // at: a trip's own boarding station. Without a station from the fix, the rider chooses it from a list.
    fun startStation(m: StationMode, at: Pair<Double, Double>? = null) {
        if (busy) return
        clearStation()
        stationMode = m; guests = 0
        look {
            val here = at ?: nearStation(m)
            if (here == null) { choosing = true; return@look }
            stationAt = here
            showStep(Payer.call { MoovitPay.station(it, here, m.routeType) })
        }
    }

    fun chooseStation(m: StationMode, at: Pair<Double, Double>) {
        choosing = false; step = null; picks = emptyList(); origin = 0; error = null
        look {
            stationAt = at
            showStep(Payer.call { MoovitPay.station(it, at, m.routeType) })
        }
    }

    fun pickStation(info: Moovit.StopInfo) = run {
        val m = stationMode ?: return@run
        val s = step ?: return@run
        val at = if (!info.lat.isNaN()) info.lat to info.lon else stationAt ?: return@run
        if (s.pickOrigin.isNotEmpty()) {
            origin = info.id; stationAt = at
            showStep(Payer.call { MoovitPay.station(it, at, m.routeType, origin = info.id) })
        } else {
            showStep(Payer.call { MoovitPay.station(it, stationAt ?: at, m.routeType, origin = origin, destination = info.id) })
        }
    }

    // A train ride paid from a trip's card gets off at that trip's station, as it got in at the trip's.
    fun tripExit(t: MoovitPay.Ticket): Pair<Double, Double>? {
        val j = model.activeJourney ?: return null
        val leg = j.paid.entries.firstOrNull { it.value == Payer.rideKey(t) }?.key ?: return null
        val ride = j.trip.legs.getOrNull(leg) ?: return null
        val wait = j.trip.legs.getOrNull(leg - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }
        return j.resolved.stop(boardingChoice(ride, wait, j.chosen[leg] ?: 0).first.toStop)?.point
    }

    fun startExit(t: MoovitPay.Ticket) {
        if (busy) return
        clearStation()
        exiting = t
        look {
            val at = tripExit(t) ?: nearStation(StationMode.TRAIN)
            if (at == null) { exiting = null; return@look }
            exitAt = at
            val e = Payer.call { MoovitPay.exitPrice(it, at) }
            exit = e
            picks = if (e.pick.isNotEmpty()) stopNames(e.pick) else emptyList()
        }
    }

    fun pickExit(info: Moovit.StopInfo) = run {
        if (info.lat.isNaN()) return@run
        val at = info.lat to info.lon
        exitAt = at; manualExit = true; picks = emptyList()
        exit = Payer.call { MoovitPay.exitPrice(it, at) }
    }

    // Whether Moovit still has the payment account on Kav's paying user; paying in Moovit's app moves it back there.
    var linked by remember { mutableStateOf(true) }
    LaunchedEffect(reload) {
        try {
            account = Payer.call { MoovitPay.account(it) }
            linked = account?.connected == true
            Payer.refresh()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
    }

    // The timetable that places the phone at a station takes seconds to read; start while the rider chooses.
    LaunchedEffect(Unit) { runCatching { loadNet(ctx.applicationContext) } }

    LaunchedEffect(Unit) {
        while (true) { delay(60_000); runCatching { Payer.refresh() } }
    }

    // Opened on a ride from the home screen's card or a trip's: closing it returns there.
    var returnOnClose by remember { mutableStateOf(false) }
    fun leave() { returnOnClose = false; model.payOpen = false }
    LaunchedEffect(Payer.showRef) {
        Payer.showRef?.let { shownKey = it; Payer.showRef = null; returnOnClose = true }
    }

    // As Moovit's app does before any new purchase: a train entrance still open must be ended first.
    fun beforeNew(go: () -> Unit) {
        val open = wallet?.tickets.orEmpty().firstOrNull { it.active && it.needsExit && !Payer.cancelled(it) }
        if (open != null) openEntrance = open else go()
    }

    // Backing out of leaving the train returns to that ride; backing out of paying for a trip's ride, to the trip.
    fun backOut() {
        val ride = exiting?.let { Payer.rideKey(it) }
        fetching?.cancel(); fetching = null
        scanFailed = false; offer = null; chosen = null; guests = 0; clearStation()
        shownKey = ride
        if (ride == null && returnOnClose) { tripLeg = null; leave() }
    }

    // A trip card's pay row: straight to its ride's way of paying, and the ticket goes back to that ride's card.
    LaunchedEffect(model.payFor) {
        val p = model.payFor ?: return@LaunchedEffect
        model.payFor = null
        tripLeg = p.leg; tripKey = p.journey; returnOnClose = true
        beforeNew { if (p.station == null) scan() else startStation(p.station, p.at) }
    }

    // The code of the bus whose ride is running shows the tickets already bought on it, not a new purchase.
    fun onScanned(qr: String) {
        val now = System.currentTimeMillis()
        val same = ridesOf(wallet?.tickets.orEmpty()).firstOrNull { r ->
            !r.canPayAgain(wallet?.window, now) && r.tickets.any { Payer.ride(it.ref)?.qr == qr }
        }
        if (same != null) { shownKey = same.key; forTrip(same.key) } else priceFor(qr, fromScanner = true)
    }

    // The ride's own bus fare or station, priced by Moovit again for passengers added to it.
    suspend fun againFor(r: RideGroup, rec: Payer.Ride): Again {
        if (rec.qr.isNotBlank()) {
            val at = model.payAt(ctx)
            val o = Payer.call { MoovitPay.price(it, rec.qr, at) }
            val f = o.fares.firstOrNull { it.code == rec.fare && it.regionId == rec.region }
                ?: o.fares.firstOrNull { it.radius == r.first.radius }
                ?: throw MoovitPay.Refused("", T("Moovit has no fare for this code.", "ל-Moovit אין תעריף לקוד הזה."))
            val q = runCatching { Payer.call { MoovitPay.quote(it, o, f, at) } }.getOrNull()
            return Again(q, f.cost) { n -> Payer.call { MoovitPay.buy(it, o, f, at, n) } }
        }
        val m = StationMode.entries.firstOrNull { it.routeType == rec.mode } ?: throw java.io.IOException("no station")
        val at = rec.lat to rec.lon
        val st = Payer.call { MoovitPay.station(it, at, m.routeType) }.station ?: throw java.io.IOException("no station")
        val q = runCatching { Payer.call { MoovitPay.quote(it, st, m.routeType) } }.getOrNull()
        return Again(q, st.cost) { n ->
            Payer.call { MoovitPay.enter(it, st, at, m.routeType, n, picked = false) }
        }
    }

    var pricing by remember { mutableStateOf<Job?>(null) }
    fun startAdding(r: RideGroup) {
        val rec = Payer.ride(r.first.ref) ?: Payer.ride(r.newest.ref) ?: return
        adding = r; again = null; addGuests = 1; addError = null; added = false
        pricing?.cancel()
        pricing = scope.launch {
            try { again = againFor(r, rec) } catch (e: CancellationException) { throw e } catch (e: Exception) { addError = failure(e) }
        }
    }

    fun stopAdding() {
        pricing?.cancel(); pricing = null
        adding = null; again = null; addError = null
    }

    // Buys the guests and, as every Moovit purchase does, my own ticket again; all of them join the ride.
    fun buyAdded() {
        val r = adding ?: return
        val a = again ?: return
        val rec = Payer.ride(r.first.ref) ?: Payer.ride(r.newest.ref) ?: return
        if (busy) return
        val count = addGuests + 1
        busy = true; addError = null
        scope.launch { withContext(NonCancellable) {
            try {
                val bought = Payer.purchase { a.buy(count) }
                runCatching {
                    bought.map { it.ref }.distinct().forEach { ref ->
                        Payer.noteRide(ref, Payer.Ride(rec.qr, rec.lat, rec.lon, System.currentTimeMillis(), r.key,
                            rec.fare, rec.region, rec.mode))
                    }
                }
                runCatching { Payer.refresh() }
                fresh = bought
                added = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { addError = failure(e) }
            finally { busy = false }
        } }
    }

    val stage = when {
        shownKey != null -> PayStage.RIDE
        offer != null || fetching != null -> PayStage.FARE
        exiting != null -> PayStage.EXIT
        stationMode != null -> PayStage.STATION
        else -> PayStage.HOME
    }
    LaunchedEffect(stage, openEntrance) {
        // A ticket or station opened directly can change these before this effect runs.
        if (stage == PayStage.HOME && shownKey == null && stationMode == null && openEntrance == null && !cameraAsked) scan()
    }
    androidx.activity.compose.BackHandler(enabled = stage != PayStage.HOME && stage != PayStage.RIDE) { backOut() }

    // Moovit's GetPrice for the chosen fare or station: my ticket and each other passenger's.
    var fareQuote by remember { mutableStateOf<MoovitPay.Quote?>(null) }
    LaunchedEffect(offer, chosen) {
        fareQuote = null
        val o = offer ?: return@LaunchedEffect
        val f = chosen ?: return@LaunchedEffect
        fareQuote = runCatching { Payer.call { MoovitPay.quote(it, o, f, model.payAt(ctx)) } }.getOrNull()
    }
    var stationQuote by remember { mutableStateOf<MoovitPay.Quote?>(null) }
    LaunchedEffect(step?.station) {
        stationQuote = null
        val st = step?.station ?: return@LaunchedEffect
        val m = stationMode ?: return@LaunchedEffect
        stationQuote = runCatching { Payer.call { MoovitPay.quote(it, st, m.routeType) } }.getOrNull()
    }

    Box(Modifier.fillMaxSize()) {
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
                        val rideNow = shownKey?.let { key ->
                            val all = (wallet?.tickets.orEmpty() + fresh).distinctBy { it.id to it.ref }
                            ridesOf(all).firstOrNull { it.key == key }
                        }
                        if (rideNow != null) {
                            // Moovit counts other passengers on every mode but the train; a ride needs its code or station.
                            val train = rideNow.tickets.any { it.mode == 7 }
                            val known = Payer.ride(rideNow.first.ref) ?: Payer.ride(rideNow.newest.ref)
                            RideView(
                                rideNow, wallet?.window,
                                onAdd = if (train || known == null) null else ({ beforeNew { startAdding(rideNow) } }),
                                onExit = { t -> shownKey = null; startExit(t) },
                                onCancel = { t -> cancelling = t },
                                onPayAgain = {
                                    shownKey = null; fresh = emptyList(); offer = null; chosen = null; guests = 0
                                    clearStation()
                                    tripLeg = model.activeJourney?.paid?.entries?.firstOrNull { it.value == rideNow.key }?.key
                                    tripKey = model.activeJourney?.paymentKey
                                    beforeNew {
                                        if (known?.qr?.isNotBlank() == true) priceFor(known.qr)
                                        else {
                                            val m = StationMode.entries.firstOrNull { it.routeType == known?.mode }
                                            if (m != null) startStation(m, known?.takeIf { !it.lat.isNaN() }?.let { it.lat to it.lon })
                                        }
                                    }
                                },
                            ) { shownKey = null; fresh = emptyList(); reload++; if (returnOnClose) leave() }
                        } else {
                            // The ride is gone from Moovit's list: back to the start rather than a blank page.
                            LaunchedEffect(Unit) { shownKey = null }
                            Box(Modifier.fillMaxSize())
                        }
                    }
                    PayStage.FARE -> {
                        val o = offer
                        if (o == null) LoadingBlock(T("Loading…", "טוען…"))
                        else {
                            val f = chosen
                            val sum = MoovitPay.summary(fareQuote, f?.cost, guests)
                            PinnedStep(top = {
                                FareDropdown(o.fares, chosen) { chosen = it }
                                Passengers(f?.let { reach(it) }, sum, canAdd = true, onAdd = { picking = true }) { guests = 0 }
                            }) {
                                Total(sum, busy = busy || f == null) {
                                    if (f != null) run {
                                        val bought = Payer.purchase { Payer.call { MoovitPay.buy(it, o, f, model.payAt(ctx), sum.count) } }
                                        val key = bought.first().ref
                                        Payer.lastFare = f.code to f.regionId
                                        scanned?.let { qr ->
                                            runCatching {
                                                bought.map { it.ref }.distinct().forEach { ref ->
                                                    Payer.noteRide(ref, Payer.Ride(qr, Double.NaN, Double.NaN,
                                                        System.currentTimeMillis(), key, f.code, f.regionId))
                                                }
                                            }
                                        }
                                        runCatching { Payer.refresh() }
                                        fresh = bought; shownKey = key
                                        forTrip(key)
                                        offer = null; chosen = null; guests = 0
                                    }
                                }
                                if (f != null) AddButton(T("Asshole mode", "מצב מנייאק"), detail = T(
                                    "have a popup in the app/a \"PAY QUICK\" button in the notification so you can pay quickly when you see an inspector on the line",
                                    "חלונית באפליקציה / כפתור \"תשלום מהיר!\" בהתראה, כדי שתוכלו לשלם במהירות כשרואים פקח בקו",
                                ), modifier = Modifier.border(1.dp, K.accent, RoundedCornerShape(K.rPill))) {
                                    val qr = scanned ?: return@AddButton
                                    val at = model.payAt(ctx)
                                    Payer.hold(Payer.Pending(
                                        T("${km(f.radius.toDouble())} km", "${km(f.radius.toDouble())} ק\"מ"), qr, f.code, f.regionId,
                                        at.first, at.second, sum.count, tripLeg, tripKey,
                                    ))
                                    offer = null; chosen = null; guests = 0; tripLeg = null
                                    leave()
                                }
                                CancelLink { backOut() }
                            }
                        }
                    }
                    PayStage.EXIT -> if (picks.isEmpty() && exit?.pick?.isEmpty() != true) LoadingBlock(T("Loading…", "טוען…")) else PayStep {
                        val e = exit
                        Text(T("Exit Station", "תחנת יציאה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                        if (picks.isNotEmpty()) StopPicks(e?.title, picks) { pickExit(it) }
                        else if (e != null) {
                            StationCard(T("Exit station:", "תחנת יציאה:"), e.name)
                            e.price?.let { p ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(T("Estimated fare for this ride:", "המחיר המשוער לנסיעה:"), fontSize = 15.sp, color = K.muted,
                                        modifier = Modifier.weight(1f))
                                    PriceText(p, e.full, 15.sp)
                                }
                            }
                            PayButton(T("Create exit ticket", "יצירת כרטיס יציאה"), busy) { confirmExit = true }
                        }
                        CancelLink { backOut() }
                    }
                    PayStage.STATION -> {
                        val m = stationMode ?: StationMode.TRAIN
                        val st = step?.station
                        if (choosing || picks.isNotEmpty()) PayStep {
                            Text(m.label, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                            if (choosing) StationList(m, lastFix) { chooseStation(m, it) }
                            else StopPicks(step?.title, picks) { pickStation(it) }
                            CancelLink { backOut() }
                        } else if (st == null) LoadingBlock(T("Loading…", "טוען…"))
                        else {
                            val sum = MoovitPay.summary(stationQuote, st.cost, guests)
                            PinnedStep(top = {
                                Text(m.label, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                                StationCard(T("Entry station:", "תחנת כניסה:"), st.name)
                                Text(T("Not this station? Choose it", "לא התחנה הזו? בחרו אותה"), fontSize = 14.sp, color = K.accent,
                                    modifier = Modifier.clickable(role = Role.Button) { choosing = true }.padding(vertical = K.gap1))
                                Passengers(null, sum, canAdd = true, onAdd = { picking = true }) { guests = 0 }
                            }) {
                                Total(sum, busy) {
                                    val at = stationAt ?: return@Total
                                    run {
                                        val bought = Payer.purchase {
                                            Payer.call { MoovitPay.enter(it, st, at, m.routeType, sum.count, picked = origin != 0) }
                                        }
                                        val key = bought.first().ref
                                        runCatching {
                                            bought.map { it.ref }.distinct().forEach { ref ->
                                                Payer.noteRide(ref, Payer.Ride("", at.first, at.second, System.currentTimeMillis(), key, mode = m.routeType))
                                            }
                                        }
                                        runCatching { Payer.refresh() }
                                        fresh = bought; shownKey = key
                                        forTrip(key)
                                        clearStation(); guests = 0
                                    }
                                }
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
                        Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                            Text(T("At a station", "בתחנה"), fontSize = 14.sp, color = K.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                                StationMode.entries.forEach { m -> Chip(m.label, false) { beforeNew { startStation(m) } } }
                            }
                        }
                        if (!linked) {
                            Note(T(
                                "Your Moovit account isn't connected to Kav any more, for example after paying in " +
                                    "Moovit's app. Sign in again to bring it back.",
                                "חשבון ה-Moovit שלכם כבר לא מחובר ל-Kav, למשל אחרי תשלום באפליקציה של Moovit. " +
                                    "התחברו מחדש כדי להחזיר אותו.",
                            ), color = K.problem)
                            AddButton(T("Sign in again", "התחברות מחדש")) { Payer.signOut() }
                        }
                    }
                }
            }
        }
        if (picking) GuestsSheet(guests, onDismiss = { picking = false }) { guests = it; picking = false }
        if (adding != null && stage == PayStage.RIDE) AddSheet(again, addGuests, busy, addError, added,
            onGuests = { addGuests = it }, onDismiss = { stopAdding() }) { buyAdded() }
    }

    openEntrance?.let { t ->
        MoovitDialog(
            T("Important!", "חשוב!"),
            endTrainRide, T("End active ride", "סיום הנסיעה הפעילה"), onDismiss = { openEntrance = null },
        ) { openEntrance = null; startExit(t) }
    }
    if (confirmExit) MoovitDialog(
        T("Is this your exit station?", "זו תחנת היציאה שלכם?"),
        T("Choose 'Yes' to create an exit ticket, valid only for this station",
            "בחרו 'כן' כדי ליצור כרטיס יציאה, שתקף רק בתחנה הזו"),
        T("Yes, this is my station", "כן, זו התחנה שלי"), onDismiss = { confirmExit = false },
    ) {
        confirmExit = false
        val t = exiting
        val at = exitAt
        val e = exit
        if (t != null && at != null && e != null) run {
            val out = Payer.purchase(allowOpenTrain = true) { Payer.call { MoovitPay.exit(it, at, t.fromStopId, t.ref, manualExit) } }
            runCatching { Payer.refresh() }
            fresh = out
            shownKey = out.firstOrNull()?.let { Payer.rideKey(it) } ?: Payer.rideKey(t)
            clearStation()
        }
    }
    cancelling?.let { t ->
        MoovitDialog(
            T("Cancel your purchase?", "לבטל את הרכישה?"),
            T("Your entrance tickets will be canceled and unusable. You won't be charged.",
                "כרטיסי הכניסה שלכם יבוטלו ולא יהיה אפשר להשתמש בהם. לא תחויבו."),
            T("Yes, cancel (0.00 ILS)", "כן, לבטל (0.00 ₪)"),
            no = T("No, keep ticket", "לא, להשאיר את הכרטיס"), onDismiss = { cancelling = null },
        ) {
            cancelling = null
            run {
                val at = Payer.ride(t.ref)?.takeIf { !it.lat.isNaN() }?.let { it.lat to it.lon } ?: model.payAt(ctx)
                val out = try {
                    Payer.purchase(allowOpenTrain = true) {
                        Payer.call { MoovitPay.exit(it, at, t.fromStopId, t.ref, manual = false, cancel = true) }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { shownKey = null; throw e }
                (listOf(t) + out).forEach { Payer.noteCancelled(it) }
                val key = Payer.rideKey(t)
                model.activeJourney?.let { j ->
                    val legs = j.paid.filterValues { it == key }.keys
                    if (legs.isNotEmpty()) model.activeJourney = j.copy(paid = j.paid - legs)
                }
                runCatching { Payer.refresh() }
                shownKey = null; fresh = emptyList()
                if (returnOnClose) leave()
            }
        }
    }
}

private enum class PayStage { HOME, FARE, STATION, EXIT, RIDE }

@Composable
private fun PayStep(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4)
            .padding(bottom = K.gap6 + LocalBottomBarInset.current).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap4),
        content = content,
    )
}

// A step whose end stays at the bottom of the screen, as Moovit's fare summary keeps its total and button there. Only
// what is above it scrolls, so the fare list opening or closing moves that part alone.
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

// Moovit's fare summary (MotPricesSummaryFragment), in Moovit's words: my ticket and the other passengers, whose cross
// takes them off, then the button that adds them. subtitle is Moovit's line under each passenger on a bus.
@Composable
private fun Passengers(subtitle: String?, s: MoovitPay.Summary, canAdd: Boolean, onAdd: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        PassengerRow(T("My ticket", "הכרטיס שלי"), subtitle, s.me, null)
        if (s.guests > 0) PassengerRow(T("Other passenger", "נוסע נוסף"), subtitle, s.other, s.guests, onClear)
        if (canAdd) AddButton(
            if (s.count > 1) T("Edit passengers", "עריכת נוסעים") else T("+ Add a passenger", "+ הוספת נוסע"), onClick = onAdd,
        )
    }
}

// The summary's end, kept at the bottom of the screen as Moovit keeps it: the estimated total and the validate button.
@Composable
private fun Total(s: MoovitPay.Summary, busy: Boolean, onPay: () -> Unit) {
    s.total?.let { total ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Text(T("Total (estimate):", "סה\"כ (הערכה):"), fontSize = 15.sp, color = K.muted)
                LtrText("X ${s.count}", 13.sp, K.dim)
                Spacer(Modifier.weight(1f))
                PriceText(total, s.full, 22.sp)
            }
            Note(T("Final fare calculated at the end of the day", "המחיר הסופי מחושב בסוף היום"))
        }
    }
    PayButton(validateText(s), busy, onClick = onPay)
}

// Moovit's validate button: free when the total is nothing, and counted when others ride along.
private fun validateText(s: MoovitPay.Summary): String {
    val free = s.total?.agorot == 0L
    return when {
        free && s.count == 1 -> T("Validate - no charge", "תיקוף - ללא חיוב")
        free -> T("Free rides for ${s.count} passengers", "נסיעות חינם ל-${s.count} נוסעים")
        s.count == 1 -> T("Pay for my ride", "תשלום על הנסיעה שלי")
        else -> T("Pay for ${s.count} passengers", "תשלום עבור ${s.count} נוסעים")
    }
}

// One passenger card of Moovit's summary, with the first discount reason Moovit gives above it; onRemove adds a cross.
@Composable
private fun PassengerRow(title: String, subtitle: String?, cost: MoovitPay.Cost?, count: Int?, onRemove: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        cost?.reasons?.firstOrNull()?.let { Text(it, fontSize = 12.sp, color = K.accent) }
        Row(
            Modifier.fillMaxWidth().panel(K.rCard).padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = K.text)
                subtitle?.let { Text(it, fontSize = 13.sp, color = K.dim) }
            }
            cost?.let { PriceText(it.price, it.full, 15.sp) }
            count?.let { LtrText("X $it", 13.sp, K.muted) }
            onRemove?.let {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button, onClickLabel = T("Remove", "הסרה"), onClick = it),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 12.sp, color = K.muted) }
            }
        }
    }
}

@Composable
private fun PriceText(price: MoovitPay.Price, full: MoovitPay.Price?, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        if (full != null && full.agorot > price.agorot) LtrText(full.text, size * 0.75f, K.dim, decoration = TextDecoration.LineThrough)
        LtrText(price.text, size, K.text, FontWeight.Medium)
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

// Moovit's station header on its entry and exit screens: "Entry station:" and the station's name.
@Composable
private fun StationCard(label: String, name: String) {
    Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        Text(label, fontSize = 13.sp, color = K.dim)
        Text(name, fontSize = 17.sp, color = K.text)
    }
}

@Composable
private fun GuestStepper(n: Int, least: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).padding(horizontal = K.gap4, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Text(T("Other passenger", "נוסע נוסף"), fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f))
        Chip("−", false) { if (n > least) onChange(n - 1) }
        androidx.compose.animation.AnimatedContent(n, label = "guests") { Text("$it", fontSize = 16.sp, color = K.text) }
        Chip("+", false) { if (n < 8) onChange(n + 1) }
    }
}

@Composable
private fun GuestsSheet(guests: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    var n by remember { mutableIntStateOf(guests) }
    BottomSheet(onDismiss) { close ->
        Text(T("Guests", "אורחים"), fontSize = 13.sp, color = K.dim)
        Spacer(Modifier.height(K.gap2))
        GuestStepper(n, 0) { n = it }
        Spacer(Modifier.height(K.gap4))
        PayButton(T("Continue", "המשך"), false) { close { onDone(n) } }
    }
}

// Passengers added to a ride are bought on its own bus code or station again: Moovit's prices for my ticket and each
// guest's, and the purchase for that many riders.
private class Again(val quote: MoovitPay.Quote?, val cost: MoovitPay.Cost?, val buy: suspend (Int) -> List<MoovitPay.Ticket>)

// Adding passengers in place, over the ride. Moovit buys my own ticket again with every purchase; inside the ride's free
// window that costs nothing, so it is shown only when it costs something. The math is Moovit's fare summary.
@Composable
private fun AddSheet(
    again: Again?, guests: Int, busy: Boolean, error: String?, done: Boolean,
    onGuests: (Int) -> Unit, onDismiss: () -> Unit, onBuy: () -> Unit,
) {
    BottomSheet(onDismiss, scrolls = true) { close ->
        if (done) LaunchedEffect(Unit) { close(onDismiss) }
        Text(T("Add passengers", "הוספת נוסעים"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(K.gap3))
        GuestStepper(guests, 1) { if (!busy && !Payer.purchasing) onGuests(it) }
        Spacer(Modifier.height(K.gap3))
        val s = again?.let { MoovitPay.summary(it.quote, it.cost, guests) }
        val total = s?.total
        if (s == null) { if (error == null) Note(T("Pricing…", "מחשבים מחיר…")) }
        else Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            PassengerRow(T("Other passenger", "נוסע נוסף"), null, s.other, guests)
            val mine = s.me?.takeIf { it.price.agorot > 0 }
            if (mine != null) {
                PassengerRow(T("My ticket", "הכרטיס שלי"), null, mine, null)
                Note(T("Moovit includes your own ticket again with this purchase.",
                    "Moovit כוללת מחדש גם את הכרטיס שלכם ברכישה הזו."))
            }
            total?.let {
                // The full fare struck beside it is for the tickets listed here.
                val full = if (mine != null) s.full else s.other?.full?.let { f -> MoovitPay.Price(f.agorot * guests, f.code) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("Total (estimate):", "סה\"כ (הערכה):"), fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f))
                    PriceText(it, full, 22.sp)
                }
            }
            Note(T("Final fare calculated at the end of the day", "המחיר הסופי מחושב בסוף היום"))
        }
        error?.let { Spacer(Modifier.height(K.gap2)); Note(it, color = K.critical) }
        Spacer(Modifier.height(K.gap4))
        val what = if (guests == 1) T("Add a passenger", "הוספת נוסע") else T("Add $guests passengers", "הוספת $guests נוסעים")
        PayButton(total?.let { "$what · ${it.text}" } ?: what, busy || total == null, onClick = onBuy)
    }
}

@Composable
private fun MoovitDialog(
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

// Moovit's line under each passenger on a bus: the fare's region and how far it reaches.
private fun reach(f: MoovitPay.Fare): String {
    val km = km(f.radius.toDouble())
    return listOfNotNull(f.to, T("Up to a $km km ride", "נסיעה של עד $km ק\"מ")).joinToString(" · ")
}

// The line Moovit puts above a ticket's QR, by mode.
private fun qrNote(t: MoovitPay.Ticket): String = when (t.mode) {
    7 -> if (t.needsExit) T("Place the QR code on the scanner at the gate", "הניחו את קוד ה-QR על הסורק בשער")
        else T("Please scan this code, even if the exit gate is open, to set the ride fare and avoid additional charges.",
            "סרקו את הקוד גם אם שער היציאה פתוח, כדי לקבוע את מחיר הנסיעה ולהימנע מחיובים נוספים.")
    5, 6, 8 -> T("Please note this QR code can be used to enter this station only", "שימו לב: קוד ה-QR הזה תקף לכניסה לתחנה הזו בלבד")
    else -> T("Present this QR code for review, whenever needed", "הציגו את קוד ה-QR הזה לביקורת, בכל פעם שיידרש")
}

// The fare, one line that opens into the others; the 15 km fare is picked when the code is scanned.
@Composable
private fun FareDropdown(fares: List<MoovitPay.Fare>, chosen: MoovitPay.Fare?, onChoose: (MoovitPay.Fare) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "fareChevron")
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.DropdownList) { open = !open }
                .padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(T("Fare", "תעריף"), fontSize = 12.sp, color = K.dim)
                Text(chosen?.let { fareLabel(it) } ?: T("Choose a fare", "בחרו תעריף"), fontSize = 15.sp, color = K.text)
            }
            chosen?.let { LtrText(it.price.text, 15.sp, K.text, FontWeight.Medium) }
            Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = turn }) {
                val w = size.width
                drawLine(K.muted, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), w * .12f, StrokeCap.Round)
                drawLine(K.muted, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), w * .12f, StrokeCap.Round)
            }
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column {
                fares.filter { it !== chosen }.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onChoose(f); open = false }
                            .padding(horizontal = K.gap4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fareLabel(f), fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f))
                        LtrText(f.price.text, 15.sp, K.text)
                    }
                }
            }
        }
    }
}

internal fun ticketTime(t: MoovitPay.Ticket): String {
    val f = clockFormat()
    return T("Bought ", "נקנה ב-") + f.format(java.util.Date(t.boughtUtc))
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun km(m: Double): String =
    if (m % 1000.0 == 0.0) "${(m / 1000).toInt()}" else String.format(java.util.Locale.US, "%.1f", m / 1000)

private val good get() = if (K.light) Color(0xFF2E7D4F) else Color(0xFF8FD8A0)

// "90:00/42:15": the free-ride window Moovit gives and the time left in it.
internal fun rideTime(window: MoovitPay.Window?, now: Long): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = K.dim)) {
        if (window != null && window.untilUtc > now) {
            append(clock(window.untilUtc - window.fromUtc))
            append("/")
            append(clock(window.untilUtc - now))
        } else append(if (window == null) T("No free transfers", "אין מעברים בחינם")
            else T("Transfer expired", "זמן המעבר פג"))
    }
}

// The account's transfer deadline can end while Moovit still keeps the validation. Preserve the receipt and
// server-provided QR, but never turn an elapsed transfer countdown into an "Active" label.
internal fun ticketStatus(t: MoovitPay.Ticket, window: MoovitPay.Window?, now: Long): AnnotatedString {
    val label = when {
        Payer.cancelled(t) -> T("Canceled", "בוטל")
        !t.active -> T("Inactive ticket", "כרטיס לא פעיל")
        t.anonymous || t.mode == 7 -> T("Active", "בתוקף")
        else -> null
    }
    return if (label != null) buildAnnotatedString { withStyle(SpanStyle(color = K.dim)) { append(label) } }
    else rideTime(window?.takeIf { t.boughtUtc >= it.fromUtc && t.boughtUtc < it.untilUtc }, now)
}

// The time on one line, laid out left to right. Wrapped in bidi isolates for Hebrew, Android measured it a
// few pixels short and pushed its last digit onto a second line.
@Composable
private fun TicketStatus(t: MoovitPay.Ticket, window: MoovitPay.Window?, now: Long, size: TextUnit, weight: FontWeight? = null) {
    Text(ticketStatus(t, window, now), fontSize = size, fontWeight = weight, maxLines = 1, softWrap = false,
        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
}

// Prices and counts the same way, instead of T.ltr's bidi isolates.
@Composable
internal fun LtrText(
    text: String, size: TextUnit, color: Color, weight: FontWeight? = null, decoration: TextDecoration? = null,
) = Text(text, fontSize = size, color = color, fontWeight = weight, textDecoration = decoration, maxLines = 1,
    softWrap = false, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))

// A ride is named by the distance it was paid for, "15 km"; a station ticket priced by its stations keeps
// Moovit's title.
internal fun rideName(t: MoovitPay.Ticket): String =
    if (t.radius > 0) T("${km(t.radius.toDouble())} km", "${km(t.radius.toDouble())} ק\"מ") else t.title.ifBlank { t.agency }

@Composable
internal fun rememberNow(): Long = produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000) }
}.value

// The tickets bought for one bus or station visit, newest first. Whether a ticket still counts is Moovit's to say:
// validating a new ride completes the one before it.
internal class RideGroup(val key: String, val tickets: List<MoovitPay.Ticket>) {
    val newest get() = tickets.first()
    val first get() = tickets.last()
    // A ride cancelled from Kav is over, whatever Moovit's list still says of it.
    fun live() = tickets.any { it.active && !Payer.cancelled(it) }
    val cancelled get() = tickets.all { Payer.cancelled(it) }

    // The free-transfer window controls another validation, not whether an existing QR is usable. A train entrance
    // still needs its exit first. After the window ends, keep the active ticket and offer paying for another ride.
    fun canPayAgain(window: MoovitPay.Window?, now: Long) = !live() ||
        (tickets.none { it.active && it.needsExit } && (window == null || now >= window.untilUtc))

    // The tickets to show: those Moovit still lists as active, all of them once the ride is over.
    val shown get() = tickets.filter { it.active && !Payer.cancelled(it) }.ifEmpty { tickets }
}

// This benefit can keep running after a validation is replaced or a train entrance is cancelled.
@Composable
private fun TransferWindow(window: MoovitPay.Window?, now: Long) {
    if (window == null || window.untilUtc <= now) return
    Text(window.title.ifBlank { T("Free transfers", "מעברים ללא תשלום") }, fontSize = 14.sp, color = K.dim,
        modifier = Modifier.fillMaxWidth().padding(K.gap4))
}

internal fun ridesOf(tickets: List<MoovitPay.Ticket>): List<RideGroup> =
    tickets.groupBy { Payer.rideKey(it) }.map { (k, v) -> RideGroup(k, v.sortedByDescending { it.boughtUtc }) }
        .sortedByDescending { it.newest.boughtUtc }

@Composable
private fun RideRow(r: RideGroup, window: MoovitPay.Window?, now: Long, inset: Dp = K.gap4, onClick: () -> Unit) {
    val live = r.live()
    val t = r.first
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = inset, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) { drawQr() }
        Column(Modifier.weight(1f)) {
            Text(rideName(t) + if (r.shown.size > 1) "  ×${r.shown.size}" else "",
                fontSize = 15.sp, color = if (live) K.text else K.dim)
            Text(ticketTime(t), fontSize = 13.sp, color = K.dim)
        }
        TicketStatus(r.shown.firstOrNull { !it.anonymous } ?: r.shown.first(), window, now, 13.sp)
    }
}

// Under "Pay for a ride" on the home screen: every ride still usable, newest first, then More for the
// payment history. Moovit is asked again each minute while a ride is live.
@Composable
internal fun PayTicketsCard(model: KavModel) {
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Payer.refresh() }
            delay(60_000)
        }
    }
    val wallet = Payer.wallet
    val now = rememberNow()
    val live = ridesOf(wallet?.tickets.orEmpty()).filter { it.live() }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        TransferWindow(wallet?.window, now)
        live.forEachIndexed { i, r ->
            Box(Modifier.popIn(i, r.key)) { RideRow(r, wallet?.window, now) { Payer.showRef = r.key; model.payOpen = true } }
        }
        Text(T("More", "עוד"), fontSize = 15.sp, color = K.accent,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { model.payHistoryOpen = true }
                .padding(horizontal = K.gap4, vertical = K.gap3))
    }
}

// Asshole mode floating over every screen: a compact rounded popup with the kept purchase, paid with one
// tap or dropped with the red cross; then "Paid", which opens the ticket. It can be dragged out of the way.
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
                        Text(T("Bus", "אוטובוס") + " · " + p.title, fontSize = 13.sp, color = K.text, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Payer.laterError?.let {
                        Text(it, fontSize = 11.sp, lineHeight = 13.sp, color = K.critical,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(K.rPill))
                            .background(if (Payer.payingLater) K.surface4 else K.accent)
                            .clickable(enabled = !Payer.payingLater, role = Role.Button) {
                                Payer.payLater({ key, leg, journey -> TripService.notePaid(ctx, key, leg, journey) })
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
                        .background(K.critical).clickable(enabled = !Payer.payingLater, role = Role.Button) { Payer.dropPending() },
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

// What a trip card's pay row asks Pay for: its leg, the station mode (none for a bus, paid by its code) and the
// trip's own boarding station, used instead of finding the station from the phone's fix.
internal class PayFor(val leg: Int, val station: StationMode?, val at: Pair<Double, Double>?, val journey: String?)

// A ride's own payment inside its trip's cards, paid the way its mode is: the code on board a bus; light rail, train
// and Carmelit at the station. Once bought, its ticket, as on the home screen.
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
    val wallet = Payer.wallet
    val now = rememberNow()
    val bought = key?.let { k -> ridesOf(wallet?.tickets.orEmpty()).firstOrNull { it.key == k } }?.takeIf { !it.cancelled }
    LaunchedEffect(key) { if (key != null) runCatching { Payer.refresh() } }
    Spacer(Modifier.height(K.gap2))
    Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
    if (bought != null) RideRow(bought, wallet?.window, now, inset = 0.dp) { Payer.showRef = bought.key; model.payOpen = true }
    if (bought == null || bought.canPayAgain(wallet?.window, now)) Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button) {
                model.payFor = PayFor(legIndex, station, if (station != null) board?.point else null, model.activeJourney?.paymentKey)
                model.payOpen = true
            }
            .padding(vertical = K.gap2),
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

// Modes paid at a station: Moovit's route type for the request, and the timetable's own type to find
// the station on the phone.
internal enum class StationMode(val routeType: Int, val gtfs: Int) {
    LIGHT_RAIL(MoovitPay.TRAM, 0), TRAIN(MoovitPay.RAIL, 2), CARMELIT(MoovitPay.CABLE, 7);

    val label get() = when (this) {
        LIGHT_RAIL -> T("Light rail", "רכבת קלה")
        TRAIN -> T("Train", "רכבת")
        CARMELIT -> T("Carmelit", "כרמלית")
    }

    val noneNear get() = when (this) {
        LIGHT_RAIL -> T("No light rail station near you.", "אין תחנת רכבת קלה לידכם.")
        TRAIN -> T("No train station near you.", "אין תחנת רכבת לידכם.")
        CARMELIT -> T("No Carmelit station near you.", "אין תחנת כרמלית לידכם.")
    }
}

// The nearest stop served by that mode, within a walk of the platform.
internal fun nearestStation(net: Net, at: Pair<Double, Double>, m: StationMode): Pair<Double, Double>? {
    var best = -1
    var bestM = 1_500.0
    for (i in net.lat.indices) {
        if (net.stopType[i] != m.gtfs) continue
        val d = metres(at.first, at.second, net.lat[i], net.lon[i])
        if (d < bestM) { bestM = d; best = i }
    }
    return if (best < 0) null else net.lat[best] to net.lon[best]
}

// Names and places for the stops Moovit offers to pick from, read anonymously: stops are public data.
private suspend fun stopNames(ids: List<Int>): List<Moovit.StopInfo> = withContext(Dispatchers.IO) {
    val anon = Online.open()
    ids.take(40).mapNotNull { runCatching { Moovit.stopInfo(anon, it) }.getOrNull() }
}

// Where the phone is now. The first answer can be the last known fix from up to two minutes ago, so the
// better fixes of the next few seconds replace it.
private suspend fun freshFix(ctx: Context): Pair<Double, Double>? {
    var latest: Pair<Double, Double>? = null
    val first = CompletableDeferred<Unit>()
    requestLocationOnce(ctx, onFail = { first.complete(Unit) }) { latest = it; first.complete(Unit) }
    withTimeoutOrNull(20_000) { first.await() } ?: return null
    delay(4_000)
    return latest
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

// Choosing the station by hand. Only the chosen station's own position goes to Moovit, which names it back.
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

@Composable
private fun StopPicks(title: String?, stops: List<Moovit.StopInfo>, onPick: (Moovit.StopInfo) -> Unit) {
    Note(title?.ifBlank { null } ?: T("Which station?", "באיזו תחנה?"))
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        stops.forEach { s ->
            Text(s.name, fontSize = 15.sp, color = K.text,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(s) }
                    .padding(horizontal = K.gap4, vertical = K.gap3))
        }
    }
}

// A ride for the inspector, as Moovit's QR viewer shows it: every ticket of the ride, one QR a page, bright and with
// no screenshots. Add a passenger buys on the same bus code or station again, through Moovit's fare summary.
@Composable
private fun RideView(
    r: RideGroup, window: MoovitPay.Window?, onAdd: (() -> Unit)?,
    onExit: ((MoovitPay.Ticket) -> Unit)?, onCancel: ((MoovitPay.Ticket) -> Unit)?, onPayAgain: () -> Unit, onClose: () -> Unit,
) {
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
    val live = r.live()
    val ordered = r.shown.sortedWith(compareBy<MoovitPay.Ticket>({ it.anonymous }, { it.boughtUtc }))
    val pager = rememberPagerState { ordered.size }
    val day = java.text.SimpleDateFormat("d.M.yyyy", java.util.Locale.US).apply { timeZone = ISRAEL }
    val shown = ordered.getOrNull(pager.currentPage) ?: r.newest
    val entrance = r.tickets.firstOrNull { it.active && it.needsExit && !Payer.cancelled(it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(K.gap4)
            .padding(bottom = LocalBottomBarInset.current).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (live && shown.active) Note(qrNote(shown), color = K.muted)
        HorizontalPager(pager, Modifier.fillMaxWidth()) { i ->
            val ticket = ordered[i]
            // As in Moovit, a completed ticket has no QR to show, only its details.
            if (ticket.active && !Payer.cancelled(ticket) && ticket.qr.isNotBlank())
                QrCode(ticket.qr, Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl)))
            else Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).panel(K.rControl), contentAlignment = Alignment.Center) {
                Text(if (r.cancelled) T("This ticket was canceled", "הכרטיס הזה בוטל")
                    else T("This ticket is no longer active", "הכרטיס הזה כבר לא פעיל"), fontSize = 16.sp, color = K.dim)
            }
        }
        if (ordered.size > 1) LtrText("${pager.currentPage + 1} / ${ordered.size}", 14.sp, K.muted)
        Text(rideName(shown), fontSize = 18.sp, color = K.text, fontWeight = FontWeight.SemiBold)
        TicketStatus(shown, window, now, 16.sp, FontWeight.Medium)
        if (!live && !shown.anonymous) TransferWindow(window, now)
        if (live && !shown.anonymous && window != null && window.untilUtc > now)
            window.title.takeIf { it.isNotBlank() }?.let { Note(it, color = K.muted) }
        listOfNotNull(
            shown.agency.takeIf { it.isNotBlank() && it != shown.title },
            shown.passenger.takeIf { it.isNotBlank() } ?: T("Other passenger", "נוסע נוסף"),
            shown.profile.takeIf { it.isNotBlank() },
            day.format(java.util.Date(shown.boughtUtc)) + "  " + ticketTime(shown),
        ).forEach { Note(it, color = K.muted) }
        shown.price?.let { LtrText(it.text, 14.sp, K.muted) }
        shown.ref.takeIf { it.isNotBlank() }?.let { Note(T("Reference number ", "מספר אסמכתא ") + it.take(12), color = K.muted) }
        if (live && onAdd != null) AddButton(T("+ Add a passenger", "+ הוספת נוסע"), onClick = onAdd)
        if (r.canPayAgain(window, now)) AddButton(T("Pay again", "תשלום מחדש"), onClick = onPayAgain)
        if (entrance != null) {
            Note(T(
                "Important: Upon arriving at your destination station, tap “Create exit ticket” to calculate the fare, " +
                    "otherwise you’ll be charged with the maximum daily rate.",
                "חשוב: כשתגיעו לתחנת היעד, הקישו על “יצירת כרטיס יציאה” כדי לחשב את המחיר, אחרת תחויבו בתעריף היומי המרבי.",
            ), color = K.problem)
            onExit?.let { PayButton(T("Create exit ticket", "יצירת כרטיס יציאה"), false) { it(entrance) } }
            onCancel?.let {
                Text(T("Cancel my ticket", "ביטול הכרטיס שלי"), fontSize = 14.sp, color = K.muted,
                    modifier = Modifier.clickable(role = Role.Button) { it(entrance) }.padding(K.gap2))
            }
        }
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
