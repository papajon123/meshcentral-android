package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.RemoteViewport
import ru.papajon.meshcentralmobile.mesh.SessionGestureAction
import ru.papajon.meshcentralmobile.mesh.SessionGesturePolicy
import ru.papajon.meshcentralmobile.mesh.TwoFingerAction
import ru.papajon.meshcentralmobile.mesh.StylusInputController
import ru.papajon.meshcentralmobile.ui.screens.session.isPen
import ru.papajon.meshcentralmobile.ui.screens.session.stylusCanvasInput
import ru.papajon.meshcentralmobile.ui.screens.session.transformWithFingers
import ru.papajon.meshcentralmobile.ui.screens.session.rememberStylusHint
import ru.papajon.meshcentralmobile.ui.screens.session.FingerDragCancellation
import ru.papajon.meshcentralmobile.ui.screens.session.hardwareMouseInput
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.components.StatusPill
import ru.papajon.meshcentralmobile.ui.components.connectionStateLabel
import ru.papajon.meshcentralmobile.ui.components.remoteControlModeShortLabel
import ru.papajon.meshcentralmobile.ui.screens.session.ClipboardSheet
import ru.papajon.meshcentralmobile.ui.screens.session.KeyboardSheet
import ru.papajon.meshcentralmobile.ui.screens.session.MouseModeSheet
import ru.papajon.meshcentralmobile.ui.screens.session.NativeKeyboardCapture
import ru.papajon.meshcentralmobile.ui.screens.session.SessionOptionsSheet
import ru.papajon.meshcentralmobile.ui.screens.session.SessionConnectionControls
import ru.papajon.meshcentralmobile.ui.screens.session.SessionTopBar
import ru.papajon.meshcentralmobile.ui.screens.session.SessionRefreshAction
import ru.papajon.meshcentralmobile.ui.screens.session.TextSheet
import ru.papajon.meshcentralmobile.ui.screens.session.PhoneControlPanel
import ru.papajon.meshcentralmobile.ui.screens.session.PhoneSessionCanvas
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape
import ru.papajon.meshcentralmobile.ui.theme.SquircleShape

private enum class SessionSheet { None, Pointer, Keyboard, Text, Clipboard, Options }

/**
 * Live session screen: the remote framebuffer owns the whole surface, and every control lives in a
 * floating layer that can be hidden, so the desktop is never squeezed into a strip.
 */
