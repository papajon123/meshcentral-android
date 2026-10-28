package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.files.BatchRelay
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File

/** Real Android frames/actions: IO-backed controller updates must not depend on a virtual Compose clock. */
class FilesSelectionTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var diagnosticState: (() -> String)? = null
    private var originalAccessibilityFlags = 0

    // Android 8 can keep reporting a dismissed dialog as the active window; search every window then.
    @Before fun retrieveAllWindows() {
        val automation = instrumentation.uiAutomation
        originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @After fun restoreWindowRetrieval() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
    }

    @Test fun longPressSelectsSeveralAndDeletionRequiresCompleteListConfirmation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply {
            files["/fixture/alpha.txt"] = byteArrayOf(1)
            files["/fixture/beta.txt"] = byteArrayOf(2)
            files["/fixture/keep.txt"] = byteArrayOf(3)
        }
        val controller = FilesController(relay, scope, context)
        diagnosticState = { controller.state.value.toString() }
        try {
            controller.connect()
            withTimeout(5000) { controller.state.first { it.connected && !it.busy } }
            activity.scenario.onActivity { host ->
                host.setContent { MeshCentralMobileTheme { FilesScreen(controller, {}, Modifier.safeDrawingPadding()) } }
            }
            selectTwo()
            screenshot("files-selection")
            click(context.getString(R.string.tools_delete_selected))
            awaitUi("first confirmation") { controller.state.value.confirmation != null && visible(context.getString(R.string.tools_delete_list)) }
            assertTrue(visible("alpha.txt")); assertTrue(visible("beta.txt"))
            assertEquals(2, controller.state.value.confirmation!!.entries.size)
            assertEquals(setOf("alpha.txt", "beta.txt"), controller.state.value.confirmation!!.entries.map { it.name }.toSet())
            assertEquals(0, relay.deletes)
            screenshot("files-delete-confirmation")
            click(context.getString(R.string.files_cancel))
            awaitUi("dismissed confirmation") { controller.state.value.confirmation == null && visible(context.getString(R.string.tools_select_hint)) }
            assertEquals(0, relay.deletes)
            selectTwo()
            click(context.getString(R.string.tools_delete_selected))
            awaitUi("second confirmation") { controller.state.value.confirmation != null && visible(context.getString(R.string.files_confirm)) }
            assertEquals(2, controller.state.value.confirmation!!.entries.size)
            assertEquals(setOf("alpha.txt", "beta.txt"), controller.state.value.confirmation!!.entries.map { it.name }.toSet())
            click(context.getString(R.string.files_confirm))
            awaitUi("verified deletion") { relay.deletes == 1 && !controller.state.value.batchBusy }
            assertEquals(setOf("/fixture/keep.txt"), relay.files.keys)
            awaitUi("deleted rows removed") { !visible("alpha.txt") && !visible("beta.txt") && visible("keep.txt") }
        } finally {
            controller.close()
            withTimeoutOrNull(5000) { controller.state.first { !it.connected } }
            scope.cancel()
        }
    }

    @Test fun folderSingleTapActionsAndMultiSelectWorkOverAgentFiles(): Unit = runBlocking {
        verifyFolderInteractions(sftp = false)
    }

    @Test fun folderSingleTapActionsAndMultiSelectWorkOverSftp(): Unit = runBlocking {
        verifyFolderInteractions(sftp = true)
    }

    /**
     * BatchRelay(sftp = true) completes the SFTP handshake with a direct `c` frame, so no SSH
     * credential UI is involved in this navigation and selection contract.
     */
    private suspend fun verifyFolderInteractions(sftp: Boolean) {
        val mode = if (sftp) "sftp" else "agent"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay(sftp = sftp).apply {
            files["/folder/one.txt"] = byteArrayOf(1)
            files["/second/two.txt"] = byteArrayOf(2)
        }
        val controller = FilesController(relay, scope, context)
        diagnosticState = { "$mode: ${controller.state.value}" }
        try {
            controller.connect()
            withTimeout(5000) { controller.state.first { it.connected && !it.busy } }
            assertEquals("$mode protocol mismatch", sftp, controller.state.value.sftp)
            activity.scenario.onActivity { host ->
                host.setContent { MeshCentralMobileTheme { FilesScreen(controller, {}, Modifier.safeDrawingPadding()) } }
            }
            controller.browse("/")
            awaitUi("$mode root listing") {
                controller.state.value.path == "/" && !controller.state.value.busy &&
                    visible("folder") && visible("second")
            }

            click("folder")
            awaitUi("$mode short tap opened folder") {
                controller.state.value.path == "/folder" && !controller.state.value.busy && visible("one.txt")
            }
            assertNull("Short folder tap opened a dialog", controller.state.value.confirmation)
            assertFalse("Short folder tap showed the folder menu", visible(context.getString(R.string.files_close)))
            screenshot("files-folder-tap-$mode")

            click(context.getString(R.string.files_parent))
            awaitUi("$mode parent returned to root") {
                controller.state.value.path == "/" && !controller.state.value.busy && visible("folder")
            }

            click("${context.getString(R.string.session_dock_more)}: folder")
            awaitUi("$mode folder menu") {
                visible(context.getString(R.string.files_rename)) &&
                    visible(context.getString(R.string.files_delete)) &&
                    visible(context.getString(R.string.tools_download_folder))
            }
            assertEquals("$mode folder menu changed the path", "/", controller.state.value.path)
            screenshot("files-folder-menu-$mode")

            click(context.getString(R.string.files_close))
            awaitUi("$mode folder menu dismissed") {
                controller.state.value.path == "/" && !visible(context.getString(R.string.files_close))
            }

            longPress("folder")
            awaitUi("$mode long press selected folder") {
                visible(context.getString(R.string.tools_selected, 1))
            }
            scrollTo("second")
            click("second")
            scrollTo(context.getString(R.string.tools_selected, 2), forward = false)
            awaitUi("$mode tap added the second folder") {
                visible(context.getString(R.string.tools_selected, 2))
            }
            assertEquals("$mode selection navigated away from root", "/", controller.state.value.path)
            assertEquals("$mode selection changed the listing",
                setOf("folder", "second"), controller.state.value.entries.map { it.name }.toSet())
            screenshot("files-folder-selection-$mode")
        } finally {
            controller.close()
            withTimeoutOrNull(5000) { controller.state.first { !it.connected } }
            scope.cancel()
        }
    }

    private suspend fun selectTwo() {
        longPress("alpha.txt")
        awaitUi("first selection") { visible(context.getString(R.string.tools_selected, 1)) }
        click("beta.txt")
        awaitUi("two selected files") { visible(context.getString(R.string.tools_selected, 2)) }
    }

    /** Keep physical long-press coverage, using Android uptime rather than virtual Compose time. */
    private suspend fun longPress(label: String) {
        awaitUi("long-press $label") { findActionable(label, longClick = true) != null }
        val row = requireNotNull(findActionable(label, longClick = true))
        val bounds = Rect().also { row.getBoundsInScreen(it) }
        val downAt = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            return try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        try {
            assertTrue(inject(MotionEvent.ACTION_DOWN))
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        } finally { assertTrue(inject(MotionEvent.ACTION_UP)) }
    }

    private fun firstNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { found -> return found } }
            return null
        }
        val automation = instrumentation.uiAutomation
        return visit(automation.rootInActiveWindow)
            ?: automation.windows.asSequence().mapNotNull { visit(it.root) }.firstOrNull()
    }
    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        return text == label || text?.startsWith("$label\n") == true ||
            description == label || description?.startsWith("$label\n") == true
    }
    private fun find(label: String) = firstNode { matches(it, label) }
    // A dismissed dialog can remain the active Android 8 window; skip its non-clickable title.
    private fun findActionable(label: String, longClick: Boolean = false) =
        firstNode { matches(it, label) && actionable(it, longClick) != null }?.let { actionable(it, longClick) }
    private fun visible(label: String) = find(label) != null
    private fun actionable(node: AccessibilityNodeInfo?, longClick: Boolean = false): AccessibilityNodeInfo? {
        var target = node ?: return null
        while (if (longClick) !target.isLongClickable else !target.isClickable) target = target.parent ?: return null
        return target.takeIf { it.isEnabled && it.isVisibleToUser }
    }
    private suspend fun scrollTo(label: String, forward: Boolean = true) {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        repeat(8) {
            if (visible(label)) return
            val list = firstNode { it.isScrollable }
            assertNotNull("No scrollable list for $label", list)
            list!!.performAction(action)
            delay(250)
        }
        awaitUi("scroll to $label") { visible(label) }
    }

    private suspend fun click(label: String) {
        awaitUi("click $label") { findActionable(label) != null }
        assertTrue(findActionable(label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun awaitUi(stage: String, condition: () -> Boolean) {
        val completed = withTimeoutOrNull(15_000) {
            while (!condition()) delay(50)
            true
        }
        if (completed != true) {
            screenshot("files-selection-timeout")
            throw AssertionError("Timed out at $stage: ${diagnosticState?.invoke()}")
        }
    }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "tools-plus/$name.png").apply { parentFile!!.mkdirs() }
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
