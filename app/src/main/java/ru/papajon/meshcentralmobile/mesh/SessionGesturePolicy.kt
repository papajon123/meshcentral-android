package ru.papajon.meshcentralmobile.mesh

import ru.papajon.meshcentralmobile.model.RemoteControlMode

/**
 * What a touch stream means in the live session.
 *
 * The model follows the mainstream remote desktop clients so a phone can actually navigate a large
 * desktop:
 *
 * - **Direct touch**: tap = left click, drag = move the view, hold = right click,
 *   two-finger vertical swipe = remote scroll, pinch = zoom.
 * - **Trackpad**: one finger moves the pointer relatively, tap = left click, hold still = right
 *   click, two-finger swipe = scroll, pinch = zoom.
 * - **Zoom / pan**: gestures only move the view, nothing is sent to the computer.
 *
 * The decision logic lives here, without Compose types, so it can be unit tested.
 */
enum class SessionGestureAction {
    /** Nothing yet: still deciding between click, drag and pan. */
    Pending,

    /** Press and release at the same spot: a left click. */
    LeftClick,

    /** Two taps in a row: the agent's double-click packet. */
    DoubleClick,

    /** Move the viewport, no remote input. */
    ViewPan,

    /** Forward the pointer to the computer (absolute in direct touch, relative on a trackpad). */
    PointerMove,

    /** Button is held down and the pointer follows the finger. */
    DragRemote,

    /** Wheel scroll on the computer. */
    RemoteScroll,

    /** Long press or two-finger tap: right click. */
    RightClick,
}

object SessionGesturePolicy {
    /** Movement beyond this distance starts a view pan in direct touch. */
    const val TAP_SLOP_DP = 24f

    /** Holding still for this long sends a right click without waiting for release. */
    const val LONG_PRESS_MILLIS = 380L

    fun onHold(mode: RemoteControlMode, movedDistanceDp: Float): SessionGestureAction =
        if (mode != RemoteControlMode.PanZoom && mode != RemoteControlMode.Scroll &&
            movedDistanceDp.isFinite() && movedDistanceDp <= TAP_SLOP_DP
        ) SessionGestureAction.RightClick else SessionGestureAction.Pending

    /** Minimum translation/scale change before a two-finger tap becomes a gesture. */
    const val MULTI_TOUCH_SLOP_DP = 18f
    const val PINCH_SLOP = 0.05f

    /** Two taps within this window count as a double click. */
    const val DOUBLE_TAP_WINDOW_MILLIS = 300L

    /** Zoom limits for the view (1 = fit the whole framebuffer on screen). */
    const val MIN_ZOOM = RemoteViewport.MIN_ZOOM
    const val MAX_ZOOM = RemoteViewport.MAX_ZOOM

    /** Down event: always undecided until the finger moves, lifts or the long press fires. */
    fun onDown(): SessionGestureAction = SessionGestureAction.Pending

    /** While one finger is down. */
    fun onSinglePointerMove(
        mode: RemoteControlMode,
        movedBeyondSlop: Boolean,
        viewPanning: Boolean,
    ): SessionGestureAction = when (mode) {
        RemoteControlMode.Trackpad -> SessionGestureAction.PointerMove
        RemoteControlMode.Scroll -> if (movedBeyondSlop) SessionGestureAction.RemoteScroll else SessionGestureAction.Pending

        // Direct touch keeps the view still for small movements (that is a click in progress) and
        // turns a real drag into a view pan.
        RemoteControlMode.DirectTouch -> when {
            viewPanning || movedBeyondSlop -> SessionGestureAction.ViewPan
            else -> SessionGestureAction.PointerMove
        }

        RemoteControlMode.PanZoom -> SessionGestureAction.ViewPan
    }

    /** Decide once per two-finger gesture; tiny movement remains a right-click candidate. */
    fun onMultiTouch(
        mode: RemoteControlMode,
        totalPanXDp: Float,
        totalPanYDp: Float,
        totalZoom: Float,
    ): SessionGestureAction = when {
        mode == RemoteControlMode.PanZoom -> SessionGestureAction.ViewPan
        kotlin.math.abs(totalZoom - 1f) >= PINCH_SLOP -> SessionGestureAction.ViewPan
        kotlin.math.abs(totalPanYDp) >= MULTI_TOUCH_SLOP_DP &&
            kotlin.math.abs(totalPanYDp) > kotlin.math.abs(totalPanXDp) * 1.2f ->
            SessionGestureAction.RemoteScroll
        kotlin.math.abs(totalPanXDp) >= MULTI_TOUCH_SLOP_DP -> SessionGestureAction.ViewPan
        else -> SessionGestureAction.Pending
    }

    /** Finger lifted. */
    fun onUp(
        mode: RemoteControlMode,
        movedBeyondSlop: Boolean,
        longPressReached: Boolean,
        wasMultiTouch: Boolean,
        viewPanning: Boolean,
        rightClickSent: Boolean = false,
    ): SessionGestureAction = when {
        rightClickSent -> SessionGestureAction.Pending
        mode == RemoteControlMode.Scroll -> SessionGestureAction.Pending
        mode == RemoteControlMode.PanZoom -> SessionGestureAction.Pending
        // A short two-finger tap (no pan and no scroll) is a right click in input modes.
        wasMultiTouch -> if (viewPanning) SessionGestureAction.Pending else SessionGestureAction.RightClick

        // The timer normally sends this while the finger is still down. This is a fallback for
        // a release that wins the scheduling race with the timer.
        longPressReached -> if (movedBeyondSlop || viewPanning) {
            if (mode == RemoteControlMode.Trackpad) SessionGestureAction.PointerMove
            else SessionGestureAction.ViewPan
        } else {
            SessionGestureAction.RightClick
        }

        viewPanning || movedBeyondSlop -> when (mode) {
            // A drag on a trackpad already moved the pointer; a drag in direct touch panned the view.
            RemoteControlMode.Trackpad -> SessionGestureAction.PointerMove
            RemoteControlMode.DirectTouch -> SessionGestureAction.ViewPan
            RemoteControlMode.PanZoom -> SessionGestureAction.ViewPan
            RemoteControlMode.Scroll -> SessionGestureAction.Pending
        }

        mode == RemoteControlMode.PanZoom -> SessionGestureAction.Pending
        else -> SessionGestureAction.LeftClick
    }

    /** Two taps close together become one double click instead of two single clicks. */
    fun isDoubleTap(previousTapUpMillis: Long, nowMillis: Long): Boolean {
        if (previousTapUpMillis <= 0L) return false
        val delta = nowMillis - previousTapUpMillis
        return delta in 0..DOUBLE_TAP_WINDOW_MILLIS
    }

    fun nextZoom(current: Float, gestureZoom: Float): Float {
        if (!gestureZoom.isFinite() || gestureZoom <= 0f) return current
        return (current * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
