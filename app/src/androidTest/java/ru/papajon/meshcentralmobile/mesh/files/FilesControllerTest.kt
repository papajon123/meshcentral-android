package ru.papajon.meshcentralmobile.mesh.files

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus

/** Synthetic protocol fixtures only. Local cache file URIs stand in for the SAF stream provider. */
class FilesControllerTest {
    private lateinit var relay: FakeRelay
    private lateinit var controller: FilesController
    private lateinit var scope: CoroutineScope
    private val files = mutableListOf<File>()

    @Before fun setup() = runBlocking {
        relay = FakeRelay()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = FilesController(relay, scope, InstrumentationRegistry.getInstrumentation().targetContext)
        controller.connect()
        val request = nextJson()
        assertEquals("ls", request.getString("action"))
        listing(request.getInt("reqid"), "[]")
        waitFor { !controller.state.value.busy }
    }

    @After fun cleanup() {
        controller.close()
        scope.cancel()
        files.forEach { it.delete() }
    }

    @Test fun mkdirWaitsForMatchingReadbackAndReportsMissingResult() = runBlocking {
        controller.propose(FileChange.CreateDirectory)
        waitFor { controller.state.value.confirmation != null }
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
        controller.confirm("new folder")
        assertEquals("mkdir", nextJson().getString("action"))
        val readback = nextJson()
        assertTrue(controller.state.value.busy)
        listing(readback.getInt("reqid") - 1, "[]")
        delay(100)
        assertTrue(controller.state.value.busy)
        listing(readback.getInt("reqid"), "[]")
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_change_unverified), controller.state.value.message)
    }

    @Test fun deniedDirectoryPreservesLastKnownLocation() = runBlocking {
        controller.browse("/denied")
        val request = nextJson()
        relay.frames.send(RelayFrame.Text("""{"path":"/denied","reqid":${request.getInt("reqid")},"dir":null}"""))
        waitFor { !controller.state.value.busy }
        assertEquals("/tmp", controller.state.value.path)
        assertEquals(UiText.resource(R.string.files_directory_failed), controller.state.value.message)
    }

    @Test fun uploadHasOneChunkInFlightAndRequiresSha256Readback() = runBlocking {
        val source = cacheFile().apply { writeBytes(ByteArray(16_383) { (it % 256).toByte() }) }
        controller.proposeUpload(Uri.fromFile(source))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("target.bin")
        val request = nextJson()
        assertEquals("upload", request.getString("action"))
        val id = request.getInt("reqid")
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
        event("uploadstart", id)
        val first = withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Binary
        assertEquals(16_381, first.value.size)
        assertEquals(0, first.value[0].toInt())
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
        event("uploadack", id)
        val second = withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Binary
        assertEquals(4, second.value.size)
        event("uploadack", id)
        assertEquals("uploaddone", nextJson().getString("action"))
        assertTrue(controller.state.value.busy)
        event("uploaddone", id + 1)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
        event("uploaddone", id)
        val readback = nextJson()
        listing(readback.getInt("reqid"), """[{"n":"target.bin","t":3,"s":16383}]""")
        val download = nextJson()
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":${download.getInt("id")}}"""))
        assertEquals("startack", nextJson().getString("sub"))
        val original = source.readBytes()
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 0) + original.copyOfRange(0, 16_380)))
        assertEquals("ack", nextJson().getString("sub"))
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1) + original.copyOfRange(16_380, 16_383)))
        val postflight = nextJson()
        assertTrue(controller.state.value.busy)
        listing(postflight.getInt("reqid"), """[{"n":"target.bin","t":3,"s":16383}]""")
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun agentUploadRejectsOldRemoteFileOfTheSameSize() = runBlocking {
        controller.proposeUpload(Uri.fromFile(cacheFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("target.bin")
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        event("uploadack", id)
        assertEquals("uploaddone", nextJson().getString("action"))
        event("uploaddone", id)
        listing(nextJson().getInt("reqid"), """[{"n":"target.bin","t":3,"s":3}]""")
        val downloadId = nextJson().getInt("id")
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":$downloadId}"""))
        nextJson() // startack
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1, 1, 2, 4)))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_change_unverified), controller.state.value.message)
    }

    @Test fun emptyAgentUploadRequiresAnActualReadbackFinalFrame() = runBlocking {
        controller.proposeUpload(Uri.fromFile(cacheFile()))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("empty.bin")
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        assertEquals("uploaddone", nextJson().getString("action"))
        event("uploaddone", id)
        listing(nextJson().getInt("reqid"), """[{"n":"empty.bin","t":3,"s":0}]""")
        val downloadId = nextJson().getInt("id")
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":$downloadId}"""))
        assertEquals("startack", nextJson().getString("sub"))
        assertTrue(controller.state.value.busy)
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1)))
        listing(nextJson().getInt("reqid"), """[{"n":"empty.bin","t":3,"s":0}]""")
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun cancelSentMkdirIsUnverifiedRatherThanCancelled() = runBlocking { cancelMutation(FileChange.CreateDirectory) }
    @Test fun cancelSentRenameIsUnverifiedRatherThanCancelled() = runBlocking { cancelMutation(FileChange.Rename) }
    @Test fun cancelSentDeleteIsUnverifiedRatherThanCancelled() = runBlocking { cancelMutation(FileChange.Delete) }

    @Test fun documentReturnedAfterCloseIsReportedAndNeverOpenedOrDeleted() = runBlocking {
        controller.refresh()
        listing(nextJson().getInt("reqid"), """[{"n":"source.bin","t":3,"s":1}]""")
        waitFor { !controller.state.value.busy }
        assertTrue(controller.prepareDownload(controller.state.value.entries.single()))
        controller.close()
        waitFor { !controller.state.value.connected }
        val contents = byteArrayOf(10, 20, 30)
        val destination = cacheFile().apply { writeBytes(contents) }
        controller.downloadChosen(Uri.fromFile(destination))
        assertEquals(UiText.resource(R.string.files_local_partial), controller.state.value.message)
        assertArrayEquals(contents, destination.readBytes())
    }

    @Test fun oversizedUtf8JsonReportsDirectoryLimitInsteadOfGenericDisconnect() = runBlocking {
        controller.refresh()
        nextJson()
        val oversized = JSONObject().put("padding", "я".repeat(524_289)).toString()
        relay.frames.send(RelayFrame.Text(oversized))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_directory_failed), controller.state.value.message)
    }

    @Test fun transportSizeFailureDuringListingPreservesTheSpecificLimitError() = runBlocking {
        controller.refresh()
        nextJson()
        relay.state.value = RelayStatus(RelayPhase.Failed, UiText.resource(R.string.relay_buffer_limit))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_directory_failed), controller.state.value.message)
    }

    private suspend fun cancelMutation(change: FileChange) {
        controller.refresh()
        listing(nextJson().getInt("reqid"), """[{"n":"old.bin","t":3,"s":1}]""")
        waitFor { !controller.state.value.busy }
        controller.propose(change, if (change == FileChange.CreateDirectory) null else controller.state.value.entries.single())
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("new-name")
        val mutation = nextJson()
        assertTrue(mutation.getString("action") in listOf("mkdir", "rename", "rm"))
        assertEquals("ls", nextJson().getString("action"))
        controller.cancel()
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_change_unverified), controller.state.value.message)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun exactBlockDownloadWaitsForEmptyFinalHeaderAndWritesOnlyPayload() = runBlocking {
        val destination = cacheFile().apply { writeBytes(ByteArray(32_000) { 42 }) }
        controller.download("/tmp/backup-12:00.tar", "backup-12:00.tar", Uri.fromFile(destination))
        val preflight = nextJson()
        assertEquals("ls", preflight.getString("action"))
        listing(preflight.getInt("reqid"), """[{"n":"backup-12:00.tar","t":3,"s":16380}]""")
        val request = nextJson()
        assertEquals("/tmp/backup-12:00.tar", request.getString("path"))
        val id = request.getInt("id")
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":$id}"""))
        val startAck = nextJson()
        assertEquals(1, startAck.getInt("ack"))
        val payload = ByteArray(16_380) { (it % 256).toByte() }
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 0) + payload))
        assertEquals("ack", nextJson().getString("sub"))
        assertTrue(controller.state.value.busy)
        assertArrayEquals(payload, destination.readBytes())
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1)))
        waitFor { !controller.state.value.busy }
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(UiText.resource(R.string.files_download_complete), controller.state.value.message)
    }

    @Test fun emptyDownloadTruncatesExistingDocumentAndStillRequiresFinalFrame() = runBlocking {
        val destination = cacheFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        controller.download("/tmp/empty.bin", "empty.bin", Uri.fromFile(destination))
        val preflight = nextJson()
        listing(preflight.getInt("reqid"), """[{"n":"empty.bin","t":3,"s":0}]""")
        val request = nextJson()
        assertEquals(0L, destination.length())
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":${request.getInt("id")}}"""))
        assertEquals("startack", nextJson().getString("sub"))
        assertTrue(controller.state.value.busy)
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1)))
        waitFor { !controller.state.value.busy }
        assertEquals(0L, destination.length())
        assertEquals(UiText.resource(R.string.files_download_complete), controller.state.value.message)
    }

    @Test fun agentShortFinalFrameCannotReportSuccessfulDownload() = runBlocking {
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(cacheFile()))
        listing(nextJson().getInt("reqid"), """[{"n":"source.bin","t":3,"s":5}]""")
        val request = nextJson()
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":${request.getInt("id")}}"""))
        nextJson() // startack
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1, 1, 2)))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_partial), controller.state.value.message)
    }

    @Test fun agentExcessBytesAreRejectedBeforeWritingPastKnownSize() = runBlocking {
        val destination = cacheFile()
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(destination))
        listing(nextJson().getInt("reqid"), """[{"n":"source.bin","t":3,"s":1}]""")
        val request = nextJson()
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":${request.getInt("id")}}"""))
        nextJson()
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1, 1, 2)))
        waitFor { !controller.state.value.connected }
        assertEquals(0L, destination.length())
        assertEquals(UiText.resource(R.string.files_partial), controller.state.value.message)
    }

    @Test fun uploadErrorWithoutRequestIdStopsAndDoesNotReplay() = runBlocking {
        val source = cacheFile().apply { writeBytes(byteArrayOf(123, 0, 1)) }
        controller.proposeUpload(Uri.fromFile(source))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("failed.bin")
        val request = nextJson()
        event("uploadstart", request.getInt("reqid"))
        withTimeout(3_000) { relay.sent.receive() }
        relay.frames.send(RelayFrame.Text("""{"action":"uploaderror"}"""))
        waitFor { !controller.state.value.connected }
        assertTrue(relay.closed)
        assertEquals(UiText.resource(R.string.files_partial), controller.state.value.message)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun cancelUploadWaitsForAckThenChecksRemoval() = runBlocking {
        controller.proposeUpload(Uri.fromFile(cacheFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("cancelled.bin")
        val request = nextJson()
        event("uploadstart", request.getInt("reqid"))
        withTimeout(3_000) { relay.sent.receive() }
        controller.cancel()
        assertEquals("uploadcancel", nextJson().getString("action"))
        assertTrue(controller.state.value.busy)
        event("uploadack", request.getInt("reqid")) // A chunk already in flight may still be acknowledged.
        event("uploadcancel", request.getInt("reqid"))
        val readback = nextJson()
        listing(readback.getInt("reqid"), "[]")
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_change_verified), controller.state.value.message)
    }

    private fun cacheFile(): File = File.createTempFile("files-test-", ".bin",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).also { files += it }
    private suspend fun nextJson(): JSONObject = JSONObject((withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Text).value)
    private suspend fun listing(id: Int, entries: String) {
        relay.frames.send(RelayFrame.Binary("""{"path":"/tmp","reqid":$id,"dir":$entries}""".toByteArray()))
    }
    private suspend fun event(action: String, id: Int) {
        relay.frames.send(RelayFrame.Binary("""{"action":"$action","reqid":$id}""".toByteArray()))
    }
    private suspend fun waitFor(predicate: () -> Boolean) { withTimeout(3_000) { while (!predicate()) delay(10) } }

    private class FakeRelay : RelayConnection {
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(8)
        val sent = Channel<RelayFrame>(32)
        override val incoming = frames.receiveAsFlow()
        @Volatile var closed = false
        override fun connect() { state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = !closed && sent.trySend(RelayFrame.Text(value)).isSuccess
        override fun sendBinary(value: ByteArray) = !closed && sent.trySend(RelayFrame.Binary(value)).isSuccess
        override fun close() { closed = true; state.value = RelayStatus(RelayPhase.Closed) }
    }
}
