package ru.papajon.meshcentralmobile.management.tasks

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshNodeEvent
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

/** Ordinary cancellation drains its late reply before another read. A genuinely lost untagged
 * request cannot prove freshness: reads stay retryable but display-only until reconnection. */
class TaskToolsBroker(
    private val drainTimeoutMillis: Long = 15_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val send: (JSONObject) -> Boolean,
) {
    private data class Key(val node: String, val type: String)
    private data class Pending(val id: String, val pid: Long?, val reply: CompletableDeferred<JSONObject>, var abandonedAt: Long? = null)
    private val lock = Any()
    private val pending = mutableMapOf<Key, Pending>()
    private val uncertain = mutableSetOf<Key>()
    private val nodeRevisions = mutableMapOf<String, Long>()
    private var serial = 0L
    private var connectionRevision = 0L

    fun generation(node: String): Long = synchronized(lock) { nodeRevisions[node] ?: connectionRevision }
    fun certain(node: String, type: String): Boolean = synchronized(lock) {
        expireAbandoned()
        val key = Key(node, type)
        key !in uncertain && pending[key]?.abandonedAt == null
    }

    // Lazy expiry uses a monotonic clock; no background coroutine outlives the client.
    private fun expireAbandoned() {
        val now = nanoTime()
        pending.filterValues { p -> p.abandonedAt?.let { now - it >= drainTimeoutMillis * 1_000_000 } == true }
            .forEach { (key, _) -> pending.remove(key); uncertain.add(key) }
    }

    suspend fun request(command: JSONObject, timeoutMillis: Long = 15_000): JSONObject {
        require(command.getString("action") == "msg")
        val key = Key(command.getString("nodeid"), command.getString("type"))
        require(key.type in setOf("ps", "services", "psinfo", "service"))
        val pid = if (key.type == "psinfo") command.getLong("pid").also { require(it > 0) } else null
        val p = Pending(UUID.randomUUID().toString(), pid, CompletableDeferred())
        val sideKey = if (key.type == "service") Key(key.node, "services") else null
        val side = sideKey?.let { Pending(p.id, null, CompletableDeferred()) }
        synchronized(lock) {
            expireAbandoned()
            if (pending.containsKey(key) || sideKey != null && pending.containsKey(sideKey)) throw ControlRequestException("task_reply_pending")
            if (pending.size + (if (side == null) 1 else 2) > 64) throw ControlRequestException("too_many_requests")
            pending[key] = p
            if (sideKey != null && side != null) pending[sideKey] = side
            try {
                if (!send(JSONObject(command.toString()).put("responseid", p.id))) throw ControlRequestException("send_failed")
            } catch (error: Exception) {
                pending.remove(key)
                if (sideKey != null) pending.remove(sideKey)
                throw error
            }
        }
        try {
            return withTimeout(timeoutMillis) {
                val reply = p.reply.await()
                side?.reply?.await()
                reply
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                // Ordinary screen cancellation retains ownership until its late reply is drained.
                // Only an actual timeout (or an expired drain) leaves freshness unknowable.
                fun abandon(k: Key, value: Pending) {
                    if (pending[k] !== value) return
                    if (cancelled is TimeoutCancellationException) { pending.remove(k); uncertain.add(k) }
                    else value.abandonedAt = nanoTime()
                }
                abandon(key, p)
                if (side != null) abandon(requireNotNull(sideKey), side)
            }
            throw cancelled
        } finally { p.reply.cancel(); side?.reply?.cancel() }
    }

    fun accept(message: JSONObject): Boolean = synchronized(lock) {
        expireAbandoned()
        if (message.optString("action") != "msg") return false
        val type = message.optString("type")
        if (type.isEmpty() && message.has("result")) {
            val id = message.optString("responseid")
            val entry = pending.entries.firstOrNull { it.value.id == id } ?: return false
            val result = message.optString("result")
            if (!result.equals("ok", true)) {
                val code = when {
                    result.contains("denied", true) || result.contains("permission", true) -> "denied"
                    result.contains("route", true) || result.contains("offline", true) -> "offline"
                    else -> "server_rejected"
                }
                pending.filterValues { it.id == entry.value.id }.forEach { (k, value) ->
                    pending.remove(k); value.reply.completeExceptionally(ControlRequestException(code))
                }
            }
            return true
        }
        val key = Key(message.optString("nodeid"), type)
        val p = pending[key] ?: return false
        if (p.pid != null && message.optLong("pid", -1) != p.pid) return false
        pending.remove(key)
        // Override any similarly named server field; this metadata is client-owned.
        p.reply.complete(JSONObject(message.toString()).put(VERIFIABLE, key !in uncertain))
        true
    }

    fun nodeDisconnected(node: String) = synchronized(lock) {
        nodeRevisions[node] = ++serial
        pending.filterKeys { it.node == node }.forEach { (key, p) ->
            pending.remove(key); p.reply.completeExceptionally(ControlRequestException("agent_disconnected"))
        }
        uncertain.removeAll { it.node == node }
    }

    fun nodeEvent(event: MeshNodeEvent) {
        val lostAgent = when (event) {
            is MeshNodeEvent.NodeConnected -> event.nodeId.takeIf { event.conn and 1 == 0 }
            is MeshNodeEvent.NodeChanged -> event.device.nodeId.takeIf { event.device.connectivityKnown && event.device.conn and 1 == 0 }
            is MeshNodeEvent.NodeRemoved -> event.nodeId
            else -> null
        }
        lostAgent?.let(::nodeDisconnected)
    }

    /** A paged or by-id `nodes` reply covers only some devices: absence is not an offline signal. */
    fun nodesSnapshot(reported: List<MeshDevice>) {
        reported.filter { it.connectivityKnown && it.conn and 1 == 0 }.forEach { nodeDisconnected(it.nodeId) }
    }

    fun disconnected() = synchronized(lock) {
        connectionRevision = ++serial
        nodeRevisions.clear()
        pending.values.forEach { it.reply.completeExceptionally(ControlRequestException("disconnected")) }
        pending.clear(); uncertain.clear()
    }

    companion object {
        private const val VERIFIABLE = "_taskToolsVerifiable"
        fun verifiable(reply: JSONObject): Boolean = reply.optBoolean(VERIFIABLE, true)
    }
}
