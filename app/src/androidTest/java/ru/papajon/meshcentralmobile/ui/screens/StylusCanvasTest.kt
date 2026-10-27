package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.session.StylusHint
import ru.papajon.meshcentralmobile.ui.screens.session.rememberStylusHint
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.util.concurrent.CopyOnWriteArrayList

/** Real MotionEvent dispatch through the production canvas, with synthetic devices and no server. */
class StylusCanvasTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val input = Sink()
    private val mode = mutableStateOf(RemoteControlMode.Trackpad)
    private val enabled = mutableStateOf(true)
    private val permitted = mutableStateOf(true)
    private val shown = mutableStateOf(true)
    private val dragArmed = mutableStateOf(false)
    private var origin = Offset.Zero
    private var canvasCoordinates: LayoutCoordinates? = null
    private var zoom = 1f
    private var used = 0
    private var downTime = 0L
    private var originalWindowCallback: Window.Callback? = null
    private var activeHover: MotionEvent? = null
    private var activeTouch: MotionEvent? = null

    @Before fun clearPreviousInjectedPointerState() = releaseInjectedPointerState()

    private fun releaseInjectedPointerState() {
        // UiAutomation uses a shared virtual device across test Activities. Android 16 keeps
        // its hover/contact state even after a window closes; release both streams explicitly.
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = origin.x + 100.5f; y = origin.y + 200.5f })
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT)) {
            val event = MotionEvent.obtain(now, now, action, 1, properties, coordinates, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true) }
            finally { event.recycle() }
            // No active stream is also a valid cleanup state; actual test events still assert delivery.
        }
    }

    @After fun restoreWindowCallback() {
        // Android 16 validates injected streams across Activity/test boundaries. Finish both
        // streams even when an assertion failed, otherwise the next test inherits a held pointer.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        activeTouch?.let { event ->
            try { event.action = MotionEvent.ACTION_CANCEL; automation.injectInputEvent(event, true) }
            finally { event.recycle(); activeTouch = null }
        }
        activeHover?.let { event ->
            try { event.action = MotionEvent.ACTION_HOVER_EXIT; automation.injectInputEvent(event, true) }
            finally { event.recycle(); activeHover = null }
        }
        releaseInjectedPointerState()
        originalWindowCallback?.let { original ->
            compose.runOnUiThread { compose.activity.window.callback = original }
        }
    }

    private fun canvas(phone: Boolean = false, initialZoom: Float = 1f) {
        val frame = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val device = MeshDevice("node//stylus", "mesh//fixture", "Stylus fixture", conn = 1,
            agentId = if (phone) 14 else 3, rights = 8, remoteInput = PhoneRemoteInput.Enabled)
        compose.setContent { MeshCentralMobileTheme {
            var scale by remember { mutableStateOf(initialZoom) }
            var panX by remember { mutableStateOf(0f) }
            var panY by remember { mutableStateOf(0f) }
            val size = with(LocalDensity.current) { 400.toDp() }
            // Deliberate nonzero layout origin catches accidental view-relative MotionEvent x/y.
            Box(Modifier.fillMaxSize().padding(start = 40.dp, top = 70.dp)) {
                if (shown.value) SessionCanvas(
                    RemoteDesktopState(device = device, connectionState = ConnectionState.Connected,
                        framebuffer = frame, remoteWidth = 400, remoteHeight = 400,
                        handshakeComplete = true, inputLocked = !permitted.value),
                    mode.value, dragArmed.value, { dragArmed.value = false }, scale, panX, panY,
                    { z, x, y -> scale = z; zoom = z; panX = x; panY = y }, {},
                    inputSink = input,
                    modifier = Modifier.requiredSize(size).testTag("stylus_canvas")
                        .onGloballyPositioned { canvasCoordinates = it; origin = it.localToScreen(Offset.Zero) },
                    stylusPrecisionEnabled = enabled.value, onStylusUsed = { used++ })
            }
        } }
        compose.waitForIdle()
        compose.runOnUiThread {
            val original = compose.activity.window.callback
            originalWindowCallback = original
            compose.activity.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    // Exact packet assertions need the injected samples, not Android's frame-time
                    // resampling of short DOWN/MOVE intervals. Keep real system input dispatch;
                    // disable batching only for this test Activity's current touch stream.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        compose.activity.window.decorView.requestUnbufferedDispatch(event)
                    }
                    return original.dispatchTouchEvent(event)
                }
            }
        }
        // Activity/IME window transitions run on Android's clock, outside Compose's test clock.
        SystemClock.sleep(350)
        compose.waitForIdle()
    }

    @Test fun penHoverAndDragHaveExactBytesInEveryComputerMode() {
        assertPenHoverAndDragBytes()
    }

    @Test fun shortPenSampleIntervalsHaveExactBytesInEveryComputerMode() {
        // Android may extrapolate MOVE coordinates when samples are 2..20 ms apart.
        // Pin a device sample interval independently of emulator/frame scheduling.
        assertPenHoverAndDragBytes(moveAfterDownMillis = 10L)
    }

    private fun assertPenHoverAndDragBytes(moveAfterDownMillis: Long? = null) {
        canvas()
        for (controlMode in RemoteControlMode.entries) {
            compose.runOnIdle { mode.value = controlMode; input.packets.clear() }
            event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
            event(MotionEvent.ACTION_HOVER_MOVE, 120f, 210f)
            event(MotionEvent.ACTION_HOVER_EXIT, 120f, 210f)
            event(MotionEvent.ACTION_DOWN, 100f, 200f)
            event(MotionEvent.ACTION_MOVE, 150f, 250f,
                eventTimeMillis = moveAfterDownMillis?.let { downTime + it })
            event(MotionEvent.ACTION_UP, 150f, 250f)
            compose.runOnIdle {
                assertEquals(controlMode.name, listOf("0002000a0000006400c8", "0002000a0000007800d2",
                    "0002000a0002006400c8", "0002000a0000009600fa", "0002000a0004009600fa"), input.packets.toList())
            }
        }
        assertTrue(used > 0)
    }

    @Test fun sideButtonsAndEraserDoNotGenerateExtraFingerClicks() {
        canvas()
        for ((tool, button, mask) in listOf(Triple(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY, "08"),
            Triple(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY, "20"),
            Triple(MotionEvent.TOOL_TYPE_ERASER, 0, "08"))) {
            compose.runOnIdle { input.packets.clear() }
            event(MotionEvent.ACTION_DOWN, 100f, 200f, tool, button)
            event(MotionEvent.ACTION_UP, 100f, 200f, tool)
            assertEquals(listOf("0002000a00${mask}006400c8", "0002000a00${if (mask == "08") "10" else "40"}006400c8"), input.packets.toList())
        }
    }

    @Test fun cancelReleasesHeldButtonAndDoesNotClick() {
        canvas()
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        event(MotionEvent.ACTION_CANCEL, 100f, 200f)
        compose.runOnIdle { assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList()) }
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        assertTrue("A replacement touch stream may be a palm beside the cancelled pen", input.packets.isEmpty())
        SystemClock.sleep(300)
        event(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("Finger input must recover after cancellation",
            listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
    }

    @Test fun penTakesOverFingerDragWithoutPrematureButtonRelease() {
        canvas()
        compose.runOnIdle { dragArmed.value = true }
        event(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(Finger(100f, 200f), Pen(150f, 250f)))
        pointers(MotionEvent.ACTION_MOVE, listOf(Finger(100f, 200f), Pen(180f, 280f)))
        pointers(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(Finger(100f, 200f), Pen(180f, 280f)))
        event(MotionEvent.ACTION_UP, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8",
            "0002000a0002009600fa", "0002000a000000b40118", "0002000a000400b40118"), input.packets.toList())
    }

    @Test fun revocationAndDisposalReleasePenWithoutStartingAnotherPress() {
        canvas()
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        compose.runOnIdle { permitted.value = false }
        event(MotionEvent.ACTION_MOVE, 150f, 250f)
        event(MotionEvent.ACTION_UP, 150f, 250f)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
        compose.runOnIdle { permitted.value = true; input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 100f, 200f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        assertEquals(listOf("0002000a0008006400c8", "0002000a0010006400c8"), input.packets.toList())
        event(MotionEvent.ACTION_UP, 100f, 200f)
    }

    @Test fun palmDuringPenContactIsIgnoredButTwoActualFingersZoom() {
        canvas()
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(Pen(100f, 200f), Finger(150f, 180f)))
        pointers(MotionEvent.ACTION_MOVE, listOf(Pen(120f, 210f), Finger(200f, 220f)))
        pointers(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), listOf(Pen(120f, 210f), Finger(150f, 150f), Finger(250f, 250f)))
        pointers(MotionEvent.ACTION_MOVE, listOf(Pen(120f, 210f), Finger(100f, 100f), Finger(300f, 300f)))
        pointers(MotionEvent.ACTION_POINTER_UP or (2 shl 8), listOf(Pen(120f, 210f), Finger(100f, 100f), Finger(300f, 300f)))
        pointers(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(Pen(120f, 210f), Finger(100f, 100f)))
        event(MotionEvent.ACTION_UP, 120f, 210f)
        compose.runOnIdle {
            assertEquals(listOf("0002000a0002006400c8", "0002000a0000007800d2", "0002000a0004007800d2"), input.packets.toList())
            assertTrue("Two fingers must still zoom", zoom > 1f)
        }
    }

    @Test fun hoverRejectsPalmThenRestoresFingerAfterExitGuard() {
        canvas()
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        compose.runOnIdle { assertTrue("Palm tap must not send a click: ${input.packets}", input.packets.isEmpty()) }
        SystemClock.sleep(300)
        event(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 100f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
    }

    @Test fun turningPrecisionOffRestoresSelectedFingerMode() {
        canvas()
        compose.runOnIdle { enabled.value = false; mode.value = RemoteControlMode.PanZoom }
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        event(MotionEvent.ACTION_MOVE, 150f, 250f)
        event(MotionEvent.ACTION_UP, 150f, 250f)
        compose.runOnIdle { assertTrue(input.packets.isEmpty()); assertEquals(0, used) }
    }

    @Test fun penLeavingCanvasForPanelRestoresFingerInput() {
        canvas()
        compose.runOnIdle { mode.value = RemoteControlMode.DirectTouch }
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        // Compose sends Exit to the canvas with this native HOVER_MOVE, not HOVER_EXIT.
        event(MotionEvent.ACTION_HOVER_MOVE, 100f, -30f)
        event(MotionEvent.ACTION_HOVER_EXIT, 100f, -30f)
        SystemClock.sleep(300)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 120f, 210f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        event(MotionEvent.ACTION_UP, 120f, 210f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals(listOf("0002000a0002007800d2", "0002000a0004007800d2"), input.packets.toList())
    }

    @Test fun palmFromTouchscreenDuringPenStrokeDoesNotClick() {
        canvas()
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        // Separate physical sources cancel the old stream. UiAutomation injects both through
        // one virtual device, so deliver that cancellation before starting its replacement.
        event(MotionEvent.ACTION_CANCEL, 100f, 200f)
        event(MotionEvent.ACTION_DOWN, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        event(MotionEvent.ACTION_UP, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals("Changing input source cancels the stroke but must not admit the palm",
            listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
        SystemClock.sleep(300)
        compose.runOnIdle { input.packets.clear(); mode.value = RemoteControlMode.DirectTouch }
        event(MotionEvent.ACTION_DOWN, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        event(MotionEvent.ACTION_UP, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals(listOf("0002000a0002009600fa", "0002000a0004009600fa"), input.packets.toList())
    }

    @Test fun trackpadTapAfterPenHoverClicksAtPenPosition() {
        canvas()
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        event(MotionEvent.ACTION_HOVER_EXIT, 100f, 200f)
        SystemClock.sleep(300)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
    }

    @Test fun trackpadTapAfterZoomedPenHoverUsesCanvasCoordinates() {
        canvas(initialZoom = 2f)
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        event(MotionEvent.ACTION_HOVER_EXIT, 100f, 200f)
        SystemClock.sleep(300)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf("0002000a0002009600c8", "0002000a0004009600c8"), input.packets.toList())
    }

    @Test fun hoverAfterCancelledPenRestoresFingerInput() {
        canvas()
        compose.runOnIdle { mode.value = RemoteControlMode.DirectTouch }
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        event(MotionEvent.ACTION_CANCEL, 100f, 200f)
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        event(MotionEvent.ACTION_HOVER_EXIT, 100f, 200f)
        SystemClock.sleep(300)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        event(MotionEvent.ACTION_UP, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        assertEquals(listOf("0002000a0002009600fa", "0002000a0004009600fa"), input.packets.toList())
    }

    @Test fun trackpadTapAfterPenReleaseUsesTheFinalPixelAtZoom() {
        canvas(initialZoom = 2f)
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        event(MotionEvent.ACTION_UP, 150f, 250f)
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        event(MotionEvent.ACTION_UP, 300f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(listOf("0002000a000200af00e1", "0002000a000400af00e1"), input.packets.toList())
    }

    @Test fun phonePenTapHoldAndSwipeIgnoreHoverAndSideButtons() {
        canvas(phone = true)
        event(MotionEvent.ACTION_HOVER_ENTER, 100f, 200f)
        event(MotionEvent.ACTION_HOVER_MOVE, 110f, 210f)
        event(MotionEvent.ACTION_HOVER_EXIT, 110f, 210f)
        assertTrue(input.packets.isEmpty())
        for (duration in listOf(20L, 450L)) {
            compose.runOnIdle { input.packets.clear() }
            event(MotionEvent.ACTION_DOWN, 100f, 200f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
            SystemClock.sleep(duration)
            event(MotionEvent.ACTION_UP, 100f, 200f)
            compose.waitUntil(3000) { input.packets.size == 2 }
            assertEquals(listOf("0002000a0002006400c8", "0002000a0004006400c8"), input.packets.toList())
        }
        compose.runOnIdle { input.packets.clear() }
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        SystemClock.sleep(70)
        event(MotionEvent.ACTION_MOVE, 150f, 250f)
        SystemClock.sleep(70)
        event(MotionEvent.ACTION_UP, 180f, 280f)
        compose.waitUntil(3000) { input.packets.lastOrNull() == "0002000a000400b40118" }
        assertEquals(listOf("0002000a0002006400c8", "0002000a0000009600fa", "0002000a000000b40118",
            "0002000a000400b40118"), input.packets.toList())
    }

    @Test fun phoneWithoutInputPermissionNeverSendsPen() {
        canvas(phone = true)
        compose.runOnIdle { permitted.value = false }
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        event(MotionEvent.ACTION_MOVE, 150f, 250f)
        event(MotionEvent.ACTION_UP, 150f, 250f)
        compose.runOnIdle { assertTrue(input.packets.isEmpty()) }
    }

    @Test fun phoneReplayCompletionKeepsPenContactProtection() {
        canvas(phone = true)
        compose.mainClock.autoAdvance = false
        event(MotionEvent.ACTION_DOWN, 100f, 200f)
        SystemClock.sleep(400)
        event(MotionEvent.ACTION_UP, 100f, 200f)
        assertEquals("Replay must still be holding the remote pointer", 1, input.packets.size)
        compose.mainClock.advanceTimeByFrame()
        event(MotionEvent.ACTION_DOWN, 110f, 210f)
        assertEquals("Busy replay must not start a second remote contact", 1, input.packets.size)
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals("Replay must have completed", 2, input.packets.size) }
        compose.runOnIdle { input.packets.clear() }
        // Release UiAutomation's virtual stream before replacing its source. cancelContact
        // preserves an existing physical contact; it cannot restore one incorrectly lost on replay.
        event(MotionEvent.ACTION_CANCEL, 110f, 210f)
        event(MotionEvent.ACTION_DOWN, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        event(MotionEvent.ACTION_UP, 150f, 250f, MotionEvent.TOOL_TYPE_FINGER,
            source = InputDevice.SOURCE_TOUCHSCREEN)
        compose.runOnIdle { assertTrue("Replay completion must preserve palm rejection", input.packets.isEmpty()) }
    }

    @Test fun settingsToggleAndFirstUseFlagSurviveRepositoryReload() {
        val name = "stylus-test-${System.nanoTime()}"
        val context = compose.activity
        val store = SettingsRepository(context, name)
        try {
            assertTrue(store.getSettings().stylusPrecisionEnabled)
            assertFalse(store.getSettings().stylusHintSeen)
            compose.setContent { MeshCentralMobileTheme {
                SettingsScreen(store.settingsState.value, "https://example.invalid/", false,
                    {}, {}, {}, {}, {}, {}, {}, onStylusPrecisionChanged = store::updateStylusPrecision)
            } }
            val label = context.getString(ru.papajon.meshcentralmobile.R.string.stylus_precision_title)
            compose.onNodeWithText(label).performScrollTo().assertIsOn().performClick().assertIsOff()
            assertFalse(SettingsRepository(context, name).getSettings().stylusPrecisionEnabled)
            compose.onNodeWithText(label).performClick().assertIsOn()
            val lockLabel = context.getString(ru.papajon.meshcentralmobile.R.string.settings_lock_now)
            compose.onNodeWithText(lockLabel).performScrollTo()
            assertTrue("Setting and lock action must not overlap",
                compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.bottom <=
                    compose.onNodeWithText(lockLabel).fetchSemanticsNode().boundsInRoot.top)
            screenshot("stylus-settings")
            compose.runOnIdle { store.markStylusHintSeen() }
            assertTrue(SettingsRepository(context, name).getSettings().stylusPrecisionEnabled)
            assertTrue(SettingsRepository(context, name).getSettings().stylusHintSeen)
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun firstUseHintIsVisibleOnceAndDoesNotReturnAfterReopening() {
        val seen = mutableStateOf(false)
        val show = mutableStateOf(true)
        var claims = 0
        lateinit var hint: StylusHint
        compose.setContent { MeshCentralMobileTheme {
            if (show.value) {
                hint = rememberStylusHint(seen.value, { seen.value = true; claims++ }, phone = false)
                androidx.compose.material3.SnackbarHost(hint.host)
            }
        } }
        compose.runOnIdle { repeat(5) { hint.onUse() } }
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.stylus_hint_desktop)).assertIsDisplayed()
        screenshot("stylus-first-use")
        compose.runOnIdle { assertEquals(1, claims); hint.host.currentSnackbarData?.dismiss() }
        compose.waitForIdle()
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { show.value = true }
        compose.waitForIdle()
        compose.runOnIdle { hint.onUse(); assertEquals(1, claims); assertNull(hint.host.currentSnackbarData) }
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun event(action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0,
        source: Int = InputDevice.SOURCE_STYLUS, eventTimeMillis: Long? = null) =
        pointers(action, listOf(Point(x, y, tool)), buttons, source, eventTimeMillis)

    private fun pointers(action: Int, points: List<Point>, buttons: Int = 0, source: Int = InputDevice.SOURCE_STYLUS,
        eventTimeMillis: Long? = null) {
        compose.waitForIdle()
        if (action == MotionEvent.ACTION_DOWN && activeTouch?.source?.let { it != source } == true) {
            // Injected pen and touchscreen events share virtual device -1. Android 16 rejects
            // a replacement DOWN before the view can cancel the old source. Send that source-
            // change cancellation explicitly, through the same real system input dispatcher.
            val previous = requireNotNull(activeTouch)
            previous.action = MotionEvent.ACTION_CANCEL
            try {
                assertTrue("System rejected source-change cancellation",
                    InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(previous, true))
            } finally { previous.recycle(); activeTouch = null }
            compose.waitForIdle()
        }
        compose.runOnUiThread { canvasCoordinates?.takeIf { it.isAttached }?.let { origin = it.localToScreen(Offset.Zero) } }
        val now = eventTimeMillis ?: SystemClock.uptimeMillis()
        SystemClock.sleep((now - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val properties = points.mapIndexed { index, point -> MotionEvent.PointerProperties().apply { id = index; toolType = point.tool } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
            // Aim at pixel centers, not float-sensitive borders between adjacent framebuffer pixels.
            x = origin.x + point.x + 0.5f; y = origin.y + point.y + 0.5f; pressure = 1f; size = 0.1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, now, action, points.size, properties, coordinates, 0,
            buttons, 1f, 1f, 0, 0, source, 0)
        try {
            assertTrue("System rejected injected action ${event.actionMasked}",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    activeHover?.recycle(); activeHover = MotionEvent.obtain(event)
                }
                MotionEvent.ACTION_HOVER_EXIT -> { activeHover?.recycle(); activeHover = null }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activeTouch?.recycle(); activeTouch = null }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                    activeTouch?.recycle(); activeTouch = MotionEvent.obtain(event)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = (0 until points.size).filter { it != event.actionIndex }
                    activeTouch?.recycle()
                    activeTouch = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE,
                        remaining.size, remaining.map { properties[it] }.toTypedArray(),
                        remaining.map { coordinates[it] }.toTypedArray(), 0, buttons, 1f, 1f, 0, 0, source, 0)
                }
            }
        }
        finally { event.recycle() }
        compose.waitForIdle()
    }

    private data class Point(val x: Float, val y: Float, val tool: Int)
    private fun Pen(x: Float, y: Float) = Point(x, y, MotionEvent.TOOL_TYPE_STYLUS)
    private fun Finger(x: Float, y: Float) = Point(x, y, MotionEvent.TOOL_TYPE_FINGER)

    private class Sink : NativeRemoteInputSink {
        val packets = CopyOnWriteArrayList<String>()
        private fun record(packet: ByteArray): Boolean { packets += packet.joinToString("") { "%02x".format(it.toInt() and 255) }; return true }
        override fun sendMouseMove(x: Int, y: Int) = record(KvmInputEncoder.mouseMove(x, y))
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = record(KvmInputEncoder.mouseButton(x, y, button, down))
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean { KvmInputEncoder.mouseClick(x,y,button).forEach(::record); return true }
        override fun sendMouseDoubleClick(x: Int, y: Int): Boolean { KvmInputEncoder.mouseDoubleClick(x,y).forEach(::record); return true }
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = record(KvmInputEncoder.mouseScroll(x,y,delta))
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = false
        override fun sendUnicodeText(text: String) = 0
        override fun sendCtrlAltDel() = false
    }
}
