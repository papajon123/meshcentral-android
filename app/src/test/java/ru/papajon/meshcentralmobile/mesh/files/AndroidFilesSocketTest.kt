package ru.papajon.meshcentralmobile.mesh.files

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.*

/** Real WebSockets replay MeshTunnel 1.0.23/main frames; no invented download header on 1.0.23. */
class AndroidFilesSocketTest {
    @Test fun mainAgentLargeDownloadDrainsAll65535ByteFrames() = largeDownload(true)
    @Test fun releasedAgentLargeDownloadDrainsAll65535ByteFrames() = largeDownload(false)
    @Test fun interruptedMainStreamReportsTransportFailureAfterNineteenFrames() = interruptedDownload(true)
    @Test fun interruptedReleasedStreamReportsTransportFailureAfterNineteenFrames() = interruptedDownload(false)

    private fun interruptedDownload(withHeader: Boolean) = runBlocking {
        Fixture().use { f ->
            f.start()
            assertTrue(f.adapter.sendText("""{"action":"download","sub":"start","path":"Images/test.png","id":8,"size":1347662}"""))
            val p10 = f.downloadSocket()
            if (withHeader) p10.send("""{"op":"ok","size":1347662}""")
            // Exact frame boundaries observed on the live relay before the TLS failure.
            repeat(19) { assertTrue(p10.send(ByteArray(65_535) { 42 }.toByteString())) }
            assertEquals("start", JSONObject((f.next() as RelayFrame.Text).value).getString("sub"))
            var bytes = 0
            while (bytes < 1_245_165) {
                val frame = f.next() as RelayFrame.Binary
                assertFalse(AgentFilesProtocol.downloadFinal(frame.value))
                bytes += frame.value.size - 4
            }
            assertEquals(1_245_165, bytes)
            // An abnormal close models a failed transport; a failed TLS record must likewise never be EOF.
            p10.close(1011, "Synthetic transport failure")
            val error = JSONObject((f.next() as RelayFrame.Text).value)
            assertEquals("cancel", error.getString("sub"))
            assertEquals("transport", error.getString("error"))
            assertEquals(RelayPhase.Connected, f.control.state.value.phase)
        }
    }

    private fun largeDownload(withHeader: Boolean) = runBlocking {
        Fixture().use { f ->
            f.start()
            val data = ByteArray(1_347_662) { (it * 31).toByte() }
            assertTrue(f.adapter.sendText("""{"action":"download","sub":"start","path":"Images/test.png","id":8,"size":${data.size}}"""))
            val p10 = f.downloadSocket()
            if (withHeader) p10.send("""{"op":"ok","size":${data.size}}""")
            var offset = 0
            while (offset < data.size) {
                val count = minOf(65_535, data.size - offset)
                assertTrue(p10.send(data.toByteString(offset, count)))
                offset += count
            }
            p10.close(1000, "")
            assertEquals("start", JSONObject((f.next() as RelayFrame.Text).value).getString("sub"))
            val result = java.io.ByteArrayOutputStream()
            withTimeout(10_000) {
                while (true) {
                    val frame = f.next()
                    assertTrue("Download cancelled after ${result.size()} bytes", frame is RelayFrame.Binary)
                    val packet = (frame as RelayFrame.Binary).value
                    result.write(packet, 4, packet.size - 4)
                    if (AgentFilesProtocol.downloadFinal(packet)) break
                    delay(1) // Exercise bounded queues with a slower SAF-like consumer.
                }
            }
            assertArrayEquals(data, result.toByteArray())
        }
    }

