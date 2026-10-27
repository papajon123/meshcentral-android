package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.terminal.TerminalAuth
import ru.papajon.meshcentralmobile.terminal.TerminalController
import ru.papajon.meshcentralmobile.ui.components.StatusDot
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import java.util.Locale

@Composable
fun TerminalScreen(controller: TerminalController, onBack: () -> Unit, modifier: Modifier = Modifier, onReopen: (() -> Unit)? = null,
    favoriteCommands: List<ru.papajon.meshcentralmobile.model.FavoriteCommand> = emptyList(),
    onAddFavorite: (ru.papajon.meshcentralmobile.model.FavoriteCommand) -> Boolean = { false },
    onRemoveFavorite: (Int) -> Unit = {},
    profileId: String = "",
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val renderer = remember(controller) { controller.renderer(context) }
    var control by remember(controller) { mutableStateOf(false) }
    var favoritesOpen by remember(controller, profileId) { mutableStateOf(false) }
    DisposableEffect(renderer, profileId) { onDispose { renderer?.inputDialog(false) } }
    if (favoritesOpen) androidx.compose.runtime.key(controller, profileId) {
        TerminalFavorites(favoriteCommands, state.ready, onAddFavorite, onRemoveFavorite,
            controller::insertFavorite,
            { favoritesOpen = false; renderer?.inputDialog(false) })
    }
    val background = String.format(Locale.ROOT, "#%06x", MaterialTheme.colorScheme.background.toArgb() and 0xffffff)
    val foreground = String.format(Locale.ROOT, "#%06x", MaterialTheme.colorScheme.onBackground.toArgb() and 0xffffff)
    val inputLabel = stringResource(R.string.terminal_input_label)
    val outputLabel = stringResource(R.string.terminal_accessibility_output_limit)
    val copyLabel = stringResource(R.string.terminal_clipboard_label)
    val reconnectMarker = stringResource(R.string.terminal_new_session)
    val fontSize = (16 * LocalDensity.current.fontScale).toInt()
    LaunchedEffect(controller) { controller.connect() }
    LaunchedEffect(renderer, background, foreground, fontSize, inputLabel, outputLabel) {
        renderer?.configure(background, foreground, fontSize, inputLabel, outputLabel)
    }
    LaunchedEffect(state.ready) { if (!state.ready) { control = false; renderer?.setControl(false) } }

    val ended = state.phase == RelayPhase.Closed || state.phase == RelayPhase.Failed
    val statusColor = when {
        state.auth != TerminalAuth.None -> MaterialTheme.colorScheme.tertiary
        state.ready -> MeshTheme.colors.online
        state.phase == RelayPhase.Failed -> MaterialTheme.colorScheme.error
        state.phase == RelayPhase.Closed -> MaterialTheme.colorScheme.outline
        else -> MeshTheme.colors.warning
    }
    val status = stringResource(when {
        state.auth != TerminalAuth.None -> R.string.terminal_auth_required
        state.ready -> R.string.terminal_connected
        state.awaitingShell -> R.string.terminal_waiting_shell
        state.phase == RelayPhase.Failed -> R.string.terminal_failed
        state.phase == RelayPhase.Closed -> R.string.terminal_closed
        state.phase == RelayPhase.Waiting -> R.string.terminal_waiting
        else -> R.string.terminal_connecting
    })

    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.terminal_back))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (controller.isSsh) {
                                stringResource(R.string.terminal_ssh_title)
                            } else {
                                stringResource(R.string.terminal_title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusDot(color = statusColor, size = 8.dp)
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = controller::disconnect,
                        enabled = !ended,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.terminal_disconnect))
                    }
                }
                if (state.recorded) {
                    Text(
                        text = stringResource(R.string.terminal_recorded),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        state.message?.let { message ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = message.asString(),
                    modifier = Modifier
                        .padding(10.dp)
                        .heightIn(max = 96.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (ended) {
            when {
                state.canReconnect -> TextButton(onClick = { controller.reconnect(reconnectMarker) }) {
                    Text(stringResource(R.string.terminal_reconnect))
                }
                // A controller with a factory owns reconnect; onReopen is only for legacy callers.
                (state.reopenRequired || !controller.supportsReconnect) && onReopen != null ->
                    TextButton(onClick = onReopen) { Text(stringResource(R.string.workspace_reopen)) }
                state.reopenRequired || !controller.supportsReconnect -> Text(
                    text = stringResource(R.string.terminal_reopen_hint),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { renderer?.inputDialog(true); favoritesOpen = true }) { Text(stringResource(R.string.parity2_favorites)) }
        if (renderer != null) {
            AndroidView(
                factory = renderer::createHost,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
                onRelease = renderer::releaseHost,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { renderer?.focus() },
                    enabled = state.ready,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.terminal_keyboard))
                }
                FilterChip(
                    selected = control,
                    onClick = {
                        control = !control
                        renderer?.setControl(control)
                        renderer?.focus()
                    },
                    enabled = state.ready,
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(R.string.terminal_ctrl)) },
                )
                for ((label, key) in listOf(
                    R.string.terminal_esc to "\u001b",
                    R.string.terminal_tab to "\t",
                    R.string.terminal_up to "\u001b[A",
                    R.string.terminal_down to "\u001b[B",
                    R.string.terminal_left to "\u001b[D",
                    R.string.terminal_right to "\u001b[C",
                    R.string.terminal_enter to "\r",
                )) {
                    TextButton(
                        onClick = { renderer?.key(key); renderer?.focus() },
                        enabled = state.ready,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(label))
                    }
                }
                TextButton(
                    onClick = { renderer?.selectAll() },
                    enabled = renderer != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.terminal_select_all))
                }
                TextButton(
                    onClick = { renderer?.copySelection(copyLabel) },
                    enabled = renderer != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.terminal_copy))
                }
                TextButton(
                    onClick = { renderer?.clearSelection() },
                    enabled = renderer != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.terminal_clear_selection))
                }
            }
        }
    }
    if (state.auth != TerminalAuth.None) SshAuthentication(controller, state.auth)
}

