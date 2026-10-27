package ru.papajon.meshcentralmobile.mesh

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class DesktopPlusRelayTest {
    @Test fun requestedQualitySurvivesReconnectAndPipSuppressesEveryInputPath() {
        val packets = ConcurrentLinkedQueue<ByteArray>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope) { true }
        fun fixture() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("c")
                webSocket.send(ByteString.of(*byteArrayOf(0, 7, 0, 8, 3, 32, 2, 88)))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { packets.add(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        try {
            server.enqueue(fixture()); server.enqueue(fixture()); server.start()
            relay.connect(server.url("/"), MeshDevice("node//plus", "mesh//plus", "Fixture", conn = 1, agentId = 4))
            await { relay.desktopState.value.hasFrame && packets.any { it.contentEquals(KvmInputEncoder.refresh()) } }
            relay.setQuality(80, 10, 512)
            val expected = byteArrayOf(0, 5, 0, 10, 1, 80, 2, 0, 0, 100)
            await { packets.any { it.contentEquals(expected) } }
            val before = packets.count { it.contentEquals(expected) }
            relay.reconnect()
            await { packets.count { it.contentEquals(expected) } > before }
            assertEquals(DesktopQuality(80, 10, 512), relay.desktopState.value.quality)
            await { relay.desktopState.value.bytesReceived > 0 }
            relay.sendRemoteKey(17, true)
            relay.sendMouseButton(5, 7, MouseButton.Right, true)
            relay.setInputSuppressed(true)
            await { packets.any { it.contentEquals(KvmInputEncoder.key(17, false)) } &&
                packets.any { it.contentEquals(KvmInputEncoder.mouseButton(5, 7, MouseButton.Right, false)) } }
            assertFalse(relay.sendMouseMove(8, 9))
            assertFalse(relay.sendMouseScroll(8, 9, 120f))
            assertFalse(relay.sendRemoteKey(65, true))
            assertEquals(0, relay.sendUnicodeText("blocked"))
            assertFalse(relay.sendCtrlAltDel())
            relay.setInputSuppressed(false)
            assertTrue(relay.sendRemoteKey(65, true))
            relay.setPaused(true)
            await { packets.any { it.contentEquals(KvmInputEncoder.key(65, false)) } }
            assertFalse(relay.sendMouseMove(9, 10))
        } finally {
            relay.disconnect(); scope.cancel(); http.dispatcher.executorService.shutdown(); server.shutdown()
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("Desktop relay timed out", condition())
    }
}
