package ru.papajon.meshcentralmobile.terminal

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
)

/** One non-reconnecting session. Own in a ViewModel, close when leaving the session. No saved state. */
class TerminalController(
    private val connection: RelayConnection,
    private val scope: CoroutineScope,
    private val shellReadyTimeoutMillis: Long = 30_000,
    private val inputDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(connection.protocol in setOf(RelayProtocol.AgentTerminal, RelayProtocol.SshTerminal))
        require(shellReadyTimeoutMillis > 0)
    }

    val isSsh: Boolean = connection.protocol == RelayProtocol.SshTerminal
    private val mutableState = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = mutableState.asStateFlow()
    private var started = false
    private var ended = false
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

    @Synchronized fun connect() {
        if (started || ended) return
        started = true
        frameJob = scope.launch(inputDispatcher) {
            connection.incoming.collect { frame ->
                receive(frame)?.let { bytes ->
                    // The suspending wait is outside controller/buffer monitors and off the UI thread.
                    if (outputBuffer.append(bytes) == TerminalOutputBuffer.Result.Oversize) synchronized(this@TerminalController) {
                        if (!ended) fail(R.string.terminal_output_overflow)
                    }
                }
            }
        }
        stateJob = scope.launch {
            connection.state.collect { status ->
                synchronized(this@TerminalController) {
                    if (ended) return@collect
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
                        if (!sshAuthenticated && mutableState.value.auth == TerminalAuth.None) startSshHandshakeTimeout()
                    }
                }
            }
        }
        connection.connect()
    }

    /** Application-context renderer survives Activity recreation; attach only changes its parent. */
    @Synchronized fun renderer(context: Context): TerminalWebRenderer? {
        renderer?.let { return it }
        if (ended) return null
        return try {
            TerminalWebRenderer(context.applicationContext, this).also { renderer = it }
        } catch (_: RuntimeException) {
            rendererInitializationFailed(); null
        } catch (_: LinkageError) {
            rendererInitializationFailed(); null
        }
    }

    @Synchronized internal fun rendererReady() {
        if (ended) return
        rendererLoaded = true
        renderer?.setInputEnabled(mutableState.value.ready)
        drain()
    }

    @Synchronized internal fun outputConsumed() {
        writing = false
        outputBuffer.consumed()
        drain()
    }

    private fun drain() {
        if (!ended && rendererLoaded && !writing) {
            val next = outputBuffer.peek() ?: return
            writing = true
            renderer?.write(next)
        }
    }

    /** Decodes control under a short lock; output admission/suspension happens in the caller. */
    @Synchronized private fun receive(frame: RelayFrame): ByteArray? {
        if (ended) return null
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
                if (sshOutputReceived) markShellReady() else {
                    shellTimeoutJob = scope.launch {
                        delay(shellReadyTimeoutMillis)
                        synchronized(this@TerminalController) {
                            if (!ended && !mutableState.value.ready) fail(R.string.terminal_shell_timeout)
                        }
                    }
                }
            }
            return null
        }
        if (isSsh && text.startsWith("~")) {
            if (text.length > 1) {
                sshOutputReceived = true
                if (sshAuthenticated && !mutableState.value.ready) markShellReady()
                return text.substring(1).toByteArray(Charsets.UTF_8)
            }
            return null
        }
        val control = if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
        if (control != null && control.optString("ctrlChannel") == "102938") {
            when (control.optString("type")) {
                "ping" -> connection.sendText(JSONObject().put("ctrlChannel", "102938").put("type", "pong").toString())
                "console" -> mutableState.value = mutableState.value.copy(message =
                    control.optString("msg").takeIf { it.isNotBlank() }?.let { UiText.Literal(it.take(2048)) })
            }
            return null
        }
        if (isSsh) {
            when (control?.optString("action")) {
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
                    startSshHandshakeTimeout()
                }
                "autherror" -> fail(R.string.terminal_auth_failed)
                "sessiontimeout" -> fail(R.string.terminal_session_timeout)
                "connectionerror" -> fail(R.string.terminal_ssh_connection_failed)
            }
        } else return text.toByteArray(Charsets.UTF_8)
        return null
    }

    /** Bounds both a silent open WebSocket and a server that never answers submitted credentials. */
    private fun startSshHandshakeTimeout() {
        sshHandshakeStarted = true
        sshHandshakeTimeoutJob?.cancel()
        sshHandshakeTimeoutJob = scope.launch {
            delay(shellReadyTimeoutMillis)
            synchronized(this@TerminalController) {
                if (!ended && !sshAuthenticated && mutableState.value.auth == TerminalAuth.None) {
                    fail(R.string.terminal_session_timeout)
                }
            }
        }
    }

    private fun markShellReady() {
        shellTimeoutJob?.cancel()
        mutableState.value = mutableState.value.copy(ready = true, awaitingShell = false,
            auth = TerminalAuth.None, message = null)
        renderer?.setInputEnabled(true)
        sendSize()
    }

    /** Credentials are used only to construct this one outbound message. keep is always zero. */
    @Synchronized fun authenticate(username: String, password: String, key: String = "", keyPassphrase: String = "") {
        if (ended || !isSsh || mutableState.value.auth == TerminalAuth.None) return
        val msg = if (mutableState.value.auth == TerminalAuth.KeyPassphrase) {
            sizeMessage("sshkeyauth").put("keypass", keyPassphrase)
        } else {
            sizeMessage("sshauth").put("username", username).put("keep", 0).also {
                if (key.isNotBlank()) it.put("key", key).put("keypass", keyPassphrase)
                else it.put("password", password)
            }
        }
        if (sendControl(msg)) {
            mutableState.value = mutableState.value.copy(auth = TerminalAuth.None, awaitingShell = true, message = null)
            startSshHandshakeTimeout()
        }
    }

    @Synchronized fun sendInput(text: String): Boolean {
        if (ended || !mutableState.value.ready || text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INPUT_BYTES) return false
        val accepted = if (isSsh) connection.sendText("~$text") else connection.sendBinary(bytes)
        if (!accepted) fail(R.string.terminal_send_failed)
        return accepted
    }

    @Synchronized internal fun resize(cols: Int, rowCount: Int, width: Int, height: Int) {
        if (ended) return
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
        if (ended) return false
        return connection.sendText(message.toString()).also { if (!it) fail(R.string.terminal_send_failed) }
    }

    @Synchronized internal fun rendererFailed() { if (!ended) fail(R.string.terminal_renderer_failed) }
    @Synchronized internal fun rendererInitializationFailed() { if (!ended) fail(R.string.terminal_webview_update_required) }

    private fun fail(message: Int) {
        finish(RelayPhase.Failed, UiText.Resource(message))
        connection.close()
    }

    private fun finish(phase: RelayPhase, message: UiText?) {
        ended = true
        sshHandshakeTimeoutJob?.cancel()
        shellTimeoutJob?.cancel()
        outputBuffer.close(); writing = false
        mutableState.value = mutableState.value.copy(phase = phase, ready = false, awaitingShell = false, auth = TerminalAuth.None,
            message = message ?: mutableState.value.message)
        renderer?.setInputEnabled(false)
    }

    /** End remote input while retaining the visible scrollback for explicit selection/copy. */
    @Synchronized fun disconnect() {
        finish(RelayPhase.Closed, null)
        frameJob?.cancel(); stateJob?.cancel()
        connection.close()
    }

    /** Explicit session disposal, not a Composable detach/rotation callback. */
    @Synchronized fun close() {
        disconnect()
        renderer?.destroy(); renderer = null
    }

    companion object {
        const val MAX_PENDING_BYTES = 1_048_576
        const val MAX_INPUT_BYTES = 65_536
    }
}
