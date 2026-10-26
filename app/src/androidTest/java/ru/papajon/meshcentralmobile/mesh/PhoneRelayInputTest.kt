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

class PhoneRelayInputTest {
    @Test fun revocationReleasesOnlyAnExistingPhonePressOnItsOriginalSocket() {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        var permitted = true
        val relay = MeshDesktopRelay(http, scope, phoneInputAllowed = { permitted }, sendControlMessage = { true })
        val packets = LinkedBlockingQueue<String>()
        var serverSocket: WebSocket? = null
        val phone = MeshDevice("node//phone", "mesh//test", "Phone", agentId = 14, conn = 1, rights = 8,
            remoteInput = PhoneRemoteInput.Enabled)
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket = webSocket; webSocket.send("c") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == "2") webSocket.send(byteArrayOf(0, 7, 0, 8, 4, 56, 7, -128).toByteString())
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size >= 4 && bytes[1].toInt() in setOf(1, 2, 14, 85)) packets.offer(bytes.hex())
                }
            }))
            server.start()
            relay.connect(server.url("/"), phone)
            val deadline = System.currentTimeMillis() + 5000
            while (!relay.desktopState.value.inputEnabled && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(relay.desktopState.value.inputEnabled)
            // No key-up or touch-init packets may request consent just by opening a phone viewer.
            assertNull(packets.poll(100, TimeUnit.MILLISECONDS))
            assertTrue(relay.sendMouseButton(300, 600, MouseButton.Left, true))
            assertEquals("0002000a0002012c0258", packets.poll(3, TimeUnit.SECONDS))
            assertTrue(relay.sendMouseMove(300, 500))
            assertEquals("0002000a0000012c01f4", packets.poll(3, TimeUnit.SECONDS))
            permitted = false
            relay.updatePhoneDevice(phone.copy(remoteInput = PhoneRemoteInput.Unknown))
            assertEquals("0002000a0004012c01f4", packets.poll(3, TimeUnit.SECONDS))
            assertFalse(relay.sendMouseButton(5, 5, MouseButton.Left, true))
            assertFalse(relay.sendMouseButton(5, 5, MouseButton.Left, false))
            assertFalse(relay.sendRemoteKey(36, true))
            assertEquals(0, relay.sendUnicodeText("blocked"))
            assertNull(packets.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            relay.disconnect(); serverSocket?.close(1000, null); scope.cancel()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown()
        }
    }
}
