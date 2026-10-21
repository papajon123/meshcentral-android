package ru.papajon.meshcentralmobile.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TerminalOutputBufferTest {
    @Test fun burstWaitsForSlowConsumerAndPreservesEveryByteInOrder() = runBlocking {
        val maximum = AtomicInteger()
        lateinit var buffer: TerminalOutputBuffer
        buffer = TerminalOutputBuffer(64) { maximum.accumulateAndGet(buffer.queuedBytes, ::maxOf) }
        val producer = async(Dispatchers.Default) {
            repeat(100) { index ->
                assertEquals(TerminalOutputBuffer.Result.Enqueued, buffer.append(ByteArray(32) { index.toByte() }))
            }
        }
        try {
            withTimeout(2000) { while (buffer.queuedBytes != 64) delay(1) }
            assertFalse(producer.isCompleted)
            withTimeout(5000) {
                repeat(100) { expected ->
                    while (buffer.peek() == null) delay(1)
                    assertArrayEquals(ByteArray(32) { expected.toByte() }, buffer.peek())
                    delay(2) // Renderer work before its acknowledgement, not an arbitrary readiness delay.
                    buffer.consumed()
                }
                producer.await()
            }
            assertEquals(64, maximum.get())
            assertEquals(0, buffer.queuedBytes)
        } finally { buffer.close(); producer.cancelAndJoin() }
    }

    @Test fun closeWakesSuspendedProducerAndDropsSensitiveOutput() = runBlocking {
        val buffer = TerminalOutputBuffer(16) {}
        buffer.append(ByteArray(16))
        val entered = CompletableDeferred<Unit>()
        val producer = async(Dispatchers.Default) { entered.complete(Unit); buffer.append(byteArrayOf(1)) }
        entered.await()
        assertFalse(producer.isCompleted)
        buffer.close()
        assertEquals(TerminalOutputBuffer.Result.Closed, withTimeout(2000) { producer.await() })
        assertEquals(0, buffer.queuedBytes)
        assertNull(buffer.peek())
    }

    @Test fun cancellationInterruptsWaitingWithoutRendererAcknowledgement() = runBlocking {
        val buffer = TerminalOutputBuffer(16) {}
        buffer.append(ByteArray(16))
        val entered = CompletableDeferred<Unit>()
        val producer = async(Dispatchers.Default) { entered.complete(Unit); buffer.append(byteArrayOf(1)) }
        entered.await()
        withTimeout(2000) { producer.cancelAndJoin() }
        assertTrue(producer.isCancelled)
        buffer.close()
    }

    @Test fun singleOversizeFrameIsRejectedBeforeAnyBytesAreQueued() = runBlocking {
        val buffer = TerminalOutputBuffer(16) {}
        assertEquals(TerminalOutputBuffer.Result.Oversize, buffer.append(ByteArray(17)))
        assertNull(buffer.peek())
        assertEquals(0, buffer.queuedBytes)
        buffer.close()
    }
}
