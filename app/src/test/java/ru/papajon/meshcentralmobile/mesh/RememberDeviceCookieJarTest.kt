package ru.papajon.meshcentralmobile.mesh

import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust
import ru.papajon.meshcentralmobile.security.RememberedDeviceStore

class RememberDeviceCookieJarTest {
    @Test fun sendsOneMergedCookieHeaderAndNeverSendsTwofactorToARelayOrAnotherUser() {
        MockWebServer().use { server ->
            server.start()
            var raw: String? = null
            val store = RememberedDeviceStore({ raw }, { raw = it }, { raw = null })
            val origin = server.url("/team/").toString()
            store.save(origin, "alice", "fixture-cookie", 30, RememberedDeviceTrust.DebugHttp)
            val jar = MeshCookieJar()
            fun forUser(user: String) = jar.forControlRequest { store.cookieForRequest(origin, user, it, true, RememberedDeviceTrust.DebugHttp) }
            jar.saveFromResponse(server.url("/team/"), listOf(
                Cookie.Builder().name("session").value("synthetic").hostOnlyDomain(server.hostName).path("/").build(),
                Cookie.Builder().name("twofactor").value("unscoped").hostOnlyDomain(server.hostName).path("/").build(),
            ))
            val client = OkHttpClient.Builder().cookieJar(forUser("alice")).build()
            fun request(path: String): List<String> {
                server.enqueue(MockResponse().setBody("ok"))
                client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
                return server.takeRequest().headers.values("Cookie")
            }
            try {
                assertEquals(listOf("session=synthetic; twofactor=fixture-cookie"), request("/team/control.ashx"))
                assertEquals(listOf("session=synthetic"), request("/team/meshrelay.ashx"))
                // A pending old request must not acquire the new account's cookie.
                store.save(origin, "bob", "other-cookie", 30, RememberedDeviceTrust.DebugHttp)
                assertEquals(listOf("session=synthetic"), request("/team/control.ashx"))
                assertEquals("other-cookie", forUser("bob").loadForRequest(server.url("/team/control.ashx")).last().value)
                assertEquals(listOf("session"), jar.loadForRequest(server.url("/team/control.ashx")).map { it.name })
                store.clear() // Same operation used by logout and server/profile change.
                assertEquals(listOf("session=synthetic"), request("/team/control.ashx"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
