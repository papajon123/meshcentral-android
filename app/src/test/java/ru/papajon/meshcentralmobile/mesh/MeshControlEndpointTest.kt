package ru.papajon.meshcentralmobile.mesh

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MeshControlEndpointTest {
    @Test fun keepsDomainPathAndOmitsAnAbsentKey() {
        assertEquals("wss://mesh.example:4443/team/control.ashx",
            MeshControlEndpoint.url("https://mesh.example:4443/team/".toHttpUrl(), null))
        assertEquals("wss://mesh.example/team/control.ashx",
            MeshControlEndpoint.url("https://mesh.example/team".toHttpUrl(), ""))
    }

    @Test fun keyIsOneEncodedQueryValueNotAPathFragmentOrExtraParameter() {
        val key = "synthetic +&=?/#% ключ"
        val result = MeshControlEndpoint.url("https://mesh.example/team/".toHttpUrl(), key)
            .replaceFirst("wss://", "https://").toHttpUrl()
        assertEquals("/team/control.ashx", result.encodedPath)
        assertEquals(setOf("key"), result.queryParameterNames)
        assertEquals(key, result.queryParameter("key"))
        assertNull(result.fragment)
    }

    @Test fun doesNotInheritQueryOrFragmentFromTheBaseUrl() {
        assertEquals("wss://mesh.example/control.ashx?key=new",
            MeshControlEndpoint.url("https://mesh.example/?key=old&other=value#fragment".toHttpUrl(), "new"))
    }
}
