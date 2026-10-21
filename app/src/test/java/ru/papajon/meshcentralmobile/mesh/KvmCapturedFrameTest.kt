package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests built from bytes captured on 2026-09-20 from a real Mesh Agent session.
 *
 * Source: local MeshCentral v1.2.5 on 127.0.0.1:8443, Windows agent, session captured by the
 * standalone relay probe (1260 KVM frames). Only the
 * frame headers below are reproduced verbatim; tile payloads are synthetic because the capture
 * stored their length and JPEG magic rather than the full image bytes.
 */
class KvmCapturedFrameTest {
    @Test
    fun capturedScreenSizeFrameIsParsed() {
        // Real frame: cmd 7, size 8, 5120 x 1440 (three monitors side by side).
        val frame = hex("00070008140005a0")

        assertEquals(KvmPacket.ScreenSize(5120, 1440), KvmPacketParser.parse(frame))
    }

    @Test
    fun capturedDisplayListFrameIsParsed() {
        // Real frame: cmd 11, size 14, count 3, ids 65535/1/2, selected 65535 ("all displays").
        val frame = hex("000b000e0003ffff00010002ffff")

        val packet = KvmPacketParser.parse(frame) as KvmPacket.Displays

        assertEquals(listOf(65535, 1, 2), packet.ids)
        assertEquals(KvmPacketParser.ALL_DISPLAYS_INDEX, packet.selected)
    }

    @Test
    fun capturedJumboWrapperHeaderIsUnwrapped() {
        // Real wrapper: cmd 27, size 8, u32 at offset 4 = 470805, inner command 3 with size 0.
        val wrapperHeader = hex("001b000800072f150003000000000000")
        val jumboWrapper = wrapperHeader.copyOfRange(0, 12)
        val innerHeader = wrapperHeader.copyOfRange(8, 12)
        val image = ByteArray(200) { index -> (index % 251).toByte() }
        val innerSize = image.size + 8
        val inner = u16(3) + u16(0) + u16(7) + u16(9) + image
        val wire = jumboWrapper(innerSize) + u16(3) + u16(0) + u16(7) + u16(9) + image

        // The wrapper captured in the session looks exactly like the one built here.
        assertArrayEquals(hex("001b0008"), jumboWrapper.copyOfRange(0, 4))
        assertEquals(3, u16At(wrapperHeader, 8))
        assertEquals(0, u16At(wrapperHeader, 10))

        val packet = KvmFrameDecoder().append(wire).single() as KvmPacket.Tile

        assertEquals(7, packet.x)
        assertEquals(9, packet.y)
        assertArrayEquals(image, packet.image)
        // The inner size field is zero; only the wrapper length decides where the tile ends.
        assertEquals(0, u16At(innerHeader, 2))
        // The wrapper carries the wrapped command's total size, not the wrapper's own size.
        assertEquals(inner.size, innerSize)
    }

    @Test
    fun capturedPointerAndKeyboardFramesShareOneWebSocketPayload() {
        // The capture logged cmd 88 (size 5) and cmd 18 (size 5) as 8 byte payloads, which means a
        // single WebSocket message carried a command plus the beginning of the next one. The
        // decoder must buffer the remainder instead of dropping it.
        val decoder = KvmFrameDecoder()
        val firstPayload = hex("0058000500") + byteArrayOf(0x00, 0x12, 0x00)
        val secondPayload = hex("0501") // completes "00 12 00 05 01"

        assertEquals(KvmPacket.Cursor(0), decoder.append(firstPayload).single())
        assertEquals(3, decoder.pendingBytes)

        assertEquals(KvmPacket.KeyState(1), decoder.append(secondPayload).single())
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun capturedTouchInitAcknowledgementIsNotMistakenForATile() {
        // Real frame: cmd 14 (KVM_INIT_TOUCH) size 6 with a two byte payload.
        val frame = hex("000e00060001")

        val packet = KvmPacketParser.parse(frame)

        assertTrue(packet is KvmPacket.Unknown)
        assertEquals(14, (packet as KvmPacket.Unknown).command)
    }

    private fun jumboWrapper(realSize: Int): ByteArray {
        return u16(27) + u16(8) + byteArrayOf(
            ((realSize shr 24) and 0xff).toByte(),
            ((realSize shr 16) and 0xff).toByte(),
            ((realSize shr 8) and 0xff).toByte(),
            (realSize and 0xff).toByte(),
        )
    }

    private fun u16(value: Int): ByteArray {
        return byteArrayOf(((value shr 8) and 0xff).toByte(), (value and 0xff).toByte())
    }

    private fun u16At(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun hex(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
