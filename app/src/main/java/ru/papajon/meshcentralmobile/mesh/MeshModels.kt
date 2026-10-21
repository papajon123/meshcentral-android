package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import ru.papajon.meshcentralmobile.model.ConnectionState

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

    /** Site administrators receive this value in `meshes[].links[userid].rights`. */
    const val FULL = -1
}

data class MeshGroup(
    val id: String,
    val name: String,
    val description: String = "",
    /** Raw `links` map from the control channel: MeshCentral user id -> rights bitmask. */
    val rightsByUser: Map<String, Int> = emptyMap(),
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
) {
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

    val statusLabel: String
        get() = when {
            !online -> "offline"
            viewOnly -> "online · view only"
            else -> "online"
        }
}

enum class DeviceStatus(val label: String) {
    Online("online"),
    Offline("offline"),
}

enum class DesktopCapability(val label: String) {
    MeshAgentDesktop("Mesh Agent desktop"),
    UnsupportedOffline("offline"),
    UnsupportedNonMeshAgent("not a Mesh Agent session"),
    UnsupportedNoPermission("no remote-control permission for this device group"),
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

    val label: String
        get() = buildString {
            append("Two-factor code required")
            val channels = buildList {
                if (email) add("email")
                if (sms) add("SMS")
                if (push) add("push")
            }
            if (channels.isNotEmpty()) append(" (").append(channels.joinToString(", ")).append(")")
        }
}

data class MeshClientState(
    val connectionState: ConnectionState = ConnectionState.Offline,
    val serverName: String? = null,
    val message: String? = null,
    val lastError: String? = null,
    val groups: List<MeshGroup> = emptyList(),
    val devices: List<MeshDevice> = emptyList(),
    val userId: String? = null,
    val twoFactorChallenge: TwoFactorChallenge? = null,
    val socketConnected: Boolean = false,
    val requiresSignIn: Boolean = true,
    /** False until a fresh nodes snapshot arrives after reconnecting. */
    val devicesCurrent: Boolean = false,
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
    val detail: String? = null,
)

data class RemoteDesktopState(
    val connectionState: ConnectionState = ConnectionState.Offline,
    val device: MeshDevice? = null,
    val message: String? = null,
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

    val qualityLabel: String
        get() {
            val rtt = rttMillis ?: return "measuring"
            return when {
                rtt < 80 -> "excellent"
                rtt < 180 -> "good"
                rtt < 400 -> "fair"
                else -> "poor"
            }
        }
}

/** Events emitted by the control channel parser. */
sealed interface MeshControlEvent {
    data class ServerInfo(val name: String?, val domain: String?) : MeshControlEvent
    data class UserInfo(val userId: String?, val name: String?, val siteAdmin: Boolean) : MeshControlEvent
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
