package ru.papajon.meshcentralmobile.input

import ru.papajon.meshcentralmobile.mesh.MouseButton

interface NativeRemoteInputSink {
    fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean = false): Boolean
    fun sendUnicodeText(text: String): Int
    fun sendCtrlAltDel(): Boolean
    fun sendMouseMove(x: Int, y: Int): Boolean
    fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean
    fun sendMouseClick(x: Int, y: Int, button: MouseButton = MouseButton.Left): Boolean
    fun sendMouseDoubleClick(x: Int, y: Int): Boolean
    fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean
}
