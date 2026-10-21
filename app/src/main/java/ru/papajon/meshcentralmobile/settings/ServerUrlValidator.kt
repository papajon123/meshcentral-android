package ru.papajon.meshcentralmobile.settings

import java.net.URI
import java.net.URISyntaxException
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText

/**
 * Validates the MeshCentral endpoint typed by the user.
 *
 * The validator is deliberately platform-independent ([java.net.URI]) so the same rules run in
 * JVM tests and on Android. A MeshCentral installation may live under a sub-path
 * (`https://example.com/meshcentral/`), and a trailing slash is optional: the client normalizes
 * it later.
 */
object ServerUrlValidator {
    fun validate(url: String, allowHttpInDebug: Boolean): UiText? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return UiText.Resource(R.string.runtime_validation_url_required)

        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return UiText.Resource(R.string.runtime_validation_invalid_url)
        }

        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return UiText.Resource(R.string.runtime_validation_https_required)
        }
        if (scheme == "http" && !allowHttpInDebug) {
            return UiText.Resource(R.string.runtime_validation_http_debug_only)
        }
        if (parsed.isOpaque) return UiText.Resource(R.string.runtime_validation_invalid_url)
        if (parsed.rawUserInfo != null) {
            return UiText.Resource(R.string.runtime_validation_credentials_not_allowed)
        }
        if (parsed.rawQuery != null) {
            return UiText.Resource(R.string.runtime_validation_query_not_allowed)
        }
        if (parsed.rawFragment != null) {
            return UiText.Resource(R.string.runtime_validation_fragment_not_allowed)
        }

        val authority = parsed.rawAuthority
        if (authority.isNullOrBlank()) return UiText.Resource(R.string.runtime_validation_host_required)

        // Check the port before the host: `host:abc` parses as a URI but getHost() returns null,
        // and `host:` produces no port at all. Both are port errors, not missing-host errors.
        portProblem(authority)?.let { return it }

        val host = parsed.host
        if (host.isNullOrBlank() || !isValidHost(host)) {
            return UiText.Resource(R.string.runtime_validation_host_invalid)
        }
        return null
    }

    private fun portProblem(authority: String): UiText? {
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

    private val PORT_ERROR = UiText.Resource(R.string.runtime_validation_port_invalid)
}
