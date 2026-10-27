package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.transfer.TransferOutcome
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Queue inputs stay process-local; SAF permissions are leased until replacement or close. */
class FilesBatch(private val controller: FilesController, scope: CoroutineScope, context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cleanup = DownloadDocumentCleanup(context)
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val lock = Any()
    private val mutable = MutableStateFlow(DownloadBatchState())
    val state = mutable.asStateFlow()
    private var model: DownloadQueueModel? = null
    private var entries: List<RemoteFile> = emptyList()
    private var path = ""
    private var tree: Uri? = null
    private var document: Uri? = null
    private var upload: Pair<Uri, String>? = null
    private var single = false
    private var retryPicker: Int? = null
    private var pickerPending = false
    private var pickerDocument = false
    private var task: Job? = null
    private var disposed = false
    private val grants = mutableListOf<SafGrantLease>()
    private data class Archive(val parent: String, val name: String)
    private val archives = mutableMapOf<Int, Archive>()
    private val perFileFallback = mutableSetOf<Int>()
    private var localWarning: UiText? = null
    private class ItemFailure(val remote: String, val detail: UiText? = null) : java.io.IOException("Unverified item")

    /** Idle is published from finally just before its Job completes. Do not lose that first tap. */
    private suspend fun awaitIdleJobCompletion(): Boolean {
        val previous = synchronized(lock) {
            if (disposed || pickerPending || mutable.value.pending || controller.state.value.batchBusy) return false
            task
        }
        // Never join while holding lock: the finishing task may still need it.
        previous?.join()
        return synchronized(lock) {
            !disposed && task === previous && !pickerPending && !mutable.value.pending && !controller.state.value.batchBusy
        }
    }

    suspend fun prepare(selection: List<RemoteFile>, singleDocument: Boolean = false): Boolean {
        if (!awaitIdleJobCompletion()) return false
        val snapshot = controller.state.value
        if (snapshot.androidPhone || !snapshot.connected || snapshot.busy || snapshot.batchBusy ||
            selection.isEmpty() || selection.size > 1000 || selection.distinctBy { it.name }.size != selection.size ||
            selection.any { !it.actionable || it.type == 1 || it !in snapshot.entries } ||
            (singleDocument && (selection.size != 1 || selection[0].directory))) return false
        synchronized(lock) { if (disposed || pickerPending || mutable.value.pending || task?.isCompleted == false) return false }
        try { controller.reserveBatch(true, expectedPath = snapshot.path) } catch (_: Exception) { return false }
        discardUnstartedDocument()
        releaseGrants()
        synchronized(lock) {
            entries = selection.toList(); path = snapshot.path; tree = null; document = null; model = null; upload = null
            single = singleDocument; retryPicker = null; pickerPending = true; pickerDocument = singleDocument
            archives.clear(); perFileFallback.clear(); localWarning = null
            mutable.value = DownloadBatchState(choosing = true, singleDocument = single)
        }
        return true
    }

    /** Activity destruction must not cancel an already delivered picker result. */
    fun choose(uri: Uri?, document: Boolean = false) { worker.launch(NonCancellable) { chosen(uri, document) } }

    suspend fun chosen(uri: Uri?, isDocument: Boolean = false) = withContext(Dispatchers.IO + NonCancellable) {
        val accept = synchronized(lock) {
            if (!pickerPending) return@withContext
            pickerPending = false
            !disposed && mutable.value.choosing
        }
        if (!accept) {
            if (pickerDocument && isDocument && uri != null) cleanup.discard(uri)
            return@withContext
        }
        if (uri == null || uri.scheme != "content" || (!isDocument && !DocumentsContract.isTreeUri(uri))) {
            synchronized(lock) { mutable.value = model?.state ?: DownloadBatchState(); retryPicker = null }
            controller.reserveBatch(false)
            return@withContext
        }
        val grant = try { SafGrantLease.acquire(appContext, uri, write = true) } catch (_: Exception) {
            if (isDocument) cleanup.discard(uri)
            synchronized(lock) { mutable.value = model?.state ?: DownloadBatchState(); retryPicker = null }
            controller.batchMessage(R.string.review_grant_failed); controller.reserveBatch(false)
            return@withContext
        }
        synchronized(lock) {
            if (disposed) { if (isDocument) cleanup.discard(uri); grant.close(); return@withContext }
            grants += grant
            if (isDocument) document = uri else tree = uri
            if (model == null) model = DownloadQueueModel(entries.map { it.displayName }, singleDocument = single)
            retryPicker?.let { model?.retry(it) }; retryPicker = null
            mutable.value = requireNotNull(model).state
        }
        start(reconnect = !controller.state.value.connected)
    }

    internal suspend fun upload(uri: Uri, name: String, directory: String) {
        synchronized(lock) { task }?.join()
        discardUnstartedDocument()
        releaseGrants()
        val grant = try { SafGrantLease.acquire(appContext, uri, write = false) } catch (_: Exception) {
            controller.batchMessage(R.string.review_grant_failed); controller.reserveBatch(false); return
        }
        val accepted = synchronized(lock) {
            if (disposed || mutable.value.pending || controller.state.value.path != directory || controller.state.value.busy) false
            else {
                grants += grant; upload = uri to name; path = directory; tree = null; document = null; single = false; entries = emptyList()
                model = DownloadQueueModel(listOf(name), uploading = true); mutable.value = requireNotNull(model).state; true
            }
        }
        if (accepted) start(reconnect = false)
        else { grant.close(); controller.reserveBatch(false); controller.batchMessage(R.string.transfer_error_busy) }
    }

    suspend fun prepareSingleRetry(index: Int): String? {
        if (!awaitIdleJobCompletion()) return null
        synchronized(lock) {
            if (disposed || pickerPending || !single || mutable.value.pending || task?.isCompleted == false ||
                model?.state?.rows?.getOrNull(index)?.status !in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled, DownloadStatus.Paused)) return null
        }
        try { controller.reserveBatch(true) } catch (_: Exception) { return null }
        discardUnstartedDocument()
        releaseGrants()
        synchronized(lock) { document = null; retryPicker = index; pickerPending = true; pickerDocument = true; mutable.value = requireNotNull(model).state.copy(choosing = true) }
        return entries[index].name
    }

    /** index == null retries all unfinished items after one explicit reconnect/authentication. */
    suspend fun retry(index: Int? = null): Boolean {
        if (!awaitIdleJobCompletion()) return false
        synchronized(lock) {
            if (disposed || single || mutable.value.pending || task?.isCompleted == false || (tree == null && upload == null)) return false
        }
        try { controller.reserveBatch(true) } catch (_: Exception) { return false }
        val accepted = synchronized(lock) {
            if (disposed || task?.isCompleted == false) false else {
                val accepted = if (index == null) model?.retryAll() == true else model?.retry(index) == true
                if (accepted) mutable.value = requireNotNull(model).state
                accepted
            }
        }
        if (!accepted) { controller.reserveBatch(false); return false }
        start(reconnect = true)
        return true
    }

    private fun publish(block: (DownloadQueueModel) -> Unit) = synchronized(lock) {
        model?.let { block(it); mutable.value = it.state }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start(reconnect: Boolean) {
        synchronized(lock) {
            if (disposed || task?.isCompleted == false) return
            // ATOMIC enters try/finally even when cancelled before the IO dispatcher runs it.
            task = worker.launch(start = CoroutineStart.ATOMIC) {
                try {
                    controller.reserveBatch(true)
                    if (reconnect && !controller.state.value.connected && !controller.reconnectForBatch()) {
                        publish { it.pauseQueued() }; return@launch
                    }
                    while (currentCoroutineContext().isActive) {
                        if (!controller.state.value.connected) { publish { it.pauseQueued() }; break }
                        val index = synchronized(lock) { model?.next().also { mutable.value = requireNotNull(model).state } } ?: break
                        localWarning = null
                        try {
                            val bytes = runItem(index)
                            val warning = joinWarnings(localWarning, cleanArchives())
                            publish { it.succeed(index, bytes, warning) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            val failure = error as? ItemFailure
                            val remote = failure?.remote ?: AgentFilesProtocol.join(path, entries.getOrNull(index)?.name ?: upload?.second.orEmpty())
                            val location = UiText.resource(R.string.review_file_failed, RemoteFile(remote, 3).displayName)
                            val detail = failure?.detail?.let { UiText.resource(R.string.review_detail_join, location, it) } ?: location
                            publish { it.fail(index, detail) }
                            cleanArchives()
                            if (!controller.state.value.connected) { publish { it.pauseQueued() }; break }
                        }
                    }
                    if (controller.state.value.connected && !controller.state.value.busy) controller.listQueued(path)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { controller.cancelBatchOperation(); cleanArchives() }
                    publish { it.cancel() }
                    throw cancelled
                } catch (_: Exception) {
                    publish { queue ->
                        queue.state.rows.indices.filter { queue.state.rows[it].status == DownloadStatus.Active }.forEach { queue.fail(it) }
                        queue.pauseQueued()
                    }
                } finally {
                    withContext(NonCancellable) { cleanArchives(); discardUnstartedDocument(); controller.reserveBatch(false) }
                }
            }
        }
    }

    private suspend fun runItem(index: Int): Long {
        val uploading = upload
        if (uploading != null) {
            if (controller.state.value.path != path) controller.listQueued(path)
            val outcome = controller.uploadBatchQueued(uploading.first, uploading.second, path) { report(index, it) }
            if (outcome is TransferOutcome.Cancelled) throw CancellationException("Upload cancelled")
            if (outcome !is TransferOutcome.Success) throw ItemFailure(AgentFilesProtocol.join(path, uploading.second), (outcome as? TransferOutcome.Failed)?.error)
            return outcome.bytes
        }
        val entry = entries[index]
        if (single) {
            val target = synchronized(lock) { requireNotNull(document).also { document = null } }
            // Ownership passes to downloadQueued, including cancellation before its first lock.
            return saveIntoDocument(path, entry, target, null, false) { report(index, it) }
        }
        val destination = requireNotNull(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
        if (!entry.directory) return saveFile(path, entry, root, entry.name, false) { report(index, it) }
        if (!controller.state.value.sftp && index !in perFileFallback) {
            val archive = Archive(path, "meshpilot-${UUID.randomUUID()}.zip")
            archives[index] = archive
            try {
                val remote = controller.archiveQueued(path, entry, archive.name)
                return saveFile(path, remote, root, entry.name + ".zip", true) { report(index, it) }
            } catch (_: FilesController.ArchiveUnavailable) {
                // The controller proved absence after a cancel/listing fence.
                archives.remove(index)
            } catch (_: FilesController.ArchiveInterrupted) {
                // `zipping` was acknowledged, but the archive never arrived (write error or
                // silence). Keep the archive record for cleanup and copy this row per file.
                perFileFallback += index
            } catch (_: FilesController.ListingUnavailable) {
                // The listing needed to prove the archive failed; copy this row per file.
                perFileFallback += index
            }
        }
        perFileFallback += index
        val folder = create(root, DocumentsContract.Document.MIME_TYPE_DIR, entry.name + ".partial")
        var visited = 0
        var completed = 0L
        var firstFailure: ItemFailure? = null
        suspend fun walk(remote: String, local: Uri, depth: Int) {
            if (depth > 32) throw ItemFailure(remote)
            val children = try { controller.listQueued(remote) }
            catch (unavailable: FilesController.ListingUnavailable) { throw ItemFailure(unavailable.remote, unavailable.detail) }
            catch (_: Exception) { throw ItemFailure(remote) }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                val childPath = AgentFilesProtocol.join(remote, child.name)
                if (++visited > 20_000) throw ItemFailure(childPath)
                try {
                    if (!child.actionable || child.type !in setOf(2, 3)) throw ItemFailure(childPath, UiText.resource(R.string.review_skipped, child.displayName))
                    if (child.directory) walk(childPath, create(local, DocumentsContract.Document.MIME_TYPE_DIR, child.name), depth + 1)
                    else if (QueueSpecialFiles.createEmptyLocally(child)) {
                        // A FIFO/device reports size 0; opening it remotely can block the agent
                        // and the SFTP server. The control listing already established emptiness.
                        completed += saveEmpty(local, child.name)
                    } else completed += saveFile(remote, child, local, child.name, false) { report(index, completed + it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (firstFailure == null) firstFailure = error as? ItemFailure ?: ItemFailure(childPath)
                    if (!controller.state.value.connected) throw requireNotNull(firstFailure)
                }
            }
        }
        walk(AgentFilesProtocol.join(path, entry.name), folder, 0)
        firstFailure?.let { throw it }
        if (!renamed(folder, entry.name)) noteNoRename()
        return completed
    }

    private fun report(index: Int, bytes: Long) = synchronized(lock) {
        model?.let { if (it.progress(index, bytes)) mutable.value = it.state }
    }

    /** Cleanup failures are warnings, never reasons to repeat a verified download. */
    private suspend fun cleanArchives(): UiText? {
        var warning: UiText? = null
        for ((index, archive) in archives.toMap()) {
            val remote = AgentFilesProtocol.join(archive.parent, archive.name)
            val removed = try {
                check(controller.state.value.connected && !controller.state.value.busy)
                val current = controller.listQueued(archive.parent).singleOrNull { it.name == archive.name }
                if (current != null) { check(!current.directory); controller.deleteQueued(archive.parent, listOf(current)) }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            archives.remove(index)
            if (!removed) {
                controller.archiveLeft(remote)
                warning = UiText.resource(R.string.review_archive_left, RemoteFile(remote, 3).displayName)
            }
        }
        return warning
    }

    private fun create(parent: Uri, mime: String, name: String): Uri {
        require(AgentFilesProtocol.usableExistingName(name, 3))
        return requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, name))
    }

    private suspend fun saveFile(parent: String, entry: RemoteFile, local: Uri, name: String, zip: Boolean, progress: (Long) -> Unit): Long {
        val destination = create(local, "application/octet-stream", "$name.partial")
        return saveIntoDocument(parent, entry, destination, name, zip, progress)
    }

    /** The control listing proved this entry is zero bytes; never open a FIFO/device remotely. */
    private fun saveEmpty(local: Uri, name: String): Long {
        val destination = create(local, "application/octet-stream", "$name.partial")
        if (!renamed(destination, name)) noteNoRename()
        return 0
    }

    private fun renamed(document: Uri, name: String): Boolean =
        runCatching { DocumentsContract.renameDocument(resolver, document, name) != null }.getOrDefault(false)

    private fun noteNoRename() {
        if (localWarning == null) localWarning = UiText.resource(R.string.review_local_no_rename)
    }

    private fun joinWarnings(first: UiText?, second: UiText?): UiText? = when {
        first == null -> second
        second == null -> first
        else -> UiText.resource(R.string.review_detail_join, first, second)
    }

    private suspend fun saveIntoDocument(parent: String, entry: RemoteFile, destination: Uri, rename: String?, zip: Boolean, progress: (Long) -> Unit): Long {
        var verified = false
        var keep = false
        try {
            val outcome = controller.downloadQueued(parent, entry, destination, progress, archive = zip)
            if (outcome !is TransferOutcome.Success) throw ItemFailure(AgentFilesProtocol.join(parent, entry.name), (outcome as? TransferOutcome.Failed)?.error)
            verified = true
            currentCoroutineContext().ensureActive()
            if (zip && !validArchive(destination)) throw ItemFailure(AgentFilesProtocol.join(parent, entry.name), UiText.resource(R.string.review_zip_invalid))
            if (rename != null) {
                // A provider without rename support must not throw away a verified document.
                // Keep it under the .partial name and tell the user how to finish the rename.
                if (!renamed(destination, rename)) noteNoRename()
                keep = true
            }
            return outcome.bytes
        } catch (error: Exception) {
            // The controller exclusively owns cleanup while a stream is open. Only verified,
            // closed documents belong here (e.g. failed ZIP validation or rename).
            if (verified && !keep) cleanup.discard(destination)
            throw error
        }
    }

    private fun validArchive(uri: Uri): Boolean = runCatching {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(resolver.openFileDescriptor(uri, "r")!!).use { input ->
            val channel = input.channel
            run {
                ZipArchiveValidator.validate(object : ArchiveBytes {
                    override val size = channel.size()
                    override fun read(offset: Long, length: Int): ByteArray {
                        val bytes = ByteArray(length); val buffer = ByteBuffer.wrap(bytes); var position = offset
                        while (buffer.hasRemaining()) { val count = channel.read(buffer, position); check(count > 0); position += count }
                        return bytes
                    }
                })
            }
        }
    }.getOrDefault(false)

    fun cancel() {
        synchronized(lock) {
            if (mutable.value.choosing) {
                mutable.value = model?.state ?: DownloadBatchState(); retryPicker = null
                worker.launch { controller.reserveBatch(false) }
            }
            task?.cancel()
            model?.cancel(); mutable.value = model?.state ?: DownloadBatchState()
        }
    }

    private fun releaseGrants() { synchronized(lock) { grants.forEach { it.close() }; grants.clear() } }

    private fun discardUnstartedDocument() {
        val pending = synchronized(lock) { document.also { document = null } }
        if (pending != null) cleanup.discard(pending)
    }

    internal suspend fun closeAndJoin() = withContext(NonCancellable) {
        val running = synchronized(lock) { disposed = true; task?.also { it.cancel() } }
        running?.join()
        cleanArchives()
        discardUnstartedDocument()
        releaseGrants()
        synchronized(lock) {
            tree = null; document = null; upload = null; entries = emptyList()
            model?.cancel(); mutable.value = model?.state ?: DownloadBatchState()
        }
        worker.cancel()
    }
}
