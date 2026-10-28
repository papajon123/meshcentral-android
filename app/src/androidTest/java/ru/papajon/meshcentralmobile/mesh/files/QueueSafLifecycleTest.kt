package ru.papajon.meshcentralmobile.mesh.files

import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class QueueSafLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun activityFinishKeepsPersistedTreeUntilQueueCloses(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { files["/fixture/one"] = ByteArray(500) { 6 }; stallDownload = true }
        val c = FilesController(relay, scope, context)
        var destination: Uri? = null
        try {
            c.connect(); withTimeout(5000) { c.state.first { it.connected && !it.busy } }
            assertTrue(c.batch.prepare(c.state.value.entries))
            compose.setContent { MeshCentralMobileTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    destination = uri; c.batch.choose(uri)
                }
                Button(onClick = { picker.launch(null) }) { Text("Choose queue fixture") }
            } }
            compose.onNodeWithText("Choose queue fixture").performClick()
            clickSystem("Show roots", description = true)
            clickSystem("Queue grant fixture")
            // Android 8-10 DocumentsUI selects the folder with "Select" and grants it without a dialog.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                clickSystem("Use this folder")
                clickSystem("Allow")
            } else clickSystem("Select")
            withTimeout(10_000) { while (destination == null || !resolver.persistedUriPermissions.any { it.uri == destination }) delay(20) }
            val chosen = requireNotNull(destination)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == chosen && it.isReadPermission && it.isWritePermission })
            instrumentation.runOnMainSync { compose.activity.finish() }
            relay.releaseStalledDownload()
            withTimeout(10_000) { c.batch.state.first { it.completed == 1 } }
            assertTrue(resolver.persistedUriPermissions.any { it.uri == chosen })
            // Read the completed bytes through the real cross-UID grant after Activity destruction.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(chosen, DocumentsContract.getTreeDocumentId(chosen))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                val doc = DocumentsContract.buildDocumentUriUsingTree(chosen, cursor.getString(0))
                assertArrayEquals(ByteArray(500) { 6 }, resolver.openInputStream(doc)!!.use { it.readBytes() })
                DocumentsContract.deleteDocument(resolver, doc)
            }
            c.close()
            withTimeout(5000) { while (resolver.persistedUriPermissions.any { it.uri == chosen }) delay(20) }
        } finally { c.close(); scope.cancel() }
    }

    private suspend fun clickSystem(label: String, description: Boolean = false) = withTimeout(8000) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val value = if (description) node.contentDescription else node.text
            if (value?.toString()?.equals(label, ignoreCase = true) == true) return node
            repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
            return null
        }
        while (true) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) {
                var clickable: AccessibilityNodeInfo = node
                while (!clickable.isClickable) {
                    val parent = clickable.parent ?: break
                    clickable = parent
                }
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
            }
            delay(100)
        }
    }
}
