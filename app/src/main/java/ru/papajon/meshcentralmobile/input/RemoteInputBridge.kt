package ru.papajon.meshcentralmobile.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.webkit.WebView

object RemoteInputBridge {
    /** A labelled Ctrl shortcut always sends Ctrl, regardless of the modifier toggles in the sheet. */
    fun sendCtrlShortcutToRemote(nativeSink: NativeRemoteInputSink?, keyCode: Int): RemoteInputResult =
        sendKeyToRemote(webView = null, nativeSink = nativeSink, keyCode = keyCode, ctrl = true)

    fun sendKeyToRemote(
        webView: WebView?,
        nativeSink: NativeRemoteInputSink? = null,
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): RemoteInputResult {
        if (nativeSink != null) {
            return sendNativeKeyToRemote(nativeSink, keyCode, ctrl, alt, shift, meta)
        }

        if (webView == null) {
            return RemoteInputResult.Failed("Удалённый WebView не\u00A0готов.")
        }

        webView.requestFocus()
        val now = System.currentTimeMillis()
        val metaState = buildMetaState(ctrl, alt, shift, meta)
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        webView.dispatchKeyEvent(down)
        webView.dispatchKeyEvent(up)
        return RemoteInputResult.Sent(2)
    }

    fun sendTextToRemote(
        webView: WebView?,
        text: String,
        nativeSink: NativeRemoteInputSink? = null,
    ): RemoteInputResult {
        if (nativeSink != null) {
            if (text.isEmpty()) {
                return RemoteInputResult.Failed("Поле пустое.")
            }
            val eventCount = nativeSink.sendUnicodeText(text)
            return if (eventCount > 0) {
                RemoteInputResult.Sent(eventCount)
            } else {
                RemoteInputResult.Failed("Нативный рабочий стол не\u00A0подключён.")
            }
        }

        if (webView == null) {
            return RemoteInputResult.Failed("Удалённый WebView не\u00A0готов.")
        }
        if (text.isEmpty()) {
            return RemoteInputResult.Failed("Поле пустое.")
        }

        webView.requestFocus()
        val events = KeyCharacterMap
            .load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray())

        if (events == null || events.isEmpty()) {
            return RemoteInputResult.Failed("Android не\u00A0смог превратить текст в\u00A0нажатия клавиш. Воспользуйтесь буфером обмена.")
        }

        events.forEach { event ->
            webView.dispatchKeyEvent(event)
        }
        return RemoteInputResult.Sent(events.size)
    }

    fun copyToAndroidClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun readFromAndroidClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0) ?: return ""
        return item.coerceToText(context)?.toString().orEmpty()
    }

    private fun buildMetaState(
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
    ): Int {
        var state = 0
        if (ctrl) state = state or KeyEvent.META_CTRL_ON
        if (alt) state = state or KeyEvent.META_ALT_ON
        if (shift) state = state or KeyEvent.META_SHIFT_ON
        if (meta) state = state or KeyEvent.META_META_ON
        return state
    }

    private fun sendNativeKeyToRemote(
        nativeSink: NativeRemoteInputSink,
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
    ): RemoteInputResult {
        val remoteKeyCode = RemoteKeyCodeMapper.toVirtualKeyCode(keyCode)
            ?: return RemoteInputResult.Failed("Эта клавиша пока не\u00A0поддерживается в\u00A0нативном рабочем столе.")

        val modifiers = buildList {
            if (ctrl) add(RemoteKeyCodeMapper.VK_CTRL)
            if (alt) add(RemoteKeyCodeMapper.VK_ALT)
            if (shift) add(RemoteKeyCodeMapper.VK_SHIFT)
            if (meta) add(RemoteKeyCodeMapper.VK_META)
        }.filterNot { it == remoteKeyCode }

        var events = 0
        modifiers.forEach { modifier ->
            if (!nativeSink.sendRemoteKey(modifier, down = true, extended = RemoteKeyCodeMapper.isExtended(modifier))) {
                return RemoteInputResult.Failed("Нативный рабочий стол не\u00A0подключён.")
            }
            events++
        }

        val extended = RemoteKeyCodeMapper.isExtended(remoteKeyCode)
        if (!nativeSink.sendRemoteKey(remoteKeyCode, down = true, extended = extended)) {
            return RemoteInputResult.Failed("Нативный рабочий стол не\u00A0подключён.")
        }
        if (!nativeSink.sendRemoteKey(remoteKeyCode, down = false, extended = extended)) {
            return RemoteInputResult.Failed("Нативный рабочий стол не\u00A0подключён.")
        }
        events += 2

        modifiers.asReversed().forEach { modifier ->
            if (!nativeSink.sendRemoteKey(modifier, down = false, extended = RemoteKeyCodeMapper.isExtended(modifier))) {
                return RemoteInputResult.Failed("Нативный рабочий стол не\u00A0подключён.")
            }
            events++
        }

        return RemoteInputResult.Sent(events)
    }
}

sealed interface RemoteInputResult {
    data class Sent(val eventCount: Int) : RemoteInputResult
    data class Failed(val reason: String) : RemoteInputResult
}
