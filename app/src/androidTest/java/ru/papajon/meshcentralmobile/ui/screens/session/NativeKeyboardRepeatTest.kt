package ru.papajon.meshcentralmobile.ui.screens.session

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton

@RunWith(AndroidJUnit4::class)
class NativeKeyboardRepeatTest {
    @Test
    fun inputConnectionStillForwardsFunctionKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1))
        }
        assertEquals(listOf(112), sink.keys)
    }

    @Test
    fun deletingAnEmojiByCodePointKeepsTheEditorValid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.commitText("A😀", 1)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            assertEquals("A", field.text.toString())
        }
        assertEquals(1, sink.backspaces)
    }

    @Test
    fun emptyCommitRemovesCompositionFromBothEditors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("тест", 1)
            connection.commitText("", 1)
            assertEquals("", field.text.toString())
        }
        assertEquals(4, sink.backspaces)
    }

    @Test
    fun backspaceInsideComposingWordUpdatesEditorOnEveryRepeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("привет", 1)
            repeat(6) { count ->
                connection.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, count))
                assertEquals("The IME must observe each deletion", "привет".dropLast(count + 1), field.text.toString())
            }
        }
        assertEquals(6, sink.backspaces)
    }

    @Test
    fun repeatedBackspaceUpdatesBothRemoteAndLocalEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            field.setText("hello")
            field.setSelection(5)
            val connection = field.onCreateInputConnection(EditorInfo())!!

            repeat(5) { count ->
                connection.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, count))
            }

            assertEquals("", field.text.toString())
        }
        assertEquals(5, sink.backspaces)
    }

    private class RecordingSink : NativeRemoteInputSink {
        var backspaces = 0
        val keys = mutableListOf<Int>()
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            if (down) keys += remoteKeyCode
            if (remoteKeyCode == 8 && down) backspaces++
            return true
        }
        override fun sendUnicodeText(text: String): Int = text.length
        override fun sendCtrlAltDel(): Boolean = true
        override fun sendMouseMove(x: Int, y: Int): Boolean = true
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean = true
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean = true
        override fun sendMouseDoubleClick(x: Int, y: Int): Boolean = true
        override fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean = true
    }
}
