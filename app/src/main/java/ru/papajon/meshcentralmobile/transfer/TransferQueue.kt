package ru.papajon.meshcentralmobile.transfer

import android.content.ContentResolver
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.files.AgentFilesProtocol
import ru.papajon.meshcentralmobile.mesh.files.FilesController

enum class TransferStatus { Queued, Active, Succeeded, Failed, Cancelled }

/**
 * UI-safe queue row. Deliberately contains no content URI: URIs stay in [TransferItem] for the
 * lifetime of this process only and are never put into a Bundle, log or preference.
 */
data class QueuedTransfer(
    val id: Long,
    val name: String,
    val size: Long?,
    val status: TransferStatus,
    val bytes: Long = 0,
    val error: UiText? = null,
    val partialRemote: Boolean = false,
)

/** The queue is bound to one controller/profile/node/directory for its whole lifetime. */
class TransferTarget(
    val profileId: String,
    val profileName: String,
    val nodeId: String,
    val nodeName: String,
    val directory: String,
    val controller: FilesController,
) {
    val displayName: String
        get() = when {
            profileName.isBlank() -> nodeName
            nodeName.isBlank() -> profileName
            else -> "$profileName · $nodeName"
        }

    internal fun sameBinding(other: TransferTarget): Boolean = controller === other.controller &&
        profileId == other.profileId && nodeId == other.nodeId && directory == other.directory
}

/**
 * Runtime-only input for one upload. [uri] must be a content URI from an explicit share/open grant.
 * The queue never logs or persists it. [toString] intentionally omits the URI.
 */
class TransferItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long? = null,
) {
    override fun toString(): String = "TransferItem(id=$id, name=$name, size=$size)"
}

internal fun interface QueuedUploader {
    suspend fun upload(
        target: TransferTarget,
        item: TransferItem,
        onProgress: (Long) -> Unit,
    ): TransferOutcome
}

private object ControllerUploader : QueuedUploader {
    override suspend fun upload(
        target: TransferTarget,
        item: TransferItem,
        onProgress: (Long) -> Unit,
    ): TransferOutcome = target.controller.uploadQueued(item.uri, item.name, target.directory, onProgress)
}

/**
 * Sequential upload queue: at most one [FilesController.uploadQueued] call is active and at most
 * [MAX_FILES] rows are retained. A failed item never changes later items into successes; queued
 * items are only sent after an explicit root confirmation passed to [enqueueConfirmed].
 */
