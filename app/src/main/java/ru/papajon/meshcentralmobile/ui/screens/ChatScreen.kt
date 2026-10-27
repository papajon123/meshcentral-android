package ru.papajon.meshcentralmobile.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.chat.*
import ru.papajon.meshcentralmobile.notifications.NotificationNotifier
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(controller: ChatController, onClose: () -> Unit, modifier: Modifier = Modifier, onRead: () -> Unit = {},
    deviceName: String = stringResource(R.string.chat_title), onBack: () -> Unit = onClose,
    onKeepAlive: () -> Boolean = { true }, screenVisible: Boolean = true) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var notifications by remember { mutableStateOf(NotificationNotifier.hasPermission(context)) }
    var permissionAttempted by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationNotifier.hasPermission(context)
    }
    LaunchedEffect(controller) { controller.start() }
    DisposableEffect(controller, lifecycle, screenVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = NotificationNotifier.hasPermission(context)
                controller.setVisible(screenVisible)
                if (screenVisible) onRead()
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) controller.setVisible(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        controller.setVisible(screenVisible && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (screenVisible && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onRead()
        onDispose { controller.setVisible(false); lifecycle.lifecycle.removeObserver(observer) }
    }
    ChatContent(state, controller::setDraft, { controller.send() }, { if (onKeepAlive()) controller.requestWindow() }, onClose,
        modifier = modifier, notificationsEnabled = notifications, deviceName = deviceName,
        notificationPermissionDenied = permissionAttempted && !notifications, onBack = onBack,
        onEnableNotifications = {
            if (!permissionAttempted && Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionAttempted = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        })
}

@Composable
fun ChatContent(
    state: ChatState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRequest: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsEnabled: Boolean = true,
    onEnableNotifications: () -> Unit = {},
    deviceName: String = stringResource(R.string.chat_title),
    notificationPermissionDenied: Boolean = false,
    onBack: () -> Unit = onClose,
) {
    var confirmClose by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val requestClose = {
        keyboard?.hide()
        if (state.messages.isNotEmpty() || state.draft.isNotEmpty()) confirmClose = true else onClose()
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text(stringResource(R.string.chat_close)) }, text = { Text(stringResource(R.string.chat_close_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }, modifier = Modifier.testTag("chat-confirm-close")) { Text(stringResource(R.string.chat_close)) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) } })
    val list = rememberLazyListState()
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val scrollHeader = compact && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val compactRecovery = scrollHeader && state.phase != ChatPhase.Connected
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.size)
    }
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!scrollHeader) ChatHeader(deviceName, requestClose, onBack)
        if (state.phase != ChatPhase.Connected && !compactRecovery) ChatConnectionControls(state, onRequest)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-messages"), state = list,
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "chat-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == ChatPhase.Connected) ChatConnectionControls(state, onRequest)
                    if (state.invitationUnavailable) Text(stringResource(R.string.chat_invite_unavailable))
                    if (state.recorded) Text(stringResource(R.string.chat_recorded), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                    if (notificationPermissionDenied) Text(stringResource(R.string.chat_notifications_unavailable), style = MaterialTheme.typography.bodySmall)
                    if (!notificationsEnabled) TextButton(onClick = onEnableNotifications) { Text(stringResource(R.string.chat_notifications_enable)) }
                    if (state.messages.isEmpty()) Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                    Text(stringResource(if (message.outgoing) R.string.chat_you else R.string.chat_user) + " · " + formatter.format(Date(message.timeMillis)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.truncated) Text(stringResource(R.string.chat_truncated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 560.dp)) {
                        SelectionContainer { Text(message.text, Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scrollHeader) IconButton(onClick = { keyboard?.hide(); onBack() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.chat_back))
            }
            OutlinedTextField(value = state.draft, onValueChange = onDraft,
                modifier = Modifier.weight(1f).testTag("chat-input"), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (state.canSend) onSend() }),
                enabled = state.phase != ChatPhase.Closed,
                label = {
                    if (compactRecovery) ChatStatus(state, small = true)
                    else Text(if (scrollHeader) stringResource(R.string.chat_message) + " · " + deviceName
                        else stringResource(R.string.chat_message), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
                isError = state.draft.length > ChatProtocol.MAX_TEXT || state.sendFailed,
                supportingText = if (state.draft.length > ChatProtocol.MAX_TEXT || state.sendFailed) ({
                    Text(if (state.sendFailed) stringResource(R.string.chat_send_failed)
                        else stringResource(R.string.chat_length, ChatProtocol.MAX_TEXT))
                }) else null)
            if (compactRecovery) ChatRetry(state, onRequest)
            else FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.size(48.dp).testTag("chat-send")) {
                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.chat_send))
            }
            if (scrollHeader) IconButton(onClick = requestClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.chat_close))
            }
        }
    }
}

/** Recovery controls stay above the transcript; with a landscape IME they share the composer. */
@Composable
private fun ChatConnectionControls(state: ChatState, onRequest: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatStatus(state)
        ChatRetry(state, onRequest)
    }
}

@Composable
private fun ChatStatus(state: ChatState, small: Boolean = false) {
        Text(stringResource(when (state.phase) {
            ChatPhase.Idle, ChatPhase.Connecting -> R.string.chat_connecting
            ChatPhase.Waiting -> R.string.chat_waiting
            ChatPhase.Reconnecting -> R.string.chat_reconnecting
            ChatPhase.Verifying -> R.string.chat_verifying_peer
            ChatPhase.ServerRetained -> R.string.chat_server_retained
            ChatPhase.NotOpened -> R.string.chat_not_opened
            ChatPhase.Connected -> R.string.chat_connected
            ChatPhase.PeerLeft -> R.string.chat_peer_left
            ChatPhase.Failed -> R.string.chat_failed
            ChatPhase.Closed -> R.string.chat_closed
        }), Modifier.testTag("chat-status"), style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ChatRetry(state: ChatState, onRequest: () -> Unit) {
        if (state.phase != ChatPhase.Connected && state.phase != ChatPhase.Closed) {
            OutlinedButton(onClick = onRequest, enabled = state.canRequest && state.phase !in setOf(ChatPhase.Connecting, ChatPhase.Verifying, ChatPhase.ServerRetained),
                modifier = Modifier.testTag("chat-request")) { Text(stringResource(R.string.chat_request)) }
        }
}

@Composable
private fun ChatHeader(deviceName: String, onClose: () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.chat_back)) }
        Text(deviceName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = onClose) { Text(stringResource(R.string.chat_close)) }
    }
}
