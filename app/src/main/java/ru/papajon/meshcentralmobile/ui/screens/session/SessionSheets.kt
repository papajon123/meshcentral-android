@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.input.RemoteInputBridge
import ru.papajon.meshcentralmobile.input.RemoteInputResult
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.ClipboardKind
import ru.papajon.meshcentralmobile.mesh.ClipboardStatus
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.RemoteViewport
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshSheet
import ru.papajon.meshcentralmobile.ui.components.SheetHeader
import ru.papajon.meshcentralmobile.ui.components.StatusPill
import ru.papajon.meshcentralmobile.ui.components.clipboardKindLabel
import ru.papajon.meshcentralmobile.ui.components.remoteControlModeLabel
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/** Input mode picker: named behaviours instead of a hidden gesture legend. */
@Composable
fun MouseModeSheet(
    current: RemoteControlMode,
    onSelect: (RemoteControlMode) -> Unit,
    onDoubleClick: () -> Unit,
    onArmDrag: () -> Unit,
    onClose: () -> Unit,
) {
    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.session_pointer_title),
                subtitle = stringResource(R.string.session_pointer_subtitle),
            )
            RemoteControlMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            OutlinedButton(
                onClick = onDoubleClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = PillShape,
            ) {
                Text(stringResource(R.string.session_pointer_double_click))
            }
            OutlinedButton(
                onClick = onArmDrag,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = PillShape,
            ) {
                Text(stringResource(R.string.session_pointer_drag_next))
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: RemoteControlMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, description) = when (mode) {
        RemoteControlMode.DirectTouch -> Icons.Outlined.TouchApp to
            stringResource(R.string.session_mode_direct_touch_description)
        RemoteControlMode.Trackpad -> Icons.Outlined.Mouse to
            stringResource(R.string.session_mode_trackpad_description)
        RemoteControlMode.PanZoom -> Icons.Outlined.FitScreen to
            stringResource(R.string.session_mode_pan_zoom_description)
        RemoteControlMode.Scroll -> Icons.Outlined.SwapVert to
            stringResource(R.string.session_mode_scroll_description)
    }
    MeshCard(
        modifier = modifier.fillMaxWidth(),
        container = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(remoteControlModeLabel(mode), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                StatusPill(text = stringResource(R.string.common_status_active), color = MeshTheme.colors.online)
            }
        }
    }
}

