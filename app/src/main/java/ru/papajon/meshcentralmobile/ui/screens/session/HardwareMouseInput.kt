package ru.papajon.meshcentralmobile.ui.screens.session

import android.view.MotionEvent
import android.view.InputDevice
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.papajon.meshcentralmobile.input.HardwareMouseAction
import ru.papajon.meshcentralmobile.input.HardwareMouseController
import ru.papajon.meshcentralmobile.input.HardwarePointerRoute
import ru.papajon.meshcentralmobile.input.HardwarePointerRouting
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.input.remoteWheelAxes
import ru.papajon.meshcentralmobile.input.touchpadPinchFactor
import ru.papajon.meshcentralmobile.input.nativeTouchpadRoute
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.mesh.RemoteViewport

private class NativePointerDispatch {
    var handling = false
    var canvasHit = false
    var gesture = false
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.hardwareMouseInput(input: NativeRemoteInputSink, viewport: RemoteViewport, enabled: Boolean,
    onPinch: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onNativeGestureStart: () -> Unit = {},
    onCursor: (Int, Int) -> Unit = { _, _ -> }): Modifier {
    val currentCursor by rememberUpdatedState(onCursor)
    val currentPinch by rememberUpdatedState(onPinch)
    val currentGestureStart by rememberUpdatedState(onNativeGestureStart)
    val activity = LocalContext.current.findActivity()
    val nativeDispatch = remember { NativePointerDispatch() }
    val controller = remember(input) { HardwareMouseController(input) { x, y -> currentCursor(x, y) } }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val view by rememberUpdatedState(viewport)
    val focused = LocalWindowInfo.current.isWindowFocused
    val active by rememberUpdatedState(enabled)
    val routing = remember { HardwarePointerRouting() }
    val routedMouseEvent = remember { booleanArrayOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, enabled, viewport.remoteWidth, viewport.remoteHeight, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { nativeDispatch.gesture = false; nativeDispatch.canvasHit = false; routing.reset(); controller.releaseAll() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); nativeDispatch.gesture = false; nativeDispatch.canvasHit = false; routing.reset(); controller.releaseAll() }
    }
    LaunchedEffect(controller, focused) {
        // Gaining focus must not release the button that delivered the first click.
        if (!focused) { nativeDispatch.gesture = false; nativeDispatch.canvasHit = false; routing.reset(); controller.releaseAll() }
    }
    fun handle(route: HardwarePointerRoute, event: MotionEvent) {
        val layout = coordinates ?: return
        if (!layout.isAttached) return
        val local = layout.screenToLocal(Offset(event.rawX, event.rawY))
        if (route == HardwarePointerRoute.Pinch && view.hitsImage(local.x, local.y))
            currentPinch(local.x, local.y, event.touchpadPinchFactor())
        val action = when (route) {
            HardwarePointerRoute.Hover, HardwarePointerRoute.Move -> HardwareMouseAction.Move
            HardwarePointerRoute.Buttons -> HardwareMouseAction.Buttons
            HardwarePointerRoute.Scroll -> HardwareMouseAction.Scroll
            HardwarePointerRoute.Exit -> HardwareMouseAction.Exit
            HardwarePointerRoute.Cancel -> HardwareMouseAction.Cancel
            HardwarePointerRoute.Consume, HardwarePointerRoute.Pinch -> null
        } ?: return
        val buttons = buildSet {
            if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) add(MouseButton.Left)
            if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) add(MouseButton.Right)
            if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) add(MouseButton.Middle)
        }
        val (vertical, horizontal) = if (action == HardwareMouseAction.Scroll) event.remoteWheelAxes() else 0f to 0f
        controller.handle(action, local.x, local.y, view, active, buttons, vertical, horizontal,
            event.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)
    }

    DisposableEffect(activity, controller, routing) {
        val window = activity?.window
        val previous = window?.callback
        var installed = true
        fun dispatch(event: MotionEvent, delegate: () -> Boolean): Boolean {
            if (!installed) return delegate()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Let Compose hit-test DOWN first. A local toolbar/overlay covering the canvas
                // must never send a native gesture to the remote surface underneath it.
                nativeDispatch.canvasHit = false
                nativeDispatch.gesture = false
                return delegate()
            }
            val native = event.nativeTouchpadRoute() != null
            if (!native && !nativeDispatch.gesture) return delegate()
            if (!nativeDispatch.gesture && !nativeDispatch.canvasHit) return delegate()
            val layout = coordinates
            if (layout?.isAttached != true) return delegate()
            val local = layout.screenToLocal(Offset(event.rawX, event.rawY))
            if (!nativeDispatch.gesture && !view.hitsImage(local.x, local.y)) return delegate()
            val route = routing.route(event) ?: return delegate()
            if (route !in setOf(HardwarePointerRoute.Pinch, HardwarePointerRoute.Scroll, HardwarePointerRoute.Consume)) {
                nativeDispatch.gesture = false
                return delegate()
            }
            if (!nativeDispatch.gesture) { nativeDispatch.gesture = true; currentGestureStart() }
            handle(route, event)
            // Compose 1.7 filters position-identical MOVE before motionEventSpy. Handle only
            // native axes here, then let its normal DOWN/UP path finish without duplicate input.
            nativeDispatch.handling = true
            return try { delegate(); true } finally {
                nativeDispatch.handling = false
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    nativeDispatch.gesture = false
            }
        }
        val callback = previous?.let { original -> object : android.view.Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = dispatch(event) { original.dispatchTouchEvent(event) }
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = dispatch(event) { original.dispatchGenericMotionEvent(event) }
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) { nativeDispatch.gesture = false; nativeDispatch.canvasHit = false; routing.reset(); controller.releaseAll() }
                original.onWindowFocusChanged(hasFocus)
            }
        } }
        if (window != null && callback != null) window.callback = callback
        onDispose {
            installed = false
            nativeDispatch.gesture = false
            nativeDispatch.canvasHit = false
            if (window != null && window.callback === callback) window.callback = previous
        }
    }

    return onGloballyPositioned { coordinates = it }.motionEventSpy { event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            nativeDispatch.canvasHit = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        }
        val native = event.nativeTouchpadRoute()
        if (nativeDispatch.handling ||
            (activity != null && native != null && event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_SCROLL)) {
            // Compose can replay its cached MOVE after layout/zoom. Native MOVE deltas have
            // exactly one consumer at the Window boundary. DOWN is handled here after hit-test.
            routedMouseEvent[0] = true
        } else {
            val route = routing.route(event)
            routedMouseEvent[0] = route != null
            if (native != null && !nativeDispatch.gesture) {
                nativeDispatch.gesture = true
                currentGestureStart()
            }
            if (route != null) handle(route, event)
        }
        if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT)) {
            nativeDispatch.canvasHit = false
            nativeDispatch.gesture = false
        }
    }.pointerInput(controller, routing) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (routedMouseEvent[0]) {
                        if (event.type == PointerEventType.Exit ||
                            (event.type == PointerEventType.Release && event.changes.none { it.pressed })) controller.releaseAll()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        } finally {
            routing.reset()
            controller.releaseAll()
        }
    }
}
