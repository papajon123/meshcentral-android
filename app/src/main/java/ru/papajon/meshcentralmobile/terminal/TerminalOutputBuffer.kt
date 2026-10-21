package ru.papajon.meshcentralmobile.terminal

import kotlinx.coroutines.channels.Channel

/** Single producer, single acknowledged consumer. Byte capacity includes the renderer's in-flight chunk. */
internal class TerminalOutputBuffer(
    private val capacity: Int,
    private val onAvailable: () -> Unit,
) {
    enum class Result { Enqueued, Closed, Oversize }
    private val lock = Any()
    private val chunks = ArrayDeque<ByteArray>()
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var bytes = 0
    val queuedBytes: Int get() = synchronized(lock) { bytes }

    init { require(capacity > 0) }

    suspend fun append(frame: ByteArray): Result {
        if (frame.size > capacity) return Result.Oversize
        var offset = 0
        while (offset < frame.size) {
            val length = minOf(16_384, capacity, frame.size - offset)
            val added = synchronized(lock) {
                if (closed) return Result.Closed
                if (length <= capacity - bytes) {
                    chunks.addLast(frame.copyOfRange(offset, offset + length))
                    bytes += length
                    true
                } else false
            }
            if (added) {
                offset += length
                onAvailable() // Never call the controller while holding the buffer lock.
            } else {
                // Suspension does not hold a monitor or block the main/WebSocket thread.
                // A conflated signal preserves a release racing with this receive.
                if (spaceAvailable.receiveCatching().isClosed) return Result.Closed
            }
        }
        return synchronized(lock) { if (closed) Result.Closed else Result.Enqueued }
    }

    fun peek(): ByteArray? = synchronized(lock) { chunks.firstOrNull() }

    fun consumed() {
        synchronized(lock) { if (chunks.isNotEmpty()) bytes -= chunks.removeFirst().size }
        spaceAvailable.trySend(Unit)
    }

    fun close() {
        synchronized(lock) { closed = true; chunks.clear(); bytes = 0 }
        spaceAvailable.close() // Wakes append immediately, even without another renderer ACK.
    }
}
