package ru.papajon.meshcentralmobile.mesh.files

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.*

class FilesBatchTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree get() = DocumentsContract.buildTreeDocumentUri(context.packageName + FixtureDocumentsProvider.AUTHORITY_SUFFIX, FixtureDocumentsProvider.ROOT_ID)

    private suspend fun ready(controller: FilesController) = withTimeout(5000) { controller.state.first { it.connected && !it.busy } }
    private suspend fun done(controller: FilesController) {
        withTimeout(8000) { controller.batch.state.first { it.rows.isNotEmpty() && !it.pending } }
        withTimeout(5000) { controller.state.first { !it.batchBusy } }
    }
    private fun documents(parent: String = FixtureDocumentsProvider.ROOT_ID): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
    }
    private fun bytes(id: String): ByteArray = context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))!!.use { it.readBytes() }

    @Test fun downloadsSeveralFilesThroughSafAndVerifiesEveryByte() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay()
        relay.files["/fixture/one.txt"] = "one".toByteArray()
        relay.files["/fixture/two.bin"] = ByteArray(32_800) { (it % 251).toByte() }
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); ready(controller)
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            assertTrue(controller.state.value.batchBusy)
            controller.batch.chosen(tree); done(controller)
            assertEquals(2, controller.batch.state.value.completed)
            val docs = documents().associate { it.second to it.first }
            relay.files.forEach { (path, expected) -> assertArrayEquals(expected, bytes(requireNotNull(docs[path.substringAfterLast('/')])) ) }
            assertTrue(documents().none { it.second.endsWith(".partial") })
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun failedFileWaitsForExplicitRetryAndKeepsTheRelay() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { files["/fixture/a.txt"] = "retry".toByteArray(); failDownload = true }
        var created = 0
        var fresh: BatchRelay? = null
        val controller = FilesController(relay, scope, context, connectionFactory = {
            created++; BatchRelay(relay.files).also { fresh = it }
        })
        try {
            controller.connect(); ready(controller)
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            controller.batch.chosen(tree); done(controller)
            assertEquals(DownloadStatus.Failed, controller.batch.state.value.rows.single().status)
            assertEquals(0, created)
            assertEquals(1, relay.downloads)
            relay.failDownload = false
            controller.batch.retry(0); done(controller)
            assertEquals(0, created)
            assertNull(fresh)
            assertEquals(2, relay.downloads)
            assertEquals(1, controller.batch.state.value.completed)
            assertArrayEquals("retry".toByteArray(), bytes(documents().single().first))
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun sftpFolderUsesRecursiveVerifiedFilesAndNoZipRequest() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay(sftp = true).apply {
            files["/fixture/folder/one.txt"] = byteArrayOf(1, 2)
            files["/fixture/folder/nested/two.bin"] = ByteArray(16_380) { 7 }
            files["/fixture/folder/empty"] = byteArrayOf()
        }
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); ready(controller)
            controller.browse("/fixture")
            withTimeout(5000) { controller.state.first { it.path == "/fixture" && !it.busy } }
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            controller.batch.chosen(tree); done(controller)
            assertEquals(1, controller.batch.state.value.completed)
            assertEquals(0, relay.zips)
            // The zero-length entry is created locally from the control listing, never opened.
            assertEquals(2, relay.downloads)
            val folder = documents().single().first
            val children = documents(folder).associate { it.second to it.first }
            assertArrayEquals(byteArrayOf(1, 2), bytes(children.getValue("one.txt")))
            assertArrayEquals(byteArrayOf(), bytes(children.getValue("empty")))
            assertArrayEquals(ByteArray(16_380) { 7 }, bytes(documents(children.getValue("nested")).single().first))
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun agentArchiveWaitsForCompletionDownloadsAndRemovesOwnedTemporaryFile() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(8) }
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); ready(controller)
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            controller.batch.chosen(tree); done(controller)
            assertEquals(1, controller.batch.state.value.completed)
            assertEquals(1, relay.zips)
            assertEquals("folder.zip", documents().single().second)
            assertTrue(relay.files.keys.none { it.endsWith(".zip") })
            assertTrue(relay.files.containsKey("/fixture/folder/a"))
            assertEquals(1, relay.deletes)
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun confirmedUploadCanBeRetriedWithoutLosingItsSource() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { failUpload = true }
        var fresh: BatchRelay? = null
        val controller = FilesController(relay, scope, context, connectionFactory = {
            BatchRelay(relay.files).also { fresh = it }
        })
        val payload = ByteArray(32_800) { (it % 127).toByte() }
        val source = FixtureDocumentsProvider.addForTest(context, "upload.bin", payload, true, true)
        try {
            controller.connect(); ready(controller)
            controller.proposeUpload(source)
            withTimeout(5000) { controller.state.first { it.confirmation != null } }
            assertEquals(0, relay.uploads)
            controller.confirm("upload.bin")
            done(controller)
            assertEquals(DownloadStatus.Failed, controller.batch.state.value.rows.single().status)
            assertEquals(1, relay.uploads)
            assertNull(fresh)
            assertTrue(controller.batch.retry(0)); done(controller)
            assertEquals(1, controller.batch.state.value.completed)
            assertEquals(1, fresh!!.uploads)
            assertArrayEquals(payload, relay.files.getValue("/fixture/upload.bin"))
            assertArrayEquals(payload, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun retryCannotStealAGroupDeletionReservation() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { files["/fixture/a"] = ByteArray(5); stallDelete = true }
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); ready(controller)
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            val missingTree = DocumentsContract.buildTreeDocumentUri(context.packageName + FixtureDocumentsProvider.AUTHORITY_SUFFIX, "missing-root")
            controller.batch.chosen(missingTree); done(controller)
            assertEquals(DownloadStatus.Failed, controller.batch.state.value.rows.single().status)
            assertTrue(controller.state.value.connected)
            controller.proposeDelete(controller.state.value.entries)
            withTimeout(5000) { controller.state.first { it.confirmation != null } }
            controller.confirm("")
            withTimeout(5000) { controller.state.first { it.batchBusy && !it.busy && relay.deletes == 1 } }
            assertFalse(controller.batch.retry(0))
            assertTrue(controller.state.value.batchBusy)
            assertEquals(0, relay.downloads)
            controller.cancel()
            withTimeout(5000) { controller.state.first { !it.batchBusy } }
            assertTrue(relay.files.containsKey("/fixture/a"))
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }

    @Test fun cancellingStopsQueueAndDiscardsPartialDocument() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { files["/fixture/a"] = ByteArray(200); files["/fixture/b"] = ByteArray(100); stallDownload = true }
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); ready(controller)
            assertTrue(controller.batch.prepare(controller.state.value.entries))
            controller.batch.chosen(tree)
            withTimeout(5000) { controller.state.first { it.transferName != null } }
            controller.batch.cancel(); done(controller)
            assertEquals(0, controller.batch.state.value.completed)
            assertTrue(controller.batch.state.value.rows.all { it.status == DownloadStatus.Cancelled })
            assertTrue(documents().isEmpty())
            assertTrue(relay.downloads <= 1)
        } finally { controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }
}

