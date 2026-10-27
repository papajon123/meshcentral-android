package ru.papajon.meshcentralmobile.forward

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure

/** A relaxed relay heartbeat tolerates brief backpressure. TLS verification is unchanged. */
internal object RawTcpTransport {
    fun client(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient = base.newBuilder()
        .pingInterval(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
}

/** One TCP socket / one WebSocket. Reader callbacks block at the local TCP writer: bounded backpressure.
 * Outgoing reads stop while OkHttp's queue is full. No reconnects, retries, or protocol downgrade.
 */
internal class TcpRelayBridge(
    private val local: Socket,
    private val factory: WebSocket.Factory,
    private val request: Request,
    private val scope: CoroutineScope,
    private val valid: () -> Boolean,
    private val onBytes: (Long, Long) -> Unit,
    private val onFinished: (ForwardError?) -> Unit,
    private val handshakeMillis: Long = 20_000,
    private val drainMillis: Long = 5_000,
    private val uploadDrainMillis: Long = 120_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : PortConnection {
    private val ended = AtomicBoolean()
    private val started = AtomicBoolean()
    private val ready = CountDownLatch(1)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    private val lastReceivedAt = AtomicLong(System.nanoTime())
    private val delivering = AtomicBoolean()
    private var reader: Job? = null

    override fun start() {
        if (!started.compareAndSet(false, true) || ended.get()) return
        if (!valid()) { finish(ForwardError.UNAVAILABLE); return }
        try {
            val created = factory.newWebSocket(request, Listener())
            socket = created
            if (ended.get()) { created.cancel(); return }
            reader = scope.launch(Dispatchers.IO) {
                try {
                    if (!ready.await(handshakeMillis, TimeUnit.MILLISECONDS)) { finish(ForwardError.TIMEOUT); return@launch }
                    if (ended.get()) return@launch
                    val buffer = ByteArray(16 * 1024)
                    val input = local.getInputStream()
                    while (!ended.get()) {
                        if (!valid()) { finish(ForwardError.UNAVAILABLE); break }
                        val count = input.read(buffer)
                        if (count < 0) {
                            // A local FIN must not discard accepted bytes still queued in OkHttp.
                            var queued = created.queueSize()
                            var uploadDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(uploadDrainMillis)
                            var backoff = 5L
                            while (!ended.get() && queued != 0L) {
                                val remaining = created.queueSize()
                                if (remaining < queued) {
                                    uploadDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(uploadDrainMillis)
                                }
                                queued = remaining
                                if (queued == 0L) break
                                if (System.nanoTime() >= uploadDeadline) {
                                    finish(ForwardError.CONNECTION_LOST); return@launch
                                }
                                pause(backoff)
                                backoff = (backoff * 2).coerceAtMost(100)
                            }
                            if (ended.get()) break
                            // No upstream FIN frame: allow an initial grace period, then extend it
                            // for each received frame and while a local write is backpressured.
                            val eofAt = System.nanoTime()
                            while (!ended.get()) {
                                val quietMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - maxOf(eofAt, lastReceivedAt.get()))
                                if (!delivering.get() && quietMillis >= drainMillis) { finish(null); break }
                                pause((drainMillis - quietMillis).coerceIn(10, 100))
                            }
                            break
                        }
                        var backoff = 5L
                        while (!ended.get() && created.queueSize() + count > MAX_QUEUED_BYTES) {
                            pause(backoff)
                            backoff = (backoff * 2).coerceAtMost(100)
                        }
                        if (ended.get()) break
                        if (!created.send(buffer.toByteString(0, count))) { finish(ForwardError.CONNECTION_LOST); break }
                        onBytes(count.toLong(), 0)
                    }
                } catch (_: Exception) { if (!ended.get()) finish(null, reset = true) }
            }
        } catch (_: Exception) { finish(ForwardError.CONNECT_FAILED) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (ended.get()) return
            if (!connected && (text == "c" || text == "cr")) {
                // Pairing marker only. Sending "1" here corrupts raw TCP traffic.
                connected = true
                ready.countDown()
            } else if (!connected) finish(ForwardError.CONNECT_FAILED)
            // Post-pairing text is a relay control message, never inject it into the byte stream.
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (ended.get()) return
            if (bytes.size > MAX_FRAME_BYTES) { finish(ForwardError.LIMIT); return }
            if (!connected || !valid()) { finish(ForwardError.UNAVAILABLE); return }
            if (bytes.size == 0) return
            delivering.set(true)
            lastReceivedAt.set(System.nanoTime())
            try {
                local.getOutputStream().write(bytes.toByteArray())
                onBytes(0, bytes.size.toLong())
                lastReceivedAt.set(System.nanoTime())
            } catch (_: Exception) { finish(null, reset = true) } // A local reset must not kill siblings.
            finally { delivering.set(false) }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish(closeError(code))
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(closeError(code))
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(if (t.serverCertificateFailure() != null) ForwardError.TLS else
                if (connected) ForwardError.CONNECTION_LOST else ForwardError.CONNECT_FAILED)
        }
    }
    private fun closeError(code: Int): ForwardError? = when {
        !connected -> ForwardError.CONNECT_FAILED
        code == 1000 || code == 1005 -> null // MeshCentral uses a bare close for normal target EOF.
        else -> ForwardError.CONNECTION_LOST
    }
    private fun finish(error: ForwardError?, reset: Boolean = error != null) {
        if (!ended.compareAndSet(false, true)) return
        ready.countDown()
        // A remote EOF with unsent local bytes is incomplete delivery, not a clean stream.
        val incomplete = !reset && socket?.queueSize()?.let { it != 0L } == true
        if (reset || incomplete) {
            resetLocalSocket(local)
            socket?.cancel()
        } else {
            socket?.close(1000, null)
            runCatching { local.close() }
        }
        reader?.cancel()
        onFinished(if (incomplete) ForwardError.CONNECTION_LOST else error)
    }
    override fun close() = finish(null, reset = true)
    override fun abort(error: ForwardError) = finish(error)
    companion object {
        const val MAX_QUEUED_BYTES = 256 * 1024L
        const val MAX_FRAME_BYTES = 1024 * 1024
    }
}
