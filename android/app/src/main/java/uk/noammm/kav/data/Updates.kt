package uk.noammm.kav.data

import uk.noammm.kav.ui.T
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Releases on GitHub are the only channel: no store, no server of Kav's own. The
 * newest release is compared with the running version at every launch, and its APK
 * is fetched and handed to the system installer on request.
 */
object Updates {
    const val OWNER = "ImNoammm"
    const val REPO = "kav"
    const val PAGE = "https://github.com/$OWNER/$REPO/releases"
    /** Where a bug or an idea goes. The alternative people had was a Reddit comment. */
    const val ISSUES = "https://github.com/$OWNER/$REPO/issues"

    class Release(
        val version: String,
        val name: String,
        val notes: String,
        val apkUrl: String?,
        val apkBytes: Long,
        val pageUrl: String,
    )

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** GET releases/latest, drafts and pre-releases are not offered. */
    fun latest(): Release {
        val c = (URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Kav")
        }
        val code = c.responseCode
        if (code != 200) throw RuntimeException("GitHub HTTP $code")
        val o = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
        }
        return Release(
            version = o.optString("tag_name").removePrefix("v").removePrefix("V"),
            name = o.optString("name"),
            notes = o.optString("body"),
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            apkBytes = apk?.optLong("size") ?: -1L,
            pageUrl = o.optString("html_url").ifBlank { PAGE },
        )
    }

    /** Numeric, segment by segment: 1.10 is newer than 1.9, and a missing segment is zero. */
    fun isNewer(remote: String, installed: String): Boolean {
        val a = segments(remote); val b = segments(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** The release's APK, fetched to the cache with progress, reused if already there. */
    fun download(ctx: Context, release: Release, onProgress: (Long, Long) -> Unit): File {
        val url = release.apkUrl ?: throw RuntimeException(T("This release has no APK attached", "לגרסה הזו לא מצורף קובץ APK"))
        val file = File(dir(ctx), "kav-${release.version}.apk")
        if (file.exists() && release.apkBytes > 0 && file.length() == release.apkBytes) {
            onProgress(file.length(), file.length()); return file
        }
        dir(ctx).listFiles()?.forEach { if (it != file) it.delete() }
        val part = File(file.path + ".part")
        var c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kav")
        // GitHub hands assets off to another host; HttpURLConnection follows only
        // within one scheme, and objects.githubusercontent.com is https like the API
        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(URL(url), next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Kav")
            hops++
        }
        if (c.responseCode != 200) throw RuntimeException(T("Download HTTP ${c.responseCode}", "הורדה נכשלה, שגיאת HTTP ${c.responseCode}"))
        val total = if (release.apkBytes > 0) release.apkBytes else c.contentLengthLong
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        if (!part.renameTo(file)) throw RuntimeException(T("Could not keep the download", "לא ניתן היה לשמור את ההורדה"))
        return file
    }

    /** Android 8 and later gate sideloading per app; the toggle lives in system settings. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Hand the file to the system installer, which does the asking and the replacing. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
