package ru.papajon.meshcentralmobile.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.ui.screens.session.RemoteInputField

/** Real Android key events for the input-only remote keyboard map switch. */
@RunWith(AndroidJUnit4::class)
class RemoteKeyboardMapTest {
    @Test fun inactiveEditorStopsAcceptingAnInputConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, RecordingSink())
            org.junit.Assert.assertNotNull(field.onCreateInputConnection(EditorInfo()))
            field.updateInput(null, useRemoteKeyboardMap = false)
            org.junit.Assert.assertFalse(field.onCheckIsTextEditor())
            org.junit.Assert.assertNull(field.onCreateInputConnection(EditorInfo()))
        }
    }

    @Test fun localDeadKeyComposesOnceAndDoesNotSendARemoteVirtualKey() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = false,
            unicodeForKey = { event -> if (event.keyCode == KeyEvent.KEYCODE_GRAVE)
                android.view.KeyCharacterMap.COMBINING_ACCENT or '^'.code else event.unicodeChar })
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE))
        controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_GRAVE))
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E))
        controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E))
        assertEquals(listOf("ê"), sink.texts)
        assertTrue(sink.keys.isEmpty())
    }

    @Test fun losingWindowFocusReleasesRawKeysOwnedByThePhoneField() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink, useRemoteKeyboardMap = true)
            field.onKeyDown(KeyEvent.KEYCODE_Z, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z))
            field.onWindowFocusChanged(false)
        }
        assertEquals(listOf(SentKey(90, true, false), SentKey(90, false, false)), sink.keys)
    }

    @Test
    fun printableZIsOneUnicodeTextWhenRemoteKeyboardMapIsOff() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = false)

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)))

        assertEquals(listOf("z"), sink.texts)
        assertEquals("A printable key must not also send virtual keys", emptyList<SentKey>(), sink.keys)
    }

    @Test
    fun theSameZKeyEventUsesVk90DownAndUpWhenRemoteKeyboardMapIsOn() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = true)

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)))

        assertEquals(
            listOf(SentKey(90, down = true, extended = false), SentKey(90, down = false, extended = false)),
            sink.keys,
        )
        assertEquals(emptyList<String>(), sink.texts)
    }

    @Test
    fun germanAndRussianImeCharactersStayUnicodeWhenRemoteKeyboardMapIsOff() {
        val sink = RecordingSink()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink, useRemoteKeyboardMap = false)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.commitText("\u00fc", 1)
            connection.commitText("\u044f", 1)
        }

        assertEquals(listOf("\u00fc", "\u044f"), sink.texts)
        assertEquals(emptyList<SentKey>(), sink.keys)
    }

    @Test
    fun ctrlCKeepsItsModifiersWhenRemoteKeyboardMapIsOff() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = false)

        assertTrue(controller.handle(
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, metaState = KeyEvent.META_CTRL_ON, scanCode = 29),
        ))
        assertTrue(controller.handle(
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 3, KeyEvent.META_CTRL_ON, scanCode = 46),
        ))
        assertTrue(controller.handle(
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 3, KeyEvent.META_CTRL_ON, scanCode = 46),
        ))
        assertTrue(controller.handle(
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, metaState = KeyEvent.META_CTRL_ON, scanCode = 29),
        ))

        assertEquals(
            listOf(
                SentKey(17, down = true, extended = false),
                SentKey(67, down = true, extended = false),
                SentKey(67, down = false, extended = false),
                SentKey(17, down = false, extended = false),
            ),
            sink.keys,
        )
        assertEquals(emptyList<String>(), sink.texts)
    }

    @Test
    fun altGrCharacterKeepsCtrlAltAndSendsThePhysicalKeyWhenRemoteKeyboardMapIsOff() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = false)
        val altGr = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON

        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, metaState = altGr, scanCode = 100)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, '@'.code, altGr, scanCode = 16)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, '@'.code, altGr, scanCode = 16)))
        assertTrue(controller.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, metaState = altGr, scanCode = 100)))

        assertTrue("AltGr text must not become Unicode", sink.texts.isEmpty())
        val qDown = sink.keys.indexOfFirst { it.code == 81 && it.down }
        assertTrue("Q must be sent as a virtual key", qDown >= 0)
        assertTrue("Right Alt must be down before Q", sink.keys.take(qDown).any { it.code == 18 && it.down && it.extended })
        assertTrue("AltGr's Ctrl must be down before Q", sink.keys.take(qDown).any { it.code == 17 && it.down })
        assertTrue("Q must be released", sink.keys.drop(qDown).any { it.code == 81 && !it.down })
        assertTrue("Right Alt must be released", sink.keys.any { it.code == 18 && !it.down && it.extended })
    }

    @Test
    fun softImeTextIsUnchangedWhenRemoteKeyboardMapIsOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink, useRemoteKeyboardMap = true)
            val connection = field.onCreateInputConnection(EditorInfo())!!
            connection.commitText("z", 1)
            connection.setComposingText("\u044f", 1)
            connection.commitText("\u044f", 1)
            connection.deleteSurroundingText(1, 0)
        }

        assertEquals(listOf("z", "\u044f"), sink.texts)
        assertEquals(
            listOf(SentKey(8, down = true, extended = false), SentKey(8, down = false, extended = false)),
            sink.keys,
        )
    }

    @Test
    fun releaseAllLeavesNoVirtualKeyPressed() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = true)

        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44))
        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, 'x'.code, scanCode = 45))
        controller.releaseAll()

        assertEquals(
            listOf(
                SentKey(90, down = true, extended = false),
                SentKey(88, down = true, extended = false),
                SentKey(88, down = false, extended = false),
                SentKey(90, down = false, extended = false),
            ),
            sink.keys,
        )
        controller.releaseAll()
        assertEquals(4, sink.keys.size)
    }

    @Test
    fun unicodeKeysAreNotLeftAsVirtualKeysAfterReleaseAll() {
        val sink = RecordingSink()
        val controller = HardwareKeyboardController(sink, useRemoteKeyboardMap = false)

        controller.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44))
        controller.releaseAll()

        assertEquals(listOf("z"), sink.texts)
        assertEquals(emptyList<SentKey>(), sink.keys)
    }

    @Test
    fun changingTheModeReleasesHardwareKeysHeldByThePhoneField() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink, useRemoteKeyboardMap = true)
            assertTrue(field.onKeyDown(
                KeyEvent.KEYCODE_Z,
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44),
            ))
            assertEquals(listOf(SentKey(90, down = true, extended = false)), sink.keys)
            field.updateInput(sink, useRemoteKeyboardMap = false)
        }

        assertEquals(
            listOf(SentKey(90, down = true, extended = false), SentKey(90, down = false, extended = false)),
            sink.keys,
        )
        assertTrue(sink.texts.isEmpty())
    }

    @Test
    fun phoneFieldSendsPrintableUnicodeWhenRemoteKeyboardMapIsOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink, useRemoteKeyboardMap = false)
            val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)
            val up = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 'z'.code, scanCode = 44)
            assertTrue(field.onKeyDown(KeyEvent.KEYCODE_Z, down))
            assertTrue(field.onKeyUp(KeyEvent.KEYCODE_Z, up))
        }

        assertEquals(listOf("z"), sink.texts)
        assertEquals(emptyList<SentKey>(), sink.keys)
    }

    @Test
    fun systemKeysNeverReachTheRemoteDesktop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = RecordingSink()
        instrumentation.runOnMainSync {
            val field = RemoteInputField(instrumentation.targetContext, sink)
            for (keyCode in listOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_POWER,
            )) {
                field.onKeyDown(keyCode, key(KeyEvent.ACTION_DOWN, keyCode))
                field.onKeyUp(keyCode, key(KeyEvent.ACTION_UP, keyCode))
            }
        }

        assertEquals(emptyList<SentKey>(), sink.keys)
        assertEquals(emptyList<String>(), sink.texts)
    }

    private fun key(
        action: Int,
        keyCode: Int,
        unicodeChar: Int = 0,
        metaState: Int = 0,
        repeatCount: Int = 0,
        scanCode: Int = 0,
    ) = KeyEvent(
        0L,
        0L,
        action,
        keyCode,
        repeatCount,
        metaState,
        TEST_DEVICE_ID,
        scanCode,
        0,
        InputDevice.SOURCE_KEYBOARD,
    ).also {
        // Android derives characters from its virtual key map; there is no constructor argument
        // that injects Unicode into a KeyEvent. Check the fixture for ordinary printable keys.
        if (unicodeChar != 0 && metaState == 0) assertEquals(unicodeChar, it.unicodeChar)
    }

    private data class SentKey(val code: Int, val down: Boolean, val extended: Boolean)

    private class RecordingSink : NativeRemoteInputSink {
        val keys = mutableListOf<SentKey>()
        val texts = mutableListOf<String>()

        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            keys += SentKey(remoteKeyCode, down, extended)
            return true
        }

        override fun sendUnicodeText(text: String): Int {
            texts += text
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
        // A synthetic hardware id; Android falls back to its virtual character map if absent.
        const val TEST_DEVICE_ID = 7
    }
}
