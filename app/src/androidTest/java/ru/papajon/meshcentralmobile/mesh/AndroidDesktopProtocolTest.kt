package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
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
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDesktopProtocolTest {
    @Test fun officialAgentKeepaliveThenSizeAndJumboProducesVisiblePixels() {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val http = OkHttpClient()
        val relay = MeshDesktopRelay(http, scope) { true }
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.MAGENTA)
        val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        // Same layout as official Android ScreenCaptureService.sendEntireImage: jumbo + tile.
        val size = png.size + 8
        val tile = byteArrayOf(0, 27, 0, 8, (size shr 24).toByte(), (size shr 16).toByte(),
            (size shr 8).toByte(), size.toByte(), 0, 3, 0, 0, 0, 0, 0, 0) + png
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text != "2") return
                    webSocket.send(byteArrayOf(0).toByteString())
                    webSocket.send(byteArrayOf(0, 7, 0, 8, 0, 2, 0, 2).toByteString())
                    webSocket.send(tile.toByteString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }))
            server.start()
            relay.connect(server.url("/"), MeshDevice(nodeId = "node/test/android", meshId = "mesh/test/one",
                name = "Test Android", conn = 1, agentId = 14))
            val deadline = System.currentTimeMillis() + 8_000
            fun visible() = relay.desktopState.value.framebuffer?.getPixel(0, 0) == Color.MAGENTA
            while (!visible() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("The decoded tile, not just an allocated black screen, must be visible", visible())
        } finally {
            relay.disconnect()
            scope.cancel()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
