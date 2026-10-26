package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

data class HardwareDetails(
    val biosVendor: String? = null, val biosVersion: String? = null, val biosDate: String? = null, val biosMode: String? = null,
    val boardName: String? = null, val boardVendor: String? = null, val boardVersion: String? = null,
    val tpmSpec: String? = null, val tpmManufacturer: String? = null, val tpmVersion: String? = null,
    val tpmActivated: Boolean? = null, val tpmEnabled: Boolean? = null, val tpmOwned: Boolean? = null,
    val lastBootMillis: Long? = null,
) {
    companion object {
        fun parse(reply: JSONObject): HardwareDetails {
            val hardware = reply.optJSONObject("hardware")
            val ids = hardware?.optJSONObject("identifiers")
            val tpm = hardware?.optJSONObject("tpm")
            val boot = (reply.optJSONObject("node")?.opt("lastbootuptime") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it > 0 && it <= 4_102_444_800_000.0 && it % 1 == 0.0 }?.toLong()
            return HardwareDetails(ids.text("bios_vendor"), ids.text("bios_version"), ids.text("bios_date"), ids.text("bios_mode"),
                ids.text("board_name"), ids.text("board_vendor"), ids.text("board_version"),
                tpm.text("SpecVersion"), tpm.scalar("ManufacturerId"), tpm.text("ManufacturerVersion"),
                tpm?.opt("IsActivated") as? Boolean, tpm?.opt("IsEnabled") as? Boolean, tpm?.opt("IsOwned") as? Boolean, boot)
        }
    }
}

data class InstalledProgram(val name: String?, val version: String?, val publisher: String?)

object InstalledSoftware {
    const val LIMIT = 1000
    fun allowed(device: MeshDevice, current: Boolean): Boolean {
        val rights = device.rights ?: return false
        return NativeMetrics.allowed(device, current) && (rights == MeshRights.FULL || rights and MeshRights.NO_SOFTWARE == 0)
    }
    fun command(device: MeshDevice, current: Boolean): JSONObject {
        require(allowed(device, current))
        return JSONObject().put("action", "software").put("nodeid", device.nodeId).put("type", "installedapps")
    }
    fun parse(reply: JSONObject): List<InstalledProgram> {
        val value = reply.opt("value") as? String ?: throw IllegalArgumentException("Missing software data")
        require(value.length <= 2_000_000 && value.trimStart().startsWith("["))
        val array = JSONArray(value)
        return (0 until minOf(array.length(), LIMIT)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            InstalledProgram(item.text("name"), item.text("version"), item.text("publisher"))
        }
    }
}

data class MetricsSample(val cpuPercent: Double?, val memoryPercent: Double?, val receivedAtMillis: Long)

object NativeMetrics {
    fun allowed(device: MeshDevice, current: Boolean): Boolean = current && device.isMeshAgent && !device.isAndroidAgent &&
        device.connectivityKnown && device.conn and 1 != 0 &&
        device.rights?.let { it and (MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW) != 0 } == true
    fun command(device: MeshDevice, current: Boolean): JSONObject {
        require(allowed(device, current))
        return JSONObject().put("action", "msg").put("type", "cpuinfo").put("nodeid", device.nodeId)
    }
    fun parse(reply: JSONObject, nowMillis: Long): MetricsSample = MetricsSample(
        percent(reply.optJSONObject("cpu")?.opt("total")), percent(reply.optJSONObject("memory")?.opt("percentConsumed")), nowMillis)
    private fun percent(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 }
}

private fun JSONObject?.text(key: String): String? = (this?.opt(key) as? String)?.trim()?.take(256)?.takeIf { it.isNotEmpty() }
private fun JSONObject?.scalar(key: String): String? = when (val value = this?.opt(key)) {
    is String -> value.trim().take(256).takeIf { it.isNotEmpty() }
    is Number -> value.toString().take(256)
    else -> null
}
