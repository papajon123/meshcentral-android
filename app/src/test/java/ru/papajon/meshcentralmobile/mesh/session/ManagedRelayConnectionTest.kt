package ru.papajon.meshcentralmobile.mesh.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ManagedRelayConnectionTest {
    @Test fun androidDownloadUsesP10HandshakeAndDrainsBeforeEof() = fixture(RelayProtocol.AgentFileDownload) { f ->
        f.connection.connect(); f.open(); f.text("c")
        assertEquals("10", f.factory.socket.request().url.queryParameter("p"))
        assertEquals(10, f.control.single().getInt("usage"))
        assertEquals("options", JSONObject(f.factory.socket.sentText[0]).getString("type"))
        assertEquals("10", f.factory.socket.sentText[1])
        f.text("""{"op":"ok","size":3}""")
        f.factory.listener.onMessage(f.factory.socket, byteArrayOf(123, 0, 42).toByteString())
        f.factory.listener.onClosing(f.factory.socket, 1000, "")
        val frames = mutableListOf<RelayFrame>()
        runBlocking { withTimeout(2000) { f.connection.incoming.collect { frames += it } } }
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(123, 0, 42), (frames.last() as RelayFrame.Binary).value)
        assertEquals(RelayPhase.Closed, f.connection.state.value.phase)
    }

    @Test fun agentHandshakeOrdersOptionsBeforeProtocolAndReportsRecording() = fixture { f ->
        f.connection.connect()
        assertEquals("5", f.factory.socket.request().url.queryParameter("p"))
        assertEquals("cookie", f.factory.socket.request().url.queryParameter("auth"))
        assertEquals("relay-cookie", f.control.single().getString("value").substringAfter("rauth="))
        assertEquals(5, f.control.single().getInt("usage"))
        assertTrue(f.control.single().getString("value").startsWith("*/team/meshrelay.ashx?p=5&"))
        assertFalse(f.connection.sendText("before-ready"))
        f.open()
        f.text("cr")
        assertEquals("options", JSONObject(f.factory.socket.sentText[0]).getString("type"))
        assertFalse(JSONObject(f.factory.socket.sentText[0]).has("action"))
        assertEquals("5", f.factory.socket.sentText[1])
        assertEquals(RelayPhase.Connected, f.connection.state.value.phase)
        assertTrue(f.connection.state.value.recorded)
        f.text("""{"ctrlChannel":"102938","type":"ping"}""")
        assertEquals("pong", JSONObject(f.factory.socket.sentText.last()).getString("type"))
    }

    @Test fun sshUsesSeparateEndpointWithoutAgentHandshakeOrTunnelCommand() = fixture(RelayProtocol.SshTerminal) { f ->
        f.connection.connect()
        assertEquals("/team/sshterminalrelay.ashx", f.factory.socket.request().url.encodedPath)
        assertTrue(f.control.isEmpty())
        f.open()
        assertEquals(RelayPhase.Connected, f.connection.state.value.phase)
        f.text("c")
        assertTrue(f.factory.socket.sentText.isEmpty())
        assertEquals(RelayFrame.Text("c"), runBlocking { f.connection.incoming.first() })
        assertTrue(f.connection.sendText("~echo test\r"))
    }

    @Test fun sftpEndpointIsNotTheAgentFilesEndpoint() = fixture(RelayProtocol.SshFiles) { f ->
        f.connection.connect()
        assertEquals("/team/sshfilesrelay.ashx", f.factory.socket.request().url.encodedPath)
        assertTrue(f.control.isEmpty())
    }

    @Test fun closeIsFinalAndLateCallbacksNeverReopenOrReplay() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        assertTrue(f.connection.sendText("one-operation"))
        f.connection.close(); f.connection.close(); f.connection.connect(); f.open(); f.text("c")
        assertEquals(RelayPhase.Closed, f.connection.state.value.phase)
        assertEquals(1, f.factory.created)
        assertEquals(1, f.factory.socket.sentBinary.count { it.utf8() == "one-operation" })
        assertFalse(f.connection.sendText("do-not-send"))
        assertFalse(f.connection.sendBinary(byteArrayOf(1)))
        assertEquals(1, f.finished)
    }

    @Test fun burstBackpressuresSlowConsumerWithoutClosingOrDroppingFrames() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        val full = CountDownLatch(1)
        val sent = AtomicInteger()
        val producer = thread(isDaemon = true) {
            repeat(32) { index ->
                if (index == ManagedRelayConnection.INCOMING_FRAMES) full.countDown()
                f.text("frame-$index")
                sent.incrementAndGet()
            }
        }
        assertTrue(full.await(2, TimeUnit.SECONDS))
        assertEquals(ManagedRelayConnection.INCOMING_FRAMES, sent.get())
        assertEquals(RelayPhase.Connected, f.connection.state.value.phase)
        // A blocked incoming callback must not hold the connection lock needed by outbound input.
        assertTrue(f.connection.sendText("input-while-output-is-full"))
        val received = mutableListOf<String>()
        runBlocking {
            withTimeout(5000) {
                f.connection.incoming.take(32).collect { frame ->
                    received.add((frame as RelayFrame.Text).value)
                    delay(2)
                }
            }
        }
        producer.join(2000)
        assertFalse(producer.isAlive)
        assertEquals((0 until 32).map { "frame-$it" }, received)
        assertEquals(RelayPhase.Connected, f.connection.state.value.phase)
    }

    @Test fun closeUnblocksFullIncomingQueueWithoutNeedingConsumer() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        repeat(ManagedRelayConnection.INCOMING_FRAMES) { f.text("buffered") }
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val producer = thread(isDaemon = true) { entered.countDown(); f.text("waiting"); exited.countDown() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        f.connection.close()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        producer.join(2000)
        assertEquals(RelayPhase.Closed, f.connection.state.value.phase)
        assertTrue(f.factory.socket.cancelled)
        assertFalse(f.connection.sendText("no-replay"))
    }

    @Test fun ownerCancellationUnblocksFullIncomingQueue() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        repeat(ManagedRelayConnection.INCOMING_FRAMES) { f.text("buffered") }
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        thread(isDaemon = true) { entered.countDown(); f.text("waiting"); exited.countDown() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        f.scope.cancel()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertEquals(RelayPhase.Closed, f.connection.state.value.phase)
    }

    @Test fun sendQueueAndLargeFramesAreBounded() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        f.factory.socket.queued = ManagedRelayConnection.MAX_OUTGOING_BYTES
        assertFalse(f.connection.sendBinary(byteArrayOf(1)))
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
    }

    @Test fun changedAccountBlocksFurtherInput() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        f.valid = false
        assertFalse(f.connection.sendText("command"))
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
    }

    @Test fun knownDenialNeverOpensSocket() = fixture { f ->
        f.available = SessionAvailability.Denied
        f.connection.connect()
        assertEquals(0, f.factory.created)
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
    }

    @Test fun missingCredentialsTimesOutWithoutOpeningSocket() = fixture(authTimeout = 0) { f ->
        f.hasCredentials = false
        f.connection.connect()
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
        assertEquals(0, f.factory.created)
    }

    @Test fun disconnectedSocketDoesNotReconnect() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        f.factory.listener.onFailure(f.factory.socket, java.io.IOException("secret URL must not be surfaced"), null)
        f.connection.connect()
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
        assertEquals(1, f.factory.created)
    }

    @Test fun waitingForAgentHasABoundedTimeout() = fixture(handshakeTimeout = 20) { f ->
        f.connection.connect(); f.open()
        runBlocking { withTimeout(2_000) { f.connection.state.first { it.phase == RelayPhase.Failed } } }
        assertTrue(f.factory.socket.cancelled)
        assertFalse(f.connection.sendText("late-input"))
    }

    @Test fun routingFailureClosesOnlyThisConnectionWithoutRetry() = fixture { f ->
        f.connection.connect(); f.open()
        f.connection.routingFailed()
        f.connection.connect()
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
        assertEquals(1, f.factory.created)
    }

    @Test fun oversizeIncomingBinaryFailsBeforeEnteringConsumerBuffer() = fixture { f ->
        f.connection.connect(); f.open(); f.text("c")
        f.factory.listener.onMessage(f.factory.socket, ByteArray(ManagedRelayConnection.MAX_FRAME_BYTES + 1).toByteString())
        assertEquals(RelayPhase.Failed, f.connection.state.value.phase)
    }

    private fun fixture(protocol: RelayProtocol = RelayProtocol.AgentFiles, authTimeout: Long = 20_000, handshakeTimeout: Long = 60_000, test: (Fixture) -> Unit) {
        val f = Fixture(protocol, authTimeout, handshakeTimeout)
        try { test(f) } finally { f.connection.close(); f.scope.cancel() }
    }

    private class Fixture(protocol: RelayProtocol, authTimeout: Long, handshakeTimeout: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = FakeFactory()
        val control = mutableListOf<JSONObject>()
        var valid = true
        var available = SessionAvailability.Available
        var hasCredentials = true
        var finished = 0
        val connection = ManagedRelayConnection(protocol, "node//test", factory, scope,
            credentials = { if (hasCredentials) RelayCredentials("https://mesh.example/team/".toHttpUrl(), "cookie", "relay-cookie") else null },
            validSession = { valid }, availability = { available }, sendControl = { control.add(it); true },
            onFinished = { finished++ }, options = JSONObject().put("action", "discard").put("consent", 1),
            authTimeoutMillis = authTimeout, handshakeTimeoutMillis = handshakeTimeout)
        fun open() = factory.listener.onOpen(factory.socket, Response.Builder().request(factory.socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun text(value: String) = factory.listener.onMessage(factory.socket, value)
    }

    private class FakeFactory : WebSocket.Factory {
        lateinit var socket: FakeSocket
        lateinit var listener: WebSocketListener
        var created = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            created++
            this.listener = listener
            return FakeSocket(request).also { socket = it }
        }
    }

    private class FakeSocket(private val request: Request) : WebSocket {
        val sentText = mutableListOf<String>()
        val sentBinary = mutableListOf<ByteString>()
        var cancelled = false
        var queued = 0L
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean { if (cancelled) return false; sentText.add(text); return true }
        override fun send(bytes: ByteString): Boolean { if (cancelled) return false; sentBinary.add(bytes); return true }
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
    }
}
