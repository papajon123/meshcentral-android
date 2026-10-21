package ru.papajon.meshcentralmobile.mesh

import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.model.ConnectionState

/**
 * Native Mesh Agent desktop (KVM) session over `/meshrelay.ashx?p=2`.
 *
 * Handshake verified against MeshCentral 1.2.5 `public/scripts/agent-redir-ws-0.1.1.js:150-170`:
 * after the relay sends the text frame `c` (or `cr`), the viewer must send its protocol number
 * as a text frame (`obj.socket.send(obj.protocol)`, 2 for KVM) before any binary KVM data flows.
 * Immediately after the first screen-size command the viewer sends the initialisation batch from
 * `agent-desktop-0.0.2.js:202-256`.
 */
class MeshDesktopRelay(
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val sendControlMessage: (JSONObject) -> Boolean,
) : NativeRemoteInputSink {
    private val framebuffer = KvmFramebuffer()
    private val decoder = KvmFrameDecoder()
    private val random = SecureRandom()

    @Volatile private var relaySocket: WebSocket? = null
    private var tunnelId: String? = null
    private var currentBaseUrl: HttpUrl? = null
    private var currentDevice: MeshDevice? = null
    private var authCookie: String? = null
    private var relayCookie: String? = null
    @Volatile private var frameVersion = 0L
    private var protocolNumberSent = false
    private var manualClose = false
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var resumeWatchdogJob: Job? = null
    private var quality = DesktopQuality()
    private var attempt = 0
    @Volatile private var backgroundPaused = false
    private val initialisation = DesktopInitialisationTracker()

    private val _desktopState = MutableStateFlow(RemoteDesktopState())
    val desktopState: StateFlow<RemoteDesktopState> = _desktopState

    /** Supplies the relay cookies gathered by the control channel (see `authcookie`). */
    fun updateRelayCredentials(authCookie: String?, relayCookie: String?) {
        this.authCookie = authCookie
        this.relayCookie = relayCookie
    }

    fun connect(baseUrl: HttpUrl, device: MeshDevice) {
        if (!device.canOpenNativeDesktop) {
            _desktopState.value = RemoteDesktopState(
                connectionState = ConnectionState.Failed,
                device = device,
                message = "Нативный рабочий стол работает только с\u00A0устройствами Mesh Agent в\u00A0сети и\u00A0с\u00A0правом удалённого управления.",
            )
            return
        }

        manualClose = false
        backgroundPaused = false
        cancelReconnect()
        currentBaseUrl = baseUrl
        currentDevice = device
        attempt = 0
        _desktopState.value = RemoteDesktopState(
            connectionState = ConnectionState.LoadingRemoteDesktop,
            device = device,
            message = "Запрашиваем туннель к\u00A0рабочему столу Mesh Agent.",
            viewOnly = device.viewOnly,
        )
        openRelay()
    }

    fun reconnect() {
        val baseUrl = currentBaseUrl
        val device = currentDevice
        if (baseUrl == null || device == null) return
        manualClose = false
        cancelReconnect()
        resumeWatchdogJob?.cancel()
        stopKeepAlive()
        val previousSocket = relaySocket
        relaySocket = null
        sendControlClose(previousSocket)
        previousSocket?.close(CLOSE_NORMAL, "Reconnecting desktop")
        attempt = 0
        _desktopState.update {
            it.copy(connectionState = ConnectionState.Reconnecting, message = "Переподключаемся к\u00A0ретранслятору рабочего стола.")
        }
        openRelay()
    }

    fun hasActiveSession(): Boolean = currentBaseUrl != null && currentDevice != null && !manualClose

    /** Restore the same session after backgrounding, or reopen its relay if it disappeared. */
    fun resumeAfterBackground(controlReady: Boolean): DesktopResumeAction {
        backgroundPaused = false
        val state = _desktopState.value
        val action = DesktopResumePolicy.onForeground(
            hasSession = hasActiveSession(),
            controlReady = controlReady,
            relaySocketOpen = relaySocket != null,
            handshakeComplete = state.connectionState == ConnectionState.Connected && state.handshakeComplete,
        )
        when (action) {
            DesktopResumeAction.Refresh -> {
                if (!setPaused(false) || !requestRefresh()) reconnect() else watchForFreshFrame()
            }
            DesktopResumeAction.Reconnect -> reconnect()
            DesktopResumeAction.WaitForRelay -> watchForFreshFrame()
            DesktopResumeAction.None, DesktopResumeAction.WaitForControl -> Unit
        }
        return action
    }

    private fun watchForFreshFrame() {
        resumeWatchdogJob?.cancel()
        val socket = relaySocket ?: return
        val previousVersion = frameVersion
        resumeWatchdogJob = scope.launch {
            delay(RESUME_FRAME_TIMEOUT_MILLIS)
            if (!backgroundPaused && relaySocket === socket && frameVersion == previousVersion) reconnect()
        }
    }

    fun disconnect(resetState: Boolean = true) {
        manualClose = true
        cancelReconnect()
        resumeWatchdogJob?.cancel()
        stopKeepAlive()
        sendControlClose()
        relaySocket?.close(CLOSE_NORMAL, "Desktop session closed")
        relaySocket = null
        tunnelId = null
        protocolNumberSent = false
        decoder.reset()
        if (resetState) {
            currentDevice = null
            framebuffer.clear()
            _desktopState.value = RemoteDesktopState()
        }
    }

    fun requestRefresh(): Boolean = sendBinary(KvmInputEncoder.refresh())

    fun setQuality(jpegQuality: Int, frameRate: Int): Boolean {
        quality = quality.copy(jpegQuality = jpegQuality.coerceIn(1, 100), frameRate = frameRate.coerceIn(1, 60))
        return sendBinary(KvmInputEncoder.compression(jpegQuality = quality.jpegQuality, frameRate = quality.frameRate))
    }

    /** Pauses the remote screen stream; used when the app goes to the background. */
    fun setPaused(paused: Boolean): Boolean {
        backgroundPaused = paused
        if (paused) resumeWatchdogJob?.cancel()
        return sendBinary(KvmInputEncoder.pause(paused))
    }

    fun selectDisplay(display: Int): Boolean {
        _desktopState.update { it.copy(selectedDisplay = display) }
        return sendBinary(KvmInputEncoder.setDisplay(display))
    }

    fun requestDisplays(): Boolean = sendBinary(KvmInputEncoder.getDisplays())

    override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
        if (!inputAllowed()) return false
        return sendBinary(KvmInputEncoder.key(remoteKeyCode, down, extended))
    }

    override fun sendUnicodeText(text: String): Int {
        if (!inputAllowed()) return 0
        var sent = 0
        for (char in text) {
            for (packet in KvmInputEncoder.unicodeChar(char)) {
                if (!sendBinary(packet)) return sent
                sent++
            }
        }
        return sent
    }

    override fun sendCtrlAltDel(): Boolean {
        if (!inputAllowed()) return false
        return sendBinary(KvmInputEncoder.ctrlAltDel())
    }

    override fun sendMouseMove(x: Int, y: Int): Boolean {
        if (!inputAllowed()) return false
        return sendBinary(KvmInputEncoder.mouseMove(x, y))
    }

    override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean {
        if (!inputAllowed()) return false
        return sendBinary(KvmInputEncoder.mouseButton(x, y, button, down))
    }

    override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean {
        if (!inputAllowed()) return false
        return KvmInputEncoder.mouseClick(x, y, button).all(::sendBinary)
    }

    override fun sendMouseDoubleClick(x: Int, y: Int): Boolean {
        if (!inputAllowed()) return false
        return KvmInputEncoder.mouseDoubleClick(x, y).all(::sendBinary)
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean {
        if (!inputAllowed()) return false
        return sendBinary(KvmInputEncoder.mouseScroll(x, y, delta))
    }

    fun sendDoubleClick(x: Int, y: Int): Boolean {
        if (!inputAllowed()) return false
        return KvmInputEncoder.mouseDoubleClick(x, y).all(::sendBinary)
    }

    fun sendCtrlShiftEsc(): Boolean {
        if (!inputAllowed()) return false
        return KvmInputEncoder.ctrlShiftEsc().all(::sendBinary)
    }

    private fun inputAllowed(): Boolean {
        val state = _desktopState.value
        return relaySocket != null && state.handshakeComplete && !state.viewOnly && !state.inputLocked
    }

    private fun sendBinary(packet: ByteArray): Boolean {
        return relaySocket?.send(ByteString.of(*packet)) == true
    }

    private fun openRelay() {
        val baseUrl = currentBaseUrl ?: return
        val device = currentDevice ?: return

        decoder.reset()
        initialisation.onNewRelay()
        protocolNumberSent = false
        val id = MeshControlMessages.randomTunnelId(random)
        tunnelId = id
        framebuffer.clear()
        frameVersion = 0L
        _desktopState.update {
            it.copy(framebuffer = null, remoteWidth = 0, remoteHeight = 0,
                frameVersion = 0L, handshakeComplete = false,
                connectionState = if (it.connectionState == ConnectionState.LoadingRemoteDesktop) {
                    ConnectionState.LoadingRemoteDesktop
                } else {
                    ConnectionState.Reconnecting
                })
        }

        val requested = sendControlMessage(
            MeshControlMessages.tunnelRequest(
                nodeId = device.nodeId,
                tunnelId = id,
                relayCookie = relayCookie,
                responseId = TUNNEL_RESPONSE_ID,
            ),
        )
        if (!requested) {
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = "Канал управления не\u00A0авторизован, туннель к\u00A0рабочему столу запросить нельзя.",
                )
            }
            return
        }

        val relayUrl = MeshControlMessages.relayViewerUrl(
            baseUrl = baseUrl,
            nodeId = device.nodeId,
            tunnelId = id,
            authCookie = authCookie,
            relayCookie = relayCookie,
        )
        relaySocket = okHttpClient.newWebSocket(
            Request.Builder().url(relayUrl).build(),
            RelayListener(device),
        )
    }

    private inner class RelayListener(
        private val device: MeshDevice,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.LoadingRemoteDesktop,
                    device = device,
                    message = "Ретранслятор подключён, ждём подтверждения от\u00A0агента.",
                    viewOnly = device.viewOnly,
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleRelayText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleRelayBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (relaySocket !== webSocket) return
            relaySocket = null
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = MeshConnectionError.describe(t, response?.code),
                )
            }
            scheduleReconnect(MeshConnectionError.describe(t, response?.code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (relaySocket !== webSocket) return
            relaySocket = null
            if (manualClose) {
                _desktopState.update {
                    it.copy(
                        connectionState = ConnectionState.Offline,
                        message = reason.ifBlank { "Сеанс рабочего стола завершён." },
                    )
                }
                return
            }
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = reason.ifBlank { "Ретранслятор рабочего стола закрыт." },
                )
            }
            scheduleReconnect(reason.ifBlank { "ретранслятор закрыт" })
        }
    }

    private fun handleRelayText(webSocket: WebSocket, text: String) {
        when {
            text == "c" || text == "cr" -> {
                if (protocolNumberSent) return
                protocolNumberSent = true
                // MeshCentral waits for the viewer protocol number before forwarding KVM data.
                webSocket.send(PROTOCOL_NUMBER)
                _desktopState.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        message = "Агент подключён, начинаем поток KVM.",
                        handshakeComplete = false,
                    )
                }
                startKeepAlive()
            }

            text.startsWith("{") -> handleControlJson(webSocket, text)
            else -> Unit
        }
    }

    private fun handleRelayBinary(payload: ByteArray) {
        val packets = decoder.append(payload)
        if (packets.isEmpty()) return

        var update: FramebufferUpdate? = null
        var screenSize: KvmPacket.ScreenSize? = null
        var displays: KvmPacket.Displays? = null
        var geometry: KvmPacket.DisplayGeometry? = null
        var cursor: KvmPacket.Cursor? = null
        var inputLock: KvmPacket.InputLock? = null
        var keyState: KvmPacket.KeyState? = null
        var message: String? = null

        for (packet in packets) {
            when (packet) {
                is KvmPacket.ScreenSize -> {
                    screenSize = packet
                    framebuffer.apply(packet)?.let { update = it }
                }

                is KvmPacket.Tile -> framebuffer.apply(packet)?.let { update = it }
                is KvmPacket.CopyRect -> framebuffer.apply(packet)?.let { update = it }
                is KvmPacket.Displays -> displays = packet
                is KvmPacket.DisplayGeometry -> geometry = packet
                is KvmPacket.Cursor -> cursor = packet
                is KvmPacket.InputLock -> inputLock = packet
                is KvmPacket.KeyState -> keyState = packet
                is KvmPacket.Message -> message = packet.text.ifBlank { null }
                is KvmPacket.Unknown -> Unit
            }
        }

        val previousSize = _desktopState.value.remoteWidth to _desktopState.value.remoteHeight
        if (update != null) frameVersion++

        _desktopState.update { state ->
            var next = state
            if (update != null) {
                next = next.copy(
                    connectionState = ConnectionState.Connected,
                    framebuffer = update.bitmap,
                    remoteWidth = update.width,
                    remoteHeight = update.height,
                    frameVersion = frameVersion,
                    handshakeComplete = true,
                    message = null,
                )
            }
            screenSize?.let { size ->
                val changed = previousSize.first != size.width || previousSize.second != size.height
                next = next.copy(
                    remoteWidth = size.width,
                    remoteHeight = size.height,
                    handshakeComplete = true,
                    message = if (changed) null else next.message,
                )
            }
            displays?.let { value ->
                next = next.copy(
                    displays = value.ids,
                    selectedDisplay = value.selected.takeIf { it != KvmPacketParser.ALL_DISPLAYS_INDEX },
                )
            }
            geometry?.let { next = next.copy(displayGeometry = it.displays) }
            cursor?.let { next = next.copy(cursorIndex = it.index) }
            inputLock?.let { next = next.copy(inputLocked = it.locked) }
            keyState?.let { next = next.copy(keyboardState = it.mask) }
            if (message != null) next = next.copy(message = message)
            next
        }

        if (screenSize != null) {
            val changed = previousSize.first != screenSize.width || previousSize.second != screenSize.height
            if (initialisation.shouldInitialise(changed)) sendInitialisationSequence()
        }
        if (displays?.ids?.size == 0) requestDisplays()
    }

    /**
     * Exactly what the MeshCentral browser sends after a screen size arrives: compression,
     * unpause, input-lock query, six key-up frames and KVM_INIT_TOUCH.
     */
    private fun sendInitialisationSequence() {
        initialisation.markInitialised()
        KvmInputEncoder.initialisationSequence(
            jpegQuality = quality.jpegQuality,
            frameRate = quality.frameRate,
        ).forEach(::sendBinary)
        sendBinary(KvmInputEncoder.getDisplays())
        if (backgroundPaused) sendBinary(KvmInputEncoder.pause(true)) else requestRefresh()
    }

    private fun handleControlJson(webSocket: WebSocket, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optInt("ctrlChannel") != MeshControlMessages.CTRL_CHANNEL) return
        when (json.optString("type")) {
            "ping" -> webSocket.send(
                JSONObject()
                    .put("ctrlChannel", MeshControlMessages.CTRL_CHANNEL)
                    .put("type", "pong")
                    .put("time", json.optLong("time", now()))
                    .toString(),
            )

            "rtt", "pong" -> {
                val sentAt = json.optLong("time", 0L)
                if (sentAt > 0L) {
                    _desktopState.update { it.copy(rttMillis = (now() - sentAt).coerceAtLeast(0L)) }
                }
            }
            else -> Unit
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            while (true) {
                delay(KEEP_ALIVE_INTERVAL_MILLIS)
                val socket = relaySocket ?: break
                val sent = socket.send(
                    JSONObject()
                        .put("ctrlChannel", MeshControlMessages.CTRL_CHANNEL)
                        .put("type", "rtt")
                        .put("time", now())
                        .toString(),
                )
                if (!sent) break
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun sendControlClose(socket: WebSocket? = relaySocket) {
        socket?.send(
            JSONObject()
                .put("ctrlChannel", MeshControlMessages.CTRL_CHANNEL)
                .put("type", "close")
                .toString(),
        )
    }

    private fun scheduleReconnect(reason: String) {
        if (manualClose) return
        val baseUrl = currentBaseUrl ?: return
        val device = currentDevice ?: return
        if (attempt >= MAX_ATTEMPTS) {
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = "Ретранслятор рабочего стола остановлен после $MAX_ATTEMPTS попыток: $reason",
                )
            }
            return
        }
        attempt++
        val backoff = min(BASE_BACKOFF_MILLIS * (1L shl (attempt - 1)), MAX_BACKOFF_MILLIS)
        _desktopState.update {
            it.copy(
                connectionState = ConnectionState.Reconnecting,
                attempt = attempt,
                message = "Переподключение через ${backoff / 1000} с\u00A0(попытка $attempt из\u00A0$MAX_ATTEMPTS).",
            )
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            if (manualClose) return@launch
            if (currentBaseUrl != baseUrl || currentDevice?.nodeId != device.nodeId) return@launch
            openRelay()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private companion object {
        /** Relay protocol number for KVM (`obj.protocol = 2` in agent-desktop-0.0.2.js). */
        const val PROTOCOL_NUMBER = "2"
        const val CLOSE_NORMAL = 1000
        const val KEEP_ALIVE_INTERVAL_MILLIS = 10_000L
        const val RESUME_FRAME_TIMEOUT_MILLIS = 6_000L
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 20_000L
        const val MAX_ATTEMPTS = 5
        const val TUNNEL_RESPONSE_ID = "android-tunnel"

        fun now(): Long = System.currentTimeMillis()
    }
}

/** Compression settings requested from the agent (command 5). */
data class DesktopQuality(
    val jpegQuality: Int = 60,
    val frameRate: Int = 30,
)
