package ru.papajon.meshcentralmobile.input

import ru.papajon.meshcentralmobile.mesh.KvmInputEncoder
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.mesh.RemoteViewport

enum class HardwareMouseAction { Move, Buttons, Scroll, Exit, Cancel }

/** Owns button edges; Android emits both DOWN/UP and BUTTON_PRESS/RELEASE for the same click. */
class HardwareMouseController(private val input: NativeRemoteInputSink, private val onCursor: (Int, Int) -> Unit = { _, _ -> }) {
    private val pressed = mutableSetOf<MouseButton>()
    private var x = 0
    private var y = 0
    fun handle(action: HardwareMouseAction, localX: Float, localY: Float, viewport: RemoteViewport,
        enabled: Boolean, buttons: Set<MouseButton> = emptySet(), vertical: Float = 0f,
        horizontal: Float = 0f, shiftDown: Boolean = false) {
        if (!enabled || action == HardwareMouseAction.Cancel || action == HardwareMouseAction.Exit) {
            releaseAll(); return
        }
        if (!viewport.hitsImage(localX, localY) && pressed.isEmpty()) return
        val point = viewport.toRemote(localX, localY)
        x = point.first; y = point.second
        // Release tracked buttons even outside the image; only new presses require an image hit.
        (pressed - buttons).toList().forEach { input.sendMouseButton(x, y, it, false); pressed.remove(it) }
        if (viewport.hitsImage(localX, localY)) (buttons - pressed).forEach {
            if (input.sendMouseButton(x, y, it, true)) pressed.add(it)
        }
        if (action == HardwareMouseAction.Move) input.sendMouseMove(x, y)
        if (action == HardwareMouseAction.Scroll) {
            if (vertical.isFinite() && vertical != 0f) input.sendMouseScroll(x, y, vertical * 360f)
            // MeshCentral 1.3.1 has only a vertical wheel field. Shift+wheel is the compatible
            // horizontal-scroll convention; applications which ignore it cannot scroll sideways.
            if (horizontal.isFinite() && horizontal != 0f) {
                val acquired = !shiftDown && input.sendRemoteKey(KvmInputEncoder.VK_SHIFT, true)
                if (shiftDown || acquired) try { input.sendMouseScroll(x, y, -horizontal * 360f) }
                finally { if (acquired) input.sendRemoteKey(KvmInputEncoder.VK_SHIFT, false) }
            }
        }
        onCursor(x, y)
    }
    fun releaseAll() {
        pressed.toList().forEach { input.sendMouseButton(x, y, it, false) }
        pressed.clear()
    }
}
