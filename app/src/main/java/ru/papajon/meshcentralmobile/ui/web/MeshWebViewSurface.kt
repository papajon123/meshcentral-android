package ru.papajon.meshcentralmobile.ui.web

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.ui.components.connectionStateLabel
import ru.papajon.meshcentralmobile.web.MeshWebViewController
import ru.papajon.meshcentralmobile.web.MeshWebViewStatus

@Composable
fun MeshWebViewSurface(
    serverUrl: String,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    onWebViewReady: (WebView) -> Unit = {},
) {
    if (!BuildConfig.DEBUG) return
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf(MeshWebViewStatus()) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = connectionStateLabel(status.state),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { webViewRef?.reload() }) {
                    Text(stringResource(R.string.common_action_refresh))
                }
            }
        }
        if (status.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = { (status.progress.coerceIn(0, 100)) / 100f },
            )
        }
        status.errorMessage?.let {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp),
                text = it.asString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    MeshWebViewController.configure(
                        webView = this,
                        serverUrl = serverUrl,
                        onStatusChanged = { status = it },
                    )
                    webViewRef = this
                    onWebViewReady(this)
                    loadUrl(serverUrl)
                }
            },
            update = { webView ->
                webViewRef = webView
                onWebViewReady(webView)
            },
        )
    }
}
