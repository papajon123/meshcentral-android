package ru.papajon.meshcentralmobile.mesh

import java.nio.charset.StandardCharsets

/**
 * Streaming decoder for the MeshCentral Mesh Agent KVM binary channel (relay usage 2).
 *
 * Wire format verified against MeshCentral 1.2.5 sources:
 * - `public/scripts/agent-redir-ws-0.1.1.js:196-231` — per-message accumulation and jumbo unwrapping.
 * - `public/scripts/agent-desktop-0.0.2.js:219-322` — command table and payload layouts.
 *
 * Every command starts with a 4 byte big-endian header: `command:u16`, `size:u16`, where `size`
 * is the total command length including the header. A jumbo wrapper (`command == 27 && size == 8`)
 * carries the real length of the following command as a big-endian 32-bit value at offset 4; the
 * wrapped command starts at offset 8 and its own `size` field must be ignored. The browser reads
 * that field as a 24-bit value at offset 5, which is equivalent for every payload below 16 MiB;
 * this decoder reads the full 32-bit value written by `intToStr()` in `agent-desktop-0.0.2.js`.
 */
class KvmFrameDecoder {
    private var buffer: ByteArray = EMPTY

    /** Bytes waiting for the remainder of a fragmented command. */
    val pendingBytes: Int get() = buffer.size

    fun reset() {
        buffer = EMPTY
    }

    /** Feeds raw WebSocket payload bytes and returns every complete command found. */
    fun append(data: ByteArray): List<KvmPacket> {
        if (data.isEmpty()) return emptyList()
        buffer = if (buffer.isEmpty()) data.copyOf() else buffer + data

        val packets = ArrayList<KvmPacket>(2)
        while (true) {
            val frame = nextFrame() ?: break
            val packet = KvmPacketParser.parseFrame(frame.command, frame.size, frame.bytes)
            if (packet != null) packets.add(packet)
        }
        return packets
    }

    private fun nextFrame(): Frame? {
        if (buffer.size < HEADER_SIZE) return null
        var command = buffer.u16(0)
        var size = buffer.u16(2)
        if (size < HEADER_SIZE) {
            // Corrupt length: drop the stream instead of looping forever on the same bytes.
            reset()
            return null
        }

        if (command == KvmPacketParser.COMMAND_JUMBO && size == JUMBO_WRAPPER_SIZE) {
            if (buffer.size < JUMBO_WRAPPER_SIZE + HEADER_SIZE) return null
            val realSize = buffer.u32(4)
            if (realSize < HEADER_SIZE || realSize > MAX_FRAME_BYTES) {
                reset()
                return null
            }
            val total = JUMBO_WRAPPER_SIZE + realSize
            if (buffer.size < total) return null
            command = buffer.u16(JUMBO_WRAPPER_SIZE)
            val frame = buffer.copyOfRange(JUMBO_WRAPPER_SIZE, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            return Frame(command = command, size = realSize, bytes = frame)
        }

        if (size > MAX_FRAME_BYTES) {
            reset()
            return null
        }
        if (buffer.size < size) return null
        val frame = buffer.copyOfRange(0, size)
        buffer = buffer.copyOfRange(size, buffer.size)
        return Frame(command = command, size = size, bytes = frame)
    }

    private data class Frame(val command: Int, val size: Int, val bytes: ByteArray)

    private companion object {
        val EMPTY = ByteArray(0)
        const val HEADER_SIZE = 4
        const val JUMBO_WRAPPER_SIZE = 8
        const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    }
}

/**
 * Stateless parser for a single complete KVM command.
 *
 * Command ids and payload layouts are taken from `agent-desktop-0.0.2.js`:
 * tile 3, copy rect 4, screen size 7, displays 11, display ack 12, message 17, key state 18,
 * jumbo 27, alert 65, display geometry 82, input lock 87, mouse cursor 88.
 */
object KvmPacketParser {
    /** Parses one self-contained command buffer (used by tests and for already complete frames). */
    fun parse(data: ByteArray): KvmPacket? {
        if (data.size < HEADER_SIZE) return null
        val command = data.u16(0)
        val declared = data.u16(2)
        if (command == COMMAND_JUMBO && declared == JUMBO_WRAPPER_SIZE && data.size >= JUMBO_WRAPPER_SIZE + HEADER_SIZE) {
            val realSize = data.u32(4)
            if (realSize < HEADER_SIZE || realSize > data.size - JUMBO_WRAPPER_SIZE) return null
            return parseFrame(
                command = data.u16(JUMBO_WRAPPER_SIZE),
                size = realSize,
                frame = data.copyOfRange(JUMBO_WRAPPER_SIZE, JUMBO_WRAPPER_SIZE + realSize),
            )
        }
        if (declared < HEADER_SIZE || declared > data.size) return null
        return parseFrame(command, declared, data)
    }

