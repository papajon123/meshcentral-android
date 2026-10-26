package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps the system and network information returned by MeshCentral for a node onto
 * [DeviceSystemSnapshot].
 *
 * The field names follow the data that the server and MeshCtrl actually consume:
 * `node.rname`/`node.name`, `hardware.windows.osinfo`, `hardware.linux`,
 * `hardware.identifiers`, `hardware.windows.memory`, `hardware.linux.memory.Memory_Device`,
 * `hardware.identifiers.storage_devices`, `network.netif2` and `network.netif`.
 *
 * This parser intentionally ignores everything else: serial numbers, UUIDs, BitLocker recovery
 * material, users, credentials and any unknown vendor fields never leave this function. It is
 * tolerant of missing or malformed values and returns an empty snapshot instead of throwing.
 */
object SystemInfoParser {
    private const val BYTES_PER_GIB = 1024L * 1024L * 1024L

    fun parse(
        systemInfo: JSONObject,
        networkInfo: JSONObject? = null,
    ): DeviceSystemSnapshot {
        return try {
            val node = systemInfo.optJSONObject("node")
            val hardware = systemInfo.optJSONObject("hardware")
            val windows = hardware?.optJSONObject("windows")
            val linux = hardware?.optJSONObject("linux")
            val identifiers = hardware?.optJSONObject("identifiers")
            val osInfo = windows?.optJSONObject("osinfo")
            val mobile = hardware?.optJSONObject("mobile")

            DeviceSystemSnapshot(
                deviceName = node?.text("rname") ?: node?.text("name"),
                osName = osInfo?.text("Caption") ?: node?.text("osdesc") ?: mobile?.let { "Android" },
                osVersion = osInfo?.text("Version") ?: osInfo?.text("BuildNumber") ?: mobile?.text("androidrelease"),
                architecture = osInfo?.text("OSArchitecture") ?: linux?.text("arch"),
                kernelRelease = linux?.text("kernel_release"),
                cpu = identifiers?.text("cpu_name") ?: windowsCpuName(windows),
                model = identifiers?.text("product_name")
                    ?: mobile?.text("model")
                    ?: identifiers?.text("board_name")
                    ?: linux?.text("board_name"),
                memoryModules = memoryModules(windows, linux),
                disks = disks(identifiers),
                networkAddresses = networkAddresses(networkInfo),
                hardwareDetails = HardwareDetails.parse(systemInfo),
            )
        } catch (_: Exception) {
            DeviceSystemSnapshot()
        }
    }

    private fun windowsCpuName(windows: JSONObject?): String? {
        if (windows == null) return null
        for (processor in windows.objectValues("cpu")) {
            processor.text("Name")?.let { return it }
        }
        return null
    }

    private fun memoryModules(windows: JSONObject?, linux: JSONObject?): List<DeviceSystemSnapshot.MemoryModule> {
        val modules = ArrayList<DeviceSystemSnapshot.MemoryModule>()
        if (windows != null) {
            for (module in windows.objectValues("memory")) {
                val capacity = positiveLong(module.opt("Capacity"))
                val speed = module.scalarText("Speed")
                if (capacity != null || speed != null) {
                    modules += DeviceSystemSnapshot.MemoryModule(capacityBytes = capacity, speed = speed)
                }
            }
        }
        val linuxMemory = linux?.optJSONObject("memory")
        if (linuxMemory != null) {
            for (module in linuxMemory.objectValues("Memory_Device")) {
                val sizeGb = positiveLong(module.opt("Size"))
                val capacity = sizeGb?.let(::gibibytesToBytes)
                val speed = module.scalarText("Speed")
                if (capacity != null || speed != null) {
                    modules += DeviceSystemSnapshot.MemoryModule(capacityBytes = capacity, speed = speed)
                }
            }
        }
        return modules
    }

    private fun disks(identifiers: JSONObject?): List<DeviceSystemSnapshot.Disk> {
        if (identifiers == null) return emptyList()
        val disks = ArrayList<DeviceSystemSnapshot.Disk>()
        for (storage in identifiers.objectValues("storage_devices")) {
            val name = storage.text("Caption")
            val model = storage.text("Model")
            val capacity = positiveLong(storage.opt("Size"))
            if (name != null || model != null || capacity != null) {
                disks += DeviceSystemSnapshot.Disk(name = name, model = model, capacityBytes = capacity)
            }
        }
        return disks
    }

    private fun networkAddresses(networkInfo: JSONObject?): List<DeviceSystemSnapshot.NetworkAddress> {
        if (networkInfo == null) return emptyList()
        val addresses = LinkedHashSet<DeviceSystemSnapshot.NetworkAddress>()

        val netif2 = networkInfo.optJSONObject("netif2")
        if (netif2 != null) {
            for (interfaceName in netif2.keys()) {
                val layers = netif2.optJSONArray(interfaceName) ?: continue
                for (index in 0 until layers.length()) {
                    val layer = layers.optJSONObject(index) ?: continue
                    val address = layer.text("address") ?: continue
                    if (!isUsableAddress(address)) continue
                    addresses += DeviceSystemSnapshot.NetworkAddress(
                        interfaceName = interfaceName.takeIf { it.isNotBlank() },
                        address = address,
                        family = layer.text("family"),
                    )
                }
            }
        }

        // Older agents and cached network records can only have the flat netif array.
        if (addresses.isEmpty()) {
            for (interfaceInfo in networkInfo.objectValues("netif")) {
                val address = interfaceInfo.text("v4addr") ?: continue
                if (!isUsableAddress(address)) continue
                addresses += DeviceSystemSnapshot.NetworkAddress(
                    interfaceName = interfaceInfo.text("name"),
                    address = address,
                    family = "IPv4",
                )
            }
        }

        return addresses.sortedWith(
            compareBy<DeviceSystemSnapshot.NetworkAddress>(
                { it.interfaceName.orEmpty() },
                { it.family.orEmpty() },
                { it.address },
            ),
        )
    }

    private fun isUsableAddress(address: String): Boolean =
        address.isNotBlank() && address != "0.0.0.0" && address != "::"

    private fun gibibytesToBytes(gibibytes: Long): Long? = try {
        Math.multiplyExact(gibibytes, BYTES_PER_GIB)
    } catch (_: ArithmeticException) {
        null
    }

    private fun JSONObject.text(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.scalarText(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Number -> value.toString().takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun positiveLong(value: Any?): Long? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!number.isFinite() || number <= 0.0) return null
        if (number % 1.0 != 0.0) return null
        if (number > Long.MAX_VALUE.toDouble()) return null
        return number.toLong().takeIf { it > 0L }
    }

    private fun JSONObject.objectValues(key: String): List<JSONObject> {
        val array = optJSONArray(key)
        if (array != null) return array.objects()

        val objectValue = optJSONObject(key) ?: return emptyList()
        return objectValue.keys().asSequence().mapNotNull { objectValue.optJSONObject(it) }.toList()
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
