package ru.papajon.meshcentralmobile.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.*
import ru.papajon.meshcentralmobile.ui.components.ShareDestinationPanel
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

class PhoneFilesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rootDisablesSendHereAndCategoryReenablesIt(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = PhoneRelay()
        val controller = FilesController(relay, scope, context)
        val workspace = ConnectionWorkspace.get(context.applicationContext as Application)
        val source = File(context.cacheDir, "updates/phone-root-test.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        try {
            controller.connect(); answer(relay, """[{"n":"Sdcard","t":2}]""")
            withTimeout(4000) { controller.state.first { it.connected && !it.busy } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", source)
            compose.runOnIdle { workspace.acceptShare(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            compose.waitUntil(5000) { workspace.sharedFiles.isNotEmpty() }
            compose.setContent { MeshCentralMobileTheme { Column(Modifier.safeDrawingPadding()) {
                ShareDestinationPanel(workspace, "Fixture", MeshDevice("node//phone", "mesh//test", "Phone"), controller) {}
            } } }
            compose.onNodeWithText(context.getString(R.string.workspace_send_here)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.phone_files_choose_category)).assertIsDisplayed()
            screenshot("phone-root-upload-disabled")
            controller.open(controller.state.value.entries.single()); answer(relay, "[]")
            withTimeout(4000) { controller.state.first { it.path == "Sdcard" && !it.busy } }
            compose.onNodeWithText(context.getString(R.string.workspace_send_here)).assertIsEnabled()
        } finally { compose.runOnIdle { workspace.dismissShare() }; controller.close(); scope.cancel(); source.delete() }
    }

    @Test fun duplicateMediaRowsRenderAndExplainUnavailableActions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = PhoneRelay()
        val controller = FilesController(relay, scope, context)
        try {
            controller.connect(); answer(relay, """[{"n":"Images","t":2}]""")
            withTimeout(4000) { controller.state.first { it.connected && !it.busy } }
            controller.open(controller.state.value.entries.single())
            answer(relay, """[{"n":"same.jpg","t":3,"s":4,"d":1},{"n":"same.jpg","t":3,"s":8,"d":2}]""")
            withTimeout(4000) { controller.state.first { it.path == "Images" && !it.busy } }
            compose.setContent { MeshCentralMobileTheme { FilesScreen(controller, {}, Modifier.safeDrawingPadding()) } }
            compose.onAllNodesWithText("same.jpg").assertCountEquals(2)
            screenshot("phone-duplicate-media-rows")
            compose.onAllNodesWithText("same.jpg")[0].performClick()
            compose.onNodeWithText(context.getString(R.string.phone_files_ambiguous_name)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.files_download)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.files_delete)).assertDoesNotExist()
        } finally { controller.close(); scope.cancel() }
    }

    private suspend fun answer(relay: PhoneRelay, entries: String) {
        val request = JSONObject(withTimeout(4000) { relay.sent.receive() })
        relay.input.send(RelayFrame.Text(request.put("dir", org.json.JSONArray(entries)).toString()))
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "phone-evidence/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private class PhoneRelay : RelayConnection {
        override val androidFiles = true
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        val input = Channel<RelayFrame>(16)
        val sent = Channel<String>(16)
        override val incoming = input.receiveAsFlow()
        override fun connect() { state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = sent.trySend(value).isSuccess
        override fun sendBinary(value: ByteArray) = true
        override fun close() { input.close(); state.value = RelayStatus(RelayPhase.Closed) }
    }
}
