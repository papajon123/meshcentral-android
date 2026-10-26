package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.FavoriteCommand
import ru.papajon.meshcentralmobile.ui.components.Parity2FormDialog
import ru.papajon.meshcentralmobile.ui.components.Parity2TextField
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

@Composable
internal fun TerminalFavorites(
    commands: List<FavoriteCommand>, ready: Boolean,
    onSave: (FavoriteCommand) -> Boolean, onRemove: (Int) -> Unit, onInsert: (String) -> Boolean, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Parity2FormDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.parity2_favorites)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
                Text(stringResource(R.string.parity2_favorites_hint))
                commands.forEachIndexed { index, command ->
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { name = command.name; draft = command.command; failed = false }, modifier = Modifier.weight(1f).testTag("parity2_favorite_$index")) { Text(command.name) }
                        TextButton(onClick = { onRemove(index) }, modifier = Modifier.testTag("parity2_favorite_remove_$index")) { Text(stringResource(R.string.settings_pin_remove_action)) }
                    }
                }
                Parity2TextField(name, { name = it.take(60) }, label = stringResource(R.string.manage_label_name), modifier = Modifier.fillMaxWidth().testTag("parity2_favorite_name"))
                Parity2TextField(draft, { draft = it.take(4096); failed = false }, label = stringResource(R.string.parity2_command), modifier = Modifier.fillMaxWidth().testTag("parity2_favorite_draft"))
                TextButton(enabled = FavoriteCommand(name, draft).valid && commands.size < FavoriteCommand.LIMIT,
                    onClick = { failed = !onSave(FavoriteCommand(name, draft)) }, modifier = Modifier.testTag("parity2_favorite_save")) { Text(stringResource(R.string.parity_save)) }
                if (failed) Text(stringResource(R.string.manage_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = ready && FavoriteCommand.safeCommand(draft), onClick = {
                if (FavoriteCommand.safeCommand(draft) && onInsert(draft)) onClose() else failed = true
            }, modifier = Modifier.testTag("parity2_favorite_insert")) { Text(stringResource(R.string.parity2_insert)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } })
}
