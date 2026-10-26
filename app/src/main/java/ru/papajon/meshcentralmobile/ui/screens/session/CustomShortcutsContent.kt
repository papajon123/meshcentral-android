@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ru.papajon.meshcentralmobile.ui.screens.session

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
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.input.RemoteInputBridge
import ru.papajon.meshcentralmobile.input.RemoteInputResult
import ru.papajon.meshcentralmobile.model.CustomShortcut
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.components.ParityFormDialog

@Composable
internal fun CustomShortcutsContent(
    shortcuts: List<CustomShortcut>, input: NativeRemoteInputSink?,
    onAdd: (CustomShortcut) -> Boolean, onRemove: (Int) -> Unit, onResult: (RemoteInputResult) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        Text(stringResource(R.string.parity_shortcuts), style = MaterialTheme.typography.titleSmall)
        shortcuts.forEachIndexed { index, shortcut ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
                OutlinedButton(enabled = input != null, onClick = { onResult(RemoteInputBridge.sendCustomShortcut(input, shortcut)) }) {
                    Text("${shortcut.label} · ${shortcut.chord}")
                }
                TextButton(onClick = { onRemove(index) }) { Text(stringResource(R.string.parity_remove_shortcut, shortcut.label)) }
            }
        }
        TextButton(onClick = { editing = true }, enabled = shortcuts.size < CustomShortcut.MAX_SHORTCUTS,
            modifier = Modifier.testTag("parity_shortcut_add")) { Text(stringResource(R.string.parity_add_shortcut)) }
        Text("${shortcuts.size}/${CustomShortcut.MAX_SHORTCUTS}", style = MaterialTheme.typography.bodySmall)
    }
    if (editing) ShortcutEditor(onSave = { if (onAdd(it)) editing = false }, onClose = { editing = false })
}

@Composable
private fun ShortcutEditor(onSave: (CustomShortcut) -> Unit, onClose: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var label by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("R") }
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var alt by rememberSaveable { mutableStateOf(false) }
    var shift by rememberSaveable { mutableStateOf(false) }
    var win by rememberSaveable { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf(false) }
    val shortcut = CustomShortcut(label.trim(), key, ctrl, alt, shift, win)
    ParityFormDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.parity_add_shortcut)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
                Text(stringResource(R.string.parity_suggestions), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs)) {
                    CustomShortcut.suggestions.forEach { suggestion ->
                        SuggestionChip(onClick = {
                            label = suggestion.label; key = suggestion.key
                            ctrl = suggestion.ctrl; alt = suggestion.alt; shift = suggestion.shift; win = suggestion.win
                        }, label = { Text(suggestion.chord) })
                    }
                }
                OutlinedTextField(label, { if (it.length <= CustomShortcut.MAX_LABEL) label = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("parity_shortcut_label"), label = { Text(stringResource(R.string.parity_shortcut_label)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs)) {
                    FilterChip(ctrl, { ctrl = !ctrl }, label = { Text("Ctrl") })
                    FilterChip(alt, { alt = !alt }, label = { Text("Alt") })
                    FilterChip(shift, { shift = !shift }, label = { Text("Shift") })
                    FilterChip(win, { win = !win }, label = { Text("Win") })
                }
                Box {
                    OutlinedButton(onClick = { keyMenu = true }) { Text(stringResource(R.string.parity_shortcut_key, key)) }
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        CustomShortcut.keys.keys.forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { key = value; keyMenu = false })
                        }
                    }
                }
                Text(shortcut.chord)
            }
        },
        confirmButton = { TextButton(enabled = shortcut.valid, onClick = { focus.clearFocus(); keyboard?.hide(); onSave(shortcut) }, modifier = Modifier.testTag("parity_shortcut_save")) { Text(stringResource(R.string.parity_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.manage_cancel)) } },
    )
}
