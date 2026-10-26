package ru.papajon.meshcentralmobile.mesh

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StylusRelayInputTest {
    @Test fun inputLockReleasesEveryOwnedPenButtonOnTheSameSocket() = withRelay { relay, socket, packets ->
        for (button in MouseButton.entries) assertTrue(relay.sendMouseButton(10, 20, button, true))
        repeat(3) { assertNotNull(packets.poll(3, TimeUnit.SECONDS)) }
        assertTrue(relay.sendMouseMove(100, 200))
        assertEquals("0002000a0000006400c8", packets.poll(3, TimeUnit.SECONDS))
        socket.send(byteArrayOf(0, 87, 0, 5, 1).toByteString())
        val deadline = System.currentTimeMillis() + 3000
        while (relay.desktopState.value.inputEnabled && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(relay.desktopState.value.inputEnabled)
        val releases = (1..3).map { packets.poll(3, TimeUnit.SECONDS) }.toSet()
        assertEquals(setOf("0002000a0004006400c8", "0002000a0010006400c8", "0002000a0040006400c8"), releases)
        assertFalse(relay.sendMouseButton(999, 999, MouseButton.Left, false))
        assertFalse(relay.sendMouseButton(999, 999, MouseButton.Left, true))
        assertNull(packets.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun backgroundPauseReleasesPenBeforePausingStream() = withRelay { relay, _, packets ->
        assertTrue(relay.sendMouseButton(10, 20, MouseButton.Right, true))
        assertEquals("0002000a0008000a0014", packets.poll(3, TimeUnit.SECONDS))
        assertTrue(relay.setPaused(true))
        assertEquals("0002000a0010000a0014", packets.poll(3, TimeUnit.SECONDS))
    }

    private fun withRelay(check: (MeshDesktopRelay, WebSocket, LinkedBlockingQueue<String>) -> Unit) {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope, sendControlMessage = { true })
        val packets = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val computer = MeshDevice("node//stylus", "mesh//fixture", "Stylus fixture", agentId = 3, conn = 1, rights = 8)
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.offer(webSocket); webSocket.send("c") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == "2") webSocket.send(byteArrayOf(0, 7, 0, 8, 3, 32, 2, 88).toByteString())
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size >= 4 && bytes[1].toInt() == 2) packets.offer(bytes.hex())
                }
            }))
            server.start()
            relay.connect(server.url("/"), computer)
            val socket = requireNotNull(sockets.poll(3, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 5000
            while (!relay.desktopState.value.inputEnabled && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(relay.desktopState.value.inputEnabled)
            check(relay, socket, packets)
        } finally {
            relay.disconnect(); scope.cancel()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown()
        }
    }
}
