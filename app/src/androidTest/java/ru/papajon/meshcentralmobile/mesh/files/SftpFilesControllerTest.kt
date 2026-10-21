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
import ru.papajon.meshcentralmobile.mesh.session.*

/** Reproduces CreateSshFilesRelay defects without an SSH host or persisted credentials. */
class SftpFilesControllerTest {
    private lateinit var relay: FakeSftp
    private lateinit var controller: FilesController
    private lateinit var scope: CoroutineScope
    private val files = mutableListOf<File>()

    @Before fun setup() = runBlocking {
        relay = FakeSftp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = FilesController(relay, scope, InstrumentationRegistry.getInstrumentation().targetContext)
        controller.connect()
        delay(50)
        assertFalse(controller.state.value.connected) // An open websocket is not SFTP authentication.
        relay.frames.send(RelayFrame.Text("""{"action":"sshauth"}"""))
        waitFor { controller.state.value.authRequired }
        controller.authenticatePassword("synthetic-user", "synthetic-password")
        val auth = nextJson()
        assertEquals("sshauth", auth.getString("action"))
        assertFalse(auth.getBoolean("keep"))
        assertFalse(controller.state.value.connected)
        relay.frames.send(RelayFrame.Text("c"))
        val listing = nextJson()
        listReply(listing.getInt("reqid"), "[]")
        waitFor { controller.state.value.connected && !controller.state.value.busy }
    }

    @After fun cleanup() {
        controller.close()
        scope.cancel()
        files.forEach { it.delete() }
    }

