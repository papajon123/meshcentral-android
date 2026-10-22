package ru.papajon.meshcentralmobile.companion

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONException
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

/**
 * State holder for one device's companion session.
 *
 * The caller supplies the long-lived runtime scope. The controller creates its own child job,
 * so [close] cancels only its own polling/actions and never touches the Mesh client.
 */
class CompanionController(
    parentScope: CoroutineScope,
    private val client: CompanionClient,
    val nodeId: String,
) : Closeable {
    constructor(
        parentScope: CoroutineScope,
        meshClient: ru.papajon.meshcentralmobile.mesh.MeshCentralClient,
        nodeId: String,
    ) : this(parentScope, CompanionClient(meshClient), nodeId)

    private val ownJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownJob)
    private val actionMutex = Mutex()
    private val _state = MutableStateFlow(CompanionUiState())
    private var pollJob: Job? = null
    private var pollJobId: String? = null

    @Volatile
    private var closed = false

    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
    }

    val state: StateFlow<CompanionUiState> = _state.asStateFlow()

    /** Reads capabilities, history and events. It never starts a job. */
    fun refresh(): Job = scope.launch {
        if (!actionMutex.tryLock()) return@launch
        try {
            refreshLocked()
        } finally {
            actionMutex.unlock()
        }
    }

    fun refreshEvents(): Job = scope.launch {
        if (!actionMutex.tryLock()) return@launch
        try {
            when (val result = call { loadEvents() }) {
                is CallResult.Success -> Unit
                is CallResult.Failure -> _state.update {
                    it.copy(
                        error = result.code,
                        errorUncertain = isUncertain(result.code),
                    )
                }
            }
        } finally {
            actionMutex.unlock()
        }
    }

    /** Starts an allowlisted read-only template. The UI owns the disclosure confirmation. */
    fun startRead(template: CompanionTemplate): Job {
        require(template.readOnly) { "only read-only templates are accepted here" }
        return startJobInternal(template, serviceName = null, confirmed = false, reuse = null)
    }

    fun startDiagnostics(): Job = startRead(CompanionTemplate.DIAGNOSTICS)

    fun startServicesRead(): Job = startRead(CompanionTemplate.SERVICES)

    fun startProcessesRead(): Job = startRead(CompanionTemplate.PROCESSES)

    /** The UI must have shown a confirmation dialog before calling this. */
    fun serviceAction(action: CompanionServiceAction, serviceName: String): Job =
        startJobInternal(
            template = action.template,
            serviceName = serviceName,
            confirmed = true,
            reuse = null,
        )

    /**
     * Explicitly resends the same operation id after a timeout/disconnect. The server deduplicates
     * it. This method is never called automatically by polling or refresh.
     */
    fun recheckPendingOperation(): Job {
        val pending = _state.value.pendingOperation ?: return scope.launch { }
        return startJobInternal(
            template = pending.template,
            serviceName = pending.serviceName,
            confirmed = pending.confirmed,
            reuse = pending,
        )
    }

    fun dismissPendingOperation() {
        _state.update {
            it.copy(pendingOperation = null, error = null, errorUncertain = false)
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, errorUncertain = false) }
    }

    fun selectJob(jobId: String) {
        val job = _state.value.jobs.firstOrNull { it.id == jobId }
            ?: _state.value.selectedJob?.takeIf { it.id == jobId }
            ?: return
        _state.update { it.copy(selectedJob = job) }
        if (job.active) {
            ensurePolling(job.id)
        } else {
            val active = _state.value.jobs.firstOrNull { it.active }
            if (active != null) ensurePolling(active.id) else stopPolling()
        }
    }

    /** Queued jobs can be cancelled; a running job is reported as unavailable by the plugin. */
    fun cancelJob(jobId: String): Job = scope.launch {
        if (!actionMutex.tryLock()) return@launch
        try {
            val job = findJob(jobId)
            if (job == null) {
                setError(CompanionErrorCode.JOB_NOT_FOUND)
                return@launch
            }
            if (job.status == CompanionJobStatus.RUNNING) {
                setError(CompanionErrorCode.CANCEL_UNSUPPORTED)
                return@launch
            }
            if (!job.active) {
                _state.update { it.copy(selectedJob = job, error = null, errorUncertain = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, errorUncertain = false) }
            val updated = when (val result = call { client.cancelJob(jobId) }) {
                is CallResult.Failure -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = result.code,
                            errorUncertain = isUncertain(result.code),
                        )
                    }
                    return@launch
                }
                is CallResult.Success -> result.value
            }
            if (updated.nodeId != nodeId) {
                setError(CompanionErrorCode.PROTOCOL)
                stopPolling()
                return@launch
            }
            _state.update {
                it.mergeJob(updated).copy(
                    loading = false,
                    selectedJob = updated,
                    polling = updated.active,
                )
            }
            if (updated.active) ensurePolling(updated.id) else stopPolling()
        } finally {
            actionMutex.unlock()
        }
    }

    /**
     * Deletes one terminal job on the server. Protocol v1 answers `UNKNOWN_OP`; in that case the
     * local list is left unchanged and the typed error is shown.
     */
    fun deleteJob(jobId: String): Job = scope.launch {
        if (!actionMutex.tryLock()) return@launch
        try {
            val job = findJob(jobId)
            if (job == null) {
                setError(CompanionErrorCode.JOB_NOT_FOUND)
                return@launch
            }
            if (job.active) {
                setError(CompanionErrorCode.JOB_ACTIVE)
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, errorUncertain = false) }
            when (val result = call { client.deleteJob(jobId) }) {
                is CallResult.Failure -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = result.code,
                            errorUncertain = isUncertain(result.code),
                        )
                    }
                    return@launch
                }
                is CallResult.Success -> Unit
            }
            _state.update { it.removeJob(jobId).copy(loading = false) }
        } finally {
            actionMutex.unlock()
        }
    }

    /** Explicit bulk delete; stops at the first failure and keeps every unconfirmed job. */
    fun clearHistory(): Job = scope.launch {
        if (!actionMutex.tryLock()) return@launch
        try {
            val targets = _state.value.jobs.filter { it.terminal }
            if (targets.isEmpty()) return@launch
            _state.update { it.copy(loading = true, error = null, errorUncertain = false) }
            for (job in targets) {
                when (val result = call { client.deleteJob(job.id) }) {
                    is CallResult.Failure -> {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = result.code,
                                errorUncertain = isUncertain(result.code),
                            )
                        }
                        return@launch
                    }
                    is CallResult.Success -> Unit
                }
                _state.update { it.removeJob(job.id) }
            }
            _state.update { it.copy(loading = false) }
        } finally {
            actionMutex.unlock()
        }
    }

    /** Cancels only this controller's child work. The supplied Mesh client stays open. */
    override fun close() {
        if (closed) return
        closed = true
        pollJob?.cancel()
        pollJobId = null
        ownJob.cancel()
    }

    private suspend fun refreshLocked() {
        _state.update { it.copy(loading = true, error = null, errorUncertain = false) }
        val capabilities = when (val result = call { client.capabilities() }) {
            is CallResult.Failure -> {
                _state.update {
                    it.copy(
                        available = false,
                        loading = false,
                        error = result.code,
                        errorUncertain = false,
                    )
                }
                return
            }
            is CallResult.Success -> result.value
        }
        val supported = capabilities.protocolVersion == COMPANION_PROTOCOL_VERSION &&
            capabilities.operations.contains("startJob")
        if (!supported) {
            _state.update {
                it.copy(
                    available = false,
                    capabilities = capabilities,
                    loading = false,
                    error = CompanionErrorCode.UNSUPPORTED_PROTOCOL,
                    errorUncertain = false,
                )
            }
            return
        }

        var firstError: CompanionErrorCode? = null
        val previous = _state.value
        val jobs = when (val result = call { client.listJobs(nodeId).sortedByDescending { it.createdAt } }) {
            is CallResult.Failure -> {
                firstError = result.code
                previous.jobs
            }
            is CallResult.Success -> result.value
        }
        val page = when (val result = call { client.events(previous.eventCursor) }) {
            is CallResult.Failure -> {
                if (firstError == null) firstError = result.code
                null
            }
            is CallResult.Success -> result.value
        }

        _state.update { current ->
            val selected = current.selectedJob?.let { old ->
                jobs.firstOrNull { it.id == old.id } ?: old
            } ?: jobs.firstOrNull { it.active }
            val mergedEvents = (if (page == null) {
                current.events
            } else if (page.resetCursor) {
                page.items
            } else {
                current.events + page.items
            }).distinctBy { it.id }.sortedBy { it.sequence }.takeLast(MAX_EVENTS_IN_MEMORY)
            current.copy(
                available = true,
                capabilities = capabilities,
                loading = false,
                jobs = jobs,
                selectedJob = selected,
                events = mergedEvents,
                eventCursor = page?.cursor ?: current.eventCursor,
                error = firstError,
                errorUncertain = false,
            )
        }
        val active = _state.value.jobs.firstOrNull { it.active }
        if (active != null) {
            ensurePolling(active.id)
        } else {
            stopPolling()
        }
    }

    private fun startJobInternal(
        template: CompanionTemplate,
        serviceName: String?,
        confirmed: Boolean,
        reuse: PendingOperation?,
    ): Job = scope.launch {
        if (closed) return@launch
        val snapshot = _state.value
        if (!snapshot.available) {
            setError(CompanionErrorCode.UNSUPPORTED_PROTOCOL)
            return@launch
        }
        if (snapshot.pendingOperation != null && reuse == null) {
            setError(CompanionErrorCode.PENDING_OPERATION)
            return@launch
        }
        if (reuse == null && snapshot.jobs.any { it.active }) {
            setError(CompanionErrorCode.NODE_BUSY)
            return@launch
        }
        if (!actionMutex.tryLock()) return@launch
        try {
            val pending = reuse ?: PendingOperation(
                operationId = CompanionClient.newOperationId(),
                createdAt = System.currentTimeMillis(),
                template = template,
                serviceName = serviceName,
                confirmed = confirmed,
            )
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    errorUncertain = false,
                    pendingOperation = reuse ?: pending,
                )
            }
            val job = when (
                val result = call {
                    client.startJob(
                        nodeId = nodeId,
                        template = pending.template,
                        parameters = pending.parameters,
                        operationId = pending.operationId,
                        createdAt = pending.createdAt,
                        confirmed = pending.confirmed,
                    )
                }
            ) {
                is CallResult.Failure -> {
                    val uncertain = isUncertain(result.code)
                    _state.update {
                        it.copy(
                            loading = false,
                            error = result.code,
                            errorUncertain = uncertain,
                            pendingOperation = if (uncertain) pending else null,
                        )
                    }
                    return@launch
                }
                is CallResult.Success -> result.value
            }
            _state.update {
                it.mergeJob(job).copy(
                    loading = false,
                    selectedJob = job,
                    error = null,
                    errorUncertain = false,
                    pendingOperation = null,
                )
            }
            if (job.active) ensurePolling(job.id) else loadEventsQuietly()
        } finally {
            actionMutex.unlock()
        }
    }

    private fun ensurePolling(jobId: String) {
        if (closed) return
        if (pollJob?.isActive == true && pollJobId == jobId) return
        pollJob?.cancel()
        pollJobId = jobId
        pollJob = scope.launch {
            _state.update { it.copy(polling = true) }
            while (isActive) {
                delay(POLL_DELAY_MILLIS)
                val current = _state.value.jobs.firstOrNull { it.id == jobId } ?: break
                if (!current.active) break
                val updated = when (val result = call { client.getJob(jobId) }) {
                    is CallResult.Failure -> {
                        _state.update {
                            it.copy(
                                polling = false,
                                error = result.code,
                                errorUncertain = isUncertain(result.code),
                            )
                        }
                        return@launch
                    }
                    is CallResult.Success -> result.value
                }
                if (updated.nodeId != nodeId) {
                    _state.update { it.copy(polling = false) }
                    setError(CompanionErrorCode.PROTOCOL)
                    return@launch
                }
                _state.update {
                    it.mergeJob(updated).copy(polling = updated.active)
                }
                if (!updated.active) {
                    loadEventsQuietly()
                    return@launch
                }
            }
            _state.update { it.copy(polling = false) }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollJobId = null
        _state.update { it.copy(polling = false) }
    }

    private suspend fun loadEvents() {
        val page = client.events(_state.value.eventCursor)
        _state.update { current ->
            val merged = (if (page.resetCursor) {
                page.items
            } else {
                current.events + page.items
            }).distinctBy { it.id }.sortedBy { it.sequence }.takeLast(MAX_EVENTS_IN_MEMORY)
            current.copy(events = merged, eventCursor = page.cursor)
        }
    }

    private fun loadEventsQuietly() {
        if (closed) return
        scope.launch {
            try {
                loadEvents()
            } catch (_: TimeoutCancellationException) {
                // The job state is already available; a missing event refresh is not a retry.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The job state is already available; a missing event refresh is not a retry.
            }
        }
    }

    private fun findJob(jobId: String): CompanionJob? =
        _state.value.jobs.firstOrNull { it.id == jobId }
            ?: _state.value.selectedJob?.takeIf { it.id == jobId }

    private fun setError(code: CompanionErrorCode) {
        _state.update { it.copy(loading = false, error = code, errorUncertain = false) }
    }

    private fun CompanionUiState.mergeJob(job: CompanionJob): CompanionUiState {
        val jobs = (listOf(job) + jobs.filterNot { it.id == job.id })
            .sortedByDescending { it.createdAt }
        val selected = if (selectedJob?.id == job.id) job else selectedJob
        return copy(jobs = jobs, selectedJob = selected)
    }

    private fun CompanionUiState.removeJob(jobId: String): CompanionUiState = copy(
        jobs = jobs.filterNot { it.id == jobId },
        selectedJob = selectedJob?.takeIf { it.id != jobId },
        polling = if (selectedJob?.id == jobId) false else polling,
    )

    private fun classify(error: Throwable): CompanionErrorCode = when (error) {
        is CompanionException -> error.code
        is TimeoutCancellationException -> CompanionErrorCode.TIMEOUT
        is CancellationException -> throw error
        is ControlRequestException -> {
            val mapped = CompanionErrorCode.fromWire(error.code)
            if (mapped == CompanionErrorCode.UNKNOWN) CompanionErrorCode.NETWORK else mapped
        }
        is JSONException -> CompanionErrorCode.PROTOCOL
        is IllegalArgumentException -> CompanionErrorCode.INVALID_ARGUMENT
        is IOException -> CompanionErrorCode.NETWORK
        else -> CompanionErrorCode.UNKNOWN
    }

    private fun isUncertain(code: CompanionErrorCode): Boolean = when (code) {
        CompanionErrorCode.TIMEOUT,
        CompanionErrorCode.DISCONNECTED,
        CompanionErrorCode.NETWORK,
        CompanionErrorCode.PROTOCOL,
        CompanionErrorCode.UNKNOWN,
        -> true
        else -> false
    }

    private sealed interface CallResult<out T> {
        data class Success<T>(val value: T) : CallResult<T>
        data class Failure(val code: CompanionErrorCode) : CallResult<Nothing>
    }

    private suspend fun <T> call(block: suspend () -> T): CallResult<T> = try {
        CallResult.Success(block())
    } catch (timeout: TimeoutCancellationException) {
        CallResult.Failure(CompanionErrorCode.TIMEOUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CallResult.Failure(classify(error))
    }

    companion object {
        const val POLL_DELAY_MILLIS = 2_000L
        private const val MAX_EVENTS_IN_MEMORY = 200
    }
}
