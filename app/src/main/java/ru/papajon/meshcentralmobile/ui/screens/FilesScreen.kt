package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.files.FileChange
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.files.RemoteFile
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.theme.MeshMono
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import java.util.Locale

/** SAF grants are temporary; no storage-wide permission, persisted URI, or saved transfer is used. */
@Composable
fun FilesScreen(controller: FilesController, onBack: () -> Unit, modifier: Modifier = Modifier, preserveOnBack: Boolean = false, onReopen: (() -> Unit)? = null) {
    val state by controller.state.collectAsState()
    var selected by remember(controller) { mutableStateOf<RemoteFile?>(null) }
    var leaving by remember(controller) { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        controller.downloadChosen(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.proposeUpload(uri)
    }
    val requestBack: () -> Unit = { if (state.busy && !preserveOnBack) leaving = true else onBack() }
    BackHandler(onBack = requestBack)
    LaunchedEffect(controller) { controller.connect() }
    val enabled = state.connected && !state.busy && state.confirmation == null
    val spacing = MeshTheme.spacing
    val canParent = state.path.isNotEmpty() && state.path != "/"

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item(key = 0) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = requestBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.files_back))
                    }
                    Text(
                        text = stringResource(R.string.files_title),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PathSurface(path = state.path)
                FilesToolbar(
                    enabled = enabled,
                    canParent = canParent,
                    onParent = controller::parent,
                    onRefresh = controller::refresh,
                    onCreateDirectory = { controller.propose(FileChange.CreateDirectory) },
                    onUpload = { openDocument.launch(arrayOf("*/*")) },
                )
                if (!state.connected && !state.busy && !state.authRequired) {
                    onReopen?.let { action -> TextButton(onClick = action) { Text(stringResource(R.string.workspace_reopen)) } }
                    if (onReopen == null) Text(
                        text = stringResource(R.string.files_reopen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.authRequired) {
                    SftpAuthentication(controller, state.askKeyPass)
                }
                state.message?.let { message ->
                    MessageBanner(message = message.asString())
                }
                if (state.busy) {
                    TransferBanner(
                        name = state.transferName,
                        transferred = state.transferred,
                        onCancel = controller::cancel,
                    )
                }
                if (state.entries.isEmpty() && state.connected && !state.busy) {
                    Text(
                        text = stringResource(R.string.files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(
            items = state.entries,
            key = { _, entry -> entry.name },
        ) { index, entry ->
            Column {
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                FileRow(entry = entry, enabled = enabled) { selected = entry }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.displayName) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (!entry.actionable) Text(stringResource(R.string.files_name_unavailable))
                    if (entry.actionable && entry.directory) {
                        TextButton(
                            onClick = { selected = null; controller.open(entry) },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.files_open))
                        }
                    } else if (entry.actionable) {
                        TextButton(
                            onClick = {
                                selected = null
                                if (controller.prepareDownload(entry)) createDocument.launch(entry.name + ".partial")
                            },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.files_download))
                        }
                        Text(
                            text = stringResource(R.string.files_download_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.actionable && entry.type != 1) {
                        TextButton(
                            onClick = { selected = null; controller.propose(FileChange.Rename, entry) },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.files_rename))
                        }
                        TextButton(
                            onClick = { selected = null; controller.propose(FileChange.Delete, entry) },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.files_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { selected = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.files_close))
                }
            },
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
        val destination = if (request.path.isEmpty()) stringResource(R.string.files_root) else request.path
        AlertDialog(
            onDismissRequest = controller::dismissConfirmation,
            title = { Text(stringResource(title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(stringResource(R.string.files_destination, destination))
                    if (request.change == FileChange.Delete) {
                        val warning = if (request.directory && state.sftp) R.string.files_sftp_delete_empty
                        else if (request.directory) R.string.files_confirm_delete_tree else R.string.files_confirm_delete
                        Text(stringResource(warning, request.name))
                    } else {
                        if (request.change == FileChange.Rename) {
                            Text(stringResource(R.string.files_original_name, request.name))
                            Text(stringResource(R.string.files_rename_warning))
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.files_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (request.change == FileChange.Upload) {
                            Text(stringResource(R.string.files_upload_warning))
                        }
                    }
                    state.message?.let { message ->
                        Text(
                            text = message.asString(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { controller.confirm(name) },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (request.change == FileChange.Delete) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (request.change == FileChange.Delete) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    ),
                ) {
                    Text(
                        stringResource(
                            if (request.change == FileChange.Upload) R.string.files_upload_confirm else R.string.files_confirm,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = controller::dismissConfirmation,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.files_leave_title)) },
            text = { Text(stringResource(R.string.files_leave_warning)) },
            confirmButton = {
                TextButton(
                    onClick = { leaving = false; controller.close(); onBack() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.files_leave))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { leaving = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }
}

@Composable
private fun PathSurface(path: String) {
    val displayPath = if (path.isEmpty()) stringResource(R.string.files_root) else path
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = displayPath,
                modifier = Modifier.weight(1f),
                style = MeshMono.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilesToolbar(
    enabled: Boolean,
    canParent: Boolean,
    onParent: () -> Unit,
    onRefresh: () -> Unit,
    onCreateDirectory: () -> Unit,
    onUpload: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = onParent,
            enabled = enabled && canParent,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.files_parent))
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.files_refresh))
        }
        OutlinedButton(
            onClick = onCreateDirectory,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.files_mkdir))
        }
        Button(
            onClick = onUpload,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.files_upload))
        }
    }
}

