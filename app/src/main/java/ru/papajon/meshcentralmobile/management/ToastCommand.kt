package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

/** MeshCentral 1.3.1 meshuser.js:3464–3509. An OK response is NOT a delivery receipt. */
object ToastCommand {
    const val MAX_TITLE = 512
    const val MAX_MESSAGE = 4096

    fun allowed(device: MeshDevice, current: Boolean): Boolean = current && device.connectivityKnown &&
        device.conn and 1 != 0 && device.isMeshAgent && device.agentId != 14 &&
        device.rights?.let { it and MeshRights.CHAT_NOTIFY != 0 } == true

    fun valid(title: String, message: String): Boolean = title.isNotBlank() && title.length <= MAX_TITLE &&
        message.isNotBlank() && message.length <= MAX_MESSAGE

    fun create(device: MeshDevice, current: Boolean, title: String, message: String): JSONObject {
        require(allowed(device, current)) { "Notification unavailable" }
        require(valid(title, message)) { "Invalid notification length" }
        return JSONObject().put("action", "toast").put("nodeids", JSONArray().put(device.nodeId))
            .put("title", title).put("msg", message)
    }
}
