// SPDX-License-Identifier: Apache-2.0
// MeshCentral protocol adaptation (Ylian Saint-Hilaire and contributors).
// Modified for Kotlin/Android; see NOTICE and docs/PROTOCOL.md.
package ru.papajon.meshcentralmobile.mesh

import kotlin.math.roundToInt

/**
 * Encoder for MeshCentral KVM client-to-agent commands.
 *
 * Every layout below is copied byte for byte from `public/scripts/agent-desktop-0.0.2.js`
 * (MeshCentral 1.2.5), so a native client produces exactly what the browser would send.
 */
object KvmInputEncoder {
    /**
     * `00 05 00 0A type level scaling:u16 frameTimer:u16`
     * (`obj.SendCompressionLevel`, agent-desktop-0.0.2.js:190).
     * Compression types: 1 = JPEG, 2 = PNG, 3 = TIFF, 4 = WebP.
     */
    fun compression(
        compressionType: Int = COMPRESSION_JPEG,
        jpegQuality: Int = 60,
        scaling: Int = 1024,
        frameRate: Int = 50,
    ): ByteArray {
        return packet(
            KvmPacketParser.COMMAND_COMPRESSION,
            byteArrayOf(compressionType.byte(), jpegQuality.coerceIn(1, 100).byte()) +
                u16(scaling.coerceIn(128, 4096)) +
                u16(frameRate.coerceIn(1, 200)),
        )
    }

    /** `00 06 00 04` (`obj.SendRefresh`). */
    fun refresh(): ByteArray = packet(KvmPacketParser.COMMAND_REFRESH)

    /** `00 08 00 05 00|01` (`obj.SendUnPause` / `obj.SendPause`). */
    fun pause(paused: Boolean): ByteArray {
        return packet(KvmPacketParser.COMMAND_PAUSE, byteArrayOf(if (paused) 1 else 0))
    }

    /** `00 57 00 05 code`, where 0 = unlock, 1 = lock, 2 = query (`obj.SendRemoteInputLock`). */
    fun inputLock(code: Int): ByteArray {
        return packet(KvmPacketParser.COMMAND_INPUT_LOCK, byteArrayOf(code.coerceIn(0, 2).byte()))
    }

    fun inputLockQuery(): ByteArray = inputLock(2)

    /** `00 0B 00 04` (`obj.GetDisplayNumbers`). */
    fun getDisplays(): ByteArray = packet(KvmPacketParser.COMMAND_DISPLAYS)

    /** `00 0C 00 06 hi lo` (`obj.SetDisplay`). */
    fun setDisplay(display: Int): ByteArray {
        return packet(KvmPacketParser.COMMAND_CURRENT_DISPLAY, u16(display))
    }

    /** `00 0E 00 04` — KVM_INIT_TOUCH, sent by the browser right after the first screen size. */
    fun initTouch(): ByteArray = packet(KvmPacketParser.COMMAND_INIT_TOUCH)

    /**
     * Frames the browser sends immediately after the first screen-size command
     * (`ProcessScreenMsg` plus `case 7` in agent-desktop-0.0.2.js:202-256):
     * compression, unpause, input-lock query, six key-up frames, then KVM_INIT_TOUCH.
     */
    fun initialisationSequence(
        jpegQuality: Int = 60,
        frameRate: Int = 30,
    ): List<ByteArray> = buildList {
        add(compression(jpegQuality = jpegQuality, frameRate = frameRate))
        add(pause(paused = false))
        add(inputLockQuery())
        addAll(clearStuckModifiers())
        add(initTouch())
    }

    /** Key-up frames for Shift, Ctrl, Alt, Left-Windows, Right-Windows, Shift. */
    fun clearStuckModifiers(): List<ByteArray> {
        val order = listOf(VK_SHIFT, VK_CONTROL, VK_ALT, VK_LWIN, VK_RWIN, VK_SHIFT)
        return order.map { key(keyCode = it, down = false, extended = false) }
    }

    /** `00 02 00 0A 00 00 x:u16 y:u16` — absolute framebuffer pixels (`obj.SendMouseMsg`). */
    fun mouseMove(x: Int, y: Int): ByteArray = mousePacket(x, y, buttons = 0)

