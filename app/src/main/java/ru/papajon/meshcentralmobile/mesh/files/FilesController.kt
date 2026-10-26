package ru.papajon.meshcentralmobile.mesh.files

import ru.papajon.meshcentralmobile.json.optText

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.transfer.TransferOutcome

enum class FileChange { CreateDirectory, Rename, Delete, Upload }

/** In-memory only; never persisted in a Bundle, saved state, log or preferences. */
class FileConfirmation(
    val change: FileChange,
    val path: String,
    val name: String = "",
    val directory: Boolean = false,
    internal val source: Uri? = null,
)

data class FilesState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val path: String = "",
    val entries: List<RemoteFile> = emptyList(),
    val message: UiText? = null,
    val confirmation: FileConfirmation? = null,
    val transferred: Long? = null,
    val transferName: String? = null,
    val sftp: Boolean = false,
    val authRequired: Boolean = false,
    val askKeyPass: Boolean = false,
    val androidPhone: Boolean = false,
)

/** One operation and one chunk in flight. Every retry requires a new explicit user action.
 * All protocol and SAF stream work runs on IO, serialized by a mutex; no per-frame coroutine queue.
 */
class FilesController(connection: RelayConnection, scope: CoroutineScope, context: Context) {
    private val connection = connection
    private val sftp = connection.protocol == RelayProtocol.SshFiles
    private val androidPhone = connection.androidFiles
    private val resolver = context.applicationContext.contentResolver
    private val documentCleanup = DownloadDocumentCleanup(context)
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mutable = MutableStateFlow(FilesState(sftp = sftp, androidPhone = androidPhone))
    val state: StateFlow<FilesState> = mutable.asStateFlow()
    private var sequence = 0
    private var listingId: Int? = null
    private var listingPath = ""
    private var androidDeleteId: String? = null
    private var androidDeletePath: String = ""
    private var verification: ((List<RemoteFile>) -> Boolean)? = null
    private var listingHandler: ((List<RemoteFile>) -> Unit)? = null
    private var timeout: Job? = null
    private var transfer: Transfer? = null
    private var pendingCancel: PendingCancel? = null
    private val selectedDownload = AtomicReference<Pair<String, RemoteFile>?>(null)
    private val pendingPicker = AtomicBoolean(false)
    private val lateDocumentMessage = AtomicReference<UiText?>(null)