@Composable
private fun SshAuthentication(controller: TerminalController, auth: TerminalAuth) {
    // Deliberately not rememberSaveable: passwords/private keys never enter Bundle or saved state.
    var username by remember(controller, auth) { mutableStateOf("") }
    var password by remember(controller, auth) { mutableStateOf("") }
    var key by remember(controller, auth) { mutableStateOf("") }
    var passphrase by remember(controller, auth) { mutableStateOf("") }
    var useKey by remember(controller, auth) { mutableStateOf(false) }
    fun clear() { username = ""; password = ""; key = ""; passphrase = "" }
    DisposableEffect(controller, auth) { onDispose { clear() } }
    AlertDialog(onDismissRequest = { clear(); controller.close() },
        title = { Text(stringResource(R.string.terminal_ssh_auth_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.terminal_credentials_ephemeral))
                if (auth == TerminalAuth.PasswordOrKey) {
                    OutlinedTextField(username, { username = it.take(256) }, label = { Text(stringResource(R.string.terminal_username)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    FilterChip(selected = useKey, onClick = { useKey = !useKey; password = ""; key = "" },
                        label = { Text(stringResource(R.string.terminal_use_key)) })
                    if (useKey) OutlinedTextField(key, { key = it.take(65_536) }, label = { Text(stringResource(R.string.terminal_private_key)) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp), minLines = 2, maxLines = 4,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                    else OutlinedTextField(password, { password = it.take(4096) }, label = { Text(stringResource(R.string.terminal_password)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                }
                if (useKey || auth == TerminalAuth.KeyPassphrase) OutlinedTextField(passphrase, { passphrase = it.take(4096) },
                    label = { Text(stringResource(R.string.terminal_key_passphrase)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            }
        },
        confirmButton = { Button(onClick = { controller.authenticate(username, password, if (useKey) key else "", passphrase); clear() },
            enabled = auth == TerminalAuth.KeyPassphrase || (username.isNotBlank() && (!useKey || key.isNotBlank()))) {
            Text(stringResource(R.string.terminal_authenticate))
        } },
        dismissButton = { TextButton(onClick = { clear(); controller.close() }) { Text(stringResource(R.string.terminal_cancel)) } },
    )
}
