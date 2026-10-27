package ru.papajon.meshcentralmobile.input

import android.view.KeyEvent

/** Physical key ownership includes side/extended flags; no unicode/IME duplication. */
class HardwareKeyboardController(private val input: NativeRemoteInputSink, private val isWindows: Boolean = true) {
    private data class PhysicalKey(val device: Int, val scan: Int, val code: Int)
    private data class RemoteKey(val code: Int, val extended: Boolean = false)
    private val held = linkedMapOf<PhysicalKey, RemoteKey>()
    private val implicit = linkedSetOf<RemoteKey>()
    private val pressed = linkedSetOf<RemoteKey>()

    fun handle(event: KeyEvent): Boolean {
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 || event.keyCode in skippedSystemKeys) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val remote = remoteKey(event.keyCode) ?: return false
        val physical = PhysicalKey(event.deviceId, event.scanCode, event.keyCode)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val repeat = held.containsKey(physical)
            held[physical] = remote
            if (!isModifier(remote)) holdImplicitModifiers(event)
            if (repeat && remote in pressed) send(remote, true) else press(remote)
        } else {
            held.remove(physical)?.let { owned ->
                if (owned !in held.values && owned !in implicit) release(owned)
            }
            if (!isModifier(remote) && held.values.none { !isModifier(it) }) releaseImplicit()
        }
        return true
    }

    fun releaseAll() {
        val keys = pressed.toList().asReversed()
        held.clear(); implicit.clear(); pressed.clear()
        keys.forEach { send(it, false) }
    }

    private fun family(key: RemoteKey): Int = when (key.code) {
        160, 161 -> 16
        92 -> 91
        else -> key.code
    }
    private fun isModifier(key: RemoteKey) = family(key) in setOf(16, 17, 18, 91)
    private fun holdImplicitModifiers(event: KeyEvent) {
        val wanted = buildSet {
            if (event.isCtrlPressed) add(RemoteKey(17, isWindows && event.metaState and KeyEvent.META_CTRL_RIGHT_ON != 0))
            if (event.isAltPressed) add(RemoteKey(18, isWindows && event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0))
            if (event.isShiftPressed) add(RemoteKey(if (isWindows && event.metaState and KeyEvent.META_SHIFT_RIGHT_ON != 0) 161 else 16))
            if (event.isMetaPressed) add(RemoteKey(if (event.metaState and KeyEvent.META_META_RIGHT_ON != 0) 92 else 91, isWindows))
        }
        (implicit - wanted).toList().forEach { key ->
            implicit.remove(key)
            if (key !in held.values) release(key)
        }
        wanted.forEach { key ->
            if (held.values.none { family(it) == family(key) } && press(key)) implicit.add(key)
        }
    }
    private fun releaseImplicit() {
        val keys = implicit.toList().asReversed()
        implicit.clear()
        keys.filter { it !in held.values }.forEach(::release)
    }
    private fun send(key: RemoteKey, down: Boolean) = input.sendRemoteKey(key.code, down, key.extended)
    private fun press(key: RemoteKey): Boolean {
        if (key in pressed) return true
        return send(key, true).also { if (it) pressed.add(key) }
    }
    private fun release(key: RemoteKey) { if (pressed.remove(key)) send(key, false) }

    private fun remoteKey(androidKey: Int): RemoteKey? {
        // MeshCentral 1.3.1 agent-desktop-0.0.2.js:411-430. Right Shift has its own VK,
        // whereas right Ctrl/Alt and numpad Enter/Divide use the extended action flag.
        if (androidKey == KeyEvent.KEYCODE_SHIFT_RIGHT) return RemoteKey(if (isWindows) 161 else 16)
        if (androidKey == KeyEvent.KEYCODE_META_RIGHT) return RemoteKey(92, isWindows)
        val vk = virtualKeyCode(androidKey) ?: return null
        val extended = isWindows && (RemoteKeyCodeMapper.isExtended(vk) || androidKey in setOf(
            KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_BREAK))
        return RemoteKey(vk, extended)
    }

    /** [RemoteKeyCodeMapper] plus keys the native keyboard also needs for editing and shortcuts. */
    private fun virtualKeyCode(keyCode: Int): Int? =
        RemoteKeyCodeMapper.toVirtualKeyCode(keyCode) ?: when (keyCode) {
            KeyEvent.KEYCODE_INSERT -> 45
            KeyEvent.KEYCODE_SYSRQ -> 44
            KeyEvent.KEYCODE_BREAK -> 19
            KeyEvent.KEYCODE_SCROLL_LOCK -> 145
            KeyEvent.KEYCODE_NUM_LOCK -> 144
            KeyEvent.KEYCODE_CAPS_LOCK -> 20

            KeyEvent.KEYCODE_SEMICOLON -> 186
            KeyEvent.KEYCODE_EQUALS -> 187
            KeyEvent.KEYCODE_COMMA -> 188
            KeyEvent.KEYCODE_MINUS -> 189
            KeyEvent.KEYCODE_PERIOD -> 190
            KeyEvent.KEYCODE_SLASH -> 191
            KeyEvent.KEYCODE_GRAVE -> 192
            KeyEvent.KEYCODE_LEFT_BRACKET -> 219
            KeyEvent.KEYCODE_BACKSLASH -> 220
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 221
            KeyEvent.KEYCODE_APOSTROPHE -> 222
            KeyEvent.KEYCODE_PLUS -> 187
            KeyEvent.KEYCODE_STAR -> 106

            KeyEvent.KEYCODE_NUMPAD_0 -> 96
            KeyEvent.KEYCODE_NUMPAD_1 -> 97
            KeyEvent.KEYCODE_NUMPAD_2 -> 98
            KeyEvent.KEYCODE_NUMPAD_3 -> 99
            KeyEvent.KEYCODE_NUMPAD_4 -> 100
            KeyEvent.KEYCODE_NUMPAD_5 -> 101
            KeyEvent.KEYCODE_NUMPAD_6 -> 102
            KeyEvent.KEYCODE_NUMPAD_7 -> 103
            KeyEvent.KEYCODE_NUMPAD_8 -> 104
            KeyEvent.KEYCODE_NUMPAD_9 -> 105
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 111
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 106
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 109
            KeyEvent.KEYCODE_NUMPAD_ADD -> 107
            KeyEvent.KEYCODE_NUMPAD_DOT -> 110
            KeyEvent.KEYCODE_NUMPAD_COMMA -> 108
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 13

            else -> null
        }

    private companion object {
        /** System keys must keep their local meaning and are never sent to the desktop. */
        val skippedSystemKeys = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
