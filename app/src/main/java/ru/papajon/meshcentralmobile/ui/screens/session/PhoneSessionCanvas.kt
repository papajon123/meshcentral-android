package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.PhoneTouchGesture
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.RemoteViewport

/** Phone-only direct touch. Two fingers always transform the local picture, never send KVM. */
@Composable
fun PhoneSessionCanvas(state: RemoteDesktopState, zoom: Float, panX: Float, panY: Float,
    onViewportChanged: (Float, Float, Float) -> Unit, onCanvasSizeChanged: (IntSize) -> Unit,
    input: NativeRemoteInputSink, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val viewport = RemoteViewport(size.width, size.height, state.remoteWidth, state.remoteHeight, zoom, panX, panY)
    val currentViewport by rememberUpdatedState(viewport)
    val currentState by rememberUpdatedState(state)
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val scope = rememberCoroutineScope()
    var replay by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(state.device?.nodeId, state.attempt, state.inputEnabled, state.remoteWidth, state.remoteHeight) {
        onDispose { replay?.cancel() }
    }
    Canvas(modifier.testTag("phone_control_canvas").onSizeChanged { size = it; onCanvasSizeChanged(it) }
        .pointerInput(state.device?.nodeId, state.attempt, state.inputEnabled, state.remoteWidth, state.remoteHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentState.hasFrame) return@awaitEachGesture
                val initialViewport = currentViewport
                val start = initialViewport.toRemote(down.position.x, down.position.y)
                val gesture = PhoneTouchGesture(start.first, start.second)
                val acceptsInput = currentState.inputEnabled && replay?.isActive != true &&
                    initialViewport.hitsImage(down.position.x, down.position.y)
                var multiTouch = false
                var last = down
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed || it.previousPressed } > 1) {
                        multiTouch = true
                        gesture.cancel()
                        val view = currentViewport
                        val center = event.calculateCentroid(useCurrent = true)
                        val pan = event.calculatePan()
                        val factor = event.calculateZoom()
                        if (center.x.isFinite() && center.y.isFinite() && factor.isFinite()) {
                            val result = view.zoomAround(center.x, center.y, view.zoom * factor)
                            val clamped = view.copy(zoom = result.zoom).clampPan(result.panX + pan.x, result.panY + pan.y)
                            currentOnViewportChanged(result.zoom, clamped.first, clamped.second)
                        }
                    } else if (!multiTouch) {
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        val point = initialViewport.toRemote(change.position.x, change.position.y)
                        if (change.pressed) gesture.move(point.first, point.second, change.uptimeMillis - down.uptimeMillis)
                        if (!currentState.inputEnabled && change.pressed) {
                            val view = currentViewport
                            val delta = change.position - change.previousPosition
                            val pan = view.clampPan(view.panX + delta.x, view.panY + delta.y)
                            currentOnViewportChanged(view.zoom, pan.first, pan.second)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (acceptsInput && !multiTouch && currentState.inputEnabled) {
                    val end = initialViewport.toRemote(last.position.x, last.position.y)
                    val points = gesture.finish(end.first, end.second, last.uptimeMillis - down.uptimeMillis)
                    replay = scope.launch { PhoneTouchGesture.replay(points, input) { currentState.inputEnabled } }
                }
            }
        }) {
        val frame = state.framebuffer ?: return@Canvas
        drawImage(frame.asImageBitmap(), dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()),
            dstSize = IntSize(viewport.drawnWidth.roundToInt().coerceAtLeast(1), viewport.drawnHeight.roundToInt().coerceAtLeast(1)))
    }
}
