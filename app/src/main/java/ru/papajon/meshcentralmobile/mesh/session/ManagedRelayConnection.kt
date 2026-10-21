package ru.papajon.meshcentralmobile.mesh.session

import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshControlMessages

/** In-memory only. Deliberately not a data class: credentials must never appear in toString. */
internal class RelayCredentials(val baseUrl: HttpUrl, val authCookie: String, val relayCookie: String?)

/**
 * One-shot relay: no retries, replay, credential persistence or protocol-specific payload decoding.
 * Wire contract: MeshCentral 1.2.6 agent-redir-ws-0.1.1.js, apprelays.js and webserver.js.
 * All state/socket transitions and sends share a lock so a late callback cannot revive a closed session.
 */
internal class ManagedRelayConnection(
    override val protocol: RelayProtocol,
    private val nodeId: String,
    private val socketFactory: WebSocket.Factory,
    private val scope: CoroutineScope,
    private val credentials: () -> RelayCredentials?,
    private val validSession: () -> Boolean,
    private val availability: () -> SessionAvailability,
    private val sendControl: (JSONObject) -> Boolean,
    private val onFinished: (ManagedRelayConnection) -> Unit,
    options: JSONObject? = null,
    private val authTimeoutMillis: Long = 20_000,
    private val handshakeTimeoutMillis: Long = 60_000,
) : RelayConnection {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RelayStatus())
    override val state = mutableState.asStateFlow()
    private val frames = Channel<RelayFrame>(INCOMING_FRAMES)
    override val incoming = frames.receiveAsFlow()
    private var socket: WebSocket? = null
    private var startJob: Job? = null
    private var timeoutJob: Job? = null
    private var ended = false
    private val tunnelId = MeshControlMessages.randomTunnelId(SecureRandom())
    internal val responseId = "android-relay-$tunnelId"
    private val agent = protocol == RelayProtocol.AgentTerminal || protocol == RelayProtocol.AgentFiles
    // Snapshot caller-owned mutable JSON; do not retain it after handshake or close.
    private var optionsText = if (agent) options?.let {
        JSONObject(it.toString()).apply { remove("action"); put("type", "options") }.toString()
    } else null
    private var scopeCompletion: DisposableHandle? = null

    init {
        // A cancelled owner must also unblock a WebSocket reader waiting for a slow consumer.
        scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    override fun connect() = synchronized(lock) {
        if (ended || mutableState.value.phase != RelayPhase.Idle) return@synchronized
        if (!validSession()) { fail(R.string.relay_sign_in_required); return@synchronized }
        val available = availability()
        if (!available.canAttempt) {
            finish(RelayPhase.Failed, available.label)
            return@synchronized
        }
        mutableState.value = RelayStatus(RelayPhase.Connecting)
        startJob = scope.launch {
            var waited = 0L
            while (true) {
                val ready = synchronized(lock) {
                    if (ended) return@launch
                    if (!validSession()) { fail(R.string.relay_sign_in_required); return@launch }
                    credentials()
                }
                if (ready != null) {
                    synchronized(lock) {
                        if (!ended && validSession()) open(ready)
                        else if (!ended) fail(R.string.relay_sign_in_required)
                    }
                    return@launch
                }
                if (waited >= authTimeoutMillis) {
                    synchronized(lock) { fail(R.string.relay_timeout) }
                    return@launch
                }
                delay(100)
                waited += 100
            }
        }
    }

    private fun open(auth: RelayCredentials) {
        val url = if (agent) {
            MeshControlMessages.relayViewerUrl(auth.baseUrl, nodeId, tunnelId, auth.authCookie, auth.relayCookie, protocol.usage)
        } else {
            MeshControlMessages.sshViewerUrl(auth.baseUrl, nodeId, auth.authCookie, protocol == RelayProtocol.SshFiles)
        }
        timeoutJob = scope.launch {
            delay(handshakeTimeoutMillis)
            synchronized(lock) { if (!ended && mutableState.value.phase != RelayPhase.Connected) fail(R.string.relay_timeout) }
        }
        try {
            socket = socketFactory.newWebSocket(Request.Builder().url(url).build(), Listener())
            if (agent && !sendControl(MeshControlMessages.tunnelRequest(nodeId, tunnelId, auth.relayCookie, protocol.usage, responseId, auth.baseUrl.encodedPath))) {
                fail(R.string.relay_connection_failed)
            }
        } catch (_: Exception) {
            // Exception messages may contain the auth URL; never expose or log them.
            fail(R.string.relay_connection_failed)
        }
    }

    override fun sendText(value: String): Boolean = synchronized(lock) {
        if (value.length > MAX_FRAME_BYTES) return@synchronized rejectOversize()
        val byteCount = value.toByteArray(Charsets.UTF_8).size
        send(byteCount) { it.send(value) }
    }

    override fun sendBinary(value: ByteArray): Boolean = synchronized(lock) {
        send(value.size) { it.send(value.toByteString()) }
    }

    private fun send(size: Int, operation: (WebSocket) -> Boolean): Boolean {
        if (ended || mutableState.value.phase != RelayPhase.Connected) return false
        if (!validSession()) { fail(R.string.relay_sign_in_required); return false }
        val target = socket ?: return false
        if (size > MAX_FRAME_BYTES || target.queueSize() + size > MAX_OUTGOING_BYTES) return rejectOversize()
        if (!operation(target)) { fail(R.string.relay_connection_failed); return false }
        return true
    }

    private fun rejectOversize(): Boolean {
        if (!ended) fail(R.string.relay_buffer_limit)
        return false
    }

    override fun close() = synchronized(lock) { finish(RelayPhase.Closed, null) }

    internal fun routingFailed() = synchronized(lock) { fail(R.string.relay_routing_failed) }

    private fun fail(message: Int) { finish(RelayPhase.Failed, UiText.Resource(message)) }

    private fun finish(phase: RelayPhase, message: UiText?) {
        if (ended) return
        ended = true
        optionsText = null
        startJob?.cancel()
        timeoutJob?.cancel()
        scopeCompletion?.dispose()
        val previous = socket
        socket = null
        previous?.cancel()
        // Drop buffered sensitive output when the owner ends the session.
        // cancel (rather than close) also wakes suspended sends and discards buffered output.
        frames.cancel()
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        onFinished(this)
    }

    private fun receive(frame: RelayFrame) {
        // OkHttp calls this on its WebSocket reader, never on the UI thread. Waiting here applies
        // bounded TCP backpressure. Crucially, no connection lock is held while the consumer is slow.
        if (frames.trySendBlocking(frame).isFailure) synchronized(lock) {
            if (!ended) fail(R.string.relay_connection_failed)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (ended || socket !== webSocket) { webSocket.cancel(); return@synchronized }
            if (!validSession()) { fail(R.string.relay_sign_in_required); return@synchronized }
            mutableState.value = RelayStatus(if (agent) RelayPhase.Waiting else RelayPhase.Connected)
            if (!agent) timeoutJob?.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          val frame = synchronized(lock) {
            if (ended || socket !== webSocket) return
            if (!validSession()) { fail(R.string.relay_sign_in_required); return }
            if (text.length > MAX_FRAME_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
                fail(R.string.relay_buffer_limit); return
            }
            if (agent && mutableState.value.phase == RelayPhase.Waiting && (text == "c" || text == "cr")) {
                val sentOptions = optionsText?.let { webSocket.send(it) } ?: true
                optionsText = null
                if (!sentOptions || !webSocket.send(protocol.usage.toString())) {
                    fail(R.string.relay_connection_failed); return
                }
                timeoutJob?.cancel()
                mutableState.value = RelayStatus(RelayPhase.Connected, recorded = text == "cr")
                return
            }
            if (agent && text.startsWith("{")) {
                val control = runCatching { JSONObject(text) }.getOrNull()
                if (control?.optString("ctrlChannel") == MeshControlMessages.CTRL_CHANNEL.toString()) {
                    when (control.optString("type")) {
                        "ping" -> {
                            if (!webSocket.send(JSONObject().put("ctrlChannel", "102938").put("type", "pong").toString())) fail(R.string.relay_connection_failed)
                            return
                        }
                        "close" -> { fail(R.string.relay_remote_closed); return }
                    }
                }
            }
            // Includes consent/status control messages before Agent readiness and raw SSH auth messages.
            RelayFrame.Text(text)
          }
          receive(frame)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          val frame = synchronized(lock) {
            if (ended || socket !== webSocket) return
            if (!validSession()) { fail(R.string.relay_sign_in_required); return }
            if (bytes.size > MAX_FRAME_BYTES) { fail(R.string.relay_buffer_limit); return }
            if (agent && mutableState.value.phase != RelayPhase.Connected) { fail(R.string.relay_protocol_error); return }
            RelayFrame.Binary(bytes.toByteArray())
          }
          receive(frame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (!ended && socket === webSocket) fail(R.string.relay_connection_failed)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!ended && socket === webSocket) fail(R.string.relay_remote_closed)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!ended && socket === webSocket) fail(R.string.relay_remote_closed)
        }
    }

    companion object {
        internal const val INCOMING_FRAMES = 8
        internal const val MAX_FRAME_BYTES = 1024 * 1024
        internal const val MAX_OUTGOING_BYTES = 1024L * 1024
    }
}
