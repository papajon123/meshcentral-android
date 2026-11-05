package ru.papajon.meshcentralmobile.mesh

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.model.ConnectionState

class DesktopConsentTest {
    @Test fun optionsPrecedeProtocolAndDenialDoesNotReconnect() = fixture { relay, peers, messages ->
        peers.single().send("c")
        await { messages.size >= 2 }
        val first = JSONObject(messages.elementAt(0))
        assertEquals("options", first.getString("type"))
        assertEquals(8, first.getInt("consent"))
        assertEquals("2", messages.elementAt(1))
        peers.single().send("""{"ctrlChannel":"102938","type":"console","msgid":1}""")
        await { relay.desktopState.value.message == UiText.Resource(R.string.consent_waiting) }
        assertFalse(relay.sendRemoteKey(65, true))
        peers.single().send("""{"ctrlChannel":"102938","type":"console","msgid":2}""")
        await { relay.desktopState.value.connectionState == ConnectionState.Failed }
        assertEquals(UiText.Resource(R.string.consent_denied), relay.desktopState.value.message)
        assertFalse(relay.hasActiveSession())
        assertFalse(relay.sendRemoteKey(65, true))
        Thread.sleep(1200)
        assertEquals(1, peers.size)
    }

    @Test fun aSilentConsentRequestTimesOutInsteadOfConnectingForever() = fixture(timeout = 100) { relay, peers, _ ->
        peers.single().send("c")
        await { relay.desktopState.value.connectionState == ConnectionState.Failed }
        assertEquals(UiText.Resource(R.string.consent_timeout), relay.desktopState.value.message)
        assertFalse(relay.hasActiveSession())
    }

    private fun fixture(timeout: Long = 90_000, test: (MeshDesktopRelay, ConcurrentLinkedQueue<WebSocket>, ConcurrentLinkedQueue<String>) -> Unit) {
        val server = MockWebServer()
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val peers = ConcurrentLinkedQueue<WebSocket>()
        val messages = ConcurrentLinkedQueue<String>()
        val relay = MeshDesktopRelay(http, scope, consentTimeoutMillis = timeout) { true }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peers.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.add(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        try {
            server.start()
            relay.connect(server.url("/"), MeshDevice("node//consent", "mesh//fixture", "Fixture", conn = 1, agentId = 3), requestConsent = true)
            await { peers.isNotEmpty() }
            test(relay, peers, messages)
        } finally {
            relay.disconnect(); scope.cancel(); http.dispatcher.executorService.shutdown(); server.shutdown()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Consent state did not arrive", condition())
    }
}