@Composable
fun RemoteDesktopScreen(
    meshClient: MeshCentralClient,
    onFullscreenChanged: (Boolean) -> Unit,
    onBrowseDevices: () -> Unit,
    modifier: Modifier = Modifier,
    onMinimize: (() -> Unit)? = null,
    onCloseSession: (() -> Unit)? = null,
    customShortcuts: List<ru.papajon.meshcentralmobile.model.CustomShortcut> = emptyList(),
    onAddShortcut: ((ru.papajon.meshcentralmobile.model.CustomShortcut) -> Boolean)? = null,
    onRemoveShortcut: (Int) -> Unit = {},
    shortcutProfileId: String = "",
    stylusPrecisionEnabled: Boolean = true,
    stylusHintSeen: Boolean = false,
    onStylusHintSeen: () -> Unit = {},
) {
    val desktopState by meshClient.desktopState.collectAsState()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var dockVisible by rememberSaveable { mutableStateOf(true) }
    var hintVisible by rememberSaveable { mutableStateOf(false) }
    var debugVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardActive by rememberSaveable { mutableStateOf(false) }
    var dragArmed by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(SessionSheet.None) }
    var mode by rememberSaveable { mutableStateOf(RemoteControlMode.DirectTouch) }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var panX by rememberSaveable { mutableStateOf(0f) }
    var panY by rememberSaveable { mutableStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastGesture by remember { mutableStateOf("idle") }
    var twoFingerAction by rememberSaveable(desktopState.device?.nodeId) { mutableStateOf(TwoFingerAction.Default) }
    var pinchEnabled by rememberSaveable(desktopState.device?.nodeId) { mutableStateOf(true) }
    var hardwareKeyboardCaptured by rememberSaveable { mutableStateOf(true) }
    val pip = ru.papajon.meshcentralmobile.ui.screens.session.LocalDesktopPip.current
    val inPip = pip?.active == true
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayPreferences = remember(context) { ru.papajon.meshcentralmobile.settings.DesktopPreferences(context) }
    var pipAutomatic by remember { mutableStateOf(displayPreferences.loadPipAutomatic()) }
    LaunchedEffect(shortcutProfileId, desktopState.device?.nodeId) {
        desktopState.device?.nodeId?.let { node ->
            displayPreferences.loadDisplay(shortcutProfileId, node)?.let(meshClient::selectRemoteDisplay)
            val saved = displayPreferences.load(shortcutProfileId, node)
            meshClient.setRemoteQuality(saved.jpegQuality, saved.frameRate, saved.scaling)
        }
    }
    fun saveQuality(value: ru.papajon.meshcentralmobile.mesh.DesktopQuality) {
        desktopState.device?.nodeId?.let { displayPreferences.save(shortcutProfileId, it, value) }
        meshClient.setRemoteQuality(value.jpegQuality, value.frameRate, value.scaling)
    }
    fun chooseDisplay(display: Int) {
        desktopState.device?.let { displayPreferences.saveDisplay(shortcutProfileId, it.nodeId, display) }
        meshClient.selectRemoteDisplay(display)
    }
    val pipEligible = desktopState.hasFrame && desktopState.connectionState == ConnectionState.Connected &&
        (sheet == SessionSheet.None || inPip)
    DisposableEffect(pip, pipEligible, pipAutomatic, desktopState.remoteWidth, desktopState.remoteHeight) {
        pip?.configure(pipEligible, pipAutomatic, desktopState.remoteWidth, desktopState.remoteHeight)
        onDispose { pip?.configure(false) }
    }
    val stylusHint = rememberStylusHint(stylusHintSeen, onStylusHintSeen, desktopState.device?.isAndroidAgent == true)
    androidx.compose.runtime.key(desktopState.device?.nodeId, desktopState.remoteWidth, desktopState.remoteHeight) {
    ru.papajon.meshcentralmobile.ui.screens.session.HardwareKeyboardCapture(
        input = meshClient.remoteInputSink,
        isWindows = desktopState.device?.isWindows == true,
        enabled = hardwareKeyboardCaptured && !inPip && desktopState.inputEnabled && desktopState.hasFrame &&
            desktopState.device?.isAndroidAgent != true && sheet == SessionSheet.None,
        onReleaseCapture = { hardwareKeyboardCaptured = false; keyboardActive = false; sheet = SessionSheet.Options },
    )
    }

    BackHandler(enabled = !inPip) {
        when {
            sheet != SessionSheet.None -> sheet = SessionSheet.None
            keyboardActive -> keyboardActive = false
            fullscreen -> fullscreen = false
            else -> if (onMinimize != null) onMinimize() else { meshClient.disconnectDesktop(); onBrowseDevices() }
        }
    }

    DisposableEffect(fullscreen) {
        onFullscreenChanged(fullscreen)
        onDispose { onFullscreenChanged(false) }
    }

    if (inPip) {
        ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipPreview(desktopState, modifier)
        return
    }

    val device = desktopState.device
    if (device == null) {
        Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.DesktopWindows,
                title = stringResource(R.string.session_empty_title),
                description = stringResource(R.string.session_empty_description),
                actionLabel = stringResource(R.string.session_empty_action),
                onAction = onBrowseDevices,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
        return
    }

    // Phone-agent sessions do not keep desktop keyboard/drag controls active.
    LaunchedEffect(device.nodeId, device.isAndroidAgent) {
        if (device.isAndroidAgent) {
            keyboardActive = false
            dragArmed = false
            hintVisible = false
            sheet = SessionSheet.None
        }
    }

    val debugDetails = if (debugVisible && ru.papajon.meshcentralmobile.BuildConfig.DEBUG)
        sessionDebugText(canvasSize, desktopState, zoom, panX, panY, lastGesture) else null

    Box(
        modifier = modifier
            .testTag(if (fullscreen) "remote-desktop-fullscreen" else "remote-desktop")
            .fillMaxSize()
            .background(MeshTheme.colors.canvas),
    ) {
        if (device.isAndroidAgent) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (chromeVisible) SessionTopBar(desktopState, debugDetails = debugDetails, onMinimize = onMinimize,
                    onBack = { if (onCloseSession != null) onCloseSession() else { meshClient.disconnectDesktop(); onBrowseDevices() } },
                    onSelectDisplay = ::chooseDisplay,
                    onRefresh = { meshClient.requestRemoteRefresh() }, onReconnect = meshClient::reconnectDesktop,
                    onHideChrome = { chromeVisible = false })
                else TextButton(onClick = { chromeVisible = true }) { Text(stringResource(R.string.session_action_show_controls)) }
                ru.papajon.meshcentralmobile.ui.screens.session.PhoneViewOnlyNotice(device, Modifier.padding(8.dp))
                PhoneSessionCanvas(desktopState, zoom, panX, panY,
                    onViewportChanged = { z, x, y -> zoom = z; panX = x; panY = y },
                    onCanvasSizeChanged = { canvasSize = it }, input = meshClient.remoteInputSink,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    stylusPrecisionEnabled = stylusPrecisionEnabled, onStylusUsed = stylusHint.onUse,
                    twoFingerAction = twoFingerAction, pinchEnabled = pinchEnabled)
                PhoneControlPanel(desktopState, meshClient.remoteInputSink,
                    Modifier.heightIn(max = 176.dp).verticalScroll(rememberScrollState()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) {
                        Text(stringResource(R.string.common_action_fit_to_screen))
                    }
                    TextButton(onClick = { sheet = SessionSheet.Options }) { Text(stringResource(R.string.session_dock_more)) }
                }
            }
        } else SessionCanvas(
            desktopState = desktopState,
            mode = if (device.supportsRemoteInput) mode else RemoteControlMode.PanZoom,
            dragArmed = dragArmed,
            onDragConsumed = { dragArmed = false },
            zoom = zoom,
            panX = panX,
            panY = panY,
            onViewportChanged = { newZoom, newPanX, newPanY ->
                zoom = newZoom
                panX = newPanX
                panY = newPanY
            },
            onCanvasSizeChanged = { canvasSize = it },
            onGestureAction = { lastGesture = it.name },
            inputSink = meshClient.remoteInputSink,
            modifier = Modifier.fillMaxSize(),
            stylusPrecisionEnabled = stylusPrecisionEnabled,
            onStylusUsed = stylusHint.onUse,
            twoFingerAction = twoFingerAction,
            pinchEnabled = pinchEnabled,
        )

        if (!desktopState.hasFrame || desktopState.connectionState == ConnectionState.Reconnecting) {
            ConnectionOverlay(
                state = desktopState,
                onRetry = meshClient::reconnectDesktop,
                onCancel = {
                    if (onCloseSession != null) onCloseSession() else { meshClient.disconnectDesktop(); onBrowseDevices() }
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!device.isAndroidAgent) Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            ru.papajon.meshcentralmobile.ui.screens.session.PhoneViewOnlyNotice(device, Modifier.padding(8.dp))
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(animationSpec = MeshTheme.motion.effectDefault) +
                    slideInVertically(animationSpec = MeshTheme.motion.spatialDefault) { -it / 2 },
                exit = fadeOut(animationSpec = MeshTheme.motion.effectFast) +
                    slideOutVertically(animationSpec = MeshTheme.motion.spatialFast) { -it / 2 },
            ) {
                SessionTopBar(
                    state = desktopState,
                    debugDetails = debugDetails,
                    onMinimize = onMinimize,
                    onBack = {
                        if (onCloseSession != null) onCloseSession() else { meshClient.disconnectDesktop(); onBrowseDevices() }
                    },
                    onSelectDisplay = ::chooseDisplay,
                    onRefresh = { meshClient.requestRemoteRefresh() },
                    onReconnect = meshClient::reconnectDesktop,
                    onHideChrome = { chromeVisible = false },
                )
            }

            if (!chromeVisible) Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { chromeVisible = true }, shape = PillShape) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.session_action_show_controls))
                }
                Spacer(Modifier.weight(1f))
                SessionConnectionControls(
                    state = desktopState,
                    onRefresh = { meshClient.requestRemoteRefresh() },
                    onReconnect = meshClient::reconnectDesktop,
                    modifier = Modifier.widthIn(max = 244.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = !device.isAndroidAgent && chromeVisible && dockVisible,
            enter = fadeIn(animationSpec = MeshTheme.motion.effectDefault) +
                slideInVertically(animationSpec = MeshTheme.motion.spatialDefault) { it / 2 },
            exit = fadeOut(animationSpec = MeshTheme.motion.effectFast) +
                slideOutVertically(animationSpec = MeshTheme.motion.spatialFast) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SessionDock(
                    state = desktopState,
                    mode = mode,
                    dragArmed = dragArmed,
                    keyboardActive = keyboardActive,
                    onModeClick = { sheet = SessionSheet.Pointer },
                    onKeyboardToggle = { keyboardActive = !keyboardActive },
                    onKeys = { sheet = SessionSheet.Keyboard },
                    onClipboard = { sheet = SessionSheet.Clipboard },
                    onOptions = { sheet = SessionSheet.Options },
                    onHideDock = { dockVisible = false },
                )
            }
        }

        if (!device.isAndroidAgent && chromeVisible && !dockVisible) {
            FilledTonalIconButton(
                onClick = { dockVisible = true },
                shape = PillShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 6.dp),
            ) {
                Icon(Icons.Outlined.ArrowDropUp, contentDescription = stringResource(R.string.session_dock_show))
            }
        }

        AnimatedVisibility(
            visible = hintVisible && desktopState.hasFrame,
            enter = fadeIn(animationSpec = MeshTheme.motion.effectDefault),
            exit = fadeOut(animationSpec = MeshTheme.motion.effectFast),
            modifier = Modifier.align(Alignment.Center),
        ) {
            GestureGuide(onDismiss = { hintVisible = false })
        }

        // Invisible text field that turns the phone keyboard into remote key packets.
        NativeKeyboardCapture(
            input = meshClient.remoteInputSink,
            active = !device.isAndroidAgent && keyboardActive && desktopState.inputEnabled,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        androidx.compose.material3.SnackbarHost(stylusHint.host,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }

    when (sheet) {
        SessionSheet.None -> Unit
        SessionSheet.Pointer -> MouseModeSheet(
            current = mode,
            onSelect = {
                mode = it
                dragArmed = false
                if (it == RemoteControlMode.Scroll) dockVisible = false
                sheet = SessionSheet.None
            },
            onDoubleClick = {
                val center = currentRemoteCentre(desktopState)
                meshClient.sendRemoteDoubleClick(center.first, center.second)
            },
            onArmDrag = {
                dragArmed = true
                sheet = SessionSheet.None
            },
            onClose = { sheet = SessionSheet.None },
        )

        SessionSheet.Keyboard -> androidx.compose.runtime.key(shortcutProfileId) { KeyboardSheet(
            input = meshClient.remoteInputSink,
            onClose = { sheet = SessionSheet.None },
            onOpenTextSheet = { sheet = SessionSheet.Text },
            customShortcuts = customShortcuts,
            onAddShortcut = onAddShortcut,
            onRemoveShortcut = onRemoveShortcut,
        ) }

        SessionSheet.Text -> TextSheet(
            input = meshClient.remoteInputSink,
            onClose = { sheet = SessionSheet.None },
        )

        SessionSheet.Clipboard -> ClipboardSheet(
            meshClient = meshClient,
            device = device,
            onClose = { sheet = SessionSheet.None },
        )

        SessionSheet.Options -> SessionOptionsSheet(
            state = desktopState,
            fullscreen = fullscreen,
            zoom = zoom,
            onZoomIn = {
                zoom = (zoom * 1.25f).coerceAtMost(RemoteViewport.MAX_ZOOM)
                panX = 0f
                panY = 0f
            },
            onZoomOut = {
                zoom = (zoom / 1.25f).coerceAtLeast(1f)
                if (zoom <= 1f) { panX = 0f; panY = 0f }
            },
            onResetZoom = { zoom = 1f; panX = 0f; panY = 0f },
            debugVisible = debugVisible,
            onDebugVisibleChange = { debugVisible = it },
            onQuality = { quality, frames -> saveQuality(desktopState.quality.copy(jpegQuality = quality, frameRate = frames)) },
            onScaling = { saveQuality(desktopState.quality.copy(scaling = it)) },
            twoFingerAction = twoFingerAction,
            onTwoFingerAction = { twoFingerAction = it },
            pinchEnabled = pinchEnabled,
            onPinchEnabled = { pinchEnabled = it },
            hardwareKeyboardCaptured = hardwareKeyboardCaptured,
            onHardwareKeyboardCaptured = { hardwareKeyboardCaptured = it },
            pipSupported = pip?.supported == true,
            pipAutomatic = pipAutomatic,
            onPipAutomatic = { pipAutomatic = it; displayPreferences.savePipAutomatic(it) },
            onEnterPip = {
                sheet = SessionSheet.None
                keyboardActive = false
                pip?.configure(desktopState.hasFrame, pipAutomatic, desktopState.remoteWidth, desktopState.remoteHeight)
                pip?.enter()
            },
            onReconnect = { meshClient.reconnectDesktop() },
            onShowHelp = {
                sheet = SessionSheet.None
                hintVisible = true
            },
            onToggleFullscreen = { fullscreen = !fullscreen },
            onHideChrome = {
                chromeVisible = false
                sheet = SessionSheet.None
            },
            onClose = { sheet = SessionSheet.None },
        )
    }
}

