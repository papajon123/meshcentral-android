package ru.papajon.meshcentralmobile.mesh

import android.util.Log
import androidx.annotation.StringRes
import java.io.Closeable
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.AuthCookie
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.ClipMessage
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.Close
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.Meshes
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.NodeUpdated
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.Nodes
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.RoutingResult
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.ServerInfo
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent.UserInfo
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * MeshCentral control channel (`/control.ashx`) plus the desktop relay it drives.
 *
 * Authentication uses the `x-meshauth` HTTP header, the same mechanism MeshCtrl uses:
 * `base64(user),base64(password)[,base64(2fa)]` (MeshCentral 1.2.5 `webserver.js:9165`).
 * The regular `POST /login` form flow is not used, which keeps the session independent of a
 * browser cookie jar and gives precise `{action:'close', cause, msg}` failure reporting.
 */
class MeshCentralClient(
    private val secureVault: SecureVault,
    private val scope: CoroutineScope,
) : Closeable, NativeRemoteInputSink {
    private val cookieJar = MeshCookieJar()
    private val allowSelfSignedCertificates = AtomicBoolean(false)
    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        // x-meshauth is a custom credential header: never forward it to a redirect target.
        .followRedirects(false)
        .followSslRedirects(false)
        .apply(::applyDebugTlsSettings)
        .build()

    private var baseUrl: HttpUrl? = null
    private var controlSocket: WebSocket? = null
    @Volatile private var controlConnected = false
    private var controlAttempt = 0
    private var controlReconnectJob: Job? = null
    private var authCookieJob: Job? = null
    private var manualClose = false
    private var authRejected = false
    @Volatile private var appForeground = true
    @Volatile private var pendingDesktopResume = false

    private var sessionUsername: String? = null
    private var sessionPassword: String? = null
    private var sessionToken: String? = null
    private var authCookie: String? = null
    private var relayCookie: String? = null

    private var clipboardRequest: ClipboardRequest? = null
    private var clipboardTimeoutJob: Job? = null

    private val relay = MeshDesktopRelay(
        okHttpClient = okHttpClient,
        scope = scope,
        sendControlMessage = ::sendControlJson,
    )

    private val _clientState = MutableStateFlow(MeshClientState())
    val clientState: StateFlow<MeshClientState> = _clientState
    val desktopState: StateFlow<RemoteDesktopState> = relay.desktopState

    private val _clipboardState = MutableStateFlow<ClipboardTransfer?>(null)
    val clipboardState: StateFlow<ClipboardTransfer?> = _clipboardState

    val remoteInputSink: NativeRemoteInputSink = this

    /**
     * Debug builds only: accepts self-signed certificates (for a local MeshCentral test server).
     * Release builds never install this trust manager and always validate with the platform CAs.
     */
    fun setAllowSelfSignedCertificates(enabled: Boolean) {
        if (BuildConfig.DEBUG) allowSelfSignedCertificates.set(enabled)
    }

    private fun applyDebugTlsSettings(builder: OkHttpClient.Builder) {
        if (!BuildConfig.DEBUG) return
        val platform = platformTrustManager()
        val permissive = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                platform.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (allowSelfSignedCertificates.get()) return
                platform.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(permissive), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, permissive)
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        builder.hostnameVerifier { hostname, session ->
            allowSelfSignedCertificates.get() || defaultVerifier.verify(hostname, session)
        }
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun configureServer(serverUrl: String) {
        val parsed = serverUrl.toHttpUrlOrNullNormalized() ?: return
        if (baseUrl == parsed) return
        disconnectControl(manual = true)
        relay.disconnect()
        cookieJar.clear()
        sessionUsername = null
        sessionPassword = null
        sessionToken = null
        authCookie = null
        relayCookie = null
        relay.updateRelayCredentials(null, null)
        isSiteAdmin = false
        baseUrl = parsed
        _clientState.value = MeshClientState(
            connectionState = ConnectionState.Offline,
            message = UiText.Resource(R.string.runtime_server_saved),
        )
    }

    /** Signs in with `x-meshauth`. [token] carries a two-factor code, or a `**email**` request. */
    fun login(serverUrl: String, username: String, password: String, token: String? = null) {
        val parsed = serverUrl.toHttpUrlOrNullNormalized()
        if (parsed == null) {
            _clientState.value = MeshClientState(
                connectionState = ConnectionState.Failed,
                message = UiText.Resource(R.string.runtime_server_invalid),
            )
            return
        }
        if (username.isBlank()) {
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = UiText.Resource(R.string.runtime_username_required),
                )
            }
            return
        }

        if (baseUrl != parsed || sessionUsername != username.trim()) {
            relay.disconnect()
            isSiteAdmin = false
            _clientState.value = MeshClientState()
        }
        manualClose = false
        authRejected = false
        controlAttempt = 0
        baseUrl = parsed
        sessionUsername = username.trim()
        sessionPassword = password
        sessionToken = token?.takeIf { it.isNotBlank() }
        cookieJar.clear()
        authCookie = null
        relayCookie = null
        relay.updateRelayCredentials(null, null)

        _clientState.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                message = UiText.Resource(R.string.runtime_connecting_server),
                lastError = null,
                twoFactorChallenge = null,
            )
        }
        openControlSocket()
    }

    fun requestTwoFactorCode(channel: TwoFactorChannel) {
        val username = sessionUsername ?: return
        val password = sessionPassword ?: return
        login(
            serverUrl = baseUrl?.toString() ?: return,
            username = username,
            password = password,
            token = channel.requestToken,
        )
    }

    fun submitTwoFactorCode(code: String) {
        val username = sessionUsername ?: return
        val password = sessionPassword ?: return
        login(baseUrl?.toString() ?: return, username, password, code)
    }

    fun loginWithStoredCredentials(serverUrl: String): Boolean {
        val credentials = secureVault.getCredentials(serverUrl) ?: return false
        login(serverUrl, credentials.username, credentials.password, token = null)
        return true
    }

    fun refreshDevices() {
        if (!controlConnected) {
            retryConnection()
            return
        }
        requestControlSnapshot()
    }

    /** A user retry starts a new attempt budget, even after automatic retries were exhausted. */
    fun retryConnection() {
        if (sessionUsername == null || sessionPassword == null || authRejected) return
        controlAttempt = 0
        manualClose = false
        openControlSocket()
    }

    fun onNetworkAvailable() {
        if (!manualClose && !authRejected && !controlConnected &&
            _clientState.value.connectionState != ConnectionState.Connecting
        ) retryConnection()
    }

    fun onAppBackground() {
        appForeground = false
        relay.setPaused(true)
    }

    fun onAppForeground() {
        appForeground = true
        onNetworkAvailable()
        if (relay.hasActiveSession()) {
            pendingDesktopResume = !controlConnected
            if (controlConnected) relay.resumeAfterBackground(controlReady = true)
        }
    }

    fun connectDesktop(device: MeshDevice) {
        val url = baseUrl
        if (url == null) {
            _clientState.update {
                it.copy(connectionState = ConnectionState.Failed, message = UiText.Resource(R.string.runtime_server_not_set))
            }
            return
        }
        if (!controlConnected || !_clientState.value.devicesCurrent) {
            _clientState.update {
                it.copy(connectionState = ConnectionState.Failed, message = UiText.Resource(R.string.runtime_sign_in_before_desktop))
            }
            return
        }
        relay.connect(url, device)
        pendingDesktopResume = false
        _clientState.update {
            it.copy(
                connectionState = ConnectionState.LoadingRemoteDesktop,
                message = UiText.resource(R.string.runtime_opening_device, device.name),
            )
        }
    }

    fun reconnectDesktop() = relay.reconnect()

    fun requestRemoteRefresh(): Boolean = relay.requestRefresh()

    fun setRemoteQuality(jpegQuality: Int, frameRate: Int): Boolean = relay.setQuality(jpegQuality, frameRate)

    fun setRemotePaused(paused: Boolean): Boolean = relay.setPaused(paused)

    fun selectRemoteDisplay(display: Int): Boolean = relay.selectDisplay(display)

    fun requestRemoteDisplays(): Boolean = relay.requestDisplays()

    fun sendRemoteDoubleClick(x: Int, y: Int): Boolean = relay.sendDoubleClick(x, y)

    fun sendRemoteCtrlShiftEsc(): Boolean = relay.sendCtrlShiftEsc()

    fun disconnectDesktop() {
        pendingDesktopResume = false
        relay.disconnect()
        _clientState.update {
            it.copy(connectionState = if (controlConnected) ConnectionState.Authenticated else ConnectionState.Offline)
        }
    }

    fun logout(clearStoredCredentials: Boolean) {
        relay.disconnect()
        disconnectControl(manual = true)
        cookieJar.clear()
        sessionUsername = null
        sessionPassword = null
        sessionToken = null
        authCookie = null
        relayCookie = null
        relay.updateRelayCredentials(null, null)
        clipboardRequest = null
        clipboardTimeoutJob?.cancel()
        _clipboardState.value = null
        if (clearStoredCredentials) secureVault.clearCredentials()
        _clientState.value = MeshClientState(
            connectionState = ConnectionState.Offline,
            message = UiText.Resource(R.string.runtime_session_ended),
        )
    }

    override fun close() {
        relay.disconnect()
        disconnectControl(manual = true)
        okHttpClient.dispatcher.executorService.shutdown()
    }

    // region clipboard

    /** "Get the clipboard from the PC" — the text is returned through [clipboardState]. */
    fun fetchClipboardFromRemote(device: MeshDevice): Boolean {
        if (!controlConnected) {
            _clipboardState.value = ClipboardTransfer(
                kind = ClipboardKind.Fetch,
                nodeId = device.nodeId,
                status = ClipboardStatus.Failed,
                detail = UiText.Resource(R.string.runtime_sign_in_before_clipboard),
            )
            return false
        }
        return startClipboardRequest(
            request = ClipboardRequest(kind = ClipboardKind.Fetch, nodeId = device.nodeId, tag = nextTag()),
            message = { tag -> MeshControlMessages.fetchClipboard(device.nodeId, tag) },
        )
    }

    /** "Send this text to the PC clipboard". */
    fun sendClipboardToRemote(device: MeshDevice, text: String): Boolean {
        if (!controlConnected) {
            _clipboardState.value = ClipboardTransfer(
                kind = ClipboardKind.Send,
                nodeId = device.nodeId,
                status = ClipboardStatus.Failed,
                detail = UiText.Resource(R.string.runtime_sign_in_before_clipboard),
            )
            return false
        }
        if (text.isEmpty()) {
            _clipboardState.value = ClipboardTransfer(
                kind = ClipboardKind.Send,
                nodeId = device.nodeId,
                status = ClipboardStatus.Failed,
                detail = UiText.Resource(R.string.runtime_clipboard_text_required),
            )
            return false
        }
        return startClipboardRequest(
            request = ClipboardRequest(ClipboardKind.Send, device.nodeId, nextTag(), text = text),
            message = { tag -> MeshControlMessages.sendClipboard(device.nodeId, text, tag) },
        )
    }

    fun clearClipboardStatus() {
        clipboardTimeoutJob?.cancel()
        clipboardRequest = null
        _clipboardState.value = null
    }

    private fun startClipboardRequest(request: ClipboardRequest, message: (String?) -> JSONObject): Boolean {
        clipboardTimeoutJob?.cancel()
        clipboardRequest = request
        _clipboardState.value = ClipboardTransfer(
            kind = request.kind,
            nodeId = request.nodeId,
            text = request.text,
            status = ClipboardStatus.Pending,
            detail = if (request.kind == ClipboardKind.Fetch) {
                UiText.Resource(R.string.runtime_clipboard_waiting_fetch)
            } else {
                UiText.Resource(R.string.runtime_clipboard_sending)
            },
        )
        if (!sendControlJson(message(request.tag))) {
            clipboardRequest = null
            _clipboardState.value = ClipboardTransfer(
                kind = request.kind,
                nodeId = request.nodeId,
                status = ClipboardStatus.Failed,
                detail = UiText.Resource(R.string.runtime_control_channel_not_connected),
            )
            return false
        }
        clipboardTimeoutJob = scope.launch {
            delay(CLIPBOARD_TIMEOUT_MILLIS)
            val pending = clipboardRequest
            if (pending != null && pending.tag == request.tag) {
                clipboardRequest = null
                _clipboardState.value = ClipboardTransfer(
                    kind = pending.kind,
                    nodeId = pending.nodeId,
                    status = ClipboardStatus.Failed,
                    detail = if (pending.kind == ClipboardKind.Fetch) {
                        UiText.Resource(R.string.runtime_clipboard_fetch_timeout)
                    } else {
                        UiText.Resource(R.string.runtime_clipboard_send_unconfirmed)
                    },
                )
            }
        }
        return true
    }

    private fun handleClipboardMessage(message: ClipMessage) {
        val pending = clipboardRequest
        if (pending == null) {
            if (message.type == "getclip" && message.data != null) {
                _clipboardState.value = ClipboardTransfer(
                    kind = ClipboardKind.Fetch,
                    nodeId = "",
                    text = message.data,
                    status = ClipboardStatus.Success,
                    detail = UiText.Resource(R.string.runtime_clipboard_received),
                )
            }
            return
        }
        val matchesKind = when (pending.kind) {
            ClipboardKind.Fetch -> message.type == "getclip"
            ClipboardKind.Send -> message.type == "setclip"
        }
        if (!matchesKind) return
        clipboardTimeoutJob?.cancel()
        clipboardRequest = null
        _clipboardState.value = when {
            message.success == false -> ClipboardTransfer(
                kind = pending.kind,
                nodeId = pending.nodeId,
                status = ClipboardStatus.Failed,
                detail = UiText.Resource(R.string.runtime_clipboard_rejected),
            )

            pending.kind == ClipboardKind.Fetch -> {
                val text = message.data
                if (text == null) {
                    ClipboardTransfer(
                        kind = pending.kind,
                        nodeId = pending.nodeId,
                        status = ClipboardStatus.Failed,
                        detail = UiText.Resource(R.string.runtime_clipboard_empty_response),
                    )
                } else {
                    ClipboardTransfer(
                        kind = pending.kind,
                        nodeId = pending.nodeId,
                        text = text,
                        status = ClipboardStatus.Success,
                        detail = UiText.resource(R.string.runtime_clipboard_received_chars, text.length),
                    )
                }
            }

            else -> ClipboardTransfer(
                kind = pending.kind,
                nodeId = pending.nodeId,
                text = pending.text,
                status = ClipboardStatus.Success,
                detail = UiText.Resource(R.string.runtime_clipboard_sent),
            )
        }
    }

    // endregion

    override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
        return relay.sendRemoteKey(remoteKeyCode, down, extended)
    }

    override fun sendUnicodeText(text: String): Int {
        val sent = relay.sendUnicodeText(text)
        if (BuildConfig.DEBUG && sent > 0) {
            Log.d(TAG, "Forwarded $sent unicode key events to the remote session")
        }
        return sent
    }

    override fun sendCtrlAltDel(): Boolean = relay.sendCtrlAltDel()

    override fun sendMouseMove(x: Int, y: Int): Boolean = relay.sendMouseMove(x, y)

    override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean {
        return relay.sendMouseButton(x, y, button, down)
    }

    override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean = relay.sendMouseClick(x, y, button)

    override fun sendMouseDoubleClick(x: Int, y: Int): Boolean = relay.sendDoubleClick(x, y)

    override fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean {
        return relay.sendMouseScroll(x, y, delta)
    }

    private fun openControlSocket() {
        val url = baseUrl ?: return
        val username = sessionUsername ?: return
        val password = sessionPassword ?: return
        disconnectControl(manual = true)
        manualClose = false
        _clientState.update {
            it.copy(connectionState = ConnectionState.Connecting, message = UiText.Resource(R.string.runtime_opening_control_channel))
        }
        val request = Request.Builder()
            .url(url.toWebSocketUrl("/control.ashx"))
            .header(HEADER_MESH_AUTH, MeshAuth.headerValue(username, password, sessionToken))
            .build()
        controlSocket = okHttpClient.newWebSocket(request, ControlListener())
    }

    private fun disconnectControl(manual: Boolean) {
        if (manual) manualClose = true
        controlConnected = false
        _clientState.update { it.copy(socketConnected = false, devicesCurrent = false) }
        controlReconnectJob?.cancel()
        controlReconnectJob = null
        authCookieJob?.cancel()
        authCookieJob = null
        val previous = controlSocket
        controlSocket = null
        previous?.close(1000, "control closed")
    }

    private fun requestControlSnapshot() {
        sendControlJson(MeshControlMessages.serverInfo())
        sendControlJson(MeshControlMessages.meshes())
        sendControlJson(MeshControlMessages.nodes(responseId = "android-nodes"))
        sendControlJson(MeshControlMessages.authCookie())
    }

    private fun sendControlJson(json: JSONObject): Boolean {
        return controlSocket?.send(json.toString()) == true
    }

    private fun reconnectControl(@StringRes reasonRes: Int) {
        if (manualClose || authRejected) return
        if (controlAttempt >= MAX_CONTROL_ATTEMPTS) {
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = UiText.Resource(R.string.runtime_reconnect_exhausted),
                )
            }
            return
        }
        controlAttempt++
        val backoff = min(BASE_BACKOFF_MILLIS * (1L shl (controlAttempt - 1)), MAX_BACKOFF_MILLIS)
        _clientState.update {
            it.copy(
                connectionState = ConnectionState.Reconnecting,
                message = UiText.resource(
                    reasonRes,
                    backoff / 1000,
                    controlAttempt,
                    MAX_CONTROL_ATTEMPTS,
                ),
            )
        }
        controlReconnectJob?.cancel()
        controlReconnectJob = scope.launch {
            delay(backoff)
            if (manualClose || authRejected) return@launch
            openControlSocket()
        }
    }

    private fun mergeNodes(
        incoming: List<MeshDevice>,
        replaceAll: Boolean,
    ): List<MeshDevice> {
        val state = _clientState.value
        val merged = if (replaceAll) {
            incoming
        } else {
            val byId = state.devices.associateBy { it.nodeId }.toMutableMap()
            incoming.forEach { device ->
                val existing = byId[device.nodeId]
                byId[device.nodeId] = if (existing == null) device else mergeDevice(existing, device)
            }
            byId.values.toList()
        }
        val resolved = MeshControlParser.resolveRights(
            devices = merged,
            groups = state.groups,
            userId = state.userId,
            siteAdmin = state.userId != null && isSiteAdmin,
        )
        return resolved.sortedWith(
            compareBy<MeshDevice> { !it.online }
                .thenBy { it.meshName.lowercase(Locale.US) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
    }

    private var isSiteAdmin = false

    /** Keeps fields the server does not resend (for example `agent` on `changenode`). */
    private fun mergeDevice(existing: MeshDevice, incoming: MeshDevice): MeshDevice {
        return incoming.copy(
            meshId = incoming.meshId.ifBlank { existing.meshId },
            meshName = incoming.meshName.ifBlank { existing.meshName },
            icon = if (incoming.icon != 0) incoming.icon else existing.icon,
            osDescription = incoming.osDescription.ifBlank { existing.osDescription },
            ipAddress = incoming.ipAddress.ifBlank { existing.ipAddress },
            mtype = if (incoming.mtype != 0) incoming.mtype else existing.mtype,
            agentId = incoming.agentId ?: existing.agentId,
            agentVersion = incoming.agentVersion ?: existing.agentVersion,
            agentCaps = incoming.agentCaps ?: existing.agentCaps,
            rights = incoming.rights ?: existing.rights,
        )
    }

    private fun applyGroups(groups: List<MeshGroup>) {
        val state = _clientState.value
        val named = groups.map { group -> group }
        val namesById = named.associateBy { it.id }
        val devices = MeshControlParser.resolveRights(
            devices = state.devices,
            groups = named,
            userId = state.userId,
            siteAdmin = isSiteAdmin,
        ).map { device ->
            device.copy(meshName = namesById[device.meshId]?.name ?: device.meshName)
        }.sortedWith(
            compareBy<MeshDevice> { !it.online }
                .thenBy { it.meshName.lowercase(Locale.US) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
        _clientState.update { it.copy(groups = named, devices = devices) }
    }

    private fun handleNodeEvent(event: MeshNodeEvent) {
        when (event) {
            is MeshNodeEvent.NodeConnected -> {
                val devices = _clientState.value.devices.map { device ->
                    if (device.nodeId == event.nodeId) device.copy(conn = event.conn, pwr = event.pwr) else device
                }
                _clientState.update { it.copy(devices = sortDevices(devices)) }
            }

            is MeshNodeEvent.NodeChanged -> upsertDevice(event.device)
            is MeshNodeEvent.NodeAdded -> upsertDevice(event.device)
            is MeshNodeEvent.NodeRemoved -> {
                val devices = _clientState.value.devices.filterNot { it.nodeId == event.nodeId }
                _clientState.update { it.copy(devices = sortDevices(devices)) }
            }

            is MeshNodeEvent.MeshChanged -> {
                if (event.meshId != null) requestControlSnapshot()
            }
        }
    }

    private fun upsertDevice(device: MeshDevice) {
        _clientState.update { state ->
            val existing = state.devices.firstOrNull { it.nodeId == device.nodeId }
            val merged = if (existing != null) mergeDevice(existing, device) else device
            val withRights = MeshControlParser.resolveRights(
                devices = listOf(merged),
                groups = state.groups,
                userId = state.userId,
                siteAdmin = isSiteAdmin,
            ).first().let { resolved ->
                resolved.copy(meshName = state.groups.firstOrNull { it.id == resolved.meshId }?.name ?: resolved.meshName)
            }
            val devices = state.devices.filterNot { it.nodeId == device.nodeId } + withRights
            state.copy(devices = sortDevices(devices))
        }
    }

    private fun sortDevices(devices: List<MeshDevice>): List<MeshDevice> {
        return devices.sortedWith(
            compareBy<MeshDevice> { !it.online }
                .thenBy { it.meshName.lowercase(Locale.US) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
    }

    private inner class ControlListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (controlSocket !== webSocket) return
            controlConnected = true
            controlAttempt = 0
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Authenticated,
                    message = UiText.Resource(R.string.runtime_control_authorized),
                    socketConnected = true,
                    lastError = null,
                )
            }
            requestControlSnapshot()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (controlSocket !== webSocket) return
            when (val event = MeshControlParser.parse(text)) {
                is ServerInfo -> _clientState.update {
                    it.copy(serverName = event.name ?: it.serverName)
                }

                is UserInfo -> {
                    isSiteAdmin = event.siteAdmin
                    _clientState.update { state ->
                        state.copy(
                            userId = event.userId ?: state.userId,
                            requiresSignIn = false,
                            serverName = state.serverName ?: event.name,
                        )
                    }
                    // Credentials are only persisted once the server accepted them.
                    sessionUsername?.let { user ->
                        sessionPassword?.let { pass ->
                            baseUrl?.let { server -> secureVault.saveCredentials(user, pass, server.toString()) }
                        }
                    }
                    sendControlJson(MeshControlMessages.meshes())
                    sendControlJson(MeshControlMessages.nodes(responseId = "android-nodes"))
                }

                is Meshes -> applyGroups(event.groups)

                is Nodes -> {
                    val devices = mergeNodes(event.devices, replaceAll = true)
                    _clientState.update {
                        it.copy(
                            connectionState = if (it.connectionState == ConnectionState.Connecting) {
                                ConnectionState.Authenticated
                            } else {
                                it.connectionState
                            },
                            devices = devices,
                            devicesCurrent = true,
                            lastError = null,
                            message = UiText.resource(
                                R.string.runtime_devices_loaded,
                                devices.size,
                                devices.count { device -> device.online },
                            ),
                        )
                    }
                }

                is AuthCookie -> {
                    authCookie = event.cookie
                    relayCookie = event.relayCookie
                    relay.updateRelayCredentials(event.cookie, event.relayCookie)
                    scheduleAuthCookieRefresh()
                    if (appForeground && pendingDesktopResume && controlConnected) {
                        pendingDesktopResume = false
                        relay.resumeAfterBackground(controlReady = true)
                    }
                }

                is NodeUpdated -> handleNodeEvent(event.event)

                is ClipMessage -> handleClipboardMessage(event)

                is RoutingResult -> {
                    if (event.result != null && !event.result.equals("OK", ignoreCase = true)) {
                        _clientState.update {
                            it.copy(
                                message = UiText.resource(
                                    R.string.runtime_routing_failed,
                                    UiText.Literal(event.result),
                                ),
                            )
                        }
                    }
                }

                is Close -> handleClose(event)

                is MeshControlEvent.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (controlSocket !== webSocket) return
            if (relay.hasActiveSession()) pendingDesktopResume = true
            controlConnected = false
            val reason = MeshConnectionError.describe(t, response?.code)
            Log.w(TAG, "Control socket failed: ${t.javaClass.simpleName}")
            authRejected = response?.code == 401 || response?.code == 403
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    socketConnected = false,
                    devicesCurrent = false,
                    requiresSignIn = it.requiresSignIn || authRejected,
                    lastError = reason,
                    message = reason,
                )
            }
            reconnectControl(R.string.runtime_control_failure_reconnecting)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (controlSocket === webSocket) webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (controlSocket !== webSocket) return
            if (relay.hasActiveSession()) pendingDesktopResume = true
            controlConnected = false
            if (manualClose || authRejected) {
                _clientState.update {
                    it.copy(
                        connectionState = if (manualClose) ConnectionState.Offline else ConnectionState.Failed,
                        socketConnected = false,
                        devicesCurrent = false,
                    )
                }
                return
            }
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    socketConnected = false,
                    devicesCurrent = false,
                    lastError = UiText.Resource(R.string.runtime_connection_lost),
                    message = UiText.Resource(R.string.runtime_connection_lost),
                )
            }
            reconnectControl(R.string.runtime_control_closed_reconnecting)
        }
    }

    private fun handleClose(event: Close) {
        controlConnected = false
        val failure = MeshAuthDiagnostics.classify(event.cause, event.message)
        authRejected = failure != MeshAuthFailure.Unknown || event.cause.equals("noauth", ignoreCase = true)
        val challenge = event.twoFactorChallenge
        val description = MeshAuthDiagnostics.describe(failure, challenge)
        Log.w(TAG, "Control channel rejected authentication: $failure")
        _clientState.update {
            it.copy(
                connectionState = ConnectionState.Failed,
                socketConnected = false,
                devicesCurrent = false,
                requiresSignIn = it.requiresSignIn || authRejected,
                twoFactorChallenge = challenge,
                lastError = description,
                message = description,
            )
        }
        controlSocket?.close(1000, null)
    }

    private fun scheduleAuthCookieRefresh() {
        authCookieJob?.cancel()
        authCookieJob = scope.launch {
            delay(AUTH_COOKIE_REFRESH_MILLIS)
            if (controlConnected) sendControlJson(MeshControlMessages.authCookie())
        }
    }

    private fun nextTag(): String = "android-${System.currentTimeMillis()}"

    private fun String.toHttpUrlOrNullNormalized(): HttpUrl? {
        val candidate = trim()
        if (candidate.isBlank()) return null
        return (if (candidate.endsWith("/")) candidate else "$candidate/").toHttpUrlOrNull()
    }

    private fun HttpUrl.toWebSocketUrl(path: String): String {
        val secure = isHttps
        val httpUrl = newBuilder()
            .encodedPath(encodedPath.trimEnd('/') + "/" + path.trimStart('/'))
            .query(null)
            .build()
            .toString()
        return when {
            secure && httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
            !secure && httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
            else -> httpUrl
        }
    }

    private data class ClipboardRequest(
        val kind: ClipboardKind,
        val nodeId: String,
        val tag: String?,
        val text: String? = null,
    )

    companion object {
        const val HEADER_MESH_AUTH = "x-meshauth"
        private const val TAG = "MeshCentralClient"

        private const val BASE_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 20_000L
        private const val MAX_CONTROL_ATTEMPTS = 5
        private const val CLIPBOARD_TIMEOUT_MILLIS = 15_000L
        private const val AUTH_COOKIE_REFRESH_MILLIS = 25 * 60 * 1000L
    }
}

/** Two-factor delivery channels MeshCentral can use. */
enum class TwoFactorChannel(val requestToken: String) {
    Email(MeshAuth.REQUEST_EMAIL_TOKEN),
    Sms(MeshAuth.REQUEST_SMS_TOKEN),
    Push(MeshAuth.REQUEST_PUSH_TOKEN),
}
