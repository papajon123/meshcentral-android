package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import androidx.annotation.StringRes
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability
import ru.papajon.meshcentralmobile.mesh.session.availabilityFor

/**
 * MeshCentral mesh (device group) rights.
 *
 * Values verified against MeshCentral 1.2.5 `meshrelay.js` (MESHRIGHT_* constants) and `meshuser.js`.
 */
object MeshRights {
    const val NONE = 0
    const val EDIT_MESH = 1
    const val MANAGE_USERS = 2
    const val MANAGE_COMPUTERS = 4
    const val REMOTE_CONTROL = 8
    const val AGENT_CONSOLE = 16
    const val SERVER_FILES = 32
    const val WAKE_DEVICE = 64
    const val SET_NOTES = 128
    const val REMOTE_VIEW = 256
    const val NO_TERMINAL = 512
    const val NO_FILES = 1024
    const val DESKTOP_LIMITED_INPUT = 4096
    const val NO_DESKTOP = 65536

    /** Site administrators receive this value in `meshes[].links[userid].rights`. */
    const val FULL = -1
}

data class MeshGroup(
    val id: String,
    val name: String,
    val description: String = "",
    /** Raw `links` map from the control channel: MeshCentral user id -> rights bitmask. */
    val rightsByUser: Map<String, Int> = emptyMap(),
    val relayNodeId: String? = null,
) {
    fun rightsFor(userId: String?): Int? {
        if (userId.isNullOrBlank()) return null
        return rightsByUser[userId]
    }
}

data class MeshDevice(
    val nodeId: String,
    val meshId: String,
    val name: String,
    val meshName: String = "",
    val icon: Int = 0,
    val conn: Int = 0,
    val pwr: Int = 0,
    val osDescription: String = "",
    val ipAddress: String = "",
    val mtype: Int = 0,
    val agentId: Int? = null,
    val agentVersion: String? = null,
    val agentCaps: Int? = null,
    /** Resolved from the mesh `links` for the signed-in user; null when the server did not send links. */
    val rights: Int? = null,
    val host: String? = null,
    val sshPort: Int? = null,
    /** Effective direct node grants, independent of the group grant. No credentials are retained. */
    val rightsByUser: Map<String, Int> = emptyMap(),
    val relayNodeId: String? = null,
    /** False for an omitted conn field; never turn missing metadata into a known offline state. */
    val connectivityKnown: Boolean = true,
) {
    fun sessionAvailability(protocol: RelayProtocol, serverCapabilities: MeshServerCapabilities = MeshServerCapabilities()): SessionAvailability =
        availabilityFor(this, protocol, serverCapabilities)

    val status: DeviceStatus
        get() = if (conn > 0) DeviceStatus.Online else DeviceStatus.Offline

    val online: Boolean get() = conn > 0

    val isMeshAgent: Boolean get() = agentId != null

    /** 256 = view-only, unless the account also holds remote control (8) or full rights. */
    val viewOnly: Boolean
        get() {
            val value = rights ?: return false
            if (value == MeshRights.FULL) return false
            val canView = (value and MeshRights.REMOTE_VIEW) != 0 || (value and MeshRights.REMOTE_CONTROL) != 0
            val canControl = (value and MeshRights.REMOTE_CONTROL) != 0
            return canView && !canControl
        }

    val hasDesktopPermission: Boolean
        get() {
            val value = rights ?: return true // Server did not send links; let the server decide.
            if (value == MeshRights.FULL) return true
            if ((value and MeshRights.NO_DESKTOP) != 0) return false
            return (value and MeshRights.REMOTE_CONTROL) != 0 || (value and MeshRights.REMOTE_VIEW) != 0
        }

    val desktopCapability: DesktopCapability
        get() = when {
            !online -> DesktopCapability.UnsupportedOffline
            !isMeshAgent -> DesktopCapability.UnsupportedNonMeshAgent
            !hasDesktopPermission -> DesktopCapability.UnsupportedNoPermission
            else -> DesktopCapability.MeshAgentDesktop
        }

    val canOpenNativeDesktop: Boolean
        get() = desktopCapability == DesktopCapability.MeshAgentDesktop

    /** View-only sessions still open a desktop, but the client must not send input. */
    val inputAllowed: Boolean
        get() = canOpenNativeDesktop && !viewOnly

    val statusLabel: UiText
        get() = when {
            !online -> UiText.Resource(R.string.common_status_offline)
            viewOnly -> UiText.Resource(R.string.runtime_status_online_view_only)
            else -> UiText.Resource(R.string.common_status_online)
        }
}

