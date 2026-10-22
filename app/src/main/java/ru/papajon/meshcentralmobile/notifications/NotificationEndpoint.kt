package ru.papajon.meshcentralmobile.notifications

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Strict validation and origin binding for the registration endpoint.
 *
 * The server returns an absolute same-origin path such as `/d1/mobilecompanion/events`. It must
 * never be able to turn into a different host, a query-token endpoint, a redirect target or a
 * traversal path, so only a small ASCII path grammar is accepted.
 */
object NotificationEndpoint {
    private val SEGMENT = Regex("[A-Za-z0-9._~-]{1,64}")
    private const val MAX_PATH_LENGTH = 512
    private const val MAX_SEGMENTS = 8

    fun isValidPath(path: String): Boolean {
        if (path.length !in 1..MAX_PATH_LENGTH) return false
        if (!path.startsWith("/")) return false
        if (path.startsWith("//") || path.contains("//")) return false
        if (!path.endsWith(NotificationContract.ENDPOINT_SUFFIX)) return false
        if (path.any { it == '\\' || it == '%' || it == '?' || it == '#' || it == '@' || it == ':' }) {
            return false
        }
        if (path.any { it.code < 0x21 || it.code > 0x7e }) return false
        val segments = path.split('/').drop(1)
        if (segments.size !in 2..MAX_SEGMENTS) return false
        return segments.all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." && SEGMENT.matches(segment)
        }
    }

    /** A configured MeshCentral address usable for background notifications. */
    fun normalizeServerUrl(serverUrl: String): String? {
        val url = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (!url.isHttps) return null
        return try {
            url.newBuilder()
                .encodedPath(url.encodedPath.trimEnd('/') + "/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Resolves the server path against the configured origin. Returns null for a non-HTTPS server,
     * an address with credentials/query/fragment, or a path outside the strict grammar.
     */
    fun resolve(serverUrl: String, endpoint: String): HttpUrl? {
        if (!isValidPath(endpoint)) return null
        val server = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (server.username.isNotEmpty() || server.password.isNotEmpty()) return null
        if (server.query != null || server.fragment != null) return null
        if (!server.isHttps) return null
        val resolved = try {
            server.newBuilder()
                .encodedPath(endpoint)
                .query(null)
                .fragment(null)
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (resolved.scheme != server.scheme || resolved.host != server.host || resolved.port != server.port) {
            return null
        }
        if (resolved.encodedPath != endpoint) return null
        return resolved
    }

    fun origin(serverUrl: String): String? {
        val server = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (!server.isHttps || server.username.isNotEmpty() || server.password.isNotEmpty()) return null
        return "${server.scheme}://${server.host}:${server.port}"
    }
}

/** A profile/URL/account-bound key for non-secret notification state. */
object NotificationProfileScope {
    fun scopeKey(profileId: String, serverUrl: String, userId: String): String? {
        if (!profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        val normalized = NotificationEndpoint.normalizeServerUrl(serverUrl) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("v1\n$profileId\n$normalized\n$userId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
    }
}
