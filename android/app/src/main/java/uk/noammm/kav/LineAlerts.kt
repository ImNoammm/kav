package uk.noammm.kav

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import uk.noammm.kav.data.Coming
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.comingText
import uk.noammm.kav.data.shownName
import uk.noammm.kav.data.nextWake
import uk.noammm.kav.ui.Shown
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.clockFormat
import java.util.Date

// The phone's alarm, a restart, a new clock or an alert swiped away: the alerts' notifications brought up to date and the
// next wake set, off the main thread.
class LineAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                if (intent.action == LineAlerts.ACT_QUIET) {
                    LineAlerts.quiet(ctx, intent.getLongExtra(LineAlerts.EXTRA_ID, 0), intent.getLongExtra(LineAlerts.EXTRA_UNTIL, 0))
                }
                LineAlerts.run(ctx.applicationContext)
            } finally {
                done.finish()
            }
        }.start()
    }
}

// Line alerts: between the times and on the days a rider chose, the next buses of their lines at a stop in
// the notification shade, refreshed about every minute from Kav's server. The wake is an inexact alarm, which a sleeping
// phone may hold back a few minutes.
object LineAlerts {
    const val ACT_WAKE = "uk.noammm.kav.LINE_ALERTS"
    const val ACT_QUIET = "uk.noammm.kav.LINE_ALERT_QUIET"
    const val EXTRA_ID = "alert"
    const val EXTRA_UNTIL = "until"
    // The stop a tapped alert opens on the Stations tab.
    const val EXTRA_STOP = "uk.noammm.kav.STOP"
    private const val CHANNEL = "line-alerts"
    private const val TAG = "line-alert"

    // After the alerts change, and when Kav opens (a force stop drops its alarms).
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        Thread { run(app) }.start()
    }

    // Swiped away within its window. Android sends the same when the notification times out at the window's end, which
    // then quieted the next window, the next day's for an alert the whole day.
    fun quiet(ctx: Context, id: Long, until: Long) {
        if (System.currentTimeMillis() / 1000 < until) Prefs.quietAlert(ctx, id, until)
    }

    @Synchronized fun run(ctx: Context) {
        T.lang = Prefs.lang(ctx)
        Shown.twelveHour = Prefs.twelveHour(ctx)
        val now = System.currentTimeMillis() / 1000
        val nm = NotificationManagerCompat.from(ctx)
        // With notifications off, nothing to wake for: Kav sets the alarm again when it's let post them.
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            schedule(ctx, null)
            return
        }
        val alerts = Prefs.lineAlerts(ctx)
        val quietUntil = { a: LineAlert -> Prefs.alertQuietUntil(ctx, a.id) }
        val showing = alerts.filter { it.window(now) != null && quietUntil(it) <= now }
        // The next wake first: a run the system cuts short for taking too long must not end the alerts.
        schedule(ctx, nextWake(alerts, now, quietUntil))
        // Deleted, finished or swiped away.
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
            .filter { it.tag == TAG && showing.none { a -> notificationId(a) == it.id } }
            .forEach { nm.cancel(TAG, it.id) }
        if (showing.isEmpty()) return
        // All at once and twenty seconds at most, well inside what the system allows a broadcast.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(showing.size.coerceAtMost(4))
        try {
            val found = pool.invokeAll(showing.map { a -> java.util.concurrent.Callable { runCatching { coming(ctx, a) }.getOrNull() } },
                20, java.util.concurrent.TimeUnit.SECONDS)
            showing.zip(found).forEach { (a, f) ->
                post(ctx, a, runCatching { f.get() }.getOrNull(), now, a.window(now)!!.last + 1)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun notificationId(a: LineAlert) = a.id.hashCode()

    // The alert's lines due at its stop, from the server live locations come from. Null when it can't be asked.
    private fun coming(ctx: Context, a: LineAlert): List<Coming>? {
        Prefs.loadSources(ctx)
        return runCatching { fromKav(a) }.getOrNull()
    }

    private fun fromKav(a: LineAlert): List<Coming> {
        val board = KavLive.stops(listOf(a.code))
        return board.due.filter { it.stop == a.code && it.expected > 0 }.mapNotNull { d ->
            // A train goes by where it ends, as the timetable names it; a bus by its number.
            val label = if (a.toward.isNotEmpty()) board.lines[d.route]?.to?.takeIf { to -> a.toward.any { it.name == to } }?.let(::shownName)
                else d.number.takeIf { it in a.lines }
            label?.let { Coming(it, d.expected, d.underWay) }
        }
    }

    private fun post(ctx: Context, a: LineAlert, coming: List<Coming>?, now: Long, until: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(T("Line alerts", "התראות קווים"))
                .setDescription(T(
                    "Your next buses and trains at a stop, on the days and at the times you chose",
                    "האוטובוסים והרכבות הבאים שלכם בתחנה, בימים ובשעות שבחרתם",
                ))
                .build(),
        )
        val lines = if (a.toward.isNotEmpty()) T("Trains to ", "רכבות אל ") + a.toward.joinToString(", ") { shownName(it.name) }
            else a.lines.joinToString(", ")
        val text = coming?.let { comingText(it, now) { at -> clockFormat().format(Date(at * 1000)) } }
            ?: T("Couldn't get the times. Trying again in a minute.", "לא הצלחנו לקבל את הזמנים. ננסה שוב בעוד דקה.")
        val open = PendingIntent.getActivity(
            ctx, notificationId(a),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_STOP, a.stop).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val swiped = PendingIntent.getBroadcast(
            ctx, notificationId(a),
            Intent(ctx, LineAlertReceiver::class.java).setAction(ACT_QUIET).putExtra(EXTRA_ID, a.id).putExtra(EXTRA_UNTIL, until),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("$lines at ${a.name}", "$lines ב${a.name}"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(Prefs.accent(ctx))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Gone at the window's end even if the phone holds the next wake back.
            .setTimeoutAfter((until - now).coerceAtLeast(1) * 1000)
            .setContentIntent(open)
            .setDeleteIntent(swiped)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { nm.notify(TAG, notificationId(a), n) }
    }

    private fun schedule(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, LineAlertReceiver::class.java).setAction(ACT_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
    }
}
