package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.json.optText
import ru.papajon.meshcentralmobile.forward.canForwardPorts

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
import kotlinx.coroutines.flow.first
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
    private val singleAttempt: Boolean = false,
) : Closeable, NativeRemoteInputSink {
    private val cookieJar = MeshCookieJar()
    private class ControlCookieScope(val serverUrl: String, val username: String)
    private var baseUrl: HttpUrl? = null
    private var pinnedSpkiSha256: String? = null
    private var expectedSpkiSha256: String? = null
    val backgroundAccessRejected: Boolean get() = authRejected || certificateRejected
    val configuredServerAddress: String? get() = baseUrl?.toString()
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
        .retryOnConnectionFailure(!singleAttempt)
        .apply { ServerTls(baseUrl, pinnedSpkiSha256, BuildConfig.DEBUG && allowSelfSignedCertificates, expectedSpkiSha256 = expectedSpkiSha256).applyTo(this) }
        .build()

    /** Desktop and all tool/file relays resolve the current immutable TLS policy at connect time. */
    private val socketFactory = object : WebSocket.Factory {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val transport = tlsTransport
            val identity = request.tag(ControlCookieScope::class.java)
            // A queued request retains its own identity even if the user switches accounts before
            // OkHttp asks for cookies. Relays have no control identity tag and use the ordinary jar.
            val requestTransport = transportFor(request, transport.client) {
                if (identity == null) transport.client else transport.client.newBuilder()
                .cookieJar(cookieJar.forControlRequest { url ->
                    secureVault.rememberedDevice.cookieForRequest(identity.serverUrl, identity.username, url,
                        BuildConfig.DEBUG, transport.rememberedTrust)
                }).build()
            }
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
    @Volatile private var authRejected = false
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
    private val chatIdentityGeneration = AtomicLong()
    @Volatile private var chatCookieGeneration = -1L
    @Volatile private var chatControlNeedsRefresh = false
    private val chats = ConcurrentHashMap.newKeySet<ru.papajon.meshcentralmobile.mesh.chat.ChatController>()
    private val chatDeviceIds = ConcurrentHashMap<ru.papajon.meshcentralmobile.mesh.chat.ChatController, String>()
    private val _controlFailures = kotlinx.coroutines.flow.MutableSharedFlow<ru.papajon.meshcentralmobile.mesh.control.ControlRequestFailure>(extraBufferCapacity = 64)
    val controlFailures: kotlinx.coroutines.flow.SharedFlow<ru.papajon.meshcentralmobile.mesh.control.ControlRequestFailure> = _controlFailures
    private val controlRequests = ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker(
        onLateFailure = { _controlFailures.tryEmit(it) }, send = ::sendControlJson)
    private val taskToolsBroker = ru.papajon.meshcentralmobile.management.tasks.TaskToolsBroker(send = ::sendControlJson)

    fun taskTools(nodeId: String): ru.papajon.meshcentralmobile.management.tasks.TaskToolsTransport =
        object : ru.papajon.meshcentralmobile.management.tasks.TaskToolsTransport {
            override val device get() = clientState.value.devices.firstOrNull { it.nodeId == nodeId }
            override val current get() = controlConnected && clientState.value.socketConnected && clientState.value.devicesCurrent
            override val features2 get() = clientState.value.serverCapabilities.features2
            override val generation get() = taskToolsBroker.generation(nodeId)
            override suspend fun read(type: String, pid: Long?, timeoutMillis: Long): JSONObject {
                val target = requireNotNull(device)
                check(ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.canRead(target, current))
                val command = JSONObject().put("action", "msg").put("type", type).put("nodeid", nodeId)
                if (pid != null) command.put("pid", pid)
                return taskToolsBroker.request(command, timeoutMillis)
            }
            override suspend fun serviceDetails(name: String): JSONObject {
                check(ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.canRead(requireNotNull(device), current))
                return taskToolsBroker.request(JSONObject().put("action", "msg").put("type", "service").put("nodeid", nodeId).put("serviceName", name))
            }
            override fun kill(process: ru.papajon.meshcentralmobile.management.tasks.AgentProcess, confirmed: Boolean): Boolean {
                if (!taskToolsBroker.certain(nodeId, "ps")) throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("uncertain_reply")
                return sendControlJson(ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.kill(requireNotNull(device), current, process, confirmed, features2))
            }
            override fun service(service: ru.papajon.meshcentralmobile.management.tasks.AgentService,
                action: ru.papajon.meshcentralmobile.management.tasks.TaskServiceAction, confirmed: Boolean): Boolean {
                if (!taskToolsBroker.certain(nodeId, "services")) throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("uncertain_reply")
                return sendControlJson(ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.service(requireNotNull(device), current, service, action, confirmed, features2))
            }
        }
    private val eventRefresh = ru.papajon.meshcentralmobile.mesh.control.CoalescingRefresh(scope) {
        requestControl(MeshControlMessages.meshes(), "meshes")
        if (!nodesTimedOut) {
            requestNodes(silent = true)
            kotlinx.coroutines.withTimeout(15_000) {
                while (synchronized(phoneInfoLock) { nodesPager.pending || nodesExternalBusy || deferredNodes != null }) delay(20)
            }
        }
    }
    internal val eventSnapshotRefreshCount: Int get() = eventRefresh.executions
    internal val eventSnapshotRefreshIdle: Boolean get() = eventRefresh.idle
    private val phoneInfoRevision = AtomicLong()
    private val phoneInfoRequests = ConcurrentHashMap<String, Long>()
    private val phoneInfoLock = Any()

    suspend fun requestControl(
        command: JSONObject,
        responseAction: String,
        nodeId: String? = null,
        timeoutMillis: Long = 15_000,
        isolatedSnapshot: Boolean = false,
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
        val reply = if (command.optString("action") == "nodes") {
            requestNodesControl(command, timeoutMillis, isolatedSnapshot)
        } else controlRequests.request(command, responseAction, nodeId, timeoutMillis, isolatedSnapshot)
        synchronized(phoneInfoLock) {
            if (phoneInfo && controlConnected && generation == sessionGeneration.get() && phoneInfoRequests[nodeId] == revision) {
                updatePhoneInput(nodeId!!, PhoneRemoteInput.fromSystemInfo(reply))
            }
        }
        return reply
    }

    suspend fun createDeviceInvitation(groupId: String, hours: Int): ru.papajon.meshcentralmobile.management.DeviceInvitation {
        val state = _clientState.value
        val group = state.groups.first { it.id == groupId }
        val server = requireNotNull(baseUrl)
        val started = System.currentTimeMillis()
        val command = ru.papajon.meshcentralmobile.management.DeviceAdmin.invite(group, state, hours)
        val reply = requestControl(command, "createInviteLink")
        return ru.papajon.meshcentralmobile.management.DeviceAdmin.parseInvite(reply, groupId, hours, server, started, secureVault.getLoginKey(server.toString()), state.serverCapabilities)
    }

    private fun adminDevice(nodeId: String) = _clientState.value.devices.first { it.nodeId == nodeId }
    private fun adminCurrent() = _clientState.value.socketConnected && _clientState.value.devicesCurrent

    suspend fun editDevice(nodeId: String, name: String, description: String, tags: String, original: MeshDevice = adminDevice(nodeId)) {
        val command = ru.papajon.meshcentralmobile.management.DeviceAdmin.edit(adminDevice(nodeId), adminCurrent(), name, description, tags, true, original)
        if (command.length() == 2) return
        ru.papajon.meshcentralmobile.management.DeviceAdmin.requireSuccess(requestControl(command, "changedevice", nodeId))
        verifyAdminDevice(nodeId) { ru.papajon.meshcentralmobile.management.DeviceAdmin.editMatches(command, it) }
    }

    suspend fun moveDevice(nodeId: String, groupId: String) {
        val group = _clientState.value.groups.first { it.id == groupId }
        val device = adminDevice(nodeId)
        val source = _clientState.value.groups.firstOrNull { it.id == device.meshId }
        val command = ru.papajon.meshcentralmobile.management.DeviceAdmin.move(device, source, group, adminCurrent(), true)
        ru.papajon.meshcentralmobile.management.DeviceAdmin.requireSuccess(requestControl(command, "changeDeviceMesh", nodeId))
        verifyAdminDevice(nodeId) { it.meshId == groupId }
    }

    private suspend fun verifyAdminDevice(nodeId: String, matches: (MeshDevice) -> Boolean) {
        val observed = resolveDevice(nodeId, refresh = true)
        if (observed != null && matches(observed)) return
        error("Change not observed")
    }

    suspend fun readDeviceNotes(nodeId: String): String {
        require(adminCurrent())
        adminDevice(nodeId)
        return ru.papajon.meshcentralmobile.management.DeviceAdmin.parseNotes(
            requestControl(ru.papajon.meshcentralmobile.management.DeviceAdmin.readNotes(nodeId), "getNotes", nodeId), nodeId)
    }

    suspend fun saveDeviceNotes(nodeId: String, notes: String) {
        val command = ru.papajon.meshcentralmobile.management.DeviceAdmin.notes(adminDevice(nodeId), adminCurrent(), notes, true)
        check(sendControlJson(command))
        repeat(5) {
            kotlinx.coroutines.delay(200)
            if (readDeviceNotes(nodeId) == notes) return
        }
        error("Notes not observed")
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
    private val nodesPager = NodesPager()
    private var nodesTimeout: Job? = null
    private var nodesRefreshJob: Job? = null
    private val membershipChecks = MembershipChecks()
    private var membershipWorker: Job? = null
    private val deviceReadRevisions = DeviceReadRevisions()
    internal val pendingMembershipChecks get() = membershipChecks.size
    internal val trackedDeviceReads get() = deviceReadRevisions.size
    private var deviceListPollJob: Job? = null
    private val nodesControlMutex = kotlinx.coroutines.sync.Mutex()
    private var nodesExternalBusy = false
    private data class DeferredNodes(val filter: String, val collect: Boolean, val skip: Int, val ids: Set<String>? = null) {
        val query get() = DeviceListQuery(filter, collect, ids)
    }
    private var deferredNodes: DeferredNodes? = null
    private var requestedNodes: DeferredNodes? = null
    private val nodesWatchdog = NodesWatchdog()
    private val nodesTimedOut get() = nodesWatchdog.timedOut
    private var deviceListVisible = false
    private var nodesSubscriptionDirty = false
    private var selectedDeviceId: String? = null
    private var selectionLookupJob: Job? = null

    fun selectDevice(nodeId: String?) {
        selectedDeviceId = nodeId
        selectionLookupJob?.cancel()
        selectionLookupJob = if (nodeId == null) null else scope.launch {
            clientState.first { it.socketConnected && it.devicesCurrent && it.userId != null }
            try { resolveDevice(nodeId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Card shows the missing state; the retained-device refresh can retry. */ }
        }
    }

    /** Addressed reads never replace list membership or change its query/offset. */
    suspend fun resolveDevice(nodeId: String, refresh: Boolean = false, timeoutMillis: Long = 15_000): MeshDevice? {
        val state = clientState.value
        if (!state.socketConnected || !state.devicesCurrent || state.userId == null) return null
        if (!refresh) state.devices.firstOrNull { it.nodeId == nodeId }?.let { return it }
        val generation = sessionGeneration.get()
        val revision = synchronized(phoneInfoLock) { deviceReadRevisions.begin(nodeId) }
        _clientState.update { it.copy(resolvingDeviceIds = it.resolvingDeviceIds + nodeId) }
        try {
            readAddressedDevice(nodeId, "", timeoutMillis) { device ->
                if (!controlConnected || generation != sessionGeneration.get())
                    throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("disconnected")
                if (!revision.invalidated) {
                    if (device == null) handleNodeEvent(MeshNodeEvent.NodeRemoved(nodeId))
                    else upsertDevice(device, addressed = true)
                }
            }
            return clientState.value.devices.firstOrNull { it.nodeId == nodeId }
        } finally {
            synchronized(phoneInfoLock) { deviceReadRevisions.end(revision) }
            if (generation == sessionGeneration.get()) _clientState.update { it.copy(resolvingDeviceIds = it.resolvingDeviceIds - nodeId) }
        }
    }

    /** MeshCentral also returns [] for a DB error: confirm absence with a second spaced read. */
    private suspend fun readAddressedDevice(nodeId: String, filter: String, timeoutMillis: Long = 15_000,
        stillWanted: () -> Boolean = { true }, onResult: (MeshDevice?) -> Unit) = kotlinx.coroutines.withTimeout(timeoutMillis) {
        val generation=sessionGeneration.get()
        repeat(2) { attempt ->
            var empty = false
            requestNodesControl(MeshControlMessages.nodes().put("id",nodeId).put("skip",0).put("filter",filter),
                timeoutMillis, isolatedSnapshot = true, expectedGeneration=generation, stillWanted = stillWanted) { frame ->
                val parsed = MeshControlParser.parse(frame) as? Nodes
                    ?: throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("invalid_nodes")
                if (parsed.result != null && !parsed.result.equals("ok",true))
                    throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("server_rejected")
                val device = parsed.devices.firstOrNull { it.nodeId == nodeId }
                empty = device == null
                if (device != null || attempt == 1) onResult(device)
            }
            if (!empty) return@withTimeout
            if (attempt == 0) delay(1_000)
        }
    }

    private fun retainedDeviceIds(): Set<String> = additionalRelays.map { it.nodeId }.toSet() +
        chatDeviceIds.values + listOfNotNull(selectedDeviceId, desktopState.value.device?.nodeId)
    fun setDeviceBrowserFilter(filter: DeviceBrowserFilter) { _clientState.update { it.copy(deviceBrowserFilter = filter) } }
    fun setDeviceBrowserSearch(query: String) { _clientState.update { it.copy(deviceBrowserSearch = query) } }
    internal val deviceBrowserSkip: Int get() = synchronized(phoneInfoLock) {
        requestedNodes?.skip ?: _clientState.value.devicePage.skip
    }
    fun restoreDeviceBrowser(filter: DeviceBrowserFilter, search: String?, query: DeviceListQuery, skip: Int) {
        _clientState.update { it.copy(deviceBrowserFilter=filter,deviceBrowserSearch=search) }
        val normalized=query.normalized()
        if (controlConnected) requestNodes(normalized.filter,normalized.collect,skip,ids=normalized.ids)
        else {
            requestedNodes=DeferredNodes(normalized.filter,normalized.collect,skip.coerceAtLeast(0),normalized.ids)
            _clientState.update { it.copy(deviceQuery=normalized) }
        }
    }
    private var externalNodes: PendingNodesRead? = null
    private var externalEventsResponseId: String? = null
    private var externalNodesSerial = 0L

    /** Inspector verification shares the socket's paging state, so it must join the same queue. */
    private suspend fun requestNodesControl(command: JSONObject, timeout: Long, isolatedSnapshot: Boolean = false,
        expectedGeneration: Long = sessionGeneration.get(),
        stillWanted: () -> Boolean = { true }, onReply: (JSONObject) -> Unit = {}): JSONObject = kotlinx.coroutines.withTimeout(timeout) {
        val generation = expectedGeneration
        val owner = coroutineContext[Job]
        nodesControlMutex.lock()
        var claimed = false
        var sent = false
        try {
            run {
                while (synchronized(phoneInfoLock) { nodesExternalBusy }) delay(20)
                synchronized(phoneInfoLock) {
                    if (generation != sessionGeneration.get()) throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("disconnected")
                    if (!stillWanted()) throw kotlinx.coroutines.CancellationException("superseded_nodes_read")
                    nodesExternalBusy = true; claimed = true
                }
                while (synchronized(phoneInfoLock) { nodesPager.pending }) delay(20)
                val pending = synchronized(phoneInfoLock) {
                    if (!controlConnected || generation != sessionGeneration.get()) throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("disconnected")
                    if (!stillWanted()) throw kotlinx.coroutines.CancellationException("superseded_nodes_read")
                    val prefix = if (isolatedSnapshot) "android-snapshot-rpc-" else "android-node-read-"
                    val pending = PendingNodesRead("$prefix${++externalNodesSerial}") { frame ->
                        if (owner?.isActive == true && stillWanted()) onReply(frame)
                    }
                    externalNodes = pending
                    val payload = JSONObject(command.toString()).put("responseid", pending.id)
                    if (!sendControlJson(payload)) {
                        externalNodes = null
                        throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("send_failed")
                    }
                    sent = true
                    nodesSubscriptionDirty = true
                    scheduleNodesTimeout()
                    pending
                }
                pending.answer.await()
            }
        } finally {
            // Cancelling the caller does not cancel MeshCentral's DB callback. Keep the wire slot
            // occupied until its response arrives (or the socket closes), including after timeout.
            if (claimed && !sent) synchronized(phoneInfoLock) {
                if (generation == sessionGeneration.get()) releaseNodesControl()
            }
            nodesControlMutex.unlock()
        }
    }

    private fun releaseNodesControl() {
        nodesExternalBusy = false
        val next = deferredNodes
        deferredNodes = null
        if (next != null) requestNodes(next.filter, next.collect, next.skip, ids = next.ids)
    }

    private fun acceptNodesControl(frame: JSONObject): Boolean = synchronized(phoneInfoLock) {
        val pending = externalNodes ?: return@synchronized false
        if (frame.optString("action") != "nodes" || frame.optString("responseid") != (externalEventsResponseId ?: pending.id)) return@synchronized false
        if (externalEventsResponseId == null) pending.received(frame)
        if (externalEventsResponseId == null && frame.optInt("limit",clientState.value.devicePage.limit ?: 0) == 1 && clientState.value.devicePage.completeSnapshot) {
            // Apply the response now; only the wire slot waits for restoring global events.
            // With limit>1 an addressed first page already resets visibleDevices on the server.
            externalEventsResponseId = pending.id + "-events"
            if (sendControlJson(MeshControlMessages.nodes(externalEventsResponseId, 0, null, "")
                    .put("id", "android-events-${java.util.UUID.randomUUID()}"))) {
                scheduleNodesTimeout()
                return@synchronized true
            }
        }
        val resetFailed = externalEventsResponseId != null && frame.optString("responseid") != externalEventsResponseId ||
            externalEventsResponseId != null && frame.has("result") && !frame.optString("result").equals("ok", true)
        externalNodes = null
        externalEventsResponseId = null
        nodesTimeout?.cancel()
        nodesWatchdog.complete()
        if (resetFailed) {
            _clientState.update { it.copy(lastError = UiText.Resource(R.string.devices_page_failed)) }
        }
        releaseNodesControl()
        true
    }
    val clientState: StateFlow<MeshClientState> = _clientState
    val certificateTrustRequired: Boolean get() = certificateRejected
    val desktopState: StateFlow<RemoteDesktopState> = relay.desktopState

    val portForwards = ru.papajon.meshcentralmobile.forward.PortForwardManager(
        allowed = { rule -> canForward(rule.nodeId) },
        connect = { rule, local, onBytes, onFinished ->
            val generation = sessionGeneration.get()
            val server = requireNotNull(baseUrl)
            val cookie = requireNotNull(authCookie)
            ru.papajon.meshcentralmobile.forward.TcpRelayBridge(
                local, socketFactory,
                Request.Builder().url(ru.papajon.meshcentralmobile.forward.tcpRelayUrl(server, rule, cookie))
                    .tag(ru.papajon.meshcentralmobile.forward.RawTcpTransport::class.java, ru.papajon.meshcentralmobile.forward.RawTcpTransport).build(),
                scope, { generation == sessionGeneration.get() && canForward(rule.nodeId) }, onBytes, onFinished,
            )
        },
    )

    fun canForward(nodeId: String): Boolean {
        val state = _clientState.value
        return controlConnected && !certificateRejected && !state.requiresSignIn && state.devicesCurrent &&
            !authCookie.isNullOrBlank() && state.devices.any {
                it.nodeId == nodeId && it.canForwardPorts()
            }
    }

    private val forwardSessionWatch = scope.launch { clientState.collect { portForwards.validateSession() } }

    private val _clipboardState = MutableStateFlow<ClipboardTransfer?>(null)
    val clipboardState: StateFlow<ClipboardTransfer?> = _clipboardState

    val remoteInputSink: NativeRemoteInputSink = this

    private val phoneDesktopSyncJob = scope.launch {
        clientState.collect { syncPhoneDesktop() }
    }
    private val retainedDeviceRefreshJob = scope.launch {
        while (true) {
            delay(10_000)
            if (!appForeground || !controlConnected || !clientState.value.devicesCurrent ||
                !clientState.value.devicePage.serverLimited || nodesTimedOut || nodesPager.pending || nodesExternalBusy) continue
            for (id in retainedDeviceIds().filter { DeviceListPolicy.refreshRetained(clientState.value.devicePage,it) }) {
                if (!appForeground || !controlConnected) break
                try { resolveDevice(id, refresh = true, timeoutMillis = 5_000) }
                catch (_: kotlinx.coroutines.TimeoutCancellationException) { /* Retry on the next bounded pass. */ }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Transport/authorization errors must not close unrelated sessions. */ }
            }
        }
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
        setServerKeyPolicy(spkiSha256, null)
    }

    fun setServerKeyPolicy(spkiSha256: String?, expected: String?) {
        require(spkiSha256 == null || spkiSha256.matches(Regex("[0-9a-f]{64}")))
        require(expected == null || expected.matches(Regex("[0-9a-f]{64}")))
        require(spkiSha256 == null || expected == null)
        require((spkiSha256 == null && expected == null) || baseUrl != null) { "Configure the endpoint before its key" }
        if (pinnedSpkiSha256 == spkiSha256 && expectedSpkiSha256 == expected) return
        pinnedSpkiSha256 = spkiSha256
        expectedSpkiSha256 = expected
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
        closeChats()
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
        closeChats()
        secureVault.rememberedDevice.bind(parsed.toString())
        // A different endpoint cannot inherit an earlier endpoint's trust decision.
        if (baseUrl != null) {
            pinnedSpkiSha256 = null
            expectedSpkiSha256 = null
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
        requestedNodes = null
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

        if (baseUrl != parsed || sessionUsername != username.trim()) closeChats()
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
            requestedNodes = null
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
        if (nodesTimedOut) return // Automatic inspector refresh must neither hide the error nor close sessions.
        if (!controlConnected) {
            retryConnection()
            return
        }
        requestControlSnapshot()
    }

    /** Only a genuine server page is polled; snapshots and ID selections are explicit reads. */
    fun setDeviceListVisible(visible: Boolean) {
        deviceListVisible = visible
        deviceListPollJob?.cancel()
        if (!visible) nodesRefreshJob?.cancel()
        if (visible && nodesSubscriptionDirty && _clientState.value.devicePage.pagingActive &&
            !nodesTimedOut && !nodesPager.pending && !nodesExternalBusy) requestNodes(silent = true)
        deviceListPollJob = if (!visible) null else scope.launch {
            while (true) {
                delay(15_000)
                synchronized(phoneInfoLock) {
                    if (DeviceListPolicy.poll(_clientState.value.devicePage, deviceListVisible, appForeground,
                        controlConnected, nodesPager.pending || nodesExternalBusy, nodesTimedOut)) requestNodes(silent = true)
                }
            }
        }
    }

    /** Only explicit UI retry may replace a timed-out transport. */
    fun retryDevices() {
        if (nodesWatchdog.mayReconnect(explicitRetry = true)) retryConnection() else refreshDevices()
    }

    fun searchDevices(filter: String, collectAll: Boolean, ids: Set<String>? = null) = synchronized(phoneInfoLock) {
        val query = DeviceListQuery(filter, collectAll, ids).normalized()
        val current = requestedNodes?.query ?: _clientState.value.devicePage.query
        if (DeviceListPolicy.queryChanged(query, current)) requestNodes(query.filter, query.collect, 0, ids = query.ids)
    }

    fun changeDevicePage(forward: Boolean) = synchronized(phoneInfoLock) {
        val page = _clientState.value.devicePage
        if (page.loading || (forward && !page.hasNext) || (!forward && !page.hasPrevious)) return@synchronized
        requestNodes(page.filter, false, (page.skip + (if (forward) 1 else -1) * (page.limit ?: 1)).coerceAtLeast(0))
    }

    private fun requestNodes(
        filter: String = requestedNodes?.filter ?: _clientState.value.devicePage.filter,
        collectAll: Boolean = requestedNodes?.collect ?: _clientState.value.devicePage.collecting,
        skip: Int = requestedNodes?.skip ?: _clientState.value.devicePage.skip,
        silent: Boolean = false,
        ids: Set<String>? = if (requestedNodes != null) requestedNodes?.ids else _clientState.value.devicePage.requestedIds,
    ) = synchronized(phoneInfoLock) {
        if (!controlConnected) return@synchronized
        val query = DeviceListQuery(filter, collectAll, ids).normalized()
        val intent = DeferredNodes(query.filter, collectAll, skip, query.ids)
        if (nodesPager.pending && requestedNodes == intent) return@synchronized
        cancelMembershipChecks()
        requestedNodes = intent
        _clientState.update { state -> state.copy(deviceQuery = query,
            devicePage = state.devicePage.copy(loading = !nodesTimedOut && DeviceListPolicy.hideRows(state.devicePage, query, skip, silent)),
            lastError = if (nodesTimedOut) state.lastError else null) }
        if (nodesTimedOut) scheduleNodesTimeout() // Keep the actionable error, but give the queued intent a deadline.
        if (nodesExternalBusy) { deferredNodes = requestedNodes; return@synchronized }
        nodesPager.request(query.filter, collectAll, skip, query.ids)?.let(::sendNodesRequest)
        nodesPager.emptyResult()?.let { result ->
            val retained = mergeNodes(emptyList(), replaceAll = true)
            _clientState.update { state -> state.copy(devicePage = requireNotNull(result.page), devices = retained, devicesCurrent = true) }
        }
    }

    private fun sendNodesRequest(request: JSONObject) {
        nodesTimeout?.cancel()
        if (!sendControlJson(request)) {
            finishNodesFailure()
            return
        }
        scheduleNodesTimeout()
    }

    private fun scheduleNodesTimeout() {
        nodesTimeout?.cancel()
        val ticket = nodesWatchdog.arm()
        nodesTimeout = scope.launch {
            delay(30_000)
            synchronized(phoneInfoLock) {
                if (!nodesWatchdog.expire(ticket)) return@synchronized
                // Do not free the wire slot. Explicit Retry can replace a genuinely stuck socket.
                finishNodesFailure(keepFlight = true, invalidateSnapshot = externalNodes == null)
            }
        }
    }

    private fun finishNodesFailure(keepFlight: Boolean = false, invalidateSnapshot: Boolean = true) {
        if (!keepFlight) nodesPager.reset()
        _clientState.update { it.copy(devicePage = it.devicePage.copy(loading = false), devicesCurrent = it.devicesCurrent && !invalidateSnapshot,
            lastError = UiText.Resource(R.string.devices_page_failed)) }
    }

    private fun canReconcileDevices(ignoreBusy: Boolean = false): Boolean =
        requestedNodes?.collect != true && requestedNodes?.ids == null &&
            DeviceListPolicy.reconcile(_clientState.value.devicePage, deviceListVisible, appForeground,
                controlConnected, !ignoreBusy && (nodesPager.pending || nodesExternalBusy), nodesTimedOut)

    private fun refreshDevicePageSoon() {
        if (!canReconcileDevices(ignoreBusy = true) || nodesRefreshJob?.isActive == true) return
        nodesRefreshJob = scope.launch {
            delay(350)
            while (nodesPager.pending && deviceListVisible && appForeground && controlConnected) delay(100)
            synchronized(phoneInfoLock) { if (canReconcileDevices()) requestNodes(silent = true) }
        }
    }

    /** Let the server's collation decide membership without re-scanning the collected list. */
    private fun refreshFilteredSnapshotNode(device: MeshDevice) {
        if (!membershipChecks.offer(device.nodeId,device.name,System.nanoTime()/1_000_000)) membershipCheckFailed()
        startMembershipWorker()
    }

    private fun membershipCheckFailed() {
        // Keep cached rows/actions usable. A bounded queue can overflow; explicit Retry reconciles it.
        _clientState.update { it.copy(lastError=UiText.Resource(R.string.devices_page_failed)) }
    }

    private fun cancelMembershipChecks() {
        membershipChecks.clear(); membershipWorker?.cancel(); membershipWorker=null
    }

    private fun startMembershipWorker() {
        if (!appForeground || !membershipChecks.hasWaiting || membershipWorker?.isActive==true) return
        membershipWorker=scope.launch {
            try {
                while (membershipChecks.hasWaiting && appForeground && controlConnected) {
                    val quiet=500-(System.nanoTime()/1_000_000-membershipChecks.lastEventMillis)
                    if(quiet>0) { delay(quiet);continue }
                    if(nodesTimedOut) break
                    if(nodesPager.pending || nodesExternalBusy || nodesControlMutex.isLocked) { delay(100);continue }
                    val ticket=membershipChecks.take() ?: break
                    val query=clientState.value.devicePage.query
                    val generation=sessionGeneration.get()
                    fun wanted() = membershipChecks.isCurrent(ticket) && generation==sessionGeneration.get() &&
                        clientState.value.deviceQuery==query && clientState.value.devicePage.query==query &&
                        DeviceListPolicy.serverFilteredSnapshot(clientState.value.devicePage)
                    try {
                        readAddressedDevice(ticket.id,query.filter,stillWanted=::wanted) { match ->
                            if(wanted()) {
                                // Existing rows already received the event. Do not overwrite newer connectivity.
                                if(match!=null && clientState.value.devices.none { it.nodeId==ticket.id }) upsertDevice(match,addressed=true)
                                _clientState.update { state ->
                                    val ids=if(match!=null) state.devicePage.nodeIds.orEmpty()+ticket.id else state.devicePage.nodeIds.orEmpty()-ticket.id
                                    state.copy(devicePage=state.devicePage.copy(nodeIds=ids,totalCount=ids.size,returnedCount=ids.size))
                                }
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { if(wanted()) membershipCheckFailed() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { if(coroutineContext[Job]?.isActive!=true) throw cancelled }
                    catch (_: Exception) { if(wanted()) membershipCheckFailed() }
                    finally { membershipChecks.finish(ticket) }
                    delay(750)
                }
            } finally { if(membershipWorker===coroutineContext[Job]) membershipWorker=null }
        }
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
        chatCookieGeneration = -1L
        chatControlNeedsRefresh = true
        relay.onNetworkAvailable()
        // New chat relays must use a cookie issued on the replacement network, including its IP
        // binding. Do not wait for the old control socket's ping timeout after a network handoff.
        if (chats.any { it.state.value.active } && sessionUsername != null && !manualClose &&
            !authRejected && !certificateRejected && !controlRetry.exhausted) {
            openControlSocket()
            return
        }
        // A real network event may supersede an in-flight connect on the old network. It must
        // never reset the server refusal budget or resubmit a rejected password/one-time code.
        if (!singleAttempt && waitingForNetwork && !manualClose && !authRejected && !certificateRejected && !controlRetry.exhausted) {
            openControlSocket()
        }
    }

    fun onNetworkLost() {
        networkAvailable = false
        chats.toList().forEach { it.onNetworkLost() }
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
        nodesRefreshJob?.cancel()
        relay.setPaused(true)
    }

    fun onAppForeground() {
        appForeground = true
        startMembershipWorker()
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
        portForwards.stopAll()
        additionalRelays.toList().forEach { it.close() }
        chatCookieGeneration = -1L
    }

    private fun closeChats() {
        chatIdentityGeneration.incrementAndGet()
        chats.toList().forEach { it.close() }
    }

    /** Messenger authenticates with authcookie.cookie (userid/domainid), not the agent rcookie. */
    fun createChat(device: MeshDevice, onBackgroundMessage: () -> Unit = {}, onEnded: () -> Unit = {}): ru.papajon.meshcentralmobile.mesh.chat.ChatController {
        val generation = chatIdentityGeneration.get()
        val owner = _clientState.value.userId
        val server = baseUrl
        fun valid(): Boolean {
            val state = _clientState.value
            if (generation != chatIdentityGeneration.get() || owner == null || owner != state.userId ||
                server != baseUrl || state.requiresSignIn || authRejected || certificateRejected) return false
            // An established browser relay does not depend on the agent or the control transport.
            // Recheck grants/deletion as soon as an authoritative snapshot is available.
            return !state.devicesCurrent || state.devices.firstOrNull { it.nodeId == device.nodeId }
                ?.let(ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol::authorized) == true
        }
        val chat = ru.papajon.meshcentralmobile.mesh.chat.ChatController(
            scope = scope, socketFactory = socketFactory,
            request = {
                val cookie = authCookie
                if (valid() && networkAvailable && !chatControlNeedsRefresh && controlConnected && _clientState.value.devicesCurrent &&
                    chatCookieGeneration == sessionGeneration.get() && server != null && owner != null && !cookie.isNullOrBlank())
                    ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol.request(server, device.nodeId, owner, cookie)
                else null
            },
            validSession = ::valid,
            refreshAuthorization = {
                // A passive chat does not interrupt other tools on a network event. The next
                // explicit request refreshes the IP-bound cookie before opening a relay.
                chatControlNeedsRefresh.also { stale ->
                    if (stale && valid() && networkAvailable) retryConnection()
                }
            },
            invite = {
                val state = _clientState.value
                val target = state.devices.firstOrNull { it.nodeId == device.nodeId }
                valid() && controlConnected && target != null &&
                    ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol.allowed(target, state.devicesCurrent) &&
                    sendControlJson(ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol.invite(device.nodeId))
            },
            // The controller owns screen visibility and unread state, including another foreground tool.
            onIncoming = onBackgroundMessage,
            onFailure = { failure -> failure.serverCertificateFailure()?.let(::reportRelayCertificateFailure) },
            onClose = { chats.remove(it); chatDeviceIds.remove(it); onEnded() },
        )
        chats.add(chat)
        chatDeviceIds[chat] = device.nodeId
        val observer = scope.launch { clientState.collect { if (!valid()) chat.close() } }
        scope.launch {
            chat.state.first { it.phase == ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed }
            observer.cancel()
        }
        if (generation != chatIdentityGeneration.get()) chat.close()
        return chat
    }

    fun reconnectDesktop() {
        desktopState.value.device?.takeIf { it.isAndroidAgent }?.let { refreshPhoneInput(it.nodeId) }
        relay.reconnect()
    }

    fun requestRemoteRefresh(): Boolean = relay.requestRefresh()

    fun setRemoteQuality(jpegQuality: Int, frameRate: Int, scaling: Int = desktopState.value.quality.scaling): Boolean =
        relay.setQuality(jpegQuality, frameRate, scaling)

    fun setAppLocked(locked: Boolean) = relay.setAppLocked(locked)

    fun setRemoteInputSuppressed(suppressed: Boolean) = relay.setInputSuppressed(suppressed)

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
        selectDevice(null)
        closeChats()
        requestedNodes = null
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
        closeChats()
        forwardSessionWatch.cancel()
        portForwards.close()

        selectionLookupJob?.cancel()
        retainedDeviceRefreshJob.cancel()
        deviceListPollJob?.cancel()
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
        chatControlNeedsRefresh = false
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
        cancelMembershipChecks()
        eventRefresh.cancel()
        awaitingRememberCookie = false
        controlRequests.disconnected()
        taskToolsBroker.disconnected()
        closeAdditionalRelays()
        relay.onControlLost()
        if (manual) manualClose = true
        synchronized(phoneInfoLock) {
            controlConnected = false
            deviceReadRevisions.clear()
            nodesPager.reset()
            externalNodes?.disconnected()
            externalNodes = null
            externalEventsResponseId = null
            nodesExternalBusy = false
            deferredNodes = null
            nodesWatchdog.complete()
            nodesTimeout?.cancel()
            nodesRefreshJob?.cancel()
            _clientState.update { it.copy(socketConnected = false, devicesCurrent = false, resolvingDeviceIds = emptySet(),
                devicePage = it.devicePage.copy(loading = false),
                devices = it.devices.map { device -> device.copy(remoteInput = PhoneRemoteInput.Unknown) }) }
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
        requestNodes()
        sendControlJson(MeshControlMessages.authCookie())
    }

    private fun sendControlJson(json: JSONObject): Boolean {
        return controlSocket?.send(json.toString()) == true
    }

    private fun reconnectControl(@StringRes reasonRes: Int, networkFailure: Boolean = false) {
        if (singleAttempt || manualClose || authRejected || certificateRejected) return
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
            DeviceListPolicy.retainSnapshot(incoming, state.devices, additionalRelays.map { it.nodeId }.toSet() + chatDeviceIds.values +
                listOfNotNull(desktopState.value.device?.nodeId) + state.resolvingDeviceIds, selectedDeviceId,
                state.devicePage.serverLimited, state.devicesCurrent)
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
        return sortDevices(resolved)
    }

    private var isSiteAdmin = false
    private var userRemoveRights: Int? = null
    private var userGroupIds: Set<String> = emptySet()

    /** Keeps fields the server does not resend (for example `agent` on `changenode`). */
    private fun mergeDevice(existing: MeshDevice, incoming: MeshDevice): MeshDevice {
        return incoming.copy(
            name = incoming.name.takeUnless { it == incoming.nodeId } ?: existing.name,
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
        val groupGrants = MeshControlParser.resolveRights(
            groups.map { MeshDevice("", it.id, "") }, groups, state.userId, isSiteAdmin,
            userGroupIds, state.serverCapabilities.manageAllDeviceGroups, userRemoveRights,
        ).associate { it.meshId to it.rights }
        val named = groups.map { it.copy(rights = groupGrants[it.id]) }
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
        }.sortedWith(DeviceListUpdates.comparator)
        _clientState.update { it.copy(groups = named, devices = devices) }
        syncPhoneDesktop()
    }

    private fun handleNodeEvent(event: MeshNodeEvent) = synchronized(phoneInfoLock) {
        val previous = when (event) {
            is MeshNodeEvent.NodeChanged -> _clientState.value.devices.firstOrNull { it.nodeId == event.device.nodeId }
            is MeshNodeEvent.NodeAdded -> _clientState.value.devices.firstOrNull { it.nodeId == event.device.nodeId }
            else -> null
        }
        val reconcile = DeviceListPolicy.refreshForEvent(_clientState.value.devicePage, event, previous)
        taskToolsBroker.nodeEvent(event)
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
                val state = _clientState.value
                val device = state.devices.firstOrNull { it.nodeId == event.nodeId }
                if (device != null) {
                    val updated = device.copy(conn = event.conn, pwr = event.pwr, connectivityKnown = true,
                        remoteInput = device.remoteInput.takeIf { device.conn and 1 != 0 && event.conn and 1 != 0 }
                            ?: PhoneRemoteInput.Unknown,
                        battery = device.battery.takeIf { event.conn and 1 != 0 })
                    _clientState.update { it.copy(devices = DeviceListUpdates.upsert(it.devices, updated)) }
                }
            }

            is MeshNodeEvent.NodeChanged -> upsertDevice(event.device)
            is MeshNodeEvent.NodeAdded -> {
                deviceReadRevisions.invalidate(event.device.nodeId)
                if (!_clientState.value.devicePage.serverLimited || _clientState.value.devicePage.completeSnapshot) upsertDevice(event.device)
                refreshDevicePageSoon()
            }
            is MeshNodeEvent.NodeRemoved -> {
                deviceReadRevisions.invalidate(event.nodeId)
                membershipChecks.remove(event.nodeId)
                val devices = _clientState.value.devices.filterNot { it.nodeId == event.nodeId }
                _clientState.update { state ->
                    val page = state.devicePage
                    val ids = page.nodeIds.orEmpty() - event.nodeId
                    state.copy(devices = devices, devicePage = if (page.completeSnapshot)
                        page.copy(nodeIds = ids, totalCount = ids.size, returnedCount = ids.size) else page)
                }
            }

            is MeshNodeEvent.MeshChanged -> {
                if (event.nodeId != null && event.meshId != null) {
                    _clientState.value.devices.firstOrNull { it.nodeId == event.nodeId }?.let { moved ->
                        upsertDevice(moved.copy(meshId = event.meshId,
                            meshName = _clientState.value.groups.firstOrNull { it.id == event.meshId }?.name ?: event.meshId))
                    }
                }
                if (event.meshId != null) eventRefresh.request()
            }
        }
        val named = when (event) {
            is MeshNodeEvent.NodeAdded -> event.device
            is MeshNodeEvent.NodeChanged -> event.device
            else -> null
        }?.takeIf { it.name != it.nodeId }
        named?.let { membershipChecks.observeName(it.nodeId, it.name) }
        if (DeviceListPolicy.membershipCandidate(_clientState.value.devicePage,event,previous)) {
            val changed = when(event) {
                is MeshNodeEvent.NodeAdded -> event.device
                is MeshNodeEvent.NodeChanged -> event.device.takeIf { it.name != it.nodeId && it.name != previous?.name }
                else -> null
            }
            if(changed!=null) refreshFilteredSnapshotNode(changed)
        }
        if (reconcile) refreshDevicePageSoon()
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

    private fun upsertDevice(device: MeshDevice, addressed: Boolean = false) {
        _clientState.update { state ->
            val existing = state.devices.firstOrNull { it.nodeId == device.nodeId }
            if (!addressed && existing == null && state.devicePage.serverLimited &&
                (!state.devicePage.completeSnapshot || !DeviceListPolicy.admits(state.devicePage, device))) return@update state
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
            state.copy(devices = DeviceListUpdates.upsert(state.devices, withRights),
                devicePage = if (addressed) state.devicePage else DeviceListPolicy.snapshotMembership(state.devicePage, withRights))
        }
    }

    private fun sortDevices(devices: List<MeshDevice>): List<MeshDevice> {
        return devices.sortedWith(DeviceListUpdates.comparator)
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
            if (acceptNodesControl(frame)) return
            val change = frame.takeIf { it.optText("action") == "event" }?.optJSONObject("event")
            if (change?.optText("action") == "sysinfohash") refreshPhoneInput(change.optText("nodeid"))
            taskToolsBroker.accept(frame)
            val consumed = controlRequests.accept(frame)
            // Verification reads only update their target through verifyAdminDevice. Late replies
            // must not replace the device list or reset unrelated phone capability state either.
            if (ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker.isIsolatedSnapshot(frame)) return
            when (val event = MeshControlParser.parse(frame, text.length)) {
                is ServerInfo -> {
                    nodesPager.configureLimit(event.capabilities.maxDeviceView)
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
                            agentInstallAllowed = event.agentInstallAllowed,
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
                    if (DeviceListPolicy.needsInitialSnapshot(nodesPager.pending, _clientState.value.devicesCurrent)) requestNodes()
                }

                is Meshes -> applyGroups(event.groups)

                is Nodes -> synchronized(phoneInfoLock) {
                    val result = nodesPager.accept(event)
                    if (result.next != null) { sendNodesRequest(result.next); return@synchronized }
                    if (result.failed) { nodesTimeout?.cancel(); nodesWatchdog.complete(); finishNodesFailure(); return@synchronized }
                    val incoming = result.devices ?: return@synchronized
                    nodesSubscriptionDirty = false
                    nodesTimeout?.cancel()
                    nodesWatchdog.complete()
                    if (deferredNodes == null) result.page?.let { requestedNodes = DeferredNodes(it.filter, it.collecting, it.skip, it.requestedIds) }
                    val devices = mergeNodes(incoming, replaceAll = true)
                    phoneInfoRequests.keys.retainAll(devices.map { it.nodeId }.toSet())
                    taskToolsBroker.nodesSnapshot(incoming)
                    _clientState.update {
                        it.copy(
                            connectionState = if (it.connectionState == ConnectionState.Connecting) {
                                ConnectionState.Authenticated
                            } else {
                                it.connectionState
                            },
                            devices = devices,
                            devicePage = result.page ?: it.devicePage.copy(loading = false),
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
                    desktopState.value.device?.takeIf { it.isAndroidAgent && it.remoteInput == PhoneRemoteInput.Unknown }
                        ?.let { refreshPhoneInput(it.nodeId) }
                }

                is AuthCookie -> {
                    authCookie = event.cookie
                    chatCookieGeneration = sessionGeneration.get()
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
            eventRefresh.cancel()
            t.serverCertificateFailure()?.let { rejectCertificate(it); return }
            controlRequests.disconnected()
            taskToolsBroker.disconnected()
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
            val credentialsRejected = response?.code == 401 || response?.code == 403
            // A rate-limited throwaway refresh must latch without retries. The main client keeps
            // HTTP 429 as an ordinary reconnect: the cookie/remembered-device record is not proof
            // of bad credentials and must survive until the server accepts or rejects the login.
            authRejected = credentialsRejected || (singleAttempt && response?.code == 429)
            if (credentialsRejected) forgetRememberedDevice()
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
            eventRefresh.cancel()
            controlRequests.disconnected()
            taskToolsBroker.disconnected()
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
        /** The production socket factory and JVM tests share this request-tag dispatch. */
        internal fun transportFor(request: Request, base: OkHttpClient, control: () -> OkHttpClient): OkHttpClient =
            if (request.tag(ru.papajon.meshcentralmobile.forward.RawTcpTransport::class.java) != null)
                ru.papajon.meshcentralmobile.forward.RawTcpTransport.client(base)
            else control()

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
