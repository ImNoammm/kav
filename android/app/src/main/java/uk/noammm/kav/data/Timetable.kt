package uk.noammm.kav.data

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import uk.noammm.kav.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Kav's timetable for the seven days from today, which Kav's server builds each night from the ministry's real calendars
// and the weekend lines (tools/export_web_bundle.py with KAV_FROM), in place of the week the app shipped with. On a day
// that runs a timetable of its own (a holiday, an election day) the shipped week has the wrong buses. A
// copy counts only when its manifest's signature checks out against the key pinned below and the file's hash and size
// are the manifest's. It's taken in at the next start, Net being read once a run, and the shipped week stays for when
// there's none.
internal class TimetableStore(private val dir: File, key: String) {
    class Manifest(val from: LocalDate, val days: Int, val sha256: String, val size: Long)

    private val check = SignedFile("kav-timetable", "", key)
    private val next = File(dir, "next")

    // The manifest, when its signature checks out and it says what it should.
    fun manifest(bytes: ByteArray, signature: String): Manifest? {
        if (!check.verify(bytes, signature)) return null
        return runCatching {
            val o = JSONObject(bytes.toString(Charsets.UTF_8))
            Manifest(LocalDate.parse(o.getString("from")), o.getInt("days"), o.getString("sha256"), o.getLong("size"))
                .takeIf { o.getString("format") == "KAV6" }
        }.getOrNull()
    }

    private fun matches(m: Manifest, body: ByteArray) = body.size.toLong() == m.size && sha256(body) == m.sha256

    // A folder's copy when all of it checks out: the manifest by its signature, the file by the manifest.
    private fun read(folder: File): Pair<Manifest, ByteArray>? {
        return runCatching {
            val m = manifest(File(folder, MANIFEST).readBytes(), File(folder, SIGNATURE).readText()) ?: return null
            val body = File(folder, BODY).readBytes()
            if (matches(m, body)) m to body else null
        }.getOrNull()
    }

    // The copy in use, and the one waiting for the next start.
    fun installed(): Manifest? = read(dir)?.first
    fun staged(): Manifest? = read(next)?.first

    // A downloaded copy put aside for the next start, once it checks out.
    fun stage(manifest: ByteArray, signature: String, body: ByteArray): Boolean {
        val m = manifest(manifest, signature) ?: return false
        if (!matches(m, body)) return false
        synchronized(LOCK) {
            val part = File(dir, "next.part")
            part.deleteRecursively()
            part.mkdirs()
            File(part, BODY).writeBytes(body)
            File(part, SIGNATURE).writeText(signature)
            File(part, MANIFEST).writeBytes(manifest)
            next.deleteRecursively()
            return part.renameTo(next)
        }
    }

    // The waiting copy becomes the one in use, file by file with the manifest last, so a copy half moved never checks
    // out and the shipped week is read instead.
    fun promote(): Boolean {
        synchronized(LOCK) {
            if (!next.exists()) return false
            if (read(next) == null) {
                next.deleteRecursively()
                return false
            }
            File(dir, MANIFEST).delete()
            for (name in listOf(BODY, SIGNATURE, MANIFEST)) if (!File(next, name).renameTo(File(dir, name))) return false
            next.deleteRecursively()
            return true
        }
    }

    // The copy in use when it checks out and its seven days aren't long gone, else null: the shipped week's turn.
    fun current(today: LocalDate): ByteArray? {
        val (m, body) = read(dir) ?: return null
        return body.takeIf { ChronoUnit.DAYS.between(m.from, today) in -1..KEEP_DAYS }
    }

    fun discard() {
        synchronized(LOCK) { for (name in listOf(MANIFEST, SIGNATURE, BODY)) File(dir, name).delete() }
    }

    // When Kav's server last answered about it.
    fun checked(): Long = File(dir, CHECKED).lastModified()

    fun markChecked() {
        dir.mkdirs()
        File(dir, CHECKED).writeText("")
    }

