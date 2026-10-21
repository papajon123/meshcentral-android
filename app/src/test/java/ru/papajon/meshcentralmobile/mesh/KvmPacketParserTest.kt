package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests for the Mesh Agent KVM channel.
 *
 * The jumbo layout asserted here is the one MeshCentral itself writes in
 * `agent-desktop-0.0.2.js:230-232` (`shortToStr(27) + shortToStr(8) + intToStr(cmdsize) + ...`)
 * and reads back in `agent-redir-ws-0.1.1.js:224-231`.
 */
class KvmPacketParserTest {
    @Test
    fun parsesScreenSizeCommand() {
        val frame = command(7, u16(1920) + u16(1080))

        val packet = KvmPacketParser.parse(frame) as KvmPacket.ScreenSize

        assertEquals(1920, packet.width)
        assertEquals(1080, packet.height)
    }

    @Test
    fun parsesTileCommandWithJpegPayload() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val frame = command(3, u16(256) + u16(512) + jpeg)

        val packet = KvmPacketParser.parse(frame) as KvmPacket.Tile

        assertEquals(256, packet.x)
        assertEquals(512, packet.y)
        assertArrayEquals(jpeg, packet.image)
    }

    @Test
    fun parsesCopyRectCommand() {
        val frame = command(4, u16(10) + u16(20) + u16(30) + u16(40) + u16(50) + u16(60))

        val packet = KvmPacketParser.parse(frame) as KvmPacket.CopyRect

        assertEquals(10, packet.sourceX)
        assertEquals(20, packet.sourceY)
        assertEquals(30, packet.targetX)
        assertEquals(40, packet.targetY)
        assertEquals(50, packet.width)
        assertEquals(60, packet.height)
    }

    @Test
    fun parsesDisplayListCommand() {
        val frame = command(11, u16(2) + u16(1) + u16(2) + u16(2))

        val packet = KvmPacketParser.parse(frame) as KvmPacket.Displays

        assertEquals(listOf(1, 2), packet.ids)
        assertEquals(2, packet.selected)
    }

    @Test
    fun parsesDisplayGeometryCommand() {
        val frame = command(
            82,
            u16(1) + u16(0) + u16(0) + u16(1920) + u16(1080) +
                u16(2) + u16(1920) + u16(0) + u16(1280) + u16(1024),
        )

        val packet = KvmPacketParser.parse(frame) as KvmPacket.DisplayGeometry

        assertEquals(2, packet.displays.size)
        assertEquals(DisplayInfo(id = 1, x = 0, y = 0, width = 1920, height = 1080), packet.displays[0])
        assertEquals(DisplayInfo(id = 2, x = 1920, y = 0, width = 1280, height = 1024), packet.displays[1])
    }

    @Test
    fun parsesKeyStateCursorAndInputLock() {
        assertEquals(4, (KvmPacketParser.parse(command(18, byteArrayOf(4))) as KvmPacket.KeyState).mask)
        assertEquals(3, (KvmPacketParser.parse(command(88, byteArrayOf(3))) as KvmPacket.Cursor).index)
        assertTrue((KvmPacketParser.parse(command(87, byteArrayOf(1))) as KvmPacket.InputLock).locked)
        assertEquals(false, (KvmPacketParser.parse(command(87, byteArrayOf(0))) as KvmPacket.InputLock).locked)
    }

    @Test
    fun parsesAlertText() {
        val packet = KvmPacketParser.parse(command(65, "another user is controlling".toByteArray())) as KvmPacket.Message

        assertEquals(65, packet.command)
        assertEquals("another user is controlling", packet.text)
    }

    @Test
    fun rejectsTruncatedCommand() {
        val frame = command(3, u16(0) + u16(0) + ByteArray(32))
        assertNull(KvmPacketParser.parse(frame.copyOfRange(0, 20)))
    }

    @Test
    fun unwrapsJumboWrapperAndIgnoresInnerSizeField() {
        // The wrapped command keeps a zeroed size field; the real length lives in the wrapper.
        val image = ByteArray(70_000) { index -> (index % 251).toByte() }
        val inner = command(3, u16(0) + u16(0) + image)
        val wrapper = jumbo(inner.size) + inner

        val packet = KvmPacketParser.parse(wrapper) as KvmPacket.Tile

        assertEquals(0, packet.x)
        assertEquals(0, packet.y)
        assertEquals(image.size, packet.image.size)
        assertArrayEquals(image, packet.image)
    }

    @Test
    fun decoderAccumulatesFragmentedJumboAcrossManyFrames() {
        val image = ByteArray(140_000) { index -> (index % 97).toByte() }
        val inner = command(3, u16(256) + u16(128) + image)
        val wire = jumbo(inner.size) + inner
        val decoder = KvmFrameDecoder()

        var packets: List<KvmPacket> = emptyList()
        var offset = 0
        val chunk = 1000
        while (offset < wire.size) {
            val end = minOf(offset + chunk, wire.size)
            packets = decoder.append(wire.copyOfRange(offset, end))
            offset = end
            if (offset < wire.size) {
                assertTrue("no packet before the last fragment", packets.isEmpty())
            }
        }

        val tile = packets.single() as KvmPacket.Tile
        assertEquals(256, tile.x)
        assertEquals(128, tile.y)
        assertArrayEquals(image, tile.image)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun decoderAccumulatesFragmentedPlainCommand() {
        val tilePayload = ByteArray(3000) { index -> (index % 13).toByte() }
        val wire = command(3, u16(16) + u16(32) + tilePayload)
        val decoder = KvmFrameDecoder()

        assertTrue(decoder.append(wire.copyOfRange(0, 40)).isEmpty())
        assertEquals(40, decoder.pendingBytes)
        val packets = decoder.append(wire.copyOfRange(40, wire.size))

        val tile = packets.single() as KvmPacket.Tile
        assertEquals(16, tile.x)
        assertEquals(32, tile.y)
        assertArrayEquals(tilePayload, tile.image)
    }

    @Test
    fun decoderReturnsSeveralCommandsFromOneFrame() {
        val decoder = KvmFrameDecoder()
        val wire = command(7, u16(800) + u16(600)) + command(18, byteArrayOf(2)) + command(88, byteArrayOf(1))

        val packets = decoder.append(wire)

        assertEquals(3, packets.size)
        assertEquals(KvmPacket.ScreenSize(800, 600), packets[0])
        assertEquals(KvmPacket.KeyState(2), packets[1])
        assertEquals(KvmPacket.Cursor(1), packets[2])
    }

    @Test
    fun decoderRecoversFromCorruptLength() {
        val decoder = KvmFrameDecoder()
        val corrupt = byteArrayOf(0x00, 0x03, 0x00, 0x01, 0x00, 0x00)

        assertEquals(emptyList<KvmPacket>(), decoder.append(corrupt))
        assertEquals(0, decoder.pendingBytes)

        val recovered = decoder.append(command(7, u16(1024) + u16(768)))
        assertEquals(KvmPacket.ScreenSize(1024, 768), recovered.single())
    }

    @Test
    fun unknownCommandIsReportedNotDropped() {
        val packet = KvmPacketParser.parse(command(200, byteArrayOf(1, 2, 3)))

        assertEquals(KvmPacket.Unknown(200, 3), packet)
    }

    private fun command(id: Int, payload: ByteArray): ByteArray {
        return u16(id) + u16(payload.size + 4) + payload
    }

    private fun jumbo(realSize: Int): ByteArray {
        return u16(27) + u16(8) +
            byteArrayOf(
                ((realSize shr 24) and 0xff).toByte(),
                ((realSize shr 16) and 0xff).toByte(),
                ((realSize shr 8) and 0xff).toByte(),
                (realSize and 0xff).toByte(),
            )
    }

    private fun u16(value: Int): ByteArray {
        return byteArrayOf(((value shr 8) and 0xff).toByte(), (value and 0xff).toByte())
    }
}
