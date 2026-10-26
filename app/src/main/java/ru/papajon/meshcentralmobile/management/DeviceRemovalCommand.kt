package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshControlParser

enum class DeviceRemovalAction(val wire: String) { UninstallAgent("uninstallagent"), RemoveDevice("removedevices") }
enum class RemovalObservation { Unknown, Disconnected, Absent }

object DeviceRemovalCommand {
    fun available(device: MeshDevice, current: Boolean): Set<DeviceRemovalAction> = buildSet {
        if (!current || !device.isMeshAgent || device.isAndroidAgent ||
            device.rights?.let { it and MeshRights.UNINSTALL != 0 } != true) return@buildSet
        add(DeviceRemovalAction.RemoveDevice)
        if (device.connectivityKnown && device.conn and 1 != 0) add(DeviceRemovalAction.UninstallAgent)
    }

    fun create(device: MeshDevice, current: Boolean, action: DeviceRemovalAction, confirmed: Boolean, typedName: String): JSONObject {
        require(action in available(device, current))
        require(confirmed && device.name.isNotBlank() && typedName == device.name)
        return JSONObject().put("action", action.wire).put("nodeids", JSONArray().put(device.nodeId))
    }

    // Absence from a fresh, accessible device list is evidence of absence only, not proof of deletion.
    fun observe(action: DeviceRemovalAction, current: Boolean, device: MeshDevice?): RemovalObservation = when {
        !current -> RemovalObservation.Unknown
        action == DeviceRemovalAction.RemoveDevice && device == null -> RemovalObservation.Absent
        action == DeviceRemovalAction.UninstallAgent && device?.connectivityKnown == true && device.conn and 1 == 0 -> RemovalObservation.Disconnected
        else -> RemovalObservation.Unknown
    }

    fun observeReply(action: DeviceRemovalAction, nodeId: String, reply: JSONObject, current: Boolean): RemovalObservation {
        if (!current) return RemovalObservation.Unknown
        val groups = reply.optJSONObject("nodes") ?: return RemovalObservation.Unknown
        var target: MeshDevice? = null
        for (group in groups.keys()) {
            val nodes = groups.optJSONArray(group) ?: return RemovalObservation.Unknown
            for (index in 0 until nodes.length()) {
                val raw = nodes.optJSONObject(index) ?: return RemovalObservation.Unknown
                val id = (raw.opt("_id") as? String)?.takeIf { it.isNotBlank() } ?: return RemovalObservation.Unknown
                if (id == nodeId) target = MeshControlParser.parseNode(raw, group, fullSnapshot = true)
            }
        }
        return observe(action, true, target)
    }
}