    @Test fun meshRelayEmptyCloseFrameIsANormalEndOfVerifiedDownload() = runBlocking {
        // meshrelay.js closeBothSides calls peer.ws.close() without a code. Replay an
        // actual empty RFC6455 close frame (88 00); OkHttp reports local sentinel 1005.
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = OkHttpClient()
        val serving = scope.async {
            server.accept().use { socket ->
                socket.soTimeout = 4000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                val key = headers.single { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
                output.write(byteArrayOf(0x81.toByte(), 1, 'c'.code.toByte()))
                output.write(byteArrayOf(0x82.toByte(), 3, 1, 2, 3))
                output.write(byteArrayOf(0x88.toByte(), 0))
                output.flush()
                // Keep TCP alive until the viewer receives the close frame.
                runCatching { while (socket.getInputStream().read() != -1) Unit }
            }
        }
        val control = object : RelayConnection {
            override val protocol = RelayProtocol.AgentFiles
            override val state = MutableStateFlow(RelayStatus(RelayPhase.Connected))
            override val incoming = kotlinx.coroutines.flow.flow<RelayFrame> { awaitCancellation() }
            override fun connect() {}
            override fun sendText(value: String) = true
            override fun sendBinary(value: ByteArray) = true
            override fun close() {}
        }
        val adapter = AndroidFilesConnection(control, scope) {
            ManagedRelayConnection(RelayProtocol.AgentFileDownload, "node//phone", client, scope,
                credentials = { RelayCredentials(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.localPort).build(), "fixture", null) },
                validSession = { true }, availability = { SessionAvailability.Available }, sendControl = { true }, onFinished = {})
        }
        try {
            adapter.connect()
            assertTrue(adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/test","id":1,"size":3}"""))
            val frames = withTimeout(6000) { adapter.incoming.take(3).toList() }
            assertTrue("Empty relay Close must permit EOF", frames.last() is RelayFrame.Binary)
            assertTrue(AgentFilesProtocol.downloadFinal((frames.last() as RelayFrame.Binary).value))
        } finally { adapter.close(); server.close(); serving.cancel(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun releasedAgentRawDownloadSurvivesKeepalivesAndRelayMetadata() = download(withHeader = false)
    @Test fun mainAgentHeaderDownloadSurvivesKeepalivesAndRelayMetadata() = download(withHeader = true)

    private fun download(withHeader: Boolean) = runBlocking {
        Fixture().use { f ->
            val p5 = f.start()
            assertTrue(f.adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/test.bin","id":2,"size":6}"""))
            val p10 = f.downloadSocket()
            if (withHeader) p10.send("""{"op":"ok","size":6}""")
            p10.send(byteArrayOf(123, 0, 42).toByteString())
            p5.send(byteArrayOf(0).toByteString())
            p10.send(byteArrayOf(0).toByteString())
            p10.send("""{"ctrlChannel":"102938","type":"pong"}""")
            p10.send("""{"ctrlChannel":"102938","type":"rtt","time":123}""")
            p10.send("pong")
            p10.send(byteArrayOf(3, 2, 1).toByteString())
            p10.close(1000, "")
            val frames = List(4) { f.next() }
            assertEquals("start", JSONObject((frames.first() as RelayFrame.Text).value).getString("sub"))
            assertArrayEquals(byteArrayOf(123, 0, 42, 3, 2, 1), frames.drop(1).flatMap { (it as RelayFrame.Binary).value.drop(4) }.toByteArray())
            assertTrue(AgentFilesProtocol.downloadFinal((frames.last() as RelayFrame.Binary).value))
            assertTrue(f.uncaught.isEmpty())
        }
    }

    @Test fun p5LossDuringDownloadEndsWithoutUncaughtExceptionOrSuccessfulEof() = runBlocking {
        Fixture().use { f ->
            val p5 = f.start()
            assertTrue(f.adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/test.bin","id":3,"size":6}"""))
            val p10 = f.downloadSocket()
            p10.send(byteArrayOf(1, 2, 3).toByteString())
            f.next(); f.next()
            p5.close(1011, "Synthetic transport loss")
            withTimeout(4000) { f.control.state.first { it.phase == RelayPhase.Failed } }
            withTimeout(4000) { f.collector.join() }
            assertTrue(f.uncaught.isEmpty())
            assertFalse(f.remaining().any { it is RelayFrame.Binary && AgentFilesProtocol.downloadFinal(it.value) })
        }
    }

