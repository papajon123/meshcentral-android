package ru.papajon.meshcentralmobile.security

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Credentials belong to a complete server endpoint, including its MeshCentral domain path. */
internal object CredentialScope {
    fun normalize(serverUrl: String): String? {
        val url = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
    }

    fun matches(storedServer: String?, requestedServer: String): Boolean {
        val stored = storedServer?.let(::normalize) ?: return false
        val requested = normalize(requestedServer) ?: return false
        return stored == requested
    }
}
