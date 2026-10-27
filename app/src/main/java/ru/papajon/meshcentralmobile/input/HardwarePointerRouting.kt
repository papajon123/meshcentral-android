package ru.papajon.meshcentralmobile.input

import android.view.InputDevice
import android.view.MotionEvent

/** What the hardware mouse branch must do with a native pointer event. */
internal enum class HardwarePointerRoute {
    /** Pointer hover: move the remote cursor. */
    Hover,
    /** Android 14+ native touchpad pinch (one pointer plus scale axis). */
    Pinch,
    /** Pointer movement while a mouse contact/button stream is active. */
    Move,
    /** Button state change or the beginning/end of a button stream. */
    Buttons,
    /** Wheel scroll. */
    Scroll,
    /** Pointer left the canvas. */
    Exit,
    /** The stream was cancelled. */
    Cancel,
    /** Continuation of a mouse contact which does not change buttons by itself. */
    Consume,
}

/**
 * Decides which native pointer events belong to the hardware mouse branch.
 *
 * A touchpad reports fingers as SOURCE_TOUCHPAD/SOURCE_MOUSE with TOOL_TYPE_FINGER. Such a contact
 * is a mouse only when it carries a button or a wheel axis, or continues a button stream. A
 * buttonless finger contact, including a multi-finger one, must stay available to Compose's gesture
 * recognizer for pinch/scroll/pan. Pens and erasers always keep their own paths.
 */
internal class HardwarePointerRouting {
    private var buttonStream = false
    private var gestureStream = false

    fun route(event: MotionEvent): HardwarePointerRoute? {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            !event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        ) return null
        val action = event.actionMasked
        // A pen or eraser is never a mouse contact, even when the source says mouse.
        if (event.hasTool(MotionEvent.TOOL_TYPE_STYLUS) || event.hasTool(MotionEvent.TOOL_TYPE_ERASER)) {
            return null
        }
        val buttonBits = event.buttonState and
            (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)
        // No-button contacts are gestures regardless of the reported tool type.
        if (action == MotionEvent.ACTION_DOWN) { gestureStream = false; if (buttonBits == 0) buttonStream = false }
        if (buttonBits != 0) gestureStream = false
        if (gestureStream && action in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            gestureStream = false
            return HardwarePointerRoute.Consume
        }
        event.nativeTouchpadRoute()?.let {
            gestureStream = true
            return it
        }
        if (gestureStream) return HardwarePointerRoute.Consume
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_SCROLL) && event.hasWheelAxis())
            return HardwarePointerRoute.Scroll
        val owns = when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
            MotionEvent.ACTION_SCROLL -> event.hasWheelAxis()
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> true
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP,
            -> buttonBits != 0 || buttonStream
            MotionEvent.ACTION_CANCEL -> buttonStream
            else -> buttonStream
        }
        if (!owns) return null
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS) buttonStream = true
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_HOVER_EXIT
        ) buttonStream = false
        return when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> HardwarePointerRoute.Hover
            MotionEvent.ACTION_MOVE -> HardwarePointerRoute.Move
            MotionEvent.ACTION_SCROLL -> HardwarePointerRoute.Scroll
            MotionEvent.ACTION_HOVER_EXIT -> HardwarePointerRoute.Exit
            MotionEvent.ACTION_CANCEL -> HardwarePointerRoute.Cancel
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> HardwarePointerRoute.Consume
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            -> HardwarePointerRoute.Buttons
            else -> HardwarePointerRoute.Consume
        }
    }

    fun reset() {
        buttonStream = false
        gestureStream = false
    }

    private fun MotionEvent.hasTool(tool: Int) =
        (0 until pointerCount).any { getToolType(it) == tool }

    private fun MotionEvent.hasWheelAxis() =
        remoteWheelAxes().let { it.first != 0f || it.second != 0f }
}

/**
 * MeshCentral has only a vertical wheel field. [MotionEvent.AXIS_SCROLL] is the legacy wheel axis;
 * use it only when neither modern axis carries a delta.
 */
internal fun MotionEvent.remoteWheelAxes(): Pair<Float, Float> {
    val vertical = getAxisValue(MotionEvent.AXIS_VSCROLL).finiteOrZero()
    val horizontal = getAxisValue(MotionEvent.AXIS_HSCROLL).finiteOrZero()
    if (vertical != 0f || horizontal != 0f) return vertical to horizontal
    val legacy = getAxisValue(MotionEvent.AXIS_SCROLL).finiteOrZero()
    if (legacy != 0f) return legacy to 0f
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        // Native gesture axes are display-pixel deltas, not wheel detents. Match touch scroll's
        // 1.5 wheel units per pixel, including every batched historical sample.
        fun pixels(axis: Int): Float = getAxisValue(axis).finiteOrZero() +
            (0 until historySize).sumOf { getHistoricalAxisValue(axis, 0, it).finiteOrZero().toDouble() }.toFloat()
        return -pixels(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE) / 240f to
            pixels(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE) / 240f
    }
    return 0f to 0f
}

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/** Relative pinch factors must be multiplied across historical samples, not discarded. */
internal fun MotionEvent.touchpadPinchFactor(): Float {
    if (android.os.Build.VERSION.SDK_INT < 34) return 1f
    fun valid(value: Float) = if (value.isFinite() && value > 0f) value else 1f
    var factor = 1f
    for (i in 0 until historySize) factor *= valid(getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, 0, i))
    return factor * valid(getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR))
}

/** Native gesture axes are delivered even when the screen cursor is stationary. */
internal fun MotionEvent.nativeTouchpadRoute(): HardwarePointerRoute? {
    if (android.os.Build.VERSION.SDK_INT < 34 || buttonState != 0 ||
        (!isFromSource(InputDevice.SOURCE_MOUSE) && !isFromSource(InputDevice.SOURCE_TOUCHPAD)) ||
        (0 until pointerCount).any { getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) return null
    fun hasAxis(axis: Int) = getAxisValue(axis) != 0f ||
        (0 until historySize).any { getHistoricalAxisValue(axis, 0, it) != 0f }
    return when {
        classification == MotionEvent.CLASSIFICATION_PINCH || hasAxis(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR) -> HardwarePointerRoute.Pinch
        classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE ||
            hasAxis(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE) || hasAxis(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE) -> HardwarePointerRoute.Scroll
        else -> null
    }
}
