package ru.papajon.meshcentralmobile.management

import android.content.Context
import android.text.format.Formatter
import ru.papajon.meshcentralmobile.R
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Single, privacy-preserving text projection of [DeviceSystemSnapshot] shared by copy and save.
 *
 * Only the fields that the Details tab shows enter the report. Raw replies, node identifiers and
 * credentials are deliberately absent. All labels and section titles come from the existing
 * management resources; byte sizes use the platform [Formatter] so they stay localized.
 */
object DeviceInfoReport {
    const val FILE_NAME = "device-info.txt"
    const val MIME_TYPE = "text/plain"

    fun format(context: Context, snapshot: DeviceSystemSnapshot): String {
        val output = StringBuilder()

        fun section(title: String) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(title)
        }

        fun text(value: String) {
            output.append('\n').append(value)
        }

        fun field(label: String, value: String?) {
            if (!value.isNullOrBlank()) text("$label: $value")
        }

        output.append(context.getString(R.string.manage_details_subtitle))

        val system = listOf(
            R.string.manage_label_name to snapshot.deviceName,
            R.string.manage_label_os to snapshot.osName,
            R.string.manage_label_version to snapshot.osVersion,
            R.string.manage_label_architecture to snapshot.architecture,
            R.string.manage_label_kernel to snapshot.kernelRelease,
        )
        if (system.any { (_, value) -> !value.isNullOrBlank() }) {
            section(context.getString(R.string.manage_section_system))
            system.forEach { (label, value) -> field(context.getString(label), value) }
        }

        val hardware = listOf(
            R.string.manage_label_cpu to snapshot.cpu,
            R.string.manage_label_model to snapshot.model,
        )
        if (hardware.any { (_, value) -> !value.isNullOrBlank() }) {
            section(context.getString(R.string.manage_section_hardware))
            hardware.forEach { (label, value) -> field(context.getString(label), value) }
        }

        section(context.getString(R.string.manage_section_memory))
        snapshot.memoryTotalBytes?.let {
            text(context.getString(R.string.manage_memory_total, formatBytes(context, it)))
        }
        if (snapshot.memoryModules.isEmpty()) {
            text(context.getString(R.string.manage_memory_empty))
        } else {
            snapshot.memoryModules.forEachIndexed { index, module ->
                if (index > 0) output.append('\n')
                text(context.getString(R.string.manage_memory_module, index + 1))
                field(
                    context.getString(R.string.manage_memory_capacity),
                    formatBytes(context, module.capacityBytes),
                )
                field(context.getString(R.string.manage_memory_speed), module.speed)
            }
        }

        section(context.getString(R.string.manage_section_disks))
        if (snapshot.disks.isEmpty()) {
            text(context.getString(R.string.manage_disks_empty))
        } else {
            snapshot.disks.forEachIndexed { index, disk ->
                if (index > 0) output.append('\n')
                text(
                    disk.name?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.manage_disk_fallback, index + 1),
                )
                disk.model?.takeIf { it.isNotBlank() && it != disk.name }?.let {
                    field(context.getString(R.string.manage_label_model), it)
                }
                field(
                    context.getString(R.string.manage_memory_capacity),
                    formatBytes(context, disk.capacityBytes),
                )
            }
        }

        val details = snapshot.hardwareDetails
        fun hardwareSection(title: Int, values: List<Pair<Int, String?>>) {
            if (values.any { !it.second.isNullOrBlank() }) {
                section(context.getString(title))
                values.forEach { (label, value) -> field(context.getString(label), value) }
            }
        }
        fun flag(value: Boolean?): String? = value?.let {
            context.getString(if (it) R.string.parity_yes else R.string.parity_no)
        }
        hardwareSection(R.string.parity2_bios, listOf(
            R.string.parity2_vendor to details.biosVendor,
            R.string.manage_label_version to details.biosVersion,
            R.string.parity2_bios_date to BiosDate.format(details.biosDate, context.resources.configuration.locales[0]),
            R.string.parity2_mode to details.biosMode,
        ))
        hardwareSection(R.string.parity2_board, listOf(
            R.string.manage_label_name to details.boardName,
            R.string.parity2_vendor to details.boardVendor,
            R.string.manage_label_version to details.boardVersion,
        ))
        hardwareSection(R.string.parity2_tpm, listOf(
            R.string.parity2_spec to details.tpmSpec,
            R.string.parity2_vendor to details.tpmManufacturer,
            R.string.manage_label_version to details.tpmVersion,
            R.string.parity_enabled to flag(details.tpmEnabled),
            R.string.parity2_activated to flag(details.tpmActivated),
            R.string.parity2_owned to flag(details.tpmOwned),
        ))
        details.lastBootMillis?.let {
            field(context.getString(R.string.parity2_last_boot), java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT,
                context.resources.configuration.locales[0]).format(java.util.Date(it)))
        }

        section(context.getString(R.string.manage_section_network))
        if (snapshot.networkAddresses.isEmpty()) {
            text(context.getString(R.string.manage_network_empty))
        } else {
            snapshot.networkAddresses.forEachIndexed { index, address ->
                if (index > 0) output.append('\n')
                val label = listOfNotNull(
                    address.interfaceName?.takeIf { it.isNotBlank() },
                    address.family?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank {
                    context.getString(R.string.manage_label_interface)
                }
                text(label)
                if (address.address.isNotBlank()) text(address.address)
            }
        }

        output.append('\n')
        return output.toString()
    }

    private fun formatBytes(context: Context, bytes: Long?): String {
        val known = bytes?.takeIf { it > 0L }
        return if (known != null) {
            Formatter.formatFileSize(context, known)
        } else {
            context.getString(R.string.manage_not_reported)
        }
    }
}

/**
 * Writes the report to any stream supplied by the caller.
 *
 * The function returns `true` only after [OutputStream.use] has closed the stream successfully.
 * [IOException] and [SecurityException] become `false` so the caller can show one localized
 * message without exposing an exception or a document URI. [kotlinx.coroutines.CancellationException]
 * is intentionally not caught: cancellation must keep propagating.
 */
object DeviceInfoWriter {
    fun write(openStream: () -> OutputStream?, text: String): Boolean {
        return try {
            val stream = openStream() ?: return false
            stream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
