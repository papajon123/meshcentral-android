package ru.papajon.meshcentralmobile.mesh.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A conversation outlives transient control/relay loss. No text or invitations are replayed. */
class ChatController(
    private val scope: CoroutineScope,
    private val socketFactory: WebSocket.Factory,
    private val request: () -> Request?,
    private val validSession: () -> Boolean,
    private val invite: () -> Boolean,
    private val onIncoming: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val onClose: (ChatController) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val requestCooldownMillis: Long = 5_000,
    private val probeMillis: Long = 2_500,
    private val retryDelayMillis: Long = 500,
    private val credentialWaitMillis: Long = 120_000,
    private val peerProofMillis: Long = 3_000,
    private val waitingMillis: Long = 20_000,
    private val retainedRetryMillis: Long = 5_000,
    private val recoveryBudgetMillis: Long = RETAINED_RECOVERY_MILLIS,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val refreshAuthorization: () -> Boolean = { false },
) : AutoCloseable {
    companion object { const val RETAINED_RECOVERY_MILLIS = 20 * 60_000L }
    private val lock = Any()
    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private var socket: WebSocket? = null
    private var attempt = 0L
    private var sequence = 0L
    private var ended = false
    private var visible = false
    private var started = false
    private var pairedBefore = false
    private var pendingInvitation = false
    private var immediateInvitation = false
    private var recoveryAttempts = 0
    private var consecutiveFailures = 0
    private var socketOpenedAt: Long? = null
    private var recoveryStartedAt: Long? = null
    private var serverRetained = false
    private var opening: Job? = null
    private var probe: Job? = null
    private var deadline: Job? = null
    private var recoveryBudget: Job? = null
    private var cooldown: Job? = null
    private var scopeCompletion: DisposableHandle? = null
    init { scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion { close() } }

    fun setVisible(value: Boolean) = synchronized(lock) {
        visible = value
        if (value && !ended) mutableState.value = state.value.copy(unreadCount = 0)
    }

    fun setDraft(value: String) = synchronized(lock) {
        if (!ended) mutableState.value = state.value.copy(
            draft = value.replace('\r', ' ').replace('\n', ' ').take(ChatProtocol.MAX_TEXT + 1), sendFailed = false)
    }

    fun start() = synchronized(lock) {
        if (!ended && !started) {
            started = true
            pendingInvitation = true
            immediateInvitation = true
            setCooldown()
            refreshAuthorization()
            open(inviteAfterProbe = true, initial = true)
        }
    }

    /** User action only. Reuse a waiting socket and give an existing browser a chance to re-pair. */
    fun requestWindow() = synchronized(lock) {
        if (ended || !state.value.canRequest || state.value.phase in setOf(ChatPhase.Connected, ChatPhase.Verifying, ChatPhase.ServerRetained)) return@synchronized
        if (!validSession()) { close(); return@synchronized }
        started = true
        pendingInvitation = true
        immediateInvitation = false
        recoveryAttempts = 0
        consecutiveFailures = 0
        recoveryBudget?.cancel(); recoveryBudget = null
        recoveryStartedAt = null
        serverRetained = false
        setCooldown()
        val authorizationChanged = refreshAuthorization()
        if (!authorizationChanged && socket != null && state.value.phase == ChatPhase.Waiting &&
            socketOpenedAt?.let { elapsedMillis() - it < 5_000 } == true) scheduleInvitation(attempt)
        else open(inviteAfterProbe = true, initial = false)
    }

    private fun setCooldown() {
        mutableState.value = state.value.copy(canRequest = false, invitationUnavailable = false)
        cooldown?.cancel()
        cooldown = scope.launch {
            delay(requestCooldownMillis)
            synchronized(lock) { if (!ended) mutableState.value = state.value.copy(canRequest = true) }
        }
    }

    /** The network callback is separate from agent connectivity and control authorization. */
    fun onNetworkLost() = synchronized(lock) {
        if (!ended && started && state.value.active) recover()
    }

    private fun open(inviteAfterProbe: Boolean, initial: Boolean) {
        if (ended) return
        if (!validSession()) { close(); return }
        val generation = ++attempt
        socket?.cancel(); socket = null
        socketOpenedAt = null
        opening?.cancel(); probe?.cancel(); deadline?.cancel()
        mutableState.value = state.value.copy(phase = if (serverRetained) ChatPhase.ServerRetained else if (initial) ChatPhase.Connecting else ChatPhase.Reconnecting,
            recorded = false, sendFailed = false)
        opening = scope.launch {
            val maxTicks = (credentialWaitMillis / 100).toInt().coerceAtLeast(1)
            repeat(maxTicks) {
                val ready = synchronized(lock) {
                    if (ended || generation != attempt) return@launch
                    if (!validSession()) { close(); return@launch }
                    try { request() } catch (_: IllegalArgumentException) { fail(); return@launch }
                }
                if (ready != null) {
                    synchronized(lock) {
                        if (!ended && generation == attempt && validSession()) {
                            try { socket = socketFactory.newWebSocket(ready, listener(generation, inviteAfterProbe, initial)) }
                            catch (_: IllegalArgumentException) { fail() }
                        }
                    }
                    return@launch
                }
                delay(100)
            }
            synchronized(lock) { if (!ended && generation == attempt) fail() }
        }
    }

    private fun scheduleInvitation(generation: Long, immediate: Boolean = false) {
        probe?.cancel()
        probe = scope.launch {
            if (!immediate) delay(probeMillis)
            synchronized(lock) {
                if (ended || generation != attempt || state.value.phase !in setOf(ChatPhase.Waiting, ChatPhase.ServerRetained) || !pendingInvitation) return@launch
                if (!validSession()) { close(); return@launch }
                // The provider separately checks fresh control auth, rights, and an online agent.
                val sent = invite()
                if (sent) pendingInvitation = false
                mutableState.value = state.value.copy(invitationUnavailable = !sent)
            }
        }
    }

    fun send(): Boolean = synchronized(lock) {
        if (ended) return false
        if (!validSession()) { close(); return false }
        val current = state.value
        if (!current.canSend) return false
        if (socket?.send(ChatProtocol.message(current.draft)) != true) {
            mutableState.value = current.copy(sendFailed = true)
            return false
        }
        append(current.draft, true, false)
        mutableState.value = state.value.copy(draft = "", sendFailed = false)
        true
    }

    private fun listener(generation: Long, inviteAfterProbe: Boolean, initial: Boolean) = object : WebSocketListener() {
        private var openedAt: Long? = null
        private var receivedReady = false
        private fun dispatch(block: () -> Unit) {
            scope.launch {
                synchronized(lock) {
                    if (!ended && generation == attempt) {
                        if (!validSession()) close() else block()
                    }
                }
            }
        }
        override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
            openedAt = elapsedMillis()
            socketOpenedAt = openedAt
            mutableState.value = state.value.copy(phase = if (serverRetained) ChatPhase.ServerRetained else ChatPhase.Waiting)
            if (serverRetained) scope.launch {
                delay(peerProofMillis)
                synchronized(lock) {
                    // A duplicate closes immediately. A socket still accepted after the probe is
                    // ordinary waiting, even when the previous attempt was a retained pair.
                    if (!ended && generation == attempt && state.value.phase == ChatPhase.ServerRetained) {
                        serverRetained = false
                        mutableState.value = state.value.copy(phase = ChatPhase.Waiting)
                    }
                }
            }
            deadline = scope.launch {
                delay(waitingMillis)
                synchronized(lock) {
                    if (!ended && generation == attempt) finishWaiting()
                }
            }
            if (inviteAfterProbe) scheduleInvitation(generation, immediate = initial)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Parse on the WebSocket reader, not Main; only bounded decoded content crosses to UI.
            val frame = ChatProtocol.parse(text)
            dispatch { when (frame) {
                is ChatFrame.Ready -> if (!receivedReady) {
                    receivedReady = true
                    probe?.cancel()
                    deadline?.cancel()
                    mutableState.value = state.value.copy(phase = ChatPhase.Verifying, recorded = frame.recorded)
                    deadline = scope.launch {
                        delay(peerProofMillis)
                        synchronized(lock) {
                            // c can pair this socket with our own abandoned socket. Never send text
                            // or open another window until the browser proves it is actually here.
                            if (!ended && generation == attempt) recover(retained = true)
                        }
                    }
                }
                is ChatFrame.Text -> {
                    confirmPeer()
                    if (state.value.phase == ChatPhase.Connected) {
                        append(frame.text, false, frame.truncated)
                        if (!visible) onIncoming()
                    }
                }
                is ChatFrame.FileOffer -> {
                    confirmPeer()
                    if (state.value.phase == ChatPhase.Connected) webSocket.send(ChatProtocol.cancelFile(frame.id))
                }
                ChatFrame.PeerProof -> confirmPeer()
                ChatFrame.Ping -> webSocket.send(ChatProtocol.pong())
                ChatFrame.Refused -> fail()
                ChatFrame.Ignore -> Unit
            } }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dispatch { disconnected(openedAt, receivedReady) }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch { disconnected(openedAt, receivedReady) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch {
            onFailure(t) // Classifier only; exception text and authenticated URL must never be shown.
            if (!ended) {
                if (response?.code == 401 || response?.code == 403 || ++consecutiveFailures >= 5) fail() else recover()
            }
        }
    }

    private fun append(text: String, outgoing: Boolean, truncated: Boolean) {
        mutableState.value = state.value.copy(messages = (state.value.messages +
            ChatMessage(++sequence, text, outgoing, now(), truncated)).takeLast(ChatProtocol.MAX_HISTORY),
            unreadCount = if (!outgoing && !visible) (state.value.unreadCount + 1).coerceAtMost(9999) else state.value.unreadCount)
    }

    private fun confirmPeer() {
        if (state.value.phase != ChatPhase.Verifying) return
        deadline?.cancel()
        recoveryBudget?.cancel(); recoveryBudget = null
        recoveryStartedAt = null
        pairedBefore = true
        pendingInvitation = false
        immediateInvitation = false
        serverRetained = false
        recoveryAttempts = 0
        consecutiveFailures = 0
        mutableState.value = state.value.copy(phase = ChatPhase.Connected, invitationUnavailable = false)
    }

    private fun disconnected(openedAt: Long?, receivedReady: Boolean) {
        if (state.value.phase == ChatPhase.Connected) { recover(); return }
        if (state.value.phase == ChatPhase.Verifying ||
            (!receivedReady && openedAt != null && elapsedMillis() - openedAt < peerProofMillis)) {
            recover(retained = true)
            return
        }
        finishWaiting()
    }

    private fun finishWaiting() {
        // MeshCentral closes an unpaired socket after 15 s. Do not reopen browser windows in a loop.
        invalidateSocket()
        opening?.cancel()
        recoveryBudget?.cancel(); recoveryBudget = null
        recoveryStartedAt = null
        serverRetained = false
        mutableState.value = state.value.copy(phase = if (pairedBefore) ChatPhase.PeerLeft else ChatPhase.NotOpened)
    }

    private fun recover(retained: Boolean = false) {
        invalidateSocket()
        serverRetained = serverRetained || retained
        if (retained) consecutiveFailures = 0
        if (serverRetained) {
            val startedAt = recoveryStartedAt ?: elapsedMillis().also { recoveryStartedAt = it }
            if (elapsedMillis() - startedAt >= recoveryBudgetMillis) { fail(); return }
            if (recoveryBudget == null) recoveryBudget = scope.launch {
                delay(recoveryBudgetMillis)
                synchronized(lock) { if (!ended) fail() }
            }
        }
        ++recoveryAttempts
        mutableState.value = state.value.copy(phase = if (serverRetained) ChatPhase.ServerRetained else ChatPhase.Reconnecting)
        opening?.cancel()
        val generation = attempt
        opening = scope.launch {
            delay(if (serverRetained) retainedRetryMillis else (retryDelayMillis * recoveryAttempts).coerceAtMost(10_000))
            synchronized(lock) {
                if (!ended && generation == attempt) open(inviteAfterProbe = pendingInvitation, initial = immediateInvitation && pendingInvitation)
            }
        }
    }

    private fun invalidateSocket() {
        ++attempt
        probe?.cancel(); deadline?.cancel()
        socket?.cancel(); socket = null
        socketOpenedAt = null
    }
    private fun fail() {
        invalidateSocket()
        opening?.cancel()
        recoveryBudget?.cancel(); recoveryBudget = null
        recoveryStartedAt = null
        mutableState.value = state.value.copy(phase = ChatPhase.Failed)
    }

    override fun close() = synchronized(lock) {
        if (ended) return@synchronized
        ended = true
        ++attempt
        opening?.cancel(); probe?.cancel(); cooldown?.cancel(); deadline?.cancel(); recoveryBudget?.cancel()
        scopeCompletion?.dispose(); scopeCompletion = null
        socket?.close(1000, null); socket = null
        mutableState.value = ChatState(phase = ChatPhase.Closed, canRequest = false)
        onClose(this)
    }
}
