package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.*
import java.text.NumberFormat

@Composable
fun PhoneBatterySummary(device: MeshDevice, current: Boolean = true) {
    val battery = device.battery.takeIf { current && device.connectivityKnown && device.conn and 1 != 0 }
    val level = battery?.level?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it) }
    val value = level?.let { stringResource(R.string.phone_battery_level, it) }
        ?: stringResource(R.string.phone_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.phone_battery, value), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(when (battery?.charging) {
            true -> R.string.phone_charging
            false -> R.string.phone_on_battery
            null -> R.string.phone_power_unknown
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Production section, also rendered in synthetic Compose tests. No network or saved form state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneSection(device: MeshDevice, current: Boolean, busy: Boolean, onCommand: (PhoneRequest) -> Unit) {
    if (!device.isAndroidAgent) return
    val allowed = PhoneCommands.canSend(device, current)
    var selected by remember(device.nodeId) { mutableStateOf<PhoneAction?>(null) }
    Column(Modifier.fillMaxWidth().testTag("phone_section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Smartphone, null)
            Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.titleMedium)
        }
        PhoneBatterySummary(device, current)
        ru.papajon.meshcentralmobile.ui.screens.session.PhoneViewOnlyNotice(device)
        if (device.phoneInputAllowed && current) Text(stringResource(R.string.phone_control_consent_hint),
            style = MaterialTheme.typography.bodySmall)
        if (allowed) {
            Text(stringResource(R.string.phone_find), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneAction.entries.forEach { action ->
                    OutlinedButton(onClick = { selected = action }, enabled = !busy && PhoneCommands.canSend(device, current, action),
                        modifier = Modifier.testTag("phone_action_${action.name}")) {
                        Text(stringResource(action.labelRes()))
                    }
                }
            }
            if (!PhoneCommands.canSend(device, current, PhoneAction.Toast)) {
                Text(stringResource(R.string.phone_console_required), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(stringResource(R.string.phone_actions_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
    // Closing the dialog on revoked access also clears its non-persisted contents.
    val selectionAllowed = selected?.let { PhoneCommands.canSend(device, current, it) } ?: allowed
    if (!selectionAllowed) LaunchedEffect(selectionAllowed) { selected = null }
    selected?.takeIf { selectionAllowed }?.let { action ->
        key(device.nodeId, action) {
            PhoneCommandDialog(device.name, action, busy, onDismiss = { selected = null }) { request ->
                if (PhoneCommands.canSend(device, current, request.action) && !busy) {
                    onCommand(request)
                    selected = null
                }
            }
        }
    }
}

@Composable
private fun PhoneCommandDialog(deviceName: String, action: PhoneAction, busy: Boolean,
    onDismiss: () -> Unit, onSend: (PhoneRequest) -> Unit) {
    val hasText = action !in setOf(PhoneAction.Vibrate, PhoneAction.Flash)
    var text by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("MeshCentral") }
    var reviewing by remember { mutableStateOf(!hasText) }
    val request = PhoneRequest(action, text, title)
    val valid = runCatching { PhoneCommands.message("node//validation", request) }.isSuccess
    val limit = when (action) {
        PhoneAction.OpenUrl -> PhoneCommands.MAX_URL
        PhoneAction.Dial -> PhoneCommands.MAX_NUMBER
        else -> PhoneCommands.MAX_TEXT
    }
    AlertDialog(
        modifier = Modifier.testTag("phone_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.labelRes())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(deviceName, style = MaterialTheme.typography.titleSmall)
                if (reviewing) {
                    Text(stringResource(R.string.phone_confirm))
                    if (action == PhoneAction.Alert) Text(PhoneCommands.displayText(title))
                    if (hasText) Text(if (action == PhoneAction.OpenUrl || action == PhoneAction.Dial) text else PhoneCommands.displayText(text),
                        modifier = Modifier.testTag("phone_preview"))
                } else {
                    if (action == PhoneAction.Alert) OutlinedTextField(
                        value = title, onValueChange = { if (it.length <= PhoneCommands.MAX_TITLE) title = it },
                        label = { Text(stringResource(R.string.phone_message_title)) }, singleLine = true,
                        modifier = Modifier.testTag("phone_message_title"),
                    )
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= limit) text = it },
                        label = { Text(stringResource(when (action) {
                            PhoneAction.OpenUrl -> R.string.phone_url
                            PhoneAction.Dial -> R.string.phone_number
                            else -> R.string.phone_message_text
                        })) },
                        singleLine = action == PhoneAction.Dial || action == PhoneAction.OpenUrl,
                        keyboardOptions = KeyboardOptions(keyboardType = when (action) {
                            PhoneAction.Dial -> KeyboardType.Phone
                            PhoneAction.OpenUrl -> KeyboardType.Uri
                            else -> KeyboardType.Text
                        }),
                        supportingText = { Text("${text.length}/$limit") },
                        isError = text.isNotEmpty() && !valid,
                        modifier = Modifier.testTag("phone_message_text"),
                    )
                    Text(stringResource(when (action) {
                        PhoneAction.OpenUrl -> R.string.phone_url_format
                        PhoneAction.Dial -> R.string.phone_dial_hint
                        else -> R.string.phone_message_hint
                    }), style = MaterialTheme.typography.bodySmall)
                }
                if ((action == PhoneAction.Alert || action == PhoneAction.Toast) && ('"' in text || '"' in title)) {
                    Text(stringResource(R.string.phone_quotes_hint), color = MaterialTheme.colorScheme.tertiary)
                }
                if (reviewing && action == PhoneAction.Dial) Text(stringResource(R.string.phone_dial_hint))
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { if (reviewing) onSend(request) else reviewing = true },
                modifier = Modifier.testTag(if (reviewing) "phone_confirm" else "phone_review")) {
                Text(stringResource(if (reviewing) R.string.phone_send else R.string.phone_review))
            }
        },
        dismissButton = { TextButton(onClick = { if (reviewing && hasText) reviewing = false else onDismiss() }) {
            Text(stringResource(if (reviewing && hasText) R.string.phone_edit else R.string.phone_cancel))
        } },
    )
}

private fun PhoneAction.labelRes(): Int = when (this) {
    PhoneAction.Vibrate -> R.string.phone_vibrate
    PhoneAction.Flash -> R.string.phone_flash
    PhoneAction.Alert -> R.string.phone_alert
    PhoneAction.Toast -> R.string.phone_toast
    PhoneAction.OpenUrl -> R.string.phone_open_url
    PhoneAction.Dial -> R.string.phone_dial
}
