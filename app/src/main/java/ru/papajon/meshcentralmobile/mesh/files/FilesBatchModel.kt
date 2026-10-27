package ru.papajon.meshcentralmobile.mesh.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject

/** One delete request per recursive flag; a file that became a folder must never inherit `rec`. */
internal object DeletionPlan {
    fun groups(entries: List<RemoteFile>): List<List<RemoteFile>> =
        entries.groupBy { it.directory }.values.toList()

    /** Type identity only: a growing log keeps the same type and may still be deleted. */
    fun unchanged(expected: RemoteFile, fresh: RemoteFile?): Boolean =
        fresh != null && fresh.actionable && fresh.type == expected.type
}

/** A zero-length regular entry may be a FIFO/device on the agent; never open it remotely. */
internal object QueueSpecialFiles {
    fun createEmptyLocally(entry: RemoteFile): Boolean =
        !entry.directory && entry.type == 3 && entry.sizeKnown && entry.size == 0L
}

/** ZIP activity resets the idle deadline; a started archive has no separate absolute deadline. */
internal suspend fun awaitArchiveCompletion(
    done: CompletableDeferred<Unit>,
    activity: Channel<Unit>,
    timeoutMillis: Long,
): Boolean {
    require(timeoutMillis > 0)
    while (true) {
        val event = select<Int> {
            done.onAwait { 0 }
            activity.onReceive { 1 }
            onTimeout(timeoutMillis) { 2 }
        }
        when (event) {
            0 -> return true
            2 -> return false
            else -> Unit
        }
    }
}

/** Selection is scoped to one listing, never a path supplied by a later screen. */
class FileSelection {
    private var path: String? = null
    var names: Set<String> = emptySet()
        private set
    fun toggle(directory: String, entry: RemoteFile) {
        if (!entry.actionable || entry.type == 1) return
        if (path != directory) { path = directory; names = emptySet() }
        names = if (entry.name in names) names - entry.name else names + entry.name
    }
    fun reconcile(directory: String, entries: List<RemoteFile>) {
        names = if (path != directory) emptySet() else names.intersect(entries.filter { it.actionable && it.type != 1 }.map { it.name }.toSet())
        path = directory
    }
    fun clear() { names = emptySet() }
}

enum class DownloadStatus { Queued, Active, Succeeded, Failed, Cancelled, Paused }
data class DownloadRow(val name: String, val status: DownloadStatus = DownloadStatus.Queued, val bytes: Long = 0, val detail: ru.papajon.meshcentralmobile.localization.UiText? = null)
data class DownloadBatchState(val rows: List<DownloadRow> = emptyList(), val choosing: Boolean = false, val uploading: Boolean = false, val singleDocument: Boolean = false) {
    val completed: Int get() = rows.count { it.status == DownloadStatus.Succeeded }
    val bytes: Long get() = rows.sumOf { it.bytes }
    val pending: Boolean get() = choosing || rows.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Active }
}

/** No I/O and no implicit retry. The controller serializes access. */
class DownloadQueueModel(names: List<String>, uploading: Boolean = false, singleDocument: Boolean = false, private val now: () -> Long = System::nanoTime) {
    init { require(names.size <= 1000) }
    var state = DownloadBatchState(names.map(::DownloadRow), uploading = uploading, singleDocument = singleDocument)
        private set
    private var lastProgress = Long.MIN_VALUE
    fun next(): Int? {
        if (state.rows.any { it.status == DownloadStatus.Active }) return null
        val index = state.rows.indexOfFirst { it.status == DownloadStatus.Queued }.takeIf { it >= 0 } ?: return null
        lastProgress = Long.MIN_VALUE
        update(index) { it.copy(status = DownloadStatus.Active, bytes = 0, detail = null) }
        return index
    }
    fun progress(index: Int, bytes: Long): Boolean {
        val time = now()
        if (lastProgress != Long.MIN_VALUE && time - lastProgress < 100_000_000) return false
        lastProgress = time
        update(index) { if (it.status == DownloadStatus.Active) it.copy(bytes = bytes.coerceAtLeast(0)) else it }
        return true
    }
    fun succeed(index: Int, bytes: Long, warning: ru.papajon.meshcentralmobile.localization.UiText? = null) = update(index) { it.copy(status = DownloadStatus.Succeeded, bytes = bytes, detail = warning) }
    fun fail(index: Int, detail: ru.papajon.meshcentralmobile.localization.UiText? = null) = update(index) { it.copy(status = DownloadStatus.Failed, bytes = 0, detail = detail) }
    fun pauseQueued() { state = state.copy(rows = state.rows.map { if (it.status == DownloadStatus.Queued) it.copy(status = DownloadStatus.Paused) else it }) }
    fun retryAll(): Boolean {
        if (state.pending) return false
        val ids = state.rows.indices.filter { state.rows[it].status in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled, DownloadStatus.Paused) }
        ids.forEach { index -> update(index) { it.copy(status = DownloadStatus.Queued, bytes = 0, detail = null) } }
        return ids.isNotEmpty()
    }
    fun retry(index: Int): Boolean {
        if (state.pending || state.rows.getOrNull(index)?.status !in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled, DownloadStatus.Paused)) return false
        update(index) { it.copy(status = DownloadStatus.Queued, bytes = 0) }
        return true
    }
    fun cancel() { state = state.copy(rows = state.rows.map { if (it.status == DownloadStatus.Queued || it.status == DownloadStatus.Active) it.copy(status = DownloadStatus.Cancelled, bytes = 0) else it }) }
    private fun update(index: Int, block: (DownloadRow) -> DownloadRow) { state = state.copy(rows = state.rows.mapIndexed { i, row -> if (i == index) block(row) else row }) }
}

enum class ArchiveReply { Ignored, Started, Progress, Closed, Refresh, Failed }
internal object AgentArchiveProtocol {
    fun request(path: String, name: String, output: String): JSONObject {
        require(AgentFilesProtocol.usableExistingName(name, 2) && AgentFilesProtocol.validName(output))
        return JSONObject().put("action", "zip").put("path", path).put("files", JSONArray().put(name)).put("output", output)
    }
    fun reply(message: JSONObject): ArchiveReply = when (message.optString("action")) {
        "refresh" -> ArchiveReply.Refresh
        "dialogmessage" -> when {
            !message.has("msg") -> ArchiveReply.Ignored
            message.isNull("msg") -> ArchiveReply.Closed
            message.optString("msg") == "zipping" -> ArchiveReply.Started
            message.optString("msg") == "zippingFile" -> ArchiveReply.Progress
            message.optString("msg").contains("error", ignoreCase = true) -> ArchiveReply.Failed
            else -> ArchiveReply.Ignored
        }
        else -> ArchiveReply.Ignored
    }
}