/** Synthetic Agent/SFTP server. Answers through public relay frames, not controller internals. */
internal class BatchRelay(
    val files: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap(),
    private val sftp: Boolean = false,
) : RelayConnection {
    override val protocol = if (sftp) RelayProtocol.SshFiles else RelayProtocol.AgentFiles
    override val state = MutableStateFlow(RelayStatus())
    private val frames = Channel<RelayFrame>(Channel.UNLIMITED)
    override val incoming = frames.receiveAsFlow()
    var failDownload = false
    val failPaths = mutableSetOf<String>()
    val disconnectPaths = mutableSetOf<String>()
    val sizes = mutableMapOf<String, Long>()
    val commands = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    val nullListPaths = mutableSetOf<String>()
    var onDelete: ((JSONObject) -> Unit)? = null
    var downloadStops = 0
    var beforeList: ((String) -> Unit)? = null
    var ignoreZip = false
    var stallZip = false
    var corruptZip = false
    var closeBeforeZipCancel = false
    var zipCancelled = false
    var ignoreUploadCancel = false
    var stallUpload = false
    private var zipPath: String? = null
    @Volatile var stallDownload = false
    val zipRequested = CompletableDeferred<Unit>()
    val uploadRequested = CompletableDeferred<Unit>()
    private var downloadId = 0
    var stallDelete = false
    var failUpload = false
    var uploads = 0
    private var uploadPath = ""
    private var uploadId = 0
    private var uploaded = java.io.ByteArrayOutputStream()
    @Volatile var downloads = 0
    @Volatile var deletes = 0
    @Volatile var zips = 0
    private var content = ByteArray(0)
    private var offset = 0
    override fun connect() { state.value = RelayStatus(RelayPhase.Connected); if (sftp) emit("c") }
    override fun sendText(value: String): Boolean {
        val request = JSONObject(value)
        commands += request
        when (request.optString("action")) {
            "ls" -> {
                val path = request.optString("path").ifEmpty { "/fixture" }
                beforeList?.invoke(path)
                if (path in nullListPaths) {
                    emit(request.put("path", path).put("dir", JSONObject.NULL).toString())
                    return true
                }
                val names = linkedMapOf<String, JSONObject>()
                files.forEach { (name, data) ->
                    val prefix = path.trimEnd('/') + "/"
                    if (name.startsWith(prefix)) {
                        val relative = name.removePrefix(prefix)
                        val child = relative.substringBefore('/')
                        names[child] = JSONObject().put("n", child).put("t", if ('/' in relative) 2 else 3).put("s", if ('/' in relative) 0 else sizes[name] ?: data.size.toLong())
                    }
                }
                emit(request.put("path", path).put("dir", JSONArray(names.values.toList())).toString())
            }
            "download" -> when (request.optString("sub")) {
                "start" -> {
                    downloads++
                    downloadId = request.getInt("id")
                    if (request.getString("path") in disconnectPaths) close()
                    else if (failDownload || request.getString("path") in failPaths) emit(request.put("sub", "cancel").toString())
                    else {
                        content = files.getValue(request.getString("path")); offset = 0
                        if (!stallDownload) emit(request.put("sub", "start").toString())
                    }
                }
                "ack", "startack" -> {
                    val end = minOf(offset + 16_380, content.size)
                    val final = end == content.size
                    frames.trySend(RelayFrame.Binary(byteArrayOf(1, 0, 0, if (final) 1 else 0) + content.copyOfRange(offset, end)))
                    offset = end
                }
                "stop" -> downloadStops++
            }
            "zip" -> {
                zips++; zipRequested.complete(Unit)
                if (ignoreZip) return true
                zipPath = request.getString("path") + "/" + request.getString("output")
                val bytes = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("fixture.txt")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                }
                files[zipPath!!] = if (corruptZip) ByteArray(64) else bytes.toByteArray()
                emit("""{"action":"refresh"}""") // unrelated early refresh must not complete it
                emit("""{"action":"dialogmessage","msg":"zipping"}""")
                if (stallZip) return true
                emit("""{"action":"dialogmessage","msg":null}""")
                emit("""{"action":"refresh"}""")
            }
            "cancel" -> {
                zipCancelled = true
                zipPath?.let { files.remove(it) }
                emit("""{"action":"dialogmessage","msg":null}""")
                emit("""{"action":"refresh"}""")
            }
            "uploadcancel" -> {
                if (!ignoreUploadCancel) { files.remove(uploadPath); emit(JSONObject().put("action", "uploadcancel").put("reqid", uploadId).toString()) }
            }
            "upload" -> {
                uploads++; uploadId = request.getInt("reqid"); uploadRequested.complete(Unit)
                uploadPath = request.getString("path") + "/" + request.getString("name")
                uploaded = java.io.ByteArrayOutputStream()
                emit(JSONObject().put("action", if (failUpload) "uploaderror" else "uploadstart").put("reqid", uploadId).toString())
            }
            "uploaddone" -> {
                files[uploadPath] = uploaded.toByteArray()
                emit(JSONObject().put("action", "uploaddone").put("reqid", uploadId).toString())
            }
            "rm" -> {
                deletes++
                val names = request.getJSONArray("delfiles")
                if (!stallDelete) repeat(names.length()) {
                    val target = request.getString("path") + "/" + names.getString(it)
                    files.remove(target)
                    if (request.optBoolean("rec")) files.keys.filter { it.startsWith("$target/") }.forEach(files::remove)
                }
                onDelete?.invoke(request)
            }
        }
        return true
    }
    fun releaseStalledDownload() {
        stallDownload = false
        if (downloads > 0) emit(JSONObject().put("action", "download").put("sub", "start").put("id", downloadId).toString())
    }
    private fun emit(text: String) { check(frames.trySend(RelayFrame.Text(text)).isSuccess) }
    override fun sendBinary(value: ByteArray): Boolean {
        uploaded.write(value, 1, value.size - 1)
        if (!stallUpload) emit(JSONObject().put("action", "uploadack").put("reqid", uploadId).toString())
        return true
    }
    override fun close() { if (stallZip && zips > 0 && !zipCancelled) closeBeforeZipCancel = true; state.value = RelayStatus(RelayPhase.Closed) }
}
