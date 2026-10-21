package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.files.FileChange
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.files.RemoteFile
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/** SAF grants are temporary; no storage-wide permission, persisted URI, or saved transfer is used. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(controller: FilesController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    var selected by remember(controller) { mutableStateOf<RemoteFile?>(null) }
    var leaving by remember(controller) { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        controller.downloadChosen(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.proposeUpload(uri)
    }
    val requestBack: () -> Unit = { if (state.busy) leaving = true else onBack() }
    BackHandler(onBack = requestBack)
    LaunchedEffect(controller) { controller.connect() }
    val enabled = state.connected && !state.busy && state.confirmation == null
    val spacing = MeshTheme.spacing

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item {
            Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TextButton(onClick = requestBack) { Text(stringResource(R.string.files_back)) }
                Text(stringResource(R.string.files_title), style = MaterialTheme.typography.headlineSmall)
                Text(state.path.ifEmpty { stringResource(R.string.files_root) }, style = MaterialTheme.typography.bodyMedium)
                if (!state.connected && !state.busy && !state.authRequired) Text(stringResource(R.string.files_reopen))
                if (state.authRequired) {
                    SftpAuthentication(controller, state.askKeyPass)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextButton(onClick = controller::parent, enabled = enabled && state.path.isNotEmpty() && state.path != "/") {
                        Text(stringResource(R.string.files_parent))
                    }
                    TextButton(onClick = controller::refresh, enabled = enabled) { Text(stringResource(R.string.files_refresh)) }
                    TextButton(onClick = { controller.propose(FileChange.CreateDirectory) }, enabled = enabled) {
                        Text(stringResource(R.string.files_mkdir))
                    }
                    TextButton(onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = enabled) {
                        Text(stringResource(R.string.files_upload))
                    }
                }
                state.message?.let {
                    Text(it.asString(), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.transferName?.let { Text(it) }
                    state.transferred?.let { Text(stringResource(R.string.files_progress, it)) }
                    TextButton(onClick = controller::cancel) { Text(stringResource(R.string.files_cancel_operation)) }
                }
                if (state.entries.isEmpty() && state.connected && !state.busy) Text(stringResource(R.string.files_empty))
            }
        }
        items(state.entries, key = { it.name }) { entry ->
            ListItem(
                headlineContent = { Text(entry.displayName) },
                supportingContent = {
                    Text(if (!entry.actionable) stringResource(R.string.files_name_unavailable)
                        else if (entry.directory) stringResource(R.string.files_folder) else stringResource(R.string.files_size, entry.size))
                },
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { selected = entry },
            )
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.displayName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!entry.actionable) Text(stringResource(R.string.files_name_unavailable))
                    if (entry.actionable && entry.directory) {
                        TextButton(onClick = { selected = null; controller.open(entry) }, enabled = enabled) {
                            Text(stringResource(R.string.files_open))
                        }
                    } else if (entry.actionable) {
                        TextButton(onClick = {
                            selected = null
                            if (controller.prepareDownload(entry)) createDocument.launch(entry.name + ".partial")
                        }, enabled = enabled) { Text(stringResource(R.string.files_download)) }
                        Text(stringResource(R.string.files_download_notice), style = MaterialTheme.typography.bodySmall)
                    }
                    if (entry.actionable && entry.type != 1) {
                        TextButton(onClick = { selected = null; controller.propose(FileChange.Rename, entry) }, enabled = enabled) {
                            Text(stringResource(R.string.files_rename))
                        }
                        TextButton(onClick = { selected = null; controller.propose(FileChange.Delete, entry) }, enabled = enabled) {
                            Text(stringResource(R.string.files_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.files_close)) } },
        )
    }

    state.confirmation?.let { request ->
        var name by remember(request) { mutableStateOf(request.name) }
        val title = when (request.change) {
            FileChange.CreateDirectory -> R.string.files_mkdir
            FileChange.Rename -> R.string.files_rename
            FileChange.Delete -> R.string.files_delete
            FileChange.Upload -> R.string.files_upload
        }
        AlertDialog(
            onDismissRequest = controller::dismissConfirmation,
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Text(stringResource(R.string.files_destination, request.path.ifEmpty { stringResource(R.string.files_root) }))
                    if (request.change == FileChange.Delete) {
                        val warning = if (request.directory && state.sftp) R.string.files_sftp_delete_empty
                            else if (request.directory) R.string.files_confirm_delete_tree else R.string.files_confirm_delete
                        Text(stringResource(warning, request.name))
                    } else {
                        if (request.change == FileChange.Rename) {
                            Text(stringResource(R.string.files_original_name, request.name))
                            Text(stringResource(R.string.files_rename_warning))
                        }
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text(stringResource(R.string.files_name)) }, modifier = Modifier.fillMaxWidth())
                        if (request.change == FileChange.Upload) Text(stringResource(R.string.files_upload_warning))
                    }
                    state.message?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { controller.confirm(name) }, enabled = !state.busy) {
                    Text(stringResource(if (request.change == FileChange.Upload) R.string.files_upload_confirm else R.string.files_confirm))
                }
            },
            dismissButton = { TextButton(onClick = controller::dismissConfirmation) { Text(stringResource(R.string.files_cancel)) } },
        )
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text(stringResource(R.string.files_leave_title)) },
        text = { Text(stringResource(R.string.files_leave_warning)) },
        confirmButton = { TextButton(onClick = { leaving = false; controller.close(); onBack() }) { Text(stringResource(R.string.files_leave)) } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.files_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SftpAuthentication(controller: FilesController, savedKeyAvailable: Boolean) {
    // Secrets are intentionally not rememberSaveable and are cleared immediately on submit.
    var method by remember(controller, savedKeyAvailable) { mutableStateOf(if (savedKeyAvailable) 2 else 0) }
    var username by remember(controller) { mutableStateOf("") }
    var password by remember(controller) { mutableStateOf("") }
    var key by remember(controller) { mutableStateOf("") }
    var passphrase by remember(controller) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        Text(stringResource(R.string.files_sftp_auth_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
            FilterChip(selected = method == 0, onClick = { method = 0 }, label = { Text(stringResource(R.string.files_sftp_password)) })
            FilterChip(selected = method == 1, onClick = { method = 1 }, label = { Text(stringResource(R.string.files_sftp_key)) })
            if (savedKeyAvailable) FilterChip(selected = method == 2, onClick = { method = 2 }, label = { Text(stringResource(R.string.files_sftp_saved_key)) })
        }
        if (method != 2) OutlinedTextField(username, { if (it.length <= 256) username = it },
            label = { Text(stringResource(R.string.files_sftp_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (method == 0) OutlinedTextField(password, { if (it.length <= 16_384) password = it },
            label = { Text(stringResource(R.string.files_sftp_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (method == 1) OutlinedTextField(key, { if (it.length <= 65_536) key = it },
            label = { Text(stringResource(R.string.files_sftp_private_key)) }, minLines = 3, maxLines = 5,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (method != 0) OutlinedTextField(passphrase, { if (it.length <= 16_384) passphrase = it },
            label = { Text(stringResource(R.string.files_sftp_passphrase)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            when (method) {
                0 -> controller.authenticatePassword(username, password)
                1 -> controller.authenticateKey(username, key, passphrase)
                else -> controller.authenticateSavedKey(passphrase)
            }
            username = ""; password = ""; key = ""; passphrase = ""
        }, enabled = method == 2 || (username.isNotBlank() && (method == 0 || key.isNotBlank()))) {
            Text(stringResource(R.string.files_sftp_sign_in))
        }
    }
}
