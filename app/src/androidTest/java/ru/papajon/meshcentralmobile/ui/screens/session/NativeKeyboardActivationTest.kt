package ru.papajon.meshcentralmobile.ui.screens.session

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton

class NativeKeyboardActivationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun attachedEditorClosesAndReopensWithoutDuplicatingComposition() {
        val active = mutableStateOf(true)
        val remote = StringBuilder()
        val sink = object : NativeRemoteInputSink {
            override fun sendUnicodeText(text: String): Int { remote.append(text); return text.length }
            override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = true
            override fun sendCtrlAltDel() = true
            override fun sendMouseMove(x: Int, y: Int) = true
            override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = true
            override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = true
            override fun sendMouseDoubleClick(x: Int, y: Int) = true
            override fun sendMouseScroll(x: Int, y: Int, delta: Float) = true
        }
        val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        compose.setContent { NativeKeyboardCapture(sink, active.value) }
        compose.waitUntil(5_000) { compose.activity.currentFocus is RemoteInputField && manager.isAcceptingText }
        compose.runOnUiThread {
            val field = compose.activity.currentFocus as RemoteInputField
            assertTrue(field.isAttachedToWindow)
            assertTrue(field.onCreateInputConnection(EditorInfo())!!.setComposingText("word", 1))
        }
        compose.runOnIdle { assertEquals("word", remote.toString()); active.value = false }
        compose.waitUntil(5_000) { compose.activity.currentFocus !is RemoteInputField && !manager.isAcceptingText }
        compose.runOnIdle { active.value = true }
        compose.waitUntil(5_000) { compose.activity.currentFocus is RemoteInputField && manager.isAcceptingText }
        compose.runOnUiThread {
            val field = compose.activity.currentFocus as RemoteInputField
            assertTrue(field.onCreateInputConnection(EditorInfo())!!.commitText("!", 1))
        }
        compose.runOnIdle { assertEquals("word!", remote.toString()) }
    }
}
