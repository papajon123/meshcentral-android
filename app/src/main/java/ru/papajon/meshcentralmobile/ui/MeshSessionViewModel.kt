package ru.papajon.meshcentralmobile.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.terminal.TerminalController
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * Owns the MeshCentral session for the whole activity lifetime.
 *
 * Keeping the client in a ViewModel is what makes the remote desktop survive a rotation or any other
 * configuration change: a `remember { }` value would be thrown away and the socket with it.
 */
class MeshSessionViewModel(application: Application) : AndroidViewModel(application) {
    private var boundIdentity: Pair<String, String?>? = null

    /** Remember identity across Activity recreation; a new composition is not a new account. */
    fun bindIdentity(serverUrl: String, userId: String?) {
        val next = serverUrl to userId
        val previous = boundIdentity
        if (previous != null && previous != next) closeTools(clearDevice = true)
        boundIdentity = next
    }
    var selectedDeviceId by mutableStateOf<String?>(null)
        private set
    var deviceTab by mutableStateOf(DeviceTab.Overview)
        private set
    var terminalController by mutableStateOf<TerminalController?>(null)
        private set
    var filesController by mutableStateOf<FilesController?>(null)
        private set

    fun openDevice(device: MeshDevice) {
        closeTools()
        selectedDeviceId = device.nodeId
    }

    fun selectDeviceTab(tab: DeviceTab) {
        if (tab == deviceTab) return
        val state = client.clientState.value
        val device = state.devices.firstOrNull { it.nodeId == selectedDeviceId } ?: return
        val protocol = tab.protocol
        if (protocol != null && (!state.devicesCurrent || !device.sessionAvailability(protocol, state.serverCapabilities).canAttempt)) return
        closeTools()
        if (protocol != null) {
            client.disconnectDesktop()
            val options = if (tab == DeviceTab.Terminal) JSONObject().put("xterm", true).put("cols", 80).put("rows", 24) else null
            val connection = client.createRelayConnection(device, protocol, options)
            if (tab == DeviceTab.Terminal || tab == DeviceTab.Ssh) terminalController = TerminalController(connection, viewModelScope)
            else filesController = FilesController(connection, viewModelScope, getApplication())
        }
        deviceTab = tab
    }

    fun closeTools(clearDevice: Boolean = false) {
        terminalController?.close()
        filesController?.close()
        terminalController = null
        filesController = null
        deviceTab = DeviceTab.Overview
        if (clearDevice) selectedDeviceId = null
    }

    val client: MeshCentralClient by lazy {
        MeshCentralClient(secureVault = SecureVault(application), scope = viewModelScope)
    }

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { client.onNetworkAvailable() }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onCleared() {
        closeTools(clearDevice = true)
        connectivity.unregisterNetworkCallback(networkCallback)
        client.close()
        super.onCleared()
    }
}