private fun currentRemoteCentre(state: RemoteDesktopState): Pair<Int, Int> {    val width = state.remoteWidth.coerceAtLeast(2)
    val height = state.remoteHeight.coerceAtLeast(2)
    return width / 2 to height / 2
}

/**
 * Gesture guide shown once per session: the touch model is not guessable, and a user who cannot move
 * around the desktop thinks the app is broken.
 */
@Composable
private fun GestureGuide(onDismiss: () -> Unit) {
    MeshCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        container = MeshTheme.colors.glass,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.session_gesture_guide_title), style = MaterialTheme.typography.titleSmall)
            GuideRow(
                stringResource(R.string.session_gesture_one_finger),
                stringResource(R.string.session_gesture_one_finger_meaning),
            )
            GuideRow(
                stringResource(R.string.session_gesture_long_press),
                stringResource(R.string.session_gesture_long_press_meaning),
            )
            GuideRow(
                stringResource(R.string.session_gesture_two_fingers),
                stringResource(R.string.session_gesture_two_fingers_meaning),
            )
            GuideRow(
                stringResource(R.string.session_gesture_pinch),
                stringResource(R.string.session_gesture_pinch_meaning),
            )
            GuideRow(
                stringResource(R.string.session_gesture_two_finger_tap),
                stringResource(R.string.session_gesture_two_finger_tap_meaning),
            )
            GuideRow(
                stringResource(R.string.session_gesture_double_tap),
                stringResource(R.string.session_gesture_double_tap_meaning),
            )
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.common_action_got_it))
            }
        }
    }
}

