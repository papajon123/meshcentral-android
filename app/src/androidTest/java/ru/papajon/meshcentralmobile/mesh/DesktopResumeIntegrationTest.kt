package ru.papajon.meshcentralmobile.mesh

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopResumeIntegrationTest {
    @Test fun foregroundRefreshesAndAReplacementRelayInitialisesAtTheSameScreenSize() {
        val firstPackets = ConcurrentLinkedQueue<ByteArray>()
        val secondPackets = ConcurrentLinkedQueue<ByteArray>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope) { true }
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(listener(firstPackets)))
            server.enqueue(MockResponse().withWebSocketUpgrade(listener(secondPackets)))
            server.start()
            relay.connect(
                server.url("/"),
                MeshDevice(nodeId = "node/test/one", meshId = "mesh/test/one",
                    name = "Test PC", conn = 1, agentId = 4),
            )
            await { relay.desktopState.value.hasFrame && firstPackets.containsPacket(KvmInputEncoder.refresh()) }

            val initialUnpauses = firstPackets.countPacket(KvmInputEncoder.pause(false))
            val initialRefreshes = firstPackets.countPacket(KvmInputEncoder.refresh())
            assertTrue(relay.setPaused(true))
            assertEquals(DesktopResumeAction.Refresh, relay.resumeAfterBackground(controlReady = true))
            await {
                firstPackets.countPacket(KvmInputEncoder.pause(false)) > initialUnpauses &&
                    firstPackets.countPacket(KvmInputEncoder.refresh()) > initialRefreshes
            }

            relay.reconnect()
            await { secondPackets.containsPacket(KvmInputEncoder.pause(false)) &&
                secondPackets.containsPacket(KvmInputEncoder.refresh()) }
            assertTrue(relay.desktopState.value.hasFrame)
        } finally {
            relay.disconnect()
            scope.cancel()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun listener(packets: ConcurrentLinkedQueue<ByteArray>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("c")
            webSocket.send(ByteString.of(*byteArrayOf(0, 7, 0, 8, 3, 32, 2, 88))) // 800x600
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            packets.add(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    private fun ConcurrentLinkedQueue<ByteArray>.containsPacket(packet: ByteArray): Boolean =
        any { it.contentEquals(packet) }

    private fun ConcurrentLinkedQueue<ByteArray>.countPacket(packet: ByteArray): Int =
        count { it.contentEquals(packet) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for desktop protocol packets", condition())
    }
}