    @Test fun emptyDownloadNeedsFreshSizeOpenAndPostflightSize() = runBlocking {
        val file = cacheFile()
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(file))
        val preflight = nextJson()
        assertEquals("ls", preflight.getString("action"))
        fileReply(preflight.getInt("reqid"), "source.bin", 0)
        val start = nextJson()
        downloadStart(start.getInt("id"))
        assertEquals("stop", nextJson().getString("sub")) // Never waits for an EOF this server omits.
        val postflight = nextJson()
        assertTrue(controller.state.value.busy)
        fileReply(postflight.getInt("reqid"), "source.bin", 0)
        waitFor { !controller.state.value.busy }
        assertEquals(0L, file.length())
        assertEquals(UiText.resource(R.string.files_download_complete), controller.state.value.message)
    }

    @Test fun exactBlockDownloadStopsWithoutRequestingMissingEof() = runBlocking {
        val file = cacheFile()
        val payload = ByteArray(16_380) { (it % 256).toByte() }
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(file))
        fileReply(nextJson().getInt("reqid"), "source.bin", payload.size.toLong())
        downloadStart(nextJson().getInt("id"))
        assertEquals(1, nextJson().getInt("ack"))
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 0) + payload))
        assertEquals("stop", nextJson().getString("sub"))
        val postflight = nextJson()
        assertTrue(controller.state.value.busy)
        fileReply(postflight.getInt("reqid"), "source.bin", payload.size.toLong())
        waitFor { !controller.state.value.busy }
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun changedPostflightSizeRejectsOtherwiseCompleteDownload() = runBlocking {
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(cacheFile()))
        fileReply(nextJson().getInt("reqid"), "source.bin", 0)
        downloadStart(nextJson().getInt("id"))
        nextJson() // stop
        fileReply(nextJson().getInt("reqid"), "source.bin", 1)
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_sftp_unverified), controller.state.value.message)
    }

    @Test fun serverWriteErrorDisguisedAsDoneDoesNotReadOldFileOrSucceed() = runBlocking {
        val id = beginUpload(byteArrayOf(1, 2, 3))
        // Pinned apprelays.js sends uploaddone instead of uploaderror when sftp.write fails.
        event("uploaddone", id)
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_sftp_unverified), controller.state.value.message)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun oldRemoteFileOfSameSizeFailsSha256Readback() = runBlocking {
        val id = beginUpload(byteArrayOf(1, 2, 3))
        beginReadback(id, 3)
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1, 1, 2, 4)))
        assertEquals("stop", nextJson().getString("sub"))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_sftp_unverified), controller.state.value.message)
    }

    @Test fun successfulUploadRequiresMatchingSha256AndFinalSize() = runBlocking {
        val bytes = byteArrayOf(123, 0, 42)
        val id = beginUpload(bytes)
        beginReadback(id, bytes.size.toLong())
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1) + bytes))
        assertEquals("stop", nextJson().getString("sub"))
        val postflight = nextJson()
        assertTrue(controller.state.value.busy)
        fileReply(postflight.getInt("reqid"), "target.bin", bytes.size.toLong())
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun cancellationDuringReadbackNeverReportsUploadSuccess() = runBlocking {
        val id = beginUpload(byteArrayOf(1, 2, 3))
        beginReadback(id, 3)
        controller.cancel()
        waitFor { !controller.state.value.connected }
        assertTrue(relay.closed)
        assertEquals(UiText.resource(R.string.files_sftp_unverified), controller.state.value.message)
    }

    @Test fun emptyUploadStillNeedsRemoteOpenAndTwoSizeChecks() = runBlocking {
        controller.proposeUpload(Uri.fromFile(cacheFile()))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("empty.bin")
        val uploadId = nextJson().getInt("reqid")
        event("uploadstart", uploadId)
        assertEquals("uploaddone", nextJson().getString("action"))
        event("uploaddone", uploadId)
        fileReply(nextJson().getInt("reqid"), "empty.bin", 0)
        downloadStart(nextJson().getInt("id"))
        assertEquals("stop", nextJson().getString("sub"))
        val postflight = nextJson()
        assertTrue(controller.state.value.busy)
        fileReply(postflight.getInt("reqid"), "empty.bin", 0)
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun exactBlockUploadVerifiesDigestWithoutWaitingForMissingEof() = runBlocking {
        val bytes = ByteArray(16_380) { (it % 256).toByte() }
        val id = beginUpload(bytes)
        beginReadback(id, bytes.size.toLong())
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 0) + bytes))
        assertEquals("stop", nextJson().getString("sub"))
        fileReply(nextJson().getInt("reqid"), "target.bin", bytes.size.toLong())
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun cancelAckDoesNotClaimRemovalWhileRemoteFileStillExists() = runBlocking {
        val id = beginUpload(byteArrayOf(1, 2, 3))
        controller.cancel()
        assertEquals("uploadcancel", nextJson().getString("action"))
        event("uploadcancel", id)
        fileReply(nextJson().getInt("reqid"), "target.bin", 3)
        waitFor { !controller.state.value.busy }
        assertEquals(UiText.resource(R.string.files_change_unverified), controller.state.value.message)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun earlyEofFailsInsteadOfSavingShortFileAsComplete() = runBlocking {
        controller.download("/tmp/source.bin", "source.bin", Uri.fromFile(cacheFile()))
        fileReply(nextJson().getInt("reqid"), "source.bin", 5)
        downloadStart(nextJson().getInt("id"))
        nextJson() // startack
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1, 1, 2)))
        waitFor { !controller.state.value.connected }
        assertEquals(UiText.resource(R.string.files_sftp_unverified), controller.state.value.message)
    }

    private suspend fun beginUpload(bytes: ByteArray): Int {
        controller.proposeUpload(Uri.fromFile(cacheFile().apply { writeBytes(bytes) }))
        waitFor { controller.state.value.confirmation != null }
        controller.confirm("target.bin")
        val upload = nextJson()
        val id = upload.getInt("reqid")
        event("uploadstart", id)
        val encoded = withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Binary
        assertArrayEquals(byteArrayOf(0) + bytes, encoded.value)
        return id
    }
    private suspend fun beginReadback(uploadId: Int, size: Long) {
        event("uploadack", uploadId)
        assertEquals("uploaddone", nextJson().getString("action"))
        event("uploaddone", uploadId)
        val preflight = nextJson()
        assertEquals("ls", preflight.getString("action"))
        fileReply(preflight.getInt("reqid"), "target.bin", size)
        val download = nextJson()
        assertEquals("download", download.getString("action"))
        downloadStart(download.getInt("id"))
        assertEquals("startack", nextJson().getString("sub"))
    }
    private fun cacheFile(): File = File.createTempFile("sftp-test-", ".bin",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).also { files += it }
    private suspend fun nextJson(): JSONObject = JSONObject((withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Text).value)
    private suspend fun listReply(id: Int, entries: String) {
        relay.frames.send(RelayFrame.Binary("""{"path":"/tmp","reqid":$id,"dir":$entries}""".toByteArray()))
    }
    private suspend fun fileReply(id: Int, name: String, size: Long) = listReply(id, """[{"n":"$name","t":3,"s":$size}]""")
    private suspend fun downloadStart(id: Int) { relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":$id}""")) }
    private suspend fun event(action: String, id: Int) { relay.frames.send(RelayFrame.Binary("""{"action":"$action","reqid":$id}""".toByteArray())) }
    private suspend fun waitFor(predicate: () -> Boolean) { withTimeout(3_000) { while (!predicate()) delay(10) } }

    private class FakeSftp : RelayConnection {
        override val protocol = RelayProtocol.SshFiles
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
