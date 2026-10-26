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

data class EventDestination(val profileId: String, val nodeId: String?, val jobId: String?)

class OpenSession(
    val id: String,
    val nodeId: String,
    val deviceName: String,
    val tab: DeviceTab,
    val terminal: TerminalController? = null,
    val files: FilesController? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() { terminal?.close(); files?.close(); onClose() }
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
        scope.launch {
            try {
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
                val device = client.clientState.value.devices.firstOrNull { it.nodeId == target.nodeId }
                    ?: error("device unavailable")
                check(client.clientState.value.devicesCurrent && expectedUser != null)
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
    var eventDestination by mutableStateOf<EventDestination?>(null)
    fun acceptShare(intent: Intent) {
        val targetProfile = runCatching { intent.getStringExtra(NotificationContract.EXTRA_PROFILE_ID) }.getOrNull()
        if (targetProfile != null && SettingsRepository(application).profilesState.value.any { it.id == targetProfile }) {
            val node = runCatching { intent.getStringExtra(NotificationContract.EXTRA_NODE_ID) }.getOrNull()?.takeIf { it.startsWith("node/") && it.length <= 2048 }
            val job = runCatching { intent.getStringExtra(NotificationContract.EXTRA_JOB_ID) }.getOrNull()?.takeIf { it.matches(Regex("mc_[A-Za-z0-9_-]{1,128}")) }
            eventDestination = EventDestination(targetProfile, node, job)
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
    var credentialVault = vault(profileId)
        private set
    var client by mutableStateOf(MeshCentralClient(credentialVault, scope))
        private set
    private val registry = SessionRegistry<OpenSession>()
    var sessions by mutableStateOf<List<OpenSession>>(emptyList())
        private set
    var selectedDeviceId by mutableStateOf<String?>(null)
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
        if (tab == DeviceTab.Desktop) return connectDesktop(device)
        if (tab.protocol != null) {
            if (!state.devicesCurrent || !device.sessionAvailability(tab.protocol, state.serverCapabilities).canAttempt) return false
            val id = "${device.nodeId}|${tab.name}"
            val session = registry.admit(id) {
                val options = if (tab == DeviceTab.Terminal) JSONObject().put("xterm", true).put("cols", 80).put("rows", 24) else null
                val connection = client.createRelayConnection(device, tab.protocol, options)
                if (tab == DeviceTab.Terminal || tab == DeviceTab.Ssh) {
                    OpenSession(id, device.nodeId, device.name, tab, terminal = TerminalController(connection, scope))
                } else OpenSession(id, device.nodeId, device.name, tab, files = FilesController(connection, scope, application))
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
        if (old != null && old.nodeId == selectedDeviceId && old.tab == deviceTab) showOverview()
        old?.let { lastTabs.remove(it.nodeId) }
    }
    fun closeAll() {
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
        selectedDeviceId = null
        deviceTab = DeviceTab.Overview
        lastTabs.clear()
    }

    init {
        if (!networkAvailable) client.onNetworkLost()
        application.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch {
                    networkAvailable = true
                    client.onNetworkAvailable()
                } }
                override fun onLost(network: Network) { scope.launch {
                    networkAvailable = false
                    client.onNetworkLost()
                } }
            },
        )
    }

    companion object {
        @Volatile private var instance: ConnectionWorkspace? = null
        fun get(application: Application): ConnectionWorkspace = instance ?: synchronized(this) {
            instance ?: ConnectionWorkspace(application).also { instance = it }
        }
    }
}
