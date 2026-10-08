package uk.noammm.kav

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.ui.Fix
import uk.noammm.kav.ui.Payer
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.journeyProgress

class TripService : Service() {

    private var foreground = false
    private var tracking: (() -> Unit)? = null
    private var fix: Fix? = null
    private var shown: TripNotice? = null
    private var postedAt = 0L

    private var cached: Pair<ActiveJourney, Int>? = null
    private var cachedAt = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            alertIfDue()
            advance()
            post()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val settle = Runnable { post() }

    private val fired = HashSet<String>()
    private var firedFor = ""
    private val watch = object : Runnable {
        override fun run() {
            alertIfDue()
            handler.postDelayed(this, ALERT_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        T.lang = Prefs.lang(this)
        uk.noammm.kav.ui.Shown.twelveHour = Prefs.twelveHour(this)
        ensureChannel(this)
        Payer.init(this)
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_END) { end(); return START_NOT_STICKY }
        if (intent?.action == ACT_PAY) pay()
        if (intent?.action == ACT_REPOST) shown = null
        if (!foreground && !goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (journey() == null) { finish(); return START_NOT_STICKY }
        post(force = true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        handler.removeCallbacks(watch)
        handler.post(watch)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tracking?.invoke()
        tracking = null
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val first = notice()?.let { build(this, it) } ?: NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(getString(R.string.app_name))
            .build()
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val located = hasLocationPermission(this) &&
            start(first, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!located && !start(first, special)) return false
        foreground = true
        if (located) tracking = trackLocation(this, ::onFix)
        return true
    }

    // Location granted after the trip started.
    private fun track() {
        if (tracking != null || !foreground || !hasLocationPermission(this)) return
        val n = notice()?.let { build(this, it) } ?: return
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (start(n, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) tracking = trackLocation(this, ::onFix)
    }

    private fun start(n: Notification, types: Int) = try {
        ServiceCompat.startForeground(this, ID, n, types)
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun onFix(location: Location) {
        val f = Fix(location.latitude, location.longitude, location.fixTime(), location.speed)
        fix = f
        // The watch timer stops while the screen sleeps, and the fix can carry the trip past the last stop's
        // window before it runs again: every fix checks first, so "get off" isn't skipped.
        alertIfDue()
        val shell = TripBridge.fix
        if (shell != null) shell(f) else advance()
        post()
    }

    private fun journey(): Pair<ActiveJourney, Int>? {
        TripBridge.journey?.let { return it() }
        val at = JourneyFile.mtime(this)
        if (at != cachedAt) { cached = JourneyFile.load(this); cachedAt = at }
        cached?.let { (journey, _) ->
            if (System.currentTimeMillis() / 1000 > journey.trip.arr + JourneyFile.KEEP_S) {
                JourneyFile.clear(this)
                cached = null
                cachedAt = -1L
            }
        }
        return cached
    }

    // Kept on disk so a restarted service doesn't repeat them.
    private fun alertIfDue() {
        val (journey, step) = journey() ?: return
        val trip = "${journey.trip.guid}:${journey.trip.dep}"
        val sent = getSharedPreferences("trip-alerts", MODE_PRIVATE)
        if (trip != firedFor) {
            firedFor = trip
            fired.clear()
            sent.getString(trip, null)?.let { fired.addAll(it.split(',')) }
        }
        val latest = listOfNotNull(fix, TripBridge.fixNow?.invoke()).maxByOrNull { it.at }
        val due = tripAlert(journey, step, latest, System.currentTimeMillis() / 1000) ?: return
        if (!fired.add(due.key)) return
        sent.edit().clear().putString(trip, fired.joinToString(",")).apply()
        alert(this, due.title, due.text)
    }

    private fun advance() {
        if (TripBridge.journey != null) return
        val (journey, step) = journey() ?: return
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        val moved = journeyProgress(
            steps, step, journey.resolved, journey.chosen, System.currentTimeMillis() / 1000, fix, canLocate(this),
        )
        if (moved == step) return
        JourneyFile.save(this, journey, moved)
        cached = journey to moved
        cachedAt = JourneyFile.mtime(this)
    }

    private fun notice(): TripNotice? {
        val (journey, step) = journey() ?: return null
        val latest = listOfNotNull(fix, TripBridge.fixNow?.invoke()).maxByOrNull { it.at }
        return tripNotice(journey, step, latest, System.currentTimeMillis() / 1000, Prefs.accent(this))?.copy(pay = when {
            Payer.payingLater -> T("Paying…", "משלמים…")
            Payer.pending != null -> T("PAY QUICK", "תשלום מהיר!")
            Payer.paidLater != null -> T("Paid", "שולם")
            else -> null
        })
    }

    // "PAY QUICK": the bus ride kept with Asshole mode is bought. A failure comes as an alert with Moovit's reason; "Paid"
    // stays on the button for a few seconds, as on the app's square.
    private fun pay() {
        Payer.payLater({ key, leg, journey -> notePaid(this, key, leg, journey) }) {
            val paid = Payer.paidLater
            if (Payer.pending != null) Payer.laterError?.let { alert(this, T("Not paid", "לא שולם"), it) }
            post(force = true)
            if (paid != null) handler.postDelayed({
                if (Payer.paidLater == paid) Payer.paidLater = null
                post(force = true)
            }, PAID_MS)
        }
        post(force = true)
    }

    private fun post(force: Boolean = false) {
        if (!foreground) return
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - postedAt)
        if (!force && wait > 0) {
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, wait)
            return
        }
        val n = notice() ?: run { finish(); return }
        if (n == shown) return
        shown = n
        postedAt = System.currentTimeMillis()
        runCatching { NotificationManagerCompat.from(this).notify(ID, build(this, n)) }
    }

    private fun end() {
        TripBridge.end?.invoke()
        JourneyFile.clear(this)
        finish()
    }

    private fun finish() {
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ID = 7
        private const val ALERT_ID = 8
        private const val CHANNEL = "navigation"
        private const val ACT_END = "uk.noammm.kav.trip.END"
        private const val ACT_REPOST = "uk.noammm.kav.trip.REPOST"
        private const val ACT_PAY = "uk.noammm.kav.trip.PAY"
        private const val PAID_MS = 5_000L
        private const val PROMOTED = "android.requestPromotedOngoing"
        private const val TICK_MS = 15_000L
        private const val MIN_GAP_MS = 2_000L
        private const val ALERT_MS = 5_000L

        private var running: TripService? = null

        fun show(ctx: Context) {
            running?.let { it.track(); it.post(); return }
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            runCatching { ctx.startForegroundService(Intent(ctx, TripService::class.java)) }
        }

        fun repost() { running?.post() }

        // A ride paid from the notification or the app's square goes on its trip leg's card: in the app's trip when it
        // is open, else in the saved one.
        fun notePaid(ctx: Context, key: String, leg: Int?, trip: String?) {
            if (leg == null || trip == null) return
            TripBridge.journey?.let { current ->
                if (current()?.first?.paymentKey == trip) TripBridge.paid?.invoke(leg, key)
                return
            }
            val (journey, step) = JourneyFile.load(ctx) ?: return
            if (journey.paymentKey != trip) return
            JourneyFile.save(ctx, journey.copy(paid = journey.paid + (leg to key)), step)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripService::class.java))
            NotificationManagerCompat.from(ctx).cancel(ALERT_ID)
        }

        fun alert(ctx: Context, title: String, text: String) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Prefs.accent(ctx))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(ALERT_ID, n) }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.deleteNotificationChannel("trip")
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Live navigation", "ניווט חי"))
                    .setDescription(T(
                        "Step-by-step guidance while a trip is on",
                        "הנחיה צעד־אחר־צעד בזמן נסיעה",
                    ))
                    .build(),
            )
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun act(ctx: Context, action: String): PendingIntent = PendingIntent.getService(
            ctx, action.hashCode(), Intent(ctx, TripService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun build(ctx: Context, n: TripNotice): Notification {
            val accent = Prefs.accent(ctx)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) live(ctx, n, accent) else compat(ctx, n, accent)
        }

        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        private fun live(ctx: Context, n: TripNotice, accent: Int): Notification {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressSegments(n.parts.map { (length, colour) -> Notification.ProgressStyle.Segment(length).setColor(colour) })
                .setProgress(n.progress)
                .setProgressTrackerIcon(Icon.createWithBitmap(trackerIcon(n.glyph, n.tint)))
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setSubText(n.arrive)
                .setLargeIcon(Icon.createWithBitmap(plateIcon(n.glyph, n.tint)))
                .setStyle(style)
                .setShortCriticalText(n.chip)
                .apply { n.pay?.let { addAction(Notification.Action.Builder(null as Icon?, it, act(ctx, ACT_PAY)).build()) } }
                .addAction(Notification.Action.Builder(null as Icon?, T("End trip", "סיום נסיעה"), act(ctx, ACT_END)).build())
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(act(ctx, ACT_REPOST))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                // The next step at a glance on the lock screen, without unlocking.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addExtras(Bundle().apply { putBoolean(PROMOTED, true) })
                .build()
        }

        private fun compat(ctx: Context, n: TripNotice, accent: Int): Notification =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setSubText(n.arrive)
                .setLargeIcon(plateIcon(n.glyph, n.tint))
                .setProgress(n.max, n.progress, false)
                .apply { n.pay?.let { addAction(0, it, act(ctx, ACT_PAY)) } }
                .addAction(0, T("End trip", "סיום נסיעה"), act(ctx, ACT_END))
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(act(ctx, ACT_REPOST))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }
}

object TripBridge {
    @Volatile var end: (() -> Unit)? = null
    @Volatile var journey: (() -> Pair<ActiveJourney, Int>?)? = null
    @Volatile var fix: ((Fix) -> Unit)? = null
    @Volatile var fixNow: (() -> Fix?)? = null
    @Volatile var paid: ((leg: Int, key: String) -> Unit)? = null
}
