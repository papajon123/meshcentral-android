package ru.papajon.meshcentralmobile.workspace

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.security.CredentialScope
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.terminal.TerminalController
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.management.DeviceInspectorController
import ru.papajon.meshcentralmobile.companion.CompanionClient
import ru.papajon.meshcentralmobile.companion.CompanionController
import ru.papajon.meshcentralmobile.transfer.*
import ru.papajon.meshcentralmobile.notifications.NotificationController
import ru.papajon.meshcentralmobile.notifications.NotificationContract

data class EventDestination(val profileId: String, val nodeId: String?, val jobId: String?, val chatSession: String? = null)

class OpenSession(
    val id: String,
    val nodeId: String,
    val deviceName: String,
    val tab: DeviceTab,
    val terminal: TerminalController? = null,
    val files: FilesController? = null,
    val chat: ru.papajon.meshcentralmobile.mesh.chat.ChatController? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() { terminal?.close(); files?.close(); chat?.close(); onClose() }
}

/** Application lifetime, never holds an Activity. Foreground transfers can outlive its UI. */
class ConnectionWorkspace private constructor(private val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var clearingSession by mutableStateOf(false)
        private set

    /** One application-owned operation: rotation cannot cancel cleanup or allow a competing login. */
    fun clearSavedSession(disableNotifications: suspend () -> Unit) {
        if (clearingSession) return
        clearingSession = true
        val owner = client
        val vault = credentialVault
        val id = profileId
        val clearLauncher = ru.papajon.meshcentralmobile.integration.LauncherCoordinator.clear(application, id)
        cancelPendingDestinations()
        scope.launch {
            try {
                clearLauncher.join()
                closeAll()
                // Revocation is best effort. Its Keystore/WorkManager work must not run on Main.
                try { withContext(Dispatchers.IO) { withTimeout(25_000) { disableNotifications() } } }
                catch (_: TimeoutCancellationException) { /* Always finish the local cleanup. */ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Offline revocation must not retain local sign-in. */ }
                // Invalidate callbacks BEFORE deleting credentials so a late userinfo cannot save
                // them again. RememberedDeviceStore may be locked by an IO cookie read; clear on IO.
                owner.logout(clearStoredCredentials = false, clearRememberedDevice = false)
                withContext(Dispatchers.IO) {
                    vault.clearCredentials()
                    NotificationController.disableLocal(application, id)
                }
                ru.papajon.meshcentralmobile.web.MeshWebViewController.clearWebViewSession(application)
                message = UiText.Resource(R.string.settings_session_cleared)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = UiText.Resource(R.string.reliability_clear_failed) }
            finally { clearingSession = false }
        }
    }
    var transfers by mutableStateOf(TransferQueue(scope))
        private set
    var sharedFiles by mutableStateOf<List<SharedFile>>(emptyList())
        private set
    private var sharedSequence = 0L
    var reconnectingTransfer by mutableStateOf(false)
        private set
    private var retryJob: Job? = null
    fun retryTransfer(id: Long) {
        val queue = transfers
        val target = queue.target ?: return
        if (reconnectingTransfer || target.profileId != profileId || queue.state.value.none {
                it.id == id && it.status in setOf(TransferStatus.Failed, TransferStatus.Cancelled)
            }) return
        if (queue.hasPending) { queue.retry(id); return }
        val expectedClient = client
        val expectedUser = client.clientState.value.userId
        retryJob = scope.launch {
            reconnectingTransfer = true
            try {
                val device = expectedClient.resolveDevice(target.nodeId)
                    ?: error("device unavailable")
                check(client === expectedClient && client.clientState.value.devicesCurrent && expectedUser != null &&
                    client.clientState.value.userId == expectedUser && queue === transfers)
                val tab = if (target.controller.state.value.sftp) DeviceTab.Sftp else DeviceTab.Files
                val slot = sessions.firstOrNull { it.nodeId == target.nodeId && it.tab == tab }
                if (slot != null && slot.files?.state?.value?.let { !it.connected && !it.busy && !it.authRequired } == true) closeSession(slot.id)
                selectedDeviceId = device.nodeId
                if (!selectTab(tab)) return@launch
                val fresh = selectedSession?.files ?: error("file session unavailable")
                fresh.connect()
                val ready = withTimeout(30_000) { fresh.state.first { (it.connected && !it.busy) || it.authRequired || (!it.busy && it.message != null) } }
                if (ready.authRequired) { message = UiText.resource(R.string.terminal_auth_required); return@launch }
                check(ready.connected)
                if (ready.path != target.directory) {
                    fresh.browse(target.directory)
                    withTimeout(30_000) { fresh.state.first { it.path == target.directory && it.connected && !it.busy } }
                }
                check(queue === transfers && client === expectedClient && client.clientState.value.userId == expectedUser)
                check(queue.rebindForRetry(TransferTarget(target.profileId, target.profileName, target.nodeId, target.nodeName, target.directory, fresh)))
                check(queue.retry(id))
            } catch (_: TimeoutCancellationException) {
                if (queue === transfers) message = UiText.resource(R.string.workspace_queue_rejected)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (queue === transfers) message = UiText.resource(R.string.workspace_queue_rejected) }
            finally { reconnectingTransfer = false }
        }
    }
    var showTransfersRequested by mutableStateOf(false)
    var showChatsRequested by mutableStateOf(false)
    var unreadChats by mutableStateOf(0)
        private set
    private val mutableActiveChats = kotlinx.coroutines.flow.MutableStateFlow(false)
    val activeChats: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableActiveChats
    private fun refreshChatUnread() {
        unreadChats = sessions.sumOf { it.chat?.state?.value?.unreadCount ?: 0 }
        mutableActiveChats.value = sessions.any { it.chat?.state?.value?.active == true }
    }
    fun closeChats() { sessions.filter { it.chat != null }.map { it.id }.forEach(::closeSession) }
    fun ensureChatService(): Boolean = try {
        androidx.core.content.ContextCompat.startForegroundService(application,
            Intent(application, ru.papajon.meshcentralmobile.background.ChatService::class.java))
        true
    } catch (_: RuntimeException) {
        message = UiText.Resource(R.string.chat_service_failed)
        false
    }
    private var pendingEvent by mutableStateOf<EventDestination?>(null)
    private var pendingLauncher by mutableStateOf<String?>(null)
    var launcherActivationAttempted by mutableStateOf(false)
        private set
    var eventDestination: EventDestination?
        get() = pendingEvent
        set(value) {
            if (value != null) launcherDestination = null
            pendingEvent = value
        }
    var launcherDestination: String?
        get() = pendingLauncher
        set(value) {
            if (value != pendingLauncher) launcherActivationAttempted = false
            if (value != null) pendingEvent = null
            pendingLauncher = value
        }
    fun claimLauncherActivation(token: String): Boolean {
        if (launcherDestination != token || launcherActivationAttempted) return false
        launcherActivationAttempted = true
        return true
    }
    fun restoreLauncherDestination(token: String, activationAttempted: Boolean) {
        launcherDestination = token
        launcherActivationAttempted = activationAttempted
    }
    fun cancelPendingDestinations() {
        launcherDestination = null
        eventDestination = null
    }
    fun acceptShare(intent: Intent) {
        if (!ru.papajon.meshcentralmobile.workspace.IncomingIntentPolicy.accepts(intent.flags)) return
        if (intent.action == ru.papajon.meshcentralmobile.background.ChatService.SHOW_CHATS) { showChatsRequested = true; return }
        val targetProfile = runCatching { intent.getStringExtra(NotificationContract.EXTRA_PROFILE_ID) }.getOrNull()
        if (targetProfile != null && SettingsRepository(application).profilesState.value.any { it.id == targetProfile }) {
            val node = runCatching { intent.getStringExtra(NotificationContract.EXTRA_NODE_ID) }.getOrNull()?.takeIf { it.startsWith("node/") && it.length <= 2048 }
            val job = runCatching { intent.getStringExtra(NotificationContract.EXTRA_JOB_ID) }.getOrNull()?.takeIf { it.matches(Regex("mc_[A-Za-z0-9_-]{1,128}")) }
            val chat = runCatching { intent.getStringExtra(ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.EXTRA_SESSION) }
                .getOrNull()?.takeIf { it.length <= 2100 }
            eventDestination = EventDestination(targetProfile, node, job, chat)
        }
        if (intent.action == "show-transfers") { showTransfersRequested = true; return }
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        scope.launch {
            val files = withContext(Dispatchers.IO) { runCatching { SharedContentReader(application).read(intent) }.getOrDefault(emptyList()) }
            sharedFiles = files
            if (files.isEmpty()) message = UiText.resource(R.string.workspace_share_unreadable)
        }
    }
    fun dismissShare() { sharedFiles = emptyList() }
    fun enqueueShare(target: TransferTarget): Boolean {
        if (!transfers.hasPending && transfers.target != null &&
            (transfers.target?.controller !== target.controller || transfers.target?.directory != target.directory)) {
            transfers.close()
            transfers = TransferQueue(scope)
        }
        val items = sharedFiles.map { TransferItem(++sharedSequence, it.uri, it.name, it.size) }
        val accepted = transfers.enqueueConfirmed(target, items)
        if (accepted) sharedFiles = emptyList() else message = UiText.resource(R.string.workspace_queue_rejected)
        return accepted
    }
    var profileId by mutableStateOf(SettingsRepository(application).activeProfile.id)
        private set
    private var networkAvailable = application.getSystemService(ConnectivityManager::class.java).activeNetwork != null
    private var currentNetwork = application.getSystemService(ConnectivityManager::class.java).activeNetwork
    var credentialVault = vault(profileId)
        private set
    var client by mutableStateOf(MeshCentralClient(credentialVault, scope))
        private set
    private val lockSettings = SettingsRepository(application)
    val appLockVault = SecureVault(application)
    val appLock = ru.papajon.meshcentralmobile.settings.AppLockController(lockSettings, appLockVault) {
        updateAppVisibility()
    }
    private var processVisible = false

    /** One process-wide lock gate; individual Activities cannot unpause a locked desktop. */
    private fun updateAppVisibility(force: Boolean = false) {
        client.setAppLocked(appLock.isLocked)
        client.setRemoteInputSuppressed(appLock.isLocked || !appLock.hasInteractiveWindow)
        val visible = appLock.hasVisibleWindow
        if (force || visible != processVisible) {
            processVisible = visible
            if (visible) client.onAppForeground() else client.onAppBackground()
        }
    }

    private val registry = SessionRegistry<OpenSession>()
    var sessions by mutableStateOf<List<OpenSession>>(emptyList())
        private set
    private var selectedDevice by mutableStateOf<String?>(null)
    var selectedDeviceId: String?
        get() = selectedDevice
        set(value) { selectedDevice = value; client.selectDevice(value) }
    var deviceTab by mutableStateOf(DeviceTab.Overview)
    var message by mutableStateOf<UiText?>(null)
    private var boundIdentity: String? = null
    private val lastTabs = mutableMapOf<String, DeviceTab>()
    private val inspectors = linkedMapOf<String, DeviceInspectorController>()
    private val companions = linkedMapOf<String, CompanionController>()
    fun inspector(nodeId: String): DeviceInspectorController = inspectors.getOrPut(nodeId) {
        if (inspectors.size >= 4) inspectors.remove(inspectors.keys.first())?.close()
        DeviceInspectorController(client, nodeId, scope)
    }
    fun companion(nodeId: String): CompanionController = companions.getOrPut(nodeId) {
        if (companions.size >= 4) companions.remove(companions.keys.first())?.close()
        CompanionController(scope, CompanionClient(client), nodeId)
    }
    val selectedSession: OpenSession? get() = sessions.firstOrNull { it.nodeId == selectedDeviceId && it.tab == deviceTab }
    val identity: String? get() = boundIdentity

    private fun vault(id: String) = SecureVault(application, id).also {
        if (id == ProfileCatalog.DEFAULT_ID) {
            it.migrateLegacyCredentials(SettingsRepository(application).profilesState.value.first { p -> p.id == id }.serverUrl)
        }
    }

    fun switchProfile(id: String) {
        if (clearingSession) return
        if (id == profileId) return
        client.forgetRememberedDevice()
        val incoming = sharedFiles // Shared files have no target until the user confirms a folder.
        closeAll()
        sharedFiles = incoming
        client.close()
        profileId = id
        boundIdentity = null
        credentialVault = vault(id)
        client = MeshCentralClient(credentialVault, scope)
        updateAppVisibility(force = true)
        if (!networkAvailable) client.onNetworkLost()
    }

    fun identityFor(url: String, user: String?): String? = user?.let { "$profileId|${CredentialScope.normalize(url)}|$it" }
    fun bindIdentity(url: String, user: String?) {
        val next = identityFor(url, user)
        if (boundIdentity != null && boundIdentity != next) {
            NotificationController.disableLocal(application, profileId)
            closeAll()
        }
        boundIdentity = next
    }

    fun openDevice(device: MeshDevice) {
        selectedDeviceId = device.nodeId
        deviceTab = lastTabs[device.nodeId] ?: DeviceTab.Overview
    }

    fun selectTab(tab: DeviceTab): Boolean {
        val state = client.clientState.value
        val device = state.devices.firstOrNull { it.nodeId == selectedDeviceId } ?: return false
        if (tab == DeviceTab.PortForwarding && !client.canForward(device.nodeId)) return false
        if (tab == DeviceTab.Desktop) return connectDesktop(device)
        if (tab == DeviceTab.Chat) {
            val id = "${device.nodeId}|Chat"
            if (registry.get(id) == null && !ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol.allowed(device, state.devicesCurrent && state.socketConnected)) return false
            if (registry.get(id)?.chat?.state?.value?.phase == ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed) registry.close(id)
            val ownerProfile = profileId
            val existing = registry.get(id)
            val session = registry.admit(id) {
                val updates = ru.papajon.meshcentralmobile.mesh.chat.ChatNotificationUpdates(scope)
                OpenSession(id, device.nodeId, device.name, tab,
                    chat = client.createChat(device,
                        onBackgroundMessage = {
                            updates.submit {
                                val unread = registry.get(id)?.chat?.state?.value?.unreadCount ?: 0
                                if (profileId == ownerProfile && unread > 0) ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.post(application, ownerProfile, device.nodeId, id, unread)
                            }
                        },
                        onEnded = {
                            updates.cancel()
                            ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.cancel(application, ownerProfile, id)
                            scope.launch {
                                // Admission may still be constructing the session. Do not retain a
                                // dead slot or let an old callback close a newly opened conversation.
                                kotlinx.coroutines.yield()
                                if (profileId == ownerProfile && registry.get(id)?.chat?.state?.value?.phase ==
                                    ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed) closeSession(id)
                            }
                        }))
            }
            if (session == null) { message = UiText.resource(R.string.workspace_capacity); return false }
            sessions = registry.values()
            if (existing == null) scope.launch {
                session.chat?.state?.takeWhile { it.phase != ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed }
                    ?.collect { refreshChatUnread() }
                refreshChatUnread()
            }
            if (!ensureChatService()) { if (existing == null) closeSession(id); return false }
            // The explicit selection owns startup; a rapid navigation away must not leave an
            // idle foreground service waiting for a Composable that was never mounted.
            session.chat?.start()
        }
        if (tab.protocol != null) {
            if (!state.devicesCurrent || !device.sessionAvailability(tab.protocol, state.serverCapabilities).canAttempt) return false
            val id = "${device.nodeId}|${tab.name}"
            val session = registry.admit(id) {
                val options = if (tab == DeviceTab.Terminal) JSONObject().put("xterm", true).put("cols", 80).put("rows", 24) else null
                val connection = client.createRelayConnection(device, tab.protocol, options)
                val owner = client
                val identity = boundIdentity
                val factory: () -> ru.papajon.meshcentralmobile.mesh.session.RelayConnection? = {
                    val latest = owner.clientState.value
                    val currentDevice = latest.devices.firstOrNull { it.nodeId == device.nodeId }
                    if (client !== owner || boundIdentity != identity || !latest.devicesCurrent || latest.requiresSignIn ||
                        currentDevice == null || !currentDevice.sessionAvailability(tab.protocol, latest.serverCapabilities).canAttempt) null
                    else owner.createRelayConnection(currentDevice, tab.protocol, options)
                }
                if (tab == DeviceTab.Terminal || tab == DeviceTab.Ssh) {
                    OpenSession(id, device.nodeId, device.name, tab, terminal = TerminalController(connection, scope, connectionFactory = factory))
                } else OpenSession(id, device.nodeId, device.name, tab, files = FilesController(connection, scope, application, connectionFactory = factory))
            }
            if (session == null) { message = UiText.resource(R.string.workspace_capacity); return false }
            sessions = registry.values()
        }
        deviceTab = tab
        lastTabs[device.nodeId] = tab
        return true
    }

    fun connectDesktop(device: MeshDevice): Boolean {
        val current = client.clientState.value
        if (!current.devicesCurrent || !device.canOpenNativeDesktop) return false
        val old = sessions.firstOrNull { it.tab == DeviceTab.Desktop }
        if (old != null && old.nodeId != device.nodeId) {
            message = UiText.resource(R.string.workspace_desktop_limit)
            return false
        }
        val id = "${device.nodeId}|Desktop"
        if (registry.admit(id) {
            client.connectDesktop(device)
            OpenSession(id, device.nodeId, device.name, DeviceTab.Desktop, onClose = client::disconnectDesktop)
        } == null) { message = UiText.resource(R.string.workspace_capacity); return false }
        sessions = registry.values()
        client.setRemotePaused(false)
        selectedDeviceId = device.nodeId
        deviceTab = DeviceTab.Desktop
        lastTabs[device.nodeId] = DeviceTab.Desktop
        return true
    }

    fun selectSession(id: String): OpenSession? = registry.get(id)?.also {
        selectedDeviceId = it.nodeId
        deviceTab = it.tab
        if (it.tab == DeviceTab.Desktop) client.setRemotePaused(false)
    }
    fun showOverview() { deviceTab = DeviceTab.Overview }
    fun browseDevices() { selectedDeviceId = null; deviceTab = DeviceTab.Overview; client.setRemotePaused(true) }
    fun closeSession(id: String) {
        val old = registry.get(id)
        if (old?.files != null && transfers.target?.controller === old.files) transfers.cancelAll()
        registry.close(id)
        sessions = registry.values()
        refreshChatUnread()
        if (old != null && old.nodeId == selectedDeviceId && old.tab == deviceTab) showOverview()
        old?.let { lastTabs.remove(it.nodeId) }
    }
    fun closeAll() {
        client.portForwards.stopAll()
        retryJob?.cancel()
        retryJob = null
        reconnectingTransfer = false
        transfers.close()
        transfers = TransferQueue(scope)
        sharedFiles = emptyList()
        inspectors.values.forEach { it.close() }; inspectors.clear()
        companions.values.forEach { it.close() }; companions.clear()
        registry.closeAll()
        sessions = emptyList()
        unreadChats = 0
        mutableActiveChats.value = false
        selectedDeviceId = null
        deviceTab = DeviceTab.Overview
        lastTabs.clear()
    }

    init {
        if (!networkAvailable) client.onNetworkLost()
        application.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch {
                    if (currentNetwork == network && networkAvailable) return@launch
                    currentNetwork = network
                    networkAvailable = true
                    client.onNetworkAvailable()
                } }
                override fun onLost(network: Network) { scope.launch {
                    // A late loss callback for the old default must not tear down its replacement.
                    if (currentNetwork != network) return@launch
                    currentNetwork = null
                    networkAvailable = false
                    client.onNetworkLost()
                } }
            },
        )
    }

    companion object {
        @Volatile private var instance: ConnectionWorkspace? = null
        fun existing(): ConnectionWorkspace? = instance
        fun get(application: Application): ConnectionWorkspace = instance ?: synchronized(this) {
            instance ?: ConnectionWorkspace(application).also { instance = it }
        }
    }
}