enum class DeviceStatus(@StringRes private val labelRes: Int) {
    Online(R.string.common_status_online),
    Offline(R.string.common_status_offline);

    val label: UiText
        get() = UiText.Resource(labelRes)
}

enum class DesktopCapability(@StringRes private val labelRes: Int) {
    MeshAgentDesktop(R.string.runtime_capability_mesh_agent),
    UnsupportedOffline(R.string.common_status_offline),
    UnsupportedNonMeshAgent(R.string.runtime_capability_not_agent),
    UnsupportedNoPermission(R.string.runtime_capability_no_permission);

    val label: UiText
        get() = UiText.Resource(labelRes)
}

data class TwoFactorChallenge(
    val email: Boolean = false,
    val sms: Boolean = false,
    val push: Boolean = false,
    val emailSent: Boolean = false,
    val smsSent: Boolean = false,
    val pushSent: Boolean = false,
) {
    val requestable: Boolean get() = email || sms || push

    val label: UiText
        get() {
            val channels = buildList {
                if (email) add("email")
                if (sms) add("SMS")
                if (push) add("push")
            }
            return if (channels.isEmpty()) {
                UiText.Resource(R.string.runtime_two_factor_required)
            } else {
                UiText.resource(
                    R.string.runtime_two_factor_required_with_channels,
                    channels.joinToString(", "),
                )
            }
        }
}

/** Flags sent by serverinfo; absence is distinct from a known disabled feature. */
data class MeshServerCapabilities(
    val features2: Long? = null,
    val consent: Int? = null,
    val linuxShell: String? = null,
    val domainSuffix: String? = null,
) {
    val sshEnabled: Boolean? get() = features2?.let { it and 0x200L != 0L }
    val sftpEnabled: Boolean? get() = features2?.let { it and 0x200L != 0L && it and 0x800000L == 0L }
    val sshTerminalEnabled: Boolean? get() = features2?.let { it and 0x200L != 0L && it and 0x1000000L == 0L }
    val savingDeviceCredentialsAllowed: Boolean? get() = features2?.let { it and 0x400000L == 0L }
}

data class MeshClientState(
    val connectionState: ConnectionState = ConnectionState.Offline,
    val serverName: String? = null,
    val message: UiText? = null,
    val lastError: UiText? = null,
    val groups: List<MeshGroup> = emptyList(),
    val devices: List<MeshDevice> = emptyList(),
    val userId: String? = null,
    val twoFactorChallenge: TwoFactorChallenge? = null,
    val socketConnected: Boolean = false,
    val requiresSignIn: Boolean = true,
    /** False until a fresh nodes snapshot arrives after reconnecting. */
    val devicesCurrent: Boolean = false,
    val serverCapabilities: MeshServerCapabilities = MeshServerCapabilities(),
) {
    val authenticated: Boolean
        get() = connectionState == ConnectionState.Authenticated ||
            connectionState == ConnectionState.LoadingRemoteDesktop ||
            connectionState == ConnectionState.Connected

    val awaitingTwoFactor: Boolean
        get() = twoFactorChallenge != null
}

