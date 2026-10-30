package ru.papajon.meshcentralmobile.ui.screens.management

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DeviceInfoReport
import ru.papajon.meshcentralmobile.management.DeviceInfoWriter
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/**
 * Compact Copy / Save to file actions for the already displayed [snapshot].
 *
 * Callers show this only when the Details tab has a non-empty snapshot. The text is built from the
 * snapshot at click time; the save picker keeps that exact text in `remember`, never in saved state,
 * so an activity recreation cannot write a different snapshot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceInfoActions(
    snapshot: DeviceSystemSnapshot,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.device_info_copy)
    val saveLabel = stringResource(R.string.device_info_save)
    val copiedMessage = stringResource(R.string.session_text_copied)
    val copyError = stringResource(R.string.device_info_copy_error)
    val savedMessage = stringResource(R.string.device_info_saved)
    val saveError = stringResource(R.string.device_info_save_error)
    var pendingText by remember { mutableStateOf<String?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DeviceInfoReport.MIME_TYPE),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = try {
                withContext(Dispatchers.IO) {
                    DeviceInfoWriter.write(
                        openStream = { context.contentResolver.openOutputStream(uri, "wt") },
                        text = text,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(context, if (written) savedMessage else saveError, Toast.LENGTH_SHORT).show()
            }
        }
    }

    FlowRow(
        modifier = modifier.testTag(DeviceInspectorTestTags.DeviceInfoActions),
        horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs),
    ) {
        TextButton(
            onClick = {
                val text = DeviceInfoReport.format(context, snapshot)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val copied = try {
                    if (clipboard == null) {
                        false
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                        true
                    }
                } catch (_: SecurityException) {
                    false
                }
                Toast.makeText(
                    context,
                    if (copied) copiedMessage else copyError,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DeviceInspectorTestTags.DeviceInfoCopy),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(copyLabel, modifier = Modifier.padding(start = MeshTheme.spacing.xs))
        }
        TextButton(
            onClick = {
                pendingText = DeviceInfoReport.format(context, snapshot)
                createDocument.launch(DeviceInfoReport.FILE_NAME)
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(DeviceInspectorTestTags.DeviceInfoSave),
        ) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(saveLabel, modifier = Modifier.padding(start = MeshTheme.spacing.xs))
        }
    }
}
