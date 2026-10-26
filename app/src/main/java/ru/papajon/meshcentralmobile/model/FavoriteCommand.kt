package ru.papajon.meshcentralmobile.model

import org.json.JSONArray
import org.json.JSONObject

/** Plain single-line snippets. Selection edits a local draft, never sends input to the host. */
data class FavoriteCommand(val name: String, val command: String) {
    val valid: Boolean get() = name.isNotBlank() && name.length <= 60 && safeLine(name) && safeCommand(command)
    companion object {
        const val LIMIT = 20
        fun safeCommand(text: String): Boolean = text.isNotBlank() && text.length <= 4096 && safeLine(text)
        private fun safeLine(text: String): Boolean = text.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }
        fun encode(items: List<FavoriteCommand>): JSONArray = JSONArray().apply {
            items.filter { it.valid }.take(LIMIT).forEach { put(JSONObject().put("name", it.name).put("command", it.command)) }
        }
        fun decode(array: JSONArray?): List<FavoriteCommand> = buildList {
            if (array == null) return@buildList
            for (index in 0 until minOf(array.length(), LIMIT)) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.opt("name") as? String ?: continue
                val command = item.opt("command") as? String ?: continue
                FavoriteCommand(name, command).takeIf { it.valid }?.let(::add)
            }
        }
    }
}
