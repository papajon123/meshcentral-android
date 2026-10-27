package ru.papajon.meshcentralmobile.mesh.chat

import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatControllerTest {
    @Test fun manualRetryReplacesOldWaitingSocketBeforeItsDeadline() = fixture { f ->
        f.chat.start(); f.open()
        val old = f.sockets.last()
        f.elapsed += 18_000
        f.chat.requestWindow()
        assertEquals(2, f.sockets.size)
        f.open()
        old.listener.onClosing(old, 1000, "expired")
        assertEquals(ChatPhase.Waiting, f.chat.state.value.phase)
        assertEquals(2, f.invites)
    }
    @Test fun fiveConsecutiveProxyFailuresStopAutomaticRetries() = fixture { f ->
        f.chat.start()
        repeat(5) {
            f.sockets.last().let { socket -> socket.listener.onFailure(socket, java.io.IOException("proxy"),
                Response.Builder().request(f.request).protocol(Protocol.HTTP_1_1).code(502).message("Bad Gateway").build()) }
        }
        assertEquals(ChatPhase.Failed, f.chat.state.value.phase)
        assertEquals(5, f.sockets.size)
        assertEquals(0, f.invites)
    }
    @Test fun retainedPairCanRecoverAfterLinuxTcpRetransmissionWindow() = fixture { f ->
        f.connect(); f.chat.onNetworkLost(); f.open()
        f.sockets.last().let { it.listener.onClosing(it, 1005, "") }
        f.elapsed += 17 * 60_000
        f.chat.onNetworkLost()
        assertEquals(ChatPhase.ServerRetained, f.chat.state.value.phase)
        f.elapsed += 3 * 60_000
        f.chat.onNetworkLost()
        assertEquals(ChatPhase.Failed, f.chat.state.value.phase)
    }
    @Test fun browserConfirmationResetsConsecutiveFailureLimit() = fixture { f ->
        f.chat.start()
        repeat(4) { f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("lost"), null) } }
        f.open(); f.pair()
        repeat(4) { f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("lost again"), null) } }
        assertEquals(ChatPhase.Reconnecting, f.chat.state.value.phase)
        f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("fifth"), null) }
        assertEquals(ChatPhase.Failed, f.chat.state.value.phase)
    }
    @Test fun relayReadyAloneDoesNotProveBrowserPresence() = fixture { f ->
        f.chat.start(); f.open(); f.text("c")
        f.chat.setDraft("must not reach a ghost")
        assertNotEquals(ChatPhase.Connected, f.chat.state.value.phase)
        assertFalse(f.chat.send())
        f.text("""{"action":"random","random":0.5}""")
        assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
    }
    @Test fun immediateDuplicateCloseDoesNotClaimUserLeft() = fixture { f ->
        f.connect()
        f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("lost"), null) }
        f.open()
        f.sockets.last().let { it.listener.onClosing(it, 1005, "") }
        assertTrue("A retained server pair needs bounded recovery", f.chat.state.value.active)
        assertNotEquals(ChatPhase.PeerLeft, f.chat.state.value.phase)
    }
    @Test fun connectionFailureBeforeOpenKeepsInitialInvitationIntent() = fixture { f ->
        f.chat.start()
        f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("synthetic"), null) }
        f.open()
        assertEquals("An unfulfilled initial request must survive retry", 1, f.invites)
        f.pair()
        assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
    }
    @Test fun failureDuringManualProbeKeepsExactlyOnePendingInvitation() {
        val f = Fixture(probe = 100)
        try {
            f.chat.start(); f.open()
            f.timeout()
            f.chat.requestWindow(); f.open()
            f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("synthetic"), null) }
            f.open()
            val deadline = System.currentTimeMillis() + 2_000
            while (f.invites < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(2, f.invites)
            f.pair()
            Thread.sleep(150)
            assertEquals(2, f.invites)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    @Test fun handshakeGatesSendAndHistoryTracksBothDirections() = fixture { f ->
        f.chat.setDraft("outgoing")
        assertFalse(f.chat.send())
        f.chat.start()
        assertEquals(ChatPhase.Connecting, f.chat.state.value.phase)
        f.open()
        assertEquals(1, f.invites)
        assertEquals(ChatPhase.Waiting, f.chat.state.value.phase)
        assertFalse(f.chat.send())
        f.pair(recorded = true)
        assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
        assertTrue(f.chat.state.value.recorded)
        assertTrue(f.chat.send())
        assertEquals("outgoing", JSONObject(f.sockets.last().sent.single()).getString("msg"))
        f.text(ChatProtocol.message("incoming"))
        assertEquals(listOf(true, false), f.chat.state.value.messages.map { it.outgoing })
        assertEquals(listOf(1234L, 1234L), f.chat.state.value.messages.map { it.timeMillis })
        assertEquals(1, f.incoming)
        assertEquals("", f.chat.state.value.draft)
    }
    @Test fun rejectedSendRetainsDraftAndDoesNotInventHistory() = fixture { f ->
        f.connect(); f.chat.setDraft("keep me"); f.sockets.last().accept = false
        assertFalse(f.chat.send())
        assertEquals("keep me", f.chat.state.value.draft)
        assertTrue(f.chat.state.value.sendFailed)
        assertTrue(f.chat.state.value.messages.isEmpty())
    }
    @Test fun repeatWhileWaitingReinvitesWithoutSecondSocket() = fixture { f ->
        f.chat.start(); f.open(); f.chat.start(); f.chat.requestWindow()
        assertEquals(2, f.invites)
        assertEquals(1, f.sockets.size)
    }
    @Test fun peerClosureAllowsFreshPairAndIgnoresOldCallbacks() = fixture { f ->
        f.connect(); f.text(ChatProtocol.message("old"))
        val old = f.sockets.last()
        old.listener.onClosing(old, 1000, "")
        assertEquals(ChatPhase.Reconnecting, f.chat.state.value.phase)
        f.open(); f.pair()
        old.listener.onMessage(old, ChatProtocol.message("late"))
        old.listener.onClosed(old, 1000, "")
        assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
        assertEquals(listOf("old"), f.chat.state.value.messages.map { it.text })
        assertEquals("A returning browser must not receive another window", 1, f.invites)
    }
    @Test fun closeClearsMemoryAndCannotBeRevived() = fixture { f ->
        f.connect(); f.chat.setDraft("private"); f.text(ChatProtocol.message("private"))
        f.chat.close(); f.pair(); f.text(ChatProtocol.message("late")); f.chat.start(); f.chat.requestWindow()
        assertEquals(ChatPhase.Closed, f.chat.state.value.phase)
        assertEquals("", f.chat.state.value.draft)
        assertTrue(f.chat.state.value.messages.isEmpty())
        assertEquals(1, f.sockets.size)
        assertFalse(f.chat.send())
    }
    @Test fun cancellingOwnerErasesHistoryAndPreventsFurtherInvites() = fixture { f ->
        f.connect(); f.text(ChatProtocol.message("private")); f.chat.setDraft("private")
        f.scope.cancel()
        assertEquals(ChatPhase.Closed, f.chat.state.value.phase)
        assertTrue(f.chat.state.value.messages.isEmpty())
        assertEquals("", f.chat.state.value.draft)
        f.chat.requestWindow()
        assertEquals(1, f.invites)
    }
    @Test fun invalidOwnerCannotSendOrReceiveOrInvite() = fixture { f ->
        f.connect(); f.chat.setDraft("pending"); f.valid = false
        assertFalse(f.chat.send()); f.text(ChatProtocol.message("ignored")); f.chat.requestWindow()
        assertEquals(ChatPhase.Closed, f.chat.state.value.phase)
        assertTrue(f.chat.state.value.messages.isEmpty())
        assertEquals(1, f.invites)
    }
    @Test fun historyAndDraftAreBoundedAndUnknownFramesHaveNoEffect() = fixture { f ->
        f.connect()
        repeat(205) { f.text(ChatProtocol.message("message $it")) }
        assertEquals(200, f.chat.state.value.messages.size)
        assertEquals("message 5", f.chat.state.value.messages.first().text)
        f.text("""{"action":"webRtcSdp","sdp":{}}""")
        f.text("{broken")
        f.chat.setDraft("x".repeat(50_000))
        assertEquals(4097, f.chat.state.value.draft.length)
        assertFalse(f.chat.send())
        f.chat.setDraft("x".repeat(4096)); assertTrue(f.chat.send())
    }
    @Test fun pingIsAnsweredAndFailureAndRefusalAreNotPeerClosure() = fixture { f ->
        f.connect(); f.text("""{"ctrlChannel":"102938","type":"ping"}""")
        assertEquals("pong", JSONObject(f.sockets.last().sent.single()).getString("type"))
        f.text("""{"action":"close","cause":"noauth"}""")
        assertEquals(ChatPhase.Failed, f.chat.state.value.phase)
        f.chat.requestWindow(); f.open(); f.pair()
        f.sockets.last().let { it.listener.onFailure(it, java.io.IOException("synthetic"), null) }
        assertEquals(ChatPhase.Reconnecting, f.chat.state.value.phase)
    }
    @Test fun firstUnpairedTimeoutIsDistinctAndDoesNotInviteAgain() = fixture { f ->
        f.chat.start(); f.open(); f.chat.setDraft("keep draft")
        val old = f.sockets.last()
        f.elapsed += 15_000
        old.listener.onClosing(old, 1000, "")
        assertEquals(ChatPhase.NotOpened, f.chat.state.value.phase)
        assertEquals("keep draft", f.chat.state.value.draft)
        assertEquals(1, f.invites)
        assertEquals(1, f.sockets.size)
    }
    @Test fun realFiveSecondCooldownBlocksRepeatedInvitations() {
        val f = Fixture(cooldown = 5_000)
        try {
            f.chat.start(); f.open(); repeat(10) { f.chat.requestWindow() }
            assertEquals(1, f.invites)
            Thread.sleep(5_100)
            f.chat.requestWindow()
            assertEquals(2, f.invites)
            f.chat.requestWindow()
            assertEquals(2, f.invites)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    @Test fun manualRetryWaitsForReturningBrowserBeforeInviting() {
        val f = Fixture(probe = 2_500)
        try {
            f.chat.start(); f.open()
            f.timeout()
            f.chat.requestWindow(); f.open()
            assertEquals(1, f.invites)
            f.pair()
            Thread.sleep(2_600)
            assertEquals(1, f.invites)
            assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    @Test fun unseenMessagesAreCountedAndReadOnlyWhenVisible() = fixture { f ->
        f.connect(); f.text(ChatProtocol.message("one")); f.text(ChatProtocol.message("two"))
        assertEquals(2, f.chat.state.value.unreadCount)
        f.chat.setVisible(true)
        assertEquals(0, f.chat.state.value.unreadCount)
        f.text(ChatProtocol.message("seen"))
        assertEquals(0, f.chat.state.value.unreadCount)
        assertEquals(2, f.incoming)
        f.chat.setVisible(false); f.text(ChatProtocol.message("other screen"))
        assertEquals(1, f.chat.state.value.unreadCount)
        assertEquals(3, f.incoming)
    }
    @Test fun truncatedMessagesAndUnsupportedFilesHaveExplicitOutcomes() = fixture { f ->
        f.connect()
        f.text(JSONObject().put("action", "chat").put("msg", "x".repeat(5000)).toString())
        assertEquals(4096, f.chat.state.value.messages.single().text.length)
        assertTrue(f.chat.state.value.messages.single().truncated)
        f.text("""{"action":"file","id":0.5,"name":"example.txt"}""")
        val reply = JSONObject(f.sockets.last().sent.single())
        assertEquals("fileUploadCancel", reply.getString("action"))
        assertEquals(0.5, reply.getDouble("id"), 0.0)
        f.chat.setDraft("one\ntwo\rthree")
        assertEquals("one two three", f.chat.state.value.draft)
    }
    @Test fun serverPingPongOrGarbageCannotProveBrowserPresence() = fixture { f ->
        f.chat.start(); f.open(); f.text("c")
        f.text("""{"ctrlChannel":"102938","type":"ping"}""")
        f.text("{broken")
        assertEquals(ChatPhase.Verifying, f.chat.state.value.phase)
        f.text(ChatProtocol.pong())
        assertEquals(ChatPhase.Verifying, f.chat.state.value.phase)
        f.text("""{"action":"random","random":0.5}""")
        assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
    }
    @Test fun ghostPairExpiresAndRetriesWithoutAnotherInvitation() {
        val f = Fixture(proof = 40, retainedRetry = 50)
        try {
            f.chat.start(); f.open(); f.text("c")
            await { f.chat.state.value.phase == ChatPhase.ServerRetained }
            assertFalse(f.chat.send())
            await { f.sockets.size == 2 }
            f.open(); f.pair()
            assertEquals(ChatPhase.Connected, f.chat.state.value.phase)
            assertEquals(1, f.invites)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    @Test fun retainedPairHasFiniteBudgetAndCannotInviteWhileRetrying() {
        val f = Fixture(budget = 200, retainedRetry = 40)
        try {
            f.connect(); f.chat.onNetworkLost(); f.open()
            f.sockets.last().let { it.listener.onClosing(it, 1005, "") }
            assertEquals(ChatPhase.ServerRetained, f.chat.state.value.phase)
            f.chat.requestWindow()
            await { f.sockets.size >= 3 }
            f.open(); f.sockets.last().let { it.listener.onClosing(it, 1005, "") }
            await { f.chat.state.value.phase == ChatPhase.Failed }
            val count = f.sockets.size
            Thread.sleep(100)
            assertEquals(count, f.sockets.size)
            assertEquals(1, f.invites)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    @Test fun pushServerWithoutRemoteTimeoutStillStopsWaitingLocally() {
        val f = Fixture(waiting = 80)
        try {
            f.chat.start(); f.open(); f.chat.setDraft("kept")
            await { f.chat.state.value.phase == ChatPhase.NotOpened }
            assertFalse(f.chat.state.value.active)
            assertEquals("kept", f.chat.state.value.draft)
            assertEquals(1, f.invites)
        } finally { f.chat.close(); f.scope.cancel() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(condition())
    }
    private fun fixture(block: (Fixture) -> Unit) { val f = Fixture(); try { block(f) } finally { f.chat.close(); f.scope.cancel() } }
    private class Fixture(cooldown: Long = 0, probe: Long = 0, proof: Long = 3_000, waiting: Long = 20_000, budget: Long = ChatController.RETAINED_RECOVERY_MILLIS, retainedRetry: Long = 5_000) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = mutableListOf<Socket>()
        var valid = true
        var invites = 0
        var incoming = 0
        var elapsed = 0L
        val request = Request.Builder().url("https://server.example/meshrelay.ashx?id=synthetic").build()
        val chat = ChatController(scope, WebSocket.Factory { req, listener -> Socket(req, listener).also { sockets += it } },
            { request }, { valid }, { invites++; true }, onIncoming = { incoming++ }, now = { 1234L }, requestCooldownMillis = cooldown, probeMillis = probe, retryDelayMillis = 0, elapsedMillis = { elapsed }, peerProofMillis = proof, waitingMillis = waiting, recoveryBudgetMillis = budget, retainedRetryMillis = retainedRetry)
        fun open() { sockets.last().let { it.listener.onOpen(it, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()) } }
        fun text(text: String) { sockets.last().let { it.listener.onMessage(it, text) } }
        fun pair(recorded: Boolean = false) { text(if (recorded) "cr" else "c"); text("""{"action":"random","random":0.5}"""); sockets.last().sent.clear() }
        fun connect() { chat.start(); open(); pair() }
        fun timeout() { elapsed += 15_000; sockets.last().let { it.listener.onClosing(it, 1000, "") } }
    }
    private class Socket(private val req: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var accept = true
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { if (accept) sent += text; return accept }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}
