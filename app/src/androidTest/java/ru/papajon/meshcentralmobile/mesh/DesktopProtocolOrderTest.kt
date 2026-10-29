package ru.papajon.meshcentralmobile.mesh

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.*
import org.junit.Test

class DesktopProtocolOrderTest {
    /**
     * The agent reads the first tunnel message as the protocol number (meshcore.js `parseInt(data)`),
     * and the relay holds early viewer data until both sides pair. A binary packet sent before "c"
     * therefore arrives first, becomes protocol NaN, and the agent never starts KVM.
     */
    @Test fun settingsChangedBeforePairingNeverPrecedeTheProtocolNumber() {
        val messages = ConcurrentLinkedQueue<Any>()
        val peers = ConcurrentLinkedQueue<WebSocket>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope) { true }
        val size = byteArrayOf(0, 7, 0, 8, 3, 32, 2, 88)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peers.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.add(text)
                if (text == "2") {
                    webSocket.send(ByteString.of(*size))
                    webSocket.send("000b000e0003ffff00010002ffff".decodeHex())
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { messages.add(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        try {
            server.start()
            relay.connect(server.url("/"), MeshDevice("node//order", "mesh//fixture", "Fixture", conn = 1, agentId = 3))
            await { peers.isNotEmpty() }
            // What the desktop screen and workspace do as soon as they appear.
            assertFalse(relay.setQuality(jpegQuality = 40, frameRate = 15, scaling = 512))
            assertFalse(relay.selectDisplay(2))
            assertFalse(relay.setPaused(false))
            assertFalse(relay.requestRefresh())
            assertFalse(relay.sendRemoteKey(65, true))
            Thread.sleep(200)
            peers.single().send("c")
            await { relay.desktopState.value.handshakeComplete }
            val saved = DesktopQuality(40, 15, 512).packet()
            await { messages.any { it is ByteArray && it.contentEquals(saved) } }
            await { messages.any { it is ByteArray && it.contentEquals(KvmInputEncoder.setDisplay(2)) } }
            assertEquals("2", messages.first())
        } finally { relay.disconnect(); scope.cancel(); http.dispatcher.executorService.shutdown(); server.shutdown() }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("Desktop protocol order condition timed out", condition())
    }
}