    companion object {
        const val BODY = "il.kav.gz"
        const val MANIFEST = "manifest.json"
        const val SIGNATURE = "manifest.json.sig"
        const val CHECKED = "checked"
        // Past its seven days a copy's weekdays are a week old, as the shipped week's are; two weeks on it's let go.
        const val KEEP_DAYS = 13L
        private val LOCK = Any()

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

internal object Timetable {
    // The public half of .signing/bundle.key, which server/otp/rebuild.sh signs each night's manifest with.
    internal const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEY3iBi374N7EW4WLuDKkaA38M8006nUKSMnpbzE+LR3U7MPIHHrXpjqQzYlz+FtfbycjIaadUaxxlw/j9RK40mQ=="
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    // On mobile data a newer copy comes only once the one in use is this many days old.
    private const val STALE_DAYS = 3L
    private val israel = ZoneId.of("Asia/Jerusalem")

    // A copy counts with Kav's key, pinned above, or with the one the rider's own server gave (server/selfhost makes its
    // own); a copy of the other server's then doesn't check out, and the shipped week is read until the chosen one's comes.
    fun store(ctx: Context) = TimetableStore(File(ctx.filesDir, "timetable"), if (KavLive.own) Prefs.kavServerBundleKey(ctx) else KEY)

    // Another server chosen: its copy is asked for now rather than a day after the last server's.
    fun serverChanged(ctx: Context) {
        File(File(ctx.filesDir, "timetable"), TimetableStore.CHECKED).delete()
        refresh(ctx)
    }

    // At start: the copy downloaded last run, else the one taken in before; null for the shipped week. One that doesn't
    // read is let go and the shipped week read instead.
    fun load(ctx: Context): Net? {
        if (!Prefs.timetableUpdates(ctx)) return null
        val s = store(ctx)
        s.promote()
        val body = s.current(LocalDate.now(israel)) ?: return null
        return runCatching { Net.read(body.inputStream()) }.getOrElse { s.discard(); null }
    }

    // Asks Kav's server about a newer copy at most once a day while the setting is on: on Wi-Fi or another unmetered
    // network, and on mobile data only once the copy in use is STALE_DAYS old. The file itself (6.7 MB) comes only when the
    // signed manifest's hash, which is its ETag, isn't that of the copy in use or waiting. Blocks, so off the main thread.
    fun refresh(ctx: Context) {
        if (!Prefs.timetableUpdates(ctx)) return
        val s = store(ctx)
        if (System.currentTimeMillis() - s.checked() < DAY_MS) return
        val have = s.staged() ?: s.installed()
        if (metered(ctx) && ChronoUnit.DAYS.between(have?.from ?: shipped(ctx), LocalDate.now(israel)) < STALE_DAYS) return
        // The rider's own server's key, taken from the server the first time it's wanted, if it wasn't when it was chosen.
        if (KavLive.own && Prefs.kavServerBundleKey(ctx).isEmpty()) {
            val told = (KavLive.check(KavLive.server, KavLive.key) as? KavLive.Check.Answers)?.bundleKey ?: return
            Prefs.setKavServerBundleKey(ctx, told)
            return refresh(ctx)
        }
        val manifest = fetch("bundle/manifest.json") ?: return
        s.markChecked()
        if (manifest.isEmpty()) return
        val signature = fetch("bundle/manifest.json.sig")?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val m = s.manifest(manifest, signature) ?: return
        if (m.sha256 == have?.sha256) return
        val body = fetch("bundle/il.kav.gz", long = true)?.takeIf { it.isNotEmpty() } ?: return
        s.stage(manifest, signature, body)
    }

    // The shipped week came with the app, so it's as old as the app's install or last update.
    private fun shipped(ctx: Context): LocalDate = runCatching {
        Instant.ofEpochMilli(ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime).atZone(israel).toLocalDate()
    }.getOrDefault(LocalDate.of(2000, 1, 1))

    private fun metered(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    // The chosen server, asked as KavLive asks it. Null when it didn't answer, empty when it answered without the file.
    private fun fetch(path: String, long: Boolean = false): ByteArray? {
        val c = runCatching { URL("${KavLive.server}/live/v1/$path").openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = if (long) 120_000 else 15_000
            c.setRequestProperty("User-Agent", "Kav")
            KavLive.sign(c, "/live/v1/$path", KavLive.keyFor(KavLive.server))
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else ByteArray(0)
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
