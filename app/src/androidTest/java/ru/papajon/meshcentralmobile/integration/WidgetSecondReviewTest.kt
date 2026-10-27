package ru.papajon.meshcentralmobile.integration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * Second-review regressions for HTTP 429 handling and the refresh cooldown contract.
 * Each client uses an isolated credential namespace; no real endpoint or vendor profile is used.
 */
class WidgetSecondReviewTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mainClient429KeepsRememberedDeviceAndReconnectsOnceAccepted() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(org.json.JSONObject().put("action", "userinfo")
                    .put("userinfo", org.json.JSONObject().put("_id", USER)).toString())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val vault = SecureVault(context, "second-review-${UUID.randomUUID()}")
        val url = server.url("/").toString()
        assertTrue(vault.rememberedDevice.save(url, USER, "synthetic-remembered-cookie", 30,
            RememberedDeviceTrust.DebugHttp))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = MeshCentralClient(vault, scope)
        try {
            withContext(Dispatchers.Main) {
                client.configureServer(url)
                client.login(url, USER, "synthetic-password")
            }
            withTimeout(10_000) {
                while (server.requestCount < 2 || client.clientState.value.userId != USER ||
                    client.clientState.value.requiresSignIn) delay(50)
            }
            assertEquals("429 then accepted must be two transport attempts", 2, server.requestCount)
            assertNotNull("HTTP 429 must not forget the remembered-device record",
                vault.rememberedDevice.get(url, USER))
        } finally {
            withContext(Dispatchers.Main) { client.close() }
            scope.cancel()
            server.close()
            vault.rememberedDevice.clear()
        }
    }

    @Test fun singleAttempt429LatchesWithoutRetriesOrForgettingTheSentinel() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.start()
        val vault = SecureVault(context, "second-review-${UUID.randomUUID()}")
        val url = server.url("/").toString()
        assertTrue(vault.rememberedDevice.save(url, USER, "synthetic-remembered-cookie", 30,
            RememberedDeviceTrust.DebugHttp))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = MeshCentralClient(vault, scope, singleAttempt = true)
        try {
            withContext(Dispatchers.Main) {
                client.configureServer(url)
                client.login(url, USER, "synthetic-password")
            }
            withTimeout(10_000) { client.clientState.first { it.connectionState == ConnectionState.Failed } }
            assertEquals(1, server.requestCount)
            assertTrue("singleAttempt 429 must require explicit sign-in", client.clientState.value.requiresSignIn)
            withContext(Dispatchers.Main) {
                client.onNetworkLost()
                client.onNetworkAvailable()
                client.onAppForeground()
                client.retryConnection()
            }
            delay(2_500)
            assertEquals("singleAttempt 429 must not reconnect", 1, server.requestCount)
            assertNotNull("429 must not clear the remembered-device sentinel",
                vault.rememberedDevice.get(url, USER))
        } finally {
            withContext(Dispatchers.Main) { client.close() }
            scope.cancel()
            server.close()
            vault.rememberedDevice.clear()
        }
    }

    private companion object {
        private const val USER = "user//second-review"
    }
}