    fun parseFrame(command: Int, size: Int, frame: ByteArray): KvmPacket? {
        if (size < HEADER_SIZE || frame.size < size) return null
        return when (command) {
            COMMAND_TILE -> parseTile(frame, size)
            COMMAND_COPY_RECT -> parseCopyRect(frame, size)
            COMMAND_SCREEN_SIZE -> parseScreenSize(frame, size)
            COMMAND_DISPLAYS -> parseDisplays(frame, size)
            COMMAND_CURRENT_DISPLAY -> null // Empty acknowledgement of a display switch.
            COMMAND_MESSAGE, COMMAND_ALERT -> KvmPacket.Message(
                command = command,
                text = frame.copyOfRange(HEADER_SIZE, size).toString(StandardCharsets.UTF_8),
            )
            COMMAND_KEY_STATE -> if (size >= 5) KvmPacket.KeyState(frame[4].toInt() and 0xff) else null
            COMMAND_DISPLAY_INFO -> parseDisplayGeometry(frame, size)
            COMMAND_INPUT_LOCK -> if (size >= 5) KvmPacket.InputLock((frame[4].toInt() and 0xff) != 0) else null
            COMMAND_CURSOR -> if (size >= 5) KvmPacket.Cursor(frame[4].toInt() and 0xff) else null
            else -> KvmPacket.Unknown(command, size - HEADER_SIZE)
        }
    }

    private fun parseScreenSize(frame: ByteArray, size: Int): KvmPacket? {
        if (size < 8) return null
        val width = frame.u16(4)
        val height = frame.u16(6)
        if (width <= 0 || height <= 0) return null
        return KvmPacket.ScreenSize(width, height)
    }

    private fun parseTile(frame: ByteArray, size: Int): KvmPacket? {
        if (size < 8) return null
        return KvmPacket.Tile(
            x = frame.u16(4),
            y = frame.u16(6),
            image = frame.copyOfRange(8, size),
        )
    }

    private fun parseCopyRect(frame: ByteArray, size: Int): KvmPacket? {
        if (size < 16) return null
        return KvmPacket.CopyRect(
            sourceX = frame.u16(4),
            sourceY = frame.u16(6),
            targetX = frame.u16(8),
            targetY = frame.u16(10),
            width = frame.u16(12),
            height = frame.u16(14),
        )
    }

    /** `count:u16`, `count` x `id:u16`, `selected:u16` (65535 means "all displays"). */
    private fun parseDisplays(frame: ByteArray, size: Int): KvmPacket? {
        if (size < 6) return null
        val count = frame.u16(4)
        if (size < 6 + count * 2) return null
        val ids = ArrayList<Int>(count)
        for (index in 0 until count) ids.add(frame.u16(6 + index * 2))
        val selectedOffset = 6 + count * 2
        val selected = if (size >= selectedOffset + 2) frame.u16(selectedOffset) else ALL_DISPLAYS_INDEX
        return KvmPacket.Displays(ids = ids, selected = selected)
    }

    /** Records of 10 bytes starting at offset 4: `id:u16, x:u16, y:u16, w:u16, h:u16`. */
    private fun parseDisplayGeometry(frame: ByteArray, size: Int): KvmPacket? {
        if (size < HEADER_SIZE || (size - HEADER_SIZE) % 10 != 0) return null
        val count = (size - HEADER_SIZE) / 10
        val displays = ArrayList<DisplayInfo>(count)
        var offset = HEADER_SIZE
        for (index in 0 until count) {
            displays.add(
                DisplayInfo(
                    id = frame.u16(offset),
                    x = frame.u16(offset + 2),
                    y = frame.u16(offset + 4),
                    width = frame.u16(offset + 6),
                    height = frame.u16(offset + 8),
                ),
            )
            offset += 10
        }
        return KvmPacket.DisplayGeometry(displays)
    }

    const val COMMAND_INPUT_KEY = 1
    const val COMMAND_MOUSE = 2
    const val COMMAND_TILE = 3
    const val COMMAND_COPY_RECT = 4
    const val COMMAND_COMPRESSION = 5
    const val COMMAND_REFRESH = 6
    const val COMMAND_SCREEN_SIZE = 7
    const val COMMAND_PAUSE = 8
    const val COMMAND_CTRL_ALT_DEL = 10
    const val COMMAND_DISPLAYS = 11
    const val COMMAND_CURRENT_DISPLAY = 12
    const val COMMAND_INIT_TOUCH = 14
    const val COMMAND_TOUCH = 15
    const val COMMAND_MESSAGE = 17
    const val COMMAND_KEY_STATE = 18
    const val COMMAND_JUMBO = 27
    const val COMMAND_ALERT = 65
    const val COMMAND_DISPLAY_INFO = 82
    const val COMMAND_UNICODE = 85
    const val COMMAND_INPUT_LOCK = 87
    const val COMMAND_CURSOR = 88

    /** `selected` value that means "all displays are combined into one framebuffer". */
    const val ALL_DISPLAYS_INDEX = 65535

    internal const val HEADER_SIZE = 4
    internal const val JUMBO_WRAPPER_SIZE = 8
}

internal fun ByteArray.u16(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

internal fun ByteArray.u32(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
}
