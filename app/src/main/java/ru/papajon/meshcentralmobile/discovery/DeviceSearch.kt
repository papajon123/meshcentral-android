package ru.papajon.meshcentralmobile.discovery

import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup
import java.text.Normalizer
import java.util.Locale

/**
 * Pure, offline search over the currently loaded [MeshDevice] list.
 *
 * The query is Unicode-normalised and lower-cased with [Locale.ROOT]. Every whitespace-separated
 * token must match at least one searchable field of a device (AND across tokens). Searchable
 * fields are the device name, its mesh name or resolved group name, host/address and OS
 * description. An empty or blank query matches everything.
 */
object DeviceSearch {
    private val whitespace = Regex("\\s+")

    /** Normalises text for case-insensitive, Unicode-stable comparison. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()

    /** Splits a query into normalised, non-empty tokens. */
    fun tokens(query: String): List<String> =
        normalize(query)
            .split(whitespace)
            .filter { it.isNotEmpty() }

    /** True when the query contains at least one searchable token. */
    fun hasQuery(query: String): Boolean = tokens(query).isNotEmpty()

    /**
     * Checks one device. [groupName] is the resolved display name for the device's mesh, when the
     * client has one; [MeshDevice.meshName] is always checked as well.
     */
    fun matches(device: MeshDevice, query: String, groupName: String? = null): Boolean {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return true

        val fields = listOfNotNull(
            device.name,
            device.meshName,
            groupName,
            device.host,
            device.ipAddress,
            device.osDescription,
        )
            .map(::normalize)
            .filter { it.isNotEmpty() }

        return queryTokens.all { token -> fields.any { field -> field.contains(token) } }
    }

    /** Filters [devices] with an optional resolved [groups] list for group-name matching. */
    fun filter(
        devices: List<MeshDevice>,
        query: String,
        groups: List<MeshGroup> = emptyList(),
    ): List<MeshDevice> {
        if (!hasQuery(query)) return devices
        val groupNames = groups.associate { it.id to it.name }
        return devices.filter { device ->
            matches(device, query, groupNames[device.meshId])
        }
    }
}
