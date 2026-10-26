package ru.papajon.meshcentralmobile.management

/**
 * Immutable, deliberately small projection of the system information that MeshCentral stores for
 * one node.
 *
 * Only values that the inspector may show are copied here. Raw replies, credentials, user lists,
 * serial numbers, BitLocker material and unknown vendor fields never enter the snapshot. A value
 * that the device did not report stays `null` or produces an empty list; it is never turned into a
 * zero or a made-up placeholder.
 */
data class DeviceSystemSnapshot(
    val deviceName: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val architecture: String? = null,
    val kernelRelease: String? = null,
    val cpu: String? = null,
    val model: String? = null,
    val memoryModules: List<MemoryModule> = emptyList(),
    val disks: List<Disk> = emptyList(),
    val networkAddresses: List<NetworkAddress> = emptyList(),
    val hardwareDetails: HardwareDetails = HardwareDetails(),
) {
    /**
     * Sum of the module capacities, but only when every listed module reported one. A partial sum
     * would look like a measured total, so an incomplete list stays unknown instead.
     */
    val memoryTotalBytes: Long?
        get() {
            if (memoryModules.isEmpty()) return null
            var total = 0L
            for (module in memoryModules) {
                val capacity = module.capacityBytes?.takeIf { it > 0L } ?: return null
                if (total > Long.MAX_VALUE - capacity) return null
                total += capacity
            }
            return total
        }

    data class MemoryModule(
        val capacityBytes: Long? = null,
        val speed: String? = null,
    )

    data class Disk(
        val name: String? = null,
        val model: String? = null,
        val capacityBytes: Long? = null,
    )

    data class NetworkAddress(
        val interfaceName: String? = null,
        val address: String,
        val family: String? = null,
    )
}
