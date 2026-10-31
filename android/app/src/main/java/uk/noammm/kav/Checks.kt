package uk.noammm.kav

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import uk.noammm.kav.data.AutoUpdate
import uk.noammm.kav.data.GoogleRecipe
import uk.noammm.kav.data.SignedFile
import uk.noammm.kav.data.Updates
import uk.noammm.kav.ui.UpdateRun

// Everything Kav asks GitHub for by itself (Vela's recipe, the newest release, an update to download) is asked here,
// when Android runs it about every six hours and never while Kav is open or in the half hour after, so the requests
// say nothing about when anyone uses Kav.
class Checks : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            run(app)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB = 61
        private const val PERIOD_MS = 6 * 3600_000L
        private const val FLEX_MS = 2 * 3600_000L
        private const val QUIET_MS = 30 * 60_000L

        @Volatile private var shown = false

        private fun store(ctx: Context) = ctx.getSharedPreferences("kav-checks", Context.MODE_PRIVATE)

        // Kav on the screen now, or in the last half hour.
        private fun inFront(ctx: Context): Boolean =
            shown || System.currentTimeMillis() - store(ctx).getLong("front", 0) < QUIET_MS

        // When Kav comes to the front and leaves it, for inFront; and the check scheduled if it's wanted and isn't.
        fun watch(activity: ComponentActivity) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = front(activity, true)
                override fun onStop(owner: LifecycleOwner) = front(activity, false)
            })
            ensure(activity)
        }

        private fun front(ctx: Context, now: Boolean) {
            shown = now
            store(ctx).edit().putLong("front", System.currentTimeMillis()).apply()
        }

        // Always scheduled, as Vela's recipe always needs it, and once: a schedule set again at each opening would move
        // with the openings, which is the one thing it mustn't follow.
        fun ensure(ctx: Context) {
            val jobs = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (jobs.getPendingJob(JOB) != null) return
            jobs.schedule(
                JobInfo.Builder(JOB, ComponentName(ctx, Checks::class.java))
                    .setPeriodic(PERIOD_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        private fun run(ctx: Context) {
            if (inFront(ctx)) return
            runCatching { GoogleRecipe.refresh(ctx) }.onFailure { Log.w("KavChecks", "vela", it) }
            val auto = Prefs.autoUpdate(ctx)
            if (!auto && !Prefs.updatePrompts(ctx)) return
            runCatching {
                val release = Updates.checkIfDue(ctx) ?: Updates.stored(ctx) ?: return@runCatching
                if (auto) AutoUpdate.prepare(ctx, release)
            }.onFailure { Log.w("KavChecks", "update", it) }
        }

        // Kav opening: Google's recipe as kept on the phone, with no request, unless it's so old the background check
        // can't have been running (SignedFile.stale). Blocks, so off the main thread.
        fun recipesAtOpen(ctx: Context) {
            GoogleRecipe.load(ctx)
            val installed = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            val now = System.currentTimeMillis()
            if (SignedFile.stale(GoogleRecipe.checkedAt(ctx), installed, now)) runCatching { GoogleRecipe.refresh(ctx) }
        }
    }
}

// What Android's installer says about the update Install now handed it. A success needs nothing: this Kav is gone by
// then. A question Android wants answered comes up at once, Kav being on the screen. Anything else (Play Protect's
// scan question answered no, say) leaves Install now to be tapped again, and Kav asks once more when it next opens.
class InstallStatus : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null && runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        } else Log.w("KavUpdate", "install: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        UpdateRun.installing = false
    }

    companion object {
        // Mutable, as Android fills in the result; explicit, so nothing else can receive it.
        fun sender(ctx: Context, session: Int): IntentSender = PendingIntent.getBroadcast(
            ctx, session, Intent(ctx, InstallStatus::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        ).intentSender
    }
}
