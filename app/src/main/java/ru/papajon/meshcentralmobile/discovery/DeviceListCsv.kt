package ru.papajon.meshcentralmobile.discovery

import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup

/**
 * RFC 4180 CSV of the computers currently shown, for spreadsheets and inventories.
 *
 * Only list metadata the app already displays is exported (no credentials, cookies or node ids).
 * Cells that a spreadsheet would evaluate as a formula (leading `=`, `+`, `-`, `@`, tab or CR) are
 * prefixed with an apostrophe to prevent CSV formula injection from device or tag names.
 */
object DeviceListCsv {
    val HEADER = listOf("Name", "Group", "Operating system", "Address", "Status", "Tags")

    fun build(devices: List<MeshDevice>, groups: List<MeshGroup>): String {
        val names = groups.associate { it.id to it.name }
        val rows = devices.map { device ->
            listOf(
                device.name,
                names[device.meshId] ?: device.meshName,
                device.osDescription,
                device.ipAddress,
                if (device.online) "online" else "offline",
                device.tags.joinToString("; "),
            )
        }
        return (listOf(HEADER) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }

    internal fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
