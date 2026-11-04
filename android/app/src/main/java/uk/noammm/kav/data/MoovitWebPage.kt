package uk.noammm.kav.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import uk.noammm.kav.R
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Runs planning, timetable and shared-trip requests inside the logged-out website.
// Only its page, scripts, bot check and the allowed API requests may load.
internal object MoovitWebPage {
    private var context: Context? = null
    fun init(ctx: Context) { context = ctx.applicationContext }

    // The hidden browser's own user agent, which the site's check goes out with too.
    fun agent(): String? = context?.let { runCatching { android.webkit.WebSettings.getDefaultUserAgent(it) }.getOrNull() }

    internal fun allowed(host: String?, path: String): Boolean = when {
        host == "moovitapp.com" -> !path.startsWith("/api/") || path.startsWith("/api/route/") ||
            path == "/api/user" || path == "/api/user/all" || path.startsWith("/api/metro/") || path == "/api/feature-flags" ||
            path == "/api/lines/agency" || path == "/api/lines/group-trips" ||
            path == "/api/share/itinerary/get" || path == "/api/share/itinerary" ||
            path == "/api/alert/metro" || path == "/api/alert/details"
        host == "appassets.mvtdev.com" -> true
        host?.endsWith(".awswaf.com") == true -> true
        else -> false
    }

    // Fallback API version until the page supplies its own.
    @Volatile private var version = "5.151.2/V567"

    // The search under way: a new one ends it, so a search changed meanwhile isn't kept waiting.
    private val current = AtomicReference<CompletableFuture<JSONObject>?>()

    fun plan(query: JSONObject): JSONObject = ask(R.raw.moovit_web_request, query, search = true, seconds = 50)

    // The timetable's asks (Schedules): every operator's lines, or one line's day. A long list takes many requests.
    fun lines(query: JSONObject): JSONObject = ask(R.raw.moovit_web_lines, query, search = false, seconds = 90)

    // Service alerts (MoovitAlerts): the metro's whole list, or some alerts' notices.
    fun alerts(query: JSONObject): JSONObject = ask(R.raw.moovit_web_alerts, query, search = false, seconds = 60)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ask(script: Int, query: JSONObject, search: Boolean, seconds: Long): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Ask Moovit's website off the main thread" }
        val ctx = context ?: throw IOException("Moovit web planner is not initialized")
        val main = Handler(Looper.getMainLooper())
        val answer = CompletableFuture<JSONObject>()
        if (search) current.getAndSet(answer)?.completeExceptionally(IOException("Moovit web search replaced"))
        val name = JSONObject.quote("kav" + UUID.randomUUID().toString().replace("-", ""))
        val request = ctx.resources.openRawResource(script).bufferedReader().use { it.readText() }
        val language = if (uk.noammm.kav.ui.T.rtl) "he" else "en"
        var page: WebView? = null
        main.post {
            if (answer.isDone) return@post
            try {
                val view = WebView(ctx)
                page = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.setGeolocationEnabled(false)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" || request.url.host != "moovitapp.com"

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.host == "moovitapp.com") request.requestHeaders.entries
                            .firstOrNull { it.key.equals("MOOVIT_CLIENT_VERSION", ignoreCase = true) && '/' in it.value }
                            ?.let { version = it.value }
                        return if (request.url.scheme == "https" && allowed(request.url.host, request.url.path.orEmpty())) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) answer.completeExceptionally(IOException("Moovit web page did not load"))
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) answer.completeExceptionally(IOException("Moovit web HTTP ${response.statusCode}"))
                    }

                    // Each page that finishes, once: the bot check can load the page again before it's the planner.
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (answer.isDone || android.net.Uri.parse(url.orEmpty()).host != "moovitapp.com") return
                        view.evaluateJavascript("""
                            (async function() {
                                if (window.kavAsked) return;
                                window.kavAsked = true;
                                try {
                                    let tries = 0;
                                    while (!window.AwsWafIntegration) {
                                        if (++tries > 100) throw new Error('Moovit web session did not become ready: ' + JSON.stringify({
                                            at: location.href, title: document.title,
                                            scripts: Array.from(document.scripts).map(s => s.src).filter(Boolean).slice(0, 12)}));
                                        await new Promise(resolve => setTimeout(resolve, 100));
                                    }
                                    const data = await $request($query, ${JSONObject.quote(version)}, ${JSONObject.quote(language)});
                                    window[$name] = JSON.stringify({data});
                                } catch (error) { window[$name] = JSON.stringify({error: String(error)}); }
                            })();
                        """.trimIndent(), null)
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (answer.isDone) return
                        view.evaluateJavascript("window[$name] || null") { raw ->
                            try {
                                val text = JSONTokener(raw).nextValue() as? String
                                if (text == null) main.postDelayed(this, 150) else {
                                    val result = JSONObject(text)
                                    if (result.has("error")) answer.completeExceptionally(IOException(result.getString("error")))
                                    else answer.complete(result.getJSONObject("data"))
                                }
                            } catch (e: Exception) { answer.completeExceptionally(IOException("Invalid Moovit web response", e)) }
                        }
                    }
                }
                view.loadUrl(MoovitWeb.page(language))
                main.post(poll)
            } catch (e: Exception) { answer.completeExceptionally(IOException("Moovit web planner could not start", e)) }
        }
        try {
            return answer.get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause as? IOException ?: IOException("Moovit web planner failed", e.cause)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw java.net.SocketTimeoutException("Moovit web planner timed out")
        } finally {
            if (search) current.compareAndSet(answer, null)
            answer.cancel(false)
            main.post { page?.apply { stopLoading(); destroy() } }
        }
    }
}
