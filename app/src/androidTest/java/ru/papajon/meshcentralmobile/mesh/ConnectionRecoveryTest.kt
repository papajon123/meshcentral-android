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
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault

class ConnectionRecoveryTest {
    @Test fun recoveryClearsTheErrorAndWaitsForFreshStatuses() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("connection-test-$name", mode)
        }
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        val socket = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
        try {
            // Exhaust automatic retries quickly with explicit retries; the next user retry must work.
            repeat(6) { server.enqueue(MockResponse().setResponseCode(503)) }
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
            }))
            server.start()
            client.login(server.url("/").toString(), "test-user", "test-password")
            repeat(6) { index ->
                await {
                    server.requestCount >= index + 1 && client.clientState.value.lastError != null &&
                        client.clientState.value.connectionState in setOf(ConnectionState.Reconnecting, ConnectionState.Failed)
                }
                assertFalse(client.clientState.value.socketConnected)
                client.refreshDevices()
            }
            // The client's onOpen can run before MockWebServer publishes its socket.
            await { client.clientState.value.socketConnected && socket.get() != null }
            val connectedSocket = requireNotNull(socket.get())
            assertNull(client.clientState.value.lastError)
            assertFalse(client.clientState.value.devicesCurrent)
            connectedSocket.send("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture"}}""")
            connectedSocket.send("""{"action":"nodes","nodes":{}}""")
            await { client.clientState.value.devicesCurrent && !client.clientState.value.requiresSignIn }
            // A dropped transport is not a rejected password and must not force the sign-in form.
            connectedSocket.close(1001, "fixture disconnect")
            await { !client.clientState.value.socketConnected }
            assertFalse(client.clientState.value.devicesCurrent)
            assertFalse(client.clientState.value.requiresSignIn)
        } finally {
            client.close()
            scope.cancel()
            vault.clearCredentials()
            server.shutdown()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for connection state", condition())
    }
}
