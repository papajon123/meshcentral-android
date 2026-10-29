package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.settings.AppLockTimeout
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/** A compact setting; the numeric editor only occupies space while the user changes it. */
@Composable
internal fun SettingsAutoLockRow(currentSeconds: Long, onLockTimeoutChanged: (Long) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable(currentSeconds) { mutableStateOf(currentSeconds.toString()) }
    val spacing = MeshTheme.spacing
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("lock-timeout-row")
            .clickable(role = Role.Button) { input = currentSeconds.toString(); editing = true }
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_auto_lock), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_lock_delay_context), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.settings_auto_lock_seconds, currentSeconds),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (editing) {
        val seconds = AppLockTimeout.parse(input)
        val focus = remember { FocusRequester() }
        val save = {
            if (seconds != null) {
                onLockTimeoutChanged(seconds)
                editing = false
            }
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.settings_auto_lock)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(20) },
                    modifier = Modifier.fillMaxWidth().testTag("lock-timeout-input").focusRequester(focus),
                    label = { Text(stringResource(R.string.settings_lock_delay_input)) },
                    supportingText = { Text(stringResource(R.string.settings_lock_delay_hint)) },
                    isError = input.isNotEmpty() && seconds == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                // Dialog content is a separate composition; request focus only after its field exists.
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = save, enabled = seconds != null, modifier = Modifier.testTag("lock-timeout-save")) {
                    Text(stringResource(R.string.parity_save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
