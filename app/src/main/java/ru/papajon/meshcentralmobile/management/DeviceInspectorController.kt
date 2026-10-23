package ru.papajon.meshcentralmobile.management

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.*
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice

data class InspectorState(
    val snapshot: DeviceSystemSnapshot? = null,
    val services: List<ManagedService> = emptyList(),
    val processes: List<ManagedProcess> = emptyList(),
    val loading: Boolean = false,
    val companionAvailable: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
)

class DeviceInspectorController(private val client: MeshCentralClient, val nodeId: String, scope: CoroutineScope) : AutoCloseable {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val companion = CompanionClient(client)
    private val jobs = InspectorJobRunner(companion, nodeId)
    private val mutable = MutableStateFlow(InspectorState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private val device get() = client.clientState.value.devices.firstOrNull { it.nodeId == nodeId }

    fun refresh() = perform {
        val available = try { CompanionClient(client, timeoutMillis = 5_000).capabilities().operations.contains("startJob") }
            catch (_: TimeoutCancellationException) { false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        mutable.update { it.copy(companionAvailable = available) }
        val system = client.requestControl(JSONObject().put("action", "getsysinfo").put("nodeid", nodeId).put("nodeinfo", true), "getsysinfo", nodeId)
        if (system.optBoolean("noinfo")) throw MissingInfo()
        val network = client.requestControl(JSONObject().put("action", "getnetworkinfo").put("nodeid", nodeId), "getnetworkinfo", nodeId)
        mutable.update { it.copy(snapshot = SystemInfoParser.parse(system, network), notice = UiText.resource(R.string.workspace_cached_info)) }
    }

    fun loadServices() = perform { readServices() }
    private suspend fun readServices() {
        val result = runTemplate("services") as? CompanionServices ?: throw MissingInfo()
        mutable.update { it.copy(services = result.items.map { s -> ManagedService(s.name, s.displayName ?: s.name, s.state.orEmpty(), s.startMode) }) }
    }
    fun loadProcesses() = perform {
        val result = runTemplate("processes") as? CompanionProcesses ?: throw MissingInfo()
        mutable.update { it.copy(processes = result.items.map { p -> ManagedProcess(p.pid, p.name, p.cpu?.toString(), p.memoryBytes?.toString()) }) }
    }
    fun serviceAction(name: String, action: ServiceAction) = perform {
        if (device?.rights?.let { it and ru.papajon.meshcentralmobile.mesh.MeshRights.REMOTE_COMMAND != 0 } != true) {
            throw CompanionException(CompanionErrorCode.FORBIDDEN)
        }
        val result = runTemplate("service-${action.name.lowercase(java.util.Locale.ROOT)}", mapOf("name" to name), true)
        if (result !is CompanionServiceMutation || result.name != name) throw MissingInfo()
        readServices()
        mutable.update { it.copy(notice = UiText.resource(R.string.workspace_service_verified)) }
    }

    private suspend fun runTemplate(id: String, parameters: Map<String, String> = emptyMap(), confirmed: Boolean = false): CompanionResult {
        val template = CompanionTemplate.entries.first { it.id == id }
        return jobs.run(template, parameters, confirmed)
    }

    fun power(action: DevicePowerAction) = perform {
        val target = device ?: throw MissingInfo()
        require(action in availablePowerActions(target, client.clientState.value.devicesCurrent))
        val name = if (action == DevicePowerAction.Wake) "wakedevices" else "poweraction"
        val command = JSONObject().put("action", name).put("nodeids", JSONArray().put(nodeId))
        if (action != DevicePowerAction.Wake) command.put("actiontype", when (action) {
            DevicePowerAction.Shutdown -> 2; DevicePowerAction.Restart -> 3; DevicePowerAction.Sleep -> 4; else -> error("unsupported")
        })
        client.requestControl(command, name)
        mutable.update { it.copy(notice = UiText.resource(R.string.workspace_power_pending)) }
        var sawOffline = !target.online
        var observedReturn = false
        repeat(30) {
            delay(2_000)
            if (it % 5 == 0) client.refreshDevices()
            val snapshot = client.clientState.value
            val fresh = snapshot.devices.firstOrNull { d -> d.nodeId == nodeId }
            if (snapshot.devicesCurrent && fresh != null) {
                if (fresh.conn and 1 == 0) sawOffline = true
                else if (sawOffline) observedReturn = true
            }
        }
        mutable.update { it.copy(notice = UiText.resource(when {
            observedReturn -> R.string.workspace_power_reconnected
            sawOffline -> R.string.workspace_power_offline
            else -> R.string.workspace_power_unknown
        })) }
    }

    private fun perform(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = worker.launch {
            mutable.update { it.copy(loading = true, error = null, notice = null) }
            try { block() }
            catch (timeout: TimeoutCancellationException) { mutable.update { it.copy(error = inspectorFailureText(timeout)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = inspectorFailureText(error)) } }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }
    override fun close() { worker.cancel() }
    private class MissingInfo : Exception()

    companion object {
        fun availablePowerActions(device: MeshDevice, current: Boolean): Set<DevicePowerAction> = buildSet {
            val rights = device.rights ?: return@buildSet
            if (!current || !device.isMeshAgent) return@buildSet
            if (rights and 64 != 0 && device.conn and 1 == 0) add(DevicePowerAction.Wake)
            if (rights and 0x40000 != 0 && device.conn and 1 != 0) {
                add(DevicePowerAction.Restart); add(DevicePowerAction.Shutdown)
            }
        }
    }
}
