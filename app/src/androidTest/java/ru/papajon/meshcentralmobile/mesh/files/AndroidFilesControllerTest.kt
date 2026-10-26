package ru.papajon.meshcentralmobile.mesh.files

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.*

class AndroidFilesControllerTest {
    @Test fun transportFailureDeletesOwnedContentDocument() = failedContentDownload("transport")
    @Test fun cancellationDeletesOwnedContentDocument() = failedContentDownload("cancel")
    @Test fun sessionCloseDeletesOwnedContentDocument() = failedContentDownload("close")
    @Test fun ownerCancellationDeletesOwnedContentDocument() = failedContentDownload("owner")
    @Test fun shortDownloadDeletesOwnedContentDocument() = failedContentDownload("short")

    private fun failedContentDownload(failure: String) = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles)
        val download = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File.createTempFile("incomplete-", ".png", directory)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", destination)
        val controller = FilesController(AndroidFilesConnection(control, scope) { download }, scope, context)
        try {
            controller.connect(); listing(control, "", """[{"n":"Images","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single())
            listing(control, "Images", """[{"n":"fixture.png","t":3,"s":1347662}]""")
            waitFor { !controller.state.value.busy }
            assertTrue(controller.prepareDownload(controller.state.value.entries.single()))
            controller.downloadChosen(uri)
            listing(control, "Images", """[{"n":"fixture.png","t":3,"s":1347662}]""")
            download.input.send(RelayFrame.Text("""{"op":"ok","size":1347662}"""))
            repeat(19) { download.input.send(RelayFrame.Binary(ByteArray(65_535) { 42 })) }
            waitFor { controller.state.value.transferred == 1_245_165L }
            when (failure) {
                "cancel" -> controller.cancel()
                "close" -> controller.close()
                "owner" -> scope.cancel()
                "short" -> download.input.close()
                else -> {
                    download.state.value = RelayStatus(RelayPhase.Failed)
                    download.input.close(javax.net.ssl.SSLProtocolException("Synthetic TLS integrity failure"))
                }
            }
            waitFor { !controller.state.value.busy }
            assertFalse("An incomplete document must not remain usable", destination.exists())
            assertNotEquals(UiText.resource(R.string.files_download_complete), controller.state.value.message)
        } finally { controller.close(); scope.cancel(); destination.delete() }
    }

    @Test fun lateContentDocumentAfterCloseIsDeleted() = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = FilesController(AndroidFilesConnection(control, scope) { error("Unexpected download") },
            scope, context)
        FixtureDocumentsProvider.resetForTest(context)
        try {
            controller.connect(); listing(control, "", """[{"n":"Images","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single())
            listing(control, "Images", """[{"n":"source.bin","t":3,"s":3}]""")
            waitFor { !controller.state.value.busy }
            assertTrue(controller.prepareDownload(controller.state.value.entries.single()))
            controller.close()
            waitFor { !controller.state.value.connected }
            val document = FixtureDocumentsProvider.addForTest(context, "late.bin", byteArrayOf(1, 2, 3),
                allowDelete = true, allowRename = true)
            assertTrue(DocumentsContract.isDocumentUri(context, document))

            controller.downloadChosen(document)

            assertEquals(UiText.resource(R.string.final_fixes_download_removed,
                UiText.resource(R.string.files_disconnected)), controller.state.value.message)
            assertNull(context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null,
                null, null))
            assertFalse(FixtureDocumentsProvider.backingFileExists(context,
                DocumentsContract.getDocumentId(document)))
        } finally {
            controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context)
        }
    }

    @Test fun unsolicitedContentDocumentWithoutPickerIsNeverTouched() = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = FilesController(AndroidFilesConnection(control, scope) { error("Unexpected download") },
            scope, context)
        FixtureDocumentsProvider.resetForTest(context)
        val bytes = byteArrayOf(7, 8, 9)
        val document = FixtureDocumentsProvider.addForTest(context, "keep.bin", bytes,
            allowDelete = true, allowRename = true)
        try {
            controller.connect(); listing(control, "", "[]")
            waitFor { !controller.state.value.busy }

            controller.downloadChosen(document)

            assertEquals(UiText.resource(R.string.files_local_partial), controller.state.value.message)
            assertTrue(controller.state.value.connected)
            assertEquals("keep.bin", context.contentResolver.query(document,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            })
            assertArrayEquals(bytes, context.contentResolver.openInputStream(document)?.use { it.readBytes() })
            assertTrue(FixtureDocumentsProvider.backingFileExists(context,
                DocumentsContract.getDocumentId(document)))
        } finally {
            controller.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context)
        }
    }

    @Test fun androidUploadRequiresP10Sha256Readback() = verifyUpload(corrupt = false)
    @Test fun androidUploadRejectsSameSizeCorruptReadback() = verifyUpload(corrupt = true)
    @Test fun releasedAgentUploadVerifiesRawReadbackAcrossKeepalives() = verifyUpload(corrupt = false, header = false)
    @Test fun releasedAgentUploadRejectsCorruptRawReadback() = verifyUpload(corrupt = true, header = false)
    @Test fun mediaUploadWaitsForFreshSizeBeforeRawSha256Readback() = verifyUpload(corrupt = false, header = false, staleMedia = true)
    @Test fun mediaSizeThatNeverSettlesFailsWithoutReplayingTheUpload() = verifyUpload(corrupt = false, header = false, staleMedia = true, neverSettles = true)

    private fun verifyUpload(corrupt: Boolean, header: Boolean = true, staleMedia: Boolean = false, neverSettles: Boolean = false) = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles); val transfer = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("phone-source", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(123, 0, 42)) }
        val directory = if (staleMedia) "Images" else "Sdcard"
        val name = if (staleMedia) "test.png" else "test.bin"
        var downloadRequests = 0
        val adapter = AndroidFilesConnection(control, scope) { path -> downloadRequests++; assertEquals("$directory/$name", path); transfer }
        val controller = FilesController(adapter, scope, context)
        try {
            controller.connect(); listing(control, "", """[{"n":"$directory","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single()); listing(control, directory, "[]")
            waitFor { !controller.state.value.busy }
            controller.proposeUpload(Uri.fromFile(source))
            waitFor { controller.state.value.confirmation != null }
            controller.confirm(name)
            val upload = JSONObject(withTimeout(3000) { control.sent.receive() }.toString(Charsets.UTF_8))
            assertEquals("upload", upload.getString("action")); assertEquals(directory, upload.getString("path"))
            suspend fun response(action: String) { control.input.send(RelayFrame.Binary(JSONObject().put("action", action)
                .put("reqid", upload.getInt("reqid")).toString().toByteArray())) }
            response("uploadstart")
            assertArrayEquals(byteArrayOf(0, 123, 0, 42), withTimeout(3000) { control.sent.receive() })
            control.input.send(RelayFrame.Binary(byteArrayOf(0)))
            response("uploadack")
            assertEquals("uploaddone", JSONObject(withTimeout(3000) { control.sent.receive() }.toString(Charsets.UTF_8)).getString("action"))
            response("uploaddone")
            assertTrue(controller.state.value.busy)
            if (staleMedia) {
                // Real MediaStore may report 0 immediately after closing a newly inserted file.
                listing(control, directory, """[{"n":"$name","t":3,"s":0}]""")
                assertTrue(controller.state.value.busy)
                if (neverSettles) {
                    repeat(5) { listing(control, directory, """[{"n":"$name","t":3,"s":0}]""") }
                    waitFor { !controller.state.value.busy }
                    assertEquals(0, downloadRequests)
                    assertNull(controller.state.value.transferName)
                    assertEquals(UiText.resource(R.string.files_change_unverified), controller.state.value.message)
                    return@runBlocking
                }
            }
            listing(control, directory, """[{"n":"$name","t":3,"s":3}]""")
            if (header) transfer.input.send(RelayFrame.Text("""{"op":"ok","size":3}"""))
            control.input.send(RelayFrame.Binary(byteArrayOf(0)))
            transfer.input.send(RelayFrame.Binary(if (corrupt) byteArrayOf(1, 2, 3) else source.readBytes()))
            transfer.input.close()
            if (!corrupt) listing(control, directory, """[{"n":"$name","t":3,"s":3}]""")
            waitFor { !controller.state.value.busy }
            assertEquals(UiText.resource(if (corrupt) R.string.files_change_unverified else R.string.files_sftp_upload_verified),
                controller.state.value.message)
        } finally { controller.close(); scope.cancel(); source.delete() }
    }

    @Test fun androidDownloadWaitsForP10EndAndFreshListing() = verifyDownload(header = true)
    @Test fun releasedAgentDownloadWaitsForP10EndAndFreshListing() = verifyDownload(header = false)

    private fun verifyDownload(header: Boolean) = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles); val transfer = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File.createTempFile("phone-file", ".bin", File(context.cacheDir, "updates").apply { mkdirs() })
        val destinationUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", destination)
        val adapter = AndroidFilesConnection(control, scope) { path -> assertEquals("Sdcard/file.bin", path); transfer }
        val controller = FilesController(adapter, scope, context)
        try {
            controller.connect()
            listing(control, "", """[{"n":"Sdcard","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single())
            listing(control, "Sdcard", """[{"n":"file.bin","t":3,"s":3}]""")
            waitFor { !controller.state.value.busy }
            assertEquals("Sdcard", controller.state.value.path)
            assertTrue(controller.prepareDownload(controller.state.value.entries.single()))
            assertTrue(controller.state.value.busy)
            assertEquals("file.bin", controller.state.value.transferName)
            // No p5/p10 request may start while the system document picker is open.
            assertNull(withTimeoutOrNull(100) { control.sent.receive() })
            controller.downloadChosen(destinationUri)
            listing(control, "Sdcard", """[{"n":"file.bin","t":3,"s":3}]""")
            if (header) transfer.input.send(RelayFrame.Text("""{"op":"ok","size":3}"""))
            transfer.input.send(RelayFrame.Binary(byteArrayOf(123, 0, 42)))
            waitFor { controller.state.value.transferred == 3L }
            assertTrue(controller.state.value.busy)
            transfer.input.close()
            val readback = withTimeout(3000) { control.sent.receive() }
            assertTrue(controller.state.value.busy)
            control.input.send(RelayFrame.Binary(JSONObject(readback.toString(Charsets.UTF_8)).put("dir",
                org.json.JSONArray("""[{"n":"file.bin","t":3,"s":3}]""")).toString().toByteArray()))
            waitFor { !controller.state.value.busy }
            assertArrayEquals(byteArrayOf(123, 0, 42), destination.readBytes())
            assertEquals(UiText.resource(R.string.files_download_complete), controller.state.value.message)
        } finally { controller.close(); scope.cancel(); destination.delete() }
    }

    @Test fun rootQueueAndDuplicateMediaNamesDoNotCloseTheSession() = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adapter = AndroidFilesConnection(control, scope) { error("No download") }
        val controller = FilesController(adapter, scope, InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.connect(); listing(control, "", """[{"n":"Images","t":2}]""")
            waitFor { !controller.state.value.busy }
            val result = controller.uploadQueued(Uri.parse("content://synthetic/no-file"), "test.jpg", "")
            assertTrue(result is ru.papajon.meshcentralmobile.transfer.TransferOutcome.Failed)
            assertEquals(UiText.resource(R.string.phone_files_choose_category), (result as ru.papajon.meshcentralmobile.transfer.TransferOutcome.Failed).error)
            assertTrue(controller.state.value.connected)
            controller.open(controller.state.value.entries.single())
            listing(control, "Images", """[{"n":"same.jpg","t":3,"s":4,"d":1},{"n":"same.jpg","t":3,"s":8,"d":2}]""")
            waitFor { !controller.state.value.busy }
            assertEquals(2, controller.state.value.entries.size)
            for (entry in controller.state.value.entries) {
                assertFalse(controller.prepareDownload(entry))
                controller.propose(FileChange.Delete, entry)
            }
            assertNull(withTimeoutOrNull(100) { control.sent.receive() })
            assertTrue(controller.state.value.connected)
            assertNull(controller.state.value.confirmation)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun p5DisconnectMidDownloadReleasesReservationAndReportsPartialFile() = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles); val transfer = Fake(RelayProtocol.AgentFileDownload)
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File.createTempFile("phone-broken", ".bin", context.cacheDir)
        val controller = FilesController(AndroidFilesConnection(control, scope) { transfer }, scope, context)
        try {
            controller.connect(); listing(control, "", """[{"n":"Sdcard","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single()); listing(control, "Sdcard", """[{"n":"test.bin","t":3,"s":6}]""")
            waitFor { !controller.state.value.busy }
            assertTrue(controller.prepareDownload(controller.state.value.entries.single()))
            controller.downloadChosen(Uri.fromFile(destination))
            listing(control, "Sdcard", """[{"n":"test.bin","t":3,"s":6}]""")
            transfer.input.send(RelayFrame.Binary(byteArrayOf(1, 2, 3)))
            waitFor { controller.state.value.transferred == 3L }
            control.close()
            waitFor { !controller.state.value.connected && !controller.state.value.busy }
            assertNull(controller.state.value.transferName)
            assertEquals(UiText.resource(R.string.files_partial), controller.state.value.message)
            assertTrue(uncaught.isEmpty())
        } finally { controller.close(); scope.cancel(); destination.delete() }
    }

    @Test fun androidDeleteWaitsForPhonePermissionResponseBeforeReadback() = runBlocking {
        val control = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adapter = AndroidFilesConnection(control, scope) { error("Unexpected download") }
        val controller = FilesController(adapter, scope, InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.connect(); listing(control, "", """[{"n":"Images","t":2}]""")
            waitFor { !controller.state.value.busy }
            controller.open(controller.state.value.entries.single())
            listing(control, "Images", """[{"n":"test.jpg","t":3,"s":3}]""")
            waitFor { !controller.state.value.busy }
            controller.propose(FileChange.Rename, controller.state.value.entries.single())
            delay(100); assertNull(controller.state.value.confirmation)
            controller.propose(FileChange.Delete, controller.state.value.entries.single())
            waitFor { controller.state.value.confirmation != null }
            controller.confirm("")
            val deletion = JSONObject(withTimeout(3000) { control.sent.receive() }.toString(Charsets.UTF_8))
            assertEquals("rm", deletion.getString("action")); assertTrue(deletion.get("reqid") is String)
            assertNull(withTimeoutOrNull(100) { control.sent.receive() })
            control.input.send(RelayFrame.Binary(JSONObject().put("action", "rm").put("reqid", deletion.getString("reqid"))
                .put("success", true).toString().toByteArray()))
            listing(control, "Images", "[]")
            waitFor { !controller.state.value.busy }
            assertEquals(UiText.resource(R.string.files_change_verified), controller.state.value.message)
        } finally { controller.close(); scope.cancel() }
    }

    private suspend fun listing(control: Fake, path: String, entries: String) {
        val request = JSONObject(withTimeout(3000) { control.sent.receive() }.toString(Charsets.UTF_8))
        assertEquals("ls", request.getString("action")); assertEquals(path, request.getString("path"))
        control.input.send(RelayFrame.Binary(request.put("dir", org.json.JSONArray(entries)).toString().toByteArray()))
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(10) }
    private class Fake(override val protocol: RelayProtocol) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus())
        val input = Channel<RelayFrame>(16)
        val sent = Channel<ByteArray>(16)
        override val incoming = input.receiveAsFlow()
        override fun connect() { state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String): Boolean = error("p5 must use binary")
        override fun sendBinary(value: ByteArray) = sent.trySend(value).isSuccess
        override fun close() { input.close(); state.value = RelayStatus(RelayPhase.Closed) }
    }
}
