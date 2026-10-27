package ru.papajon.meshcentralmobile.mesh

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

enum class DeviceBrowserFilter { All, Online, Favorites, Recent, NeedsAttention }

data class DeviceListQuery(val filter: String = "", val collect: Boolean = false, val ids: Set<String>? = null) {
    fun normalized() = copy(filter = normalize(filter), ids = ids?.toSet())
    companion object {
        fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).take(128)
    }
}

/** Shared by the real screen/client and JVM regressions; no Android clock or UI dependencies. */
object DeviceListPolicy {
    fun refreshRetained(page: DevicePage, id: String) =
        (!page.completeSnapshot && page.limit == 1) || id !in page.nodeIds.orEmpty()
    fun membershipCandidate(page: DevicePage, event: MeshNodeEvent, previous: MeshDevice?): Boolean {
        if (!serverFilteredSnapshot(page)) return false
        val device = when (event) {
            is MeshNodeEvent.NodeAdded -> event.device
            is MeshNodeEvent.NodeChanged -> event.device
            else -> return false
        }
        if (device.name == device.nodeId) return false
        return if (device.nodeId in page.nodeIds.orEmpty()) device.name != previous?.name
            else foldSearch(device.name).contains(foldSearch(page.filter))
    }
    private fun foldSearch(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    fun serverFilteredSnapshot(page: DevicePage) = page.serverLimited && page.completeSnapshot && !page.localSearch &&
        page.requestedIds == null && page.filter.isNotEmpty()
    fun needsInitialSnapshot(pending: Boolean, current: Boolean) = !pending && !current
    fun admits(page: DevicePage, device: MeshDevice) =
        (page.requestedIds == null || device.nodeId in page.requestedIds) &&
            device.name.lowercase(Locale.ROOT).contains(page.filter)

    fun snapshotMembership(page: DevicePage, device: MeshDevice): DevicePage {
        if (!page.completeSnapshot) return page
        // A database collation can match Café for cafe. Only an addressed server check can
        // change membership after a rename; metadata events must preserve the original match.
        if (serverFilteredSnapshot(page)) return page
        val ids = if (admits(page, device)) page.nodeIds.orEmpty() + device.nodeId else page.nodeIds.orEmpty() - device.nodeId
        return page.copy(nodeIds = ids, totalCount = ids.size, returnedCount = ids.size)
    }
    fun queryChanged(request: DeviceListQuery, current: DeviceListQuery) = request.normalized() != current.normalized()
    fun hideRows(page: DevicePage, request: DeviceListQuery, skip: Int, silent: Boolean) =
        !silent && page.serverLimited && (queryChanged(request, page.query) || skip != page.skip)

    // Collected online/attention/search snapshots require explicit refresh; never 80 requests per tick.
    // Small ID selections are fetched only on selection/explicit refresh, not on a polling clock.
    fun poll(page: DevicePage, visible: Boolean, foreground: Boolean, connected: Boolean, busy: Boolean, timedOut: Boolean) =
        visible && foreground && connected && !busy && !timedOut && page.pagingActive

    fun reconcile(page: DevicePage, visible: Boolean, foreground: Boolean, connected: Boolean, busy: Boolean, timedOut: Boolean) =
        visible && foreground && connected && !busy && !timedOut && page.serverLimited && !page.completeSnapshot

    fun refreshForEvent(page: DevicePage, event: MeshNodeEvent, previous: MeshDevice?): Boolean {
        if (!page.serverLimited || page.completeSnapshot) return false
        val visible = previous?.nodeId in page.nodeIds.orEmpty()
        return when (event) {
            is MeshNodeEvent.NodeAdded -> true
            is MeshNodeEvent.NodeRemoved -> event.nodeId in page.nodeIds.orEmpty()
            is MeshNodeEvent.NodeChanged -> {
                val named = event.device.name != event.device.nodeId
                val renamed = previous != null && named && previous.name != event.device.name
                (visible && renamed) || (!visible && named && page.filter.isNotEmpty() &&
                    event.device.name.lowercase(Locale.ROOT).contains(page.filter))
            }
            else -> false
        }
    }

    fun retainSnapshot(incoming: List<MeshDevice>, previous: List<MeshDevice>, activeIds: Set<String>,
        selectedId: String?, limited: Boolean, currentConnection: Boolean): List<MeshDevice> {
        val old = previous.associateBy { it.nodeId }
        val snapshot = incoming.map { node ->
            val before = old[node.nodeId]
            if (limited && currentConnection && node.isAndroidAgent && node.online && before?.online == true)
                node.copy(remoteInput = before.remoteInput) else node
        }
        if (!limited) return snapshot
        val incomingIds = incoming.map { it.nodeId }.toSet()
        val retained = activeIds + listOfNotNull(selectedId)
        return snapshot + previous.filter { it.nodeId in retained && it.nodeId !in incomingIds }
    }
}

/** A new queued intent gets a fresh deadline but cannot hide an already reported timeout. */
internal class NodesWatchdog {
    private var ticket = 0L
    var timedOut = false
        private set
    fun arm(): Long = ++ticket
    fun expire(expected: Long): Boolean {
        if (expected != ticket) return false
        timedOut = true
        return true
    }
    fun complete() { ++ticket; timedOut = false }
    fun mayReconnect(explicitRetry: Boolean) = timedOut && explicitRetry
}

/** Transport failure is a result, not cancellation of the inspector's observation coroutine. */
internal class PendingNodesRead(val id: String, private val onReply: (JSONObject) -> Unit = {}) {
    val answer = CompletableDeferred<JSONObject>()
    fun received(frame: JSONObject) {
        try { onReply(frame); answer.complete(frame) }
        catch (error: Exception) { answer.completeExceptionally(error) }
    }
    fun disconnected() { answer.completeExceptionally(ControlRequestException("disconnected")) }
}
