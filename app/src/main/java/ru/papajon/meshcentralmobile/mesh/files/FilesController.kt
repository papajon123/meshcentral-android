package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol

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
)

/** One operation and one chunk in flight. Every retry requires a new explicit user action.
 * All protocol and SAF stream work runs on IO, serialized by a mutex; no per-frame coroutine queue.
 */
class FilesController(connection: RelayConnection, scope: CoroutineScope, context: Context) {
    private val connection = connection
    private val sftp = connection.protocol == RelayProtocol.SshFiles
    private val resolver = context.applicationContext.contentResolver
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mutable = MutableStateFlow(FilesState(sftp = sftp))
    val state: StateFlow<FilesState> = mutable.asStateFlow()
    private var sequence = 0
    private var listingId: Int? = null
    private var listingPath = ""
    private var verification: ((List<RemoteFile>) -> Boolean)? = null
    private var listingHandler: ((List<RemoteFile>) -> Unit)? = null
    private var timeout: Job? = null
    private var transfer: Transfer? = null
    private val selectedDownload = AtomicReference<Pair<String, RemoteFile>?>(null)
    private val lateDocumentWarning = AtomicBoolean(false)

    init {
        worker.coroutineContext[Job]?.invokeOnCompletion {
            // Also release streams if the owning ViewModel scope is cancelled during a transfer.
            connection.close()
            runCatching { transfer?.input?.close() }
            runCatching { transfer?.output?.close() }
            transfer = null
            selectedDownload.set(null)
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
    )
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
    fun prepareDownload(entry: RemoteFile): Boolean {
        val snapshot = state.value
        if (!snapshot.connected || snapshot.busy || entry.directory || !entry.actionable || entry !in snapshot.entries) return false
        selectedDownload.set(snapshot.path to entry)
        return true
    }

    fun downloadChosen(destination: Uri?) {
        val selected = selectedDownload.getAndSet(null)
        if (destination == null) return
        if (closed.get() || selected == null) {
            // SAF may have created a document after this controller was closed (or its selection lost).
            // Never open, truncate, or delete that document automatically.
            if (closed.get()) lateDocumentWarning.set(true)
            mutable.value = mutable.value.copy(message = text(R.string.files_local_partial))
            return
        }
        download(AgentFilesProtocol.join(selected.first, selected.second.name), selected.second.name, destination)
    }

    fun propose(change: FileChange, entry: RemoteFile? = null) = action {
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
                send(JSONObject().put("action", "rm").put("path", request.path)
                    .put("delfiles", JSONArray().put(request.name)).put("rec", request.directory))
                list(request.path)
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
        if (!available()) {
            // The picker may return after a disconnect; its empty newly-created document remains visible.
            mutable.value = mutable.value.copy(message = text(R.string.files_local_partial))
            return@action
        }
        require(AgentFilesProtocol.usableExistingName(name, 3))
        val stream = requireNotNull(resolver.openOutputStream(destination, "wt"))
        val id = ++sequence
        val current = Transfer(id, name, output = stream, destination = destination,
            stage = Stage.DownloadPreflight,
            remotePath = remotePath, remoteParent = AgentFilesProtocol.parent(remotePath))
        transfer = current
        mutable.value = mutable.value.copy(busy = true, transferred = 0L, transferName = name, message = null)
        prepareRemoteDownload(current)
    }

    fun cancel() = action {
        val current = transfer
        if (current?.input != null) {
            current.input?.close()
            current.input = null
            current.stage = Stage.UploadCancel
            send(JSONObject().put("action", "uploadcancel").put("reqid", current.id))
            armTimeout()
        } else {
            // Closing the relay releases an Agent download handle, including its incomplete stop path.
            fail(if (verification != null) R.string.files_change_unverified
                else if (current != null) R.string.files_local_partial else R.string.files_cancelled)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection.close()
        worker.launch {
            mutex.withLock {
                val partial = transfer != null
                val changed = verification != null || transfer?.expectedDigest != null
                cleanup()
                mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null,
                    message = text(if (lateDocumentWarning.get()) R.string.files_local_partial
                        else if (changed) R.string.files_change_unverified else if (partial) R.string.files_partial else R.string.files_disconnected))
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
            fail(R.string.files_directory_failed, preferReason = true)
            return
        }
        val message = JSONObject(raw)
        if (sftp) when (message.optString("action")) {
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
                if (sftp) SftpFilesAdapter.entries(message) else AgentFilesProtocol.entries(message)
            } catch (_: Exception) {
                if (listingHandler != null) { fail(if (sftp) R.string.files_sftp_unverified else R.string.files_operation_failed); return }
                timeout?.cancel()
                listingId = null
                val changed = verification != null
                verification = null
                mutable.value = mutable.value.copy(busy = false,
                    message = text(if (changed) R.string.files_change_unverified else R.string.files_directory_failed))
                return
            }
            timeout?.cancel()
            listingId = null
            val after = listingHandler
            listingHandler = null
            if (after != null) {
                mutable.value = mutable.value.copy(path = message.optString("path", listingPath), entries = entries)
                after(entries)
                return
            }
            val result = verification?.invoke(entries)
            verification = null
            mutable.value = mutable.value.copy(busy = false, path = message.optString("path", listingPath), entries = entries,
                message = result?.let { text(if (it) R.string.files_change_verified else R.string.files_change_unverified) })
            return
        }
        if (message.optString("ctrlChannel") == "102938") return
        val current = transfer ?: return
        when (message.optString("action")) {
            "download" -> {
                if (message.optInt("id", -1) != current.id ||
                    (current.output == null && current.expectedDigest == null)) return
                when (message.optString("sub")) {
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
                    "cancel" -> fail(R.string.files_operation_failed)
                }
            }
            "uploaderror" -> if (!message.has("reqid") || message.optInt("reqid", -1) == current.id) fail(R.string.files_operation_failed)
            "uploadstart", "uploadack" -> {
                if (message.optInt("reqid", -1) != current.id) return
                if (current.stage == Stage.UploadCancel) return
                val start = message.optString("action") == "uploadstart"
                check(current.stage == if (start) Stage.UploadStart else Stage.UploadAck)
                if (!start) current.bytes += current.outstanding
                mutable.value = mutable.value.copy(transferred = current.bytes)
                uploadNext(current)
            }
            "uploaddone" -> {
                if (message.optInt("reqid", -1) != current.id) return
                check(current.stage == Stage.UploadDone)
                // Both protocols must prove content, never merely an existing file of the same size.
                // SFTP additionally emits uploaddone on write errors; the stage check rejects that.
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
                cleanupTransfer()
                mutable.value = mutable.value.copy(busy = false, message = text(R.string.files_cancelled))
                verification = { entries -> entries.none { it.name == current.name } }
                list(mutable.value.path)
            }
        }
    }

    private fun uploadNext(current: Transfer) {
        val data = ByteArray(AgentFilesProtocol.CHUNK_SIZE)
        val count = requireNotNull(current.input).read(data)
        if (count < 0) {
            current.input?.close()
            current.input = null
            current.stage = Stage.UploadDone
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
        } else if (final && current.expectedDigest != null) {
            finishVerifiedDownload(current)
        } else if (final) {
            current.output?.close()
            current.output = null
            cleanupTransfer()
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
        send(JSONObject().put("action", "download").put("sub", "start").put("path", current.remotePath).put("id", current.id))
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
            val unchangedSize = entries.any { it.name == current.name && !it.directory && it.sizeKnown && it.size == current.expectedSize }
            if (!unchangedSize) fail(if (sftp) R.string.files_sftp_unverified else R.string.files_change_unverified)
            else {
                val verifiedUpload = current.expectedDigest != null
                cleanupTransfer()
                mutable.value = mutable.value.copy(busy = false, message = text(
                    if (verifiedUpload) R.string.files_sftp_upload_verified else R.string.files_download_complete))
            }
        }
    }

    private fun cleanupTransfer() {
        timeout?.cancel()
        transfer?.let { current ->
            runCatching { current.input?.close() }
            runCatching { current.output?.close() }
        }
        transfer = null
        mutable.value = mutable.value.copy(transferred = null, transferName = null)
    }
    private fun cleanup() {
        cleanupTransfer()
        listingId = null
        verification = null
        listingHandler = null
    }
    private fun fail(reason: Int, preferReason: Boolean = false) {
        val partial = transfer != null
        val changed = verification != null || transfer?.expectedDigest != null
        val unverifiedSftp = sftp && transfer != null
        cleanup()
        connection.close()
        closed.set(true)
        mutable.value = mutable.value.copy(connected = false, busy = false, confirmation = null, authRequired = false,
            message = text(if (lateDocumentWarning.get()) R.string.files_local_partial else if (preferReason) reason
                else if (unverifiedSftp) R.string.files_sftp_unverified else if (changed) R.string.files_change_unverified else if (partial) R.string.files_partial else reason))
        worker.cancel()
    }
    private fun text(id: Int): UiText = UiText.resource(id)
}
