package ru.papajon.meshcentralmobile.management

import ru.papajon.meshcentralmobile.json.optText

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val events: List<DeviceHistoryEvent>? = null,
    val powerTimeline: List<PowerTransition>? = null,
    val eventsError: Boolean = false,
    val timelineError: Boolean = false,
    val software: List<InstalledProgram>? = null,
    val softwareError: Boolean = false,
    val metrics: MetricsSample? = null,
    val metricsError: Boolean = false,
    val commandOutput: String? = null,
    val commandUncertain: Boolean = false,
    val commandOutputTruncated: Boolean = false,
    val historyLoading: Boolean = false,
)

class DeviceInspectorController(private val client: MeshCentralClient, val nodeId: String, scope: CoroutineScope) : AutoCloseable {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val companion = CompanionClient(client)
    private val jobs = InspectorJobRunner(companion, nodeId)
    private val mutable = MutableStateFlow(InspectorState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var historyOperation: Job? = null
    init {
        worker.launch {
            client.controlFailures.collect { failure ->
                if (failure.nodeId == nodeId) mutable.update { it.copy(notice = null,
                    error = inspectorFailureText(ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("server_rejected", failure.reason))) }
            }
        }
    }
    private val device get() = client.clientState.value.devices.firstOrNull { it.nodeId == nodeId }

    fun refresh() = perform {
        val available = if (device?.isAndroidAgent == true) false else try { CompanionClient(client, timeoutMillis = 5_000).capabilities().operations.contains("startJob") }
            catch (_: TimeoutCancellationException) { false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        mutable.update { it.copy(companionAvailable = available) }
        val system = client.requestControl(JSONObject().put("action", "getsysinfo").put("nodeid", nodeId).put("nodeinfo", true), "getsysinfo", nodeId)
        if (system.optBoolean("noinfo") && device?.isAndroidAgent != true) throw MissingInfo()
        val network = client.requestControl(JSONObject().put("action", "getnetworkinfo").put("nodeid", nodeId), "getnetworkinfo", nodeId)
        val parsed = SystemInfoParser.parse(system, network)
        mutable.update { it.copy(snapshot = parsed.copy(deviceName = parsed.deviceName ?: device?.name),
            notice = UiText.resource(R.string.workspace_cached_info)) }
    }

    fun loadServices() = perform { readServices() }

    fun phoneCommand(request: ru.papajon.meshcentralmobile.mesh.PhoneRequest) = perform {
        if (!client.sendPhoneCommand(nodeId, request)) {
            throw InspectorJobException(UiText.resource(R.string.phone_send_failed))
        }
        mutable.update { it.copy(notice = UiText.resource(R.string.phone_command_sent)) }
    }
    fun loadHistory() {
        if (historyOperation?.isActive == true) return
        historyOperation = worker.launch {
            mutable.update { it.copy(events = null, powerTimeline = null, eventsError = false, timelineError = false,
                historyLoading = true, error = null, notice = null) }
            try { coroutineScope {
                launch {
                    try {
                        val reply = client.requestControl(ru.papajon.meshcentralmobile.mesh.MeshControlMessages.deviceEvents(nodeId), "events", nodeId)
                        mutable.update { it.copy(events = DeviceHistoryParser.events(reply)) }
                    } catch (_: TimeoutCancellationException) { mutable.update { it.copy(eventsError = true) } }
                      catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { mutable.update { it.copy(eventsError = true) } }
                }
                launch {
                    try {
                        val timeline = DeviceHistoryLoader.powerTimeline(
                            request = { client.requestControl(ru.papajon.meshcentralmobile.mesh.MeshControlMessages.powerTimeline(nodeId), "powertimeline", nodeId) },
                            freshOffline = {
                                val current = client.clientState.value
                                current.socketConnected && current.devicesCurrent && device?.let { it.connectivityKnown && it.conn == 0 } == true
                            })
                        mutable.update { it.copy(powerTimeline = timeline) }
                    } catch (_: TimeoutCancellationException) { mutable.update { it.copy(timelineError = true) } }
                      catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { mutable.update { it.copy(timelineError = true) } }
                }
            } } finally { mutable.update { it.copy(historyLoading = false) } }
        }
    }
    fun sendToast(title: String, message: String) = perform {
        val target = device ?: throw MissingInfo()
        val current = client.clientState.value
        val command = ToastCommand.create(target, current.socketConnected && current.devicesCurrent, title, message)
        val reply = client.requestControl(command, "toast", nodeId)
        if (!reply.optText("result").equals("ok", ignoreCase = true)) throw MissingInfo()
        mutable.update { if (it.error == null) it.copy(notice = UiText.resource(R.string.parity_message_sent)) else it }
    }

    fun loadSoftware() = perform {
        mutable.update { it.copy(software = null, softwareError = false) }
        try {
            val current = client.clientState.value
            val command = InstalledSoftware.command(device ?: throw MissingInfo(), current.socketConnected && current.devicesCurrent)
            val reply = client.requestControl(command, "software", nodeId, 30_000)
            mutable.update { it.copy(software = InstalledSoftware.parse(reply)) }
        } catch (_: TimeoutCancellationException) { mutable.update { it.copy(softwareError = true) } }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { mutable.update { it.copy(softwareError = true) } }
    }

    fun loadMetrics() = perform {
        mutable.update { it.copy(metrics = null, metricsError = false) }
        try {
            val current = client.clientState.value
            val command = NativeMetrics.command(device ?: throw MissingInfo(), current.socketConnected && current.devicesCurrent)
            val reply = client.requestControl(command, "msg", nodeId)
            mutable.update { it.copy(metrics = NativeMetrics.parse(reply, System.currentTimeMillis())) }
        } catch (_: TimeoutCancellationException) { mutable.update { it.copy(metricsError = true) } }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { mutable.update { it.copy(metricsError = true, error = inspectorFailureText(error)) } }
    }

    fun runCommand(command: String, powershellUtf8: Boolean = false) = perform {
        mutable.update { it.copy(commandOutput = null, commandUncertain = false, commandOutputTruncated = false) }
        try {
            val current = client.clientState.value
            val request = RunCommand.create(device ?: throw MissingInfo(), current.socketConnected && current.devicesCurrent, command, true, powershellUtf8)
            val reply = client.requestControl(request, "msg", nodeId, 60_000)
            mutable.update { it.copy(commandOutput = RunCommand.output(reply), commandOutputTruncated = RunCommand.outputTruncated(reply)) }
        } catch (_: TimeoutCancellationException) { mutable.update { it.copy(commandUncertain = true) } }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { mutable.update { it.copy(commandUncertain = false, error = inspectorFailureText(error)) } }
    }

    fun remove(action: DeviceRemovalAction, typedName: String) = perform {
        val current = client.clientState.value
        val command = DeviceRemovalCommand.create(device ?: throw MissingInfo(), current.socketConnected && current.devicesCurrent, action, true, typedName)
        if (action == DeviceRemovalAction.UninstallAgent) {
            if (!client.requestAgentUninstall(nodeId, typedName)) throw MissingInfo()
        } else {
            val reply = client.requestControl(command, action.wire)
            if (!reply.optText("result").equals("ok", ignoreCase = true)) throw MissingInfo()
        }
        mutable.update { it.copy(notice = UiText.resource(R.string.parity2_removal_pending)) }
        var observation = RemovalObservation.Unknown
        // Re-read nodes with a new correlation id. Cached list state never proves a removal.
        repeat(6) {
            delay(2_000)
            try {
                val reply = client.requestControl(JSONObject().put("action", "nodes")
                    .put("id", nodeId).put("skip", 0).put("filter", ""), "nodes", timeoutMillis = 5_000)
                observation = DeviceRemovalCommand.observeReply(action, nodeId, reply, client.clientState.value.socketConnected)
            } catch (_: TimeoutCancellationException) { observation = RemovalObservation.Unknown }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { observation = RemovalObservation.Unknown }
        }
        mutable.update { it.copy(notice = UiText.resource(when (observation) {
            RemovalObservation.Absent -> R.string.parity2_removal_absent
            RemovalObservation.Disconnected -> R.string.parity2_removal_disconnected
            RemovalObservation.Unknown -> R.string.parity2_removal_unknown
        })) }
    }
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
        val current = client.clientState.value
        val command = DevicePowerCommand.create(target, current.socketConnected && current.devicesCurrent, action)
        val reply = client.requestControl(command, command.getString("action"), nodeId)
        ru.papajon.meshcentralmobile.mesh.control.ControlReplyPolicy.failure(command.getString("action"), reply)?.let {
            throw ru.papajon.meshcentralmobile.mesh.control.ControlRequestException("server_rejected", it)
        }
        mutable.update { it.copy(notice = UiText.resource(R.string.workspace_power_pending)) }
        var sawOffline = target.conn and 1 == 0
        var observedReturn = false
        repeat(30) {
            delay(2_000)
            if (it % 5 == 0) {
                try { client.resolveDevice(nodeId, refresh = true, timeoutMillis = 5_000) }
                catch (_: TimeoutCancellationException) { /* Keep observing, without restarting the browser query. */ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A missing observation cannot prove a power transition. */ }
            }
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
        fun availablePowerActions(device: MeshDevice, current: Boolean): Set<DevicePowerAction> = DevicePowerCommand.available(device, current)
    }
}
