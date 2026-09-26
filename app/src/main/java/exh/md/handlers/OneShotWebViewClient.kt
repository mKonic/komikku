package exh.md.handlers

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The client of a handler's one-shot WebView, which loads a site only to read or clear its local
 * storage: [onFinished] once the page has loaded, [onGone] if the renderer dies first. Returning
 * true from the latter is what keeps a dead renderer from taking the app down with it.
 *
 * One class, so the lint suppression below covers one audited override instead of every call site:
 * androidx.webkit 1.17.0's MissingOnRenderProcessGone reports every written `WebViewClient()`
 * constructor call without looking at what the class overrides (`visitConstructor` reports
 * unconditionally; only `visitClass` checks for the override). In Kotlin that includes the
 * super-constructor call of any direct subclass or object expression, so no Kotlin class extending
 * WebViewClient itself can pass it, however it handles the renderer dying.
 */
@SuppressLint("MissingOnRenderProcessGone")
internal class OneShotWebViewClient(
    private val onFinished: (WebView) -> Unit,
    private val onGone: (WebView) -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) = onFinished(view)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        onGone(view)
        return true
    }
}
