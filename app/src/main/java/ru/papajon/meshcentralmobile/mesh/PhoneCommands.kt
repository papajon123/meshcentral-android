package ru.papajon.meshcentralmobile.mesh

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

enum class PhoneAction { Vibrate, Flash, Alert, Toast, OpenUrl, Dial }

/** In-memory form contents, never saved or logged. */
class PhoneRequest(val action: PhoneAction, val text: String = "", val title: String = "")

/** MeshCentral 1.3.1 meshuser.js:3416-3419,1021-1104; Android MeshAgent.kt:715-795. */
object PhoneCommands {
    const val MAX_TEXT = 1000
    const val MAX_TITLE = 80
    const val MAX_URL = 2048
    const val MAX_NUMBER = 40

    fun canSend(device: MeshDevice, current: Boolean): Boolean = current && device.isAndroidAgent &&
        device.connectivityKnown && device.conn and 1 != 0 &&
        device.rights?.let { it and MeshRights.REMOTE_CONTROL != 0 } == true

    fun canSend(device: MeshDevice, current: Boolean, action: PhoneAction): Boolean = canSend(device, current) &&
        (action == PhoneAction.Vibrate || action == PhoneAction.Flash ||
            device.rights?.let { it and MeshRights.AGENT_CONSOLE != 0 } == true)

    /** The agent has NO backslash escaping. Replace unrepresentable ASCII quotes visibly. */
    fun displayText(value: String): String = value.replace('"', '”')

    fun message(nodeId: String, request: PhoneRequest): JSONObject = when (request.action) {
        PhoneAction.Vibrate, PhoneAction.Flash -> JSONObject().put("action", "poweraction")
            .put("nodeids", JSONArray().put(nodeId))
            .put("actiontype", if (request.action == PhoneAction.Flash) 400 else 401)
            .put("time", if (request.action == PhoneAction.Flash) 10000 else 5000)
        PhoneAction.Alert -> console(nodeId,
            "alert ${argument(request.text, MAX_TEXT)} ${argument(request.title, MAX_TITLE)}")
        PhoneAction.Toast -> console(nodeId, "toast ${argument(request.text, MAX_TEXT)}")
        PhoneAction.Dial -> {
            require(request.text.length in 1..MAX_NUMBER && request.text.matches(Regex("[0-9+*#]+")))
            console(nodeId, "dial ${request.text.replace("#", "%23")}")
        }
        PhoneAction.OpenUrl -> {
            val value = request.text
            // The agent toggles quotes and has no escape syntax. Encoded %20/%22 are safe.
            require(value.length in 1..MAX_URL && value.none { it == '"' || it.isWhitespace() || Character.isISOControl(it) })
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && (value.startsWith("http://") || value.startsWith("https://")) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            console(nodeId, "openbrowser \"$value\"")
        }
    }

    private fun argument(value: String, limit: Int): String {
        require(value.isNotBlank() && value.length <= limit &&
            value.none { Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t' })
        return "\"${displayText(value)}\""
    }

    private fun console(nodeId: String, value: String): JSONObject = JSONObject()
        .put("action", "msg").put("type", "console").put("nodeid", nodeId).put("value", value)
}
