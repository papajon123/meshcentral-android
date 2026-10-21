package ru.papajon.meshcentralmobile.settings

import java.net.URI
import java.net.URISyntaxException

/**
 * Validates the MeshCentral endpoint typed by the user.
 *
 * The validator is deliberately platform-independent ([java.net.URI]) so the same rules run in
 * JVM tests and on Android. A MeshCentral installation may live under a sub-path
 * (`https://example.com/meshcentral/`), and a trailing slash is optional: the client normalizes
 * it later.
 */
object ServerUrlValidator {
    fun validate(url: String, allowHttpInDebug: Boolean): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Введите адрес сервера."

        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return "Адрес сервера указан неверно."
        }

        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return "Адрес сервера должен начинаться с\u00A0https://."
        }
        if (scheme == "http" && !allowHttpInDebug) {
            return "Обычный HTTP разрешён только в\u00A0отладочной сборке. Используйте https://."
        }
        if (parsed.isOpaque) return "Адрес сервера указан неверно."
        if (parsed.rawUserInfo != null) {
            return "В\u00A0адресе не\u00A0должно быть имени пользователя или пароля."
        }
        if (parsed.rawQuery != null) {
            return "Адрес не\u00A0должен содержать параметры запроса после «?»."
        }
        if (parsed.rawFragment != null) {
            return "Адрес не\u00A0должен содержать фрагмент после «#»."
        }

        val authority = parsed.rawAuthority
        if (authority.isNullOrBlank()) return "В\u00A0адресе не\u00A0указано имя сервера."

        // Check the port before the host: `host:abc` parses as a URI but getHost() returns null,
        // and `host:` produces no port at all. Both are port errors, not missing-host errors.
        portProblem(authority)?.let { return it }

        val host = parsed.host
        if (host.isNullOrBlank() || !isValidHost(host)) {
            return "Имя сервера указано неверно."
        }
        return null
    }

    private fun portProblem(authority: String): String? {
        // Userinfo is rejected earlier, but strip it here too so the parser stays self-contained.
        val hostAndPort = authority.substringAfterLast('@')
        val portText = when {
            hostAndPort.startsWith("[") -> {
                val closingBracket = hostAndPort.indexOf(']')
                if (closingBracket < 0) return PORT_ERROR
                when (val suffix = hostAndPort.substring(closingBracket + 1)) {
                    "" -> return null
                    ":" -> return PORT_ERROR
                    else -> if (suffix.startsWith(":")) suffix.substring(1) else return PORT_ERROR
                }
            }
            ':' in hostAndPort -> hostAndPort.substringAfterLast(':')
            else -> return null
        }

        if (portText.isEmpty() || portText.any { !it.isDigit() }) return PORT_ERROR
        val port = portText.toIntOrNull() ?: return PORT_ERROR
        return if (port in 1..65535) null else PORT_ERROR
    }

    private fun isValidHost(host: String): Boolean {
        if (host.startsWith("[") || ':' in host) {
            // java.net.URI has already parsed the IPv6 literal; handle platforms that keep the
            // brackets and ones that return the bare address.
            val literal = host.removePrefix("[").removeSuffix("]")
            return literal.isNotBlank() && ':' in literal
        }

        // A single trailing dot is a valid absolute DNS name; ignore it for label validation.
        val normalized = host.removeSuffix(".")
        if (normalized.isEmpty() || normalized.length > 253) return false
        return normalized.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                !label.startsWith('-') &&
                !label.endsWith('-') &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    private const val PORT_ERROR = "Порт должен быть числом от 1 до 65535."
}
