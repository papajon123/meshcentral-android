package ru.papajon.meshcentralmobile.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.transfer.QueuedTransfer
import ru.papajon.meshcentralmobile.transfer.TransferQueue
import ru.papajon.meshcentralmobile.transfer.TransferStatus
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshSheet
import ru.papajon.meshcentralmobile.ui.components.SheetHeader
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/**
 * Foreground queue sheet. URIs are not present in [QueuedTransfer], so this UI cannot leak one into
 * saved state. Retry always asks again because a previous attempt may have replaced the remote file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferQueueSheet(queue: TransferQueue, onClose: () -> Unit, onRetry: (Long) -> Unit = { queue.retry(it) }) {
    val rows by queue.state.collectAsState()
    val target = queue.target
    var retry by remember(queue) { mutableStateOf<QueuedTransfer?>(null) }
    val finished = rows.any {
        it.status == TransferStatus.Succeeded || it.status == TransferStatus.Failed ||
            it.status == TransferStatus.Cancelled
    }
    val pending = rows.any { it.status == TransferStatus.Queued || it.status == TransferStatus.Active }

    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.transfer_queue_title),
                subtitle = if (target == null) {
                    stringResource(R.string.transfer_queue_unbound)
                } else {
                    stringResource(
                        R.string.transfer_queue_target,
                        target.displayName,
                        if (target.directory.isEmpty()) stringResource(R.string.transfer_queue_root) else target.directory,
                    )
                },
            )
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.transfer_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            rows.forEach { row ->
                TransferQueueRow(
                    row = row,
                    onCancel = { queue.cancel(row.id) },
                    onRetry = { retry = row },
                )
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (pending) {
                    OutlinedButton(
                        onClick = { queue.cancelAll() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.transfer_cancel_all))
                    }
                }
                if (finished) {
                    OutlinedButton(
                        onClick = { queue.clearFinished() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.transfer_clear_finished))
                    }
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.transfer_close))
                }
            }
        }
    }

    retry?.let { row ->
        AlertDialog(
            onDismissRequest = { retry = null },
            title = { Text(stringResource(R.string.transfer_retry_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    target?.let { Text(it.displayName); Text(it.directory.ifEmpty { "/" }) }
                    Text(stringResource(R.string.transfer_retry_warning))
                    if (row.partialRemote) {
                        Text(
                            text = stringResource(R.string.transfer_partial_remote),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRetry(row.id)
                        retry = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.transfer_retry))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { retry = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.transfer_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferQueueRow(
    row: QueuedTransfer,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = when (row.status) {
        TransferStatus.Queued -> stringResource(R.string.transfer_status_queued)
        TransferStatus.Active -> stringResource(R.string.transfer_status_active)
        TransferStatus.Succeeded -> stringResource(R.string.transfer_status_succeeded)
        TransferStatus.Failed -> stringResource(R.string.transfer_status_failed)
        TransferStatus.Cancelled -> stringResource(R.string.transfer_status_cancelled)
    }
    val statusColor = when (row.status) {
        TransferStatus.Queued -> MaterialTheme.colorScheme.onSurfaceVariant
        TransferStatus.Active -> MaterialTheme.colorScheme.primary
        TransferStatus.Succeeded -> MeshTheme.colors.online
        TransferStatus.Failed -> MaterialTheme.colorScheme.error
        TransferStatus.Cancelled -> MeshTheme.colors.warning
    }
    MeshCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
                if (row.size != null) {
                    Text(
                        text = stringResource(R.string.transfer_progress_size, row.bytes, row.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (row.status == TransferStatus.Active) {
                    Text(
                        text = stringResource(R.string.transfer_progress, row.bytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.status == TransferStatus.Active) {
                val size = row.size
                if (size != null && size > 0) {
                    LinearProgressIndicator(
                        progress = { (row.bytes.toFloat() / size.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            row.error?.let { error ->
                Text(
                    text = error.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (row.status == TransferStatus.Cancelled && row.partialRemote) {
                Text(
                    text = stringResource(R.string.transfer_partial_remote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshTheme.colors.warning,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.status == TransferStatus.Queued || row.status == TransferStatus.Active) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.transfer_cancel))
                    }
                }
                if (row.status == TransferStatus.Failed || row.status == TransferStatus.Cancelled) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.transfer_retry))
                    }
                }
            }
        }
    }
}
