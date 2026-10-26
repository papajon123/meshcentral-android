package ru.papajon.meshcentralmobile.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink

/** Golden bytes audited against agent c2872c9 RemoteInputProtocol.parse and MeshInputService.key. */
class PhoneInputPacketsTest {
    @Test fun allNavigationKeysHaveExactDownAndUpPackets() {
        val expected = listOf("1b", "24", "78", "79", "7a")
        PhoneNavigationKey.entries.forEachIndexed { index, key ->
            assertEquals("0001000600${expected[index]}", KvmInputEncoder.key(key.code, true).hex())
            assertEquals("0001000601${expected[index]}", KvmInputEncoder.key(key.code, false).hex())
        }
    }

    @Test fun unicodeUsesBigEndianUtf16UnitsIncludingSurrogatePairs() {
        val packets = "AЯ😀".flatMap { KvmInputEncoder.unicodeChar(it) }.map { it.hex() }
        assertEquals(listOf("00550007000041", "00550007010041", "0055000700042f", "0055000701042f",
            "0055000700d83d", "0055000701d83d", "0055000700de00", "0055000701de00"), packets)
    }

    @Test fun tapReplaysExactPressAndRelease() = runBlocking {
        val sink = PacketSink()
        PhoneTouchGesture.replay(PhoneTouchGesture(300, 600).finish(300, 600, 40), sink) { true }
        assertEquals(listOf("0002000a0002012c0258", "0002000a0004012c0258"), sink.packets)
    }

    @Test fun longPressRetainsDurationAndNeverSendsRightClick() = runBlocking {
        val gesture = PhoneTouchGesture(300, 600).finish(300, 600, 600)
        assertEquals(600L, gesture.last().elapsedMillis)
        val sink = PacketSink()
        PhoneTouchGesture.replay(gesture, sink) { true }
        assertEquals(listOf("0002000a0002012c0258", "0002000a0004012c0258"), sink.packets)
    }

    @Test fun swipeUsesAbsoluteMoveBetweenPressAndRelease() = runBlocking {
        val gesture = PhoneTouchGesture(300, 600)
        gesture.move(300, 500, 40)
        val sink = PacketSink()
        PhoneTouchGesture.replay(gesture.finish(300, 400, 80), sink) { true }
        assertEquals(listOf("0002000a0002012c0258", "0002000a0000012c01f4",
            "0002000a0000012c0190", "0002000a0004012c0190"), sink.packets)
    }

    @Test fun multitouchAndUnknownPermissionNeverSendInput() = runBlocking {
        val gesture = PhoneTouchGesture(1, 2)
        gesture.move(4, 5, 40)
        gesture.cancel()
        val sink = PacketSink()
        PhoneTouchGesture.replay(gesture.finish(8, 9, 80), sink) { true }
        PhoneTouchGesture.replay(PhoneTouchGesture(1, 2).finish(1, 2, 40), sink) { false }
        assertTrue(sink.packets.isEmpty())
    }

    @Test fun revokedInputStillAttemptsPointerRelease() = runBlocking {
        var calls = 0
        val sink = PacketSink()
        PhoneTouchGesture.replay(PhoneTouchGesture(1, 2).finish(1, 2, 40), sink) { ++calls == 1 }
        assertEquals(listOf("0002000a000200010002", "0002000a000400010002"), sink.packets)
    }

    @Test fun viewportMappingIncludesZoomPanAndRejectsLetterbox() {
        val viewport = RemoteViewport(400, 600, 1080, 1920, zoom = 2f, panX = 20f, panY = -40f)
        assertEquals(508 to 1024, viewport.toRemote(200f, 300f))
        assertFalse(RemoteViewport(600, 400, 1080, 1920).hitsImage(1f, 1f))
    }

    @Test fun hostileOrVeryLongGesturesStayBounded() {
        val gesture = PhoneTouchGesture(1, 2)
        repeat(20000) { gesture.move(it, it, it.toLong() * 40) }
        val points = gesture.finish(10, 20, Long.MAX_VALUE)
        assertTrue(points.size <= 202)
        assertEquals(8000L, points.last().elapsedMillis)
    }

    private class PacketSink : NativeRemoteInputSink {
        val packets = mutableListOf<String>()
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean {
            packets += KvmInputEncoder.mouseButton(x, y, button, down).hex(); return true
        }
        override fun sendMouseMove(x: Int, y: Int): Boolean { packets += KvmInputEncoder.mouseMove(x, y).hex(); return true }
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = error("unexpected key")
        override fun sendUnicodeText(text: String): Int = error("unexpected text")
        override fun sendCtrlAltDel() = error("unexpected CtrlAltDel")
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = error("unexpected click shortcut")
        override fun sendMouseDoubleClick(x: Int, y: Int) = error("unexpected double click")
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = error("unexpected wheel")
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
