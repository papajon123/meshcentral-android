package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus

/**
 * Controller contract for the upload document picker. The picker lease must hold exactly one
 * reserved path; a late or unsolicited URI may only ever be ignored, never delete the user's
 * synthetic provider document and never replay remote work.
 */
class UploadPickerLifecycleTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var relay: FakeRelay
    private lateinit var controller: FilesController
    private lateinit var scope: CoroutineScope

    @Before fun setup() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        relay = FakeRelay()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = FilesController(relay, scope, context)
        controller.connect()
        val root = nextJson()
        assertEquals("ls", root.getString("action"))
        assertEquals("", root.getString("path"))
        listing(root.getInt("reqid"), "", "[]")
        waitFor { !controller.state.value.busy }
        controller.refresh()
        val tmp = nextJson()
        assertEquals("ls", tmp.getString("action"))
        listing(tmp.getInt("reqid"), "/tmp", "[]")
        waitFor { !controller.state.value.busy && controller.state.value.path == "/tmp" }
    }

    @After fun cleanup() {
        controller.close()
        scope.cancel()
        FixtureDocumentsProvider.resetForTest(context)
    }

    @Test fun reserveUploadPickerHoldsLeaseUntilChoice() = runBlocking {
        assertTrue("The idle listing must grant the picker lease", controller.prepareUpload())
        val reserved = controller.state.value
        assertTrue("The reserved picker owns the controller", reserved.busy)
        assertTrue("The lifecycle must expose the pending picker", reserved.uploadPickerPending)
        assertEquals("/tmp", reserved.path)
        assertNull(reserved.confirmation)

        assertFalse("A second reserve must not steal the lease", controller.prepareUpload())
        assertTrue(controller.state.value.busy)
        assertTrue(controller.state.value.uploadPickerPending)
        assertNoCommands()
    }

    @Test fun nullChoiceReleasesLeaseAndAllowsAnotherReservation() = runBlocking {
        assertTrue(controller.prepareUpload())
        controller.uploadChosen(null)
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertNull(controller.state.value.confirmation)
        val message = controller.state.value.message
        assertTrue(message == null || message == UiText.resource(R.string.files_cancelled))
        assertNoCommands()

        assertTrue("A cancelled picker must leave a usable reservation", controller.prepareUpload())
        assertTrue(controller.state.value.busy)
        assertTrue(controller.state.value.uploadPickerPending)
        controller.uploadChosen(null)
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
    }

    @Test fun failedLaunchReleasesLeaseAndLateUriNeverDeletesSource() = runBlocking {
        assertTrue(controller.prepareUpload())
        controller.uploadPreparationFailed()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertTrue(controller.state.value.connected)
        assertNull(controller.state.value.confirmation)
        assertEquals(UiText.resource(R.string.workspace_background_unavailable), controller.state.value.message)
        assertNoCommands()

        val bytes = "late-source-after-failed-launch".toByteArray(Charsets.UTF_8)
        val late = newDocument("late-source.bin", bytes)
        controller.uploadChosen(late)
        assertNoCommands()
        assertNull("A released picker must not create a confirmation", controller.state.value.confirmation)
        assertFalse(controller.state.value.uploadPickerPending)
        assertArrayEquals(bytes, contents(late))
        assertTrue(FixtureDocumentsProvider.backingFileExists(context, DocumentsContract.getDocumentId(late)))
    }

    @Test fun validChoiceCreatesUploadConfirmationWithoutRemoteMutation() = runBlocking {
        val bytes = ByteArray(45) { (it + 1).toByte() }
        val source = newDocument("source.bin", bytes)
        assertTrue(controller.prepareUpload())
        controller.uploadChosen(source)
        waitFor { controller.state.value.confirmation != null }

        val state = controller.state.value
        assertFalse(state.busy)
        assertFalse(state.uploadPickerPending)
        assertTrue(state.connected)
        val confirmation = requireNotNull(state.confirmation)
        assertEquals(FileChange.Upload, confirmation.change)
        assertEquals("/tmp", confirmation.path)
        assertEquals("source.bin", confirmation.name)
        assertEquals(source, confirmation.source)
        assertFalse(confirmation.directory)
        assertNoCommands()
        assertArrayEquals("Choosing a source must not consume it", bytes, contents(source))
    }

    @Test fun disconnectWhilePickerPendingDropsLateUriWithoutReconnect() = runBlocking {
        val bytes = "late-source-after-disconnect".toByteArray(Charsets.UTF_8)
        assertTrue(controller.prepareUpload())
        val connectsBefore = relay.connects
        relay.close()
        waitFor { !controller.state.value.connected && !controller.state.value.busy &&
            !controller.state.value.uploadPickerPending }
        assertNull(controller.state.value.confirmation)
        assertEquals("A disconnect must not reconnect", connectsBefore, relay.connects)

        val late = newDocument("disconnected-source.bin", bytes)
        controller.uploadChosen(late)
        assertNoCommands()
        assertEquals("A late URI must not reconnect", connectsBefore, relay.connects)
        assertNull(controller.state.value.confirmation)
        assertFalse(controller.state.value.uploadPickerPending)
        assertArrayEquals(bytes, contents(late))
        assertTrue(FixtureDocumentsProvider.backingFileExists(context, DocumentsContract.getDocumentId(late)))
    }

    @Test fun ownerCancellationReleasesPendingUploadPicker() = runBlocking {
        assertTrue(controller.prepareUpload())
        scope.cancel()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertNull(controller.state.value.confirmation)
        assertFalse(controller.state.value.connected)
    }

    @Test fun controllerCancelWhilePickerPendingDropsLateUriWithoutRemoteMutation() = runBlocking {
        assertTrue(controller.prepareUpload())
        controller.cancel()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertNull(controller.state.value.confirmation)

        val bytes = "late-source-after-controller-cancel".toByteArray(Charsets.UTF_8)
        val late = newDocument("cancelled-source.bin", bytes)
        controller.uploadChosen(late)
        assertNoCommands()
        assertNull(controller.state.value.confirmation)
        assertFalse(controller.state.value.uploadPickerPending)
        assertArrayEquals(bytes, contents(late))
        assertTrue(FixtureDocumentsProvider.backingFileExists(context, DocumentsContract.getDocumentId(late)))
    }

    @Test fun cancelledPickerBlocksNewUploadUntilItsLateUriIsIgnored() = runBlocking {
        val bytes = "cancelled-picker-source".toByteArray()
        val oldSource = newDocument("cancelled-picker.bin", bytes)
        assertTrue(controller.prepareUpload())
        controller.cancel()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertFalse("The cancelled system picker still owns its outstanding callback", controller.prepareUpload())
        controller.uploadChosen(oldSource)
        withTimeout(3_000) { while (!controller.prepareUpload()) delay(10) }
        assertTrue("A new picker must own its own reservation", controller.state.value.uploadPickerPending)
        assertNull("The old URI must not become the new picker result", controller.state.value.confirmation)
        assertArrayEquals(bytes, contents(oldSource))
        assertNoCommands()
        controller.uploadChosen(null)
        waitFor { !controller.state.value.uploadPickerPending }
    }

    @Test fun cancelledPickerBlocksNewUploadUntilItsLateNullIsConsumed() = runBlocking {
        assertTrue(controller.prepareUpload())
        controller.cancel()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        assertFalse("An outstanding cancelled picker must block another launch", controller.prepareUpload())
        controller.uploadChosen(null)
        withTimeout(3_000) { while (!controller.prepareUpload()) delay(10) }
        assertTrue(controller.state.value.busy)
        assertTrue(controller.state.value.uploadPickerPending)
        assertNull(controller.state.value.confirmation)
        assertNoCommands()
        controller.uploadChosen(null)
        waitFor { !controller.state.value.uploadPickerPending }
    }

    @Test fun cancelledPickerResultDoesNotCancelAnUnrelatedDirectoryRead() = runBlocking {
        val source = newDocument("ignored-source.bin", byteArrayOf(1, 2, 3))
        assertTrue(controller.prepareUpload())
        controller.cancel()
        waitFor { !controller.state.value.busy && !controller.state.value.uploadPickerPending }
        controller.browse("/other")
        val request = nextJson()
        assertEquals("ls", request.getString("action"))
        controller.uploadChosen(source)
        assertNoCommands()
        assertTrue("The pending directory read still owns busy", controller.state.value.busy)
        assertNull(controller.state.value.confirmation)
        listing(request.getInt("reqid"), "/other", "[]")
        waitFor { !controller.state.value.busy }
        assertEquals("/other", controller.state.value.path)
        assertArrayEquals(byteArrayOf(1, 2, 3), contents(source))
    }

    private fun newDocument(name: String, bytes: ByteArray): Uri =
        FixtureDocumentsProvider.addForTest(context, name, bytes, allowDelete = true, allowRename = true)

    private fun contents(document: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(document)).use { it.readBytes() }

    private suspend fun nextJson(): JSONObject =
        JSONObject((withTimeout(3_000) { relay.sent.receive() } as RelayFrame.Text).value)

    private suspend fun listing(id: Int, path: String, entries: String) {
        relay.frames.send(RelayFrame.Binary("""{"path":"$path","reqid":$id,"dir":$entries}""".toByteArray()))
    }

    private suspend fun assertNoCommands() {
        assertNull("No remote command may be sent while the picker lease is unresolved",
            withTimeoutOrNull(100) { relay.sent.receive() })
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(3_000) { while (!predicate()) delay(10) }
    }

    private class FakeRelay : RelayConnection {
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(8)
        val sent = Channel<RelayFrame>(32)
        override val incoming = frames.receiveAsFlow()
        @Volatile var closed = false
        @Volatile var connects = 0
        override fun connect() { connects++; state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = !closed && sent.trySend(RelayFrame.Text(value)).isSuccess
        override fun sendBinary(value: ByteArray) = !closed && sent.trySend(RelayFrame.Binary(value)).isSuccess
        override fun close() { closed = true; state.value = RelayStatus(RelayPhase.Closed) }
    }
}