@Composable
private fun MessageBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TransferBanner(name: String?, transferred: Long?, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                if (name != null) {
                    Text(
                        text = name,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            transferred?.let {
                Text(
                    text = stringResource(R.string.files_progress, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.files_cancel_operation))
            }
        }
    }
}

@Composable
private fun FileRow(entry: RemoteFile, enabled: Boolean, onClick: () -> Unit) {
    val supporting = when {
        !entry.actionable -> stringResource(R.string.files_name_unavailable)
        entry.directory -> stringResource(R.string.files_folder)
        else -> {
            val size = stringResource(R.string.files_size, entry.size)
            val type = entry.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
            if (type.isBlank()) size else "$type · $size"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(
            icon = fileIcon(entry),
            size = 40.dp,
            tint = if (entry.directory) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            container = if (entry.directory) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (entry.actionable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun fileIcon(entry: RemoteFile): ImageVector {
    if (entry.directory) return Icons.Outlined.Folder
    return when (entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> Icons.Outlined.Image
        "pdf" -> Icons.Outlined.PictureAsPdf
        "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar" -> Icons.Outlined.Archive
        "mp4", "mkv", "webm", "avi", "mov" -> Icons.Outlined.Movie
        "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Outlined.AudioFile
        "csv", "xls", "xlsx" -> Icons.Outlined.TableChart
        "md", "txt", "log", "rtf" -> Icons.Outlined.TextSnippet
        "kt", "java", "js", "ts", "py", "sh", "json", "xml", "yml", "yaml", "html", "css", "c", "cpp", "go", "rs" -> Icons.Outlined.Code
        else -> Icons.Outlined.InsertDriveFile
    }
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
        Text(
            text = stringResource(R.string.files_sftp_auth_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = method == 0,
                onClick = { method = 0 },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(stringResource(R.string.files_sftp_password)) },
            )
            FilterChip(
                selected = method == 1,
                onClick = { method = 1 },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(stringResource(R.string.files_sftp_key)) },
            )
            if (savedKeyAvailable) {
                FilterChip(
                    selected = method == 2,
                    onClick = { method = 2 },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(R.string.files_sftp_saved_key)) },
                )
            }
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
        }, enabled = method == 2 || (username.isNotBlank() && (method == 0 || key.isNotBlank())),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)) {
            Text(stringResource(R.string.files_sftp_sign_in))
        }
    }
}