    fun mouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): ByteArray {
        val buttons = if (down) button.mask else button.mask * 2
        return mousePacket(x, y, buttons = buttons)
    }

    fun mouseClick(x: Int, y: Int, button: MouseButton = MouseButton.Left): List<ByteArray> {
        return listOf(mouseButton(x, y, button, down = true), mouseButton(x, y, button, down = false))
    }

    /** `00 02 00 0A 00 88 x:u16 y:u16` — the browser hardcodes 0x88 for a double click. */
    fun mouseDoubleClick(x: Int, y: Int): List<ByteArray> {
        return listOf(mousePacket(x, y, buttons = DOUBLE_CLICK_MASK))
    }

    /** `00 02 00 0C 00 00 x:u16 y:u16 delta:i16` (`obj.SendMouseMsg` scroll branch). */
    fun mouseScroll(x: Int, y: Int, delta: Float): ByteArray {
        val scroll = delta.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return packet(
            KvmPacketParser.COMMAND_MOUSE,
            byteArrayOf(0, 0) + u16(x) + u16(y) + meshCentralSignedShort(scroll),
        )
    }

    /**
     * `00 01 00 06 action kc` (`obj.SendKeyMsgKC`): action 0 = down, 1 = up,
     * 3 = extended up, 4 = extended down.
     */
    fun key(keyCode: Int, down: Boolean, extended: Boolean = false): ByteArray {
        val action = when {
            extended && down -> 4
            extended -> 3
            down -> 0
            else -> 1
        }
        return packet(
            KvmPacketParser.COMMAND_INPUT_KEY,
            byteArrayOf(action.byte(), keyCode.byte()),
        )
    }

    /** `00 55 00 07 action codepoint:u16` (`obj.SendStringUnicode`). */
    fun unicodeChar(char: Char): List<ByteArray> {
        val value = char.code
        return listOf(
            packet(KvmPacketParser.COMMAND_UNICODE, byteArrayOf(0) + u16(value)),
            packet(KvmPacketParser.COMMAND_UNICODE, byteArrayOf(1) + u16(value)),
        )
    }

    /** `00 0A 00 04` (`obj.SendCtrlAltDelMsg`). */
    fun ctrlAltDel(): ByteArray = packet(KvmPacketParser.COMMAND_CTRL_ALT_DEL)

    fun ctrlShiftEsc(): List<ByteArray> {
        return listOf(
            key(VK_CONTROL, down = true),
            key(VK_SHIFT, down = true),
            key(VK_ESCAPE, down = true),
            key(VK_ESCAPE, down = false),
            key(VK_SHIFT, down = false),
            key(VK_CONTROL, down = false),
        )
    }

    private fun mousePacket(x: Int, y: Int, buttons: Int): ByteArray {
        return packet(
            KvmPacketParser.COMMAND_MOUSE,
            byteArrayOf(0, buttons.byte()) + u16(x) + u16(y),
        )
    }

    private fun packet(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val size = HEADER_SIZE + payload.size
        return u16(command) + u16(size) + payload
    }

    private fun u16(value: Int): ByteArray {
        val coerced = value.coerceIn(0, 0xffff)
        return byteArrayOf(((coerced shr 8) and 0xff).byte(), (coerced and 0xff).byte())
    }

    /**
     * MeshCentral encodes negative 16-bit values as the complement of the absolute value
     * (`255 - (abs >> 8)`, `255 - (abs & 0xFF)` in `obj.SendMouseMsg`), which the agent decodes
     * back to the original number. This is not two's complement, so it is reproduced verbatim.
     */
    internal fun meshCentralSignedShort(value: Int): ByteArray {
        if (value >= 0) return u16(value)
        val abs = -value
        return byteArrayOf(
            (255 - ((abs shr 8) and 0xff)).byte(),
            (255 - (abs and 0xff)).byte(),
        )
    }

    private fun Int.byte(): Byte = (this and 0xff).toByte()

    const val COMPRESSION_JPEG = 1
    const val COMPRESSION_PNG = 2
    const val COMPRESSION_TIFF = 3
    const val COMPRESSION_WEBP = 4

    const val VK_SHIFT = 16
    const val VK_CONTROL = 17
    const val VK_ALT = 18
    const val VK_ESCAPE = 27
    const val VK_LWIN = 91
    const val VK_RWIN = 92

    private const val DOUBLE_CLICK_MASK = 0x88
    private const val HEADER_SIZE = 4
}

/** Mouse button bit masks from `obj.MouseButton` in agent-desktop-0.0.2.js. */
enum class MouseButton(val mask: Int) {
    Left(0x02),
    Right(0x08),
    Middle(0x20),
}
