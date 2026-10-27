package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.workspace.OpenSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionWorkspaceSheet(sessions: List<OpenSession>, onSelect: (String) -> Unit, onCloseSession: (String) -> Unit, onDismiss: () -> Unit) {
    var closing by remember { mutableStateOf<OpenSession?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.workspace_sessions), style = MaterialTheme.typography.headlineSmall) }
            item { Text(stringResource(R.string.workspace_session_hint), style = MaterialTheme.typography.bodyMedium) }
            if (sessions.isEmpty()) item { Text(stringResource(R.string.workspace_empty)) }
            items(sessions, key = { it.id }) { session ->
                val chatState = session.chat?.state?.collectAsState()?.value
                Column(Modifier.fillMaxWidth()) {
                    Text(session.deviceName, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(session.tab.labelRes), style = MaterialTheme.typography.bodyMedium)
                        ChatUnreadBadge(chatState?.unreadCount ?: 0)
                    }
                    Row {
                        TextButton(onClick = { onSelect(session.id) }) { Text(stringResource(R.string.workspace_switch)) }
                        TextButton(onClick = { closing = session }) { Text(stringResource(R.string.device_close_session)) }
                    }
                    HorizontalDivider()
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    closing?.let { session ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.device_close_session)) },
            text = { Text(stringResource(R.string.workspace_close_explanation, session.deviceName)) },
            confirmButton = { TextButton(onClick = { closing = null; onCloseSession(session.id) }) { Text(stringResource(R.string.device_close_session)) } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
