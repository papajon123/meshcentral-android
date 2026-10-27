package ru.papajon.meshcentralmobile.ui.screens.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.papajon.meshcentralmobile.input.HardwareKeyboardController
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink

/**
 * Captures hardware keyboard events for the remote desktop before they reach an editor.
 *
 * The activity's window callback sees keys before the decor view, so a focused [EditText] or an
 * input method cannot also insert them locally. Soft-keyboard events keep their normal path, and
 * every held remote key is released when capture is disabled, the window loses focus, the activity
 * is paused or the composable leaves composition.
 */
@Composable
fun HardwareKeyboardCapture(
    input: NativeRemoteInputSink,
    enabled: Boolean,
    isWindows: Boolean = true,
    onReleaseCapture: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // The activity may be a plain Activity without its own lifecycle property, so the lifecycle
    // owner comes from the composition, which also covers the test activity.
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(input, isWindows) { HardwareKeyboardController(input, isWindows) }
    val enabledState = rememberUpdatedState(enabled)
    val releaseCapture = rememberUpdatedState(onReleaseCapture)

    DisposableEffect(activity, controller, lifecycleOwner) {
        if (activity == null) {
            onDispose { controller.releaseAll() }
        } else {
            val window = activity.window
            val previousCallback = window.callback
            var installed = true
            val callback = HardwareKeyboardWindowCallback(
                delegate = previousCallback,
                controller = controller,
                enabled = { installed && enabledState.value },
                windowHasFocus = { window.decorView.hasWindowFocus() },
                releaseCapture = { releaseCapture.value() },
            )
            window.callback = callback
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    controller.releaseAll()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                installed = false
                lifecycleOwner.lifecycle.removeObserver(observer)
                controller.releaseAll()
                // If another component wrapped the callback after us, it still owns the chain;
                // replacing it would silently drop that component's interception.
                if (window.callback === callback) {
                    window.callback = previousCallback
                }
            }
        }
    }

    LaunchedEffect(enabled, controller) {
        if (!enabled) controller.releaseAll()
    }
}

/**
 * Window callback wrapper that hands physical keys to the controller and leaves everything else
 * to the activity that was installed before it.
 */
internal class HardwareKeyboardWindowCallback(
    private val delegate: Window.Callback,
    private val controller: HardwareKeyboardController,
    private val enabled: () -> Boolean,
    private val windowHasFocus: () -> Boolean,
    private val releaseCapture: () -> Unit = {},
) : Window.Callback by delegate {

    /** Updated by [onWindowFocusChanged]; the live view check covers a focus change we missed. */
    private var focused = true
    private var releaseChordPending = false

    private fun captures(): Boolean = enabled() && focused && windowHasFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (releaseChordPending) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP) {
                releaseChordPending = false
                releaseCapture()
            }
            return true
        }
        if (captures() && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.isCtrlPressed &&
            event.isAltPressed && event.isShiftPressed && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0) {
            controller.releaseAll()
            // Open the sheet after Escape UP, so that release cannot immediately dismiss it.
            releaseChordPending = true
            return true
        }
        if (captures() && controller.handle(event)) return true
        return delegate.dispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        if (!captures() || !controller.handle(event)) {
            return delegate.dispatchKeyShortcutEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Shortcut dispatch delivers a down without a matching up. Close it immediately so
            // the key and any metaState modifier cannot stay pressed on the remote computer.
            controller.handle(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (!hasFocus) { releaseChordPending = false; controller.releaseAll() }
        delegate.onWindowFocusChanged(hasFocus)
    }
}

/** Unwraps theme and other [ContextWrapper] layers to find the activity that owns the window. */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}
