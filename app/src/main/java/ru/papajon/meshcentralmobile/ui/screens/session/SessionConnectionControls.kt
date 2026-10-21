package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
        shape = PillShape,
        color = MeshTheme.colors.glass,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionConnectionStatus(state, modifier = Modifier.weight(1f, fill = false))
            SessionRefreshAction(state, onRefresh, onReconnect)
        }
    }
}

@Composable
internal fun SessionConnectionStatus(state: RemoteDesktopState, modifier: Modifier = Modifier) {
    val connected = state.connectionState == ConnectionState.Connected && state.handshakeComplete
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, size = 8.dp)
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
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
