package ru.papajon.meshcentralmobile.mesh.control

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class ControlRequestException(val code: String) : IOException(code)

/** Correlates control replies without confusing an acknowledgement with task completion. */
class ControlRequestBroker(private val send: (JSONObject) -> Boolean) {
    private data class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private data class Pending(
        val id: String,
        val action: String,
        val nodeId: String?,
        val plugin: String?,
        val reply: CompletableDeferred<JSONObject>,
    )
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val gates = mutableMapOf<Pair<String, String?>, Gate>()
    private var generation = 0L

    suspend fun request(
        command: JSONObject,
        responseAction: String,
        nodeId: String? = null,
        timeoutMillis: Long = 15_000,
    ): JSONObject {
        require(timeoutMillis in 1..120_000)
        require(command.toString().toByteArray().size <= 65_536)
        val slot = responseAction to nodeId
        val (gate, requestGeneration) = synchronized(lock) {
            if (gates.values.sumOf { it.users } >= 32) throw ControlRequestException("too_many_requests")
            gates.getOrPut(slot) { Gate() }.also { it.users++ } to generation
        }
        try {
            return gate.mutex.withLock {
                val id = "android-rpc-" + UUID.randomUUID()
                val payload = JSONObject(command.toString()).put("tag", id).put("responseid", id)
                val plugin = payload.optString("plugin").takeIf { responseAction == "plugin" && it.isNotBlank() }
                if (plugin != null) payload.put("requestId", id)
                val request = Pending(id, responseAction, nodeId, plugin, CompletableDeferred())
                try {
                    val sent = synchronized(lock) {
                        if (requestGeneration != generation) throw ControlRequestException("disconnected")
                        pending[id] = request
                        send(payload)
                    }
                    if (!sent) throw ControlRequestException("send_failed")
                    withTimeout(timeoutMillis) { request.reply.await() }
                } finally {
                    synchronized(lock) { pending.remove(id) }
                    request.reply.cancel()
                }
            }
        } finally {
            synchronized(lock) {
                gate.users--
                if (gate.users == 0) gates.remove(slot)
            }
        }
    }

    fun accept(message: JSONObject): Boolean {
        val action = message.optString("action")
        val tag = sequenceOf("requestId", "tag", "responseid")
            .map { message.optString(it) }.firstOrNull { it.isNotBlank() }
        val nodeId = message.optString("nodeid").takeIf { it.isNotBlank() }
        val request = synchronized(lock) {
            val candidate = if (tag != null) pending[tag] else if (action in UNTAGGED_READ_ACTIONS) {
                pending.values.singleOrNull { it.action == action && it.nodeId == nodeId }
            } else null
            candidate?.takeIf {
                it.action == action && (it.nodeId == null || it.nodeId == nodeId) &&
                    (it.plugin == null || it.plugin == message.optString("plugin"))
            }
        } ?: return false
        return request.reply.complete(message)
    }

    fun disconnected() {
        val requests = synchronized(lock) { generation++; pending.values.toList().also { pending.clear() } }
        requests.forEach { it.reply.completeExceptionally(ControlRequestException("disconnected")) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    companion object {
        private val UNTAGGED_READ_ACTIONS = setOf("getnetworkinfo", "lastconnect")
    }
}
