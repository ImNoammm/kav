package uk.noammm.kav.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import uk.noammm.kav.Prefs
import uk.noammm.kav.ui.T
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Downloads the week's timetable from GitHub or the selected own server. The manifest signature,
// file hash and size must all match. Net reads once per run, so verified downloads take effect
// at the next start; the last valid copy stays until a replacement checks out.
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

    // Keep next intact until all three files are installed. If Android stops us halfway, the next start can retry.
    fun promote(): Boolean {
        synchronized(LOCK) {
            if (!next.exists()) return false
            if (read(next) == null) {
                next.deleteRecursively()
                return false
            }
            if (runCatching {
                File(dir, MANIFEST).delete()
                for (name in listOf(BODY, SIGNATURE, MANIFEST)) File(next, name).copyTo(File(dir, name), overwrite = true)
            }.isFailure) return false
            next.deleteRecursively()
            return true
        }
    }

    // The copy in use when it checks out. Past its seven days its weekdays are a week old or more, but they're the
    // timetable there is until a newer copy comes.
    fun current(): ByteArray? = read(dir)?.second

    // Whether there's a copy, in use or waiting: its manifest checks out and its file is the manifest's size. Quick, for
    // a start that has to know whether to fetch one first; current() checks the whole file.
    fun ready(): Boolean = listOf(dir, next).any { folder ->
        runCatching {
            manifest(File(folder, MANIFEST).readBytes(), File(folder, SIGNATURE).readText())?.size == File(folder, BODY).length()
        }.getOrDefault(false)
    }

    fun discard() {
        synchronized(LOCK) { for (name in listOf(MANIFEST, SIGNATURE, BODY)) File(dir, name).delete() }
    }

    // When the timetable's source last answered about it.
    fun checked(): Long = File(dir, CHECKED).lastModified()

    fun markChecked() {
        dir.mkdirs()
        File(dir, CHECKED).writeText("")
    }

    fun forgetChecked() { File(dir, CHECKED).delete() }

    companion object {
        const val BODY = "il.kav.gz"
        const val MANIFEST = "manifest.json"
        const val SIGNATURE = "manifest.json.sig"
        const val CHECKED = "checked"
        private val LOCK = Any()

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

internal object Timetable {
    // The public half of .signing/timetable.key, which GitHub's nightly build signs each week's manifest with.
    internal const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKjZO/hTPZFH6+Y3FvqhpfCm17I2Wl5Nwkg6X/dQp9MroYouUoX8O5W70MM0hpWSRBX7CEB8oH07xXUqDMi7UzA=="
    // Where that build puts it: a release of its own, replaced each night.
    internal const val RELEASE = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download/timetable/"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    // On mobile data a newer copy comes only once the one in use is this many days old.
    private const val STALE_DAYS = 3L
    private val israel = ZoneId.of("Asia/Jerusalem")
    private val downloadLock = Any()

    // Whether Kav has a week to read, for setup and for a start without one: Missing until one is fetched.
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data class Failed(val why: String) : State
        data object Ready : State
    }

    var state by mutableStateOf<State>(State.Missing)

    fun init(ctx: Context) {
        if (state !is State.Downloading) state = if (store(ctx).ready()) State.Ready else State.Missing
    }

    // A copy counts with the key pinned above, or with the one the rider's own server gave (server/selfhost makes its
    // own); a copy from the other source then doesn't check out, and the chosen one's is fetched.
    private fun source(ctx: Context) = Sources.timetableFrom(Prefs.timetableSource(ctx), Prefs.ownServer(ctx))

    fun store(ctx: Context) = store(ctx, source(ctx))

    private fun store(ctx: Context, at: String?) = TimetableStore(
        File(ctx.filesDir, if (at == null) "timetable" else "timetable-own/${TimetableStore.sha256(at.toByteArray())}"),
        if (at != null) Prefs.kavServerBundleKey(ctx) else KEY,
    )

    // Another source chosen: its copy is asked for now rather than a day after the last one's.
    fun serverChanged(ctx: Context) {
        store(ctx).forgetChecked()
        refresh(ctx)
    }

    // At start: the copy downloaded last run, else the one taken in before; null when there's none yet. One that doesn't
    // read is let go and fetched again.
    fun load(ctx: Context): Net? {
        val s = store(ctx)
        s.promote()
        val body = s.current() ?: return null.also { state = State.Missing }
        return runCatching { Net.read(body.inputStream()) }.getOrElse { s.discard(); state = State.Missing; null }
    }

    // The first week, at setup or at a start without one, in the background, with its progress in state.
    fun startDownload(ctx: Context) {
        if (state is State.Downloading || state is State.Ready) return
        val app = ctx.applicationContext
        state = State.Downloading(0f)
        Thread {
            synchronized(downloadLock) {
                state = try {
                    val at = source(app)
                    if (!download(app, at, null) { state = State.Downloading(it) }) throw java.io.IOException()
                    if (source(app) == at) State.Ready else State.Missing
                } catch (e: Exception) {
                    State.Failed(T("Check the connection and try again.", "בדקו את החיבור ונסו שוב."))
                }
            }
        }.start()
    }

    // A newer copy at most once a day, from Kav's background check (Checks): with daily updates on, on Wi-Fi or another
    // unmetered network, and on mobile data only once the copy in use is STALE_DAYS old. Blocks, so off the main thread.
    fun refresh(ctx: Context): Unit = synchronized(downloadLock) {
        val at = source(ctx)
        val s = store(ctx, at)
        val have = s.staged() ?: s.installed()
        if (!due(have, Prefs.timetableUpdates(ctx), metered(ctx), s.checked(), System.currentTimeMillis(), LocalDate.now(israel))) return
        download(ctx, at, have)
        Unit
    }

    internal fun due(have: TimetableStore.Manifest?, daily: Boolean, metered: Boolean, checked: Long, now: Long, today: LocalDate): Boolean =
        daily && (have == null || (now - checked >= DAY_MS && (!metered || ChronoUnit.DAYS.between(have.from, today) >= STALE_DAYS)))

    // The signed manifest, then the file itself (6 MB) only when its hash, which is its ETag, isn't that of the copy in use
    // or waiting (have). False when nothing new checked out.
    private fun download(ctx: Context, at: String?, have: TimetableStore.Manifest?, progress: (Float) -> Unit = {}): Boolean {
        val key = if (at == null) "" else Prefs.kavServerKey(ctx)
        // The rider's own server's key, taken from the server the first time it's wanted, if it wasn't when it was chosen.
        if (at != null && Prefs.kavServerBundleKey(ctx).isEmpty()) {
            val told = (KavLive.check(at, key) as? KavLive.Check.Answers)?.bundleKey?.takeIf { it.isNotEmpty() } ?: return false
            if (source(ctx) != at) return false
            Prefs.setKavServerBundleKey(ctx, told)
        }
        val s = store(ctx, at)
        val manifest = fetch(at, key, TimetableStore.MANIFEST) ?: return false
        if (manifest.isEmpty()) return false
        val signature = fetch(at, key, TimetableStore.SIGNATURE)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val m = s.manifest(manifest, signature) ?: return false
        if (m.sha256 == have?.sha256) { s.markChecked(); return true }
        val body = fetch(at, key, TimetableStore.BODY, long = true, progress)?.takeIf { it.isNotEmpty() } ?: return false
        if (!s.stage(manifest, signature, body)) return false
        s.markChecked()
        return true
    }

    private fun metered(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    // One file of the week: from GitHub's release, or from the rider's own server (at) as KavLive asks it. Null when it
    // didn't answer, empty when it answered without the file.
    private fun fetch(at: String?, key: String, name: String, long: Boolean = false, progress: (Float) -> Unit = {}): ByteArray? {
        val path = "/live/v1/bundle/$name"
        val c = runCatching { URL(if (at == null) RELEASE + name else at + path).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = if (long) 120_000 else 15_000
            c.setRequestProperty("User-Agent", "Kav")
            if (at != null) KavLive.sign(c, path, key)
            if (c.responseCode != 200) return ByteArray(0)
            val size = c.contentLengthLong
            val out = ByteArrayOutputStream(if (size > 0) size.toInt() else 1 shl 16)
            c.inputStream.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (size > 0) progress((out.size().toFloat() / size).coerceIn(0f, 1f))
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
