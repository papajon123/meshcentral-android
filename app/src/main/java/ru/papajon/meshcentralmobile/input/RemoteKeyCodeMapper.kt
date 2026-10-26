package ru.papajon.meshcentralmobile.input

import android.view.KeyEvent

object RemoteKeyCodeMapper {
    fun toVirtualKeyCode(androidKeyCode: Int): Int? {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_TAB -> 9
            KeyEvent.KEYCODE_ENTER -> 13
            KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_MOVE_HOME -> 36
            KeyEvent.KEYCODE_MOVE_END -> 35
            KeyEvent.KEYCODE_PAGE_UP -> 33
            KeyEvent.KEYCODE_PAGE_DOWN -> 34
            KeyEvent.KEYCODE_DEL -> 8
            KeyEvent.KEYCODE_FORWARD_DEL -> 46
            KeyEvent.KEYCODE_DPAD_LEFT -> 37
            KeyEvent.KEYCODE_DPAD_UP -> 38
            KeyEvent.KEYCODE_DPAD_RIGHT -> 39
            KeyEvent.KEYCODE_DPAD_DOWN -> 40
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> VK_CTRL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> VK_ALT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> VK_META
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + (androidKeyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + (androidKeyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 112 + (androidKeyCode - KeyEvent.KEYCODE_F1)
            else -> null
        }
    }

    fun isExtended(remoteKeyCode: Int): Boolean {
        return remoteKeyCode in setOf(33, 34, 35, 36, 37, 38, 39, 40, 45, 46, VK_META)
    }

    const val VK_SHIFT = 16
    const val VK_CTRL = 17
    const val VK_ALT = 18
    const val VK_META = 91
}
