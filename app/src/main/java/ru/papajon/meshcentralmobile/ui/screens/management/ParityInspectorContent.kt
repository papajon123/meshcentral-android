package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.ToastCommand
import ru.papajon.meshcentralmobile.management.ToastReview
import ru.papajon.meshcentralmobile.mesh.SecurityStatus
import ru.papajon.meshcentralmobile.mesh.WindowsSecurity
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.ParityFormDialog
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

@Composable
internal fun WindowsSecuritySection(security: WindowsSecurity) {
    MeshCard(Modifier.fillMaxWidth().testTag("parity_security")) {
        Column(Modifier.padding(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
            Text(stringResource(R.string.parity_security), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.parity_security_cached), style = MaterialTheme.typography.bodySmall)
            SecurityLine(stringResource(R.string.parity_antivirus), security.center.antiVirus)
            SecurityLine(stringResource(R.string.parity_updates), security.center.autoUpdate)
            SecurityLine(stringResource(R.string.parity_firewall), security.center.firewall)
            security.products.forEach { product ->
                HorizontalDivider()
                Text(product.product ?: stringResource(R.string.parity_unknown), style = MaterialTheme.typography.titleSmall)
                SecurityBooleanLine(stringResource(R.string.parity_enabled), product.enabled)
                SecurityBooleanLine(stringResource(R.string.parity_updated), product.updated)
            }
        }
    }
}

@Composable
private fun SecurityBooleanLine(label: String, value: Boolean?) {
    val text = stringResource(when (value) {
        true -> R.string.parity_yes
        false -> R.string.parity_no
        null -> R.string.parity_unknown
    })
    Text("$label: $text", style = MaterialTheme.typography.bodyMedium,
        color = if (value == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SecurityLine(label: String, status: SecurityStatus) {
    val text = stringResource(when (status) {
        SecurityStatus.Ok -> R.string.parity_ok
        SecurityStatus.Warning -> R.string.parity_fixes_warning
        SecurityStatus.Problem -> R.string.parity_needs_attention
        SecurityStatus.Unknown -> R.string.parity_unknown
    })
    Text("$label: $text", style = MaterialTheme.typography.bodyMedium,
        color = when (status) {
            SecurityStatus.Problem -> MaterialTheme.colorScheme.error
            SecurityStatus.Warning -> MeshTheme.colors.warning
            else -> MaterialTheme.colorScheme.onSurface
        })
}

@Composable
internal fun ToastComposer(target: String, enabled: Boolean, onSend: (String, String) -> Unit, onClose: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<ToastReview?>(null) }
    val confirming = review != null
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(review) {
        armed = false
        if (review != null) { kotlinx.coroutines.delay(ToastReview.ARM_DELAY_MILLIS); armed = true }
    }
    ParityFormDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (confirming) R.string.parity_message_confirm else R.string.parity_message)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
                Text(target, style = MaterialTheme.typography.titleSmall)
                if (confirming) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(message)
                } else {
                    OutlinedTextField(title, { if (it.length <= ToastCommand.MAX_TITLE) title = it },
                        modifier = Modifier.fillMaxWidth().testTag("parity_toast_title"),
                        label = { Text(stringResource(R.string.parity_message_title)) },
                        supportingText = { Text("${title.length}/${ToastCommand.MAX_TITLE}") }, singleLine = true)
                    OutlinedTextField(message, { if (it.length <= ToastCommand.MAX_MESSAGE) message = it },
                        modifier = Modifier.fillMaxWidth().testTag("parity_toast_body"),
                        label = { Text(stringResource(R.string.parity_message)) }, minLines = 3,
                        supportingText = { Text("${message.length}/${ToastCommand.MAX_MESSAGE}") })
                }
            }
        },
        confirmButton = {
            if (confirming) TextButton(enabled = enabled && armed,
                modifier = Modifier.testTag("parity_toast_confirm"), onClick = {
                    if (review?.send(android.os.SystemClock.elapsedRealtime(), onSend) == true) onClose()
                }) { Text(stringResource(R.string.session_text_send)) }
            else TextButton(onClick = onClose) { Text(stringResource(R.string.manage_cancel)) }
        },
        dismissButton = {
            if (confirming) TextButton(onClick = { review = null }) { Text(stringResource(R.string.manage_cancel)) }
            else TextButton(enabled = enabled && ToastCommand.valid(title, message),
                modifier = Modifier.testTag("parity_toast_review"), onClick = {
                    focus.clearFocus(); keyboard?.hide()
                    review = ToastReview(title, message, android.os.SystemClock.elapsedRealtime())
                }) { Text(stringResource(R.string.parity_review)) }
        },
    )
}
