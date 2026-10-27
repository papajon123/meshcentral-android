package ru.papajon.meshcentralmobile.mesh.control

import ru.papajon.meshcentralmobile.json.optText

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class ControlRequestException(val code: String, val serverReason: String? = null) : IOException(serverReason ?: code)
data class ControlRequestFailure(val nodeId: String?, val action: String, val reason: String)

/** Correlates control replies without confusing an acknowledgement with task completion. */
class ControlRequestBroker(
    private val onLateFailure: (ControlRequestFailure) -> Unit = {},
    private val send: (JSONObject) -> Boolean,
) {
    private data class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private data class Pending(
        val id: String,
        val action: String,
        val nodeId: String?,
        val plugin: String?,
        val messageType: String?,
        val commandAction: String,
        val reply: CompletableDeferred<JSONObject>,
    )
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val gates = mutableMapOf<Pair<String, String?>, Gate>()
    private var generation = 0L
    private val uncertainNotes = mutableSetOf<String?>()
    // toast sends ok before its asynchronous rights check; keep bounded correlation after the ack.
    private val acknowledged = linkedMapOf<String, Pending>()

    suspend fun request(
        command: JSONObject,
        responseAction: String,
        nodeId: String? = null,
        timeoutMillis: Long = 15_000,
        isolatedSnapshot: Boolean = false,
    ): JSONObject {
        require(!isolatedSnapshot || responseAction == "nodes")
        require(timeoutMillis in 1..120_000)
        require(command.toString().toByteArray().size <= 65_536)
        val slot = responseAction to nodeId
        val (gate, requestGeneration) = synchronized(lock) {
            if (responseAction == "getNotes" && nodeId in uncertainNotes) throw ControlRequestException("reconnect_required")
            if (gates.values.sumOf { it.users } >= 32) throw ControlRequestException("too_many_requests")
            gates.getOrPut(slot) { Gate() }.also { it.users++ } to generation
        }
        try {
            return gate.mutex.withLock {
                val id = (if (isolatedSnapshot) SNAPSHOT_PREFIX else "android-rpc-") + UUID.randomUUID()
                val commandAction = command.getString("action")
                val payload = JSONObject(command.toString()).put("tag", id)
                // 1.3.1 sends Denied unconditionally when software has responseid.
                if (commandAction != "software") payload.put("responseid", id) else payload.remove("responseid")
                val plugin = payload.optText("plugin").takeIf { responseAction == "plugin" && it.isNotBlank() }
                if (plugin != null) payload.put("requestId", id)
                val messageType = if (responseAction == "msg") {
                    if (commandAction == "runcommands") "runcommands" else payload.getString("type")
                } else null
                val request = Pending(id, responseAction, nodeId, plugin, messageType, commandAction, CompletableDeferred())
                try {
                    val sent = synchronized(lock) {
                        if (requestGeneration != generation) throw ControlRequestException("disconnected")
                        if (responseAction == "getNotes" && nodeId in uncertainNotes) throw ControlRequestException("reconnect_required")
                        pending[id] = request
                        send(payload)
                    }
                    if (!sent) throw ControlRequestException("send_failed")
                    withTimeout(timeoutMillis) { request.reply.await() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    // getNotes has no correlation id. A late reply must not verify a later write.
                    if (responseAction == "getNotes") synchronized(lock) { uncertainNotes.add(nodeId) }
                    throw cancelled
                } finally {
                    synchronized(lock) {
                        pending.remove(id)
                    }
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
        val action = message.optText("action")
        val tags = sequenceOf("requestId", "tag", "responseid")
            .map { message.optText(it) }.filter { it.isNotBlank() }.toList()
        val nodeId = message.optText(if (action == "getNotes") "id" else "nodeid").takeIf { it.isNotBlank() }
        val request = synchronized(lock) {
            val candidate = if (tags.isNotEmpty()) tags.firstNotNullOfOrNull { pending[it] ?: acknowledged[it] } else if (action in UNTAGGED_READ_ACTIONS) {
                pending.values.singleOrNull { it.action == action && it.nodeId == nodeId }
            } else null
            if (candidate == null || (nodeId != null && candidate.nodeId != null && candidate.nodeId != nodeId)) return false
            val correlated = candidate.id in tags
            val commandReply = action == candidate.commandAction
            val routingReply = action == "msg" && !message.has("type") && message.has("result")
            if ((commandReply || routingReply) && (correlated || action in UNTAGGED_READ_ACTIONS)) {
                val failure = ControlReplyPolicy.failure(candidate.commandAction, message)
                if (failure != null) {
                    acknowledged.remove(candidate.id)
                    if (!candidate.reply.completeExceptionally(ControlRequestException("server_rejected", failure))) {
                        onLateFailure(ControlRequestFailure(candidate.nodeId, candidate.commandAction, failure))
                    }
                    return true
                }
                // Routing OK only acknowledges dispatch; runcommands must wait for typed output.
                if (routingReply || (candidate.commandAction == "runcommands" && action == "runcommands")) return false
            }
            candidate.takeIf {
                it.action == action && (it.nodeId == null || it.nodeId == nodeId || (correlated && action != "msg")) &&
                    (it.plugin == null || it.plugin == message.optText("plugin")) &&
                    (it.messageType == null || it.messageType == message.optText("type"))
            }
        } ?: return false
        if (request.commandAction == "toast" && message.optText("result").equals("ok", true)) synchronized(lock) {
            acknowledged[request.id] = request
            while (acknowledged.size > 64) acknowledged.remove(acknowledged.keys.first())
        }
        return request.reply.complete(message)
    }

    fun disconnected() {
        val requests = synchronized(lock) { generation++; acknowledged.clear(); uncertainNotes.clear(); pending.values.toList().also { pending.clear() } }
        requests.forEach { it.reply.completeExceptionally(ControlRequestException("disconnected")) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    companion object {
        private const val SNAPSHOT_PREFIX = "android-snapshot-rpc-"
        fun isIsolatedSnapshot(message: JSONObject): Boolean = message.optText("action") == "nodes" &&
            message.optText("responseid").startsWith(SNAPSHOT_PREFIX)
        private val UNTAGGED_READ_ACTIONS = setOf("getnetworkinfo", "lastconnect", "software", "getNotes")
    }
}

/** Only command acknowledgements are interpreted as status, never a command's stdout. */
object ControlReplyPolicy {
    fun failure(commandAction: String, reply: JSONObject): String? {
        if (reply.optText("type") == "runcommands") return null
        val result = reply.opt("result") as? String ?: return null
        val accepted = if (commandAction == "wakedevices") {
            Regex("Used ([0-9]+) device\\(s\\) to send wake packets").matchEntire(result)
                ?.groupValues?.get(1)?.toLongOrNull()?.let { it > 0 } == true
        } else result.equals("ok", ignoreCase = true)
        return if (accepted) null else result.take(512).filter { it >= ' ' || it == '\n' }.ifBlank { "Request rejected" }
    }
}
