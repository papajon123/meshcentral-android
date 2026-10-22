package ru.papajon.meshcentralmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** UI adapter: only selection is saved. Live sockets and input are never serialized or replayed. */
class MeshSessionViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val workspace = ConnectionWorkspace.get(application)
    val client get() = workspace.client
    val credentialVault get() = workspace.credentialVault
    val selectedDeviceId get() = workspace.selectedDeviceId
    val deviceTab get() = workspace.deviceTab
    val terminalController get() = workspace.selectedSession?.terminal
    val filesController get() = workspace.selectedSession?.files
    private var restorationPending = workspace.selectedDeviceId == null && saved.get<String>("node") != null

    fun switchProfile(id: String) { workspace.switchProfile(id); restorationPending = false; saveSelection() }
    fun bindIdentity(serverUrl: String, userId: String?) {
        workspace.bindIdentity(serverUrl, userId)
        if (restorationPending && userId != null) {
            restorationPending = false
            if (saved.get<String>("identity") == workspace.identity) {
                workspace.selectedDeviceId = saved["node"]
                workspace.deviceTab = runCatching { DeviceTab.valueOf(saved.get<String>("tab").orEmpty()) }.getOrDefault(DeviceTab.Overview)
            }
        }
        if (!restorationPending) saveSelection()
    }
    fun openDevice(device: MeshDevice) { workspace.openDevice(device); saveSelection() }
    fun selectDeviceTab(tab: DeviceTab): Boolean = workspace.selectTab(tab).also { saveSelection() }
    fun connectDesktop(device: MeshDevice): Boolean = workspace.connectDesktop(device).also { saveSelection() }
    fun showOverview() { workspace.showOverview(); saveSelection() }
    fun browseDevices() { workspace.browseDevices(); saveSelection() }
    fun selectSession(id: String) = workspace.selectSession(id).also { saveSelection() }
    fun closeSession(id: String) { workspace.closeSession(id); saveSelection() }
    fun rememberSelection() { if (!restorationPending) saveSelection() }
    fun closeTools(clearDevice: Boolean = false) {
        // Explicit shutdown path for logout/profile changes, not ordinary navigation.
        workspace.closeAll()
        saveSelection()
    }
    private fun saveSelection() {
        saved["identity"] = workspace.identity
        saved["node"] = selectedDeviceId
        saved["tab"] = deviceTab.name
    }
    // The Application runtime owns sockets; destroying the Activity must not kill an active transfer.
}
