package ru.papajon.meshcentralmobile.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus

class TerminalControllerTest {
    private class FakeRelay(override val protocol: RelayProtocol) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val texts = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()
        var connects = 0
        var closed = false
        var accept = true
        override fun connect() { connects++; state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = (!closed && accept).also { if (it) texts.add(value) }
        override fun sendBinary(value: ByteArray) = (!closed && accept).also { if (it) binary.add(value) }
        override fun close() { closed = true; state.value = RelayStatus(RelayPhase.Closed) }
        fun receive(text: String) { check(frames.trySend(RelayFrame.Text(text)).isSuccess) }
    }

    private fun withSession(protocol: RelayProtocol, block: (FakeRelay, TerminalController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val relay = FakeRelay(protocol)
        val controller = TerminalController(relay, scope, inputDispatcher = Dispatchers.Unconfined)
        try { block(relay, controller) } finally { controller.close(); scope.cancel() }
    }

    @Test fun agentUsesUtf8AndDoesNotReplayOnReattachOrDisconnect() = withSession(RelayProtocol.AgentTerminal) { relay, controller ->
        controller.connect()
        assertTrue(controller.sendInput("你好 Привет\r"))
        controller.connect() // rotation does not create another connection
        assertEquals(1, relay.connects)
        assertEquals("你好 Привет\r", relay.binary.single().toString(Charsets.UTF_8))
        relay.close()
        assertFalse(controller.sendInput("must not send"))
        controller.connect()
        assertEquals(1, relay.connects)
        assertEquals(1, relay.binary.size)
    }

    @Test fun sshWebsocketOpenIsNotShellReadyAndAuthenticationNeverPersists() = withSession(RelayProtocol.SshTerminal) { relay, controller ->
        controller.connect()
        assertFalse(controller.state.value.ready)
        assertFalse(controller.sendInput("early"))
        relay.receive("{\"action\":\"sshauth\"}")
        assertEquals(TerminalAuth.PasswordOrKey, controller.state.value.auth)
        controller.authenticate("synthetic-user", "synthetic-password")
        val auth = JSONObject(relay.texts.single())
        assertEquals("sshauth", auth.getString("action"))
        assertEquals(0, auth.getInt("keep"))
        assertEquals(80, auth.getInt("cols"))
        assertFalse(controller.state.value.ready)
        assertEquals(TerminalAuth.None, controller.state.value.auth)
        assertTrue(controller.state.value.awaitingShell)
        relay.receive("c")
        assertEquals(TerminalAuth.None, controller.state.value.auth)
        assertTrue(controller.state.value.awaitingShell)
        assertFalse(controller.state.value.ready)
        assertFalse(controller.sendInput("must not be queued\r"))
        relay.receive("~") // Empty data is not proof that a shell is attached.
        assertFalse(controller.state.value.ready)
        assertFalse(controller.sendInput("still must not be queued\r"))
        relay.receive("~synthetic-prompt$ ")
        assertTrue(controller.state.value.ready)
        assertFalse(controller.state.value.awaitingShell)
        assertTrue(controller.sendInput("你好\r"))
        assertEquals("~你好\r", relay.texts.last())
        assertEquals(listOf("~你好\r"), relay.texts.filter { it.startsWith("~") })
        assertTrue(relay.binary.isEmpty())
    }

    @Test fun sshPrivateKeyAndStoredKeyPassphraseHaveDistinctMessages() = withSession(RelayProtocol.SshTerminal) { relay, controller ->
        controller.connect()
        relay.receive("{\"action\":\"sshauth\"}")
        controller.authenticate("test", "", "synthetic-key", "synthetic-passphrase")
        val key = JSONObject(relay.texts.last())
        assertTrue(key.has("key")); assertFalse(key.has("password")); assertEquals(0, key.getInt("keep"))
        relay.receive("{\"action\":\"sshauth\",\"askkeypass\":true}")
        controller.authenticate("", "", keyPassphrase = "synthetic-passphrase")
        val pass = JSONObject(relay.texts.last())
        assertEquals("sshkeyauth", pass.getString("action"))
        assertFalse(pass.has("username")); assertFalse(pass.has("keep"))
    }

    @Test fun sshStoredCredentialsNeedServerRequestAndResizeUsesSshSchema() = withSession(RelayProtocol.SshTerminal) { relay, controller ->
        controller.connect()
        assertTrue(relay.texts.isEmpty())
        relay.receive("{\"action\":\"sshautoauth\"}")
        assertEquals("sshautoauth", JSONObject(relay.texts.single()).getString("action"))
        relay.receive("c")
        relay.receive("~synthetic-prompt$ ")
        controller.resize(132, 43, 1000, 700)
        val resize = JSONObject(relay.texts.last())
        assertEquals("resize", resize.getString("action"))
        assertEquals(132, resize.getInt("cols")); assertEquals(43, resize.getInt("rows"))
        assertEquals(1000, resize.getInt("width")); assertEquals(700, resize.getInt("height"))
    }

    @Test fun emptySshOutputTimesOutAndLatePromptCannotEnableInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val relay = FakeRelay(RelayProtocol.SshTerminal)
        val controller = TerminalController(relay, scope, shellReadyTimeoutMillis = 20, inputDispatcher = Dispatchers.Unconfined)
        try {
            controller.connect()
            relay.receive("c")
            relay.receive("~")
            withTimeout(2000) { controller.state.first { it.phase == RelayPhase.Failed } }
            withTimeout(2000) { relay.state.first { it.phase == RelayPhase.Closed } }
            assertTrue(relay.closed)
            assertFalse(controller.state.value.awaitingShell)
            assertFalse(controller.state.value.ready)
            relay.receive("~late-prompt$ ")
            assertFalse(controller.sendInput("must not send\r"))
            assertTrue(relay.texts.none { it.startsWith("~") })
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun silentSshWebsocketTimesOutBeforeAnyAuthenticationMessageOrC() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val relay = FakeRelay(RelayProtocol.SshTerminal)
        val controller = TerminalController(relay, scope, shellReadyTimeoutMillis = 20, inputDispatcher = Dispatchers.Unconfined)
        try {
            controller.connect()
            withTimeout(2000) { relay.state.first { it.phase == RelayPhase.Closed } }
            assertEquals(RelayPhase.Failed, controller.state.value.phase)
            assertEquals(UiText.Resource(R.string.terminal_session_timeout), controller.state.value.message)
            relay.receive("c")
            relay.receive("~late-prompt$ ")
            assertFalse(controller.state.value.ready)
            assertFalse(controller.sendInput("must not send\r"))
            assertTrue(relay.texts.isEmpty())
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun credentialEntryPausesDeadlineButSubmittedCredentialsMustReceiveC() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val relay = FakeRelay(RelayProtocol.SshTerminal)
        val controller = TerminalController(relay, scope, shellReadyTimeoutMillis = 50, inputDispatcher = Dispatchers.Unconfined)
        try {
            controller.connect()
            relay.receive("{\"action\":\"sshauth\"}")
            delay(100) // Exceed the network deadline while the human is still entering credentials.
            assertFalse(relay.closed)
            assertEquals(TerminalAuth.PasswordOrKey, controller.state.value.auth)
            controller.authenticate("synthetic-user", "synthetic-password")
            withTimeout(2000) { relay.state.first { it.phase == RelayPhase.Closed } }
            assertEquals(RelayPhase.Failed, controller.state.value.phase)
            assertEquals(UiText.Resource(R.string.terminal_session_timeout), controller.state.value.message)
            assertEquals(1, relay.texts.size)
            assertEquals("sshauth", JSONObject(relay.texts.single()).getString("action"))
            assertFalse(controller.sendInput("must not retry\r"))
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun storedSshAuthenticationAlsoTimesOutBeforeC() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val relay = FakeRelay(RelayProtocol.SshTerminal)
        val controller = TerminalController(relay, scope, shellReadyTimeoutMillis = 20, inputDispatcher = Dispatchers.Unconfined)
        try {
            controller.connect()
            relay.receive("{\"action\":\"sshautoauth\"}")
            withTimeout(2000) { relay.state.first { it.phase == RelayPhase.Closed } }
            assertEquals(UiText.Resource(R.string.terminal_session_timeout), controller.state.value.message)
            assertEquals("sshautoauth", JSONObject(relay.texts.single()).getString("action"))
            assertFalse(controller.state.value.ready)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun authenticationFailureSurvivesTransportClose() = withSession(RelayProtocol.SshTerminal) { relay, controller ->
        controller.connect()
        relay.receive("{\"action\":\"autherror\"}")
        assertEquals(RelayPhase.Failed, controller.state.value.phase)
        assertTrue(relay.closed)
        assertFalse(controller.sendInput("later"))
    }

    @Test fun oversizedOutputFrameFailsClosed() = withSession(RelayProtocol.AgentTerminal) { relay, controller ->
        controller.connect()
        relay.frames.trySend(RelayFrame.Binary(ByteArray(TerminalController.MAX_PENDING_BYTES + 1)))
        assertTrue(relay.closed)
        assertEquals(RelayPhase.Failed, controller.state.value.phase)
    }

    @Test fun sshConnectionErrorClosesWithoutExposingRemoteErrorText() = withSession(RelayProtocol.SshTerminal) { relay, controller ->
        controller.connect()
        relay.receive("{\"action\":\"connectionerror\",\"message\":\"not-for-display\"}")
        assertEquals(RelayPhase.Failed, controller.state.value.phase)
        assertTrue(relay.closed)
        assertFalse(controller.sendInput("retry"))
    }

    @Test fun rejectedInputClosesWithoutRetry() = withSession(RelayProtocol.AgentTerminal) { relay, controller ->
        controller.connect()
        relay.accept = false
        assertFalse(controller.sendInput("one command\r"))
        relay.accept = true
        assertFalse(controller.sendInput("one command\r"))
        assertTrue(relay.binary.isEmpty())
        assertEquals(RelayPhase.Failed, controller.state.value.phase)
    }

    @Test fun resizeAgentUsesControlChannelAndBoundsDimensions() = withSession(RelayProtocol.AgentTerminal) { relay, controller ->
        controller.connect()
        controller.resize(10000, 10000, 10000, 10000)
        val size = JSONObject(relay.texts.last())
        assertEquals("102938", size.getString("ctrlChannel"))
        assertEquals("termsize", size.getString("type"))
        assertEquals(500, size.getInt("cols")); assertEquals(300, size.getInt("rows"))
    }
}
