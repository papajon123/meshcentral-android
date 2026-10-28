package ru.papajon.meshcentralmobile.ui.screens

import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papajon.meshcentralmobile.input.HardwareKeyboardController
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.ui.screens.session.HardwareKeyboardCapture

@RunWith(AndroidJUnit4::class)
class HardwareKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nonWindowsKeysAndImplicitModifiersNeverUseWindowsExtendedActions() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, isWindows = false)
        for ((androidKey, vk) in listOf(KeyEvent.KEYCODE_SHIFT_RIGHT to 16, KeyEvent.KEYCODE_CTRL_RIGHT to 17,
            KeyEvent.KEYCODE_ALT_RIGHT to 18, KeyEvent.KEYCODE_NUMPAD_ENTER to 13,
            KeyEvent.KEYCODE_DPAD_RIGHT to 39, KeyEvent.KEYCODE_META_RIGHT to 92)) {
            val before = sink.snapshot().size
            controller.handle(key(KeyEvent.ACTION_DOWN, androidKey))
            controller.handle(key(KeyEvent.ACTION_UP, androidKey))
            assertEquals(listOf(SentKey(vk, true, false), SentKey(vk, false, false)), sink.snapshot().drop(before))
        }
        val before = sink.snapshot().size
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_ALT_ON or
            KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, meta))
        controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        assertEquals(listOf(17, 18, 16, 65, 65, 16, 18, 17), sink.snapshot().drop(before).map { it.code })
        assertTrue(sink.snapshot().none { it.extended })
    }

    @Test fun nonWindowsBothShiftsShareOwnershipUntilLastRelease() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, isWindows = false)
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT))
        controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(listOf(SentKey(16, true, false)), sink.snapshot())
        controller.releaseAll()
        assertEquals(listOf(SentKey(16, true, false), SentKey(16, false, false)), sink.snapshot())
    }

    @Test fun rightSideAndNumpadKeysKeepTheirExtendedBits() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)
        for ((androidKey, remote) in listOf(KeyEvent.KEYCODE_ALT_RIGHT to (18 to true),
            KeyEvent.KEYCODE_CTRL_RIGHT to (17 to true), KeyEvent.KEYCODE_NUMPAD_ENTER to (13 to true),
            KeyEvent.KEYCODE_NUMPAD_DIVIDE to (111 to true), KeyEvent.KEYCODE_SHIFT_RIGHT to (161 to false))) {
            val before = sink.snapshot().size
            controller.handle(key(KeyEvent.ACTION_DOWN, androidKey))
            controller.handle(key(KeyEvent.ACTION_UP, androidKey))
            assertEquals("Android key $androidKey", listOf(SentKey(remote.first, true, remote.second),
                SentKey(remote.first, false, remote.second)), sink.snapshot().drop(before))
        }
    }

    @Test
    fun windowDispatchForwardsPairsAndRepeats() {
        val sink = RecordingSink()
        compose.setContent { HardwareKeyboardCapture(sink, enabled = true) }
        waitForWindowFocus()

        val handled = BooleanArray(3)
        compose.runOnUiThread {
            val callback = compose.activity.window.callback
            handled[0] = callback.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, scanCode = 30))
            handled[1] = callback.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, repeatCount = 1, scanCode = 30),
            )
            handled[2] = callback.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, scanCode = 30))
        }

        assertTrue(handled.all { it })
        assertEquals(
            listOf(
                SentKey(65, down = true, extended = false),
                SentKey(65, down = true, extended = false),
                SentKey(65, down = false, extended = false),
            ),
            sink.snapshot(),
        )
    }

    @Test
    fun windowShortcutDispatchClosesItsKeyPair() {
        val sink = RecordingSink()
        compose.setContent { HardwareKeyboardCapture(sink, enabled = true) }
        waitForWindowFocus()

        val handled = booleanArrayOf(false)
        compose.runOnUiThread {
            handled[0] = compose.activity.window.callback.dispatchKeyShortcutEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON, scanCode = 46),
            )
        }

        assertTrue(handled[0])
        assertEquals(
            listOf(
                SentKey(17, down = true, extended = false),
                SentKey(67, down = true, extended = false),
                SentKey(67, down = false, extended = false),
                SentKey(17, down = false, extended = false),
            ),
            sink.snapshot(),
        )
    }

    @Test
    fun ctrlCIsSentAsKeysWithoutUnicodeText() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, scanCode = 29)))
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, metaState = KeyEvent.META_CTRL_ON, scanCode = 46),
            ),
        )
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, metaState = KeyEvent.META_CTRL_ON, scanCode = 46),
            ),
        )
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, scanCode = 29)))

        assertEquals(
            listOf(
                SentKey(17, down = true, extended = false),
                SentKey(67, down = true, extended = false),
                SentKey(67, down = false, extended = false),
                SentKey(17, down = false, extended = false),
            ),
            sink.snapshot(),
        )
        assertEquals("A hardware chord must not also send unicode text", 0, sink.unicodeCalls)
    }

    @Test
    fun altTabSynthesizesTheMissingAltDownAndReleasesIt() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, metaState = KeyEvent.META_ALT_ON, scanCode = 15),
            ),
        )
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, metaState = KeyEvent.META_ALT_ON, scanCode = 15),
            ),
        )

        assertEquals(
            listOf(
                SentKey(18, down = true, extended = false),
                SentKey(9, down = true, extended = false),
                SentKey(9, down = false, extended = false),
                SentKey(18, down = false, extended = false),
            ),
            sink.snapshot(),
        )
    }

    @Test
    fun metaStateMetaKeyIsExtendedAndReleased() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, metaState = KeyEvent.META_META_ON, scanCode = 18),
            ),
        )
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E, metaState = KeyEvent.META_META_ON, scanCode = 18),
            ),
        )

        assertEquals(
            listOf(
                SentKey(91, down = true, extended = true),
                SentKey(69, down = true, extended = false),
                SentKey(69, down = false, extended = false),
                SentKey(91, down = false, extended = true),
            ),
            sink.snapshot(),
        )
    }

    @Test
    fun f12MapsToVirtualKey123() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12, scanCode = 88)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F12, scanCode = 88)))

        assertEquals(
            listOf(
                SentKey(123, down = true, extended = false),
                SentKey(123, down = false, extended = false),
            ),
            sink.snapshot(),
        )
    }

    @Test
    fun twoShiftKeysKeepTheirIndependentPresses() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON, scanCode = 42),
            ),
        )
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.META_SHIFT_ON, scanCode = 54),
            ),
        )
        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, metaState = 0, scanCode = 42),
            ),
        )

        assertEquals(
            "The right Shift is still held, so Shift must stay down remotely",
            listOf(SentKey(16, down = true, extended = false), SentKey(161, true, false), SentKey(16, false, false)),
            sink.snapshot(),
        )

        assertTrue(
            controller.handle(
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, metaState = 0, scanCode = 54),
            ),
        )
        assertEquals(
            listOf(
                SentKey(16, down = true, extended = false),
                SentKey(161, down = true, extended = false),
                SentKey(16, down = false, extended = false),
                SentKey(161, down = false, extended = false),
            ),
            sink.snapshot(),
        )
    }

    @Test fun escapeChordReleasesCaptureAndTabEnterOperateLocalMenu() {
        val sink = RecordingSink()
        val captured = mutableStateOf(true)
        var opened = 0
        compose.setContent {
            HardwareKeyboardCapture(sink, captured.value, onReleaseCapture = { captured.value = false })
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Button(onClick = { opened++ }, modifier = Modifier.testTag("local_menu")) {
                    androidx.compose.material3.Text("Menu")
                }
                androidx.compose.material3.Button(onClick = {}, modifier = Modifier.testTag("second_local_control")) {
                    androidx.compose.material3.Text("Second")
                }
                ru.papajon.meshcentralmobile.ui.screens.session.NativeKeyboardCapture(sink, captured.value)
            }
        }
        waitForWindowFocus()
        compose.runOnUiThread {
            val callback = compose.activity.window.callback
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON))
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON))
        }
        compose.waitForIdle()
        assertFalse(captured.value)
        assertEquals(listOf(SentKey(9, true, false), SentKey(9, false, false)), sink.snapshot())
        // Releasing capture clears the hidden field's focus and hides the IME asynchronously; a Tab sent
        // before that settles is still consumed by the field (no person types that fast after the chord).
        compose.waitUntil(5_000) {
            compose.activity.currentFocus !is ru.papajon.meshcentralmobile.ui.screens.session.RemoteInputField &&
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.waitForIdle()
        compose.onNodeWithTag("local_menu").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("second_local_control").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("local_menu").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.runOnIdle { assertEquals(1, opened); assertEquals(2, sink.snapshot().size) }
    }

    @Test fun hardwareChordWithImeActiveDoesNotDuplicateText() {
        val sink = RecordingSink()
        compose.setContent {
            HardwareKeyboardCapture(sink, true)
            ru.papajon.meshcentralmobile.ui.screens.session.NativeKeyboardCapture(sink, true)
        }
        waitForWindowFocus()
        compose.runOnUiThread {
            val callback = compose.activity.window.callback
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
        }
        compose.runOnIdle {
            assertEquals(0, sink.unicodeCalls)
            assertEquals(listOf(SentKey(17, true, false), SentKey(67, true, false), SentKey(67, false, false),
                SentKey(17, false, false)), sink.snapshot())
        }
    }

    @Test
    fun aFailedDownIsNotReleasedLater() {
        val sink = RecordingSink().apply { failDownCode = 65 }
        val controller = HardwareKeyboardController(sink)

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, scanCode = 30)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, scanCode = 30)))

        assertTrue("A key we never pressed must not be released", sink.snapshot().isEmpty())
    }

    @Test
    fun disablingCaptureReleasesHeldKeysAndStopsInterception() {
        val sink = RecordingSink()
        val enabled = mutableStateOf(true)
        compose.setContent { HardwareKeyboardCapture(sink, enabled = enabled.value) }
        waitForWindowFocus()

        compose.runOnUiThread {
            compose.activity.window.callback.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, scanCode = 30),
            )
        }
        compose.runOnIdle { enabled.value = false }
        compose.waitUntil(5_000) { sink.snapshot().any { it.code == 65 && !it.down } }

        val keysAfterRelease = sink.snapshot()
        compose.runOnUiThread {
            compose.activity.window.callback.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, scanCode = 48),
            )
        }
        assertEquals("Disabled capture must delegate physical keys", keysAfterRelease, sink.snapshot())
    }

    @Test
    fun focusLossReleasesHeldKeysAndStopsInterception() {
        val sink = RecordingSink()
        compose.setContent { HardwareKeyboardCapture(sink, enabled = true) }
        waitForWindowFocus()

        compose.runOnUiThread {
            val callback = compose.activity.window.callback
            callback.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, scanCode = 30))
            callback.onWindowFocusChanged(false)
        }
        compose.waitForIdle()

        assertEquals(
            listOf(
                SentKey(65, down = true, extended = false),
                SentKey(65, down = false, extended = false),
            ),
            sink.snapshot(),
        )

        val keysAfterRelease = sink.snapshot()
        compose.runOnUiThread {
            compose.activity.window.callback.dispatchKeyEvent(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, scanCode = 48),
            )
        }
        assertEquals("An unfocused window must not capture keys", keysAfterRelease, sink.snapshot())
    }

    @Test
    fun softKeyboardEventsAreNotIntercepted() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        assertFalse(
            controller.handle(
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, flags = KeyEvent.FLAG_SOFT_KEYBOARD, scanCode = 30),
            ),
        )
        assertTrue(sink.snapshot().isEmpty())
    }

    @Test
    fun systemKeysKeepTheirLocalMeaning() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        ).forEach { keyCode ->
            assertFalse(controller.handle(key(KeyEvent.ACTION_DOWN, keyCode)))
            assertFalse(controller.handle(key(KeyEvent.ACTION_UP, keyCode)))
        }
        assertTrue(sink.snapshot().isEmpty())
    }

    @Test
    fun releaseAllReleasesEachOwnedKeyOnce() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink)

        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON, scanCode = 29))
        controller.handle(
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, metaState = KeyEvent.META_CTRL_ON, scanCode = 30),
        )
        controller.releaseAll()

        assertEquals(
            listOf(
                SentKey(17, down = true, extended = false),
                SentKey(65, down = true, extended = false),
                SentKey(65, down = false, extended = false),
                SentKey(17, down = false, extended = false),
            ),
            sink.snapshot(),
        )

        controller.releaseAll()
        assertEquals(4, sink.snapshot().size)
    }

    private fun waitForWindowFocus() {
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.activity.window.decorView.hasWindowFocus() }
    }

    private fun key(
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
        repeatCount: Int = 0,
        scanCode: Int = 0,
        flags: Int = 0,
    ) = KeyEvent(
        0L,
        0L,
        action,
        keyCode,
        repeatCount,
        metaState,
        TEST_DEVICE_ID,
        scanCode,
        flags,
        InputDevice.SOURCE_KEYBOARD,
    )

    private data class SentKey(val code: Int, val down: Boolean, val extended: Boolean)

    private class RecordingSink : NativeRemoteInputSink {
        private val sent = mutableListOf<SentKey>()
        var unicodeCalls = 0
            private set
        var failDownCode: Int? = null

        @Synchronized
        fun snapshot(): List<SentKey> = sent.toList()

        @Synchronized
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            if (down && remoteKeyCode == failDownCode) return false
            sent += SentKey(remoteKeyCode, down, extended)
            return true
        }

        @Synchronized
        override fun sendUnicodeText(text: String): Int {
            unicodeCalls++
            return text.length
        }

        override fun sendCtrlAltDel(): Boolean = true
        override fun sendMouseMove(x: Int, y: Int): Boolean = true
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean = true
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean = true
        override fun sendMouseDoubleClick(x: Int, y: Int): Boolean = true
        override fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean = true
    }

    private companion object {
        const val TEST_DEVICE_ID = 7
    }
}
