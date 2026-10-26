package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class NetworkRecoveryTest {
    @Test fun foregroundBeforeNetworkRecoveryResumesTheDesktopStream() = withClient { client, server ->
        val relayConnections = java.util.concurrent.atomic.AtomicInteger()
        val recoveredPackets = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/control.ashx") {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture"}}""")
                            webSocket.send("""{"action":"nodes","nodes":{}}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (org.json.JSONObject(text).optString("action") == "authcookie") {
                                webSocket.send("""{"action":"authcookie","cookie":"fixture-cookie","rcookie":"fixture-relay"}""")
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                        }
                    })
                }
                val generation = relayConnections.incrementAndGet()
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text == "2") webSocket.send(okio.ByteString.of(0, 7, 0, 8, 0, 16, 0, 16))
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        if (generation >= 2) recoveredPackets.add(bytes.toByteArray())
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                })
            }
        }
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        await(5000) { client.clientState.value.devicesCurrent }
        onMain { client.connectDesktop(fixtureDevice()) }
        await(5000) { client.desktopState.value.handshakeComplete }
        onMain {
            client.onAppBackground()
            client.onNetworkLost()
            client.onAppForeground()
            client.onNetworkAvailable()
        }
        await(5000) { relayConnections.get() >= 2 && client.desktopState.value.handshakeComplete }
        await(5000) { recoveredPackets.any { it.contentEquals(KvmInputEncoder.refresh()) } }
        assertFalse("Foreground recovery must not leave the desktop paused",
            recoveredPackets.any { it.contentEquals(KvmInputEncoder.pause(true)) })
    }

    @Test fun sevenDropsBeforeUpgradeStillRecoverWithoutUserRetry() = withClient { client, server ->
        repeat(7) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.enqueue(accepted())
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        await(105_000) { client.clientState.value.devicesCurrent }
        assertFalse(client.clientState.value.requiresSignIn)
        assertTrue(server.requestCount >= 8)
    }

    @Test fun serverRefusalsStillExhaustFiveAutomaticRetries() = withClient { client, server ->
        repeat(10) { server.enqueue(MockResponse().setResponseCode(503)) }
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        await(45_000) { server.requestCount >= 6 && client.clientState.value.connectionState == ConnectionState.Failed }
        Thread.sleep(1500)
        assertEquals("Initial connection plus five retries", 6, server.requestCount)
        client.onNetworkAvailable()
        Thread.sleep(1500)
        assertEquals("Network callbacks must not renew the refusal budget", 6, server.requestCount)
    }

    @Test fun anAcceptedSocketThatImmediatelyClosesStillExhaustsTheBudget() = withClient { client, server ->
        repeat(10) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "fixture refusal") }
        })) }
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        await(45_000) { server.requestCount >= 6 && client.clientState.value.connectionState == ConnectionState.Failed }
        Thread.sleep(1500)
        assertEquals(6, server.requestCount)
    }

    @Test fun lossDuringConnectingWaitsWithoutPollingThenRecoversOnTheCallback() = withClient { client, server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(accepted())
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        await(5000) { server.requestCount == 1 }
        onMain { client.onNetworkLost() }
        Thread.sleep(1500)
        assertEquals(1, server.requestCount)
        assertEquals(ConnectionState.Reconnecting, client.clientState.value.connectionState)
        onMain { client.onNetworkAvailable() }
        await(5000) { client.clientState.value.devicesCurrent }
        assertEquals(2, server.requestCount)
    }

    @Test fun logoutWhileWaitingCannotBeUndoneByNetworkCallbacks() = withClient { client, server ->
        onMain { client.onNetworkLost() }
        client.login(server.url("/").toString(), "fixture", "fixture-password")
        onMain { client.logout(true); client.onNetworkAvailable() }
        Thread.sleep(1500)
        assertEquals(0, server.requestCount)
        assertTrue(client.clientState.value.requiresSignIn)
    }

    @Test fun desktopSurvivesSevenDropsBeforeUpgrade() = withRelay { relay, server ->
        repeat(7) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "2") webSocket.send(okio.ByteString.of(0, 7, 0, 8, 0, 16, 0, 16))
            }
        }))
        val url = server.url("/")
        onMain { relay.connect(url, fixtureDevice()) }
        await(105_000) { relay.desktopState.value.handshakeComplete }
        assertEquals(ConnectionState.Connected, relay.desktopState.value.connectionState)
        assertEquals(0, relay.desktopState.value.attempt)
    }

    @Test fun desktopServerClosuresStopAfterFiveRetries() = withRelay { relay, server ->
        repeat(10) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1000, "fixture refusal") }
        })) }
        val url = server.url("/")
        onMain { relay.connect(url, fixtureDevice()) }
        await(5000) { server.requestCount >= 2 && relay.desktopState.value.connectionState == ConnectionState.Reconnecting }
        onMain { relay.resumeAfterBackground(controlReady = true) }
        await(45_000) { server.requestCount >= 6 && relay.desktopState.value.connectionState == ConnectionState.Failed }
        onMain { relay.onNetworkLost(); relay.onNetworkAvailable(); relay.onControlAvailable() }
        Thread.sleep(1500)
        assertEquals(6, server.requestCount)
    }

    private fun withRelay(block: (MeshDesktopRelay, MockWebServer) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val transport = okhttp3.OkHttpClient()
        val relay = MeshDesktopRelay(transport, scope, sendControlMessage = { true })
        val server = MockWebServer()
        try { server.start(); block(relay, server) }
        finally {
            onMain { relay.disconnect() }
            scope.cancel(); transport.dispatcher.cancelAll(); transport.connectionPool.evictAll(); server.shutdown()
        }
    }

    private fun onMain(action: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { action() } }
    private fun fixtureDevice() = MeshDevice(nodeId = "node/test/fixture", meshId = "mesh/test/fixture", name = "Fixture", agentId = 4, conn = 1)

    private fun accepted() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture"}}""")
            webSocket.send("""{"action":"nodes","nodes":{}}""")
        }
    })

    private fun withClient(block: (MeshCentralClient, MockWebServer) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("network-recovery-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        try { server.start(); block(client, server) }
        finally { client.close(); scope.cancel(); vault.clearCredentials(); server.shutdown() }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for automatic recovery", condition())
    }
}
