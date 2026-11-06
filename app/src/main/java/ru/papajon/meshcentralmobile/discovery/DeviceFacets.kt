package ru.papajon.meshcentralmobile.discovery

import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup
import java.util.Locale

/** Operating-system families a computer list can be narrowed to. */
enum class OsFamily {
    Windows, Linux, MacOs, Android, Other;

    companion object {
        fun of(device: MeshDevice): OsFamily = when {
            device.isAndroidAgent -> Android
            device.isWindows -> Windows
            device.isLinux -> Linux
            device.osDescription.contains("mac", ignoreCase = true) ||
                device.osDescription.contains("os x", ignoreCase = true) ||
                device.osDescription.contains("darwin", ignoreCase = true) -> MacOs
            else -> Other
        }
    }
}

/**
 * Offline group / operating-system / tag narrowing of the loaded computer list. Every set facet must
 * match (AND); an unset facet matches everything. Tags compare case-insensitively, as MeshCentral
 * stores them as typed by administrators.
 */
data class DeviceFacets(
    val groupId: String? = null,
    val os: OsFamily? = null,
    val tag: String? = null,
) {
    val active: Boolean get() = groupId != null || os != null || tag != null

    fun matches(device: MeshDevice): Boolean =
        (groupId == null || device.meshId == groupId) &&
            (os == null || OsFamily.of(device) == os) &&
            (tag == null || device.tags.any { it.equals(tag, ignoreCase = true) })

    fun filter(devices: List<MeshDevice>): List<MeshDevice> = if (!active) devices else devices.filter(::matches)

    data class Options(
        val groups: List<Pair<String, String>>,
        val systems: List<OsFamily>,
        val tags: List<String>,
    ) {
        val isEmpty: Boolean get() = groups.size < 2 && systems.size < 2 && tags.isEmpty()
    }

    companion object {
        /** Facet values present in [devices]; group names resolve through [groups] when known. */
        fun options(devices: List<MeshDevice>, groups: List<MeshGroup>): Options {
            val names = groups.associate { it.id to it.name }
            val groupOptions = devices.map { it.meshId }.distinct()
                .map { id -> id to (names[id] ?: devices.first { it.meshId == id }.meshName.ifBlank { id }) }
                .sortedBy { it.second.lowercase(Locale.ROOT) }
            val systems = devices.map(OsFamily::of).distinct().sortedBy { it.ordinal }
            val tags = devices.flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
            return Options(groupOptions, systems, tags)
        }
    }
}
