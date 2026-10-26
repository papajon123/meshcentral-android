package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.*
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.Parity2FormDialog
import ru.papajon.meshcentralmobile.ui.components.ParityFormDialog
import ru.papajon.meshcentralmobile.ui.components.Parity2TextField
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun RemovalActions(name: String, actions: Set<DeviceRemovalAction>, busy: Boolean, onRemove: (DeviceRemovalAction, String) -> Unit) {
    var pending by remember(name) { mutableStateOf<DeviceRemovalAction?>(null) }
    var reviewed by remember(pending) { mutableStateOf(false) }
    var typed by remember(pending) { mutableStateOf("") }
    Column {
        actions.forEach { action ->
            TextButton(onClick = { pending = action }, enabled = !busy, modifier = Modifier.testTag("parity2_${action.wire}")) {
                Text(stringResource(action.label()), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    pending?.let { action ->
        Parity2FormDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.manage_confirm_power_title, stringResource(action.label()), name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
                    if (!reviewed) Text(stringResource(if (action == DeviceRemovalAction.UninstallAgent) R.string.parity2_uninstall_warning else R.string.parity2_remove_warning))
                    if (reviewed) Parity2TextField(typed, { typed = it },
                        label = stringResource(R.string.parity2_confirm_name),
                        placeholder = name,
                        modifier = Modifier.fillMaxWidth().testTag("parity2_confirm_name"))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && action in actions && (!reviewed || (name.isNotBlank() && typed == name)),
                    onClick = { if (!reviewed) reviewed = true else { pending = null; onRemove(action, typed) } },
                    modifier = Modifier.testTag("parity2_remove_confirm")) {
                    Text(stringResource(if (reviewed) action.label() else R.string.parity_review))
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun DeviceRemovalAction.label() = if (this == DeviceRemovalAction.UninstallAgent) R.string.parity2_uninstall else R.string.parity2_remove

@Composable
internal fun HardwareDetailsContent(details: HardwareDetails, online: Boolean) {
    val unknown = stringResource(R.string.parity_unknown)
    fun value(text: String?) = text ?: unknown
    @Composable fun flag(flag: Boolean?) = stringResource(when (flag) { true -> R.string.parity_yes; false -> R.string.parity_no; null -> R.string.parity_unknown })
    @Composable fun line(label: Int, text: String?) { Text("${stringResource(label)}: ${value(text)}") }
    MeshCard(Modifier.fillMaxWidth().testTag("parity2_hardware")) {
        Column(Modifier.padding(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
            Text(stringResource(R.string.parity2_bios), style = MaterialTheme.typography.titleMedium)
            line(R.string.parity2_vendor, details.biosVendor)
            line(R.string.manage_label_version, details.biosVersion)
            line(R.string.parity2_bios_date, BiosDate.format(details.biosDate,
                androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))
            line(R.string.parity2_mode, details.biosMode)
            HorizontalDivider()
            Text(stringResource(R.string.parity2_board), style = MaterialTheme.typography.titleMedium)
            line(R.string.manage_label_name, details.boardName)
            line(R.string.parity2_vendor, details.boardVendor)
            line(R.string.manage_label_version, details.boardVersion)
            HorizontalDivider()
            Text(stringResource(R.string.parity2_tpm), style = MaterialTheme.typography.titleMedium)
            line(R.string.parity2_spec, details.tpmSpec)
            line(R.string.parity2_vendor, details.tpmManufacturer)
            line(R.string.manage_label_version, details.tpmVersion)
            line(R.string.parity_enabled, flag(details.tpmEnabled))
            line(R.string.parity2_activated, flag(details.tpmActivated))
            line(R.string.parity2_owned, flag(details.tpmOwned))
            HorizontalDivider()
            line(R.string.parity2_last_boot, details.lastBootMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) })
            val now = System.currentTimeMillis()
            val uptime = details.lastBootMillis?.takeIf { online && it <= now }?.let {
                val seconds = (now - it) / 1000
                String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            }
            line(R.string.parity2_uptime, uptime)
            Text(stringResource(R.string.parity_security_cached), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun NativeMetricsContent(sample: MetricsSample?, error: Boolean, enabled: Boolean, onLoad: () -> Unit, linux: Boolean = false) {
    MeshCard(Modifier.fillMaxWidth().testTag("parity2_metrics")) {
        Column(Modifier.padding(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
            Text(stringResource(R.string.parity2_metrics), style = MaterialTheme.typography.titleMedium)
            val unknown = stringResource(R.string.parity_unknown)
            fun percent(number: Double?) = number?.let { NumberFormat.getPercentInstance().format(it / 100.0) } ?: unknown
            Text("${stringResource(R.string.manage_cpu)}: ${percent(sample?.cpuPercent)}")
            if (linux) Text(stringResource(R.string.parity_fixes_linux_cpu_average), style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.manage_memory)}: ${percent(sample?.memoryPercent)}")
            sample?.let { Text(stringResource(R.string.parity2_sample_time, DateFormat.getDateTimeInstance().format(Date(it.receivedAtMillis))), style = MaterialTheme.typography.bodySmall) }
            if (error) Text(stringResource(R.string.manage_not_reported))
            TextButton(onClick = onLoad, enabled = enabled, modifier = Modifier.testTag("parity2_metrics_load")) { Text(stringResource(R.string.manage_refresh)) }
        }
    }
}

@Composable
internal fun SoftwareContent(software: List<InstalledProgram>?, error: Boolean, loading: Boolean, allowed: Boolean, onLoad: () -> Unit,
    status: (@Composable () -> Unit)?, modifier: Modifier) {
    LazyColumn(modifier.testTag("parity2_software_list"), contentPadding = PaddingValues(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        if (status != null) item { status() }
        item { Text(stringResource(R.string.parity2_software_hint), style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = onLoad, enabled = allowed && !loading, modifier = Modifier.testTag("parity2_software_load")) { Text(stringResource(R.string.manage_refresh)) } }
        if (error) item { Text(stringResource(R.string.parity2_software_error), color = MaterialTheme.colorScheme.error) }
        else if (software == null) item { Text(stringResource(if (loading) R.string.manage_loading else R.string.parity_unknown)) }
        else if (software.isEmpty()) item { Text(stringResource(R.string.parity_history_empty)) }
        items(software.orEmpty()) { item ->
            Column {
                Text(item.name ?: stringResource(R.string.parity_unknown), style = MaterialTheme.typography.titleSmall)
                Text("${stringResource(R.string.manage_label_version)}: ${item.version ?: stringResource(R.string.parity_unknown)}")
                Text("${stringResource(R.string.parity2_vendor)}: ${item.publisher ?: stringResource(R.string.parity_unknown)}")
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun RunCommandContent(nodeId: String, allowed: Boolean, busy: Boolean, output: String?, uncertain: Boolean,
    onRun: (String, Boolean) -> Unit, onOpenTerminal: () -> Unit, modifier: Modifier,
    outputTruncated: Boolean = false, status: (@Composable () -> Unit)? = null, windows: Boolean = false) {
    var draft by remember(nodeId) { mutableStateOf("") }
    var powershellUtf8 by remember(nodeId, windows) { mutableStateOf(false) }
    var reviewed by remember(nodeId, windows) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val utf8Label = stringResource(R.string.windows_polish_powershell_utf8)
    LazyColumn(modifier.imePadding().testTag("parity2_run_screen"), contentPadding = PaddingValues(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
        if (status != null) item { status() }
        item { Text(stringResource(R.string.parity2_run_warning)) }
        item { Text(stringResource(R.string.parity_fixes_run_warning)) }
        if (windows) item {
            Column {
                Text(stringResource(R.string.windows_polish_encoding_hint))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = powershellUtf8, onCheckedChange = { powershellUtf8 = it },
                        enabled = !busy, modifier = Modifier.testTag("windows_polish_powershell_utf8")
                            .semantics { contentDescription = utf8Label })
                    Text(utf8Label)
                }
            }
        }
        item { OutlinedTextField(draft, { draft = it.take(4096) }, modifier = Modifier.fillMaxWidth().testTag("parity2_command"),
            label = { Text(stringResource(R.string.parity2_command)) }, minLines = 2, maxLines = 6, enabled = !busy) }
        item { TextButton(onClick = {
            focus.clearFocus(); keyboard?.hide(); reviewed = draft to (windows && powershellUtf8)
        }, enabled = allowed && !busy && RunCommand.valid(draft), modifier = Modifier.testTag("parity2_run_review")) { Text(stringResource(R.string.parity_review)) } }
        if (busy) item { Text(stringResource(R.string.manage_loading)) }
        if (uncertain) item { Text(stringResource(R.string.parity2_run_uncertain), color = MaterialTheme.colorScheme.error) }
        if (outputTruncated) item { Text(stringResource(R.string.parity_fixes_output_truncated)) }
        if (output != null) {
            item { Text(stringResource(R.string.parity2_output), style = MaterialTheme.typography.titleMedium) }
            item { Text(stringResource(R.string.parity2_output_note), style = MaterialTheme.typography.bodySmall) }
            item { SelectionContainer { Text(output.ifEmpty { stringResource(R.string.parity_history_empty) }, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("parity2_output")) } }
        }
        item { TextButton(onClick = onOpenTerminal, enabled = !busy) { Text(stringResource(R.string.terminal_title)) } }
    }
    reviewed?.let { (command, utf8) ->
        ParityFormDialog(onDismissRequest = { reviewed = null }, title = { Text(stringResource(R.string.parity2_run)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
                Text(stringResource(R.string.parity2_run_warning))
                Text(stringResource(R.string.parity_fixes_run_warning))
                if (windows) Text(stringResource(if (utf8) R.string.windows_polish_powershell_utf8 else R.string.windows_polish_default_shell))
                Text(command, fontFamily = FontFamily.Monospace)
            } },
            confirmButton = { TextButton(enabled = allowed && !busy, onClick = { reviewed = null; onRun(command, utf8) }, modifier = Modifier.testTag("parity2_run_confirm")) { Text(stringResource(R.string.parity2_run)) } },
            dismissButton = { TextButton(onClick = { reviewed = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
