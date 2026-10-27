package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.files.ArchiveCleanupNotice

/** Kept outside the file tab so its asynchronous close can still report an orphan. */
@Composable
internal fun ArchiveCleanupNoticeDialog() {
    val pending by ArchiveCleanupNotice.pending.collectAsState()
    val path = pending.firstOrNull() ?: return
    AlertDialog(onDismissRequest = { ArchiveCleanupNotice.dismiss(path) },
        title = { Text(stringResource(R.string.review_cleanup_title)) },
        text = { SelectionContainer { Text(stringResource(R.string.review_archive_left, path)) } },
        confirmButton = { TextButton(onClick = { ArchiveCleanupNotice.dismiss(path) }) { Text(stringResource(R.string.files_close)) } })
}
