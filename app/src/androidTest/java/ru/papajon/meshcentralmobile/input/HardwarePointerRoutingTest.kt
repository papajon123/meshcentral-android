package ru.papajon.meshcentralmobile.input

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwarePointerRoutingTest {
    private fun event(action: Int, source: Int = InputDevice.SOURCE_MOUSE,
        tool: Int = MotionEvent.TOOL_TYPE_FINGER, buttons: Int = 0, pointers: Int = 1,
        vertical: Float = 0f, horizontal: Float = 0f, legacy: Float = 0f): MotionEvent {
        val properties = Array(pointers) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = tool }
        }
        val coordinates = Array(pointers) { index ->
            MotionEvent.PointerCoords().apply {
                x = 10f + index * 10f; y = 10f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
                setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
                setAxisValue(MotionEvent.AXIS_SCROLL, legacy)
            }
        }
        return MotionEvent.obtain(0L, 0L, action, pointers, properties, coordinates, 0, buttons,
            1f, 1f, 0, 0, source, 0)
    }

    private fun route(routing: HardwarePointerRouting, event: MotionEvent): HardwarePointerRoute? =
        try { routing.route(event) } finally { event.recycle() }

    @Test fun buttonlessMouseFingerStaysWithGestureRecognizer() {
        val routing = HardwarePointerRouting()
        assertNull(route(routing, event(MotionEvent.ACTION_DOWN)))
        assertNull(route(routing, event(MotionEvent.ACTION_MOVE, pointers = 2)))
        assertNull(route(routing, event(MotionEvent.ACTION_UP)))
    }

    @Test fun buttonlessMouseToolContactsAlsoRemainGestures() {
        val routing = HardwarePointerRouting()
        assertNull(route(routing, event(MotionEvent.ACTION_DOWN, tool = MotionEvent.TOOL_TYPE_MOUSE)))
        assertNull(route(routing, event(MotionEvent.ACTION_MOVE, tool = MotionEvent.TOOL_TYPE_MOUSE, pointers = 2)))
        assertNull(route(routing, event(MotionEvent.ACTION_UP, tool = MotionEvent.TOOL_TYPE_MOUSE)))
    }

    @Test fun gestureScrollAxisOnMoveIsWheelNotPointerMotion() {
        val routing = HardwarePointerRouting()
        assertEquals(HardwarePointerRoute.Scroll, route(routing,
            event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, legacy = 0.5f)))
    }

    @Test fun touchpadFingerButtonStreamKeepsUpUntilUp() {
        val routing = HardwarePointerRouting()
        assertEquals(HardwarePointerRoute.Buttons, route(routing,
            event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, buttons = MotionEvent.BUTTON_PRIMARY)))
        assertEquals(HardwarePointerRoute.Move, route(routing,
            event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD)))
        assertEquals(HardwarePointerRoute.Buttons, route(routing,
            event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHPAD)))
        assertNull(route(routing, event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD)))
    }

    @Test fun scrollUsesModernThenLegacyAxis() {
        val routing = HardwarePointerRouting()
        assertEquals(HardwarePointerRoute.Scroll, route(routing,
            event(MotionEvent.ACTION_SCROLL, vertical = -1f)))
        assertEquals(HardwarePointerRoute.Scroll, route(routing,
            event(MotionEvent.ACTION_SCROLL, legacy = 1f)))
        assertNull(route(routing, event(MotionEvent.ACTION_SCROLL)))
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun nativeTouchpadAxesIncludeHistoricalSamplesAndOwnTheirRelease() {
        val routing = HardwarePointerRouting()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        fun coords(pinch: Float, y: Float) = arrayOf(MotionEvent.PointerCoords().apply {
            x = 20f; this.y = 30f
            setAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, pinch)
            setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, y)
        })
        val pinch = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 1, props, coords(1.2f, 0f),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            pinch.addBatch(2, coords(1.25f, 0f), 0)
            assertEquals(HardwarePointerRoute.Pinch, routing.route(pinch))
            assertEquals(1.5f, pinch.touchpadPinchFactor(), 0.001f)
        } finally { pinch.recycle() }
        assertEquals(HardwarePointerRoute.Consume, route(routing, event(MotionEvent.ACTION_UP)))
        val scroll = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 1, props, coords(0f, 40f),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHPAD, 0)
        try {
            scroll.addBatch(2, coords(0f, 80f), 0)
            assertEquals(HardwarePointerRoute.Scroll, routing.route(scroll))
            assertEquals(-0.5f, scroll.remoteWheelAxes().first, 0.001f)
        } finally { scroll.recycle() }
    }

    @Test fun penReleaseCannotStealAnOwnedMouseButtonRelease() {
        val routing = HardwarePointerRouting()
        route(routing, event(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_PRIMARY))
        assertNull(route(routing, event(MotionEvent.ACTION_UP, tool = MotionEvent.TOOL_TYPE_STYLUS)))
        assertEquals(HardwarePointerRoute.Buttons, route(routing, event(MotionEvent.ACTION_UP)))
    }

    @Test fun mouseHoverAndStylusKeepTheirOwnPaths() {
        val routing = HardwarePointerRouting()
        assertEquals(HardwarePointerRoute.Hover, route(routing,
            event(MotionEvent.ACTION_HOVER_MOVE, tool = MotionEvent.TOOL_TYPE_MOUSE)))
        assertNull(route(routing, event(MotionEvent.ACTION_DOWN, tool = MotionEvent.TOOL_TYPE_STYLUS,
            buttons = MotionEvent.BUTTON_PRIMARY)))
        assertNull(route(routing, event(MotionEvent.ACTION_DOWN, tool = MotionEvent.TOOL_TYPE_ERASER)))
    }
}
