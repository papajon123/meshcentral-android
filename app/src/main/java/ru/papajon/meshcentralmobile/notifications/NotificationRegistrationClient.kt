package ru.papajon.meshcentralmobile.notifications

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient

class NotificationPluginException(val code: String) : java.io.IOException(code)

/** Typed wrapper around the two authenticated websocket operations. No HTTP is done here. */
internal class NotificationRegistrationClient(private val client: MeshCentralClient) {
    suspend fun register(): JSONObject = execute(OP_REGISTER, JSONObject())

    suspend fun revoke(tokenId: String) {
        execute(OP_REVOKE, JSONObject().put("tokenId", tokenId))
    }

    private suspend fun execute(op: String, fields: JSONObject): JSONObject {
        val command = JSONObject()
            .put("action", ACTION_PLUGIN)
            .put("plugin", NotificationContract.PLUGIN_ID)
            .put("op", op)
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            command.put(key, fields.opt(key))
        }
        val response = client.requestControl(
            command = command,
            responseAction = ACTION_PLUGIN,
            nodeId = null,
            timeoutMillis = TIMEOUT_MILLIS,
        )
        if (response.optText("action") != ACTION_PLUGIN ||
            response.optText("plugin") != NotificationContract.PLUGIN_ID
        ) {
            throw NotificationPluginException("PROTOCOL")
        }
        if (!response.optBoolean("ok", false)) {
            val code = response.optJSONObject("error")?.optText("code")
                ?.takeIf { it.isNotBlank() } ?: "PROTOCOL"
            throw NotificationPluginException(code)
        }
        return response.optJSONObject("data") ?: throw NotificationPluginException("PROTOCOL")
    }

    companion object {
        private const val ACTION_PLUGIN = "plugin"
        private const val OP_REGISTER = "registerNotifications"
        private const val OP_REVOKE = "revokeNotifications"
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
