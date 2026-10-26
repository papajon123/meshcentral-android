package ru.papajon.meshcentralmobile.mesh.control

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONObject
import ru.papajon.meshcentralmobile.management.RunCommand

/** Bound command/console strings BEFORE JSONObject can allocate an unbounded decoded copy.
 * OkHttp has already delivered a String; this bounds additional parsing/storage, not socket buffering.
 */
object ControlFrameDecoder {
    const val COMMAND_FRAME_LIMIT = 128 * 1024

    fun decode(text: String): JSONObject? = runCatching {
        if (text.length <= COMMAND_FRAME_LIMIT) return@runCatching JSONObject(text)
        val fields = Scanner(text).read()
        if (fields.optText("action") == "msg" && fields.optText("type") in setOf("runcommands", "console")) fields
        else JSONObject(text)
    }.getOrNull()

    private class Scanner(private val text: String) {
        private var index = 0
        private var truncated = false
        fun read(): JSONObject {
            val result = JSONObject()
            whitespace(); require(take() == '{'); whitespace()
            if (peek() == '}') { index++; return result }
            var count = 0
            while (true) {
                require(++count <= 256)
                val key = string(128)
                whitespace(); require(take() == ':'); whitespace()
                val kept = key in setOf("action", "type", "nodeid", "responseid", "tag", "result", "value")
                if (peek() == '"') {
                    val limit = when (key) { "result", "value" -> RunCommand.OUTPUT_LIMIT; else -> 1024 }
                    val value = string(if (kept) limit else 0, markTruncated = key == "result")
                    if (kept) result.put(key, value)
                } else skipValue()
                whitespace()
                when (take()) { '}' -> break; ',' -> whitespace(); else -> error("Invalid object") }
            }
            whitespace(); require(index == text.length)
            if (truncated) result.put("outputTruncated", true)
            return result
        }

        private fun string(limit: Int, markTruncated: Boolean = false): String {
            require(take() == '"')
            val out = StringBuilder(minOf(limit, 1024))
            while (true) {
                var ch = take()
                if (ch == '"') return out.toString()
                require(ch >= ' ')
                if (ch == '\\') {
                    ch = when (val escaped = take()) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            var code = 0
                            repeat(4) { code = code * 16 + (take().digitToIntOrNull(16) ?: error("Invalid escape")) }
                            code.toChar()
                        }
                        else -> error("Invalid escape")
                    }
                }
                if (out.length < limit) out.append(ch) else if (markTruncated) truncated = true
            }
        }

        private fun skipValue() {
            var depth = 0
            do {
                when (peek()) {
                    '"' -> string(0)
                    '{', '[' -> { require(++depth <= 64); index++ }
                    '}', ']' -> { if (depth == 0) return; depth--; index++ }
                    ',' -> { if (depth == 0) return; index++ }
                    else -> index++
                }
            } while (index < text.length && (depth > 0 || (peek() != ',' && peek() != '}')))
        }
        private fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun peek(): Char = text.getOrElse(index) { error("Incomplete frame") }
        private fun take(): Char = peek().also { index++ }
    }
}
