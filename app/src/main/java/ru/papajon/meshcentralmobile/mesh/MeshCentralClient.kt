package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.json.optText

import android.util.Log
import androidx.annotation.StringRes
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import okio.ByteString
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
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust
import ru.papajon.meshcentralmobile.mesh.session.ManagedRelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayCredentials
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerTls
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure

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
    private class ControlCookieScope(val serverUrl: String, val username: String)
    private var baseUrl: HttpUrl? = null
    private var pinnedSpkiSha256: String? = null
    private var allowSelfSignedCertificates = false
    @Volatile private var certificateRejected = false
    private val transportSockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private class TlsTransport(val client: OkHttpClient, val rememberedTrust: RememberedDeviceTrust?)
    @Volatile private var tlsTransport = createTlsTransport()
    private val okHttpClient: OkHttpClient get() = tlsTransport.client

    private fun createTlsTransport(): TlsTransport {
        val trust = when {
            baseUrl == null -> null
            baseUrl?.isHttps == false -> if (BuildConfig.DEBUG) RememberedDeviceTrust.DebugHttp else null
            pinnedSpkiSha256 != null -> RememberedDeviceTrust.Pinned(requireNotNull(pinnedSpkiSha256))
            BuildConfig.DEBUG && allowSelfSignedCertificates -> null // A bypass is never platform trust.
            else -> RememberedDeviceTrust.Platform
        }
        return TlsTransport(createHttpClient(), trust)
    }

    private fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        // x-meshauth is a custom credential header: never forward it to a redirect target.
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { ServerTls(baseUrl, pinnedSpkiSha256, BuildConfig.DEBUG && allowSelfSignedCertificates).applyTo(this) }
        .build()

    /** Desktop and all tool/file relays resolve the current immutable TLS policy at connect time. */
    private val socketFactory = object : WebSocket.Factory {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val transport = tlsTransport
            val identity = request.tag(ControlCookieScope::class.java)
            // A queued request retains its own identity even if the user switches accounts before
            // OkHttp asks for cookies. Relays have no tag and use only the ordinary session jar.
            val requestTransport = if (identity == null) transport.client else transport.client.newBuilder()
                .cookieJar(cookieJar.forControlRequest { url ->
                    secureVault.rememberedDevice.cookieForRequest(identity.serverUrl, identity.username, url,
                        BuildConfig.DEBUG, transport.rememberedTrust)
                }).build()
            val socket = requestTransport.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket, response)
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(webSocket, code, reason)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    transportSockets.remove(webSocket)
                    listener.onClosed(webSocket, code, reason)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    transportSockets.remove(webSocket)
                    listener.onFailure(webSocket, t, response)
                }
            })
            transportSockets.add(socket)
            return socket
        }
    }
    private var controlSocket: WebSocket? = null
    @Volatile private var controlConnected = false
    private val controlRetry = ConnectionRetryPolicy()
    private var networkAvailable = true
    private var waitingForNetwork = false
    private var controlReconnectJob: Job? = null
    private var authCookieJob: Job? = null
    private var manualClose = false
    private var authRejected = false
    @Volatile private var appForeground = true
    @Volatile private var pendingDesktopResume = false

    private var sessionUsername: String? = null
    private var sessionPassword: String? = null
    private var sessionToken: String? = null
    private var rememberDeviceRequested = false
    private var awaitingRememberCookie = false
    private var rememberCookieDays = 30
    @Volatile private var authCookie: String? = null
    @Volatile private var relayCookie: String? = null
    private val sessionGeneration = AtomicLong()
    private val additionalRelays = ConcurrentHashMap.newKeySet<ManagedRelayConnection>()
    private val _controlFailures = kotlinx.coroutines.flow.MutableSharedFlow<ru.papajon.meshcentralmobile.mesh.control.ControlRequestFailure>(extraBufferCapacity = 64)
    val controlFailures: kotlinx.coroutines.flow.SharedFlow<ru.papajon.meshcentralmobile.mesh.control.ControlRequestFailure> = _controlFailures
    private val controlRequests = ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker(
        onLateFailure = { _controlFailures.tryEmit(it) }, send = ::sendControlJson)
    private val phoneInfoRevision = AtomicLong()
    private val phoneInfoRequests = ConcurrentHashMap<String, Long>()
    private val phoneInfoLock = Any()

    suspend fun requestControl(
        command: JSONObject,
        responseAction: String,
        nodeId: String? = null,
        timeoutMillis: Long = 15_000,
    ): JSONObject {
        if (!controlConnected || _clientState.value.userId == null) {
            throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("not_authenticated")
        }
        val phoneInfo = responseAction == "getsysinfo" && nodeId != null &&
            _clientState.value.devices.any { it.nodeId == nodeId && it.isAndroidAgent }
        val revision = synchronized(phoneInfoLock) {
            if (phoneInfo) phoneInfoRevision.incrementAndGet().also { phoneInfoRequests[nodeId!!] = it } else 0L
        }
        val generation = sessionGeneration.get()
        val reply = controlRequests.request(command, responseAction, nodeId, timeoutMillis)
        synchronized(phoneInfoLock) {
            if (phoneInfo && controlConnected && generation == sessionGeneration.get() && phoneInfoRequests[nodeId] == revision) {
                updatePhoneInput(nodeId!!, PhoneRemoteInput.fromSystemInfo(reply))
            }
        }
        return reply
    }

    private fun updatePhoneInput(nodeId: String, capability: PhoneRemoteInput) {
        _clientState.update { state -> state.copy(devices = state.devices.map {
            if (it.nodeId == nodeId && it.isAndroidAgent) it.copy(remoteInput = capability) else it
        }) }
        syncPhoneDesktop()
    }

    private fun refreshPhoneInput(nodeId: String) {
        if (!_clientState.value.devices.any { it.nodeId == nodeId && it.isAndroidAgent }) return
        synchronized(phoneInfoLock) {
            phoneInfoRequests[nodeId] = phoneInfoRevision.incrementAndGet()
            updatePhoneInput(nodeId, PhoneRemoteInput.Unknown)
        }
        scope.launch {
            try {
                requestControl(JSONObject().put("action", "getsysinfo").put("nodeid", nodeId), "getsysinfo", nodeId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unknown stays view-only. Never infer support from a version or cached UI.
            }
        }
    }

    private fun syncPhoneDesktop() {
        val target = desktopState.value.device?.takeIf { it.isAndroidAgent } ?: return
        val state = _clientState.value
        relay.updatePhoneDevice(state.devices.firstOrNull { it.nodeId == target.nodeId }
            ?.takeIf { controlConnected && state.devicesCurrent })
    }

    private var clipboardRequest: ClipboardRequest? = null

    /** No server acknowledgement exists for uninstallagent in 1.3.1. Never imply completion. */
    fun requestAgentUninstall(nodeId: String, typedName: String): Boolean {
        val state = _clientState.value
        val target = state.devices.firstOrNull { it.nodeId == nodeId } ?: return false
        if (!controlConnected || state.userId == null || state.requiresSignIn) return false
        val command = ru.papajon.meshcentralmobile.management.DeviceRemovalCommand.create(
            target, state.socketConnected && state.devicesCurrent,
            ru.papajon.meshcentralmobile.management.DeviceRemovalAction.UninstallAgent, true, typedName)
        return sendControlJson(command)
    }

    /** Queue one audited phone command. True means sent locally, never executed remotely. */
    fun sendPhoneCommand(nodeId: String, request: PhoneRequest): Boolean {
        val state = _clientState.value
        val target = state.devices.firstOrNull { it.nodeId == nodeId } ?: return false
        if (!controlConnected || state.requiresSignIn || state.userId == null ||
            !PhoneCommands.canSend(target, state.devicesCurrent, request.action)) return false
        return sendControlJson(PhoneCommands.message(nodeId, request))
    }
    private var clipboardTimeoutJob: Job? = null

    private val relay = MeshDesktopRelay(
        okHttpClient = socketFactory,
        scope = scope,
        sendControlMessage = ::sendControlJson,
        onCertificateFailure = ::reportRelayCertificateFailure,
        phoneInputAllowed = { device ->
            val state = _clientState.value
            controlConnected && state.devicesCurrent && !state.requiresSignIn &&
                state.devices.firstOrNull { it.nodeId == device.nodeId }?.phoneInputAllowed == true
        },
    )

    private val _clientState = MutableStateFlow(MeshClientState())
    val clientState: StateFlow<MeshClientState> = _clientState
    val certificateTrustRequired: Boolean get() = certificateRejected
    val desktopState: StateFlow<RemoteDesktopState> = relay.desktopState

    private val _clipboardState = MutableStateFlow<ClipboardTransfer?>(null)
    val clipboardState: StateFlow<ClipboardTransfer?> = _clipboardState

    val remoteInputSink: NativeRemoteInputSink = this

    private val phoneDesktopSyncJob = scope.launch {
        clientState.collect { syncPhoneDesktop() }
    }

    /** Existing debug/test override only. Release builds always use platform trust or an explicit pin. */
    fun setAllowSelfSignedCertificates(enabled: Boolean) {
        if (!BuildConfig.DEBUG || allowSelfSignedCertificates == enabled) return
        // Startup restores this flag before the saved pin. Clear only an already active policy;
        // explicit preference changes also clear through SettingsRepository.
        if (sessionUsername != null) forgetRememberedDevice()
        allowSelfSignedCertificates = enabled
        invalidateTlsConnections()
        replaceTlsTransport()
    }

    fun setPinnedServerKey(spkiSha256: String?) {
        require(spkiSha256 == null || spkiSha256.matches(Regex("[0-9a-f]{64}")))
        require(spkiSha256 == null || baseUrl != null) { "Configure the endpoint before its key" }
        if (pinnedSpkiSha256 == spkiSha256) return
        pinnedSpkiSha256 = spkiSha256
        invalidateTlsConnections()
        replaceTlsTransport()
        secureVault.rememberedDevice.bindTrust(tlsTransport.rememberedTrust)
        _clientState.update { it.copy(rememberedDeviceExpiresAt = null) }
    }

    /** Explicit confirmation reuses only the current attempt's in-memory credentials. */
    fun retryAfterCertificateTrust() {
        val failure = _clientState.value.certificateFailure ?: return
        if (!failure.canApprove) return
        if (pinnedSpkiSha256 != failure.spkiSha256 || baseUrl?.toString() != failure.serverAddress) return
        login(baseUrl?.toString() ?: return, sessionUsername ?: return, sessionPassword ?: return, sessionToken, rememberDeviceRequested)
    }

    fun dismissCertificateFailure() {
        // Keep certificateRejected set: dismissing a dialog is never a network retry.
        forgetRememberedDevice()
        _clientState.update { it.copy(certificateFailure = null) }
    }

    private fun invalidateTlsConnections() {
        disconnectControl(manual = true)
        relay.disconnect()
        pendingDesktopResume = false
        cookieJar.clear()
        authCookie = null
        relayCookie = null
        relay.updateRelayCredentials(null, null)
        transportSockets.toList().forEach { it.cancel() }
        transportSockets.clear()
        _clientState.update {
            it.copy(connectionState = ConnectionState.Offline, userId = null,
                requiresSignIn = true, socketConnected = false, devicesCurrent = false)
        }
    }

    private fun replaceTlsTransport() {
        val previous = okHttpClient
        transportSockets.toList().forEach { it.cancel() }
        transportSockets.clear()
        tlsTransport = createTlsTransport()
        disposeTransport(previous)
    }

    private fun disposeTransport(transport: OkHttpClient) {
        transport.dispatcher.cancelAll()
        // Conscrypt may write TLS close_notify while evicting an idle connection.
        // It must not run on the UI thread, including after the owning scope is cancelled.
        scope.launch(Dispatchers.IO + NonCancellable) { transport.connectionPool.evictAll() }
        transport.dispatcher.executorService.shutdown()
    }

    private fun rejectCertificate(failure: ServerCertificateFailure) {
        forgetRememberedDevice()
        certificateRejected = true
        invalidateTlsConnections()
        val reason = MeshConnectionError.describeCertificate(failure)
        _clientState.update {
            it.copy(connectionState = ConnectionState.Failed, certificateFailure = failure,
                twoFactorChallenge = null, lastError = reason, message = reason)
        }
    }

    /** Relay listeners call this only after checking their own current socket/session identity. */
    private fun reportRelayCertificateFailure(failure: ServerCertificateFailure) {
        val transport = okHttpClient
        val generation = sessionGeneration.get()
        scope.launch {
            if (transport === okHttpClient && generation == sessionGeneration.get() && !manualClose) {
                rejectCertificate(failure)
            }
        }
    }

    fun configureServer(serverUrl: String) {
        val parsed = serverUrl.toHttpUrlOrNullNormalized() ?: return
        if (baseUrl == parsed) return
        secureVault.rememberedDevice.bind(parsed.toString())
        // A different endpoint cannot inherit an earlier endpoint's trust decision.
        if (baseUrl != null) {
            pinnedSpkiSha256 = null
            forgetRememberedDevice()
        }
        disconnectControl(manual = true)
        relay.disconnect()
        cookieJar.clear()
        sessionUsername = null
        sessionPassword = null
        sessionToken = null
        rememberCookieDays = 30
        authCookie = null
        relayCookie = null
        relay.updateRelayCredentials(null, null)
        isSiteAdmin = false
        userRemoveRights = null
        userGroupIds = emptySet()
        baseUrl = parsed
        certificateRejected = false
        replaceTlsTransport()
        _clientState.value = MeshClientState(
            connectionState = ConnectionState.Offline,
            message = UiText.Resource(R.string.runtime_server_saved),
            rememberedDeviceExpiresAt = secureVault.getCredentials(parsed.toString())?.let {
                secureVault.rememberedDevice.get(parsed.toString(), it.username)?.expiresAt
            },
        )
    }

    /** Signs in with `x-meshauth`. [token] carries a two-factor code, or a `**email**` request. */
    fun login(serverUrl: String, username: String, password: String, token: String? = null, rememberDevice: Boolean = true) {
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

        if (baseUrl != parsed) configureServer(serverUrl)
        secureVault.rememberedDevice.bind(parsed.toString(), username.trim())
        secureVault.rememberedDevice.bindTrust(tlsTransport.rememberedTrust)
        closeAdditionalRelays()
        if (baseUrl != parsed || sessionUsername != username.trim()) {
            // Also compare saved credentials after process restart, before the in-memory user exists.
            val previousUser = sessionUsername ?: secureVault.getCredentials(parsed.toString())?.username
            if (previousUser != null && previousUser != username.trim()) forgetRememberedDevice()
            rememberCookieDays = 30
            relay.disconnect()
            isSiteAdmin = false
            userRemoveRights = null
            userGroupIds = emptySet()
            _clientState.value = MeshClientState()
        }
        manualClose = false
        authRejected = false
        certificateRejected = false
        controlRetry.reset()
        baseUrl = parsed
        sessionUsername = username.trim()
        sessionPassword = password
        sessionToken = token?.takeIf { it.isNotBlank() }
        rememberDeviceRequested = rememberDevice && MeshAuth.isVerificationCode(sessionToken) && rememberCookieDays > 0
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
                certificateFailure = null,
                rememberedDeviceExpiresAt = secureVault.rememberedDevice.get(parsed.toString(), username.trim())?.expiresAt,
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

    fun submitTwoFactorCode(code: String, rememberDevice: Boolean = true) {
        val username = sessionUsername ?: return
        val password = sessionPassword ?: return
        login(baseUrl?.toString() ?: return, username, password, code, rememberDevice)
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
        if (sessionUsername == null || sessionPassword == null || authRejected || certificateRejected) return
        controlRetry.reset()
        manualClose = false
        openControlSocket()
    }

    fun onNetworkAvailable() {
        networkAvailable = true
        relay.onNetworkAvailable()
        // A real network event may supersede an in-flight connect on the old network. It must
        // never reset the server refusal budget or resubmit a rejected password/one-time code.
        if (waitingForNetwork && !manualClose && !authRejected && !certificateRejected && !controlRetry.exhausted) {
            openControlSocket()
        }
    }

    fun onNetworkLost() {
        networkAvailable = false
        relay.onNetworkLost()
        if (manualClose || authRejected || certificateRejected || controlRetry.exhausted || sessionUsername == null) return
        val previous = controlSocket
        disconnectControl(manual = false)
        previous?.cancel()
        waitForNetwork()
    }

    private fun waitForNetwork() {
        waitingForNetwork = true
        _clientState.update { it.copy(connectionState = ConnectionState.Reconnecting,
            socketConnected = false, devicesCurrent = false,
            message = UiText.Resource(R.string.reliability_waiting_network)) }
    }

    fun onAppBackground() {
        appForeground = false
        relay.setPaused(true)
    }

    fun onAppForeground() {
        appForeground = true
        if (networkAvailable && waitingForNetwork) onNetworkAvailable()
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
        val target = _clientState.value.devices.firstOrNull { it.nodeId == device.nodeId } ?: device
        relay.connect(url, if (target.isAndroidAgent) target.copy(remoteInput = PhoneRemoteInput.Unknown) else target)
        if (target.isAndroidAgent) refreshPhoneInput(target.nodeId)
        pendingDesktopResume = false
        _clientState.update {
            it.copy(
                connectionState = ConnectionState.LoadingRemoteDesktop,
                message = UiText.resource(R.string.runtime_opening_device, device.name),
            )
        }
    }

    /** Creates a single-use connection. Unknown metadata leaves the decision to the server. */
    fun createRelayConnection(device: MeshDevice, protocol: RelayProtocol, options: JSONObject? = null): RelayConnection {
        val generation = sessionGeneration.get()
        val server = baseUrl
        val connection = ManagedRelayConnection(
            protocol = protocol,
            nodeId = device.nodeId,
            socketFactory = socketFactory,
            scope = scope,
            credentials = {
                val cookie = authCookie
                if (server != null && !cookie.isNullOrBlank()) RelayCredentials(server, cookie, relayCookie) else null
            },
            validSession = {
                generation == sessionGeneration.get() && controlConnected &&
                    !_clientState.value.requiresSignIn && _clientState.value.devicesCurrent &&
                    _clientState.value.devices.any { it.nodeId == device.nodeId }
            },
            availability = {
                val state = _clientState.value
                state.devices.firstOrNull { it.nodeId == device.nodeId }
                    ?.sessionAvailability(protocol, state.serverCapabilities) ?: SessionAvailability.Unknown
            },
            sendControl = ::sendControlJson,
            onFinished = { additionalRelays.remove(it) },
            onCertificateFailure = ::reportRelayCertificateFailure,
            options = options,
            androidFileListings = device.isAndroidAgent && protocol == RelayProtocol.AgentFiles,
        )
        additionalRelays.add(connection)
        // Also covers a concurrent logout during creation; the stale object can never connect.
        if (generation != sessionGeneration.get()) connection.close()
        return if (device.isAndroidAgent && protocol == RelayProtocol.AgentFiles) {
            ru.papajon.meshcentralmobile.mesh.files.AndroidFilesConnection(connection, scope) { path ->
                createRelayConnection(device, RelayProtocol.AgentFileDownload, JSONObject().put("file", path))
            }
        } else connection
    }

    private fun closeAdditionalRelays() {
        // Invalidate before walking the registry so a concurrent creation cannot start in the gap.
        controlConnected = false
        sessionGeneration.incrementAndGet()
        additionalRelays.toList().forEach { it.close() }
    }

    fun reconnectDesktop() {
        desktopState.value.device?.takeIf { it.isAndroidAgent }?.let { refreshPhoneInput(it.nodeId) }
        relay.reconnect()
    }

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

    fun logout(clearStoredCredentials: Boolean, clearRememberedDevice: Boolean = true) {
        rememberDeviceRequested = false
        awaitingRememberCookie = false
        if (clearRememberedDevice) forgetRememberedDevice()
        isSiteAdmin = false
        userRemoveRights = null
        userGroupIds = emptySet()
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

    /** Forget locally without ending an accepted session; a pending reply must not restore it. */
    fun forgetRememberedDevice() {
        rememberDeviceRequested = false
        awaitingRememberCookie = false
        secureVault.rememberedDevice.clear()
        _clientState.update { it.copy(rememberedDeviceExpiresAt = null) }
    }

    override fun close() {
        phoneDesktopSyncJob.cancel()
        relay.disconnect()
        disconnectControl(manual = true)
        transportSockets.toList().forEach { it.cancel() }
        transportSockets.clear()
        disposeTransport(okHttpClient)
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
        if (!networkAvailable) { waitForNetwork(); return }
        waitingForNetwork = false
        _clientState.update {
            it.copy(connectionState = ConnectionState.Connecting, message = UiText.Resource(R.string.runtime_opening_control_channel))
        }
        val request = Request.Builder()
            .url(MeshControlEndpoint.url(url, secureVault.getLoginKey(url.toString())))
            .header(HEADER_MESH_AUTH, MeshAuth.headerValue(username, password, sessionToken))
            .tag(ControlCookieScope::class.java, ControlCookieScope(url.toString(), username))
            .build()
        controlSocket = socketFactory.newWebSocket(request, controlListenerForCurrentSession())
    }

    /** Control callbacks and profile/trust changes share the owner's Main scope. A late TLS result
     * must not authenticate, persist credentials or display a failure in a replacement session. */
    private fun controlListenerForCurrentSession(): WebSocketListener {
        val generation = sessionGeneration.get()
        val transport = okHttpClient
        val listener = ControlListener(tlsTransport.rememberedTrust)
        fun dispatch(action: () -> Unit) {
            scope.launch {
                if (generation == sessionGeneration.get() && transport === okHttpClient) action()
            }
        }
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch { listener.onOpen(webSocket, response) }
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch { listener.onMessage(webSocket, text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch { listener.onFailure(webSocket, t, response) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch { listener.onClosing(webSocket, code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch { listener.onClosed(webSocket, code, reason) }
        }
    }

    private fun disconnectControl(manual: Boolean) {
        awaitingRememberCookie = false
        controlRequests.disconnected()
        closeAdditionalRelays()
        relay.onControlLost()
        if (manual) manualClose = true
        synchronized(phoneInfoLock) {
            controlConnected = false
            _clientState.update { it.copy(socketConnected = false, devicesCurrent = false) }
            phoneInfoRequests.clear()
            syncPhoneDesktop()
        }
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

    private fun reconnectControl(@StringRes reasonRes: Int, networkFailure: Boolean = false) {
        if (manualClose || authRejected || certificateRejected) return
        val backoff = controlRetry.nextDelay(networkFailure)
        if (backoff == null) {
            waitingForNetwork = false
            _clientState.update {
                it.copy(
                    connectionState = ConnectionState.Failed,
                    message = UiText.Resource(R.string.runtime_reconnect_exhausted),
                )
            }
            return
        }
        waitingForNetwork = networkFailure
        _clientState.update {
            it.copy(
                connectionState = ConnectionState.Reconnecting,
                message = if (networkFailure) UiText.Resource(R.string.reliability_waiting_network) else UiText.resource(
                    reasonRes,
                    backoff / 1000,
                    controlRetry.serverRetries,
                    ConnectionRetryPolicy.MAX_SERVER_RETRIES,
                ),
            )
        }
        controlReconnectJob?.cancel()
        // No polling when Android reports no default network. DNS/connect failures on an
        // available network still get bounded backoff, since no new callback may arrive.
        if (!networkAvailable) { controlReconnectJob = null; return }
        controlReconnectJob = scope.launch {
            delay(backoff)
            if (manualClose || authRejected || certificateRejected) return@launch
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
            userGroupIds = userGroupIds,
            manageAllDeviceGroups = _clientState.value.serverCapabilities.manageAllDeviceGroups,
            removeRights = userRemoveRights,
        )
        return resolved.sortedWith(
            compareBy<MeshDevice> { !it.online }
                .thenBy { it.meshName.lowercase(Locale.US) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
    }

    private var isSiteAdmin = false
    private var userRemoveRights: Int? = null
    private var userGroupIds: Set<String> = emptySet()

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
            host = incoming.host ?: existing.host,
            sshPort = incoming.sshPort ?: existing.sshPort,
            rightsByUser = incoming.rightsByUser,
            relayNodeId = incoming.relayNodeId ?: existing.relayNodeId,
            conn = if (incoming.connectivityKnown) incoming.conn else existing.conn,
            connectivityKnown = incoming.connectivityKnown || existing.connectivityKnown,
            battery = when {
                incoming.connectivityKnown && incoming.conn and 1 == 0 -> null
                incoming.sessionsKnown -> incoming.battery
                else -> existing.battery
            },
            sessionsKnown = incoming.sessionsKnown || existing.sessionsKnown,
            windowsSecurity = existing.windowsSecurity.updatedBy(incoming.windowsSecurity),
            lastBootMillis = if (incoming.lastBootKnown) incoming.lastBootMillis else existing.lastBootMillis,
            lastBootKnown = incoming.lastBootKnown || existing.lastBootKnown,
            remoteInput = existing.remoteInput.takeUnless { incoming.connectivityKnown && incoming.conn and 1 == 0 }
                ?: PhoneRemoteInput.Unknown,
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
            userGroupIds = userGroupIds,
            manageAllDeviceGroups = _clientState.value.serverCapabilities.manageAllDeviceGroups,
            removeRights = userRemoveRights,
        ).map { device ->
            device.copy(meshName = namesById[device.meshId]?.name ?: device.meshName)
        }.sortedWith(
            compareBy<MeshDevice> { !it.online }
                .thenBy { it.meshName.lowercase(Locale.US) }
                .thenBy { it.name.lowercase(Locale.US) },
        )
        _clientState.update { it.copy(groups = named, devices = devices) }
        syncPhoneDesktop()
    }

    private fun handleNodeEvent(event: MeshNodeEvent) = synchronized(phoneInfoLock) {
        when (event) {
            is MeshNodeEvent.DeviceSessions -> _clientState.update { state ->
                state.copy(devices = state.devices.map { device ->
                    if (device.nodeId == event.nodeId) device.copy(
                        battery = event.battery.takeUnless { device.connectivityKnown && device.conn and 1 == 0 },
                        sessionsKnown = true,
                    ) else device
                })
            }
            is MeshNodeEvent.NodeConnected -> {
                val devices = _clientState.value.devices.map { device ->
                    if (device.nodeId == event.nodeId) device.copy(conn = event.conn, pwr = event.pwr, connectivityKnown = true,
                        remoteInput = device.remoteInput.takeIf { device.conn and 1 != 0 && event.conn and 1 != 0 }
                            ?: PhoneRemoteInput.Unknown,
                        battery = device.battery.takeIf { event.conn and 1 != 0 }) else device
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
        val invalidated = when (event) {
            is MeshNodeEvent.NodeConnected -> event.nodeId
            is MeshNodeEvent.NodeRemoved -> event.nodeId
            is MeshNodeEvent.NodeChanged -> event.device.nodeId.takeIf { event.device.connectivityKnown && event.device.conn and 1 == 0 }
            else -> null
        }
        if (invalidated != null) {
            phoneInfoRequests.remove(invalidated)
            updatePhoneInput(invalidated, PhoneRemoteInput.Unknown)
            if (event is MeshNodeEvent.NodeConnected && event.conn and 1 != 0 &&
                desktopState.value.device?.nodeId == invalidated) refreshPhoneInput(invalidated)
        }
        syncPhoneDesktop()
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
                userGroupIds = userGroupIds,
                manageAllDeviceGroups = state.serverCapabilities.manageAllDeviceGroups,
                removeRights = userRemoveRights,
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

    private inner class ControlListener(private val rememberedTrust: RememberedDeviceTrust?) : WebSocketListener() {
        private var opened = false
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (controlSocket !== webSocket) return
            opened = true
            controlConnected = true
            // MeshCentral checks credentials only after the upgrade, so an open socket does not
            // restore the retry budget; `userinfo` does.
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
            val frame = ru.papajon.meshcentralmobile.mesh.control.ControlFrameDecoder.decode(text) ?: return
            val change = frame.takeIf { it.optText("action") == "event" }?.optJSONObject("event")
            if (change?.optText("action") == "sysinfohash") refreshPhoneInput(change.optText("nodeid"))
            val consumed = controlRequests.accept(frame)
            when (val event = MeshControlParser.parse(frame, text.length)) {
                is ServerInfo -> {
                    _clientState.update { it.copy(serverName = event.name ?: it.serverName, serverCapabilities = event.capabilities) }
                    applyGroups(_clientState.value.groups)
                }

                is UserInfo -> {
                    controlRetry.reset()
                    waitingForNetwork = false
                    isSiteAdmin = event.siteAdmin
                    userRemoveRights = event.removeRights
                    userGroupIds = event.userGroupIds
                    _clientState.update { state ->
                        state.copy(
                            userId = event.userId ?: state.userId,
                            requiresSignIn = false,
                            serverName = state.serverName ?: event.name,
                        )
                    }
                    applyGroups(_clientState.value.groups)
                    // Credentials are only persisted once the server accepted them.
                    sessionUsername?.let { user ->
                        sessionPassword?.let { pass ->
                            baseUrl?.let { server -> secureVault.saveCredentials(user, pass, server.toString()) }
                        }
                    }
                    if (rememberDeviceRequested) {
                        rememberDeviceRequested = false
                        if (rememberedTrust != null && MeshAuth.isVerificationCode(sessionToken)) {
                            awaitingRememberCookie = sendControlJson(MeshControlMessages.twoFactorCookie())
                        }
                    }
                    // A one-time code must never be reused by a later automatic reconnect.
                    sessionToken = null
                    sendControlJson(MeshControlMessages.meshes())
                    sendControlJson(MeshControlMessages.nodes(responseId = "android-nodes"))
                }

                is Meshes -> applyGroups(event.groups)

                is Nodes -> synchronized(phoneInfoLock) {
                    phoneInfoRequests.clear()
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
                    syncPhoneDesktop()
                    desktopState.value.device?.takeIf { it.isAndroidAgent }?.let { refreshPhoneInput(it.nodeId) }
                }

                is AuthCookie -> {
                    authCookie = event.cookie
                    relayCookie = event.relayCookie
                    relay.updateRelayCredentials(event.cookie, event.relayCookie)
                    relay.onControlAvailable()
                    scheduleAuthCookieRefresh()
                    if (appForeground && pendingDesktopResume && controlConnected) {
                        pendingDesktopResume = false
                        relay.resumeAfterBackground(controlReady = true)
                    }
                }

                is MeshControlEvent.TwoFactorCookie -> {
                    if (awaitingRememberCookie && !_clientState.value.requiresSignIn) {
                        awaitingRememberCookie = false
                        val server = baseUrl?.toString()
                        val user = sessionUsername
                        if (server != null && user != null && event.cookie != null && rememberedTrust != null &&
                            secureVault.rememberedDevice.save(server, user, event.cookie, rememberCookieDays, rememberedTrust)) {
                            _clientState.update { it.copy(
                                rememberedDeviceExpiresAt = secureVault.rememberedDevice.get(server, user)?.expiresAt,
                            ) }
                        }
                    }
                }

                is NodeUpdated -> handleNodeEvent(event.event)

                is ClipMessage -> handleClipboardMessage(event)

                is RoutingResult -> {
                    if (!consumed && event.result != null && !event.result.equals("OK", ignoreCase = true)) {
                        additionalRelays.firstOrNull { it.responseId == event.responseId }?.routingFailed()
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
            t.serverCertificateFailure()?.let { rejectCertificate(it); return }
            controlRequests.disconnected()
            closeAdditionalRelays()
            if (relay.hasActiveSession()) pendingDesktopResume = true
            controlConnected = false
            Log.w(TAG, "Control socket failed: ${t.javaClass.simpleName}")
            // The server already gave its close reason: a broken transport afterwards must neither
            // replace that reason nor retry the rejected credentials.
            if (authRejected) {
                _clientState.update { it.copy(socketConnected = false, devicesCurrent = false) }
                return
            }
            val reason = MeshConnectionError.describe(t, response?.code)
            authRejected = response?.code == 401 || response?.code == 403
            if (authRejected) forgetRememberedDevice()
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
            reconnectControl(R.string.runtime_control_failure_reconnecting,
                ConnectionRetryPolicy.isNetworkFailure(t, response?.code, opened, networkAvailable))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // MeshCentral's `ws.close()` has no status code (reported as 1005), and OkHttp throws
            // when a reserved code is echoed. Answer with a normal closure instead.
            if (controlSocket === webSocket) webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (controlSocket !== webSocket) return
            controlRequests.disconnected()
            closeAdditionalRelays()
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
        closeAdditionalRelays()
        controlConnected = false
        val failure = MeshAuthDiagnostics.classify(event.cause, event.message)
        if (failure == MeshAuthFailure.BadCredentials || failure == MeshAuthFailure.TwoFactorRequired) {
            forgetRememberedDevice()
            sessionToken = null
        }
        authRejected = failure != MeshAuthFailure.Unknown || event.cause.equals("noauth", ignoreCase = true)
        val challenge = event.twoFactorChallenge
        if (challenge != null) rememberCookieDays = challenge.twoFactorCookieDays
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

    private data class ClipboardRequest(
        val kind: ClipboardKind,
        val nodeId: String,
        val tag: String?,
        val text: String? = null,
    )

    companion object {
        const val HEADER_MESH_AUTH = "x-meshauth"
        private const val TAG = "MeshCentralClient"

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
