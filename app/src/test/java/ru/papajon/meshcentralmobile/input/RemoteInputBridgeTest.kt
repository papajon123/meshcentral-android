package ru.papajon.meshcentralmobile.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MouseButton

class RemoteInputBridgeTest {
    @Test
    fun ctrlAButtonSendsACompleteSelectAllChord() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : NativeRemoteInputSink {
            override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
                events += remoteKeyCode to down
                return true
            }
            override fun sendUnicodeText(text: String) = 0
            override fun sendCtrlAltDel() = false
            override fun sendMouseMove(x: Int, y: Int) = false
            override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = false
            override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = false
            override fun sendMouseDoubleClick(x: Int, y: Int) = false
            override fun sendMouseScroll(x: Int, y: Int, delta: Float) = false
        }

        assertTrue(RemoteInputBridge.sendCtrlShortcutToRemote(sink, KeyEvent.KEYCODE_A) is RemoteInputResult.Sent)
        assertEquals(listOf(17 to true, 65 to true, 65 to false, 17 to false), events)
    }
}
