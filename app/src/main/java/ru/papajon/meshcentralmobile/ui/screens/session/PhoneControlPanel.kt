package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.PhoneNavigationKey
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState

/** No saved text: leaving the session or revoking input discards the local draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneControlPanel(state: RemoteDesktopState, input: NativeRemoteInputSink, modifier: Modifier = Modifier) {
    if (state.device?.isAndroidAgent != true || !state.inputEnabled) return
    var textDialog by remember(state.device.nodeId, state.attempt) { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    Surface(modifier.fillMaxWidth().testTag("phone_control_panel"), color = MaterialTheme.colorScheme.surface) {
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneNavigationKey.entries.forEach { action ->
                TextButton(onClick = {
                    if (currentState.inputEnabled && input.sendRemoteKey(action.code, true)) {
                        input.sendRemoteKey(action.code, false)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("phone_control_${action.name}")) {
                    Text(stringResource(action.labelRes()))
                }
            }
            TextButton(onClick = { textDialog = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("phone_control_text")) {
                Text(stringResource(R.string.phone_control_text))
            }
        }
    }
    if (textDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text(stringResource(R.string.phone_control_text)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.phone_control_text_hint))
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= 2048) text = it },
                        label = { Text(stringResource(R.string.phone_control_text)) },
                        modifier = Modifier.testTag("phone_control_text_field"))
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotEmpty(), onClick = {
                    if (currentState.inputEnabled) {
                        input.sendUnicodeText(text)
                        textDialog = false
                    }
                }, modifier = Modifier.testTag("phone_control_send")) { Text(stringResource(R.string.phone_send)) }
            },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text(stringResource(R.string.phone_cancel)) } },
        )
    }
}

private fun PhoneNavigationKey.labelRes() = when (this) {
    PhoneNavigationKey.Back -> R.string.phone_control_back
    PhoneNavigationKey.Home -> R.string.phone_control_home
    PhoneNavigationKey.Recents -> R.string.phone_control_recents
    PhoneNavigationKey.Notifications -> R.string.phone_control_notifications
    PhoneNavigationKey.QuickSettings -> R.string.phone_control_quick_settings
}
