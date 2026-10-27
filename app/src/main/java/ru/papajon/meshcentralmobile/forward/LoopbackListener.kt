package ru.papajon.meshcentralmobile.forward

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch

/** One acceptor owns this socket. close() waits for a native accept to release its descriptor.
 * Linux/JDK may retain the port until accept returns even after ServerSocket.close has returned.
 * Acknowledgement happens before the caller can acquire the manager lock, so stop may hold it.
 */
internal class LoopbackListener(val socket: ServerSocket) : AutoCloseable {
    private val gate = Any()
    @Volatile private var closed = false
    private var accepting: CountDownLatch? = null
    val isClosed: Boolean get() = closed

    fun accept(): Socket {
        val done = synchronized(gate) {
            if (closed) throw SocketException("Listener closed")
            check(accepting?.count != 1L) { "Only one acceptor is supported" }
            CountDownLatch(1).also { accepting = it }
        }
        try { return socket.accept() }
        finally { done.countDown() }
    }

    override fun close() {
        val pending = synchronized(gate) {
            closed = true
            socket.close()
            accepting
        }
        // Cancellation must not turn a reported stop into a still-occupied port.
        var interrupted = false
        while (pending != null && pending.count != 0L) {
            try { pending.await() }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
