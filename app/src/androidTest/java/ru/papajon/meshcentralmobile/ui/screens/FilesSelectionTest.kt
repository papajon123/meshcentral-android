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
import org.junit.Assert.*
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

    private suspend fun selectTwo() {
        awaitUi("first selectable row") { visible("alpha.txt") }
        val row = actionable(find("alpha.txt"), longClick = true)
        assertNotNull(row)
        // Keep physical long-press coverage, using Android uptime rather than virtual Compose time.
        val bounds = Rect().also { row!!.getBoundsInScreen(it) }
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
        awaitUi("first selection") { visible(context.getString(R.string.tools_selected, 1)) }
        click("beta.txt")
        awaitUi("two selected files") { visible(context.getString(R.string.tools_selected, 2)) }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val text = node.text?.toString()
            if (node.isVisibleToUser && (text == label || text?.startsWith("$label\n") == true)) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { found -> return found } }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun visible(label: String) = find(label) != null
    private fun actionable(node: AccessibilityNodeInfo?, longClick: Boolean = false): AccessibilityNodeInfo? {
        var target = node ?: return null
        while (if (longClick) !target.isLongClickable else !target.isClickable) target = target.parent ?: return null
        return target.takeIf { it.isEnabled && it.isVisibleToUser }
    }
    private suspend fun click(label: String) {
        awaitUi("click $label") { actionable(find(label)) != null }
        assertTrue(actionable(find(label))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
