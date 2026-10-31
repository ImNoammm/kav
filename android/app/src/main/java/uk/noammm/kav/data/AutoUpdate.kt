package uk.noammm.kav.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONObject
import uk.noammm.kav.InstallStatus
import java.io.File
import java.security.MessageDigest

// Kav updating itself: the background check (Checks) downloads a newer release on Wi-Fi, kept only when it's signed
// with exactly the installed Kav's certificates. Only the rider's Install now, asked as Kav next opens (ReadyPrompt),
// installs it, so any question from Android or Play Protect follows their own tap. Its state is kept in updated.json.
internal object AutoUpdate {
    class Notice(val from: String, val to: String, val name: String, val notes: String, val at: Long) {
        fun release() = Updates.Release(to, name, notes, null, -1, Updates.PAGE)
    }

    private const val WEEK_MS = 7 * 24 * 3600_000L

    private fun file(ctx: Context) = File(ctx.filesDir, "updated.json")

    private fun apk(ctx: Context, version: String) = File(File(ctx.cacheDir, "updates"), "kav-$version.apk")

    private fun state(ctx: Context): Notice? = runCatching {
        val f = file(ctx)
        val o = JSONObject(f.readText())
        Notice(o.optString("from"), o.optString("to"), o.optString("name"), o.optString("notes"), f.lastModified())
    }.getOrNull()

    // The newest release the background check knows of, downloaded and checked when it's newer than this Kav.
    fun prepare(ctx: Context, release: Updates.Release) {
        val installed = Updates.installedVersion(ctx)
        if (!Updates.isNewer(release.version, installed) || release.apkUrl == null) {
            File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            return
        }
        if (state(ctx)?.to == release.version && apk(ctx, release.version).exists()) return
        if (metered(ctx)) return
        val apk = Updates.download(ctx, release) { _, _ -> }
        if (!sameApp(ctx, apk)) {
            apk.delete()
            return
        }
        file(ctx).writeText(
            JSONObject().put("from", installed).put("to", release.version).put("name", release.name).put("notes", release.notes).toString(),
        )
    }

    // The update to ask about as Kav opens: downloaded and checked, and not installed yet.
    fun ready(ctx: Context): Notice? {
        val n = state(ctx) ?: return null
        return n.takeIf { !shows(it.to, Updates.installedVersion(ctx)) && apk(ctx, it.to).exists() }
    }

    // Install now: the download, checked once more, to Android's installer with Kav on the screen. On Android 12 and newer
    // it asks for no tap of its own, as the rider just gave one; Android or Play Protect may still ask (InstallStatus).
    // Blocks, so off the main thread.
    fun installNow(ctx: Context): Boolean {
        val n = state(ctx) ?: return false
        val apk = apk(ctx, n.to).takeIf { it.exists() } ?: return false
        if (!sameApp(ctx, apk)) {
            apk.delete()
            return false
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("kav.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                s.commit(InstallStatus.sender(ctx, id))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return true
    }

    // An update this Kav came from, to say so once. One that never got installed is let go after a week.
    fun notice(ctx: Context): Notice? {
        val n = state(ctx) ?: return null
        if (!shows(n.to, Updates.installedVersion(ctx))) {
            if (System.currentTimeMillis() - n.at > WEEK_MS) file(ctx).delete()
            return null
        }
        return n
    }

    fun noticeShown(ctx: Context) {
        file(ctx).delete()
        File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    // The installed version is the one updated to, or later.
    internal fun shows(to: String, installed: String) = to.isNotEmpty() && !Updates.isNewer(to, installed)

    // The download is Kav, no older than this one and signed with exactly its certificates.
    private fun sameApp(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val got = info(pm, ctx.packageName, apk.path) ?: return false
        val have = info(pm, ctx.packageName, null) ?: return false
        return got.packageName == ctx.packageName && code(got) >= code(have) && sameSigners(signers(have), signers(got))
    }

    internal fun sameSigners(have: Set<String>, got: Set<String>) = have.isNotEmpty() && have == got

    // The installed Kav, or the APK at path, with its signatures. Some Android versions leave an APK's signingInfo out:
    // the older flag is asked then.
    @Suppress("DEPRECATION")
    private fun info(pm: PackageManager, pkg: String, path: String?): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val p = runCatching { if (path == null) pm.getPackageInfo(pkg, flags) else pm.getPackageArchiveInfo(path, flags) }.getOrNull()
            if (p?.signingInfo != null) return p
        }
        val flags = PackageManager.GET_SIGNATURES
        return runCatching { if (path == null) pm.getPackageInfo(pkg, flags) else pm.getPackageArchiveInfo(path, flags) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && p.signingInfo != null) p.signingInfo?.apkContentsSigners
            else p.signatures
        return certs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()

    private fun metered(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}
