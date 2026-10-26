package ru.papajon.meshcentralmobile.model

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.input.*
import ru.papajon.meshcentralmobile.mesh.MouseButton

class CustomShortcutsTest {
    @Test fun profileRoundTripIsIsolatedAndPreservesOrder() {
        val profiles = listOf(ServerProfile("one", "One", "https://one.example.com", customShortcuts = CustomShortcut.suggestions),
            ServerProfile("two", "Two", "https://two.example.com", customShortcuts = listOf(CustomShortcut("Select", "A", ctrl = true))))
        assertEquals(profiles, ProfileCatalog.decode(ProfileCatalog.encode(profiles)))
    }
    @Test fun oldProfilesHaveNoShortcuts() {
        assertTrue(ProfileCatalog.decode("""[{"id":"old","name":"Old","serverUrl":"https://example.com"}]""").single().customShortcuts.isEmpty())
    }
    @Test fun writeRejectsThirteenAndReadCapsAtTwelve() {
        val twelve = List(12) { CustomShortcut("Key $it", "A") }
        assertEquals(twelve, CustomShortcut.decode(CustomShortcut.encode(twelve)))
        assertThrows(IllegalArgumentException::class.java) { ProfileCatalog.encode(listOf(ServerProfile("one", "One", "https://example.com", customShortcuts = twelve + twelve[0]))) }
        val array = CustomShortcut.encode(twelve).put(CustomShortcut.encode(twelve).getJSONObject(0))
        assertEquals(12, CustomShortcut.decode(array).size)
    }
    @Test fun invalidStoredKeysAndLabelsCannotReachInput() {
        assertTrue(CustomShortcut.decode(JSONArray("""[{"label":"Invalid","key":"UNKNOWN"},{"key":"R"},null]""")).isEmpty())
        assertFalse(CustomShortcut(" ", "R").valid)
        assertFalse(CustomShortcut("x".repeat(41), "R").valid)
        assertTrue(CustomShortcut("x".repeat(40), "R").valid)
        val sink = RecordingSink()
        assertTrue(RemoteInputBridge.sendCustomShortcut(sink, CustomShortcut("Invalid", "UNKNOWN")) is RemoteInputResult.Failed)
        assertTrue(sink.keys.isEmpty())
    }
    @Test fun allModifiersPressBeforeKeyAndReleaseInReverseOrder() {
        val sink = RecordingSink()
        assertEquals(RemoteInputResult.Sent(10), RemoteInputBridge.sendCustomShortcut(sink,
            CustomShortcut("All", "R", ctrl = true, alt = true, shift = true, win = true)))
        assertEquals(listOf(17 to true, 18 to true, 16 to true, 91 to true, 82 to true,
            82 to false, 91 to false, 16 to false, 18 to false, 17 to false), sink.keys)
        assertTrue(sink.extended[3]); assertTrue(sink.extended[6])
    }
    @Test fun everyOfferedKeyHasAnExistingInputMapping() {
        CustomShortcut.keys.forEach { (name, code) -> assertNotNull(name, RemoteKeyCodeMapper.toVirtualKeyCode(code)) }
    }
    @Test fun failedChordStillAttemptsToReleaseEveryPressedKey() {
        val sink = RecordingSink(failAt = 3)
        assertTrue(RemoteInputBridge.sendCustomShortcut(sink, CustomShortcut("Chord", "R", ctrl = true, alt = true)) is RemoteInputResult.Failed)
        assertEquals(listOf(17 to true, 18 to true, 82 to true, 82 to false, 18 to false, 17 to false), sink.keys)
    }
    @Test fun failedReleaseDoesNotSkipRemainingModifiers() {
        val sink = RecordingSink(failAt = 4)
        assertTrue(RemoteInputBridge.sendCustomShortcut(sink, CustomShortcut("Chord", "R", ctrl = true, alt = true)) is RemoteInputResult.Failed)
        assertEquals(listOf(17 to true, 18 to true, 82 to true, 82 to false, 18 to false, 17 to false), sink.keys)
    }
    @Test fun fiveSuggestionsProduceCorrectVirtualKeys() {
        val expected = listOf(
            listOf(91 to true, 82 to true, 82 to false, 91 to false),
            listOf(18 to true, 115 to true, 115 to false, 18 to false),
            listOf(17 to true, 16 to true, 27 to true, 27 to false, 16 to false, 17 to false),
            listOf(91 to true, 76 to true, 76 to false, 91 to false),
            listOf(18 to true, 9 to true, 9 to false, 18 to false))
        CustomShortcut.suggestions.forEachIndexed { index, shortcut ->
            val sink = RecordingSink()
            assertTrue(RemoteInputBridge.sendCustomShortcut(sink, shortcut) is RemoteInputResult.Sent)
            assertEquals(shortcut.label, expected[index], sink.keys)
        }
    }
    private class RecordingSink(private val failAt: Int = -1) : NativeRemoteInputSink {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val extended = mutableListOf<Boolean>()
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            keys += remoteKeyCode to down; this.extended += extended; return keys.size != failAt
        }
        override fun sendUnicodeText(text: String) = 0
        override fun sendCtrlAltDel() = false
        override fun sendMouseMove(x: Int, y: Int) = false
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = false
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = false
        override fun sendMouseDoubleClick(x: Int, y: Int) = false
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = false
    }
}
