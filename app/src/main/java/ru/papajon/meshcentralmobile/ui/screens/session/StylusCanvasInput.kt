package ru.papajon.meshcentralmobile.ui.screens.session

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import ru.papajon.meshcentralmobile.mesh.RemoteViewport
import ru.papajon.meshcentralmobile.mesh.StylusAction
import ru.papajon.meshcentralmobile.mesh.StylusInputController

internal class FingerDragCancellation {
    var release: (() -> Unit)? = null
    fun cancel() { release?.invoke() }
}

private class NativePenEvent { var lastAction = -1 }

/** Observe Android pen buttons/hover before Compose's finger recognizer. Finger paths consume input. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun stylusCanvasInput(
    controller: StylusInputController,
    enabled: Boolean,
    inputEnabled: Boolean,
    viewport: RemoteViewport,
    onStylusUsed: () -> Unit,
    beforePenInput: () -> Unit = {},
    // Temporary replay backpressure must not act like permission loss and reset palm state.
    contactEnabled: Boolean = true,
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentViewport by rememberUpdatedState(viewport)
    val currentInputEnabled by rememberUpdatedState(inputEnabled)
    val currentContactEnabled by rememberUpdatedState(contactEnabled)
    val currentOnStylusUsed by rememberUpdatedState(onStylusUsed)
    val currentBeforePenInput by rememberUpdatedState(beforePenInput)
    val nativeEvent = remember(controller) { NativePenEvent() }
    DisposableEffect(controller, enabled, inputEnabled, viewport) {
        onDispose { controller.reset() }
    }
    if (!enabled) return Modifier
    // motionEventSpy is not guaranteed to give local x/y. Use screen coordinates and the actual
    // canvas layout (including the phone header/insets), not the position of the activity's view.
    val watcher: (MotionEvent) -> Unit = remember(controller) {
        { event ->
            nativeEvent.lastAction = event.actionMasked
            val upIndex = if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            controller.observeFingers((0 until event.pointerCount).count {
                it != upIndex && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER
            }, event.eventTime)
            val index = (0 until event.pointerCount).firstOrNull {
                event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            }
            val layout = coordinates
            if (index != null && layout?.isAttached == true) {
                val action = when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> StylusAction.HoverEnter
                    MotionEvent.ACTION_HOVER_MOVE -> StylusAction.HoverMove
                    MotionEvent.ACTION_HOVER_EXIT -> StylusAction.HoverExit
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                        if (event.actionIndex == index) StylusAction.Down else null
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                        if (event.actionIndex == index) StylusAction.Up else null
                    MotionEvent.ACTION_MOVE -> StylusAction.Move
                    else -> null
                }
                if (action != null) {
                    // Finish the finger's press BEFORE the pen sends its own DOWN for the same
                    // remote button. Initial-pass observation runs before the finger recognizer.
                    if (action == StylusAction.Down || action == StylusAction.HoverEnter ||
                        action == StylusAction.HoverMove) currentBeforePenInput()
                    val rawX = event.rawX + event.getX(index) - event.x
                    val rawY = event.rawY + event.getY(index) - event.y
                    val local = layout.screenToLocal(Offset(rawX, rawY))
                    if (action == StylusAction.Down || action == StylusAction.HoverEnter ||
                        action == StylusAction.HoverMove) currentOnStylusUsed()
                    controller.handle(action, local.x, local.y, currentViewport,
                        currentInputEnabled && currentContactEnabled,
                        primaryButton = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0,
                        secondaryButton = event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0,
                        eraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER,
                        timeMillis = event.eventTime)
                }
            }
        }
    }
    return Modifier.onGloballyPositioned { coordinates = it }.motionEventSpy(watcher)
        .pointerInput(controller) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Leaving this canvas can be a Compose Exit backed by native HOVER_MOVE.
                        // Observe it before the finger recognizer; actionMasked alone misses it.
                        val pen = event.changes.firstOrNull { it.isPen() }
                        if (event.type == PointerEventType.Exit && pen != null) {
                            controller.handle(StylusAction.HoverExit, pen.position.x, pen.position.y,
                                currentViewport, currentInputEnabled, timeMillis = pen.uptimeMillis)
                        }
                        // Compose synthesizes releases on ACTION_CANCEL without a native event.
                        if (event.type == PointerEventType.Release && event.changes.none { it.pressed }) {
                            if (nativeEvent.lastAction == MotionEvent.ACTION_UP) controller.finishTouchStream()
                            else controller.cancelContact(SystemClock.uptimeMillis())
                        }
                    }
                }
            } finally { controller.reset() }
        }
}

internal fun PointerInputChange.isPen() = type == PointerType.Stylus || type == PointerType.Eraser

/** Only actual fingers participate; a pen plus a palm is not a pinch. Never sends remote input. */
internal fun transformWithFingers(
    event: PointerEvent, viewport: RemoteViewport, onViewportChanged: (Float, Float, Float) -> Unit,
    pinchEnabled: Boolean = true,
) {
    val fingers = event.changes.filter { it.type == PointerType.Touch && (it.pressed || it.previousPressed) }
    if (fingers.count { it.pressed } < 2) return
    val touch = PointerEvent(fingers)
    val center = touch.calculateCentroid()
    val pan = touch.calculatePan()
    val factor = if (pinchEnabled) touch.calculateZoom() else 1f
    if (!center.x.isFinite() || !center.y.isFinite() || !factor.isFinite()) return
    val result = viewport.zoomAround(center.x, center.y, viewport.zoom * factor)
    val clamped = viewport.copy(zoom = result.zoom).clampPan(result.panX + pan.x, result.panY + pan.y)
    onViewportChanged(result.zoom, clamped.first, clamped.second)
}