    @Test fun releasedAgentEmptyFileHasVerifiedEofWithoutAHeader() = runBlocking {
        Fixture().use { f ->
            f.start()
            assertTrue(f.adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/empty","id":4,"size":0}"""))
            f.downloadSocket().close(1000, "")
            assertEquals("start", JSONObject((f.next() as RelayFrame.Text).value).getString("sub"))
            assertTrue(AgentFilesProtocol.downloadFinal((f.next() as RelayFrame.Binary).value))
        }
    }

    @Test fun uploadAcknowledgementsIgnoreZerosAndMediaDuplicatesSurviveTheSocket() = runBlocking {
        Fixture().use { f ->
            val p5 = f.start()
            f.adapter.sendText("""{"action":"upload","path":"Sdcard","name":"test.bin","reqid":9}""")
            assertEquals("upload", f.command().getString("action"))
            p5.send(byteArrayOf(0).toByteString())
            p5.send("""{"action":"uploadstart","reqid":9}""".toByteArray().toByteString())
            assertEquals("uploadstart", JSONObject((f.next() as RelayFrame.Binary).value.toString(Charsets.UTF_8)).getString("action"))
            f.adapter.sendBinary(byteArrayOf(0, 123, 0, 42))
            assertArrayEquals(byteArrayOf(0, 123, 0, 42), withTimeout(3000) { f.commands.receive() })
            p5.send(byteArrayOf(0).toByteString())
            p5.send("""{"action":"uploadack","reqid":9}""".toByteArray().toByteString())
            assertEquals("uploadack", JSONObject((f.next() as RelayFrame.Binary).value.toString(Charsets.UTF_8)).getString("action"))
            f.adapter.sendText("""{"action":"ls","path":"Images","reqid":10}""")
            val listing = f.command().put("dir", org.json.JSONArray("""[{"n":"same.jpg","t":3,"s":4,"d":1},{"n":"same.jpg","t":3,"s":8,"d":2}]"""))
            p5.send(listing.toString().toByteArray().toByteString())
            val entries = AgentFilesProtocol.entries(JSONObject((f.next() as RelayFrame.Text).value), phoneMedia = true)
            assertEquals(2, entries.size)
            assertEquals(2, entries.map { it.listingIndex }.distinct().size)
        }
    }

    @Test fun oversizeMediaResponseReportsErrorAndAllowsAnotherDirectory() = runBlocking {
        Fixture().use { f ->
            val p5 = f.start()
            f.adapter.sendText("""{"action":"ls","path":"Images","reqid":1}""")
            f.command()
            val rows = org.json.JSONArray()
            repeat(16_000) { rows.put(JSONObject().put("n", "photo-${it.toString().padStart(6, '0')}-" + "x".repeat(35) + ".jpg").put("t", 3).put("s", 100).put("d", 1)) }
            val response = JSONObject().put("action", "ls").put("reqid", 1).put("path", "Images").put("dir", rows).toString().toByteArray()
            assertTrue(response.size > 1_048_576)
            p5.send(response.toByteString())
            assertEquals("directory-too-large", JSONObject((f.next() as RelayFrame.Text).value).getString("error"))
            assertEquals(RelayPhase.Connected, f.control.state.value.phase)
            f.adapter.sendText("""{"action":"ls","path":"Sdcard","reqid":2}""")
            p5.send(f.command().put("dir", org.json.JSONArray()).toString().toByteArray().toByteString())
            assertEquals("Sdcard", JSONObject((f.next() as RelayFrame.Text).value).getString("path"))
        }
    }

    private class Fixture : java.io.Closeable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val server = MockWebServer()
        val client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val commands = Channel<ByteArray>(16)
        private val p5Ready = Channel<WebSocket>(1)
        private val p10Ready = Channel<WebSocket>(1)
        val control: ManagedRelayConnection
        val adapter: AndroidFilesConnection
        private val output = Channel<RelayFrame>(16)
        val collector: Job

        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(listener(p5Ready, "5")))
            server.enqueue(MockResponse().withWebSocketUpgrade(listener(p10Ready, "10")))
            server.start()
            control = relay(RelayProtocol.AgentFiles)
            adapter = AndroidFilesConnection(control, scope) { relay(RelayProtocol.AgentFileDownload, it) }
            collector = scope.launch {
                try { adapter.incoming.collect { output.send(it) } }
                catch (_: Exception) { /* A failed stream is expected in disconnect fixtures. */ }
                finally { output.close() }
            }
        }

        private fun listener(ready: Channel<WebSocket>, usage: String) = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text == usage) ready.trySend(webSocket) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { commands.trySend(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
        }

        private fun relay(protocol: RelayProtocol, file: String? = null) = ManagedRelayConnection(
            protocol, "node//phone", client, scope,
            credentials = { RelayCredentials(server.url("/"), "synthetic-cookie", null) },
            validSession = { true }, availability = { SessionAvailability.Available }, sendControl = { true }, onFinished = {},
            options = file?.let { JSONObject().put("file", it) }, androidFileListings = protocol == RelayProtocol.AgentFiles,
        )
        suspend fun start(): WebSocket {
            adapter.connect()
            return withTimeout(4000) {
                val socket = p5Ready.receive()
                // The server can receive usage 5 before the client publishes Connected.
                // Downloads check the client state, so server readiness alone is not enough.
                adapter.state.first { it.phase == RelayPhase.Connected }
                socket
            }
        }
        suspend fun downloadSocket() = withTimeout(4000) { p10Ready.receive() }
        suspend fun next() = withTimeout(4000) { output.receive() }
        suspend fun command() = JSONObject(withTimeout(4000) { commands.receive() }.toString(Charsets.UTF_8))
        fun remaining() = buildList { while (true) add(output.tryReceive().getOrNull() ?: break) }
        override fun close() { adapter.close(); scope.cancel(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.close() }
    }
}
