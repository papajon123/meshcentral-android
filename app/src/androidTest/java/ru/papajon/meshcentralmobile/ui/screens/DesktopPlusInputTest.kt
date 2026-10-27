package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.util.concurrent.CopyOnWriteArrayList

class DesktopPlusInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sink = DesktopRecordingSink()
    private val mode = mutableStateOf(RemoteControlMode.DirectTouch)
    private val fingers = mutableStateOf(TwoFingerAction.Default)
    private val pinch = mutableStateOf(true)
    private val allowed = mutableStateOf(true)
    private val windowFocus = mutableStateOf(true)
    private val shown = mutableStateOf(true)
    private var scale = 2f
    private var pan = 0f
    private var origin = Offset.Zero
    private var downTime = 0L

    private fun canvas(phone: Boolean = false, blockingOverlay: Boolean = false) {
        val frame = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val device = MeshDevice("node//desktop-plus", "mesh//fixture", "Desktop fixture", conn = 1,
            agentId = if (phone) 14 else 3, rights = 8, remoteInput = PhoneRemoteInput.Enabled)
        compose.setContent { MeshCentralMobileTheme {
            var zoom by remember { mutableStateOf(2f) }
            var panX by remember { mutableStateOf(0f) }
            var panY by remember { mutableStateOf(0f) }
            val size = with(LocalDensity.current) { 400.toDp() }
            val platformWindow = androidx.compose.ui.platform.LocalWindowInfo.current
            val testWindow = remember(platformWindow) {
                object : androidx.compose.ui.platform.WindowInfo by platformWindow {
                    override val isWindowFocused: Boolean get() = windowFocus.value
                }
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides testWindow) {
            Box(Modifier.fillMaxSize().padding(40.dp)) {
                if (shown.value) SessionCanvas(RemoteDesktopState(device = device,
                    connectionState = ConnectionState.Connected, framebuffer = frame, remoteWidth = 400,
                    remoteHeight = 400, handshakeComplete = true, inputLocked = !allowed.value),
                    mode.value, false, {}, zoom, panX, panY,
                    { z, x, y -> zoom = z; panX = x; panY = y; scale = z; pan = y }, {},
                    inputSink = sink, modifier = Modifier.requiredSize(size).testTag("desktop_canvas")
                        .onGloballyPositioned { origin = it.localToScreen(Offset.Zero) },
                    twoFingerAction = fingers.value, pinchEnabled = pinch.value)
                if (blockingOverlay) Box(Modifier.requiredSize(size).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                })
            }
            }
        } }
        compose.waitForIdle()
        SystemClock.sleep(300)
    }

    private fun swipe(fingerCount: Int, spread: Boolean = false) {
        compose.onNodeWithTag("desktop_canvas").performTouchInput {
            repeat(fingerCount) { down(it, Offset(120f + 60f * it, 120f)) }
            repeat(10) { step ->
                repeat(fingerCount) { id ->
                    updatePointerTo(id, Offset(120f + 60f * id + if (spread) (id * 2 - 1) * (step + 1) * 5f else 0f,
                        120f + if (spread) 0f else (step + 1) * 10f))
                }
                move()
            }
            repeat(fingerCount) { up(it) }
        }
    }

    @Test fun threeFingersScrollAllComputerModesWithoutClicksOrZoom() {
        canvas()
        for (value in RemoteControlMode.entries) {
            compose.runOnIdle { mode.value = value; sink.events.clear() }
            swipe(3)
            compose.runOnIdle {
                assertTrue(value.name, sink.events.any { it.startsWith("wheel:") })
                assertFalse(value.name, sink.events.any { it.startsWith("button:") || it == "click" })
                assertEquals(2f, scale, 0.001f)
            }
        }
    }

    @Test fun twoFingerPreferenceSwitchesBetweenRemoteScrollAndLocalPan() {
        canvas()
        compose.runOnIdle { fingers.value = TwoFingerAction.Pan }
        swipe(2)
        compose.runOnIdle { assertTrue(pan > 0f); assertFalse(sink.events.any { it.startsWith("wheel:") }); sink.events.clear(); fingers.value = TwoFingerAction.Scroll }
        swipe(2)
        compose.runOnIdle { assertTrue(sink.events.any { it.startsWith("wheel:") }) }
    }

    @Test fun pinchSwitchPreventsScalingButRetainsTwoFingerPan() {
        canvas()
        compose.runOnIdle { pinch.value = false; fingers.value = TwoFingerAction.Pan }
        swipe(2, spread = true)
        compose.runOnIdle { assertEquals(2f, scale, 0.001f); pinch.value = true }
        swipe(2, spread = true)
        compose.runOnIdle { assertTrue(scale > 2f) }
    }

    @Test fun phoneRetainsDefaultPanAndSupportsThreeFingerScroll() {
        canvas(phone = true)
        swipe(2)
        compose.runOnIdle { assertTrue("phone pan=$pan events=${sink.events}", pan > 0f); assertTrue("phone events=${sink.events}", sink.events.isEmpty()) }
        swipe(3)
        compose.runOnIdle { assertTrue(sink.events.any { it.startsWith("wheel:") }); assertFalse(sink.events.contains("click")) }
    }

    @Test fun viewOnlyThreeFingersNeverSendInput() {
        canvas()
        compose.runOnIdle { allowed.value = false }
        swipe(3)
        compose.runOnIdle { assertTrue(sink.events.isEmpty()) }
    }

    @Test fun changingFromTwoToThreeAndBackDoesNotClickOrResumePinch() {
        canvas()
        compose.runOnIdle { fingers.value = TwoFingerAction.Pan }
        compose.onNodeWithTag("desktop_canvas").performTouchInput {
            down(0, Offset(100f, 100f)); down(1, Offset(180f, 100f))
            updatePointerTo(0, Offset(100f, 130f)); updatePointerTo(1, Offset(180f, 130f)); move()
            down(2, Offset(260f, 130f))
            repeat(6) { i ->
                repeat(3) { id -> updatePointerTo(id, Offset(100f + 80 * id, 150f + i * 12)) }
                move()
            }
            up(2)
            updatePointerTo(0, Offset(60f, 240f)); updatePointerTo(1, Offset(220f, 240f)); move()
            up(0); up(1)
        }
        compose.runOnIdle {
            assertTrue(sink.events.any { it.startsWith("wheel:") })
            assertFalse(sink.events.any { it == "click" || it.startsWith("button:") })
            assertEquals(2f, scale, 0.001f)
        }
    }

    @Test fun nativeMouseHoverButtonsDragAndBothWheelAxesReachSinkOnce() {
        canvas()
        // Android 16 rejects injected HOVER_ENTER; the dispatcher synthesizes the enter from the first move.
        mouse(MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
        mouse(MotionEvent.ACTION_HOVER_MOVE, 120f, 120f)
        for ((button, name) in listOf(MotionEvent.BUTTON_PRIMARY to "Left", MotionEvent.BUTTON_SECONDARY to "Right", MotionEvent.BUTTON_TERTIARY to "Middle")) {
            compose.runOnIdle { sink.events.clear() }
            mouse(MotionEvent.ACTION_DOWN, 120f, 120f, button)
            mouse(MotionEvent.ACTION_MOVE, 150f, 150f, button)
            mouse(MotionEvent.ACTION_UP, 150f, 150f)
            compose.runOnIdle {
                assertEquals(listOf("button:$name:true", "button:$name:false"), sink.events.filter { it.startsWith("button:") })
                assertTrue(sink.events.any { it.startsWith("move:") })
                assertFalse(sink.events.contains("click"))
            }
        }
        compose.runOnIdle { sink.events.clear() }
        mouse(MotionEvent.ACTION_SCROLL, 160f, 160f, vertical = 1f)
        mouse(MotionEvent.ACTION_SCROLL, 160f, 160f, horizontal = 1f)
        compose.runOnIdle {
            assertEquals(listOf("wheel:360.0", "key:16:true", "wheel:-360.0", "key:16:false"), sink.events.toList())
        }
    }

    @Test fun firstMouseClickAndHoverWorkBeforeWindowFocusAndLossReleasesButtons() {
        canvas()
        compose.runOnIdle { windowFocus.value = false }
        mouse(MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
        mouse(MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.BUTTON_PRIMARY)
        compose.runOnIdle { windowFocus.value = true }
        compose.runOnIdle {
            assertTrue(sink.events.any { it.startsWith("move:") })
            assertEquals(listOf("button:Left:true"), sink.events.filter { it.startsWith("button:") })
        }
        compose.runOnIdle { windowFocus.value = false }
        compose.runOnIdle { assertEquals(1, sink.events.count { it == "button:Left:false" }) }
        mouse(MotionEvent.ACTION_UP, 120f, 120f)
        compose.runOnIdle { assertEquals(1, sink.events.count { it == "button:Left:false" }) }
    }

    @Test fun mousePressIsReleasedOnPermissionLossAndDisposal() {
        canvas()
        mouse(MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.BUTTON_PRIMARY)
        compose.runOnIdle { allowed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, sink.events.count { it == "button:Left:false" }); allowed.value = true }
        mouse(MotionEvent.ACTION_UP, 120f, 120f)
        mouse(MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.BUTTON_SECONDARY)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, sink.events.count { it == "button:Right:false" }) }
        mouse(MotionEvent.ACTION_UP, 120f, 120f)
    }

    @Test fun disabledPinchAndHorizontalScrollGestureNeverBecomeRightClicks() {
        canvas()
        compose.runOnIdle { pinch.value = false }
        swipe(2, spread = true)
        compose.runOnIdle { assertTrue(sink.events.isEmpty()); fingers.value = TwoFingerAction.Scroll }
        compose.onNodeWithTag("desktop_canvas").performTouchInput {
            down(0, Offset(100f, 100f)); down(1, Offset(180f, 100f))
            repeat(10) { i ->
                updatePointerTo(0, Offset(110f + i * 10, 100f))
                updatePointerTo(1, Offset(190f + i * 10, 100f)); move()
            }
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(sink.events.isEmpty()); assertEquals(2f, scale, 0.001f) }
        // A stationary two-finger tap still works.
        compose.onNodeWithTag("desktop_canvas").performTouchInput {
            down(0, Offset(100f, 100f)); down(1, Offset(180f, 100f)); up(0); up(1)
        }
        compose.runOnIdle { assertEquals(listOf("click"), sink.events.toList()) }
    }

    @Test fun physicalMouseClickInPhoneSessionReplaysOneTouch() {
        canvas(phone = true)
        mouse(MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_UP, 120f, 120f)
        compose.waitUntil(5000) { sink.events.contains("button:Left:false") }
        compose.runOnIdle {
            assertEquals(listOf("button:Left:true", "button:Left:false"), sink.events.filter { it.startsWith("button:") })
            assertFalse(sink.events.contains("click"))
        }
    }

    @Test fun fingerToolFromMouseSourceDoesNotDuplicateTouchInput() {
        canvas()
        mouse(MotionEvent.ACTION_DOWN, 120f, 120f, MotionEvent.BUTTON_PRIMARY, tool = MotionEvent.TOOL_TYPE_FINGER)
        mouse(MotionEvent.ACTION_MOVE, 145f, 145f, MotionEvent.BUTTON_PRIMARY, tool = MotionEvent.TOOL_TYPE_FINGER)
        mouse(MotionEvent.ACTION_UP, 145f, 145f, tool = MotionEvent.TOOL_TYPE_FINGER)
        mouse(MotionEvent.ACTION_SCROLL, 145f, 145f, vertical = 1f, tool = MotionEvent.TOOL_TYPE_FINGER)
        compose.runOnIdle {
            assertEquals(listOf("button:Left:true", "button:Left:false"), sink.events.filter { it.startsWith("button:") })
            assertEquals(1, sink.events.count { it == "wheel:360.0" })
            assertFalse(sink.events.contains("click"))
            assertEquals(2f, scale, 0.001f)
        }
    }

    @Test fun mouseSourceFingerPinchZoomsInsteadOfMovingPointer() {
        canvas()
        gesture(pinchStrokes())
        compose.runOnIdle {
            assertTrue("scale=$scale events=${sink.events}", scale > 2f)
            assertFalse("pinch moved the remote pointer: ${sink.events}", sink.events.any { it.startsWith("move:") })
        }
    }

    @Test fun mouseSourceFingerTwoFingerSwipeScrollsWithoutMovingOrClicking() {
        canvas()
        gesture(twoFingerSwipeStrokes())
        compose.runOnIdle {
            assertTrue("no wheel in ${sink.events}", sink.events.any { it.startsWith("wheel:") })
            assertFalse("unexpected remote input ${sink.events}",
                sink.events.any { it.startsWith("move:") || it.startsWith("button:") || it == "click" })
        }
    }

    @Test fun mouseSourceFingerExplicitPanStaysLocal() {
        canvas()
        compose.runOnIdle { fingers.value = TwoFingerAction.Pan }
        gesture(twoFingerSwipeStrokes())
        compose.runOnIdle {
            assertTrue("pan=$pan events=${sink.events}", pan > 0f)
            assertTrue("explicit pan reached the computer: ${sink.events}", sink.events.isEmpty())
        }
    }

    @Test fun legacyScrollAxisStillSendsWheel() {
        canvas()
        mouse(MotionEvent.ACTION_SCROLL, 160f, 160f, tool = MotionEvent.TOOL_TYPE_FINGER, legacyScroll = 1f)
        compose.runOnIdle { assertEquals(listOf("wheel:360.0"), sink.events.toList()) }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun nativeOnePointerPinchAxisChangesZoomWithoutRemoteClick() {
        canvas()
        mouse(MotionEvent.ACTION_DOWN, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, pinchFactor = 1f, throughWindow = true)
        mouse(MotionEvent.ACTION_MOVE, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, pinchFactor = 1.5f, throughWindow = true)
        mouse(MotionEvent.ACTION_UP, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, throughWindow = true)
        compose.runOnIdle { assertEquals(3f, scale, 0.01f); assertTrue(sink.events.isEmpty()) }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun nativeScrollAxisClaimedAfterDownCannotTurnIntoTap() {
        canvas()
        mouse(MotionEvent.ACTION_DOWN, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, throughWindow = true)
        mouse(MotionEvent.ACTION_MOVE, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, gestureScrollY = 80f, throughWindow = true)
        mouse(MotionEvent.ACTION_UP, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, throughWindow = true)
        compose.runOnIdle {
            assertEquals(listOf("wheel:-120.0"), sink.events.toList())
            assertEquals(2f, scale, 0.001f)
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun nativeGestureOverLocalOverlayCannotControlCoveredDesktop() {
        canvas(blockingOverlay = true)
        mouse(MotionEvent.ACTION_DOWN, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, pinchFactor = 1f, throughWindow = true)
        mouse(MotionEvent.ACTION_MOVE, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, pinchFactor = 1.5f, throughWindow = true)
        mouse(MotionEvent.ACTION_UP, 180f, 180f, tool = MotionEvent.TOOL_TYPE_FINGER, throughWindow = true)
        compose.runOnIdle { assertEquals(2f, scale, 0.01f); assertTrue(sink.events.isEmpty()) }
    }

    private data class PointerStroke(val action: Int, val points: List<Offset>)

    private fun pointerIndex(index: Int) = index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT

    private fun twoFingerSwipeStrokes(): List<PointerStroke> {
        val left = 120f
        val right = 240f
        val startY = 80f
        val strokes = mutableListOf(
            PointerStroke(MotionEvent.ACTION_DOWN, listOf(Offset(left, startY))),
            PointerStroke(MotionEvent.ACTION_POINTER_DOWN or pointerIndex(1),
                listOf(Offset(left, startY), Offset(right, startY))),
        )
        repeat(8) { step ->
            val y = startY + (step + 1) * 30f
            strokes += PointerStroke(MotionEvent.ACTION_MOVE, listOf(Offset(left, y), Offset(right, y)))
        }
        val endY = startY + 8 * 30f
        strokes += PointerStroke(MotionEvent.ACTION_POINTER_UP or pointerIndex(1),
            listOf(Offset(left, endY), Offset(right, endY)))
        strokes += PointerStroke(MotionEvent.ACTION_UP, listOf(Offset(left, endY)))
        return strokes
    }

    private fun pinchStrokes(): List<PointerStroke> {
        val y = 200f
        return listOf(
            PointerStroke(MotionEvent.ACTION_DOWN, listOf(Offset(120f, y))),
            PointerStroke(MotionEvent.ACTION_POINTER_DOWN or pointerIndex(1),
                listOf(Offset(120f, y), Offset(200f, y))),
            PointerStroke(MotionEvent.ACTION_MOVE, listOf(Offset(80f, y), Offset(240f, y))),
            PointerStroke(MotionEvent.ACTION_MOVE, listOf(Offset(40f, y), Offset(280f, y))),
            PointerStroke(MotionEvent.ACTION_POINTER_UP or pointerIndex(1),
                listOf(Offset(40f, y), Offset(280f, y))),
            PointerStroke(MotionEvent.ACTION_UP, listOf(Offset(40f, y))),
        )
    }

    /** Injects a real SOURCE_MOUSE finger stream in one batch so no long-press timer wins. */
    private fun gesture(strokes: List<PointerStroke>) {
        compose.waitForIdle()
        downTime = SystemClock.uptimeMillis()
        strokes.forEachIndexed { index, stroke ->
            val now = downTime + index
            val properties = stroke.points.mapIndexed { pointerId, _ ->
                MotionEvent.PointerProperties().apply {
                    id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray()
            val coordinates = stroke.points.map { point ->
                MotionEvent.PointerCoords().apply {
                    x = origin.x + point.x; y = origin.y + point.y
                    pressure = 1f; size = 0.1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(downTime, now, stroke.action, stroke.points.size, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try {
                assertTrue("inject ${stroke.action}",
                    InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        compose.waitForIdle()
    }

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int = 0,
        vertical: Float = 0f, horizontal: Float = 0f, tool: Int = MotionEvent.TOOL_TYPE_MOUSE,
        legacyScroll: Float = 0f, pinchFactor: Float = 0f, gestureScrollY: Float = 0f, throughWindow: Boolean = false) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = origin.x + x; this.y = origin.y + y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical); setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_SCROLL, legacyScroll)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                setAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, pinchFactor)
                setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, gestureScrollY)
            }
        })
        val event = MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            if (throughWindow) {
                // Exact native gesture samples at the production Window boundary. System
                // injection may batch/resample unclassified events; raw contacts still use it.
                compose.runOnUiThread {
                    val windowOrigin = IntArray(2)
                    compose.activity.window.decorView.getLocationOnScreen(windowOrigin)
                    event.offsetLocation(-windowOrigin[0].toFloat(), -windowOrigin[1].toFloat())
                    compose.activity.window.callback.dispatchTouchEvent(event)
                }
            } else assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally { event.recycle() }
        compose.waitForIdle()
    }
}

internal class DesktopRecordingSink : NativeRemoteInputSink {
    val events = CopyOnWriteArrayList<String>()
    private fun record(value: String): Boolean { events += value; return true }
    override fun sendMouseMove(x: Int, y: Int) = record("move:$x:$y")
    override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = record("button:$button:$down")
    override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = record("click")
    override fun sendMouseDoubleClick(x: Int, y: Int) = record("double")
    override fun sendMouseScroll(x: Int, y: Int, delta: Float) = record("wheel:$delta")
    override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = record("key:$remoteKeyCode:$down")
    override fun sendUnicodeText(text: String): Int { events += "text:$text"; return text.length }
    override fun sendCtrlAltDel() = record("cad")
}