@Composable
private fun GuideRow(gesture: String, meaning: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = gesture,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = meaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Small text shown above the dock in debug builds with the live canvas geometry. */
@Composable
private fun sessionDebugText(
    canvasSize: IntSize,
    state: RemoteDesktopState,
    zoom: Float,
    panX: Float,
    panY: Float,
    lastGesture: String,
): String {
    val viewport = RemoteViewport(
        canvasWidth = canvasSize.width,
        canvasHeight = canvasSize.height,
        remoteWidth = state.remoteWidth,
        remoteHeight = state.remoteHeight,
        zoom = zoom,
        panX = panX,
        panY = panY,
    )
    return stringResource(
            R.string.session_debug_line,
            canvasSize.width,
            canvasSize.height,
            viewport.drawnWidth.toInt(),
            viewport.drawnHeight.toInt(),
            viewport.left.toInt(),
            viewport.top.toInt(),
            (zoom * 100).toInt(),
            gestureLabel(lastGesture),
        )
}

/**
 * The session reports the gesture it recognised by enum name; the debug line shows the same event
 * in words, and an unknown name stays as it is.
 */
@Composable
private fun gestureLabel(name: String): String = when (name) {
    "idle", "Pending" -> stringResource(R.string.session_debug_gesture_idle)
    "LeftClick" -> stringResource(R.string.session_debug_gesture_click)
    "DoubleClick" -> stringResource(R.string.session_debug_gesture_double_click)
    "ViewPan" -> stringResource(R.string.session_debug_gesture_pan)
    "PointerMove" -> stringResource(R.string.session_debug_gesture_move)
    "DragRemote" -> stringResource(R.string.session_debug_gesture_drag)
    "RemoteScroll" -> stringResource(R.string.session_debug_gesture_scroll)
    "RightClick" -> stringResource(R.string.session_debug_gesture_right_click)
    else -> name
}

@Composable
internal fun SessionDock(
    state: RemoteDesktopState,
    mode: RemoteControlMode,
    dragArmed: Boolean,
    keyboardActive: Boolean,
    onModeClick: () -> Unit,
    onKeyboardToggle: () -> Unit,
    onKeys: () -> Unit,
    onClipboard: () -> Unit,
    onOptions: () -> Unit,
    onHideDock: () -> Unit,
) {
    val enabled = state.inputEnabled
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("session-dock"),
        shape = MaterialTheme.shapes.large,
        color = MeshTheme.colors.glass,
        tonalElevation = 2.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = if (state.device?.isAndroidAgent == true) 2 else 6
        val actionWidth = maxOf(48.dp, (maxWidth - 8.dp) / count)
        Row(
            modifier = Modifier
                .fillMaxWidth().height(56.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.device?.isAndroidAgent != true) {
            DockAction(
                icon = when (mode) {
                    RemoteControlMode.DirectTouch -> Icons.Outlined.TouchApp
                    RemoteControlMode.Trackpad -> Icons.Outlined.Mouse
                    RemoteControlMode.PanZoom -> Icons.Outlined.FitScreen
                    RemoteControlMode.Scroll -> Icons.Outlined.SwapVert
                },
                label = if (dragArmed) stringResource(R.string.session_pointer_drag_armed)
                    else remoteControlModeShortLabel(mode),
                enabled = true,
                onClick = onModeClick,
                highlighted = dragArmed,
                modifier = Modifier.width(actionWidth),
            )
            DockAction(
                Icons.Outlined.Keyboard,
                stringResource(R.string.session_dock_typing),
                enabled,
                onKeyboardToggle,
                highlighted = keyboardActive,
                modifier = Modifier.width(actionWidth),
            )
            DockAction(
                Icons.Outlined.KeyboardAlt,
                stringResource(R.string.session_dock_keys),
                enabled,
                onKeys,
                modifier = Modifier.width(actionWidth),
            )
            DockAction(
                Icons.Outlined.ContentPaste,
                stringResource(R.string.session_dock_clipboard),
                state.connectionState == ConnectionState.Connected,
                onClipboard,
                modifier = Modifier.width(actionWidth),
            )
            }
            DockAction(Icons.Outlined.MoreHoriz, stringResource(R.string.session_dock_more), true, onOptions,
                modifier = Modifier.width(actionWidth))
            DockAction(Icons.Outlined.ArrowDropDown, stringResource(R.string.session_dock_hide), true, onHideDock,
                modifier = Modifier.width(actionWidth))
        }
    }
    }
}

