package ru.papajon.meshcentralmobile.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Explicit reconnect only: a new relay, a new shell, and no replay of input or credentials. */
class TerminalReconnectTest {
    private class FakeRelay(override val protocol: RelayProtocol) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val texts = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()
        @Volatile var connects = 0
        @Volatile var closed = false
        override fun connect() { connects++; state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = (!closed).also { if (it) texts.add(value) }
        override fun sendBinary(value: ByteArray) = (!closed).also { if (it) binary.add(value) }
        override fun close() { closed = true; state.value = RelayStatus(RelayPhase.Closed) }
        fun receive(text: String) { check(frames.trySend(RelayFrame.Text(text)).isSuccess) }
    }

    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factoryCalls = AtomicInteger()
        val queued = ArrayDeque<FakeRelay>()
        lateinit var controller: TerminalController

        fun start(protocol: RelayProtocol, vararg reconnects: FakeRelay): FakeRelay {
            val first = FakeRelay(protocol)
            queued.addAll(reconnects.toList())
            controller = TerminalController(first, scope, inputDispatcher = Dispatchers.Unconfined,
                connectionFactory = { factoryCalls.incrementAndGet(); queued.pollFirst() })
            controller.connect()
            return first
        }

        fun finish() { controller.close(); scope.cancel() }
    }

    @Test fun manualReconnectUsesFreshRelayAndNeverReplaysInputOrCredentials() {
        val second = FakeRelay(RelayProtocol.SshTerminal)
        val third = FakeRelay(RelayProtocol.SshTerminal)
        val session = Session()
        val first = session.start(RelayProtocol.SshTerminal, second, third)
        try {
            first.receive("{\"action\":\"sshauth\"}")
            assertEquals(TerminalAuth.PasswordOrKey, session.controller.state.value.auth)
            session.controller.authenticate("synthetic-user", "synthetic-password")
            assertEquals(1, first.texts.size)
            assertEquals(0, session.factoryCalls.get()) // A lost connection never reconnects by itself.

            session.controller.disconnect()
            assertTrue(session.controller.state.value.canReconnect)
            assertEquals(0, session.factoryCalls.get())
            assertTrue(session.controller.reconnect("New terminal session"))
            assertEquals(1, session.factoryCalls.get())
            assertEquals(1, second.connects)
            assertTrue(first.closed)
            assertFalse(session.controller.state.value.canReconnect)
            assertEquals(TerminalAuth.None, session.controller.state.value.auth)
            assertTrue(second.texts.isEmpty()) // No stored credential is submitted automatically.

            // A late frame/status on the ended relay must not affect the new session.
            first.receive("{\"action\":\"sshauth\"}")
            first.state.value = RelayStatus(RelayPhase.Failed)
            assertEquals(TerminalAuth.None, session.controller.state.value.auth)
            assertNotEquals(RelayPhase.Failed, session.controller.state.value.phase)

            second.receive("{\"action\":\"sshauth\"}")
            assertEquals(TerminalAuth.PasswordOrKey, session.controller.state.value.auth)
            assertTrue("No sshauth frame before the user submits it", second.texts.isEmpty())
            second.receive("{\"action\":\"sshautoauth\"}") // Server-saved credentials are server initiated.
            assertEquals("sshautoauth", JSONObject(second.texts.single()).getString("action"))

            session.controller.disconnect()
            assertTrue(session.controller.reconnect("Another session"))
            assertEquals(2, session.factoryCalls.get())
            assertEquals(1, third.connects)
            assertTrue(second.closed)
            assertTrue(third.texts.isEmpty())
            assertEquals(1, second.texts.size) // Only sshautoauth was sent; no credential replay.
        } finally { session.finish() }
    }

    @Test fun eachExplicitReconnectCreatesAFreshConnectionAndCloseIsFinal() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val third = FakeRelay(RelayProtocol.AgentTerminal)
        val session = Session()
        val first = session.start(RelayProtocol.AgentTerminal, second, third)
        try {
            session.controller.disconnect()
            assertTrue(session.controller.reconnect("session one"))
            session.controller.disconnect()
            assertTrue(session.controller.reconnect("session two"))
            assertEquals(2, session.factoryCalls.get())
            assertEquals(1, first.connects)
            assertEquals(1, second.connects)
            assertEquals(1, third.connects)
            assertTrue(first.closed)
            assertTrue(second.closed)
            assertFalse(third.closed)

            session.controller.close()
            assertFalse(session.controller.state.value.canReconnect)
            assertFalse(session.controller.reconnect("after disposal"))
            assertEquals(2, session.factoryCalls.get()) // close() is final and never asks the factory.
            assertTrue(third.closed)
        } finally { session.scope.cancel() }
    }

    @Test fun agentReconnectKeepsInputAndOutputLimitsWithoutReplayingInput() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val session = Session()
        val first = session.start(RelayProtocol.AgentTerminal, second)
        try {
            assertTrue(session.controller.sendInput("before\r"))
            session.controller.disconnect()
            assertTrue(session.controller.reconnect("New terminal session"))
            assertTrue(second.binary.isEmpty())
            assertTrue(session.controller.sendInput("after\r"))
            assertEquals("after\r", second.binary.single().toString(Charsets.UTF_8))
            assertEquals(1, first.binary.size)
            assertEquals("before\r", first.binary.single().toString(Charsets.UTF_8))

            assertFalse(session.controller.sendInput("x".repeat(TerminalController.MAX_INPUT_BYTES + 1)))
            assertEquals(1, second.binary.size)
            second.frames.trySend(RelayFrame.Binary(ByteArray(TerminalController.MAX_PENDING_BYTES + 1)))
            assertEquals(RelayPhase.Failed, session.controller.state.value.phase)
            assertTrue(second.closed)
            assertTrue(session.controller.state.value.canReconnect)
        } finally { session.finish() }
    }

    @Test fun delayedRendererInputCannotCrossTheSessionBoundary() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val session = Session()
        session.start(RelayProtocol.AgentTerminal, second)
        try {
            val oldEpoch = session.controller.rendererEpoch
            session.controller.disconnect()
            assertTrue(session.controller.reconnect("New session"))
            assertFalse(session.controller.sendRendererInput("delayed command\r", oldEpoch))
            assertTrue(second.binary.isEmpty())
            assertTrue(session.controller.sendRendererInput("new command\r", session.controller.rendererEpoch))
            assertEquals("new command\r", second.binary.single().toString(Charsets.UTF_8))
        } finally { session.finish() }
    }

    @Test fun markerIsPlainTextAndResetKeepsScrollback() {
        assertEquals("bad[31m\nnext", TerminalController.sanitizeSessionMarker("bad\u001b[31m\r\nnext\u0007"))
        val payload = String(TerminalController.sessionStartPayload("line\nnext"), Charsets.UTF_8)
        assertTrue(payload.contains(TerminalController.SESSION_RESET))
        assertTrue(payload.contains("line\nnext"))
        assertFalse("A reconnect must not clear the screen", payload.contains("\u001b[2J"))
        assertFalse("A reconnect must not clear scrollback", payload.contains("\u001b[3J"))
        assertFalse("User controls must not become terminal commands", payload.contains("\u001b[31m"))
    }

    @Test fun sessionBoundaryMarkerStaysOrderedAndBounded() = runBlocking {
        val buffer = TerminalOutputBuffer(32) {}
        assertEquals(TerminalOutputBuffer.Result.Enqueued, buffer.append("old".toByteArray()))
        assertEquals(TerminalOutputBuffer.Result.Enqueued, buffer.enqueueSessionStart("marker".toByteArray()))
        assertEquals(TerminalOutputBuffer.Result.Enqueued, buffer.append("new".toByteArray()))

        assertFalse(buffer.peekIsSessionStart())
        assertEquals("old", buffer.peek()?.toString(Charsets.UTF_8))
        buffer.consumed()
        assertTrue(buffer.peekIsSessionStart())
        assertEquals("marker", buffer.peek()?.toString(Charsets.UTF_8))
        buffer.consumed()
        assertFalse(buffer.peekIsSessionStart())
        assertEquals("new", buffer.peek()?.toString(Charsets.UTF_8))
        buffer.consumed()

        assertEquals(TerminalOutputBuffer.Result.Enqueued, buffer.append(ByteArray(32)))
        assertEquals("No space for an unbounded marker wait", TerminalOutputBuffer.Result.Oversize,
            buffer.enqueueSessionStart(byteArrayOf(1)))
        buffer.close()
    }
}
