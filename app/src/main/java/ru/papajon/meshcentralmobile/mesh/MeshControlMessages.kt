package ru.papajon.meshcentralmobile.mesh

import java.net.URLEncoder
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * Control-channel messages sent to MeshCentral `control.ashx`.
 *
 * Shapes verified against MeshCentral 1.2.5 `meshuser.js` (`case 'meshes'`, `case 'nodes'`,
 * `case 'msg'`, `serverCommandAuthCookie`) and `meshrelay.js` (viewer authentication).
 */
object MeshControlMessages {
    fun serverInfo(): JSONObject = JSONObject().put("action", "serverinfo")

    fun meshes(): JSONObject = JSONObject().put("action", "meshes")

    fun nodes(responseId: String? = null): JSONObject {
        val json = JSONObject().put("action", "nodes")
        if (responseId != null) json.put("responseid", responseId)
        return json
    }

    /**
     * Relay credentials. The answer is `{action:'authcookie', cookie, rcookie}`; `cookie` is used
     * as `?auth=` on the relay WebSocket and `rcookie` as `&rauth=` in the tunnel request.
     */
    fun authCookie(): JSONObject = JSONObject().put("action", "authcookie")

    fun ping(): JSONObject = JSONObject().put("action", "ping").put("responseid", "android-ping")

    fun pong(): JSONObject = JSONObject().put("action", "pong")

    /**
     * Asks the Mesh Agent to dial the relay. `value` must contain the same node id as `nodeid`,
     * because `meshuser.js` rejects a tunnel request whose URL node id differs from the command.
     */
    fun tunnelRequest(
        nodeId: String,
        tunnelId: String,
        relayCookie: String? = null,
        usage: Int = KVM_USAGE,
        responseId: String? = null,
        domainPath: String = "/",
    ): JSONObject {
        val json = JSONObject()
            .put("action", "msg")
            .put("type", "tunnel")
            .put("nodeid", nodeId)
            .put("usage", usage)
            .put("value", tunnelValue(nodeId, tunnelId, relayCookie, usage, domainPath))
        if (responseId != null) json.put("responseid", responseId)
        return json
    }

    /** Relative relay path exactly as the MeshCentral browser client sends it. */
    fun tunnelValue(nodeId: String, tunnelId: String, relayCookie: String? = null, usage: Int = KVM_USAGE, domainPath: String = "/"): String {
        val path = "/" + domainPath.trim('/')
        val builder = StringBuilder("*" + path.trimEnd('/') + "/meshrelay.ashx?p=")
            .append(usage)
            .append("&nodeid=")
            .append(urlEncode(nodeId))
            .append("&id=")
            .append(urlEncode(tunnelId))
        if (!relayCookie.isNullOrBlank()) builder.append("&rauth=").append(relayCookie)
        return builder.toString()
    }

    /** "Get the clipboard from the remote computer". */
    fun fetchClipboard(nodeId: String, tag: String? = null): JSONObject {
        val json = JSONObject()
            .put("action", "msg")
            .put("type", "getclip")
            .put("nodeid", nodeId)
        if (tag != null) json.put("tag", tag)
        return json
    }

    /** "Put this text into the clipboard of the remote computer". */
    fun sendClipboard(nodeId: String, text: String, tag: String? = null): JSONObject {
        val json = JSONObject()
            .put("action", "msg")
            .put("type", "setclip")
            .put("nodeid", nodeId)
            .put("data", text)
        if (tag != null) json.put("tag", tag)
        return json
    }

    /**
     * Viewer WebSocket URL: `wss://<host>/meshrelay.ashx?browser=1&p=2&nodeid=...&id=...[&auth=...]`.
     *
     * `browser=1` marks the viewer side of the relay pair. `auth` carries the server-issued
     * authcookie, because this app authenticates the control channel with `x-meshauth` instead of
     * a browser session cookie (`webserver.js:9135` decodes `?auth=` for WebSocket upgrades).
     */
    fun relayViewerUrl(
        baseUrl: HttpUrl,
        nodeId: String,
        tunnelId: String,
        authCookie: String? = null,
        relayCookie: String? = null,
        usage: Int = KVM_USAGE,
    ): String {
        val builder = baseUrl.newBuilder()
            .encodedPath(baseUrl.encodedPath.trimEnd('/') + "/meshrelay.ashx")
            .query(null)
            .addQueryParameter("browser", "1")
            .addQueryParameter("p", usage.toString())
            .addQueryParameter("nodeid", nodeId)
            .addQueryParameter("id", tunnelId)
        if (!authCookie.isNullOrBlank()) builder.addQueryParameter("auth", authCookie)
        if (!relayCookie.isNullOrBlank()) builder.addQueryParameter("rauth", relayCookie)
        val httpUrl = builder.build().toString()
        return when {
            httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
            httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
            else -> httpUrl
        }
    }

    fun randomTunnelId(random: java.security.SecureRandom): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(java.util.Locale.US, it.toInt() and 0xff) }
    }

    /** Authenticated device SSH endpoints; these do not use the Agent c/options/protocol handshake. */
    fun sshViewerUrl(baseUrl: HttpUrl, nodeId: String, authCookie: String, files: Boolean): String {
        val endpoint = if (files) "sshfilesrelay.ashx" else "sshterminalrelay.ashx"
        return baseUrl.newBuilder()
            .encodedPath(baseUrl.encodedPath.trimEnd('/') + "/" + endpoint)
            .query(null)
            .addQueryParameter("nodeid", nodeId)
            .addQueryParameter("auth", authCookie)
            .build().toString().let { if (baseUrl.isHttps) "wss://" + it.removePrefix("https://") else "ws://" + it.removePrefix("http://") }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Relay usage 2 is the Mesh Agent desktop (KVM) channel. */
    const val KVM_USAGE = 2

    /** `ctrlChannel` value used by MeshCentral desktop/terminal relays for control JSON. */
    const val CTRL_CHANNEL = 102938
}
