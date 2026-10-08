package uk.noammm.kav.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// A recipe published as a file on GitHub with a detached signature next to it (file.sig). It is checked at most once
// a day and the last good copy is kept on the phone. A copy counts only when its signature checks out against the key
// Kav pins for it, so whoever holds the private half is the only one who can change what Kav does.
internal class SignedFile(private val name: String, private val url: String, private val key: String) {
    // Hands every copy that verifies to take, the kept one first, then the published one at most once a day. take
    // answers whether it used the copy, and only a used copy is kept. Blocks, so off the main thread.
    fun refresh(ctx: Context, take: (ByteArray) -> Boolean) {
        val dir = File(ctx.filesDir, name).apply { mkdirs() }
        val kept = File(dir, "recipe.json")
        val keptSignature = File(dir, "recipe.json.sig")
        val checked = File(dir, "checked")
        runCatching { if (kept.exists() && keptSignature.exists() && verify(kept.readBytes(), keptSignature.readText())) take(kept.readBytes()) }
        if (checked.exists() && System.currentTimeMillis() - checked.lastModified() < DAY_MS) return
        val body = get(url) ?: return
        val signature = get("$url.sig")?.toString(Charsets.UTF_8) ?: return
        checked.writeText("")
        if (verify(body, signature) && take(body)) {
            kept.writeBytes(body)
            keptSignature.writeText(signature)
        }
    }

    // ECDSA P-256 over the file's exact bytes, as `openssl dgst -sha256 -sign` makes it.
    fun verify(body: ByteArray, signature: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(body)
            verify(Base64.getDecoder().decode(signature.trim()))
        }
    }.getOrDefault(false)

    private fun get(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Kav (+https://github.com/ImNoammm/kav)")
        }
        return try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
