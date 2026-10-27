package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.json.optText

import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure

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
    private val okHttpClient: WebSocket.Factory,
    private val scope: CoroutineScope,
    private val onCertificateFailure: (ServerCertificateFailure) -> Unit = {},
    private val phoneInputAllowed: (MeshDevice) -> Boolean = { true },
    private val sendControlMessage: (JSONObject) -> Boolean,
) : NativeRemoteInputSink {
    private val framebuffer = KvmFramebuffer()
    private val decoder = KvmFrameDecoder(paddedMessages = true)
    private val random = SecureRandom()

    @Volatile private var relaySocket: WebSocket? = null
    private var tunnelId: String? = null
    private var currentBaseUrl: HttpUrl? = null
    @Volatile private var currentDevice: MeshDevice? = null
    private data class MousePress(val socket: WebSocket, val x: Int, val y: Int)
    private val mousePresses = mutableMapOf<MouseButton, MousePress>()
    private val keyPresses = mutableMapOf<Pair<Int, Boolean>, WebSocket>()
    @Volatile private var inputSuppressed = false
    @Volatile private var appLocked = false
    private var authCookie: String? = null
    private var relayCookie: String? = null
    @Volatile private var frameVersion = 0L
    /** The agent parses the first tunnel message as the protocol; nothing binary may precede it. */
    @Volatile private var protocolNumberSent = false
    private var manualClose = false
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var resumeWatchdogJob: Job? = null
    @Volatile private var quality = DesktopQuality()
    private val streamMeter = DesktopStreamMeter()
    private var statisticsJob: Job? = null
    private val retry = ConnectionRetryPolicy()
    private var networkAvailable = true
    private var controlAvailable = true
    private var waitingForNetwork = false
    @Volatile private var backgroundPaused = false
    private val initialisation = DesktopInitialisationTracker()

    private val _desktopState = MutableStateFlow(RemoteDesktopState())
    val desktopState: StateFlow<RemoteDesktopState> = _desktopState

    /** Update capability/rights without reconnecting or replacing the framebuffer. */
    @Synchronized fun updatePhoneDevice(device: MeshDevice?) {
        val current = currentDevice?.takeIf { it.isAndroidAgent } ?: return
        val updated = device?.takeIf { it.nodeId == current.nodeId }
            ?: current.copy(remoteInput = PhoneRemoteInput.Unknown)
        if (!updated.phoneInputAllowed) releaseMouseButtons()
        currentDevice = updated
        _desktopState.update { it.copy(device = updated, viewOnly = updated.viewOnly) }
    }

    /** Supplies the relay cookies gathered by the control channel (see `authcookie`). */
    fun updateRelayCredentials(authCookie: String?, relayCookie: String?) {
        this.authCookie = authCookie
        this.relayCookie = relayCookie
    }

    fun connect(baseUrl: HttpUrl, device: MeshDevice) {
        synchronized(this) { releaseMouseButtons() }
        if (!device.canOpenNativeDesktop) {
            _desktopState.value = RemoteDesktopState(
                connectionState = ConnectionState.Failed,
                device = device,
                message = UiText.Resource(R.string.runtime_desktop_requires_agent),
            )
            return
        }

        manualClose = false
        backgroundPaused = false
        cancelReconnect()
        currentBaseUrl = baseUrl
        currentDevice = device
        retry.reset()
        _desktopState.value = RemoteDesktopState(
            connectionState = ConnectionState.LoadingRemoteDesktop,
            device = device,
            message = UiText.Resource(R.string.runtime_desktop_tunnel_requesting),
            viewOnly = device.viewOnly,
        )
        openRelay()
    }

    fun reconnect(resetBudget: Boolean = true) {
        if (!resetBudget && retry.exhausted) return
        val baseUrl = currentBaseUrl
        val device = currentDevice
        if (baseUrl == null || device == null) return
        manualClose = false
        cancelReconnect()
        resumeWatchdogJob?.cancel()
        stopKeepAlive()
        val previousSocket = relaySocket
        synchronized(this) { releaseMouseButtons() }
        relaySocket = null
        sendControlClose(previousSocket)
        previousSocket?.close(CLOSE_NORMAL, "Reconnecting desktop")
        if (resetBudget) retry.reset()
        _desktopState.update {
            it.copy(connectionState = ConnectionState.Reconnecting, message = UiText.Resource(R.string.runtime_desktop_reconnecting_relay))
        }
        openRelay()
    }

    fun hasActiveSession(): Boolean = currentBaseUrl != null && currentDevice != null && !manualClose

    fun onNetworkLost() {
        networkAvailable = false
        onControlLost()
    }

    fun onNetworkAvailable() {
        networkAvailable = true
        // Wait for the control channel's fresh authcookie before opening the relay.
    }

    fun onControlLost() {
        controlAvailable = false
        if (!hasActiveSession() || retry.exhausted) return
        cancelReconnect()
        stopKeepAlive()
        resumeWatchdogJob?.cancel()
        val previous = relaySocket
        synchronized(this) { releaseMouseButtons() }
        relaySocket = null
        previous?.cancel()
        waitForNetwork()
    }

    fun onControlAvailable() {
        controlAvailable = true
        if (waitingForNetwork && hasActiveSession() && networkAvailable && !retry.exhausted) {
            cancelReconnect()
            openRelay()
        }
    }

    private fun waitForNetwork() {
        waitingForNetwork = true
        _desktopState.update { it.copy(connectionState = ConnectionState.Reconnecting,
            handshakeComplete = false, message = UiText.Resource(R.string.reliability_waiting_network)) }
    }

    /** Restore the same session after backgrounding, or reopen its relay if it disappeared. */
    fun resumeAfterBackground(controlReady: Boolean): DesktopResumeAction {
        backgroundPaused = false
        if (appLocked) { sendBinary(KvmInputEncoder.pause(true)); return DesktopResumeAction.None }
        if (retry.exhausted) return DesktopResumeAction.None
        val state = _desktopState.value
        val action = DesktopResumePolicy.onForeground(
            hasSession = hasActiveSession(),
            controlReady = controlReady,
            relaySocketOpen = relaySocket != null,
            handshakeComplete = state.connectionState == ConnectionState.Connected && state.handshakeComplete,
        )
        when (action) {
            DesktopResumeAction.Refresh -> {
                if (!setPaused(false) || !requestRefresh()) reconnect(resetBudget = false) else watchForFreshFrame()
            }
            DesktopResumeAction.Reconnect -> reconnect(resetBudget = false)
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
            if (!backgroundPaused && !appLocked && relaySocket === socket && frameVersion == previousVersion) reconnect(resetBudget = false)
        }
    }

    fun disconnect(resetState: Boolean = true) {
        synchronized(this) { releaseMouseButtons() }
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

    fun requestRefresh(): Boolean = !appLocked && sendBinary(KvmInputEncoder.refresh())

    fun setQuality(jpegQuality: Int, frameRate: Int, scaling: Int = quality.scaling): Boolean {
        quality = DesktopQuality(jpegQuality, frameRate, scaling).normalized()
        _desktopState.update { it.copy(quality = quality) }
        val sent = sendBinary(quality.packet())
        if (sent) requestRefresh()
        return sent
    }

    /** A lock is stronger than visibility/navigation pause and survives reconnect/foreground calls. */
    @Synchronized fun setAppLocked(locked: Boolean) {
        if (appLocked == locked) return
        if (locked) releaseMouseButtons()
        appLocked = locked
        if (locked) {
            resumeWatchdogJob?.cancel()
            framebuffer.clear()
            _desktopState.update { it.copy(framebuffer = null, bytesPerSecond = 0, framesPerSecond = 0f) }
        } else {
            val state = _desktopState.value
            if (state.remoteWidth > 0 && state.remoteHeight > 0)
                framebuffer.apply(KvmPacket.ScreenSize(state.remoteWidth, state.remoteHeight))
        }
        sendBinary(KvmInputEncoder.pause(locked || backgroundPaused))
        if (!locked && !backgroundPaused) requestRefresh()
    }

    /** Pauses the remote screen stream; used when the app goes to the background. */
    @Synchronized fun setPaused(paused: Boolean): Boolean {
        val wasPaused = backgroundPaused
        backgroundPaused = paused
        if (paused || appLocked) {
            releaseMouseButtons()
            resumeWatchdogJob?.cancel()
        }
        val sent = sendBinary(KvmInputEncoder.pause(paused || appLocked))
        if (sent && wasPaused && !paused && !appLocked) requestRefresh()
        return sent
    }

    fun selectDisplay(display: Int): Boolean {
        _desktopState.update { it.copy(selectedDisplay = display) }
        return sendBinary(KvmInputEncoder.setDisplay(display))
    }

    fun requestDisplays(): Boolean = sendBinary(KvmInputEncoder.getDisplays())

    @Synchronized override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
        val key = remoteKeyCode to extended
        if (!down && keyPresses.containsKey(key)) {
            val socket = keyPresses.remove(key)
            return socket === relaySocket && socket?.send(ByteString.of(*KvmInputEncoder.key(remoteKeyCode, false, extended))) == true
        }
        if (!inputAllowed()) return false
        val socket = relaySocket ?: return false
        val sent = socket.send(ByteString.of(*KvmInputEncoder.key(remoteKeyCode, down, extended)))
        if (sent && down) keyPresses[key] = socket
        return sent
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

    @Synchronized override fun sendMouseMove(x: Int, y: Int): Boolean {
        if (!inputAllowed()) return false
        val sent = sendBinary(KvmInputEncoder.mouseMove(x, y))
        if (sent) mousePresses.replaceAll { _, press ->
            if (press.socket === relaySocket) press.copy(x = x, y = y) else press
        }
        return sent
    }

    @Synchronized override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean {
        // A release cannot start input. Release only this relay's own successful press on its
        // original socket, including after input has been locked or permission revoked.
        if (!down && mousePresses.containsKey(button)) {
            val point = if (inputAllowed() && currentDevice?.isAndroidAgent != true) x to y else null
            return releaseMouseButton(button, point)
        }
        if (!inputAllowed()) return false
        val socket = relaySocket ?: return false
        val sent = socket.send(ByteString.of(*KvmInputEncoder.mouseButton(x, y, button, down)))
        if (sent && down) mousePresses[button] = MousePress(socket, x, y)
        return sent
    }

    private fun releaseMouseButton(button: MouseButton, point: Pair<Int, Int>? = null): Boolean {
        val press = mousePresses.remove(button) ?: return false
        if (press.socket !== relaySocket) return false
        return press.socket.send(ByteString.of(*KvmInputEncoder.mouseButton(
            point?.first ?: press.x, point?.second ?: press.y, button, false)))
    }

    private fun releaseMouseButtons() {
        mousePresses.keys.toList().forEach { releaseMouseButton(it) }
        keyPresses.keys.toList().forEach { (key, extended) -> sendRemoteKey(key, false, extended) }
    }

    /** A passive PiP must never accept input, even through an old callback. */
    @Synchronized fun setInputSuppressed(suppressed: Boolean) {
        if (suppressed) releaseMouseButtons()
        inputSuppressed = suppressed
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
        return relaySocket != null && protocolNumberSent && !appLocked && !backgroundPaused && !inputSuppressed && state.inputEnabled &&
            (state.device?.isAndroidAgent != true || phoneInputAllowed(state.device))
    }

    private fun sendBinary(packet: ByteArray): Boolean {
        // State changes made before "c" (saved quality, pause, lock) are applied by the
        // initialisation sequence; sending them early makes the agent read them as the protocol.
        if (!protocolNumberSent) return false
        return relaySocket?.send(ByteString.of(*packet)) == true
    }

    private fun openRelay() {
        val baseUrl = currentBaseUrl ?: return
        val device = currentDevice ?: return
        if (!networkAvailable || !controlAvailable) { waitForNetwork(); return }
        waitingForNetwork = false

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
            controlAvailable = false
            waitForNetwork()
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
            relayListener(device),
        )
    }

    /** Retire sockets on the owner's scope; preserve reader ordering for text and KVM frames. */
    private fun relayListener(device: MeshDevice): WebSocketListener {
        val listener = RelayListener(device)
        fun dispatch(socket: WebSocket, action: () -> Unit) {
            scope.launch { if (relaySocket === socket) action() }
        }
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (relaySocket === webSocket) listener.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (relaySocket === webSocket) listener.onMessage(webSocket, text)
            }
            // Bitmap decoding stays on OkHttp's reader thread, as before; large frames must not
            // block input dispatch on Main. StateFlow safely publishes the resulting frame.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (relaySocket === webSocket) listener.onMessage(webSocket, bytes)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch(webSocket) { listener.onFailure(webSocket, t, response) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch(webSocket) { webSocket.close(CLOSE_NORMAL, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch(webSocket) { listener.onClosed(webSocket, code, reason) }
        }
    }

    private inner class RelayListener(
        private val device: MeshDevice,
    ) : WebSocketListener() {
        private var opened = false
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.LoadingRemoteDesktop,
                    device = currentDevice?.takeIf { it.nodeId == device.nodeId } ?: device,
                    message = UiText.Resource(R.string.runtime_desktop_relay_waiting_agent),
                    viewOnly = (currentDevice?.takeIf { it.nodeId == device.nodeId } ?: device).viewOnly,
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleRelayText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleRelayBinary(webSocket, bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (relaySocket !== webSocket) return
            synchronized(this@MeshDesktopRelay) { mousePresses.clear(); keyPresses.clear() }
            relaySocket = null
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = MeshConnectionError.describe(t, response?.code),
                )
            }
            val certificateFailure = t.serverCertificateFailure()
            if (certificateFailure != null) {
                manualClose = true
                cancelReconnect()
                onCertificateFailure(certificateFailure)
            } else {
                scheduleReconnect(MeshConnectionError.describe(t, response?.code),
                    ConnectionRetryPolicy.isNetworkFailure(t, response?.code, opened, networkAvailable))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (relaySocket !== webSocket) return
            synchronized(this@MeshDesktopRelay) { mousePresses.clear(); keyPresses.clear() }
            relaySocket = null
            if (manualClose) {
                _desktopState.update {
                    it.copy(
                        connectionState = ConnectionState.Offline,
                        message = UiText.Resource(R.string.runtime_desktop_session_finished),
                    )
                }
                return
            }
            val serverReason = UiText.raw(reason)
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = serverReason ?: UiText.Resource(R.string.runtime_desktop_relay_closed),
                )
            }
            scheduleReconnect(serverReason ?: UiText.Resource(R.string.runtime_desktop_relay_closed_reason))
        }
    }

    private fun handleRelayText(webSocket: WebSocket, text: String) {
        when {
            text == "c" || text == "cr" -> {
                if (protocolNumberSent) return
                // MeshCentral waits for the viewer protocol number before forwarding KVM data.
                webSocket.send(PROTOCOL_NUMBER)
                protocolNumberSent = true
                _desktopState.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        message = UiText.Resource(R.string.runtime_desktop_agent_connected),
                        handshakeComplete = false,
                    )
                }
                startKeepAlive()
            }

            text.startsWith("{") -> handleControlJson(webSocket, text)
            else -> Unit
        }
    }

    @Synchronized private fun handleRelayBinary(socket: WebSocket, payload: ByteArray) {
        streamMeter.received(payload.size)
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
                    if (!appLocked) framebuffer.apply(packet)?.let { update = it }
                }

                is KvmPacket.Tile -> if (!appLocked) framebuffer.apply(packet)?.let { update = it }
                is KvmPacket.CopyRect -> if (!appLocked) framebuffer.apply(packet)?.let { update = it }
                is KvmPacket.Displays -> displays = packet
                is KvmPacket.DisplayGeometry -> geometry = packet
                is KvmPacket.Cursor -> cursor = packet
                is KvmPacket.InputLock -> inputLock = packet
                is KvmPacket.KeyState -> keyState = packet
                is KvmPacket.Message -> message = packet.text.ifBlank { null }
                is KvmPacket.Unknown -> Unit
            }
        }

        if (relaySocket !== socket) return
        val previousSize = _desktopState.value.remoteWidth to _desktopState.value.remoteHeight
        // An upgrade or "c" can precede an immediate refusal. A real KVM frame proves recovery.
        if (update != null || screenSize != null) retry.reset()
        if (update != null) {
            frameVersion++
            if (packets.any { it is KvmPacket.Tile || it is KvmPacket.CopyRect }) streamMeter.frame()
        }

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
            if (message != null) next = next.copy(message = UiText.Literal(message))
            next
        }

        if (inputLock?.locked == true) synchronized(this) { releaseMouseButtons() }
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
            frameRate = quality.frameIntervalMillis,
            scaling = quality.scaling,
        ).filter { packet ->
            // Key-up and touch-init are also input packets; phone agents only expose a display.
            val command = packet[1].toInt()
            (!appLocked || command != 8) &&
                ((!appLocked && !inputSuppressed && _desktopState.value.device?.isAndroidAgent != true) || command !in setOf(1, 14))
        }.forEach(::sendBinary)
        sendBinary(KvmInputEncoder.getDisplays())
        if (backgroundPaused || appLocked) sendBinary(KvmInputEncoder.pause(true)) else requestRefresh()
    }

    private fun handleControlJson(webSocket: WebSocket, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optInt("ctrlChannel") != MeshControlMessages.CTRL_CHANNEL) return
        when (json.optText("type")) {
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
        streamMeter.reset(android.os.SystemClock.elapsedRealtime())
        statisticsJob = scope.launch {
            while (true) {
                delay(1000)
                val rate = streamMeter.sample(android.os.SystemClock.elapsedRealtime())
                _desktopState.update { it.copy(bytesReceived = rate.totalBytes,
                    bytesPerSecond = rate.bytesPerSecond, framesPerSecond = rate.framesPerSecond) }
            }
        }
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
        statisticsJob?.cancel()
        statisticsJob = null
        _desktopState.update { it.copy(bytesPerSecond = 0L, framesPerSecond = 0f) }
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

    private fun scheduleReconnect(reason: UiText, networkFailure: Boolean = false) {
        if (manualClose) return
        val baseUrl = currentBaseUrl ?: return
        val device = currentDevice ?: return
        val backoff = retry.nextDelay(networkFailure)
        if (backoff == null) {
            waitingForNetwork = false
            _desktopState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = UiText.resource(
                        R.string.runtime_desktop_relay_stopped,
                        ConnectionRetryPolicy.MAX_SERVER_RETRIES,
                        reason,
                    ),
                )
            }
            return
        }
        waitingForNetwork = networkFailure
        _desktopState.update {
            it.copy(
                connectionState = ConnectionState.Reconnecting,
                attempt = retry.serverRetries,
                message = if (networkFailure) UiText.Resource(R.string.reliability_waiting_network) else UiText.resource(
                    R.string.runtime_desktop_reconnecting_attempt,
                    backoff / 1000,
                    retry.serverRetries,
                    ConnectionRetryPolicy.MAX_SERVER_RETRIES,
                ),
            )
        }
        reconnectJob?.cancel()
        if (!networkAvailable || !controlAvailable) { reconnectJob = null; return }
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
        const val TUNNEL_RESPONSE_ID = "android-tunnel"

        fun now(): Long = System.currentTimeMillis()
    }
}

/** Compression settings requested from the agent (command 5). */
data class DesktopQuality(
    val jpegQuality: Int = 60,
    val frameRate: Int = 30,
    val scaling: Int = 1024,
) {
    // The wire field is a capture interval in milliseconds, NOT frames/second.
    val frameIntervalMillis: Int get() = kotlin.math.ceil(1000.0 / frameRate.coerceIn(1, 50)).toInt()
    fun normalized() = copy(jpegQuality = jpegQuality.coerceIn(1, 100),
        frameRate = frameRate.coerceIn(1, 50), scaling = scaling.coerceIn(128, 1024))
    fun packet(): ByteArray = normalized().let {
        KvmInputEncoder.compression(jpegQuality = it.jpegQuality, scaling = it.scaling, frameRate = it.frameIntervalMillis)
    }
}
