package ru.papajon.meshcentralmobile.ui.components

import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.background.FileTransferService
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.transfer.TransferTarget
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace
import ru.papajon.meshcentralmobile.localization.UiText

@Composable
fun ShareDestinationPanel(workspace: ConnectionWorkspace, profileName: String, device: MeshDevice, controller: FilesController, onOpenQueue: () -> Unit) {
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val state by controller.state.collectAsState()
    var target by remember(controller) { mutableStateOf<TransferTarget?>(null) }
    if (workspace.sharedFiles.isEmpty()) return
    val phoneRoot = state.androidPhone && state.path.isEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.workspace_choose_folder, workspace.sharedFiles.size), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { target = TransferTarget(workspace.profileId, profileName, device.nodeId, device.name, state.path, controller) },
            enabled = !phoneRoot && state.connected && !state.busy && !workspace.transfers.hasPending && state.confirmation == null) {
            Text(stringResource(R.string.workspace_send_here))
        }
        if (phoneRoot) Text(stringResource(R.string.phone_files_choose_category), style = MaterialTheme.typography.bodySmall)
    }
    target?.let { destination ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(R.string.workspace_confirm_share)) },
            text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(destination.displayName)
                Text(destination.directory.ifEmpty { "/" })
                workspace.sharedFiles.forEach { Text(it.name) }
                Text(stringResource(R.string.workspace_share_overwrite))
            } },
            confirmButton = { TextButton(onClick = {
                target = null
                try {
                    ContextCompat.startForegroundService(context, Intent(context, FileTransferService::class.java))
                    if (workspace.enqueueShare(destination)) {
                        onOpenQueue()
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                } catch (_: Exception) { workspace.message = UiText.resource(R.string.workspace_background_unavailable) }
            }) { Text(stringResource(R.string.workspace_send_here)) } },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
