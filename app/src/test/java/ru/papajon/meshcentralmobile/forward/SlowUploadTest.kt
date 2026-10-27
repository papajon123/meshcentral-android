package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class SlowUploadTest {
    @Test fun meshClientDispatchesTaggedRelayToExplicitTimeoutsWithoutChangingControlClient() {
        val base = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://example.test/meshrelay.ashx")
            .tag(RawTcpTransport::class.java, RawTcpTransport).build()
        val raw = ru.papajon.meshcentralmobile.mesh.MeshCentralClient.transportFor(request, base) {
            error("A raw relay must not take the control-client path")
        }
        assertEquals(120_000, raw.pingIntervalMillis)
        assertEquals(120_000, raw.writeTimeoutMillis)
        assertEquals(30_000, base.pingIntervalMillis)
        assertEquals(10_000, base.writeTimeoutMillis)
        assertSame(base.sslSocketFactory, raw.sslSocketFactory)
        assertSame(base.hostnameVerifier, raw.hostnameVerifier)
        assertSame(base.cookieJar, raw.cookieJar)
        val ordinary = request.newBuilder().tag(RawTcpTransport::class.java, null).build()
        val control = base.newBuilder().writeTimeout(12, TimeUnit.SECONDS).build()
        assertSame(control, ru.papajon.meshcentralmobile.mesh.MeshCentralClient.transportFor(ordinary, base) { control })
    }

    @Test fun largeUploadSurvivesReaderPausedBeyondDefaultWriteTimeout() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = RawTcpTransport.client(OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build())
        val received = MessageDigest.getInstance("SHA-256")
        val expected = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(32768) { (it % 251).toByte() }
        val total = 32L * 1024 * 1024
        val firstFrame = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val failure = AtomicReference<ForwardError?>()
        var bridge: TcpRelayBridge? = null
        try { MockWebServer().use { remote ->
            remote.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                var bytes = 0L
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
                override fun onMessage(webSocket: WebSocket, data: ByteString) {
                    if (bytes == 0L) { firstFrame.countDown(); releaseReader.await(25, TimeUnit.SECONDS) }
                    received.update(data.toByteArray()); bytes += data.size
                    if (bytes == total) webSocket.send(received.digest().toByteString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            remote.start(InetAddress.getByName("127.0.0.1"), 0)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                Socket("127.0.0.1", listener.localPort).use { peer ->
                    peer.soTimeout = 30_000
                    val local = listener.accept()
                    bridge = TcpRelayBridge(local, client, Request.Builder().url(remote.url("/relay")).build(),
                        scope, { true }, { _, _ -> }, { failure.set(it) })
                    val writerFailure = AtomicReference<Throwable?>()
                    val writer = Thread {
                        try { repeat((total / chunk.size).toInt()) { peer.getOutputStream().write(chunk); expected.update(chunk) } }
                        catch (error: Throwable) { writerFailure.set(error) }
                    }
                    try {
                        bridge!!.start(); writer.start()
                        assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
                        Thread.sleep(15_000)
                        assertNull("Default 10-second WebSocket write timeout broke upload", failure.get())
                        assertTrue("The producer must really be backpressured", writer.isAlive)
                        releaseReader.countDown()
                        writer.join(30_000)
                        assertFalse(writer.isAlive)
                        assertNull(writerFailure.get())
                        assertArrayEquals(expected.digest(), peer.getInputStream().readNBytes(32))
                    } finally { releaseReader.countDown(); bridge?.close(); peer.close(); writer.join(5_000) }
                }
            }
        }
        } finally {
            scope.cancel(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
