package uk.noammm.kav.data

import android.content.Context
import android.os.Build
import org.json.JSONObject
import uk.noammm.kav.R
import uk.noammm.kav.ui.T
import java.io.File

// "What's new", once, after Kav is updated however it came: its own background update (AutoUpdate, whose notice goes
// first), an APK from GitHub opened by hand, or adb. Due the first time Kav opens on a higher version than it last ran
// on. Kav before 3.2 kept no record of that, so an install Android says was updated rather than freshly installed
// counts as coming from an older Kav, and a fresh install shows nothing. The notes are this version's own, bundled in
// res/raw/whats_new.json (filled for each release), else GitHub's for it when the background check has them. Nothing
// is asked of the network.
internal object WhatsNew {
    class Ran(val code: Long, val name: String)

    fun due(last: Ran?, current: Ran, updatedInPlace: Boolean): Boolean =
        if (last == null) updatedInPlace else current.code > last.code

    // At an opening: the notice when it's due and there are notes for it. A run with nothing due is recorded at once, so
    // only a later update shows anything; a due one is recorded once it's been shown (shown), so another popup taking
    // the opening only puts it off.
    fun check(last: Ran?, current: Ran, updatedInPlace: Boolean, notes: () -> String?, record: (Ran) -> Unit): AutoUpdate.Notice? {
        if (!due(last, current, updatedInPlace)) {
            if (last?.code != current.code) record(current)
            return null
        }
        val text = notes()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AutoUpdate.Notice(last?.name.orEmpty(), current.name, "Kav ${current.name}", text, 0L)
    }

    // The bundled notes in the language Kav is shown in, only when they're this version's.
    fun bundled(json: String, version: String): String? = runCatching {
        val o = JSONObject(json)
        if (o.optString("version") != version) return null
        T(o.optString("en"), o.optString("he").ifBlank { o.optString("en") })
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun file(ctx: Context) = File(ctx.filesDir, "last-run")

    private fun lastRun(ctx: Context): Ran? = runCatching {
        val parts = file(ctx).readText().trim().split(' ', limit = 2)
        Ran(parts[0].toLong(), parts.getOrElse(1) { "" })
    }.getOrNull()

    private fun record(ctx: Context, r: Ran) = file(ctx).writeText("${r.code} ${r.name}")

    // This Kav, and whether Android installed it over an earlier one.
    private fun current(ctx: Context): Pair<Ran, Boolean> {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        return Ran(code, p.versionName.orEmpty()) to (p.lastUpdateTime > p.firstInstallTime)
    }

    private fun notes(ctx: Context, version: String): String? =
        runCatching { ctx.resources.openRawResource(R.raw.whats_new).bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { bundled(it, version) }
            ?: Updates.stored(ctx)?.takeIf { it.version == version }?.notes

    // Reads files, so off the main thread.
    fun notice(ctx: Context): AutoUpdate.Notice? {
        val (now, updated) = current(ctx)
        return check(lastRun(ctx), now, updated, { notes(ctx, now.name) }) { record(ctx, it) }
    }

    fun shown(ctx: Context) = record(ctx, current(ctx).first)
}
