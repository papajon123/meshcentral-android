package ru.papajon.meshcentralmobile.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import ru.papajon.meshcentralmobile.mesh.files.FileSelection
import ru.papajon.meshcentralmobile.mesh.files.DownloadStatus
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
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.background.FileTransferService
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.files.FileChange
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.files.RemoteFile
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.theme.MeshMono
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF access is leased for the queue; no storage-wide permission or saved transfer is used. */
@Composable
fun FilesScreen(controller: FilesController, onBack: () -> Unit, modifier: Modifier = Modifier, preserveOnBack: Boolean = false, onReopen: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val batch by controller.batch.state.collectAsState()
    val selection = remember(controller) { FileSelection() }
    var selectedNames by remember(controller) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.path, state.entries) {
        selection.reconcile(state.path, state.entries); selectedNames = selection.names
    }
    fun toggle(entry: RemoteFile) { selection.toggle(state.path, entry); selectedNames = selection.names }
    fun clearSelection() { selection.clear(); selectedNames = emptySet() }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        controller.batch.choose(uri)
    }
    val createBatchDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        controller.batch.choose(uri, document = true)
    }
    var downloadConfirmation by remember(controller) { mutableStateOf<List<RemoteFile>?>(null) }
    fun beginDownloads(entries: List<RemoteFile>) {
        scope.launch {
            val single = entries.size == 1 && !entries[0].directory
            if (controller.batch.prepare(entries, singleDocument = single)) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, FileTransferService::class.java))
                    if (single) createBatchDocument.launch(entries[0].name) else chooseFolder.launch(null)
                    clearSelection()
                } catch (_: Exception) { controller.batch.chosen(null) }
            }
        }
    }
    fun downloadItems(entries: List<RemoteFile>) {
        if (!state.sftp && entries.any { it.directory }) downloadConfirmation = entries else beginDownloads(entries)
    }
    fun retry(index: Int) { scope.launch {
        if (batch.singleDocument) controller.batch.prepareSingleRetry(index)?.let { createBatchDocument.launch(it) }
        else controller.batch.retry(index)
    } }
    var selected by remember(controller) { mutableStateOf<RemoteFile?>(null) }
    var leaving by remember(controller) { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        scope.launch { controller.downloadChosen(uri) }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.proposeUpload(uri)
    }
    val requestBack: () -> Unit = { if ((state.busy || state.batchBusy) && !preserveOnBack) leaving = true else onBack() }
    BackHandler(onBack = requestBack)
    LaunchedEffect(controller) { controller.connect() }
    val enabled = state.connected && !state.busy && !state.batchBusy && !batch.pending && state.confirmation == null
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
                    canCreateDirectory = !state.androidPhone,
                    canUpload = !state.androidPhone || state.path.isNotEmpty(),
                )
                if (!state.androidPhone) {
                    Text(stringResource(R.string.tools_select_hint), style = MaterialTheme.typography.bodySmall)
                    if (selectedNames.isNotEmpty()) {
                        Text(stringResource(R.string.tools_selected, selectedNames.size))
                        BatchActions(enabled,
                            onDownload = { downloadItems(state.entries.filter { it.name in selectedNames }) },
                            onDelete = { controller.proposeDelete(state.entries.filter { it.name in selectedNames }); clearSelection() },
                            onClear = ::clearSelection)
                    }
                    if (batch.rows.isNotEmpty()) {
                        Text(stringResource(R.string.tools_batch_progress, batch.completed, batch.rows.size, batch.bytes))
                        LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.rows.size }, modifier = Modifier.fillMaxWidth())
                        if (!batch.singleDocument && !batch.pending && batch.rows.any { it.status in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled, DownloadStatus.Paused) }) {
                            TextButton(onClick = { scope.launch { controller.batch.retry() } }, enabled = !state.busy && !state.batchBusy && state.confirmation == null) {
                                Text(stringResource(R.string.review_retry_all))
                            }
                        }
                    }
                    if (batch.pending) TextButton(onClick = controller.batch::cancel) { Text(stringResource(R.string.files_cancel_operation)) }
                }
                if (state.sftp) Text(stringResource(R.string.review_sftp_types), style = MaterialTheme.typography.bodySmall)
                if (state.androidPhone) Text(stringResource(R.string.phone_files_limits), style = MaterialTheme.typography.bodySmall)
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
                if (state.busy && !batch.pending) {
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
        itemsIndexed(batch.rows, key = { index, _ -> "transfer:$index" }) { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when (row.status) {
                    DownloadStatus.Queued -> R.string.tools_queued
                    DownloadStatus.Active -> if (batch.uploading) R.string.transfer_status_active else R.string.tools_active
                    DownloadStatus.Succeeded -> R.string.tools_succeeded
                    DownloadStatus.Failed -> R.string.tools_failed
                    DownloadStatus.Cancelled -> R.string.tools_cancelled
                    DownloadStatus.Paused -> R.string.review_paused
                }
                Column(Modifier.weight(1f)) {
                    Text(row.name + " · " + stringResource(status), style = MaterialTheme.typography.bodySmall)
                    row.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
                }
                if (!batch.pending && row.status in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled, DownloadStatus.Paused)) {
                    TextButton(onClick = { retry(index) }, enabled = !state.busy && !state.batchBusy && state.confirmation == null) { Text(stringResource(R.string.tools_retry)) }
                }
            }
        }
        itemsIndexed(
            items = state.entries,
            key = { _, entry -> entry.listingIndex?.let { "media:$it" } ?: "entry:${entry.name}" },
        ) { index, entry ->
            Column {
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                FileRow(entry = entry, enabled = enabled,
                    selected = entry.name in selectedNames,
                    selecting = selectedNames.isNotEmpty(),
                    onLongClick = { if (!state.androidPhone) toggle(entry) },
                    onActions = { selected = entry },
                    onClick = {
                        when {
                            selectedNames.isNotEmpty() -> toggle(entry)
                            entry.actionable && entry.directory -> controller.open(entry)
                            else -> selected = entry
                        }
                    })
            }
        }
    }

    downloadConfirmation?.let { chosen ->
        AlertDialog(onDismissRequest = { downloadConfirmation = null },
            title = { Text(stringResource(R.string.files_download)) },
            text = { Text(stringResource(R.string.review_archive_space)) },
            confirmButton = { TextButton(onClick = { downloadConfirmation = null; beginDownloads(chosen) }) { Text(stringResource(R.string.files_download)) } },
            dismissButton = { TextButton(onClick = { downloadConfirmation = null }) { Text(stringResource(R.string.files_cancel)) } })
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
                    if (!entry.actionable) Text(stringResource(if (entry.ambiguousName) R.string.phone_files_ambiguous_name
                        else R.string.files_name_unavailable))
                    if (entry.actionable && entry.directory && entry.type != 1 && !state.androidPhone) {
                        TextButton(onClick = { selected = null; downloadItems(listOf(entry)) }, enabled = enabled) {
                            Text(stringResource(R.string.tools_download_folder))
                        }
                        Text(stringResource(R.string.tools_folder_notice), style = MaterialTheme.typography.bodySmall)
                    }
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
                                if (!state.androidPhone) downloadItems(listOf(entry)) else scope.launch {
                                if (controller.prepareDownload(entry)) {
                                    try {
                                        // Start while this Activity is visible, before SAF can background it.
                                        ContextCompat.startForegroundService(context, Intent(context, FileTransferService::class.java))
                                        createDocument.launch(entry.name + ".partial")
                                    } catch (_: Exception) {
                                        withContext(NonCancellable) { controller.downloadPreparationFailed() }
                                    }
                                }
                                }
                            },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.files_download))
                        }
                        if (state.sftp) Text(stringResource(R.string.review_sftp_types), style = MaterialTheme.typography.bodySmall)
                if (state.androidPhone) Text(
                            text = stringResource(R.string.files_download_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.actionable && entry.type != 1) {
                        if (!state.androidPhone) {
                        TextButton(
                            onClick = { selected = null; controller.propose(FileChange.Rename, entry) },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.files_rename))
                        }
                        }
                        if (!state.androidPhone || state.path.isNotEmpty()) {
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
                        if (request.entries.isNotEmpty()) {
                            Text(stringResource(R.string.tools_delete_list))
                            request.entries.forEach { Text(it.displayName) }
                            if (request.directory) Text(stringResource(warning, request.entries.filter { it.directory }.joinToString(", ") { it.displayName }))
                        } else Text(stringResource(warning, request.name))
                        if (request.directory) {
                            Text(stringResource(R.string.files_delete_follow_links), style = MaterialTheme.typography.bodySmall)
                        }
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
    canCreateDirectory: Boolean = true,
    canUpload: Boolean = true,
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
        if (canCreateDirectory) OutlinedButton(
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
            enabled = enabled && canUpload,
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: RemoteFile, enabled: Boolean, selected: Boolean = false, selecting: Boolean = false,
    onLongClick: () -> Unit = {}, onActions: () -> Unit = {}, onClick: () -> Unit) {
    val supporting = when {
        entry.ambiguousName -> "#${entry.listingIndex?.plus(1)} · ${stringResource(R.string.files_size, entry.size)}"
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
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled && entry.actionable && entry.type != 1)
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
        if (entry.directory && entry.type != 1 && !selecting) {
            IconButton(onClick = onActions, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "${stringResource(R.string.session_dock_more)}: ${entry.displayName}",
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchActions(enabled: Boolean, onDownload: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDownload, enabled = enabled) { Text(stringResource(R.string.tools_download_selected)) }
        TextButton(onClick = onDelete, enabled = enabled) { Text(stringResource(R.string.tools_delete_selected), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.tools_clear_selection)) }
    }
}
