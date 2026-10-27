package ru.papajon.meshcentralmobile.terminal

import ru.papajon.meshcentralmobile.json.optText

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol

enum class TerminalAuth { None, PasswordOrKey, KeyPassphrase }

data class TerminalState(
    val phase: RelayPhase = RelayPhase.Idle,
    val ready: Boolean = false,
    val awaitingShell: Boolean = false,
    val auth: TerminalAuth = TerminalAuth.None,
    val message: UiText? = null,
    val recorded: Boolean = false,
    val canReconnect: Boolean = false,
    val reopenRequired: Boolean = false,
)

/** One terminal view with explicit, non-automatic reconnects. Own in a ViewModel, close when leaving. No saved state. */
class TerminalController(
    connection: RelayConnection,
    private val scope: CoroutineScope,
    private val shellReadyTimeoutMillis: Long = 30_000,
    private val inputDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val connectionFactory: (() -> RelayConnection?)? = null,
) {
    private var connection: RelayConnection = connection
    val isSsh: Boolean = connection.protocol == RelayProtocol.SshTerminal
    /** True when this controller owns an explicit-in-session reconnect path instead of onReopen. */
    val supportsReconnect: Boolean = connectionFactory != null
    private val mutableState = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = mutableState.asStateFlow()
    /** Non-Compose mirror of [TerminalState.canReconnect]. */
    val canReconnect: Boolean get() = mutableState.value.canReconnect
    private var started = false
    private var disposed = false
    private var rendererBroken = false
    private var sessionActive = false
    private var generation = 0
    /** Read under the output-buffer lock without taking the controller monitor. */
    @Volatile private var activeEpoch = 0
    private var columns = 80
    private var rows = 24
    private var pixelWidth = 800
    private var pixelHeight = 480
    private var renderer: TerminalWebRenderer? = null
    private val outputBuffer = TerminalOutputBuffer(MAX_PENDING_BYTES) { synchronized(this) { drain() } }
    private var writing = false
    private var rendererLoaded = false
    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private var shellTimeoutJob: Job? = null
    private var sshHandshakeTimeoutJob: Job? = null
    private var sshHandshakeStarted = false
    private var sshAuthenticated = false
    private var sshOutputReceived = false

    init {
        require(connection.protocol in setOf(RelayProtocol.AgentTerminal, RelayProtocol.SshTerminal))
        require(shellReadyTimeoutMillis > 0)
    }

    @Synchronized fun connect() {
        if (started || disposed || rendererBroken) return
        started = true
        startSessionLocked()
        connection.connect()
    }

    /**
     * Explicit user action only. The factory creates a fresh relay and a fresh shell; input,
     * credentials and server-saved authentication are never replayed automatically.
     */
    @Synchronized fun reconnect(marker: String): Boolean {
        if (disposed || rendererBroken || !started || sessionActive) return false
        val factory = connectionFactory ?: return false
        val next = try { factory() } catch (_: Throwable) { null } ?: return false
        if (next === connection || next.protocol != connection.protocol) { next.close(); return false }
        val previous = connection
        endSessionLocked()
        previous.close()
        if (outputBuffer.enqueueSessionStart(sessionStartPayload(marker)) !=
            TerminalOutputBuffer.Result.Enqueued) {
            next.close()
            return false
        }
        connection = next
        mutableState.value = TerminalState(phase = RelayPhase.Connecting)
        renderer?.setInputEnabled(false)
        startSessionLocked()
        connection.connect()
        return true
    }

    private fun startSessionLocked() {
        val target = connection
        val session = ++generation
        sessionActive = true
        activeEpoch = session
        sshHandshakeStarted = false
        sshAuthenticated = false
        sshOutputReceived = false
        shellTimeoutJob?.cancel(); shellTimeoutJob = null
        sshHandshakeTimeoutJob?.cancel(); sshHandshakeTimeoutJob = null
        frameJob = scope.launch(inputDispatcher) {
            target.incoming.collect { frame ->
                val bytes = synchronized(this@TerminalController) {
                    if (!isCurrent(session)) return@collect
                    receive(frame, session)
                } ?: return@collect
                // The suspending wait is outside controller/buffer monitors and off the UI thread.
                // The guard keeps a late old frame behind the reconnect marker from crossing it.
                val appended = outputBuffer.append(bytes) { activeEpoch == session }
                if (appended == TerminalOutputBuffer.Result.Oversize) synchronized(this@TerminalController) {
                    if (isCurrent(session)) fail(R.string.terminal_output_overflow)
                }
            }
        }
        stateJob = scope.launch {
            target.state.collect { status ->
                synchronized(this@TerminalController) {
                    if (!isCurrent(session)) return@collect
                    val closed = status.phase == RelayPhase.Closed || status.phase == RelayPhase.Failed
                    val ready = !closed && if (isSsh) mutableState.value.ready else status.phase == RelayPhase.Connected
                    mutableState.value = mutableState.value.copy(
                        phase = status.phase, ready = ready, recorded = status.recorded,
                        message = status.message ?: mutableState.value.message,
                        auth = if (closed) TerminalAuth.None else mutableState.value.auth,
                    )
                    renderer?.setInputEnabled(ready)
                    if (closed) finish(status.phase, status.message)
                    else if (ready && !isSsh) sendSize()
                    else if (isSsh && status.phase == RelayPhase.Connected && !sshHandshakeStarted) {
                        sshHandshakeStarted = true
                        if (!sshAuthenticated && mutableState.value.auth == TerminalAuth.None) {
                            startSshHandshakeTimeout(session)
                        }
                    }
                }
            }
        }
    }

    /** Application-context renderer survives Activity recreation; attach only changes its parent. */
    @Synchronized fun renderer(context: Context): TerminalWebRenderer? {
        renderer?.let { return it }
        if (disposed) return null
        return try {
            TerminalWebRenderer(context.applicationContext, this).also { renderer = it }
        } catch (_: RuntimeException) {
            rendererInitializationFailed(); null
        } catch (_: LinkageError) {
            rendererInitializationFailed(); null
        }
    }

    @Synchronized internal fun rendererReady() {
        if (disposed) return
        rendererLoaded = true
        renderer?.setInputEnabled(mutableState.value.ready)
        drain()
    }

    @Synchronized internal fun outputConsumed() {
        if (disposed) return
        writing = false
        outputBuffer.consumed()
        drain()
    }

    private fun drain() {
        if (disposed || !rendererLoaded || writing) return
        val next = outputBuffer.peek() ?: return
        writing = true
        if (outputBuffer.peekIsSessionStart()) renderer?.writeSessionStart(next) else renderer?.write(next)
    }

    /** Decodes control under a short lock; output admission/suspension happens in the caller. */
    @Synchronized private fun receive(frame: RelayFrame, session: Int): ByteArray? {
        if (!isCurrent(session)) return null
        if (frame is RelayFrame.Binary) {
            return if (!isSsh) frame.value else null
        }
        val text = (frame as RelayFrame.Text).value
        if (isSsh && text == "c") {
            // apprelays.js sends c before the async sshClient.shell callback has attached sshShell.
            // Sending here can silently discard the first command. Only actual shell output is proof.
            if (!sshAuthenticated) {
                sshHandshakeTimeoutJob?.cancel()
                sshAuthenticated = true
                mutableState.value = mutableState.value.copy(ready = false, awaitingShell = true,
                    auth = TerminalAuth.None, message = null)
                renderer?.setInputEnabled(false)
                if (sshOutputReceived) markShellReady(session) else {
                    shellTimeoutJob = scope.launch {
                        delay(shellReadyTimeoutMillis)
                        synchronized(this@TerminalController) {
                            if (isCurrent(session) && !mutableState.value.ready) fail(R.string.terminal_shell_timeout)
                        }
                    }
                }
            }
            return null
        }
        if (isSsh && text.startsWith("~")) {
            if (text.length > 1) {
                sshOutputReceived = true
                if (sshAuthenticated && !mutableState.value.ready) markShellReady(session)
                return text.substring(1).toByteArray(Charsets.UTF_8)
            }
            return null
        }
        val control = if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
        if (control != null && control.optText("ctrlChannel") == "102938") {
            when (control.optText("type")) {
                "ping" -> connection.sendText(JSONObject().put("ctrlChannel", "102938").put("type", "pong").toString())
                "console" -> mutableState.value = mutableState.value.copy(message =
                    control.optText("msg").takeIf { it.isNotBlank() }?.let { UiText.Literal(it.take(2048)) })
            }
            return null
        }
        if (isSsh) {
            when (control?.optText("action")) {
                "sshauth" -> {
                    // Human credential entry has no network deadline; resume it after submission.
                    sshHandshakeTimeoutJob?.cancel()
                    shellTimeoutJob?.cancel()
                    sshAuthenticated = false; sshOutputReceived = false
                    mutableState.value = mutableState.value.copy(ready = false, awaitingShell = false,
                        auth = if (control.optBoolean("askkeypass")) TerminalAuth.KeyPassphrase else TerminalAuth.PasswordOrKey)
                    renderer?.setInputEnabled(false)
                }
                "sshautoauth" -> if (sendControl(sizeMessage("sshautoauth"))) {
                    mutableState.value = mutableState.value.copy(awaitingShell = true)
                    startSshHandshakeTimeout(session)
                }
                "autherror" -> fail(R.string.terminal_auth_failed)
                "sessiontimeout" -> fail(R.string.terminal_session_timeout)
                "connectionerror" -> fail(R.string.terminal_ssh_connection_failed)
            }
        } else return text.toByteArray(Charsets.UTF_8)
        return null
    }

    /** Bounds both a silent open WebSocket and a server that never answers submitted credentials. */
    private fun startSshHandshakeTimeout(session: Int) {
        if (!isCurrent(session)) return
        sshHandshakeStarted = true
        sshHandshakeTimeoutJob?.cancel()
        sshHandshakeTimeoutJob = scope.launch {
            delay(shellReadyTimeoutMillis)
            synchronized(this@TerminalController) {
                if (isCurrent(session) && !sshAuthenticated && mutableState.value.auth == TerminalAuth.None) {
                    fail(R.string.terminal_session_timeout)
                }
            }
        }
    }

    private fun markShellReady(session: Int) {
        if (!isCurrent(session)) return
        shellTimeoutJob?.cancel(); shellTimeoutJob = null
        mutableState.value = mutableState.value.copy(ready = true, awaitingShell = false,
            auth = TerminalAuth.None, message = null)
        renderer?.setInputEnabled(true)
        sendSize()
    }

    /** Credentials are used only to construct this one outbound message. keep is always zero. */
    @Synchronized fun authenticate(username: String, password: String, key: String = "", keyPassphrase: String = "") {
        if (disposed || !sessionActive || !isSsh || mutableState.value.auth == TerminalAuth.None) return
        val session = generation
        val msg = if (mutableState.value.auth == TerminalAuth.KeyPassphrase) {
            sizeMessage("sshkeyauth").put("keypass", keyPassphrase)
        } else {
            sizeMessage("sshauth").put("username", username).put("keep", 0).also {
                if (key.isNotBlank()) it.put("key", key).put("keypass", keyPassphrase)
                else it.put("password", password)
            }
        }
        if (sendControl(msg)) {
            if (!isCurrent(session)) return
            mutableState.value = mutableState.value.copy(auth = TerminalAuth.None, awaitingShell = true, message = null)
            startSshHandshakeTimeout(session)
        }
    }

    internal val rendererEpoch: Int get() = activeEpoch
    @Synchronized internal fun sendRendererInput(text: String, epoch: Int): Boolean =
        epoch != 0 && epoch == activeEpoch && sendInput(text)

    @Synchronized fun sendInput(text: String): Boolean {
        if (disposed || !sessionActive || !mutableState.value.ready || text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INPUT_BYTES) return false
        renderer?.userInput()
        val accepted = if (isSsh) connection.sendText("~$text") else connection.sendBinary(bytes)
        if (!accepted) fail(R.string.terminal_send_failed)
        return accepted
    }

    /** Favorites are literal single-line input, never local echo, a key chord or an implicit Enter. */
    fun insertFavorite(text: String): Boolean =
        ru.papajon.meshcentralmobile.model.FavoriteCommand.safeCommand(text) && sendInput(text)

    @Synchronized internal fun resize(cols: Int, rowCount: Int, width: Int, height: Int) {
        if (disposed) return
        val c = cols.coerceIn(2, 500)
        val r = rowCount.coerceIn(1, 300)
        if (c == columns && r == rows && width == pixelWidth && height == pixelHeight) return
        columns = c; rows = r; pixelWidth = width.coerceIn(1, 16_384); pixelHeight = height.coerceIn(1, 16_384)
        if (mutableState.value.ready) sendSize()
    }

    private fun sizeMessage(action: String) = JSONObject().put("action", action).put("cols", columns)
        .put("rows", rows).put("width", pixelWidth).put("height", pixelHeight)

    private fun sendSize() {
        sendControl(if (isSsh) sizeMessage("resize") else JSONObject().put("ctrlChannel", "102938")
            .put("type", "termsize").put("cols", columns).put("rows", rows))
    }

    private fun sendControl(message: JSONObject): Boolean {
        if (disposed || !sessionActive) return false
        return connection.sendText(message.toString()).also { if (!it) fail(R.string.terminal_send_failed) }
    }

    @Synchronized internal fun rendererFailed() = rendererFailure(R.string.terminal_renderer_failed)
    @Synchronized internal fun rendererInitializationFailed() = rendererFailure(R.string.terminal_webview_update_required)

    private fun rendererFailure(message: Int) {
        if (disposed) return
        rendererBroken = true
        finish(RelayPhase.Failed, UiText.Resource(message))
        mutableState.value = mutableState.value.copy(reopenRequired = true)
        connection.close()
    }

    private fun fail(message: Int) {
        if (disposed || !sessionActive) return
        finish(RelayPhase.Failed, UiText.Resource(message))
        connection.close()
    }

    private fun isCurrent(session: Int): Boolean =
        !disposed && sessionActive && session == generation

    /** Ends the current session without throwing away its pending output, scrollback or renderer. */
    private fun finish(phase: RelayPhase, message: UiText?) {
        endSessionLocked()
        mutableState.value = mutableState.value.copy(phase = phase, ready = false, awaitingShell = false,
            auth = TerminalAuth.None, message = message ?: mutableState.value.message,
            canReconnect = connectionFactory != null && started && !disposed && !rendererBroken)
        renderer?.setInputEnabled(false)
    }

    private fun endSessionLocked() {
        sessionActive = false
        activeEpoch = 0
        generation++
        sshHandshakeStarted = false
        frameJob?.cancel(); frameJob = null
        stateJob?.cancel(); stateJob = null
        shellTimeoutJob?.cancel(); shellTimeoutJob = null
        sshHandshakeTimeoutJob?.cancel(); sshHandshakeTimeoutJob = null
    }

    /** End remote input while retaining the visible scrollback and an explicit reconnect option. */
    @Synchronized fun disconnect() {
        if (disposed) return
        finish(RelayPhase.Closed, null)
        connection.close()
    }

    /** Explicit session disposal, not a Composable detach/rotation callback. Reconnect is final. */
    @Synchronized fun close() {
        if (disposed) return
        disposed = true
        finish(RelayPhase.Closed, null)
        outputBuffer.close()
        writing = false
        connection.close()
        renderer?.setInputEnabled(false)
        renderer?.destroy()
        renderer = null
    }

    companion object {
        const val MAX_PENDING_BYTES = 1_048_576
        const val MAX_INPUT_BYTES = 65_536
        private const val MAX_SESSION_MARKER_CHARS = 512

        /**
         * Aborts a partial escape/OSC sequence and resets terminal modes without clearing the
         * screen or scrollback. The caller writes it as one ordered session-boundary chunk.
         */
        internal const val SESSION_RESET =
            "\u0018\u0018\u001b\\\u001b[!p\u001b[0m\u001b[?1049l\u001b[?47l\u001b[?1047l" +
                "\u001b[?1l\u001b[?6l\u001b[?7h\u001b[?25h\u001b[?1000l\u001b[?1002l" +
                "\u001b[?1003l\u001b[?1004l\u001b[?1005l\u001b[?1006l\u001b[?1015l" +
                "\u001b[?2004l\u001b[4l\u001b[20l\u001b[r\u001b>\u001b(B"

        /** Marker text is not a terminal command stream: controls and user-supplied escapes are removed. */
        internal fun sanitizeSessionMarker(marker: String): String = marker.asSequence()
            .filter { it == '\n' || !Character.isISOControl(it) }
            .take(MAX_SESSION_MARKER_CHARS)
            .joinToString("")

        internal fun sessionStartPayload(marker: String): ByteArray =
            // Resetting the scroll region homes the cursor. Move to the last row before
            // appending the boundary, otherwise it would overwrite the old visible screen.
            (SESSION_RESET + "\u001b[999;1H\r\n" + sanitizeSessionMarker(marker) + "\r\n").toByteArray(Charsets.UTF_8)
    }
}
