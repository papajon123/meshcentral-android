package ru.papajon.meshcentralmobile.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/** A local-only VT renderer. No credentials, URL input, file access, network or clipboard bridge. */
@SuppressLint("SetJavaScriptEnabled")
class TerminalWebRenderer internal constructor(context: Context, private val controller: TerminalController) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var loaded = false
    private val startupTimeout = Runnable { if (!loaded && !destroyed) controller.rendererInitializationFailed() }
    private var copyGeneration = 0
    private var lastConfiguration: String? = null
    private var inputDialogOpen = false
    private var dialogResizeHeld = false
    private var dialogSettle: Runnable? = null
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { settleInputDialog() }
    val view: WebView = WebView(appContext).apply {
        isSaveEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        addJavascriptInterface(Bridge(), "TerminalBridge")
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) settleInputDialog()
        }
        viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path
                if (request.method != "GET" || uri.scheme != "https" || uri.host != HOST || uri.port != -1 ||
                    uri.query != null || path !in ASSETS) return denied()
                return try {
                    val mime = when { path!!.endsWith(".js") -> "application/javascript"
                        path.endsWith(".css") -> "text/css"; else -> "text/html" }
                    WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store",
                        "Content-Security-Policy" to CSP, "X-Content-Type-Options" to "nosniff"),
                        appContext.assets.open("terminal" + path))
                } catch (_: Exception) { denied() }
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != URL) { view?.stopLoading(); controller.rendererFailed() }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == URL) {
                    if (dialogResizeHeld) evaluate("window.meshTerminal?.inputDialog(true)")
                    lastConfiguration?.let { evaluate("window.meshTerminalConfiguration=$it;window.meshTerminal?.configure($it)") }
                    view?.evaluateJavascript("typeof window.meshTerminal === 'object'") {
                        if (!destroyed && it != "true") controller.rendererInitializationFailed()
                    }
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) controller.rendererInitializationFailed()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
                if (request.isForMainFrame) controller.rendererInitializationFailed()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                controller.rendererFailed(); destroy(); return true
            }
        }
        loadUrl(URL)
        main.postDelayed(startupTimeout, 15_000)
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { main.post { if (!destroyed) {
            loaded = true
            main.removeCallbacks(startupTimeout)
            controller.rendererReady()
        } } }
        @JavascriptInterface fun consumed() { main.post { if (!destroyed) controller.outputConsumed() } }
        @JavascriptInterface fun input(value: String) {
            if (value.length <= TerminalController.MAX_INPUT_BYTES) main.post {
                if (!destroyed) controller.sendInput(value)
            }
        }
        @JavascriptInterface fun resize(cols: Int, rows: Int, width: Int, height: Int) {
            main.post { if (!destroyed) controller.resize(cols, rows, width, height) }
        }
    }

    internal fun write(bytes: ByteArray) {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        evaluate("window.meshTerminal.write('$encoded')")
    }

    internal fun setInputEnabled(enabled: Boolean) = evaluate("window.meshTerminal?.enable($enabled)")

    fun configure(background: String, foreground: String, fontSize: Int, inputLabel: String, tooMuchLabel: String) {
        val configuration = JSONObject().put("background", background).put("foreground", foreground)
            .put("fontSize", fontSize.coerceIn(12, 40)).put("inputLabel", inputLabel).put("tooMuchLabel", tooMuchLabel).toString()
        if (configuration == lastConfiguration) return
        lastConfiguration = configuration
        // Also applied on load: Compose may configure before the scripts finish loading.
        evaluate("window.meshTerminalConfiguration=$configuration;window.meshTerminal?.configure($configuration)")
    }

    fun focus() { main.post { if (!destroyed) {
        view.requestFocus()
        view.evaluateJavascript("window.meshTerminal?.focus()") {
            if (!destroyed) (appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    } } }
    fun selectAll() = evaluate("window.meshTerminal?.selectAll()")
    fun clearSelection() = evaluate("window.meshTerminal?.clearSelection()")
    /** Keep the remote grid stable through the dialog's keyboard and dismissal animation.
     * There is no delayed command queue here: input is still sent exactly once by the controller.
     */
    fun inputDialog(open: Boolean) {
        if (destroyed || (!open && !dialogResizeHeld)) return
        dialogSettle?.let(main::removeCallbacks)
        dialogSettle = null
        inputDialogOpen = open
        if (open) {
            dialogResizeHeld = true
            evaluate("window.meshTerminal?.inputDialog(true)")
            return
        }
        settleInputDialog()
    }

    private fun settleInputDialog() {
        if (destroyed || inputDialogOpen || !dialogResizeHeld) return
        dialogSettle?.let(main::removeCallbacks)
        dialogSettle = null
        // A detached view will be fitted again on attach. A background window waits for a
        // focus/layout event, never a polling loop or a timeout that forces an intermediate fit.
        if (!view.isAttachedToWindow) {
            dialogResizeHeld = false
            evaluate("window.meshTerminal?.inputDialog(false)")
            return
        }
        if (!view.hasWindowFocus()) return
        dialogSettle = Runnable {
            dialogSettle = null
            if (!destroyed && !inputDialogOpen && dialogResizeHeld && view.hasWindowFocus()) {
                dialogResizeHeld = false
                evaluate("window.meshTerminal?.inputDialog(false)")
            }
        }.also { main.postDelayed(it, 250) }
    }
    fun setControl(enabled: Boolean) = evaluate("window.meshTerminal?.control($enabled)")
    fun key(value: String) = evaluate("window.meshTerminal?.key(${JSONObject.quote(value)})")

    /** Clipboard is populated only by this explicit user action, never by terminal escape sequences. */
    fun copySelection(label: String) {
        val generation = ++copyGeneration
        main.post {
            if (!destroyed) view.evaluateJavascript("window.meshTerminal?.selection() || ''") { result ->
                if (!destroyed && generation == copyGeneration) {
                    val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                    if (!text.isNullOrEmpty()) (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(label, text.take(262_144)))
                }
            }
        }
    }

    fun detach() { (view.parent as? ViewGroup)?.removeView(view) }

    /**
     * Compose owns a fresh native host per composition/window, never the retained WebView itself.
     * AndroidViewHolder deliberately disables clipping; a clipped native parent also bounds the
     * WebView compositor when the same terminal is moved from a destroyed Activity to a new one.
     */
    fun createHost(context: Context): FrameLayout = FrameLayout(context).apply {
        isSaveEnabled = false
        clipChildren = true
        clipToPadding = true
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        val host = this
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (destroyed || view.parent !== host) return
                view.onResume()
                view.requestLayout()
                settleInputDialog()
                view.invalidate()
                host.invalidate()
                // A same-size reattachment need not trigger ResizeObserver. Reapply presentation
                // to request fresh xterm paint; this neither replays bytes nor sends terminal input.
                lastConfiguration?.let { evaluate("window.meshTerminal?.configure($it)") }
            }
            override fun onViewDetachedFromWindow(detached: View) {
                if (!destroyed && view.parent === host) view.onPause()
            }
        })
        detach()
        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A late release of the old composition must never detach the new Activity's child. */
    fun releaseHost(host: FrameLayout) {
        if (view.parent === host) {
            if (!destroyed) view.onPause()
            host.removeView(view)
        }
    }

    internal fun destroy() {
        main.post {
            if (!destroyed) {
                destroyed = true; copyGeneration++
                main.removeCallbacks(startupTimeout)
                dialogSettle?.let(main::removeCallbacks)
                dialogSettle = null
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
                detach(); view.stopLoading(); view.removeJavascriptInterface("TerminalBridge"); view.destroy()
            }
        }
    }

    private fun evaluate(script: String) { main.post { if (!destroyed) view.evaluateJavascript(script, null) } }

    companion object {
        private const val HOST = "terminal.meshcentral.invalid"
        private const val URL = "https://$HOST/index.html"
        private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'none'; img-src 'none'; font-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        private val ASSETS = setOf("/index.html", "/terminal.js", "/terminal.css", "/xterm.js", "/xterm.css", "/addon-fit.js")
        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
