package ru.papajon.meshcentralmobile.ui.screens.session

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.input.HardwareKeyboardController
import ru.papajon.meshcentralmobile.input.ImeTextDelta
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.input.RemoteKeyCodeMapper

/**
 * Forwards the phone's own keyboard to the remote computer.
 *
 * A custom [InputConnection] forwards composition changes immediately and sends only their delta,
 * so confirming a suggested word does not type it twice on the remote computer.
 */
@Composable
fun NativeKeyboardCapture(
    input: NativeRemoteInputSink?,
    active: Boolean,
    modifier: Modifier = Modifier,
    useRemoteKeyboardMap: Boolean = false,
    isWindows: Boolean = true,
) {
    val field = remember { mutableStateOf<RemoteInputField?>(null) }
    val hostContext = LocalContext.current
    val view = field.value

    LaunchedEffect(active, view) {
        val manager = hostContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (active && view != null) {
            view.requestFocus()
            manager?.restartInput(view)
            manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            delay(IME_RETRY_DELAY_MILLIS)
            if (view.isAttachedToWindow && view.isFocused) {
                val shown = manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) ?: false
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "IME request: focused=${view.isFocused} shown=$shown")
                }
            }
        } else {
            if (view != null) {
                view.releaseHardwareKeys()
                manager?.hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
                view.text?.clear()
            }
        }
    }

    DisposableEffect(hostContext) {
        onDispose {
            field.value?.let { current ->
                current.releaseHardwareKeys()
                val manager = hostContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                manager?.hideSoftInputFromWindow(current.windowToken, 0)
                current.text?.clear()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            RemoteInputField(
                context,
                if (active) input else null,
                useRemoteKeyboardMap,
                isWindows,
            ).also { view ->
                view.layoutParams = ViewGroup.LayoutParams(2, 2)
                // Almost invisible rather than fully transparent: some ROMs refuse to raise the
                // keyboard for a view with alpha 0.
                view.alpha = 0.01f
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.showSoftInputOnFocus = true
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                field.value = view
            }
        },
        update = { view ->
            // An inactive invisible editor must not take Tab focus or forward a late IME event.
            view.updateInput(if (active) input else null, useRemoteKeyboardMap, isWindows)
            view.isFocusable = active
            view.isFocusableInTouchMode = active
        },
    )
}

private const val TAG = "NativeKeyboardCapture"
private const val IME_RETRY_DELAY_MILLIS = 150L

