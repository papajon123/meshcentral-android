package ru.papajon.meshcentralmobile.forward

import java.util.UUID
import okhttp3.HttpUrl
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

enum class ForwardKind(val defaultPort: Int, private val scheme: String?) {
    RDP(3389, "rdp"), VNC(5900, "vnc"), SSH(22, "ssh"), HTTP(80, "http"), HTTP_ALT(8080, "http"), HTTPS(443, "https"), CUSTOM(0, null);
    fun uri(port: Int): String? = when (this) {
        RDP -> "rdp://full%20address=s:127.0.0.1:$port"
        else -> scheme?.let { "$it://127.0.0.1:$port" }
    }
}

/** Configuration only. No credentials, cookies, or enabled state are persisted. */
data class PortForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val nodeId: String,
    val name: String,
    val kind: ForwardKind = ForwardKind.CUSTOM,
    val remotePort: Int,
    val remoteAddress: String = "",
    val localPort: Int = 0,
) {
    fun valid(): Boolean = id.length in 1..80 && profileId.length in 1..128 &&
        nodeId.startsWith("node/") && nodeId.length <= 2048 &&
        name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() } &&
        remotePort in 1..65535 && localPort in 0..65535 && validHost(remoteAddress)

    companion object {
        /** No URL, userinfo, whitespace or zone identifiers. DNS is resolved by the remote agent. */
        fun validHost(host: String): Boolean {
            if (host.isEmpty()) return true
            if (host.length > 253 || host.any { it.isWhitespace() || it.isISOControl() }) return false
            if ('[' in host || ']' in host) return false
            if (':' in host) return runCatching { HttpUrl.Builder().scheme("https").host(host).build().host.contains(':') }.getOrDefault(false)
            return host.split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' } }
        }
    }
}

fun MeshDevice.canForwardPorts(): Boolean {
    val r = rights ?: return false // Fail closed until a current rights snapshot is available.
    return (r == MeshRights.FULL || r and (MeshRights.REMOTE_CONTROL or 0x00200000) != 0) &&
        (mtype == 0 || mtype == 2) && !isAndroidAgent && (!connectivityKnown || conn and 1 != 0)
}

/** p=14 is MeshCentral's Web-TCP audit label, never a terminal/files usage handshake. */
internal fun tcpRelayUrl(base: HttpUrl, rule: PortForwardRule, cookie: String): HttpUrl {
    require(rule.valid())
    return base.newBuilder().addPathSegment("meshrelay.ashx")
        .query(null).fragment(null).addQueryParameter("auth", cookie)
        .addQueryParameter("nodeid", rule.nodeId).addQueryParameter("tcpport", rule.remotePort.toString())
        .addQueryParameter("p", "14")
        .apply { if (rule.remoteAddress.isNotEmpty()) addQueryParameter("tcpaddr", rule.remoteAddress) }.build()
}

enum class ForwardError {
    INVALID_RULE, UNAVAILABLE, PORT_IN_USE, LIMIT, CONNECT_FAILED, TIMEOUT, CONNECTION_LOST, TLS, SERVICE;
    /** A transport/buffer failure after pairing belongs to one connection, never its siblings. */
    val stopsRule: Boolean get() = this != CONNECTION_LOST && this != LIMIT
}

data class PortForwardState(
    val rule: PortForwardRule,
    val localPort: Int = 0,
    val running: Boolean = false,
    val error: ForwardError? = null,
    val connections: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val autoStopped: Boolean = false,
)
