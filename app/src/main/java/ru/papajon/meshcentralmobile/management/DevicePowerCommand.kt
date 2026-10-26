package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

object DevicePowerCommand {
    fun available(device: MeshDevice, current: Boolean): Set<DevicePowerAction> = buildSet {
        val rights = device.rights ?: return@buildSet
        if (!current || !device.isMeshAgent || device.isAndroidAgent || !device.connectivityKnown) return@buildSet
        if (rights and MeshRights.WAKE_DEVICE != 0 && device.conn and 1 == 0) add(DevicePowerAction.Wake)
        if (rights and MeshRights.RESET_OFF != 0 && device.conn and 1 != 0) {
            add(DevicePowerAction.Restart); add(DevicePowerAction.Shutdown)
            add(DevicePowerAction.Sleep)
        }
    }

    fun create(device: MeshDevice, current: Boolean, action: DevicePowerAction): JSONObject {
        require(action in available(device, current))
        return JSONObject().put("action", if (action == DevicePowerAction.Wake) "wakedevices" else "poweraction")
            .put("nodeids", JSONArray().put(device.nodeId)).apply {
                if (action != DevicePowerAction.Wake) put("actiontype", when (action) {
                    DevicePowerAction.Shutdown -> 2
                    DevicePowerAction.Restart -> 3
                    DevicePowerAction.Sleep -> 4
                    else -> error("Unsupported power action")
                })
            }
    }
}
