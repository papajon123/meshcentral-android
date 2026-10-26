package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.model.RemoteControlMode

/**
 * Touch model tests. These encode the intended behaviour of the live session: nobody can navigate a
 * 5120x1440 desktop on a phone if dragging moves the pointer instead of the view.
 */
class SessionGesturePolicyTest {
    @Test
    fun scrollModeUsesOneFingerForTheRemoteWheelWithoutClicking() {
        assertEquals(
            SessionGestureAction.RemoteScroll,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.Scroll, true, false),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.Scroll, false, false),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onHold(RemoteControlMode.Scroll, 0f),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(RemoteControlMode.Scroll, true, false, false, false),
        )
    }

    @Test
    fun holdWithinToleranceClicksRightOnceAndReleaseDoesNothing() {
        assertEquals(
            SessionGestureAction.RightClick,
            SessionGesturePolicy.onHold(RemoteControlMode.DirectTouch, movedDistanceDp = 18f),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(
                RemoteControlMode.DirectTouch, false, true, false, false,
                rightClickSent = true,
            ),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onHold(RemoteControlMode.DirectTouch, movedDistanceDp = 30f),
        )
    }

    @Test
    fun twoFingerVerticalSwipeScrollsInBothInputModes() {
        assertEquals(
            SessionGestureAction.RemoteScroll,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.Trackpad, 2f, -30f, 1.01f),
        )
        assertEquals(
            SessionGestureAction.RemoteScroll,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.DirectTouch, 2f, 30f, 1.01f),
        )
        assertEquals(
            SessionGestureAction.ViewPan,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.PanZoom, 2f, 30f, 1.01f),
        )
    }

    @Test
    fun pinchAndHorizontalSwipeChangeTheViewWithoutScrolling() {
        assertEquals(
            SessionGestureAction.ViewPan,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.DirectTouch, 0f, 20f, 1.09f),
        )
        assertEquals(
            SessionGestureAction.ViewPan,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.DirectTouch, 25f, 2f, 1f),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onMultiTouch(RemoteControlMode.DirectTouch, 2f, 3f, 1.01f),
        )
    }

    @Test
    fun directTouchDragMovesTheViewInsteadOfThePointer() {
        val panning = SessionGesturePolicy.onSinglePointerMove(
            mode = RemoteControlMode.DirectTouch,
            movedBeyondSlop = true,
            viewPanning = false,
        )
        assertEquals(SessionGestureAction.ViewPan, panning)

        val smallMove = SessionGesturePolicy.onSinglePointerMove(
            mode = RemoteControlMode.DirectTouch,
            movedBeyondSlop = false,
            viewPanning = false,
        )
        assertEquals(SessionGestureAction.PointerMove, smallMove)
    }

    @Test
    fun movementNeverStartsAnUnrequestedLeftButtonDrag() {
        assertEquals(
            SessionGestureAction.PointerMove,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.DirectTouch, false, false),
        )
        assertEquals(
            SessionGestureAction.PointerMove,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.Trackpad, false, false),
        )
        assertEquals(
            SessionGestureAction.ViewPan,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.DirectTouch, true, true),
        )
        assertEquals(
            SessionGestureAction.PointerMove,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.Trackpad, true, false),
        )
    }

    @Test
    fun trackpadMovesThePointerWithoutLongPress() {
        assertEquals(
            SessionGestureAction.PointerMove,
            SessionGesturePolicy.onSinglePointerMove(RemoteControlMode.Trackpad, true, false),
        )
    }

    @Test
    fun tapIsALeftClickOnRelease() {
        assertEquals(
            SessionGestureAction.LeftClick,
            SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, false, false, false, false),
        )
        assertEquals(
            SessionGestureAction.LeftClick,
            SessionGesturePolicy.onUp(RemoteControlMode.Trackpad, false, false, false, false),
        )
    }

    @Test
    fun twoFingerTapIsARightClickAndTwoFingerDragIsNotAClick() {
        assertEquals(SessionGestureAction.RightClick, SessionGesturePolicy.onUp(RemoteControlMode.Trackpad, false, false, true, false))
        assertEquals(SessionGestureAction.Pending, SessionGesturePolicy.onUp(RemoteControlMode.PanZoom, false, false, true, false))
        assertEquals(SessionGestureAction.Pending, SessionGesturePolicy.onUp(RemoteControlMode.Scroll, false, false, true, false))
        assertEquals(SessionGestureAction.Pending, SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, false, false, true, false, rightClickSent = true))
        assertEquals(
            SessionGestureAction.RightClick,
            SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, false, false, wasMultiTouch = true, viewPanning = false),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, true, false, wasMultiTouch = true, viewPanning = true),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(RemoteControlMode.Trackpad, false, false, wasMultiTouch = true, viewPanning = true),
        )
    }

    @Test
    fun viewOnlyModeNeverClicks() {
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(RemoteControlMode.PanZoom, false, false, false, false),
        )
        assertEquals(
            SessionGestureAction.Pending,
            SessionGesturePolicy.onUp(RemoteControlMode.PanZoom, false, true, false, false),
        )
    }

    @Test
    fun holdingThenMovingDoesNotPressTheLeftButton() {
        assertEquals(
            SessionGestureAction.ViewPan,
            SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, movedBeyondSlop = true, longPressReached = true, wasMultiTouch = false, viewPanning = false),
        )
    }

    @Test
    fun longPressWithoutMovingIsARightClick() {
        // This is a fallback if the release reaches the handler before the long-press timer runs.
        assertEquals(
            SessionGestureAction.RightClick,
            SessionGesturePolicy.onUp(RemoteControlMode.DirectTouch, movedBeyondSlop = false, longPressReached = true, wasMultiTouch = false, viewPanning = false),
        )
        assertEquals(
            SessionGestureAction.RightClick,
            SessionGesturePolicy.onUp(RemoteControlMode.Trackpad, movedBeyondSlop = false, longPressReached = true, wasMultiTouch = false, viewPanning = false),
        )
    }

    @Test
    fun doubleTapIsRecognisedInsideTheWindowOnly() {
        assertTrue(SessionGesturePolicy.isDoubleTap(previousTapUpMillis = 1_000L, nowMillis = 1_200L))
        assertFalse(SessionGesturePolicy.isDoubleTap(previousTapUpMillis = 1_000L, nowMillis = 1_600L))
        assertFalse(SessionGesturePolicy.isDoubleTap(previousTapUpMillis = 0L, nowMillis = 1_100L))
        assertFalse(SessionGesturePolicy.isDoubleTap(previousTapUpMillis = 1_200L, nowMillis = 1_000L))
    }

    @Test
    fun zoomIsClampedAndRejectsBrokenFactors() {
        assertEquals(1f, SessionGesturePolicy.nextZoom(1f, 0.2f), 0.001f)
        assertEquals(16f, SessionGesturePolicy.nextZoom(4f, 10f), 0.001f)
        assertEquals(2f, SessionGesturePolicy.nextZoom(1f, 2f), 0.001f)
        assertEquals(2f, SessionGesturePolicy.nextZoom(2f, Float.NaN), 0.001f)
        assertEquals(2f, SessionGesturePolicy.nextZoom(2f, 0f), 0.001f)
    }
}
