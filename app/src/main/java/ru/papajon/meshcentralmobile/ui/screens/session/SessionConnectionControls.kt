package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.components.StatusDot
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape
import ru.papajon.meshcentralmobile.localization.asString

/** Always-visible session status and one-tap redraw/reconnect, independent of the hidden chrome. */
@Composable
internal fun SessionConnectionControls(
    state: RemoteDesktopState,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MeshTheme.colors.glass,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionConnectionStatus(state, onRefresh = onRefresh, onReconnect = onReconnect)
            SessionRefreshAction(state, onRefresh, onReconnect)
        }
    }
}

@Composable
internal fun SessionConnectionStatus(
    state: RemoteDesktopState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onReconnect: () -> Unit = {},
    extraDetails: String? = null,
) {
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    val connected = state.connectionState == ConnectionState.Connected && state.handshakeComplete && state.hasFrame
    val connecting = !connected && state.connectionState in setOf(
        ConnectionState.Connecting, ConnectionState.Authenticated, ConnectionState.LoadingRemoteDesktop,
        ConnectionState.Connected, ConnectionState.Reconnecting,
    )
    val status = stringResource(when {
        connected -> R.string.session_connection_ready
        state.connectionState == ConnectionState.Reconnecting -> R.string.session_connection_reconnecting
        connecting -> R.string.session_connection_connecting
        else -> R.string.session_connection_offline
    })
    val color = when {
        connected -> MeshTheme.colors.online
        connecting -> MeshTheme.colors.warning
        else -> MeshTheme.colors.danger
    }
    val title = stringResource(R.string.session_options_connection)
    IconButton(
        onClick = { detailsVisible = true },
        modifier = modifier.size(48.dp).testTag("session-connection-status").semantics {
            contentDescription = "$title: $status"
            stateDescription = status
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        StatusDot(color, size = 12.dp)
    }
    val keyboardAttached = LocalConfiguration.current.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY
    // Resolve in the screen's locale before entering the dialog's separate Android composition.
    val detailMessage = state.message?.asString()?.takeIf { it.isNotBlank() && it != status }
    val keyboardHint = stringResource(R.string.desktop_keyboard_release)
    val closeLabel = stringResource(R.string.common_action_close)
    val actionLabel = stringResource(if (connected) R.string.session_options_redraw else R.string.session_options_reconnect)
    if (detailsVisible) AlertDialog(
        onDismissRequest = { detailsVisible = false },
        modifier = Modifier.testTag("session-connection-details"),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, style = MaterialTheme.typography.titleMedium)
                detailMessage?.let { Text(it) }
                Text(sessionStreamRate(state.framesPerSecond, state.bytesPerSecond))
                if (state.remoteWidth > 0 && state.remoteHeight > 0) Text("${state.remoteWidth} × ${state.remoteHeight}")
                if (keyboardAttached && state.device?.isAndroidAgent != true) Text(keyboardHint)
                extraDetails?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { detailsVisible = false }) { Text(closeLabel) } },
        dismissButton = {
            val canReconnect = state.connectionState == ConnectionState.Offline || state.connectionState == ConnectionState.Failed
            if (connected || canReconnect) TextButton(onClick = {
                detailsVisible = false
                if (connected) onRefresh() else onReconnect()
            }) { Text(actionLabel) }
        },
    )
}

@Composable
internal fun SessionRefreshAction(state: RemoteDesktopState, onRefresh: () -> Unit, onReconnect: () -> Unit) {
    val connected = state.connectionState == ConnectionState.Connected && state.handshakeComplete
    val canReconnect = state.connectionState == ConnectionState.Offline || state.connectionState == ConnectionState.Failed
    IconButton(
        onClick = if (connected) onRefresh else onReconnect,
        enabled = connected || canReconnect,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = stringResource(if (connected) R.string.session_options_redraw else R.string.session_options_reconnect),
        )
    }
}
