package ru.papajon.meshcentralmobile.terminal

import kotlinx.coroutines.channels.Channel

/** Single producer, single acknowledged consumer. Byte capacity includes the renderer's in-flight chunk. */
internal class TerminalOutputBuffer(
    private val capacity: Int,
    private val onAvailable: () -> Unit,
) {
    enum class Result { Enqueued, Closed, Oversize }
    private class Chunk(val bytes: ByteArray, val sessionStart: Boolean)
    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var bytes = 0
    val queuedBytes: Int get() = synchronized(lock) { bytes }

    init { require(capacity > 0) }

    /**
     * [guard] is evaluated under the buffer lock but must only read a volatile/atomic value:
     * it prevents a late old-session frame from landing after a reconnect marker.
     */
    suspend fun append(frame: ByteArray, guard: () -> Boolean = { true }): Result {
        if (frame.size > capacity) return Result.Oversize
        var offset = 0
        while (offset < frame.size) {
            val length = minOf(16_384, capacity, frame.size - offset)
            val added = synchronized(lock) {
                if (closed) return Result.Closed
                if (!guard()) return Result.Closed
                if (length <= capacity - bytes) {
                    chunks.addLast(Chunk(frame.copyOfRange(offset, offset + length), false))
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

    fun peek(): ByteArray? = synchronized(lock) { chunks.firstOrNull()?.bytes }

    internal fun peekIsSessionStart(): Boolean = synchronized(lock) {
        chunks.firstOrNull()?.sessionStart == true
    }

    /**
     * A manual reconnect marker must never wait behind a stalled old session: admission is
     * bounded and synchronous, so the caller either gets an ordered marker or fails cleanly.
     */
    fun enqueueSessionStart(frame: ByteArray): Result {
        if (frame.size > capacity) return Result.Oversize
        synchronized(lock) {
            if (closed) return Result.Closed
            if (frame.size > capacity - bytes) return Result.Oversize
            chunks.addLast(Chunk(frame.copyOf(), true))
            bytes += frame.size
        }
        onAvailable()
        return Result.Enqueued
    }

    fun consumed() {
        synchronized(lock) { if (chunks.isNotEmpty()) bytes -= chunks.removeFirst().bytes.size }
        spaceAvailable.trySend(Unit)
    }

    fun close() {
        synchronized(lock) { closed = true; chunks.clear(); bytes = 0 }
        spaceAvailable.close() // Wakes append immediately, even without another renderer ACK.
    }
}