/** Invisible text field whose input connection is rewritten to remote key packets. */
internal class RemoteInputField(
    context: Context,
    initialSink: NativeRemoteInputSink?,
    useRemoteKeyboardMap: Boolean = false,
    isWindows: Boolean = true,
) : EditText(context) {

    private var currentSink = initialSink
    var sink: NativeRemoteInputSink?
        get() = currentSink
        set(value) { updateInput(value, remoteKeyboardMap, windowsInput) }
    private var remoteKeyboardMap = useRemoteKeyboardMap
    private var windowsInput = isWindows
    private var hardware: HardwareKeyboardController? = initialSink?.let {
        HardwareKeyboardController(it, isWindows, useRemoteKeyboardMap)
    }

    val forwarder = ImeTextDelta(
        onText = ::typeUnicode,
        onBackspace = { sendVirtualKey(8) },
        onDelete = { sendVirtualKey(46) },
    )

    /** Releases every physical key this field owns before the sink or mode changes. */
    fun releaseHardwareKeys() {
        hardware?.releaseAll()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) releaseHardwareKeys()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!focused) releaseHardwareKeys()
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }

    override fun onDetachedFromWindow() {
        releaseHardwareKeys()
        super.onDetachedFromWindow()
    }

    fun updateInput(
        nextSink: NativeRemoteInputSink?,
        useRemoteKeyboardMap: Boolean,
        isWindows: Boolean = windowsInput,
    ) {
        if (nextSink === currentSink && useRemoteKeyboardMap == remoteKeyboardMap && isWindows == windowsInput) return
        // Release against the old sink and old mode before replacing the owner.
        hardware?.releaseAll()
        currentSink = nextSink
        remoteKeyboardMap = useRemoteKeyboardMap
        windowsInput = isWindows
        hardware = nextSink?.let { HardwareKeyboardController(it, isWindows, useRemoteKeyboardMap) }
        if (nextSink == null && isAttachedToWindow) {
            // Hiding an EditText alone can leave its IME connection accepting Tab/Enter.
            // Re-query the now-inactive editor so local keyboard navigation resumes.
            context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        }
    }

    fun synchronizeComposition() {
        val editable = text ?: return
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        forwarder.synchronizeComposition(if (start >= 0 && end >= start) editable.substring(start, end) else "")
    }

    init {
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSingleLine = false
        setTextIsSelectable(false)
    }

    override fun onCheckIsTextEditor(): Boolean = currentSink != null && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (currentSink == null) return null
        outAttrs.inputType = inputType
        outAttrs.imeOptions = imeOptions
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        synchronizeComposition()
        return RemoteInputConnection(base, this)
    }

    fun typeUnicode(text: String) {
        if (text.isEmpty()) return
        sink?.sendUnicodeText(text)
    }

    fun sendVirtualKey(virtualKey: Int, extended: Boolean = RemoteKeyCodeMapper.isExtended(virtualKey)) {
        val sink = sink ?: return
        sink.sendRemoteKey(virtualKey, down = true, extended = extended)
        sink.sendRemoteKey(virtualKey, down = false, extended = extended)
    }

    /** Hardware keys arrive on the view rather than through the input connection. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // System navigation and volume belong to Android, including legacy Back on API 26–32.
        if (keyCode in localSystemKeys) return super.onKeyDown(keyCode, event)
        if (isPhysical(event)) {
            hardware?.handle(event)
            return true
        }
        val virtualKey = mapKeyCode(keyCode)
            ?: RemoteKeyCodeMapper.toVirtualKeyCode(keyCode).takeIf { event.unicodeChar == 0 }
        if (virtualKey != null) {
            sendVirtualKey(virtualKey)
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                // The IME must see the same deletion in its local editor or it may stop repeating.
                super.onKeyDown(keyCode, event)
                synchronizeComposition()
            }
            return true
        }
        val character = event.unicodeChar
        if (character != 0) {
            typeUnicode(String(Character.toChars(character)))
            super.onKeyDown(keyCode, event)
            synchronizeComposition()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in localSystemKeys) return super.onKeyUp(keyCode, event)
        if (isPhysical(event)) {
            hardware?.handle(event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Hardware events carry a non-negative device id (including the built-in keyboard id 0).
     * Key events synthesised by an IME use the
     * virtual keyboard id, so they keep the Unicode/composing path below.
     */
    private fun isPhysical(event: KeyEvent): Boolean =
        event.deviceId >= 0 && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0

    private companion object {
        val localSystemKeys = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}

/** Maps Android key codes to Windows virtual key codes, or null for printable characters. */
internal fun mapKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
    KeyEvent.KEYCODE_DEL -> 8
    KeyEvent.KEYCODE_FORWARD_DEL -> 46
    KeyEvent.KEYCODE_TAB -> 9
    KeyEvent.KEYCODE_ESCAPE -> 27
    KeyEvent.KEYCODE_DPAD_UP -> 38
    KeyEvent.KEYCODE_DPAD_DOWN -> 40
    KeyEvent.KEYCODE_DPAD_LEFT -> 37
    KeyEvent.KEYCODE_DPAD_RIGHT -> 39
    KeyEvent.KEYCODE_MOVE_HOME -> 36
    KeyEvent.KEYCODE_MOVE_END -> 35
    KeyEvent.KEYCODE_PAGE_UP -> 33
    KeyEvent.KEYCODE_PAGE_DOWN -> 34
    else -> null
}

/** Translates IME commits and key events into remote input instead of local text. */
private class RemoteInputConnection(
    target: InputConnection,
    private val field: RemoteInputField,
) : InputConnectionWrapper(target, false) {
    private val forwarder get() = field.forwarder

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        forwarder.commitText(text?.toString().orEmpty())
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        forwarder.setComposingText(text?.toString().orEmpty())
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        forwarder.finishComposingText()
        return super.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val result = super.setComposingRegion(start, end)
        field.synchronizeComposition()
        return result
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        forwarder.deleteSurroundingText(beforeLength, afterLength)
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        forwarder.deleteSurroundingText(beforeLength, afterLength)
        val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        field.synchronizeComposition()
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // deleteSurroundingText excludes the composing region. A real DEL key must go through
        // EditText so the composing word shrinks and the IME continues its long-press repeat.
        return field.dispatchKeyEvent(event)
    }
}
