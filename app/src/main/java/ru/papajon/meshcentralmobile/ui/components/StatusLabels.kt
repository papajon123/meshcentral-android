package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.ClipboardKind
import ru.papajon.meshcentralmobile.mesh.DesktopCapability
import ru.papajon.meshcentralmobile.model.AppDestination
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode

/**
 * Localized labels for the closed sets of states that the model and mesh layers carry.
 *
 * The enums themselves stay free of display strings: only the text the screens show is translated
 * here, so the mapping exists in one place instead of being repeated inside every screen.
 */
@Composable
fun connectionStateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Offline -> stringResource(R.string.common_status_offline)
    ConnectionState.Connecting -> stringResource(R.string.common_connection_connecting)
    ConnectionState.Authenticated -> stringResource(R.string.common_connection_authenticated)
    ConnectionState.LoadingRemoteDesktop -> stringResource(R.string.common_connection_loading_desktop)
    ConnectionState.Connected -> stringResource(R.string.common_connection_connected)
    ConnectionState.Reconnecting -> stringResource(R.string.common_connection_reconnecting)
    ConnectionState.Failed -> stringResource(R.string.common_connection_failed)
}

/** Full control-mode name, used by the pointer sheet. */
@Composable
fun remoteControlModeLabel(mode: RemoteControlMode): String = when (mode) {
    RemoteControlMode.DirectTouch -> stringResource(R.string.session_mode_direct_touch)
    RemoteControlMode.Trackpad -> stringResource(R.string.session_mode_trackpad)
    RemoteControlMode.PanZoom -> stringResource(R.string.session_mode_pan_zoom)
    RemoteControlMode.Scroll -> stringResource(R.string.session_mode_scroll)
}

/** Short control-mode name, used by the dock where the label sits under a 22 dp icon. */
@Composable
fun remoteControlModeShortLabel(mode: RemoteControlMode): String = when (mode) {
    RemoteControlMode.DirectTouch -> stringResource(R.string.session_mode_direct_touch)
    RemoteControlMode.Trackpad -> stringResource(R.string.session_mode_trackpad)
    RemoteControlMode.PanZoom -> stringResource(R.string.session_mode_zoom_short)
    RemoteControlMode.Scroll -> stringResource(R.string.session_mode_scroll)
}

/** Two-word chip shown when a computer is online but its desktop cannot be opened. */
@Composable
fun desktopCapabilityShortLabel(capability: DesktopCapability): String = when (capability) {
    DesktopCapability.MeshAgentDesktop -> stringResource(R.string.devices_capability_ready)
    DesktopCapability.UnsupportedOffline -> stringResource(R.string.devices_capability_offline)
    DesktopCapability.UnsupportedNonMeshAgent -> stringResource(R.string.devices_capability_no_agent)
    DesktopCapability.UnsupportedNoPermission -> stringResource(R.string.devices_capability_no_rights)
}

/** Words that finish the sentence "Connection unavailable: …". */
@Composable
fun desktopCapabilityReason(capability: DesktopCapability): String = when (capability) {
    DesktopCapability.MeshAgentDesktop -> stringResource(R.string.devices_capability_mesh_agent)
    DesktopCapability.UnsupportedOffline -> stringResource(R.string.devices_capability_reason_offline)
    DesktopCapability.UnsupportedNonMeshAgent -> stringResource(R.string.devices_capability_reason_non_agent)
    DesktopCapability.UnsupportedNoPermission -> stringResource(R.string.devices_capability_reason_no_permission)
}

/** Navigation bar title, also used as the content description of its icon. */
@Composable
fun appDestinationLabel(destination: AppDestination): String = when (destination) {
    AppDestination.Devices -> stringResource(R.string.common_nav_devices_short)
    AppDestination.Session -> stringResource(R.string.common_nav_session)
    AppDestination.Settings -> stringResource(R.string.common_nav_settings)
}

/** Direction of a clipboard transfer. */
@Composable
fun clipboardKindLabel(kind: ClipboardKind): String = when (kind) {
    ClipboardKind.Fetch -> stringResource(R.string.session_clipboard_kind_fetch)
    ClipboardKind.Send -> stringResource(R.string.session_clipboard_kind_send)
}
