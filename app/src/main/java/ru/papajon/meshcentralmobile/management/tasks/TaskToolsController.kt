package ru.papajon.meshcentralmobile.management.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

interface TaskToolsTransport {
    val device: MeshDevice?
    val current: Boolean
    val features2: Long? get() = null
    val generation: Long get() = 0L
    suspend fun read(type: String, pid: Long? = null, timeoutMillis: Long = 15_000): JSONObject
    suspend fun serviceDetails(name: String): JSONObject
    fun kill(process: AgentProcess, confirmed: Boolean): Boolean
    fun service(service: AgentService, action: TaskServiceAction, confirmed: Boolean): Boolean
}

data class TaskToolsState(
    val processes: List<AgentProcess> = emptyList(),
    val services: List<AgentService> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val mutating: Boolean = false,
    val verificationReady: Boolean = true,
    val error: Int? = null,
    val notice: Int? = null,
    val details: Map<String, String>? = null,
    val detailsLoading: Boolean = false,
    val detailsError: Int? = null,
    val serviceDetails: AgentService? = null,
)

/** Owned by the visible screen, never retains Activity or credentials. */
class TaskToolsController(
    private val transport: TaskToolsTransport,
    private val servicesMode: Boolean,
    scope: CoroutineScope,
    private val pollMillis: Long = 1_000,
    private val processDeadline: Long = 10_000,
    private val serviceDeadline: Long = 15_000,
    private val refreshMillis: Long = 5_000,
) : AutoCloseable {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(TaskToolsState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var ticker: Job? = null
    private var detailJob: Job? = null
    private var active = false
    private var confirmationOpen = false
    private var commandDispatched = false
    private var lastGeneration = transport.generation
    val features2: Long? get() = transport.features2
    private val serviceStartup = mutableMapOf<String, String>()

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        ticker?.cancel()
        if (value) {
            refresh()
            ticker = worker.launch { while (isActive) {
                delay(refreshMillis)
                if (!confirmationOpen && (mutable.value.error == null || mutable.value.error == R.string.tasks_pending_reply)) refresh()
            } }
        } else {
            worker.coroutineContext[Job]?.children?.forEach { it.cancel() }
            mutable.update { it.copy(loading = false, mutating = false, detailsLoading = false,
                notice = if (it.mutating && commandDispatched) R.string.tasks_unverified else it.notice) }
        }
    }

    fun setConfirmationOpen(open: Boolean) { confirmationOpen = open }

    fun refresh() {
        if (confirmationOpen || operation?.isActive == true || mutable.value.mutating) return
        operation = worker.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try { readList() }
            catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutable.update { it.copy(verificationReady = false, error = if (servicesMode) R.string.tasks_unsupported else R.string.tasks_timeout) }
                else throw cancelled
            } catch (error: Exception) { mutable.update { it.copy(verificationReady = false, error = errorText(error)) } }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }

    private fun requireReadable() {
        val device = transport.device ?: throw ControlRequestException("offline")
        if (!transport.current || !device.connectivityKnown || device.conn and 1 == 0) throw ControlRequestException("offline")
        if (!TaskToolsProtocol.canRead(device, transport.current)) throw ControlRequestException("denied")
    }
    private fun requireMutable() {
        requireReadable()
        if (!TaskToolsProtocol.canMutate(requireNotNull(transport.device), transport.current, transport.features2)) throw ControlRequestException("denied")
    }
    private fun requireGeneration(expected: Long) {
        if (transport.generation != expected) throw ControlRequestException("agent_disconnected")
    }
    private fun requireVerification() {
        if (!mutable.value.verificationReady) throw ControlRequestException(
            if (mutable.value.error == R.string.tasks_uncertain_reply) "uncertain_reply" else "invalid_snapshot")
    }
    private suspend fun readList(timeout: Long = 15_000) {
        requireReadable()
        val generation = transport.generation
        val reply = transport.read(if (servicesMode) "services" else "ps", timeoutMillis = timeout)
        requireReadable(); requireGeneration(generation)
        if (generation != lastGeneration) {
            serviceStartup.clear()
            mutable.update { it.copy(processes = emptyList(), services = emptyList(), loaded = false) }
            lastGeneration = generation
        }
        val trusted = TaskToolsBroker.verifiable(reply)
        try {
            if (servicesMode) {
                val previous = mutable.value.services
                val startup = serviceStartup.toMap()
                val observed = withContext(Dispatchers.Default) {
                    val rows = TaskToolsProtocol.services(reply)
                    val stopped = if (trusted) TaskToolsEvidence.systemdStoppedNames(previous, rows) else emptySet()
                    rows.map { row -> row.copy(startType = row.startType ?: startup[row.name], observedNotRunning = row.name in stopped) }
                }
                mutable.update { it.copy(services = observed, loaded = true, verificationReady = trusted && observed.isNotEmpty(),
                    error = snapshotError(trusted, observed.isEmpty())) }
            } else {
                val rows = withContext(Dispatchers.Default) { TaskToolsProtocol.processes(reply) }
                mutable.update { it.copy(processes = rows, loaded = true, verificationReady = trusted && rows.isNotEmpty(),
                    error = snapshotError(trusted, rows.isEmpty())) }
            }
        } catch (error: IllegalArgumentException) { throw ControlRequestException("invalid_snapshot") }
    }

    fun kill(process: AgentProcess, confirmed: Boolean) {
        if (!confirmed || servicesMode || mutable.value.mutating) return
        mutate {
            val generation = transport.generation
            readList(); requireVerification(); requireGeneration(generation)
            val before = mutable.value.processes
            if (before.none { it.pid == process.pid && it.name == process.name }) throw ControlRequestException("changed")
            requireMutable(); requireGeneration(generation)
            if (!transport.kill(process, true)) throw ControlRequestException("send_failed")
            commandDispatched = true
            mutable.update { it.copy(notice = R.string.tasks_sent) }
            var observedAfterSend = false
            val windows = transport.device?.isWindows == true
            val gone = pollUntil(processDeadline) {
                readList(); requireVerification(); requireGeneration(generation)
                if (!TaskToolsEvidence.processSnapshot(before, mutable.value.processes)) throw ControlRequestException("invalid_snapshot")
                if (!windows && mutable.value.processes.any { it.pid == process.pid && it.name != process.name })
                    throw ControlRequestException("changed")
                observedAfterSend = true
                TaskToolsEvidence.processEnded(process, before, mutable.value.processes, windows)
            }
            mutable.update { it.copy(notice = when {
                gone -> R.string.tasks_killed
                observedAfterSend -> R.string.tasks_still_running
                else -> R.string.tasks_unverified
            }) }
        }
    }

    fun service(target: AgentService, action: TaskServiceAction, confirmed: Boolean) {
        if (!confirmed || !servicesMode || mutable.value.mutating) return
        mutate {
            val generation = transport.generation
            readList(); requireVerification(); requireGeneration(generation)
            val baseline = mutable.value.services
            val before = baseline.firstOrNull { it.name == target.name } ?: throw ControlRequestException("changed")
            if (TaskToolsProtocol.critical(before) && !TaskToolsProtocol.critical(target)) throw ControlRequestException("changed")
            requireMutable(); requireGeneration(generation)
            if (!transport.service(before, action, true)) throw ControlRequestException("send_failed")
            commandDispatched = true
            mutable.update { it.copy(notice = R.string.tasks_sent) }
            var sawStopped = before.running == false
            val verified = pollUntil(serviceDeadline) {
                readList(); requireVerification(); requireGeneration(generation)
                if (!TaskToolsEvidence.serviceSnapshot(baseline, mutable.value.services)) throw ControlRequestException("invalid_snapshot")
                val now = mutable.value.services.firstOrNull { it.name == target.name }
                if (now?.running == false) sawStopped = true
                serviceVerified(action, before, now, sawStopped)
            }
            mutable.update { it.copy(notice = if (verified) R.string.tasks_service_verified else R.string.tasks_service_unverified) }
        }
    }

    /** Stop between polls; a verification deadline must not abandon an in-flight agent reply. */
    private suspend fun pollUntil(deadlineMillis: Long, check: suspend () -> Boolean): Boolean {
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < deadlineMillis) {
            delay(pollMillis)
            if ((System.nanoTime() - started) / 1_000_000 >= deadlineMillis) break
            if (check()) return true
        }
        return false
    }

    private fun mutate(block: suspend () -> Unit) {
        val previousRead = operation
        commandDispatched = false
        // Mark busy synchronously, then join an in-flight auto refresh instead of losing a click.
        operation = worker.launch(start = CoroutineStart.UNDISPATCHED) {
            mutable.update { it.copy(mutating = true, error = null, notice = null) }
            try { previousRead?.join(); requireMutable(); block() }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(notice = if (commandDispatched) R.string.tasks_unverified else null) }
                if (cancelled !is TimeoutCancellationException) throw cancelled
                mutable.update { it.copy(error = R.string.tasks_timeout, verificationReady = false) }
            } catch (error: Exception) {
                mutable.update { it.copy(error = errorText(error, action = true), notice = if (commandDispatched) R.string.tasks_unverified else null) }
            } finally { mutable.update { it.copy(mutating = false) } }
        }
    }

    fun details(process: AgentProcess) {
        if (process.pid <= 0 || detailJob?.isActive == true) return
        detailJob = worker.launch {
            mutable.update { it.copy(detailsLoading = true, details = emptyMap(), detailsError = null) }
            try {
                requireReadable()
                val detail = TaskToolsProtocol.details(transport.read("psinfo", process.pid), process.pid)
                mutable.update { it.copy(details = detail, detailsError = if (detail.isEmpty()) R.string.tasks_details_unavailable else null) }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                mutable.update { it.copy(detailsError = R.string.tasks_details_unavailable) }
            } catch (_: Exception) { mutable.update { it.copy(detailsError = R.string.tasks_details_unavailable) } }
            finally { mutable.update { it.copy(detailsLoading = false) } }
        }
    }
    fun serviceDetails(service: AgentService) {
        if (operation?.isActive == true || detailJob?.isActive == true) return
        detailJob = worker.launch {
            mutable.update { it.copy(serviceDetails = service, detailsLoading = true, detailsError = null) }
            try {
                requireReadable()
                val detail = TaskToolsProtocol.serviceDetails(transport.serviceDetails(service.name), service.name).copy(displayName = service.displayName)
                detail.startType?.takeIf { it.isNotBlank() }?.let { serviceStartup[service.name] = it }
                mutable.update { it.copy(serviceDetails = detail, services = it.services.map { row ->
                    if (row.name == detail.name) row.copy(startType = detail.startType ?: row.startType) else row
                }) }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                mutable.update { it.copy(detailsError = R.string.tasks_timeout) }
            } catch (error: Exception) { mutable.update { it.copy(detailsError = errorText(error)) } }
            finally { mutable.update { it.copy(detailsLoading = false) } }
        }
        operation = detailJob
    }
    fun dismissDetails() { detailJob?.cancel(); mutable.update { it.copy(details = null, serviceDetails = null, detailsLoading = false, detailsError = null) } }
    override fun close() { worker.cancel() }

    companion object {
        fun serviceVerified(action: TaskServiceAction, before: AgentService, now: AgentService?, sawStopped: Boolean): Boolean = when (action) {
            TaskServiceAction.Start -> now?.running == true
            TaskServiceAction.Stop -> now?.running == false
            TaskServiceAction.Restart -> now?.running == true && (sawStopped ||
                (before.pid != null && before.pid > 0 && now.pid != null && now.pid > 0 && before.pid != now.pid))
        }
        private fun snapshotError(trusted: Boolean, empty: Boolean): Int? = when {
            !trusted -> R.string.tasks_uncertain_reply
            empty -> R.string.tasks_empty_snapshot
            else -> null
        }
        private fun errorText(error: Exception, action: Boolean = false): Int = when ((error as? ControlRequestException)?.code) {
            "offline", "disconnected", "agent_disconnected", "not_authenticated" -> R.string.tasks_offline
            "denied" -> R.string.tasks_denied
            "changed" -> R.string.tasks_changed
            "task_reply_pending" -> R.string.tasks_pending_reply
            "uncertain_reply" -> R.string.tasks_uncertain_reply
            "invalid_snapshot" -> R.string.tasks_empty_snapshot
            "send_failed" -> R.string.tasks_send_failed
            else -> if (action) {
                if (error is IllegalArgumentException) R.string.tasks_invalid_target else R.string.tasks_action_error
            } else R.string.tasks_error
        }
    }
}
