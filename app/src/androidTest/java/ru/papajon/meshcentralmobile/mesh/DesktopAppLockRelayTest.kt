package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class DesktopAppLockRelayTest {
    @Test fun lockDropsFramesAndCannotBeOverriddenByResumeNavigationOrReconnect() {
        val packets = ConcurrentLinkedQueue<ByteArray>()
        val peers = ConcurrentLinkedQueue<WebSocket>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope) { true }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff116655.toInt()) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val tile = byteArrayOf(0, 3, ((png.size + 8) shr 8).toByte(), (png.size + 8).toByte(), 0, 0, 0, 0) + png
        val size = byteArrayOf(0, 7, 0, 8, 3, 32, 2, 88)
        fun sendImage(socket: WebSocket) { socket.send(ByteString.of(*size)); socket.send(ByteString.of(*tile)) }
        fun fixture() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peers.add(webSocket); webSocket.send("c"); sendImage(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                packets.add(bytes.toByteArray())
                if (bytes.toByteArray().contentEquals(KvmInputEncoder.refresh())) sendImage(webSocket)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        try {
            server.enqueue(fixture()); server.enqueue(fixture()); server.start()
            relay.connect(server.url("/"), MeshDevice("node//lock", "mesh//fixture", "Fixture", conn = 1, agentId = 3))
            await { relay.desktopState.value.framebuffer?.getPixel(0, 0) == 0xff116655.toInt() }
            relay.setAppLocked(true)
            await { packets.any { it.contentEquals(KvmInputEncoder.pause(true)) } }
            assertNull(relay.desktopState.value.framebuffer)
            val unpauses = packets.count { it.contentEquals(KvmInputEncoder.pause(false)) }
            relay.setInputSuppressed(false)
            relay.setPaused(false)
            assertEquals(DesktopResumeAction.None, relay.resumeAfterBackground(true))
            assertFalse(relay.requestRefresh())
            assertFalse(relay.sendRemoteKey(65, true))
            sendImage(peers.last()) // A queued frame cannot reappear after lock.
            Thread.sleep(200)
            assertNull(relay.desktopState.value.framebuffer)
            relay.reconnect()
            await { peers.size == 2 }
            Thread.sleep(200)
            assertNull(relay.desktopState.value.framebuffer)
            assertEquals(unpauses, packets.count { it.contentEquals(KvmInputEncoder.pause(false)) })
            relay.setAppLocked(false)
            await { relay.desktopState.value.framebuffer?.getPixel(0, 0) == 0xff116655.toInt() }
            assertTrue(relay.sendRemoteKey(65, true))
        } finally { relay.disconnect(); scope.cancel(); http.dispatcher.executorService.shutdown(); server.shutdown(); bitmap.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("Locked desktop relay condition timed out", condition())
    }
}
