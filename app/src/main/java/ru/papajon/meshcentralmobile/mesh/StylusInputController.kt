package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink

enum class StylusAction { HoverEnter, HoverMove, HoverExit, Down, Move, Up, Cancel }

/** Pen policy shared by the Android adapter and JVM protocol tests. No pressure/tilt wire fields. */
class StylusInputController(
    private val input: NativeRemoteInputSink,
    private val phone: Boolean = false,
    private val onPhoneGesture: (List<PhoneTouchPoint>) -> Unit = {},
    private val onCursor: (Int, Int) -> Unit = { _, _ -> },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var hoverTime: Long? = null
    private var touching = false
    private var blockedFingerGesture = false
    private var fingerCount = 0
    private var heldButton: MouseButton? = null
    private var lastPoint = 0 to 0
    private var contactViewport: RemoteViewport? = null
    private var phoneGesture: PhoneTouchGesture? = null
    private var downTime = 0L
    private var hoverExitTime: Long? = null

    val blocksFingerInput: Boolean get() = isHovering(nowMillis()) || touching || blockedFingerGesture

    private fun isHovering(timeMillis: Long) =
        hoverTime?.let { timeMillis - it in 0..HOVER_TIMEOUT_MILLIS } == true

    /** A rejected palm stays rejected until it lifts, even when the pen leaves first. */
    fun observeFingers(count: Int, timeMillis: Long = nowMillis()) {
        fingerCount = count
        if (count == 0) blockedFingerGesture = false
        else if (isHovering(timeMillis) || touching || hoverExitTime?.let { timeMillis - it in 0..HOVER_EXIT_GRACE_MILLIS } == true)
            blockedFingerGesture = true
        if (count >= 2) cancelContact()
    }

    fun handle(
        action: StylusAction, x: Float, y: Float, viewport: RemoteViewport, inputEnabled: Boolean,
        primaryButton: Boolean = false, secondaryButton: Boolean = false, eraser: Boolean = false,
        timeMillis: Long = nowMillis(),
    ) {
        when (action) {
            StylusAction.HoverEnter, StylusAction.HoverMove -> {
                // A fresh hover proves a cancelled physical contact has ended.
                touching = false
                hoverTime = timeMillis
                hoverExitTime = null
            }
            StylusAction.HoverExit -> { hoverTime = null; hoverExitTime = timeMillis }
            StylusAction.Down -> { hoverTime = null; touching = true }
            StylusAction.Up -> touching = false
            StylusAction.Cancel -> { cancelContact(timeMillis); return }
            StylusAction.Move -> Unit
        }
        if (fingerCount > 0 && (isHovering(timeMillis) || touching)) blockedFingerGesture = true
        if (contactViewport != null && contactViewport != viewport) cancelContact()
        if (!inputEnabled || !x.isFinite() || !y.isFinite() || fingerCount >= 2) {
            cancelContact()
            return
        }
        val point = viewport.toRemote(x, y)
        when (action) {
            StylusAction.HoverEnter, StylusAction.HoverMove -> {
                if (!phone && heldButton == null && viewport.hitsImage(x, y) &&
                    input.sendMouseMove(point.first, point.second)) onCursor(point.first, point.second)
            }
            StylusAction.Down -> {
                cancelContact()
                if (!viewport.hitsImage(x, y)) return
                contactViewport = viewport
                lastPoint = point
                downTime = timeMillis
                if (phone) {
                    phoneGesture = PhoneTouchGesture(point.first, point.second)
                } else {
                    // Latch the button at contact: releasing a side button must not leave it held.
                    val button = when {
                        eraser -> MouseButton.Right
                        secondaryButton -> MouseButton.Middle
                        primaryButton -> MouseButton.Right
                        else -> MouseButton.Left
                    }
                    if (input.sendMouseButton(point.first, point.second, button, true)) {
                        heldButton = button
                        onCursor(point.first, point.second)
                    }
                }
            }
            StylusAction.Move -> {
                phoneGesture?.move(point.first, point.second, timeMillis - downTime)
                if (heldButton != null) {
                    if (input.sendMouseMove(point.first, point.second)) {
                        lastPoint = point
                        onCursor(point.first, point.second)
                    } else cancelContact()
                }
            }
            StylusAction.Up -> {
                val completed = phoneGesture?.finish(point.first, point.second, timeMillis - downTime)
                phoneGesture = null
                if (heldButton != null) lastPoint = point
                if (cancelContact()) onCursor(point.first, point.second)
                if (!completed.isNullOrEmpty()) onPhoneGesture(completed)
            }
            StylusAction.HoverExit, StylusAction.Cancel -> Unit
        }
    }

    /** Release only our successful press; never create input during cancellation/revocation. */
    fun cancelContact(timeMillis: Long? = null): Boolean {
        // A device/source switch can cancel Compose's stream while the physical pen is still down.
        // Keep proximity/palm state and guard the replacement stream, but release our remote press.
        if (timeMillis != null && (touching || isHovering(timeMillis) || blockedFingerGesture))
            hoverExitTime = timeMillis
        val button = heldButton
        heldButton = null
        phoneGesture = null
        contactViewport = null
        return button != null && input.sendMouseButton(lastPoint.first, lastPoint.second, button, false)
    }

    fun reset() {
        cancelContact()
        hoverTime = null
        touching = false
        blockedFingerGesture = false
        fingerCount = 0
        hoverExitTime = null
    }

    fun finishTouchStream() {
        cancelContact()
        hoverTime = null
        touching = false
        blockedFingerGesture = false
        fingerCount = 0
    }

    companion object {
        // HOVER_EXIT is not guaranteed when the pen leaves the window or the app is backgrounded.
        const val HOVER_TIMEOUT_MILLIS = 750L
        // Android may synthesize HOVER_EXIT just before a palm DOWN on a different tool. Keep a
        // short transition guard; an actual finger gesture after the pen leaves works normally.
        const val HOVER_EXIT_GRACE_MILLIS = 250L
    }
}