data class DisplayInfo(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

enum class ClipboardKind { Fetch, Send }

enum class ClipboardStatus { Pending, Success, Failed }

data class ClipboardTransfer(
    val kind: ClipboardKind,
    val nodeId: String,
    val text: String? = null,
    val status: ClipboardStatus,
    val detail: UiText? = null,
)

data class RemoteDesktopState(
    val connectionState: ConnectionState = ConnectionState.Offline,
    val device: MeshDevice? = null,
    val message: UiText? = null,
    val framebuffer: Bitmap? = null,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val frameVersion: Long = 0L,
    val rttMillis: Long? = null,
    val handshakeComplete: Boolean = false,
    val displays: List<Int> = emptyList(),
    val selectedDisplay: Int? = null,
    val displayGeometry: List<DisplayInfo> = emptyList(),
    val cursorIndex: Int = 0,
    val inputLocked: Boolean = false,
    val keyboardState: Int = 0,
    val viewOnly: Boolean = false,
    val bytesReceived: Long = 0L,
    val clipboard: ClipboardTransfer? = null,
    val attempt: Int = 0,
) {
    val hasFrame: Boolean
        get() = framebuffer != null && remoteWidth > 0 && remoteHeight > 0

    /** True when the client may send keyboard/mouse packets. */
    val inputEnabled: Boolean
        get() = connectionState == ConnectionState.Connected &&
            handshakeComplete &&
            !viewOnly &&
            !inputLocked

    val qualityLabel: UiText
        get() {
            val rtt = rttMillis ?: return UiText.Resource(R.string.common_quality_measuring)
            return UiText.Resource(
                when {
                    rtt < 80 -> R.string.common_quality_excellent
                    rtt < 180 -> R.string.common_quality_good
                    rtt < 400 -> R.string.common_quality_fair
                    else -> R.string.common_quality_poor
                },
            )
        }
}

/** Events emitted by the control channel parser. */
sealed interface MeshControlEvent {
    data class ServerInfo(val name: String?, val domain: String?, val capabilities: MeshServerCapabilities = MeshServerCapabilities()) : MeshControlEvent
    data class UserInfo(val userId: String?, val name: String?, val siteAdmin: Boolean, val userGroupIds: Set<String> = emptySet()) : MeshControlEvent
    data class Meshes(val groups: List<MeshGroup>) : MeshControlEvent
    data class Nodes(val devices: List<MeshDevice>) : MeshControlEvent
    data class AuthCookie(val cookie: String?, val relayCookie: String?) : MeshControlEvent
    data class NodeUpdated(val event: MeshNodeEvent) : MeshControlEvent
    data class ClipMessage(
        val type: String,
        val success: Boolean?,
        val data: String?,
        val tag: String?,
        val sessionId: String?,
    ) : MeshControlEvent

    data class RoutingResult(val result: String?, val tag: String?, val responseId: String?) : MeshControlEvent
    data class Close(
        val cause: String?,
        val message: String?,
        val twoFactorChallenge: TwoFactorChallenge?,
    ) : MeshControlEvent

    data class Unknown(val action: String?, val rawLength: Int) : MeshControlEvent
}

/** Server pushed node events (`{action:'event', event:{action:...}}`). */
sealed interface MeshNodeEvent {
    data class NodeConnected(val nodeId: String, val conn: Int, val pwr: Int) : MeshNodeEvent
    data class NodeChanged(val device: MeshDevice) : MeshNodeEvent
    data class NodeAdded(val device: MeshDevice) : MeshNodeEvent
    data class NodeRemoved(val nodeId: String) : MeshNodeEvent
    data class MeshChanged(val meshId: String?) : MeshNodeEvent
}

/** KVM commands received from the Mesh Agent through the relay. */
sealed interface KvmPacket {
    data class ScreenSize(val width: Int, val height: Int) : KvmPacket

    data class Tile(val x: Int, val y: Int, val image: ByteArray) : KvmPacket {
        override fun equals(other: Any?): Boolean {
            return other is Tile && x == other.x && y == other.y && image.contentEquals(other.image)
        }

        override fun hashCode(): Int = 31 * (31 * x + y) + image.contentHashCode()
    }

    data class CopyRect(
        val sourceX: Int,
        val sourceY: Int,
        val targetX: Int,
        val targetY: Int,
        val width: Int,
        val height: Int,
    ) : KvmPacket

    data class Displays(val ids: List<Int>, val selected: Int) : KvmPacket {
        override fun equals(other: Any?): Boolean {
            return other is Displays && ids == other.ids && selected == other.selected
        }

        override fun hashCode(): Int = 31 * ids.hashCode() + selected
    }

    data class DisplayGeometry(val displays: List<DisplayInfo>) : KvmPacket {
        override fun equals(other: Any?): Boolean = other is DisplayGeometry && displays == other.displays

        override fun hashCode(): Int = displays.hashCode()
    }

    data class KeyState(val mask: Int) : KvmPacket
    data class Cursor(val index: Int) : KvmPacket
    data class InputLock(val locked: Boolean) : KvmPacket
    data class Message(val command: Int, val text: String) : KvmPacket
    data class Unknown(val command: Int, val payloadSize: Int) : KvmPacket
}
