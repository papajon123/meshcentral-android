package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.papajon.meshcentralmobile.model.RemoteControlMode

class DesktopGestureOptionsTest {
    @Test fun rawMotionDisqualifiesTwoFingerTapEvenWhenTransformIsDisabled() {
        for ((x, y, zoom) in listOf(Triple(0f, 0f, 1.2f), Triple(25f, 0f, 1f), Triple(14f, 14f, 1f))) {
            assertEquals(SessionGestureAction.Pending, SessionGesturePolicy.onUp(
                RemoteControlMode.DirectTouch, false, false, true, false,
                multiTouchMoved = SessionGesturePolicy.multiTouchMoved(x, y, zoom)))
        }
        assertEquals(SessionGestureAction.RightClick, SessionGesturePolicy.onUp(
            RemoteControlMode.DirectTouch, false, false, true, false,
            multiTouchMoved = SessionGesturePolicy.multiTouchMoved(1f, 1f, 1.01f)))
    }

    @Test fun threeFingersScrollEvenInPanModeAndNeverPinch() {
        assertEquals(SessionGestureAction.RemoteScroll, SessionGesturePolicy.onMultiTouch(
            RemoteControlMode.PanZoom, 0f, 30f, 1.5f, pointerCount = 3))
    }
    @Test fun explicitPanOverridesDefaultRemoteScroll() {
        assertEquals(SessionGestureAction.ViewPan, SessionGesturePolicy.onMultiTouch(
            RemoteControlMode.DirectTouch, 0f, 30f, 1f, twoFingerAction = TwoFingerAction.Pan))
    }
    @Test fun disabledPinchDoesNotTurnSmallMovementIntoPan() {
        assertEquals(SessionGestureAction.Pending, SessionGesturePolicy.onMultiTouch(
            RemoteControlMode.DirectTouch, 0f, 1f, 1.5f, pinchEnabled = false))
    }
    @Test fun explicitScrollStillAllowsPinchUnlessDisabled() {
        assertEquals(SessionGestureAction.ViewPan, SessionGesturePolicy.onMultiTouch(
            RemoteControlMode.DirectTouch, 0f, 30f, 1.2f, twoFingerAction = TwoFingerAction.Scroll))
        assertEquals(SessionGestureAction.RemoteScroll, SessionGesturePolicy.onMultiTouch(
            RemoteControlMode.DirectTouch, 0f, 30f, 1.2f, twoFingerAction = TwoFingerAction.Scroll, pinchEnabled = false))
    }
}
