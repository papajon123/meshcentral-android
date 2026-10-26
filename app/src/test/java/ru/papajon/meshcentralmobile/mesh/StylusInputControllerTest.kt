package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink

class StylusInputControllerTest {
    private val view = RemoteViewport(800, 600, 800, 600)

    @Test fun hoverAndDragUseAbsoluteKvmBytes() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true)
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        pen.handle(StylusAction.Move, 300f, 250f, view, true)
        pen.handle(StylusAction.Up, 300f, 250f, view, true)
        assertEquals(listOf("0002000a0000006400c8", "0002000a0002006400c8",
            "0002000a0000012c00fa", "0002000a0004012c00fa"), sink.packets)
    }

    @Test fun sideButtonsAndEraserLatchUntilRelease() {
        for ((primary, secondary, eraser, down, up) in listOf(
            Case(true, false, false, "08", "10"), Case(false, true, false, "20", "40"),
            Case(false, false, true, "08", "10"), Case(true, true, false, "20", "40"))) {
            val sink = Sink()
            val pen = StylusInputController(sink)
            pen.handle(StylusAction.Down, 10f, 20f, view, true, primary, secondary, eraser)
            pen.handle(StylusAction.Up, 10f, 20f, view, true)
            assertEquals(listOf("0002000a00${down}000a0014", "0002000a00${up}000a0014"), sink.packets)
        }
    }

    @Test fun zoomPanAndLetterboxMapToFramePixels() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        val transformed = RemoteViewport(600, 400, 1200, 800, zoom = 2f, panX = 50f, panY = -20f)
        pen.handle(StylusAction.Down, 100f, 80f, transformed, true)
        pen.handle(StylusAction.Up, 200f, 180f, transformed, true)
        assertEquals(listOf("0002000a0002015e012c", "0002000a000401c20190"), sink.packets)
        sink.packets.clear()
        val letterbox = RemoteViewport(800, 600, 800, 400)
        for (action in listOf(StylusAction.HoverMove, StylusAction.Down, StylusAction.Up))
            pen.handle(action, 20f, 40f, letterbox, true)
        assertTrue(sink.packets.isEmpty())
    }

    @Test fun palmsRemainBlockedUntilLiftAndTwoFingersCancelDrag() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        pen.handle(StylusAction.HoverEnter, 100f, 200f, view, true)
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(1)
        pen.handle(StylusAction.HoverExit, 100f, 200f, view, true)
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(0)
        assertFalse(pen.blocksFingerInput)
        sink.packets.clear()
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        pen.observeFingers(1)
        pen.handle(StylusAction.Move, 110f, 210f, view, true)
        pen.observeFingers(2)
        pen.handle(StylusAction.Move, 300f, 300f, view, true)
        pen.handle(StylusAction.Up, 300f, 300f, view, true)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0000006e00d2",
            "0002000a0004006e00d2"), sink.packets)
    }

    @Test fun cancelAndRevocationReleaseOnlyOurSuccessfulPressAtLastPoint() {
        for (action in listOf(StylusAction.Cancel, StylusAction.Move)) {
            val sink = Sink()
            val pen = StylusInputController(sink)
            pen.handle(StylusAction.Down, 10f, 20f, view, true, primaryButton = true)
            pen.handle(action, 90f, 90f, view, false)
            pen.reset()
            assertEquals(listOf("0002000a0008000a0014", "0002000a0010000a0014"), sink.packets)
        }
        val sink = Sink().apply { accept = false }
        val pen = StylusInputController(sink)
        pen.handle(StylusAction.Down, 10f, 20f, view, true)
        sink.accept = true
        pen.handle(StylusAction.Up, 10f, 20f, view, true)
        pen.reset()
        assertTrue(sink.packets.isEmpty())
    }

    @Test fun geometryChangeCancelsStrokeAndMoveNeverRestartsIt() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        pen.handle(StylusAction.Down, 10f, 20f, view, true)
        pen.handle(StylusAction.Move, 20f, 30f, view.copy(zoom = 2f), true)
        pen.handle(StylusAction.Up, 20f, 30f, view.copy(zoom = 2f), true)
        assertEquals(listOf("0002000a0002000a0014", "0002000a0004000a0014"), sink.packets)
    }

    @Test fun disabledInputAndNonfiniteCoordinatesNeverStartPress() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        for (action in StylusAction.entries) pen.handle(action, 10f, 20f, view, false)
        pen.handle(StylusAction.Down, Float.NaN, 20f, view, true)
        pen.handle(StylusAction.Up, 10f, 20f, view, true)
        assertTrue(sink.packets.isEmpty())
    }

    @Test fun phoneIgnoresHoverAndButtonsAndPreservesTouchTiming() {
        val sink = Sink()
        val gestures = mutableListOf<List<PhoneTouchPoint>>()
        val pen = StylusInputController(sink, phone = true, onPhoneGesture = { gestures += it })
        pen.handle(StylusAction.HoverMove, 10f, 20f, view, true)
        pen.handle(StylusAction.Down, 10f, 20f, view, true, primaryButton = true, eraser = true, timeMillis = 100)
        pen.handle(StylusAction.Move, 50f, 60f, view, true, timeMillis = 500)
        pen.handle(StylusAction.Up, 80f, 90f, view, true, timeMillis = 750)
        assertTrue(sink.packets.isEmpty())
        assertEquals(listOf(listOf(PhoneTouchPoint(10, 20, 0), PhoneTouchPoint(50, 60, 400),
            PhoneTouchPoint(80, 90, 650))), gestures)
    }

    @Test fun phonePinchAndRevocationDiscardPendingGesture() {
        for (pinch in listOf(true, false)) {
            val gestures = mutableListOf<List<PhoneTouchPoint>>()
            val pen = StylusInputController(Sink(), phone = true, onPhoneGesture = { gestures += it })
            pen.handle(StylusAction.Down, 10f, 20f, view, true)
            if (pinch) pen.observeFingers(2)
            pen.handle(StylusAction.Move, 50f, 60f, view, pinch, timeMillis = 100)
            pen.handle(StylusAction.Up, 50f, 60f, view, true, timeMillis = 200)
            assertTrue(gestures.isEmpty())
        }
    }

    @Test fun syntheticHoverExitImmediatelyBeforePalmDownStillRejectsPalm() {
        val pen = StylusInputController(Sink())
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true, timeMillis = 1000)
        pen.handle(StylusAction.HoverExit, 100f, 200f, view, true, timeMillis = 1100)
        pen.observeFingers(1, timeMillis = 1120)
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(0, timeMillis = 1200)
        pen.observeFingers(1, timeMillis = 1500)
        assertFalse(pen.blocksFingerInput)
    }

    @Test fun staleHoverDoesNotRejectANewFingerGesture() {
        var now = 1000L
        val pen = StylusInputController(Sink(), nowMillis = { now })
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true, timeMillis = 1000)
        assertTrue(pen.blocksFingerInput)
        now = 1751
        assertFalse("Hover must expire even without another native event", pen.blocksFingerInput)
        pen.observeFingers(1, timeMillis = 2000)
        assertFalse("Missing HOVER_EXIT must not disable fingers forever", pen.blocksFingerInput)
    }

    @Test fun freshHoverExtendsTimeoutButRejectedPalmOutlivesIt() {
        var now = 1000L
        val pen = StylusInputController(Sink(), nowMillis = { now })
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true)
        now = 1700
        pen.handle(StylusAction.HoverMove, 110f, 210f, view, true)
        now = 2000
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(1)
        now = 3000
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(0)
        assertFalse(pen.blocksFingerInput)
    }

    @Test fun penDownClearsHoverEvenWhenAndroidOmitsHoverExit() {
        val pen = StylusInputController(Sink())
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true)
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        pen.handle(StylusAction.Up, 100f, 200f, view, true)
        assertFalse(pen.blocksFingerInput)
        pen.finishTouchStream()
        assertFalse(pen.blocksFingerInput)
    }

    @Test fun cancelReleasesContactButKeepsPalmRejectedUntilLift() {
        val sink = Sink()
        val pen = StylusInputController(sink)
        pen.handle(StylusAction.Down, 100f, 200f, view, true, timeMillis = 1000)
        pen.handle(StylusAction.Cancel, 100f, 200f, view, true, timeMillis = 1100)
        pen.observeFingers(1, timeMillis = 1120)
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(1, timeMillis = 2000)
        assertTrue("Rejected palm must stay rejected past the transition guard", pen.blocksFingerInput)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), sink.packets)
        pen.finishTouchStream()
        pen.observeFingers(1, timeMillis = 2100)
        assertFalse(pen.blocksFingerInput)
    }

    @Test fun cancelledPenHoverKeepsTransitionGuardAfterStreamFinishes() {
        var now = 1000L
        val pen = StylusInputController(Sink(), nowMillis = { now })
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true)
        now = 1100
        pen.handle(StylusAction.Cancel, 100f, 200f, view, true)
        pen.finishTouchStream()
        now = 1300
        pen.observeFingers(1)
        assertTrue(pen.blocksFingerInput)
        pen.observeFingers(0)
        now = 1351
        pen.observeFingers(1)
        assertFalse(pen.blocksFingerInput)
    }

    @Test fun ordinaryTouchCancellationDoesNotBlockNextFinger() {
        val pen = StylusInputController(Sink())
        pen.observeFingers(1, timeMillis = 1000)
        pen.handle(StylusAction.Cancel, 100f, 200f, view, true, timeMillis = 1100)
        pen.observeFingers(1, timeMillis = 1120)
        assertFalse("Cancelling a finger-only gesture must not create pen proximity", pen.blocksFingerInput)
    }

    @Test fun hoverAfterCancelledContactRestoresFingersWhenPenLeaves() {
        var now = 1000L
        val pen = StylusInputController(Sink(), nowMillis = { now })
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        now = 1100
        pen.handle(StylusAction.Cancel, 100f, 200f, view, true)
        now = 1200
        pen.handle(StylusAction.HoverMove, 100f, 200f, view, true)
        now = 1300
        pen.handle(StylusAction.HoverExit, 100f, 200f, view, true)
        now = 2100
        pen.observeFingers(1)
        assertFalse("New hover proves that the cancelled pen contact has ended", pen.blocksFingerInput)
    }

    @Test fun cursorFollowsSuccessfulReleaseAtANewPoint() {
        val sink = Sink()
        val cursor = mutableListOf<Pair<Int, Int>>()
        val pen = StylusInputController(sink, onCursor = { x, y -> cursor += x to y })
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        pen.handle(StylusAction.Up, 110f, 210f, view, true)
        assertEquals(listOf(100 to 200, 110 to 210), cursor)
        cursor.clear()
        pen.handle(StylusAction.Down, 100f, 200f, view, true)
        sink.accept = false
        pen.handle(StylusAction.Up, 120f, 220f, view, true)
        assertEquals("Rejected release must not publish an unsent cursor position", listOf(100 to 200), cursor)
    }

    private data class Case(val primary: Boolean, val secondary: Boolean, val eraser: Boolean,
        val down: String, val up: String)

    internal class Sink : NativeRemoteInputSink {
        val packets = mutableListOf<String>()
        var accept = true
        private fun record(packet: ByteArray): Boolean {
            if (accept) packets += packet.joinToString("") { "%02x".format(it.toInt() and 255) }
            return accept
        }
        override fun sendMouseMove(x: Int, y: Int) = record(KvmInputEncoder.mouseMove(x, y))
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) =
            record(KvmInputEncoder.mouseButton(x, y, button, down))
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = false
        override fun sendMouseDoubleClick(x: Int, y: Int) = false
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = false
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = false
        override fun sendUnicodeText(text: String) = 0
        override fun sendCtrlAltDel() = false
    }
}
