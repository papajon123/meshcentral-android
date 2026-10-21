package ru.papajon.meshcentralmobile.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.model.ConnectionState

data class MeshWebViewStatus(
    val state: ConnectionState = ConnectionState.Offline,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val progress: Int = 0,
)

object MeshWebViewController {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        webView: WebView,
        serverUrl: String,
        onStatusChanged: (MeshWebViewStatus) -> Unit,
    ) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val serverUri = Uri.parse(serverUrl)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            safeBrowsingEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onStatusChanged(
                    MeshWebViewStatus(
                        state = if (newProgress >= 100) ConnectionState.Connected else ConnectionState.Connecting,
                        isLoading = newProgress < 100,
                        progress = newProgress,
                    ),
                )
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val targetUri = request.url
                if (targetUri.scheme == "http" || targetUri.scheme == "https") {
                    val sameHost = targetUri.host.equals(serverUri.host, ignoreCase = true)
                    if (sameHost) return false
                    openExternalBrowser(view.context, targetUri)
                    return true
                }
                onStatusChanged(
                    MeshWebViewStatus(
                        state = ConnectionState.Failed,
                        errorMessage = "Ссылка с\u00A0такой схемой заблокирована: ${targetUri.scheme}",
                    ),
                )
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onStatusChanged(
                    MeshWebViewStatus(
                        state = ConnectionState.Connecting,
                        isLoading = true,
                    ),
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                onStatusChanged(
                    MeshWebViewStatus(
                        state = ConnectionState.Connected,
                        isLoading = false,
                        progress = 100,
                    ),
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onStatusChanged(
                        MeshWebViewStatus(
                            state = ConnectionState.Failed,
                            isLoading = false,
                            errorMessage = error?.description?.toString() ?: "Не\u00A0удалось загрузить страницу в\u00A0WebView.",
                        ),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    onStatusChanged(
                        MeshWebViewStatus(
                            state = ConnectionState.Failed,
                            isLoading = false,
                            errorMessage = "HTTP ${errorResponse?.statusCode ?: "error"}",
                        ),
                    )
                }
            }
        }
    }

    fun clearWebViewSession(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        context.cacheDir.deleteRecursively()
    }

    private fun openExternalBrowser(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No-op: the in-app WebView remains on the current MeshCentral page.
        }
    }
}
