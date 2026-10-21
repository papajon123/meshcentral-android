package ru.papajon.meshcentralmobile.ui.screens.session

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Exercises a real IME long press; requires an enabled soft keyboard with an accessible Delete key. */
class NativeKeyboardImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun holdingTheSoftKeyboardDeleteClearsTheWholeComposingWord() {
        val sink = RecordingSink()
        compose.setContent {
            MeshCentralMobileTheme {
                Box(Modifier.fillMaxSize()) { NativeKeyboardCapture(sink, active = true) }
            }
        }
        compose.waitUntil(10000) {
            val focused = compose.activity.currentFocus
            focused is RemoteInputField && focused.hasWindowFocus()
        }
        lateinit var field: RemoteInputField
        compose.runOnIdle {
            field = compose.activity.currentFocus as RemoteInputField
            // This test exercises a real keyboard hold, so establish its window precondition
            // after the activity owns focus rather than racing the activity launch animation.
            field.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            field.onCreateInputConnection(EditorInfo())!!.setComposingText("repeattext", 1)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        var delete: AccessibilityNodeInfo? = null
        val screen = automation.takeScreenshot().let { bitmap ->
            Rect(0, 0, bitmap.width, bitmap.height).also { bitmap.recycle() }
        }
        val bounds = Rect()
        var stableFrames = 0
        val deadline = SystemClock.uptimeMillis() + 15000
        while (stableFrames < 3 && SystemClock.uptimeMillis() < deadline) {
            delete = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .firstNotNullOfOrNull { findDelete(it.root) }
            val next = Rect().also { delete?.getBoundsInScreen(it) }
            stableFrames = if (!next.isEmpty && screen.contains(next) && next == bounds) stableFrames + 1 else 0
            bounds.set(next)
            SystemClock.sleep(100)
        }
        if (delete == null) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val method = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            android.util.Log.e("KeyboardImeTest", "IME precondition failed: method=$method, windowFocus=${field.hasWindowFocus()}, windowTypes=${automation.windows.map { it.type }}")
            java.io.File(context.cacheDir, "keyboard-precondition-failure.png").outputStream().use {
                automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertNotNull("The active IME must expose its Delete key", delete)
        assertTrue("Wait for the keyboard animation before pressing its key", stableFrames >= 3)
        android.util.Log.i("KeyboardImeTest", "Delete key: ${delete!!.contentDescription}, bounds=$bounds")
        fun snapshot(name: String) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.cacheDir, name).outputStream().use {
                automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        snapshot("keyboard-before.png")
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        try { SystemClock.sleep(2500) } finally { touch(MotionEvent.ACTION_UP) }
        snapshot("keyboard-after.png")
        android.util.Log.i("KeyboardImeTest", "Deletion events=${sink.deletions}")
        compose.runOnIdle {
            assertEquals("The local editor must be empty after the hold", "", field.text.toString())
            assertEquals("The remote side must receive all deletions", "", sink.remote.toString())
            assertTrue("A hold must delete more than one character", sink.deletions >= 10)
        }
    }

    private fun findDelete(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = listOfNotNull(node.contentDescription, node.text).joinToString(" ").lowercase()
        if (label.contains("delete") || label.contains("backspace") || label.contains("удалить") || label.contains("стереть")) return node
        repeat(node.childCount) { findDelete(node.getChild(it))?.let { result -> return result } }
        return null
    }

    private class RecordingSink : NativeRemoteInputSink {
        val remote = StringBuilder()
        var deletions = 0
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            if (remoteKeyCode == 8 && down) { deletions++; if (remote.isNotEmpty()) remote.deleteCharAt(remote.lastIndex) }
            return true
        }
        override fun sendUnicodeText(text: String): Int { remote.append(text); return text.length }
        override fun sendCtrlAltDel() = true
        override fun sendMouseMove(x: Int, y: Int) = true
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = true
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = true
        override fun sendMouseDoubleClick(x: Int, y: Int) = true
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = true
    }
}