class TransferQueue internal constructor(
    scope: CoroutineScope,
    private val uploader: QueuedUploader,
) {
    constructor(scope: CoroutineScope) : this(scope, ControllerUploader)

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val child = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO,
    )
    private val _state = MutableStateFlow<List<QueuedTransfer>>(emptyList())
    val state: StateFlow<List<QueuedTransfer>> = _state.asStateFlow()

    /** For a root ForegroundService: an upload is currently talking to the controller. */
    val active: Boolean get() = _state.value.any { it.status == TransferStatus.Active }

    /** For a root ForegroundService: queued or active rows still need the service alive. */
    val hasPending: Boolean get() = _state.value.any {
        it.status == TransferStatus.Queued || it.status == TransferStatus.Active
    }

    @Volatile
    private var boundTarget: TransferTarget? = null
    private var activeId: Long? = null
    private var activeJob: Job? = null
    private var pump: Job? = null
    // Runtime only: the content URI lives here, never in QueuedTransfer/state. Cleared on close and
    // for rows removed by clearFinished(); max 20 entries by the queue bound.
    private val inputs = HashMap<Long, TransferItem>()

    val target: TransferTarget? get() = boundTarget

    /** Explicit retry may replace a dead transport, but never its profile, device, protocol or folder. */
    fun rebindForRetry(target: TransferTarget): Boolean = synchronized(lock) {
        val previous = boundTarget ?: return@synchronized false
        val connection = target.controller.state.value
        if (closed.get() || hasPending || !connection.connected || connection.busy ||
            target.profileId != previous.profileId || target.nodeId != previous.nodeId ||
            target.directory != previous.directory || target.directory != connection.path ||
            connection.sftp != previous.controller.state.value.sftp) return@synchronized false
        boundTarget = target
        true
    }

    init {
        // Parent/queue scope cancellation must leave no row stuck as active and no child job alive.
        child.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(lock) {
                _state.value = _state.value.map { row ->
                    if (row.status == TransferStatus.Queued) {
                        row.copy(status = TransferStatus.Cancelled, partialRemote = false)
                    } else if (row.status == TransferStatus.Active) {
                        row.copy(status = TransferStatus.Cancelled, partialRemote = true)
                    } else row
                }
                activeId = null
                activeJob = null
            }
        }
    }

    /**
     * Atomic acceptance of an already-confirmed batch. Returns false for an empty batch, more than
     * [MAX_FILES] retained rows, duplicate ids, invalid content URI/name, a changed controller path,
     * a different binding, or a closed queue. No partial batch is accepted.
     */
    fun enqueueConfirmed(target: TransferTarget, items: List<TransferItem>): Boolean {
        if (!child.isActive || items.isEmpty() || items.size > MAX_FILES) return false
        if (target.directory != target.controller.state.value.path) return false
        val ids = HashSet<Long>(items.size)
        for (item in items) {
            if (item.uri.scheme != ContentResolver.SCHEME_CONTENT) return false
            if (!AgentFilesProtocol.validName(item.name)) return false
            if (item.size != null && item.size < 0) return false
            if (!ids.add(item.id)) return false
        }
        var startPump = false
        val accepted = synchronized(lock) {
            if (closed.get()) return@synchronized false
            val existing = boundTarget
            if (existing != null && !existing.sameBinding(target)) return@synchronized false
            if (_state.value.size + items.size > MAX_FILES) return@synchronized false
            if (_state.value.any { it.id in ids }) return@synchronized false
            if (existing == null) boundTarget = target
            items.forEach { inputs[it.id] = it }
            _state.value = _state.value + items.map {
                QueuedTransfer(id = it.id, name = it.name, size = it.size, status = TransferStatus.Queued)
            }
            startPump = pump?.isActive != true
            true
        }
        if (accepted && startPump) ensurePump()
        return accepted
    }

    /** Cancels a queued row without sending it, or safely cancels the one active operation. */
    fun cancel(id: Long): Boolean {
        var job: Job? = null
        val accepted = synchronized(lock) {
            val row = _state.value.firstOrNull { it.id == id } ?: return@synchronized false
            when (row.status) {
                TransferStatus.Queued -> {
                    replace(id) { it.copy(status = TransferStatus.Cancelled, partialRemote = false) }
                    true
                }
                TransferStatus.Active -> {
                    job = activeJob
                    job != null
                }
                else -> false
            }
        }
        job?.cancel()
        return accepted
    }

    fun cancelAll(): Boolean {
        var job: Job? = null
        val changed = synchronized(lock) {
            if (closed.get()) return@synchronized false
            var any = false
            _state.value = _state.value.map { row ->
                if (row.status == TransferStatus.Queued) {
                    any = true
                    row.copy(status = TransferStatus.Cancelled, partialRemote = false)
                } else {
                    if (row.status == TransferStatus.Active) job = activeJob
                    row
                }
            }
            any || job != null
        }
        job?.cancel()
        return changed
    }

    /** Explicit retry after the UI showed the partial-overwrite warning again. */
    fun retry(id: Long): Boolean {
        var startPump = false
        val accepted = synchronized(lock) {
            if (closed.get()) return@synchronized false
            val row = _state.value.firstOrNull { it.id == id } ?: return@synchronized false
            if (row.status != TransferStatus.Failed && row.status != TransferStatus.Cancelled) {
                return@synchronized false
            }
            replace(id) {
                it.copy(
                    status = TransferStatus.Queued,
                    bytes = 0,
                    error = null,
                    partialRemote = false,
                )
            }
            startPump = pump?.isActive != true
            true
        }
        if (accepted && startPump) ensurePump()
        return accepted
    }

    fun clearFinished(): Int = synchronized(lock) {
        val removed = _state.value.filter {
            it.status != TransferStatus.Queued && it.status != TransferStatus.Active
        }
        removed.forEach { inputs.remove(it.id) }
        _state.value = _state.value.filter {
            it.status == TransferStatus.Queued || it.status == TransferStatus.Active
        }
        removed.size
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        var job: Job? = null
        var worker: Job? = null
        synchronized(lock) {
            _state.value = _state.value.map { row ->
                when (row.status) {
                    TransferStatus.Queued -> row.copy(status = TransferStatus.Cancelled, partialRemote = false)
                    TransferStatus.Active -> row.copy(status = TransferStatus.Cancelled, partialRemote = true)
                    else -> row
                }
            }
            job = activeJob
            worker = pump
            activeId = null
            activeJob = null
            inputs.clear()
        }
        job?.cancel()
        worker?.cancel()
        child.cancel()
    }

    private fun ensurePump() {
        synchronized(lock) {
            if (closed.get() || pump?.isActive == true) return
            val next = child.launch(start = CoroutineStart.LAZY) {
                try { runPump() }
                finally {
                    synchronized(lock) { pump = null }
                    // An enqueue can race with the final empty check while this job is still active.
                    if (!closed.get() && child.isActive && hasPending) ensurePump()
                }
            }
            pump = next
            next.start()
        }
    }

    private suspend fun runPump() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val work = synchronized(lock) {
                if (closed.get()) return
                if (activeId != null) return
                _state.value = _state.value.map { row ->
                    if (row.status == TransferStatus.Queued && !inputs.containsKey(row.id)) {
                        row.copy(
                            status = TransferStatus.Failed,
                            error = UiText.resource(R.string.transfer_upload_failed),
                        )
                    } else row
                }
                val next = _state.value.firstOrNull { it.status == TransferStatus.Queued } ?: return
                val item = requireNotNull(inputs[next.id])
                replace(next.id) {
                    it.copy(
                        status = TransferStatus.Active,
                        bytes = 0,
                        error = null,
                        partialRemote = false,
                    )
                }
                activeId = next.id
                val target = boundTarget
                val job = target?.let {
                    child.launch(start = CoroutineStart.LAZY) {
                        val outcome = try {
                            uploader.upload(it, item) { bytes -> updateProgress(next.id, bytes) }
                        } catch (_: CancellationException) {
                            TransferOutcome.Cancelled(partialRemote = false)
                        } catch (_: Exception) {
                            TransferOutcome.Failed(UiText.resource(R.string.transfer_upload_failed))
                        }
                        finish(next.id, outcome)
                    }
                }
                activeJob = job
                // Start before releasing the lock: a cancel() cannot miss a still-lazy job.
                job?.start()
                Triple(next, job, target)
            }
            val job = work.second
            val target = work.third
            if (job == null || target == null) {
                finish(work.first.id, TransferOutcome.Failed(UiText.resource(R.string.transfer_upload_failed)))
                synchronized(lock) { activeId = null; activeJob = null }
                continue
            }
            job.join()
            synchronized(lock) {
                // Cancellation before a lazy child gets CPU never enters its try/catch body.
                if (_state.value.any { it.id == work.first.id && it.status == TransferStatus.Active }) {
                    finish(work.first.id, TransferOutcome.Cancelled(partialRemote = true))
                }
                if (activeId == work.first.id) activeId = null
                if (activeJob === work.second) activeJob = null
            }
        }
    }

    private fun updateProgress(id: Long, bytes: Long) {
        if (bytes < 0) return
        synchronized(lock) {
            replace(id) {
                if (it.status == TransferStatus.Active) it.copy(bytes = bytes) else it
            }
        }
    }

    private fun finish(id: Long, outcome: TransferOutcome) {
        synchronized(lock) {
            replace(id) { row ->
                when (outcome) {
                    is TransferOutcome.Success -> if (row.status == TransferStatus.Cancelled) row else row.copy(
                        status = TransferStatus.Succeeded,
                        bytes = outcome.bytes,
                        error = null,
                        partialRemote = false,
                    )
                    is TransferOutcome.Failed -> if (row.status == TransferStatus.Cancelled) row else row.copy(
                        status = TransferStatus.Failed,
                        error = outcome.error,
                        partialRemote = row.partialRemote,
                    )
                    is TransferOutcome.Cancelled -> row.copy(
                        status = TransferStatus.Cancelled,
                        partialRemote = outcome.partialRemote,
                    )
                }
            }
        }
    }

    private fun replace(id: Long, block: (QueuedTransfer) -> QueuedTransfer) {
        _state.value = _state.value.map { if (it.id == id) block(it) else it }
    }

    companion object {
        const val MAX_FILES = 20
    }
}

/** Root convenience beside the queue's own properties. */
val List<QueuedTransfer>.active: Boolean
    get() = any { it.status == TransferStatus.Active }

/** Root convenience beside the queue's own properties. */
val List<QueuedTransfer>.hasPending: Boolean
    get() = any { it.status == TransferStatus.Queued || it.status == TransferStatus.Active }