@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = MeshTheme.motion.effectDefault,
        label = "dock-alpha",
    )
    val tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .height(48.dp)
            .clickable(role = Role.Button, interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .semantics { selected = highlighted }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Connection progress with the real protocol stages, so waiting looks like progress, not a freeze. */
@Composable
internal fun ConnectionOverlay(
    state: RemoteDesktopState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stages = buildList {
        add(stringResource(R.string.session_stage_control_channel) to true)
        add(
            stringResource(R.string.session_stage_tunnel_requested) to
                (state.connectionState != ConnectionState.LoadingRemoteDesktop),
        )
        add(
            stringResource(R.string.session_stage_relay) to
                (state.connectionState == ConnectionState.Connected || state.handshakeComplete),
        )
        add(stringResource(R.string.session_stage_kvm) to state.handshakeComplete)
        add(stringResource(R.string.session_stage_first_frame) to state.hasFrame)
    }
    val failed = state.connectionState == ConnectionState.Failed

    MeshCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(
                    text = state.message?.asString()
                        ?: stringResource(R.string.session_failed_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                MeshLoadingIndicator()
                Text(
                    text = state.device?.name?.let { stringResource(R.string.session_connecting_to, it) }
                        ?: stringResource(R.string.session_connecting_to_computer),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stages.forEach { (label, done) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(PillShape)
                                .background(
                                    if (done) MeshTheme.colors.online else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            if (state.connectionState == ConnectionState.Reconnecting) {
                Text(
                    text = state.message?.asString() ?: stringResource(R.string.session_reconnect_attempt, state.attempt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshTheme.colors.warning,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed || state.connectionState == ConnectionState.Reconnecting) {
                    androidx.compose.material3.Button(onClick = onRetry, shape = PillShape) {
                        Text(stringResource(R.string.common_action_retry))
                    }
                }
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        }
    }
}

@Composable
internal fun SessionCanvas(
    desktopState: RemoteDesktopState,
    mode: RemoteControlMode,
    dragArmed: Boolean,
    onDragConsumed: () -> Unit,
    zoom: Float,
    panX: Float,
    panY: Float,
    onViewportChanged: (Float, Float, Float) -> Unit,
    onCanvasSizeChanged: (IntSize) -> Unit,
    onGestureAction: (SessionGestureAction) -> Unit = {},
    inputSink: NativeRemoteInputSink,
    modifier: Modifier = Modifier,
    stylusPrecisionEnabled: Boolean = true,
    onStylusUsed: () -> Unit = {},
    twoFingerAction: TwoFingerAction = TwoFingerAction.Default,
    pinchEnabled: Boolean = true,
) {
    if (desktopState.device?.isAndroidAgent == true) {
        PhoneSessionCanvas(desktopState, zoom, panX, panY, onViewportChanged, onCanvasSizeChanged, inputSink, modifier,
            stylusPrecisionEnabled, onStylusUsed, twoFingerAction, pinchEnabled)
        return
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var trackpadCursor by remember { mutableStateOf(Offset.Zero) }
    // Last remote pointer position we sent, drawn as a cursor because the agent does not paint one.
    var remoteCursor by remember { mutableStateOf(Offset.Unspecified) }
    // Double-tap detection state, kept across gestures.
    var lastTapUpMillis by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val viewport = RemoteViewport(
        canvasWidth = canvasSize.width,
        canvasHeight = canvasSize.height,
        remoteWidth = desktopState.remoteWidth,
        remoteHeight = desktopState.remoteHeight,
        zoom = zoom,
        panX = panX,
        panY = panY,
        fitWidth = desktopState.device?.supportsRemoteInput == false,
    )
    val currentViewport by rememberUpdatedState(viewport)
    val currentDragArmed by rememberUpdatedState(dragArmed)
    val currentOnDragConsumed by rememberUpdatedState(onDragConsumed)
    val currentInputEnabled by rememberUpdatedState(desktopState.inputEnabled)
    val currentHasFrame by rememberUpdatedState(desktopState.hasFrame)
    val stylus = remember(inputSink, desktopState.device?.nodeId, desktopState.attempt,
        desktopState.remoteWidth, desktopState.remoteHeight) {
        StylusInputController(inputSink, onCursor = { x, y ->
            remoteCursor = Offset(x.toFloat(), y.toFloat())
            val canvasPoint = currentViewport.toCanvas(x, y)
            trackpadCursor = Offset(canvasPoint.first, canvasPoint.second)
        }, nowMillis = android.os.SystemClock::uptimeMillis)
    }
    val fingerDragCancellation = remember { FingerDragCancellation() }
    val touchpadGestureCancellation = remember { FingerDragCancellation() }
    val penInput = stylusCanvasInput(stylus, stylusPrecisionEnabled,
        desktopState.inputEnabled && desktopState.hasFrame, viewport, onStylusUsed, fingerDragCancellation::cancel)
    val mouseInput = Modifier.hardwareMouseInput(inputSink, viewport, desktopState.inputEnabled && desktopState.hasFrame,
        onNativeGestureStart = touchpadGestureCancellation::cancel,
        onPinch = { x, y, factor ->
            if (pinchEnabled && currentHasFrame) {
                val result = currentViewport.zoomAround(x, y, SessionGesturePolicy.nextZoom(currentViewport.zoom, factor))
                onViewportChanged(result.zoom, result.panX, result.panY)
            }
        }) { x, y ->
        remoteCursor = Offset(x.toFloat(), y.toFloat())
        val point = currentViewport.toCanvas(x, y)
        trackpadCursor = Offset(point.first, point.second)
    }

    LaunchedEffect(canvasSize, desktopState.remoteWidth, desktopState.remoteHeight) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            trackpadCursor = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged {
                canvasSize = it
                onCanvasSizeChanged(it)
            }
            .then(penInput)
            .then(mouseInput)
            .pointerInput(mode, stylus, stylusPrecisionEnabled, twoFingerAction, pinchEnabled, desktopState.inputEnabled) {
                coroutineScope {
                    val gestureScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Only a down already claimed by the hardware mouse branch is skipped. A
                        // buttonless finger contact (even with source mouse/touchpad) stays here.
                        if (down.isConsumed && !down.isPen() && !stylus.blocksFingerInput) return@awaitEachGesture
                        if (!currentHasFrame) return@awaitEachGesture
                        var penOrPalm = stylusPrecisionEnabled && (down.isPen() || stylus.blocksFingerInput)

                        val longPressReached = java.util.concurrent.atomic.AtomicBoolean(false)
                        val slopPx = with(density) {
                            (if (mode == RemoteControlMode.Scroll) 6f else SessionGesturePolicy.TAP_SLOP_DP).dp.toPx()
                        }
                        val pressStartedAt = android.os.SystemClock.uptimeMillis()
                        val downPosition = down.position
                        var lastPosition = downPosition
                        var movedBeyondSlop = false
                        var rightClickSent = false
                        var viewPanning = false
                        var multiTouch = false
                        var maxPointers = 1
                        var multiTouchAction = SessionGestureAction.Pending
                        var totalPanX = 0f
                        var totalPanY = 0f
                        var totalZoom = 1f
                        var multiTouchMoved = false
                        var scrolledBy = 0f
                        var remoteDragging = false
                        var lastReportedAction: SessionGestureAction? = null
                        val releaseFingerDrag = {
                            if (remoteDragging) {
                                val remote = currentViewport.toRemote(lastPosition.x, lastPosition.y)
                                inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, false)
                                remoteDragging = false
                            }
                        }
                        fingerDragCancellation.release = releaseFingerDrag
                        if (!penOrPalm && currentDragArmed && currentInputEnabled &&
                            currentViewport.hitsImage(downPosition.x, downPosition.y)
                        ) {
                            val remote = currentViewport.toRemote(downPosition.x, downPosition.y)
                            if (inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, down = true)) {
                                remoteDragging = true
                                remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                currentOnDragConsumed()
                                onGestureAction(SessionGestureAction.DragRemote)
                            }
                        }
                        // The right click happens while the finger is still down. Small movements
                        // within the tap tolerance do not cancel it.
                        val longPressTimer = gestureScope.launch {
                            delay(SessionGesturePolicy.LONG_PRESS_MILLIS)
                            longPressReached.set(true)
                            val distanceDp = (lastPosition - downPosition).getDistance() / density.density
                            if (!penOrPalm && !(stylusPrecisionEnabled && stylus.blocksFingerInput) &&
                                !remoteDragging && !multiTouch && !viewPanning &&
                                SessionGesturePolicy.onHold(mode, distanceDp) == SessionGestureAction.RightClick &&
                                currentInputEnabled
                            ) {
                                val point = if (mode == RemoteControlMode.Trackpad) trackpadCursor else downPosition
                                val viewportAtHold = currentViewport
                                if (viewportAtHold.hitsImage(point.x, point.y)) {
                                    val remote = viewportAtHold.toRemote(point.x, point.y)
                                    rightClickSent = inputSink.sendMouseClick(remote.first, remote.second, MouseButton.Right)
                                    if (rightClickSent) {
                                        remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                        lastTapUpMillis = 0L
                                        onGestureAction(SessionGestureAction.RightClick)
                                    }
                                }
                            }
                        }

                        val cancelForTouchpad = {
                            multiTouch = true
                            multiTouchMoved = true
                            longPressTimer.cancel()
                            releaseFingerDrag()
                        }
                        touchpadGestureCancellation.release = cancelForTouchpad
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                penOrPalm = penOrPalm || (stylusPrecisionEnabled &&
                                    (stylus.blocksFingerInput || event.changes.any { it.isPen() }))
                                if (penOrPalm) {
                                    longPressTimer.cancel()
                                    lastTapUpMillis = 0L
                                    if (remoteDragging) {
                                        val remote = currentViewport.toRemote(lastPosition.x, lastPosition.y)
                                        inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, false)
                                        remoteDragging = false
                                    }
                                    transformWithFingers(event, currentViewport, onViewportChanged, pinchEnabled)
                                    event.changes.forEach { it.consume() }
                                    if (pressedCount == 0) break
                                    continue
                                }
                                // A native touchpad gesture can be classified after DOWN. Once the
                                // Initial mouse/axis handler claims it, cancel this pending tap/drag.
                                if (event.changes.any { it.isConsumed }) {
                                    multiTouch = true
                                    multiTouchMoved = true
                                    longPressTimer.cancel()
                                    if (remoteDragging) {
                                        val remote = currentViewport.toRemote(lastPosition.x, lastPosition.y)
                                        inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, false)
                                        remoteDragging = false
                                    }
                                    if (pressedCount == 0) break
                                    continue
                                }
                                if (pressedCount == 0) {
                                    if (remoteDragging) lastPosition = event.changes.firstOrNull()?.position ?: lastPosition
                                    break
                                }

                                if (pressedCount >= 2) {
                                    if (pressedCount > maxPointers) {
                                        if (pressedCount >= 3) {
                                            multiTouchAction = SessionGestureAction.Pending
                                            totalPanX = 0f; totalPanY = 0f; totalZoom = 1f
                                            scrolledBy = 0f
                                        }
                                        maxPointers = pressedCount
                                    }
                                    if (remoteDragging && currentInputEnabled) {
                                        val remote = currentViewport.toRemote(lastPosition.x, lastPosition.y)
                                        inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, down = false)
                                        remoteDragging = false
                                        viewPanning = true
                                    }
                                    multiTouch = true
                                    longPressTimer.cancel()
                                    lastTapUpMillis = 0L
                                    val panChange = event.calculatePan()
                                    val zoomChange = if (maxPointers < 3) event.calculateZoom() else 1f
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    totalPanX += panChange.x
                                    totalPanY += panChange.y
                                    totalZoom *= zoomChange
                                    multiTouchMoved = multiTouchMoved || SessionGesturePolicy.multiTouchMoved(
                                        totalPanX / density.density, totalPanY / density.density, totalZoom)
                                    val newlyDecided = multiTouchAction == SessionGestureAction.Pending
                                    if (newlyDecided) {
                                        multiTouchAction = SessionGesturePolicy.onMultiTouch(
                                            mode = mode,
                                            totalPanXDp = totalPanX / density.density,
                                            totalPanYDp = totalPanY / density.density,
                                            totalZoom = totalZoom,
                                            pointerCount = maxPointers,
                                            twoFingerAction = twoFingerAction,
                                            pinchEnabled = pinchEnabled,
                                        )
                                        if (multiTouchAction != SessionGestureAction.Pending) {
                                            viewPanning = true
                                            onGestureAction(multiTouchAction)
                                        }
                                    }
                                    // A three-finger contact must never become a right click on release.
                                    if (maxPointers >= 3) viewPanning = true
                                    when (multiTouchAction) {
                                        SessionGestureAction.RemoteScroll -> {
                                            scrolledBy += if (newlyDecided) totalPanY else panChange.y
                                            if (currentInputEnabled && abs(scrolledBy) > 6f) {
                                                val cursor = if (mode == RemoteControlMode.Trackpad) trackpadCursor else centroid
                                                val remote = currentViewport.toRemote(cursor.x, cursor.y)
                                                inputSink.sendMouseScroll(remote.first, remote.second, -scrolledBy * 1.5f)
                                                scrolledBy = 0f
                                            }
                                        }

                                        SessionGestureAction.ViewPan -> {
                                            val base = currentViewport
                                            val zoomed = base.zoomAround(
                                                focusX = centroid.x,
                                                focusY = centroid.y,
                                                requestedZoom = SessionGesturePolicy.nextZoom(
                                                    base.zoom,
                                                    if (!pinchEnabled) 1f else if (newlyDecided) totalZoom else zoomChange,
                                                ),
                                            )
                                            val clamped = base
                                                .copy(zoom = zoomed.zoom)
                                                .clampPan(
                                                    zoomed.panX + if (newlyDecided) totalPanX else panChange.x,
                                                    zoomed.panY + if (newlyDecided) totalPanY else panChange.y,
                                                )
                                            onViewportChanged(zoomed.zoom, clamped.first, clamped.second)
                                        }
                                        else -> Unit
                                    }
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                // The last finger of a two-finger gesture must not move the pointer.
                                if (multiTouch) {
                                    event.changes.forEach { it.consume() }
                                    continue
                                }

                                val change = event.changes.first()
                                val delta = change.position - lastPosition
                                lastPosition = change.position
                                if (remoteDragging) {
                                    val remote = currentViewport.toRemote(change.position.x, change.position.y)
                                    remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                    inputSink.sendMouseMove(remote.first, remote.second)
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                if (rightClickSent) {
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                if ((change.position - downPosition).getDistance() > slopPx) {
                                    movedBeyondSlop = true
                                    // A deliberate move pans the view; it cannot become a right click.
                                    longPressTimer.cancel()
                                }

                                val action = SessionGesturePolicy.onSinglePointerMove(
                                    mode = mode,
                                    movedBeyondSlop = movedBeyondSlop,
                                    viewPanning = viewPanning,
                                )
                                if (action != lastReportedAction) {
                                    lastReportedAction = action
                                    onGestureAction(action)
                                }
                                when (action) {
                                    SessionGestureAction.ViewPan -> {
                                        viewPanning = true
                                        // Read the live viewport: the gesture lambda is created once
                                        // per mode, so captured pan values would be stale.
                                        val base = currentViewport
                                        val clamped = base.clampPan(base.panX + delta.x, base.panY + delta.y)
                                        onViewportChanged(base.zoom, clamped.first, clamped.second)
                                    }

                                    SessionGestureAction.PointerMove -> when (mode) {
                                        RemoteControlMode.DirectTouch -> if (currentInputEnabled) {
                                            val remote = currentViewport.toRemote(change.position.x, change.position.y)
                                            remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                            inputSink.sendMouseMove(remote.first, remote.second)
                                        }

                                        RemoteControlMode.Trackpad -> {
                                            trackpadCursor = Offset(
                                                x = (trackpadCursor.x + delta.x).coerceIn(0f, canvasSize.width.toFloat()),
                                                y = (trackpadCursor.y + delta.y).coerceIn(0f, canvasSize.height.toFloat()),
                                            )
                                            if (currentInputEnabled) {
                                                val remote = currentViewport.toRemote(trackpadCursor.x, trackpadCursor.y)
                                                remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                                inputSink.sendMouseMove(remote.first, remote.second)
                                            }
                                        }

                                        RemoteControlMode.PanZoom -> Unit
                                        RemoteControlMode.Scroll -> Unit
                                    }

                                    SessionGestureAction.RemoteScroll -> {
                                        scrolledBy += delta.y
                                        if (currentInputEnabled && abs(scrolledBy) > 6f) {
                                            val remote = currentViewport.toRemote(change.position.x, change.position.y)
                                            inputSink.sendMouseScroll(remote.first, remote.second, -scrolledBy * 1.5f)
                                            scrolledBy = 0f
                                        }
                                    }

                                    else -> Unit
                                }
                                event.changes.forEach { it.consume() }
                            }

                            if (penOrPalm) return@awaitEachGesture
                            if (remoteDragging) {
                                val remote = currentViewport.toRemote(lastPosition.x, lastPosition.y)
                                inputSink.sendMouseButton(remote.first, remote.second, MouseButton.Left, down = false)
                                remoteDragging = false
                                return@awaitEachGesture
                            }
                            val upAction = SessionGesturePolicy.onUp(
                                mode = mode,
                                movedBeyondSlop = movedBeyondSlop,
                                longPressReached = longPressReached.get() ||
                                    (!movedBeyondSlop && android.os.SystemClock.uptimeMillis() - pressStartedAt >=
                                        SessionGesturePolicy.LONG_PRESS_MILLIS),
                                wasMultiTouch = multiTouch,
                                viewPanning = viewPanning,
                                rightClickSent = rightClickSent,
                                multiTouchMoved = multiTouchMoved,
                            )
                            if (upAction != SessionGestureAction.LeftClick) lastTapUpMillis = 0L
                            val viewportAtUp = currentViewport
                            when (upAction) {
                                SessionGestureAction.LeftClick,
                                SessionGestureAction.DoubleClick,
                                SessionGestureAction.RightClick,
                                -> if (currentInputEnabled) {
                                    val point = when {
                                        mode == RemoteControlMode.Trackpad -> trackpadCursor
                                        multiTouch -> downPosition
                                        else -> downPosition
                                    }
                                    // A tap outside the picture must not click the nearest edge.
                                    if (!viewportAtUp.hitsImage(point.x, point.y)) {
                                        lastReportedAction = null
                                        return@awaitEachGesture
                                    }
                                    val remote = viewportAtUp.toRemote(point.x, point.y)
                                    remoteCursor = Offset(remote.first.toFloat(), remote.second.toFloat())
                                    val button = when (upAction) {
                                        SessionGestureAction.RightClick -> MouseButton.Right
                                        else -> MouseButton.Left
                                    }
                                    val now = System.currentTimeMillis()
                                    val doubleClick = upAction == SessionGestureAction.LeftClick &&
                                        !multiTouch &&
                                        SessionGesturePolicy.isDoubleTap(lastTapUpMillis, now) &&
                                        (downPosition - lastTapPosition).getDistance() <= slopPx * 2
                                    if (doubleClick) {
                                        inputSink.sendMouseDoubleClick(remote.first, remote.second)
                                        lastTapUpMillis = 0L
                                    } else {
                                        inputSink.sendMouseClick(remote.first, remote.second, button)
                                        if (upAction == SessionGestureAction.LeftClick && !multiTouch) {
                                            lastTapUpMillis = now
                                            lastTapPosition = downPosition
                                        }
                                    }
                                }

                                else -> Unit
                            }
                        } finally {
                            longPressTimer.cancel()
                            releaseFingerDrag()
                            if (fingerDragCancellation.release === releaseFingerDrag) fingerDragCancellation.release = null
                            if (touchpadGestureCancellation.release === cancelForTouchpad) touchpadGestureCancellation.release = null
                        }
                    }
                }
            },
    ) {
        val frame = desktopState.framebuffer ?: return@Canvas
        val image = frame.asImageBitmap()
        val drawWidth = currentViewport.drawnWidth.roundToInt().coerceAtLeast(1)
        val drawHeight = currentViewport.drawnHeight.roundToInt().coerceAtLeast(1)
        drawImage(
            image = image,
            dstOffset = IntOffset(currentViewport.left.roundToInt(), currentViewport.top.roundToInt()),
            dstSize = IntSize(drawWidth, drawHeight),
        )
        if (remoteCursor != Offset.Unspecified && desktopState.hasFrame) {
            val (cursorX, cursorY) = currentViewport.toCanvas(remoteCursor.x.toInt(), remoteCursor.y.toInt())
            drawRemoteCursor(Offset(cursorX, cursorY))
        }
    }
}

/**
 * Draws the remote pointer.
 *
 * MeshCentral only sends the cursor *shape* index (command 88) and never its position, and the agent
 * does not paint the cursor into the tiles, so a client that wants to show a pointer has to draw one
 * where it last sent the mouse. A white arrow with a dark outline stays visible on any wallpaper.
 */
private fun DrawScope.drawRemoteCursor(position: Offset) {
    val height = 24.dp.toPx()
    val width = 18.dp.toPx()
    val path = Path().apply {
        moveTo(position.x, position.y)
        lineTo(position.x, position.y + height)
        lineTo(position.x + width * 0.30f, position.y + height * 0.74f)
        lineTo(position.x + width * 0.48f, position.y + height * 1.06f)
        lineTo(position.x + width * 0.68f, position.y + height * 0.98f)
        lineTo(position.x + width * 0.50f, position.y + height * 0.66f)
        lineTo(position.x + width * 0.86f, position.y + height * 0.66f)
        close()
    }
    drawPath(path = path, color = Color.White)
    drawPath(path = path, color = Color(0xFF101418), style = Stroke(width = 1.6.dp.toPx()))
}

/** MeshCentral display id that means "the whole virtual desktop". */
private const val ALL_DISPLAYS = 65535
