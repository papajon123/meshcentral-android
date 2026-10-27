package ru.papajon.meshcentralmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.DeviceListQuery
import ru.papajon.meshcentralmobile.mesh.DeviceBrowserFilter
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Saves selection and browser intent, never live sockets or remote input. */
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
                if (saved.contains("browserFilter")) client.restoreDeviceBrowser(
                    runCatching { DeviceBrowserFilter.valueOf(saved.get<String>("browserFilter").orEmpty()) }.getOrDefault(DeviceBrowserFilter.All),
                    saved["browserSearch"],
                    DeviceListQuery(saved.get<String>("queryFilter").orEmpty(),saved.get<Boolean>("queryCollect") ?: false,
                        saved.get<ArrayList<String>>("queryIds")?.toSet()),saved.get<Int>("querySkip") ?: 0)
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
        val state=client.clientState.value
        saved["browserFilter"]=state.deviceBrowserFilter.name
        saved["browserSearch"]=state.deviceBrowserSearch
        saved["queryFilter"]=state.deviceQuery.filter
        saved["queryCollect"]=state.deviceQuery.collect
        saved["queryIds"]=state.deviceQuery.ids?.let { ArrayList(it) }
        saved["querySkip"]=client.deviceBrowserSkip
    }
    // The Application runtime owns sockets; destroying the Activity must not kill an active transfer.
}
