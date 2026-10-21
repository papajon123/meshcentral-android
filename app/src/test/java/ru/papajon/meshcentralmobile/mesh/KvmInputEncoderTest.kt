package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact tests for the client-to-agent commands.
 *
 * Expected bytes are taken from MeshCentral 1.2.5 `agent-desktop-0.0.2.js`
 * (`SendCompressionLevel`, `SendUnPause`, `SendRefresh`, `SendRemoteInputLock`, `GetDisplayNumbers`,
 * `SetDisplay`, `SendMouseMsg`, `SendKeyMsgKC`, `SendStringUnicode`, `SendCtrlAltDelMsg`).
 */
class KvmInputEncoderTest {
    @Test
    fun compressionMatchesMeshCentralLayout() {
        assertArrayEquals(
            bytes(0x00, 0x05, 0x00, 0x0A, 0x01, 0x3C, 0x04, 0x00, 0x00, 0x32),
            KvmInputEncoder.compression(jpegQuality = 60, scaling = 1024, frameRate = 50),
        )
    }

    @Test
    fun pauseUnpauseAndRefresh() {
        assertArrayEquals(bytes(0x00, 0x08, 0x00, 0x05, 0x01), KvmInputEncoder.pause(paused = true))
        assertArrayEquals(bytes(0x00, 0x08, 0x00, 0x05, 0x00), KvmInputEncoder.pause(paused = false))
        assertArrayEquals(bytes(0x00, 0x06, 0x00, 0x04), KvmInputEncoder.refresh())
    }

    @Test
    fun inputLockAndDisplayCommands() {
        assertArrayEquals(bytes(0x00, 0x57, 0x00, 0x05, 0x02), KvmInputEncoder.inputLockQuery())
        assertArrayEquals(bytes(0x00, 0x57, 0x00, 0x05, 0x01), KvmInputEncoder.inputLock(1))
        assertArrayEquals(bytes(0x00, 0x0B, 0x00, 0x04), KvmInputEncoder.getDisplays())
        assertArrayEquals(bytes(0x00, 0x0C, 0x00, 0x06, 0x00, 0x01), KvmInputEncoder.setDisplay(1))
        assertArrayEquals(bytes(0x00, 0x0E, 0x00, 0x04), KvmInputEncoder.initTouch())
    }

    @Test
    fun keyCommandUsesMeshCentralActionCodes() {
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x06, 0x00, 0x41), KvmInputEncoder.key(65, down = true))
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x06, 0x01, 0x41), KvmInputEncoder.key(65, down = false))
        assertArrayEquals(
            bytes(0x00, 0x01, 0x00, 0x06, 0x04, 0x25),
            KvmInputEncoder.key(37, down = true, extended = true),
        )
        assertArrayEquals(
            bytes(0x00, 0x01, 0x00, 0x06, 0x03, 0x25),
            KvmInputEncoder.key(37, down = false, extended = true),
        )
    }

    @Test
    fun unicodeCommandCarriesUtf16CodeUnit() {
        val packets = KvmInputEncoder.unicodeChar('Д')

        assertEquals(2, packets.size)
        assertArrayEquals(bytes(0x00, 0x55, 0x00, 0x07, 0x00, 0x04, 0x14), packets[0])
        assertArrayEquals(bytes(0x00, 0x55, 0x00, 0x07, 0x01, 0x04, 0x14), packets[1])
    }

    @Test
    fun ctrlAltDelUsesCommandTen() {
        assertArrayEquals(bytes(0x00, 0x0A, 0x00, 0x04), KvmInputEncoder.ctrlAltDel())
    }

    @Test
    fun mouseMoveAndButtonsUseAbsolutePixels() {
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseMove(5, 7),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x02, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Left, down = true),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x04, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Left, down = false),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x08, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Right, down = true),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x10, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Right, down = false),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x20, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Middle, down = true),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x40, 0x00, 0x05, 0x00, 0x07),
            KvmInputEncoder.mouseButton(5, 7, MouseButton.Middle, down = false),
        )
    }

    @Test
    fun mouseDoubleClickUsesHardcodedMask() {
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0A, 0x00, 0x88, 0x00, 0x01, 0x00, 0x02),
            KvmInputEncoder.mouseDoubleClick(1, 2).single(),
        )
    }

    @Test
    fun mouseScrollUsesMeshCentralComplementForNegativeDeltas() {
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x0A, 0x00, 0x14, 0x00, 0x78),
            KvmInputEncoder.mouseScroll(10, 20, 120f),
        )
        assertArrayEquals(
            bytes(0x00, 0x02, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x0A, 0x00, 0x14, 0xFF, 0x87),
            KvmInputEncoder.mouseScroll(10, 20, -120f),
        )
    }

    @Test
    fun initialisationSequenceMatchesBrowserOrder() {
        val packets = KvmInputEncoder.initialisationSequence(jpegQuality = 60, frameRate = 30)

        assertEquals(10, packets.size)
        assertArrayEquals(KvmInputEncoder.compression(jpegQuality = 60, frameRate = 30), packets[0])
        assertArrayEquals(KvmInputEncoder.pause(paused = false), packets[1])
        assertArrayEquals(KvmInputEncoder.inputLockQuery(), packets[2])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_SHIFT, down = false), packets[3])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_CONTROL, down = false), packets[4])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_ALT, down = false), packets[5])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_LWIN, down = false), packets[6])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_RWIN, down = false), packets[7])
        assertArrayEquals(KvmInputEncoder.key(KvmInputEncoder.VK_SHIFT, down = false), packets[8])
        assertArrayEquals(bytes(0x00, 0x0E, 0x00, 0x04), packets[9])
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
