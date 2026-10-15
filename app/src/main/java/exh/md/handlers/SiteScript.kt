package exh.md.handlers

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.WebView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reads a string result from a site's localStorage, shared with the in-app WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal suspend fun evaluateOnSite(baseUrl: String, script: String): String? {
    val context = Injekt.get<Application>()
    val executor = ContextCompat.getMainExecutor(context)
    val latch = CountDownLatch(1)
    val result = AtomicReference<String?>()
    var webView: WebView? = null
    var rendererGone = false

    executor.execute {
        val view = WebView(context)
        webView = view
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = true
        }
        view.webViewClient = OneShotWebViewClient(
            onFinished = { loaded ->
                loaded.evaluateJavascript(script) { value ->
                    if (!rendererGone) result.set(value)
                    latch.countDown()
                }
            },
            onGone = {
                rendererGone = true
                latch.countDown()
            },
        )
        view.loadDataWithBaseURL("$baseUrl/", " ", "text/html", "utf-8", null)
    }

    try {
        withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }
        return result.get()?.let { runCatching { Json.decodeFromString<String?>(it) }.getOrNull() }
    } finally {
        executor.execute {
            webView?.let { view ->
                if (!rendererGone) view.stopLoading()
                view.destroy()
            }
        }
    }
}

/** Escapes a value as a JavaScript string literal, or `null`. */
internal fun jsString(value: String?): String = Json.encodeToString(value)