    init {
        worker.coroutineContext[Job]?.invokeOnCompletion {
            // Completion may race a picker callback on the UI scope. Serialize even final
            // cleanup, and release the foreground reservation after owner cancellation too.
            scope.launch(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    closed.set(true)
                    completeQueued(TransferOutcome.Cancelled(partialRemote = transferTouchedRemote()))
                    pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = true))
                    pendingCancel = null
                    connection.close()
                    val removed = cleanup()
                    mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null,
                        message = removed?.let { downloadCleanupMessage(text(R.string.files_cancelled), it) } ?: mutable.value.message)
                }
            }
        }
    }

    private class Transfer(
        var id: Int,
        val name: String,
        var input: InputStream? = null,
        var output: OutputStream? = null,
        val destination: Uri? = null,
        var stage: Stage,
        var bytes: Long = 0,
        var outstanding: Int = 0,
        val remotePath: String = "",
        val remoteParent: String = "",
        var expectedSize: Long? = null,
        val digest: java.security.MessageDigest = SftpFilesAdapter.digest(),
        var expectedDigest: ByteArray? = null,
        val queued: CompletableDeferred<TransferOutcome>? = null,
        val progress: ((Long) -> Unit)? = null,
        var remoteTouched: Boolean = false,
        var cancelRequested: Boolean = false,
        var mediaSizeChecks: Int = 0,
    )
    private class PendingCancel(val completion: CompletableDeferred<TransferOutcome>)
    private enum class Stage { DownloadPreflight, DownloadStart, DownloadData, DownloadPostflight, UploadStart, UploadAck, UploadDone, UploadCancel }

    fun connect() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        if (connection.protocol != RelayProtocol.AgentFiles && !sftp) {
            mutable.value = FilesState(message = text(R.string.files_unsupported))
            connection.close()
            return
        }
        mutable.value = mutable.value.copy(busy = true, message = text(R.string.files_connecting))
        // Covers a silent SFTP WebSocket before either sshauth or c; waiting for user input is exempt.
        armTimeout()
        worker.launch {
            try {
                connection.incoming.collect { frame ->
                    mutex.withLock {
                        if (!closed.get()) guarded {
                            when (frame) {
                                is RelayFrame.Text -> receiveText(frame.value)
                                is RelayFrame.Binary -> {
                                    val bytes = frame.value
                                    if (sftp && bytes.size == 1 && bytes[0] == 'c'.code.toByte()) {
                                        receiveText("c")
                                    } else if (bytes.firstOrNull() == '{'.code.toByte()) {
                                        if (bytes.size > AgentFilesProtocol.MAX_JSON_BYTES) fail(R.string.files_directory_failed, preferReason = true)
                                        else receiveJson(bytes.toString(Charsets.UTF_8))
                                    } else receiveDownload(bytes)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutex.withLock { fail(R.string.files_disconnected) } }
        }
        worker.launch {
            connection.state.collect { status ->
                mutex.withLock {
                    when (status.phase) {
                        RelayPhase.Connected -> if (!sftp && !mutable.value.connected && !closed.get()) {
                            mutable.value = mutable.value.copy(connected = true)
                            guarded { list("") }
                        }
                        RelayPhase.Closed, RelayPhase.Failed -> if (!closed.get()) {
                            if (listingId != null && (status.message as? UiText.Resource)?.id == R.string.relay_buffer_limit) {
                                fail(R.string.files_directory_failed, preferReason = true)
                            } else fail(R.string.files_disconnected)
                        }
                        else -> Unit
                    }
                }
            }
        }
        connection.connect()
    }

    fun browse(path: String) = action {
        if (available()) list(path)
    }

    fun authenticatePassword(username: String, password: String) = authenticate {
        require(username.isNotBlank() && username.length <= 256 && password.length <= 16_384)
        SftpFilesAdapter.password(username, password)
    }
    fun authenticateKey(username: String, key: String, passphrase: String) = authenticate {
        require(username.isNotBlank() && username.length <= 256 && key.length in 1..65_536 && passphrase.length <= 16_384)
        SftpFilesAdapter.privateKey(username, key, passphrase)
    }
    fun authenticateSavedKey(passphrase: String) = authenticate {
        require(mutable.value.askKeyPass && passphrase.length <= 16_384)
        SftpFilesAdapter.savedKey(passphrase)
    }
    private fun authenticate(message: () -> JSONObject) = action {
        if (!sftp || !mutable.value.authRequired) return@action
        send(message())
        mutable.value = mutable.value.copy(authRequired = false, busy = true, message = text(R.string.files_connecting))
        armTimeout()
    }
    fun parent() = browse(AgentFilesProtocol.parent(state.value.path))
    fun refresh() = browse(state.value.path)
    fun open(entry: RemoteFile) {
        if (entry.directory && entry.actionable && entry in state.value.entries) browse(AgentFilesProtocol.join(state.value.path, entry.name))
    }

    /** The selection stays with the session across Activity recreation, never in a Bundle. */
    suspend fun prepareDownload(entry: RemoteFile): Boolean = mutex.withLock {
        val snapshot = state.value
        if (closed.get() || !worker.coroutineContext[Job]!!.isActive || !snapshot.connected || snapshot.busy ||
            entry.directory || !entry.actionable || entry !in snapshot.entries) return@withLock false
        selectedDownload.set(snapshot.path to entry)
        pendingPicker.set(true)
        // The picker covers our Activity. Reserve the operation now so the foreground
        // transfer service protects the sockets before a destination URI exists.
        mutable.value = snapshot.copy(busy = true, transferName = entry.name, transferred = null, message = null)
        true
    }

    suspend fun downloadPreparationFailed() = withContext(NonCancellable) { mutex.withLock {
        pendingPicker.set(false)
        if (selectedDownload.getAndSet(null) == null || closed.get()) return@withLock
        mutable.value = mutable.value.copy(busy = false, transferName = null, transferred = null,
            message = text(R.string.workspace_background_unavailable))
    } }

    suspend fun downloadChosen(destination: Uri?) = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        val ownedPicker = pendingPicker.getAndSet(false)
        val selected = selectedDownload.getAndSet(null)
        if (destination == null) {
            if (selected != null && !closed.get()) {
                mutable.value = mutable.value.copy(busy = false, transferName = null, transferred = null)
            }
            return@withLock
        }
        if (closed.get() || selected == null) {
            // Only this controller's outstanding ACTION_CREATE_DOCUMENT callback owns the URI.
            // An unsolicited callback or non-content URI must never delete an unrelated file.
            val removed = if (ownedPicker) documentCleanup.discard(destination) else null
            val message = removed?.let { downloadCleanupMessage(text(R.string.files_disconnected), it) }
                ?: text(R.string.files_local_partial)
            if (closed.get()) lateDocumentMessage.set(message)
            mutable.value = mutable.value.copy(message = message)
            return@withLock
        }
        guarded { beginDownload(AgentFilesProtocol.join(selected.first, selected.second.name), selected.second.name, destination, fromPicker = true) }
    } }

    /**
     * Explicitly confirmed batch upload with no dependency on [FilesState.confirmation].
     *
     * The call is serialized with every other controller operation. [directory] must be the exact
     * current controller path; it is rechecked before any bytes are sent, so a late target cannot be
     * silently used. The stream is always opened from the start: a retry never resumes an offset.
     * Success is returned only by the audited uploaddone -> read-back SHA-256/size -> postflight
     * path; a status message is never interpreted as success.
     */
    suspend fun uploadQueued(
        uri: Uri,
        name: String,
        directory: String,
        onProgress: (Long) -> Unit = {},
    ): TransferOutcome {
        if (closed.get()) return TransferOutcome.Failed(text(R.string.transfer_error_closed))
        if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) {
            return TransferOutcome.Failed(text(R.string.transfer_error_content_uri))
        }
        if (!AgentFilesProtocol.validName(name)) {
            return TransferOutcome.Failed(text(R.string.files_invalid_name))
        }

        val completion = CompletableDeferred<TransferOutcome>()
        var started = false
        mutex.withLock {
            if (closed.get()) {
                completion.complete(TransferOutcome.Failed(text(R.string.transfer_error_closed)))
                return@withLock
            }
            if (directory != mutable.value.path) {
                completion.complete(TransferOutcome.Failed(text(R.string.transfer_error_path_changed)))
                return@withLock
            }
            if (!mutable.value.connected) {
                completion.complete(TransferOutcome.Failed(text(R.string.files_disconnected)))
                return@withLock
            }
            if (androidPhone && directory.isEmpty()) {
                completion.complete(TransferOutcome.Failed(text(R.string.phone_files_choose_category)))
                return@withLock
            }
            if (mutable.value.busy || transfer != null || listingId != null || verification != null ||
                listingHandler != null || mutable.value.confirmation != null) {
                completion.complete(TransferOutcome.Failed(text(R.string.transfer_error_busy)))
                return@withLock
            }
            val stream = try {
                resolver.openInputStream(uri)
            } catch (_: Exception) {
                null
            }
            if (stream == null) {
                completion.complete(TransferOutcome.Failed(text(R.string.transfer_error_stream)))
                return@withLock
            }
            val id = ++sequence
            val current = Transfer(
                id = id,
                name = name,
                input = stream,
                stage = Stage.UploadStart,
                remotePath = AgentFilesProtocol.join(directory, name),
                remoteParent = directory,
                queued = completion,
                progress = onProgress,
            )
            transfer = current
            mutable.value = mutable.value.copy(busy = true, transferred = 0L, transferName = name, message = null)
            reportProgress(current, 0L)
            current.remoteTouched = true
            val sent = try { connection.sendText(
                JSONObject().put("action", "upload").put("path", directory).put("name", name)
                    .put("reqid", id).put("append", false).toString(),
            ) } catch (_: Exception) { false }
            if (!sent) {
                fail(R.string.files_operation_failed)
                return@withLock
            }
            armTimeout()
            started = true
        }

        if (!started || completion.isCompleted) return completion.await()
        return try {
            completion.await()
        } catch (cancelled: CancellationException) {
            // The queue can cancel its child job. Finish the protocol operation without leaving a
            // stream, timer or deferred behind; never turn this path into a success.
            withContext(NonCancellable) { abortQueuedUpload(completion) }
        }
    }

    fun propose(change: FileChange, entry: RemoteFile? = null) = action {
        if (androidPhone && (change == FileChange.CreateDirectory || change == FileChange.Rename ||
            (change == FileChange.Delete && mutable.value.path.isEmpty()))) return@action
        if (!available() || (change == FileChange.Upload)) return@action
        if ((change == FileChange.Rename || change == FileChange.Delete) &&
            (entry == null || entry.type == 1 || !entry.actionable || entry !in mutable.value.entries)) return@action
        mutable.value = mutable.value.copy(
            confirmation = FileConfirmation(change, mutable.value.path, entry?.name.orEmpty(), entry?.directory == true),
            message = null,
        )
    }

    fun proposeUpload(uri: Uri) = action {
        if (!available()) return@action
        if (androidPhone && mutable.value.path.isEmpty()) return@action
        var name = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
        }
        mutable.value = mutable.value.copy(
            confirmation = FileConfirmation(FileChange.Upload, mutable.value.path, name, source = uri), message = null,
        )
    }

    fun dismissConfirmation() = action { mutable.value = mutable.value.copy(confirmation = null) }

    /** Invoked only by the explicit confirmation button, including the upload overwrite warning. */
    fun confirm(name: String) = action {
        val request = mutable.value.confirmation ?: return@action
        if (!mutable.value.connected || mutable.value.busy) return@action
        if (request.change != FileChange.Delete && !AgentFilesProtocol.validName(name)) {
            mutable.value = mutable.value.copy(message = text(R.string.files_invalid_name))
            return@action
        }
        if ((request.change == FileChange.Rename || request.change == FileChange.CreateDirectory) &&
            mutable.value.entries.any { it.name.equals(name, ignoreCase = true) }) {
            mutable.value = mutable.value.copy(message = text(R.string.files_exists))
            return@action
        }
        mutable.value = mutable.value.copy(confirmation = null, busy = true, message = null)
        when (request.change) {
            FileChange.CreateDirectory -> {
                verification = { entries -> entries.any { it.name == name && it.directory } }
                send(JSONObject().put("action", "mkdir").put("path", AgentFilesProtocol.join(request.path, name)))
                list(request.path)
            }
            FileChange.Rename -> {
                verification = { entries -> entries.none { it.name == request.name } && entries.any { it.name == name } }
                send(JSONObject().put("action", "rename").put("path", request.path)
                    .put("oldname", request.name).put("newname", name))
                list(request.path)
            }
            FileChange.Delete -> {
                verification = { entries -> entries.none { it.name == request.name } }
                val message = JSONObject().put("action", "rm").put("path", request.path)
                    .put("delfiles", JSONArray().put(request.name)).put("rec", request.directory)
                if (androidPhone) {
                    androidDeleteId = (++sequence).toString()
                    androidDeletePath = request.path
                    message.put("reqid", androidDeleteId)
                }
                send(message)
                if (androidPhone) armTimeout() else list(request.path)
            }
            FileChange.Upload -> {
                val stream = requireNotNull(resolver.openInputStream(requireNotNull(request.source)))
                val id = ++sequence
                transfer = Transfer(id, name, input = stream, stage = Stage.UploadStart,
                    remotePath = AgentFilesProtocol.join(request.path, name), remoteParent = request.path)
                mutable.value = mutable.value.copy(transferred = 0L, transferName = name)
                send(JSONObject().put("action", "upload").put("path", request.path).put("name", name)
                    .put("reqid", id).put("append", false))
                armTimeout()
            }
        }
    }

    /** destination must be a new document returned by ACTION_CREATE_DOCUMENT. */
    fun download(remotePath: String, name: String, destination: Uri) = action {
        beginDownload(remotePath, name, destination, fromPicker = false)
    }

    private fun beginDownload(remotePath: String, name: String, destination: Uri, fromPicker: Boolean) {
        val snapshot = mutable.value
        val ready = if (fromPicker) snapshot.connected && snapshot.busy && snapshot.transferName == name &&
            snapshot.confirmation == null && transfer == null else available()
        if (!ready) {
            val message = documentCleanup.discard(destination)?.let {
                downloadCleanupMessage(text(R.string.files_disconnected), it)
            } ?: text(R.string.files_local_partial)
            mutable.value = if (fromPicker && transfer == null) mutable.value.copy(busy = false, transferName = null,
                transferred = null, message = message)
            else mutable.value.copy(message = message)
            return
        }
        require(AgentFilesProtocol.usableExistingName(name, 3))
        val id = ++sequence
        val current = Transfer(id, name, destination = destination,
            stage = Stage.DownloadPreflight,
            remotePath = remotePath, remoteParent = AgentFilesProtocol.parent(remotePath))
        transfer = current
        current.output = requireNotNull(resolver.openOutputStream(destination, "wt"))
        mutable.value = mutable.value.copy(busy = true, transferred = 0L, transferName = name, message = null)
        prepareRemoteDownload(current)
    }

    fun cancel() = action {
        val current = transfer
        when {
            current?.input != null -> beginUploadCancel(current)
            current != null && current.stage == Stage.UploadCancel -> Unit
            current != null -> {
                // The upload itself is already complete and is being read back. There is no safe
                // remote cancel for that phase; report an honest partial result and end the session.
                completeQueued(TransferOutcome.Cancelled(partialRemote = true))
                fail(if (current.destination != null) R.string.files_cancelled
                    else if (verification != null) R.string.files_change_unverified
                    else if (sftp) R.string.files_sftp_unverified else R.string.files_local_partial)
            }
            pendingCancel != null -> Unit
            else -> fail(if (verification != null) R.string.files_change_unverified
                else R.string.files_cancelled)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        completeQueued(TransferOutcome.Cancelled(partialRemote = transferTouchedRemote()))
        pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = true))
        pendingCancel = null
        connection.close()
        worker.launch {
            mutex.withLock {
                val partial = transfer != null
                val changed = verification != null || transfer?.expectedDigest != null
                val removed = cleanup()
                val reason = text(if (changed) R.string.files_change_unverified else if (partial) R.string.files_cancelled else R.string.files_disconnected)
                mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null,
                    message = lateDocumentMessage.get() ?: removed?.let { downloadCleanupMessage(reason, it) }
                        ?: text(if (changed) R.string.files_change_unverified else if (partial) R.string.files_partial else R.string.files_disconnected))
            }
            worker.cancel()
        }
    }

    private fun available(): Boolean = !closed.get() && mutable.value.connected && !mutable.value.busy && mutable.value.confirmation == null
    private fun action(block: suspend () -> Unit) {
        if (closed.get()) return
        worker.launch { mutex.withLock { if (!closed.get()) guarded(block) } }
    }
    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(R.string.files_operation_failed) }
    }
    private fun send(message: JSONObject) { check(connection.sendText(message.toString())) }
    private fun list(path: String, after: ((List<RemoteFile>) -> Unit)? = null) {
        listingId = ++sequence
        listingPath = path
        listingHandler = after
        mutable.value = mutable.value.copy(busy = true,
            message = if (transfer?.expectedDigest != null) text(R.string.files_sftp_verifying) else null)
        send(JSONObject().put("action", "ls").put("path", path).put("reqid", listingId))
        armTimeout()
    }

    private fun receiveText(raw: String) {
        if (sftp && raw == "c") {
            check(!mutable.value.connected)
            timeout?.cancel()
            mutable.value = mutable.value.copy(connected = true, authRequired = false, askKeyPass = false)
            list("/")
        } else receiveJson(raw)
    }
    private fun armTimeout() {
        timeout?.cancel()
        timeout = worker.launch {
            delay(30_000)
            mutex.withLock { fail(R.string.files_timeout) }
        }
    }

    private fun receiveJson(raw: String) {
        if (!AgentFilesProtocol.jsonWithinLimit(raw)) {
            if (androidPhone && AgentFilesProtocol.isMediaCategory(listingPath) && listingId != null) mediaListingTooLarge()
            else fail(R.string.files_directory_failed, preferReason = true)
            return
        }
        val message = JSONObject(raw)
        if (androidPhone && message.optText("error") == "directory-too-large" && listingId != null &&
            message.optInt("reqid", -1) == listingId && message.optText("path") == listingPath) {
            mediaListingTooLarge()
            return
        }
        if (sftp) when (message.optText("action")) {
            "sshauth" -> {
                check(!mutable.value.connected)
                timeout?.cancel()
                mutable.value = mutable.value.copy(busy = false, authRequired = true,
                    askKeyPass = message.optBoolean("askkeypass"), message = text(R.string.files_sftp_auth_notice))
                return
            }
            "autherror", "connectionerror", "sessiontimeout" -> {
                fail(R.string.files_sftp_auth_failed)
                return
            }
        }
        if (message.has("dir")) {
            if (listingId == null || message.optInt("reqid", -1) != listingId) return
            val entries = try {
                if (sftp) SftpFilesAdapter.entries(message) else AgentFilesProtocol.entries(message,
                    phoneMedia = androidPhone && AgentFilesProtocol.isMediaCategory(listingPath))
            } catch (_: Exception) {
                if (listingHandler != null) { fail(if (sftp) R.string.files_sftp_unverified else R.string.files_operation_failed); return }
                timeout?.cancel()
                listingId = null
                val changed = verification != null
                verification = null
                pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = true))
                pendingCancel = null
                mutable.value = mutable.value.copy(busy = false,
                    message = text(if (changed) R.string.files_change_unverified else R.string.files_directory_failed))
                return
            }
            timeout?.cancel()
            listingId = null
            val after = listingHandler
            listingHandler = null
            if (after != null) {
                mutable.value = mutable.value.copy(path = message.optText("path", listingPath), entries = entries)
                after(entries)
                return
            }
            val result = verification?.invoke(entries)
            verification = null
            pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = result != true))
            pendingCancel = null
            mutable.value = mutable.value.copy(busy = false, path = message.optText("path", listingPath), entries = entries,
                message = result?.let { text(if (it) R.string.files_change_verified else R.string.files_change_unverified) })
            return
        }
        if (message.optText("ctrlChannel") == "102938") return
        if (androidPhone && message.optText("action") == "rm" && androidDeleteId != null &&
            message.optText("reqid") == androidDeleteId) {
            androidDeleteId = null
            // A response alone is not proof: reread even after an agent-reported failure.
            list(androidDeletePath)
            return
        }
        val current = transfer ?: return
        when (message.optText("action")) {
            "download" -> {
                if (message.optInt("id", -1) != current.id ||
                    (current.output == null && current.expectedDigest == null)) return
                when (message.optText("sub")) {
                    "start" -> {
                        check(current.stage == Stage.DownloadStart)
                        current.stage = Stage.DownloadData
                        if (sftp && current.expectedSize == 0L) {
                            finishVerifiedDownload(current)
                            return
                        }
                        send(JSONObject().put("action", "download").put("sub", "startack").put("id", current.id).put("ack", 1))
                        armTimeout()
                    }
                    "cancel" -> fail(if (!androidPhone) R.string.files_operation_failed
                        else if (message.optText("error") == "transport") R.string.final_fixes_download_transport_failed
                        else R.string.final_fixes_download_invalid)
                }
            }
            "uploaderror" -> if (!message.has("reqid") || message.optInt("reqid", -1) == current.id) fail(R.string.files_operation_failed)
            "uploadstart", "uploadack" -> {
                if (message.optInt("reqid", -1) != current.id) return
                if (current.stage == Stage.UploadCancel) return
                val start = message.optText("action") == "uploadstart"
                check(current.stage == if (start) Stage.UploadStart else Stage.UploadAck)
                if (!start) current.bytes += current.outstanding
                mutable.value = mutable.value.copy(transferred = current.bytes)
                reportProgress(current, current.bytes)
                uploadNext(current)
            }
            "uploaddone" -> {
                if (message.optInt("reqid", -1) != current.id) return
                check(current.stage == Stage.UploadDone)
                // Both protocols must prove content, never merely an existing file of the same size.
                // SFTP additionally emits uploaddone on write errors; the stage check rejects that.
                reportProgress(current, current.bytes)
                current.expectedDigest = current.digest.digest()
                current.expectedSize = current.bytes
                current.bytes = 0
                current.id = ++sequence
                current.stage = Stage.DownloadPreflight
                mutable.value = mutable.value.copy(transferred = 0L, message = text(R.string.files_sftp_verifying))
                prepareRemoteDownload(current)
            }
            "uploadcancel" -> {
                if (message.optInt("reqid", -1) != current.id || current.stage != Stage.UploadCancel) return
                current.queued?.let { pendingCancel = PendingCancel(it) }
                cleanupTransfer()
                mutable.value = mutable.value.copy(busy = false, message = text(R.string.files_cancelled))
                verification = { entries -> entries.none { it.name == current.name } }
                list(current.remoteParent)
            }
        }
    }

    private fun mediaListingTooLarge() {
        if (transfer != null || listingHandler != null) {
            fail(R.string.phone_files_directory_too_large, preferReason = true)
            return
        }
        timeout?.cancel()
        listingId = null
        verification = null
        pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = true))
        pendingCancel = null
        mutable.value = mutable.value.copy(busy = false, path = listingPath, entries = emptyList(),
            message = text(R.string.phone_files_directory_too_large))
    }

    private fun uploadNext(current: Transfer) {
        val data = ByteArray(AgentFilesProtocol.CHUNK_SIZE)
        val count = requireNotNull(current.input).read(data)
        if (count < 0) {
            current.input?.close()
            current.input = null
            current.stage = Stage.UploadDone
            reportProgress(current, current.bytes)
            send(JSONObject().put("action", "uploaddone").put("reqid", current.id))
        } else {
            check(count > 0)
            current.digest.update(data, 0, count)
            current.outstanding = count
            current.stage = Stage.UploadAck
            check(connection.sendBinary(AgentFilesProtocol.uploadFrame(data, count)))
        }
        armTimeout()
    }

    private fun receiveDownload(data: ByteArray) {
        val current = transfer ?: return
        check(current.stage == Stage.DownloadData)
        val final = AgentFilesProtocol.downloadFinal(data)
        val nextBytes = current.bytes + data.size - 4
        val expectedSize = requireNotNull(current.expectedSize)
        require(nextBytes in 0..expectedSize && (!final || nextBytes == expectedSize))
        val sizeReached = if (sftp) SftpFilesAdapter.reachedExpectedSize(expectedSize, nextBytes, final) else false
        if (current.expectedDigest != null) current.digest.update(data, 4, data.size - 4)
        else requireNotNull(current.output).write(data, 4, data.size - 4)
        current.bytes += data.size - 4
        mutable.value = mutable.value.copy(transferred = current.bytes)
        if (sftp && sizeReached) {
            finishVerifiedDownload(current)
        } else if (final && (current.expectedDigest != null || androidPhone)) {
            finishVerifiedDownload(current)
        } else if (final) {
            current.output?.close()
            current.output = null
            cleanupTransfer(keepDownload = true)
            mutable.value = mutable.value.copy(busy = false,
                message = text(R.string.files_download_complete))
        } else {
            send(JSONObject().put("action", "download").put("sub", "ack").put("id", current.id))
            armTimeout()
        }
    }

    private fun prepareRemoteDownload(current: Transfer) {
        list(current.remoteParent) { entries ->
            val entry = entries.singleOrNull { it.name == current.name && !it.directory && it.actionable && it.sizeKnown }
            if (entry != null && androidPhone && AgentFilesProtocol.isMediaCategory(current.remoteParent) &&
                current.expectedDigest != null && current.expectedSize != entry.size && current.mediaSizeChecks < 5) {
                // MediaStore publishes the new length asynchronously after uploaddone.
                // Only re-read metadata (at most five times); never repeat the upload or
                // accept a stale size. The subsequent p10 SHA-256 check remains mandatory.
                current.mediaSizeChecks++
                worker.launch {
                    delay(1_000)
                    mutex.withLock {
                        if (!closed.get() && transfer === current && current.stage == Stage.DownloadPreflight) {
                            guarded { prepareRemoteDownload(current) }
                        }
                    }
                }
                return@list
            }
            if (entry == null || (current.expectedSize != null && current.expectedSize != entry.size)) {
                fail(if (sftp) R.string.files_sftp_unverified else R.string.files_operation_failed)
            } else {
                current.expectedSize = entry.size
                startDownload(current)
            }
        }
    }

    private fun startDownload(current: Transfer) {
        current.stage = Stage.DownloadStart
        send(JSONObject().put("action", "download").put("sub", "start").put("path", current.remotePath).put("id", current.id)
            .apply { if (androidPhone) put("size", current.expectedSize) })
        armTimeout()
    }

    private fun finishVerifiedDownload(current: Transfer) {
        check(current.bytes == current.expectedSize)
        // No further ack: exact-size upstream downloads otherwise silently omit their final header.
        if (sftp) send(JSONObject().put("action", "download").put("sub", "stop").put("id", current.id))
        current.output?.close()
        current.output = null
        if (current.expectedDigest != null && !SftpFilesAdapter.matches(requireNotNull(current.expectedDigest), current.digest.digest())) {
            fail(if (sftp) R.string.files_sftp_unverified else R.string.files_change_unverified)
            return
        }
        current.stage = Stage.DownloadPostflight
        list(current.remoteParent) { entries ->
            val unchangedSize = entries.singleOrNull { it.name == current.name && !it.directory && it.actionable &&
                it.sizeKnown && it.size == current.expectedSize } != null
            if (!unchangedSize) fail(if (sftp) R.string.files_sftp_unverified else R.string.files_change_unverified)
            else {
                val verifiedUpload = current.expectedDigest != null
                val bytes = current.expectedSize ?: current.bytes
                reportProgress(current, bytes)
                cleanupTransfer(keepDownload = true)
                current.queued?.complete(TransferOutcome.Success(bytes))
                mutable.value = mutable.value.copy(busy = false, message = text(
                    if (verifiedUpload) R.string.files_sftp_upload_verified else R.string.files_download_complete))
            }
        }
    }

    private fun cleanupTransfer(keepDownload: Boolean = false): DownloadDocumentCleanup.Result? {
        timeout?.cancel()
        var removed: DownloadDocumentCleanup.Result? = null
        transfer?.let { current ->
            runCatching { current.input?.close() }
            runCatching { current.output?.close() }
            if (!keepDownload) removed = current.destination?.let(documentCleanup::discard)
        }
        transfer = null
        mutable.value = mutable.value.copy(transferred = null, transferName = null)
        return removed
    }
    private fun cleanup(): DownloadDocumentCleanup.Result? {
        selectedDownload.set(null)
        val removed = cleanupTransfer()
        listingId = null
        androidDeleteId = null
        verification = null
        listingHandler = null
        return removed
    }
    private fun fail(reason: Int, preferReason: Boolean = false) {
        val failedTransfer = transfer
        val partial = transfer != null
        val changed = verification != null || transfer?.expectedDigest != null
        val unverifiedSftp = sftp && transfer != null
        val removed = cleanup()
        val message = lateDocumentMessage.get() ?: if (removed != null) downloadCleanupMessage(text(reason), removed)
            else text(if (preferReason) reason
            else if (unverifiedSftp) R.string.files_sftp_unverified else if (changed) R.string.files_change_unverified else if (partial) R.string.files_partial else reason)
        if (failedTransfer?.cancelRequested == true) {
            failedTransfer.queued?.complete(TransferOutcome.Cancelled(partialRemote = true))
        } else {
            failedTransfer?.queued?.complete(TransferOutcome.Failed(message))
        }
        pendingCancel?.completion?.complete(TransferOutcome.Cancelled(partialRemote = true))
        pendingCancel = null
        connection.close()
        closed.set(true)
        mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null, authRequired = false,
            message = message)
        worker.cancel()
    }

    private fun downloadCleanupMessage(reason: UiText, result: DownloadDocumentCleanup.Result): UiText =
        UiText.resource(when (result) {
            DownloadDocumentCleanup.Result.Deleted -> R.string.final_fixes_download_removed
            DownloadDocumentCleanup.Result.Marked -> R.string.final_fixes_download_marked
            DownloadDocumentCleanup.Result.Retained -> R.string.final_fixes_download_retained
        }, reason)

    /** Returns true when an uploadcancel request is now awaiting its acknowledgement. */
    private fun beginUploadCancel(current: Transfer): Boolean {
        if (current.stage == Stage.UploadCancel) return true
        val input = current.input ?: return false
        current.cancelRequested = true
        runCatching { input.close() }
        current.input = null
        current.stage = Stage.UploadCancel
        if (!connection.sendText(JSONObject().put("action", "uploadcancel").put("reqid", current.id).toString())) {
            fail(R.string.files_operation_failed)
            return false
        }
        armTimeout()
        return true
    }

    /**
     * Cancellation of the caller's coroutine must still terminate the protocol operation. We send
     * the normal uploadcancel, wait for its verified absence, and close everything if the protocol
     * cannot answer in the same 30-second bound. The returned value is never a fake success.
     */
    private suspend fun abortQueuedUpload(completion: CompletableDeferred<TransferOutcome>): TransferOutcome {
        var awaitingAck = false
        mutex.withLock {
            if (!completion.isCompleted) {
                val current = transfer?.takeIf { it.queued === completion }
                when {
                    current == null -> {
                        if (pendingCancel?.completion === completion) awaitingAck = true
                        else completion.complete(TransferOutcome.Cancelled(partialRemote = false))
                    }
                    current.stage == Stage.UploadCancel -> awaitingAck = true
                    current.input != null -> awaitingAck = beginUploadCancel(current)
                    else -> {
                        // The upload body already ended and its read-back is in flight: there is no
                        // safe remote cancel, so end the session and report a possible replacement.
                        completion.complete(TransferOutcome.Cancelled(partialRemote = true))
                        fail(R.string.files_partial)
                    }
                }
            }
        }
        if (awaitingAck) {
            val outcome = withTimeoutOrNull(31_000) { completion.await() }
            if (outcome != null) return outcome
            mutex.withLock {
                if (!completion.isCompleted) {
                    completion.complete(TransferOutcome.Cancelled(partialRemote = true))
                    cleanup()
                    connection.close()
                    closed.set(true)
                    mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null,
                        authRequired = false, message = text(R.string.files_partial))
                    worker.cancel()
                }
            }
        }
        return completion.await()
    }

    private fun completeQueued(outcome: TransferOutcome) {
        transfer?.queued?.complete(outcome)
    }

    private fun transferTouchedRemote(): Boolean = transfer?.let { it.remoteTouched || it.expectedDigest != null } == true

    private fun reportProgress(current: Transfer, bytes: Long) {
        runCatching { current.progress?.invoke(bytes) }
    }

    private fun text(id: Int): UiText = UiText.resource(id)
}