/** Keyboard sheet: modifier toggles, a compact key grid and the shortcut row. */
@Composable
fun KeyboardSheet(
    input: NativeRemoteInputSink?,
    onClose: () -> Unit,
    onOpenTextSheet: (() -> Unit)? = null,
) {
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var alt by rememberSaveable { mutableStateOf(false) }
    var shift by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }
    val context = LocalContext.current

    fun send(keyCode: Int, meta: Boolean = false, shortcut: Boolean = false) {
        val result = if (shortcut) {
            RemoteInputBridge.sendCtrlShortcutToRemote(input, keyCode)
        } else {
            RemoteInputBridge.sendKeyToRemote(
                webView = null,
                nativeSink = input,
                keyCode = keyCode,
                ctrl = ctrl,
                alt = alt,
                shift = shift,
                meta = meta,
            )
        }
        message = when (result) {
            is RemoteInputResult.Sent -> UiText.Resource(R.string.session_key_sent)
            is RemoteInputResult.Failed -> result.reason
        }
    }

    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.session_keyboard_title),
                subtitle = stringResource(R.string.session_keyboard_subtitle),
            )
            if (onOpenTextSheet != null) {
                OutlinedButton(
                    onClick = onOpenTextSheet,
                    shape = PillShape,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.session_keyboard_long_text))
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, shape = PillShape, label = { Text("Ctrl") })
                FilterChip(selected = alt, onClick = { alt = !alt }, shape = PillShape, label = { Text("Alt") })
                FilterChip(selected = shift, onClick = { shift = !shift }, shape = PillShape, label = { Text("Shift") })
                FilterChip(
                    selected = false,
                    onClick = { send(android.view.KeyEvent.KEYCODE_META_LEFT, meta = true) },
                    shape = PillShape,
                    label = { Text("Win") },
                )
            }

            KeyGrid(modifier = Modifier.padding(horizontal = 16.dp)) { _, keyCode ->
                send(keyCode)
            }

            SectionLabel(stringResource(R.string.session_keyboard_shortcuts))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShortcutChip("Ctrl+A") { send(android.view.KeyEvent.KEYCODE_A, shortcut = true) }
                ShortcutChip("Ctrl+C") { send(android.view.KeyEvent.KEYCODE_C, shortcut = true) }
                ShortcutChip("Ctrl+V") { send(android.view.KeyEvent.KEYCODE_V, shortcut = true) }
                ShortcutChip("Ctrl+X") { send(android.view.KeyEvent.KEYCODE_X, shortcut = true) }
                ShortcutChip("Ctrl+Z") { send(android.view.KeyEvent.KEYCODE_Z, shortcut = true) }
                ShortcutChip("Ctrl+S") { send(android.view.KeyEvent.KEYCODE_S, shortcut = true) }
                ShortcutChip("Ctrl+Enter") { send(android.view.KeyEvent.KEYCODE_ENTER, shortcut = true) }
                ShortcutChip("Ctrl+Alt+Del") {
                    message = if (input?.sendCtrlAltDel() == true) {
                        UiText.Resource(R.string.session_ctrl_alt_del_sent)
                    } else {
                        UiText.Resource(R.string.common_not_connected)
                    }
                }
            }

            message?.let {
                Text(
                    text = it.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun KeyGrid(modifier: Modifier = Modifier, onKey: (String, Int) -> Unit) {
    val keys = listOf(
        "Esc" to android.view.KeyEvent.KEYCODE_ESCAPE,
        "Tab" to android.view.KeyEvent.KEYCODE_TAB,
        "Enter" to android.view.KeyEvent.KEYCODE_ENTER,
        "Backspace" to android.view.KeyEvent.KEYCODE_DEL,
        "Del" to android.view.KeyEvent.KEYCODE_FORWARD_DEL,
        "←" to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        "↑" to android.view.KeyEvent.KEYCODE_DPAD_UP,
        "↓" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        "→" to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    ) + (1..12).map { "F$it" to (android.view.KeyEvent.KEYCODE_F1 + it - 1) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, code) in keys) {
            KeyButton(label = label, onClick = { onKey(label, code) })
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ShortcutChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, shape = PillShape, label = { Text(label) })
}

/** Text entry sheet using MeshCentral unicode key packets. */
@Composable
fun TextSheet(
    input: NativeRemoteInputSink?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<UiText?>(null) }

    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.session_text_title),
                subtitle = stringResource(R.string.session_text_subtitle),
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(140.dp),
                value = draft,
                onValueChange = { draft = it },
                shape = MaterialTheme.shapes.small,
                label = { Text(stringResource(R.string.session_text_field_label)) },
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (draft.isEmpty()) {
                            message = UiText.Resource(R.string.session_text_nothing_to_send)
                            return@Button
                        }
                        val events = input?.sendUnicodeText(draft) ?: 0
                        message = if (events > 0) {
                            UiText.resource(R.string.session_text_typed, events)
                        } else {
                            UiText.Resource(R.string.common_not_connected)
                        }
                    },
                    shape = PillShape,
                ) {
                    Text(stringResource(R.string.session_text_send))
                }
                OutlinedButton(
                    onClick = {
                        val text = RemoteInputBridge.readFromAndroidClipboard(context)
                        draft = text
                        message = if (text.isBlank()) {
                            UiText.Resource(R.string.session_text_clipboard_empty)
                        } else {
                            UiText.Resource(R.string.session_text_pasted)
                        }
                    },
                    shape = PillShape,
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_action_paste))
                }
                OutlinedButton(
                    onClick = {
                        if (draft.isNotEmpty()) {
                            RemoteInputBridge.copyToAndroidClipboard(
                                context,
                                context.getString(R.string.session_text_clip_label),
                                draft,
                            )
                            message = UiText.Resource(R.string.session_text_copied)
                        }
                    },
                    shape = PillShape,
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_action_copy))
                }
                TextButton(onClick = { draft = "" }) { Text(stringResource(R.string.common_action_clear)) }
            }
            message?.let {
                Text(
                    text = it.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Clipboard exchange with the remote computer, both directions. */
@Composable
fun ClipboardSheet(
    meshClient: MeshCentralClient,
    device: MeshDevice?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val transfer by meshClient.clipboardState.collectAsState()

    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                title = stringResource(R.string.session_clipboard_title),
                subtitle = device?.name?.let { stringResource(R.string.session_clipboard_subtitle, it) }
                    ?: stringResource(R.string.session_clipboard_subtitle_computer),
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BigAction(
                    icon = Icons.Outlined.ContentPaste,
                    title = stringResource(R.string.session_clipboard_get),
                    subtitle = stringResource(R.string.session_clipboard_get_hint),
                    enabled = device != null,
                    modifier = Modifier.weight(1f),
                ) { device?.let { meshClient.fetchClipboardFromRemote(it) } }
                BigAction(
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(R.string.session_clipboard_send),
                    subtitle = stringResource(R.string.session_clipboard_send_hint),
                    enabled = device != null,
                    modifier = Modifier.weight(1f),
                ) {
                    val target = device ?: return@BigAction
                    val text = RemoteInputBridge.readFromAndroidClipboard(context)
                    if (text.isBlank()) {
                        meshClient.sendClipboardToRemote(target, "")
                    } else {
                        meshClient.sendClipboardToRemote(target, text)
                    }
                }
            }

            transfer?.let { current ->
                val (label, color) = when (current.status) {
                    ClipboardStatus.Pending ->
                        stringResource(R.string.session_clipboard_status_pending) to MeshTheme.colors.warning
                    ClipboardStatus.Success ->
                        stringResource(R.string.session_clipboard_status_success) to MeshTheme.colors.online
                    ClipboardStatus.Failed ->
                        stringResource(R.string.session_clipboard_status_failed) to MeshTheme.colors.danger
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusPill(
                                text = "${clipboardKindLabel(current.kind)} · $label",
                                color = color,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (current.kind == ClipboardKind.Fetch && current.text != null) {
                                TextButton(onClick = {
                                    RemoteInputBridge.copyToAndroidClipboard(
                                        context,
                                        context.getString(R.string.session_clipboard_copy_label),
                                        current.text,
                                    )
                                }) {
                                    Text(stringResource(R.string.session_clipboard_copy_again))
                                }
                            }
                        }
                        Text(
                            text = current.detail?.asString().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        current.text?.takeIf { it.isNotBlank() }?.let { text ->
                            Text(
                                text = text.take(600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.session_clipboard_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun BigAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MeshCard(
        modifier = modifier,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Displays, stream quality and session actions in one sheet. */
@Composable
fun SessionOptionsSheet(
    state: RemoteDesktopState,
    fullscreen: Boolean,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    debugVisible: Boolean,
    onDebugVisibleChange: (Boolean) -> Unit,
    onQuality: (Int, Int) -> Unit,
    onReconnect: () -> Unit,
    onShowHelp: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onHideChrome: () -> Unit,
    onClose: () -> Unit,
) {
    var quality by remember(state.attempt) { mutableStateOf(60f) }
    var frameRate by remember(state.attempt) { mutableStateOf(30f) }

    MeshSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHeader(title = stringResource(R.string.session_options_title), subtitle = state.device?.name)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onZoomOut, enabled = zoom > 1f, shape = PillShape) {
                    Icon(Icons.Outlined.ZoomOut, contentDescription = stringResource(R.string.session_zoom_out))
                }
                OutlinedButton(onClick = onZoomIn, enabled = zoom < RemoteViewport.MAX_ZOOM, shape = PillShape) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.session_zoom_in))
                }
                OutlinedButton(onClick = onResetZoom, enabled = zoom > 1f, shape = PillShape) {
                    Icon(Icons.Outlined.FitScreen, contentDescription = stringResource(R.string.common_action_fit_to_screen))
                }
            }
            SectionLabel(stringResource(R.string.session_options_stream_quality))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.session_options_image_quality, quality.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 20f..95f,
                    onValueChangeFinished = { onQuality(quality.toInt(), frameRate.toInt()) },
                )
                Text(
                    stringResource(R.string.session_options_frame_rate, frameRate.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = frameRate,
                    onValueChange = { frameRate = it },
                    valueRange = 5f..60f,
                    onValueChangeFinished = { onQuality(quality.toInt(), frameRate.toInt()) },
                )
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(onClick = { onQuality(35, 12) }, shape = PillShape) {
                    Text(stringResource(R.string.session_options_preset_saver))
                }
                OutlinedButton(onClick = { onQuality(60, 30) }, shape = PillShape) {
                    Text(stringResource(R.string.session_options_preset_balanced))
                }
                OutlinedButton(onClick = { onQuality(85, 50) }, shape = PillShape) {
                    Text(stringResource(R.string.session_options_preset_sharp))
                }
            }

            SectionLabel(stringResource(R.string.session_options_connection))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onShowHelp, shape = PillShape) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.session_gesture_guide_title))
                }
                OutlinedButton(onClick = onReconnect, shape = PillShape) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.session_options_reconnect))
                }
                OutlinedButton(onClick = onToggleFullscreen, shape = PillShape) {
                    Icon(
                        imageVector = if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (fullscreen) {
                            stringResource(R.string.session_options_leave_fullscreen)
                        } else {
                            stringResource(R.string.session_options_fullscreen)
                        },
                    )
                }
                OutlinedButton(onClick = onHideChrome, shape = PillShape) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.session_action_hide_controls))
                }
            }

            if (ru.papajon.meshcentralmobile.BuildConfig.DEBUG) {
                FilterChip(
                    selected = debugVisible,
                    onClick = { onDebugVisibleChange(!debugVisible) },
                    label = { Text(stringResource(R.string.session_options_debug)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (state.message != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MeshTheme.colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = state.message?.asString().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
