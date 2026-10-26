package ru.papajon.meshcentralmobile.model

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

data class CustomShortcut(
    val label: String,
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val win: Boolean = false,
) {
    val valid: Boolean get() = label.isNotBlank() && label.length <= MAX_LABEL && key in keys
    val chord: String get() = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (win) add("Win")
        add(key)
    }.joinToString("+")

    companion object {
        const val MAX_SHORTCUTS = 12
        const val MAX_LABEL = 40
        val keys: Map<String, Int> = buildMap {
            ('A'..'Z').forEach { put(it.toString(), KeyEvent.KEYCODE_A + (it - 'A')) }
            ('0'..'9').forEach { put(it.toString(), KeyEvent.KEYCODE_0 + (it - '0')) }
            (1..12).forEach { put("F$it", KeyEvent.KEYCODE_F1 + it - 1) }
            put("Esc", KeyEvent.KEYCODE_ESCAPE); put("Tab", KeyEvent.KEYCODE_TAB)
            put("Enter", KeyEvent.KEYCODE_ENTER); put("Space", KeyEvent.KEYCODE_SPACE)
            put("Backspace", KeyEvent.KEYCODE_DEL); put("Delete", KeyEvent.KEYCODE_FORWARD_DEL)
            put("Home", KeyEvent.KEYCODE_MOVE_HOME); put("End", KeyEvent.KEYCODE_MOVE_END)
            put("PageUp", KeyEvent.KEYCODE_PAGE_UP); put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN)
            put("↑", KeyEvent.KEYCODE_DPAD_UP); put("↓", KeyEvent.KEYCODE_DPAD_DOWN)
            put("←", KeyEvent.KEYCODE_DPAD_LEFT); put("→", KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        val suggestions = listOf(
            CustomShortcut("Win+R", "R", win = true),
            CustomShortcut("Alt+F4", "F4", alt = true),
            CustomShortcut("Ctrl+Shift+Esc", "Esc", ctrl = true, shift = true),
            CustomShortcut("Win+L", "L", win = true),
            CustomShortcut("Alt+Tab", "Tab", alt = true),
        )

        fun encode(shortcuts: List<CustomShortcut>): JSONArray {
            require(shortcuts.size <= MAX_SHORTCUTS && shortcuts.all { it.valid })
            return JSONArray().apply {
                shortcuts.forEach { put(JSONObject().put("label", it.label).put("key", it.key)
                    .put("ctrl", it.ctrl).put("alt", it.alt).put("shift", it.shift).put("win", it.win)) }
            }
        }

        fun decode(array: JSONArray?): List<CustomShortcut> = if (array == null) emptyList() else buildList {
            for (index in 0 until minOf(array.length(), MAX_SHORTCUTS)) {
                val item = array.optJSONObject(index) ?: continue
                val shortcut = CustomShortcut(item.opt("label") as? String ?: "", item.opt("key") as? String ?: "",
                    item.opt("ctrl") == true, item.opt("alt") == true, item.opt("shift") == true, item.opt("win") == true)
                if (shortcut.valid) add(shortcut)
            }
        }
    }
}
