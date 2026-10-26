package ru.papajon.meshcentralmobile.mesh

import okhttp3.HttpUrl

/** The optional domain loginKey gates control.ashx before x-meshauth (MeshCentral 1.3.1). */
internal object MeshControlEndpoint {
    fun url(server: HttpUrl, loginKey: String?): String {
        val endpoint = server.newBuilder()
            .encodedPath(server.encodedPath.trimEnd('/') + "/control.ashx")
            .query(null)
            .fragment(null)
            .apply { if (!loginKey.isNullOrEmpty()) addQueryParameter("key", loginKey) }
            .build()
        return endpoint.toString().replaceFirst(if (endpoint.isHttps) "https://" else "http://",
            if (endpoint.isHttps) "wss://" else "ws://")
    }
}
