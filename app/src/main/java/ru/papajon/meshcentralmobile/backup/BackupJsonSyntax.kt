package ru.papajon.meshcentralmobile.backup

import org.json.JSONTokener

/**
 * Android's org.json accepts comments, unquoted keys and duplicate members. Validate standard
 * JSON and its depth BEFORE invoking that recursive parser, so quoting inside a comment cannot
 * hide deeply nested input from the guard. No decoded values or input are included in errors.
 */
internal object BackupJsonSyntax {
    private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    fun validate(text: String) = Parser(text).run()

    private class Parser(private val text: String) {
        private var offset = 0
        fun run() {
            whitespace()
            check(peek() == '{')
            value(0)
            whitespace()
            check(offset == text.length)
        }

        private fun value(depth: Int) {
            whitespace()
            when (peek()) {
                '{' -> {
                    check(depth < 64)
                    expect('{')
                    val keys = HashSet<String>()
                    if (consume('}')) return
                    while (true) {
                        whitespace()
                        val start = offset
                        string()
                        val key = JSONTokener(text.substring(start, offset)).nextValue() as String
                        check(keys.add(key))
                        expect(':')
                        value(depth + 1)
                        if (consume('}')) return
                        expect(',')
                    }
                }
                '[' -> {
                    check(depth < 64)
                    expect('[')
                    if (consume(']')) return
                    while (true) {
                        value(depth + 1)
                        if (consume(']')) return
                        expect(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> {
                    val match = number.find(text, offset)
                    check(match != null && match.range.first == offset)
                    offset = match!!.range.last + 1
                }
                else -> invalid()
            }
        }

        private fun string() {
            expect('"')
            while (offset < text.length) {
                when (val char = text[offset++]) {
                    '"' -> return
                    '\\' -> {
                        check(offset < text.length)
                        when (text[offset++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                check(offset < text.length && text[offset] in "0123456789abcdefABCDEF")
                                offset++
                            }
                            else -> invalid()
                        }
                    }
                    else -> check(char.code >= 0x20)
                }
            }
            invalid()
        }

        private fun literal(value: String) {
            check(text.startsWith(value, offset))
            offset += value.length
        }
        private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
        private fun peek(): Char? = text.getOrNull(offset)
        private fun consume(char: Char): Boolean {
            whitespace()
            if (peek() != char) return false
            offset++
            return true
        }
        private fun expect(char: Char) { check(consume(char)) }
        private fun check(condition: Boolean) { if (!condition) invalid() }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid backup JSON syntax or depth")
    }
}
