package ru.papajon.meshcentralmobile.transfer

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus

/** Synthetic AgentFiles protocol only; no real device, network or credentials. */
class UploadQueueControllerTest {
    private lateinit var relay: FakeRelay
    private lateinit var controller: FilesController
    private lateinit var scope: CoroutineScope
    private lateinit var context: android.content.Context
    private val files = mutableListOf<File>()

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        relay = FakeRelay()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = FilesController(relay, scope, context)
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

    @Test fun successRequiresUploadDoneReadbackDigestAndPostflightSize() = runBlocking {
        val bytes = byteArrayOf(11, 0, 33)
        val progress = mutableListOf<Long>()
        val result = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(bytes), "target.bin", "/tmp") { progress += it }
        }
        val upload = nextJson()
        assertEquals("upload", upload.getString("action"))
        val id = upload.getInt("reqid")
        assertTrue(controller.state.value.busy)
        assertTrue(result.isActive)

        event("uploadstart", id)
        val chunk = withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Binary
        assertEquals(0, chunk.value[0].toInt())
        assertArrayEquals(bytes, chunk.value.copyOfRange(1, chunk.value.size))
        assertTrue(result.isActive)

        event("uploadack", id)
        assertEquals("uploaddone", nextJson().getString("action"))
        assertTrue(result.isActive)
        event("uploaddone", id)

        val preflight = nextJson()
        assertEquals("ls", preflight.getString("action"))
        listing(preflight.getInt("reqid"), """[{"n":"target.bin","t":3,"s":3}]""")
        val download = nextJson()
        relay.frames.send(RelayFrame.Text("""{"action":"download","sub":"start","id":${download.getInt("id")}}"""))
        assertEquals("startack", nextJson().getString("sub"))
        relay.frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1) + bytes))
        val postflight = nextJson()
        assertEquals("ls", postflight.getString("action"))
        assertTrue(result.isActive)
        listing(postflight.getInt("reqid"), """[{"n":"target.bin","t":3,"s":3}]""")

        assertEquals(TransferOutcome.Success(3), withTimeout(3_000) { result.await() })
        waitFor { !controller.state.value.busy }
        assertTrue(progress.contains(3L))
        assertEquals(UiText.resource(R.string.files_sftp_upload_verified), controller.state.value.message)
    }

    @Test fun uploadErrorIsFailureAndIsNotReplayed() = runBlocking {
        val result = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(byteArrayOf(1, 2, 3)), "failed.bin", "/tmp") {}
        }
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        relay.frames.send(RelayFrame.Text("""{"action":"uploaderror"}"""))
        assertTrue(withTimeout(3_000) { result.await() } is TransferOutcome.Failed)
        waitFor { !controller.state.value.connected }
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun disconnectFailsTheDeferredWithoutSuccessOrImplicitRetry() = runBlocking {
        val result = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(byteArrayOf(1, 2, 3)), "cut.bin", "/tmp") {}
        }
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        relay.state.value = RelayStatus(RelayPhase.Failed, UiText.resource(R.string.relay_connection_failed))
        val outcome = withTimeout(3_000) { result.await() }
        assertTrue(outcome is TransferOutcome.Failed)
        waitFor { !controller.state.value.connected }
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
    }

    @Test fun cancelWaitsForAckAndAConfirmedRemoval() = runBlocking {
        val result = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(byteArrayOf(1, 2, 3)), "cancelled.bin", "/tmp") {}
        }
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        controller.cancel()
        assertEquals("uploadcancel", nextJson().getString("action"))
        event("uploadack", id)
        event("uploadcancel", id)
        val readback = nextJson()
        assertEquals("ls", readback.getString("action"))
        listing(readback.getInt("reqid"), "[]")
        assertEquals(TransferOutcome.Cancelled(partialRemote = false), withTimeout(3_000) { result.await() })
        waitFor { !controller.state.value.busy }
    }

    @Test fun cancelWithRemainingRemoteFileReportsPartialRemote() = runBlocking {
        val result = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(byteArrayOf(1, 2, 3)), "cancelled.bin", "/tmp") {}
        }
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        controller.cancel()
        assertEquals("uploadcancel", nextJson().getString("action"))
        event("uploadcancel", id)
        val readback = nextJson()
        listing(readback.getInt("reqid"), """[{"n":"cancelled.bin","t":3,"s":3}]""")
        assertEquals(TransferOutcome.Cancelled(partialRemote = true), withTimeout(3_000) { result.await() })
    }

    @Test fun pathMismatchWrongSchemeAndUnreadableContentSendNothing() = runBlocking {
        val source = contentUri(byteArrayOf(1, 2, 3))
        assertTrue(controller.uploadQueued(source, "wrong-path.bin", "/other") {} is TransferOutcome.Failed)
        assertTrue(controller.uploadQueued(Uri.fromFile(cacheFile()), "file-uri.bin", "/tmp") {} is TransferOutcome.Failed)
        assertTrue(controller.uploadQueued(Uri.parse("content://missing.provider/nope"), "missing.bin", "/tmp") {} is TransferOutcome.Failed)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })
        assertFalse(controller.state.value.busy)
    }

    @Test fun secondQueuedUploadIsRejectedWhileOneIsActive() = runBlocking {
        val first = async(Dispatchers.IO) {
            controller.uploadQueued(contentUri(byteArrayOf(1, 2, 3)), "first.bin", "/tmp") {}
        }
        val id = nextJson().getInt("reqid")
        event("uploadstart", id)
        withTimeout(3_000) { relay.sent.receive() }
        assertTrue(controller.uploadQueued(contentUri(byteArrayOf(4)), "second.bin", "/tmp") {} is TransferOutcome.Failed)
        assertNull(withTimeoutOrNull(100) { relay.sent.receive() })

        controller.cancel()
        assertEquals("uploadcancel", nextJson().getString("action"))
        event("uploadcancel", id)
        val readback = nextJson()
        listing(readback.getInt("reqid"), "[]")
        assertTrue(withTimeout(3_000) { first.await() } is TransferOutcome.Cancelled)
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File.createTempFile("upload-queue-", ".bin", directory).apply { writeBytes(bytes) }
        files += file
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    }

    private fun cacheFile(): File = File.createTempFile("upload-queue-local-", ".bin", context.cacheDir)
        .also { files += it }

    private suspend fun nextJson(): JSONObject =
        JSONObject((withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Text).value)

    private suspend fun listing(id: Int, entries: String) {
        relay.frames.send(RelayFrame.Binary("""{"path":"/tmp","reqid":$id,"dir":$entries}""".toByteArray()))
    }

    private suspend fun event(action: String, id: Int) {
        relay.frames.send(RelayFrame.Binary("""{"action":"$action","reqid":$id}""".toByteArray()))
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(3_000) { while (!predicate()) delay(10) }
    }

    private class FakeRelay : RelayConnection {
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(16)
        val sent = Channel<RelayFrame>(64)
        override val incoming = frames.receiveAsFlow()
        override fun connect() { state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = sent.trySend(RelayFrame.Text(value)).isSuccess
        override fun sendBinary(value: ByteArray) = sent.trySend(RelayFrame.Binary(value)).isSuccess
        override fun close() { state.value = RelayStatus(RelayPhase.Closed) }
    }
}
