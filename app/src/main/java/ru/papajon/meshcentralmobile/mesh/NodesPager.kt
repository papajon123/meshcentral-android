package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import java.util.Locale

/** A page and the query that produced it; membership excludes retained detail/session devices. */
data class DevicePage(
    val limit: Int? = null,
    val skip: Int = 0,
    val totalCount: Int? = null,
    val returnedCount: Int = 0,
    val filter: String = "",
    val collecting: Boolean = false,
    val loading: Boolean = false,
    val nodeIds: Set<String>? = null,
    val requestedIds: Set<String>? = null,
    val localSearch: Boolean = false,
) {
    val query: DeviceListQuery get() = DeviceListQuery(filter, collecting, requestedIds)
    val serverLimited: Boolean get() = limit != null
    val completeSnapshot: Boolean get() = collecting || requestedIds != null || localSearch
    val hasPrevious: Boolean get() = !completeSnapshot && skip > 0
    val hasNext: Boolean get() = !completeSnapshot && limit != null && returnedCount > 0 &&
        (totalCount?.let { skip.toLong() + returnedCount < it } ?: (returnedCount >= limit))
    val pagingActive: Boolean get() = serverLimited && !completeSnapshot && (skip > 0 || returnedCount >= requireNotNull(limit))
}

/** All reads on one socket are sequential: the server mutates skip/filter and visibleDevices. */
internal class NodesPager {
    private data class Intent(val generation: Long, val query: DeviceListQuery, val skip: Int)
    private data class Flight(val intent: Intent, val skip: Int, val id: String, val nodeId: String?, val restoringEvents: Boolean = false)
    data class Result(val devices: List<MeshDevice>? = null, val page: DevicePage? = null,
        val next: JSONObject? = null, val failed: Boolean = false)
    private var serial = 0L
    private var desired = Intent(0, DeviceListQuery(), 0)
    private var flight: Flight? = null
    private val collected = LinkedHashMap<String, MeshDevice>()
    private var pageLimit: Int? = null
    private var localSearch = false
    private var targetIndex = 0
    private var completedSnapshot: Result? = null
    val pending: Boolean get() = flight != null

    fun reset() { flight = null; collected.clear(); pageLimit = null; localSearch = false; completedSnapshot = null }
    fun configureLimit(limit: Int?) { pageLimit = limit?.takeIf { it > 0 } ?: pageLimit }
    fun request(filter: String, collect: Boolean, skip: Int = 0, ids: Set<String>? = null): JSONObject? {
        val query = DeviceListQuery(filter, collect, ids).normalized()
        val offset = skip.coerceAtLeast(0)
        if (pending && desired.query == query && desired.skip == offset) return null
        desired = Intent(++serial, query, offset)
        completedSnapshot = null
        collected.clear(); targetIndex = 0; localSearch = false
        return if (flight == null && ids?.isEmpty() != true) start(desired.skip) else null
    }
    fun emptyResult(): Result? = if (!pending && desired.query.ids?.isEmpty() == true)
        Result(emptyList(), DevicePage(limit = pageLimit, totalCount = 0, filter = desired.query.filter,
            requestedIds = emptySet(), nodeIds = emptySet())) else null

    private fun start(skip: Int): JSONObject {
        val id = "android-nodes-${++serial}"
        val nodeId = desired.query.ids?.sorted()?.get(targetIndex)
        flight = Flight(desired, if (nodeId == null) skip else 0, id, nodeId)
        return MeshControlMessages.nodes(id, if (nodeId == null) skip else 0, pageLimit,
            if (nodeId != null || localSearch) "" else desired.query.filter).apply { if (nodeId != null) put("id", nodeId) }
    }
    fun accept(event: MeshControlEvent.Nodes): Result {
        val legacy = event.responseId == null && event.limit == null && pageLimit == null
        val active = flight ?: return if (legacy) Result(event.devices,
            DevicePage(totalCount = event.devices.size, returnedCount = event.devices.size)) else Result()
        if (event.responseId != active.id && !legacy) return Result()
        flight = null
        if (active.intent.generation != desired.generation) {
            configureLimit(event.limit)
            return emptyResult() ?: Result(next = start(desired.skip))
        }
        if (event.result != null && !event.result.equals("ok", true)) return Result(failed = true)
        if (active.restoringEvents) return requireNotNull(completedSnapshot).also { completedSnapshot = null }
        pageLimit = event.limit
        val offset = if (event.limit == null) 0 else event.skip ?: active.skip
        if (event.limit != null && offset != active.skip) return Result(failed = true)
        val query = desired.query
        if (active.nodeId == null && event.limit != null && query.filter.isNotEmpty() &&
            DeviceListQuery.normalize(event.filter.orEmpty()) != query.filter) localSearch = true
        val page = DevicePage(event.limit, offset, event.totalCount ?: if (event.limit == null ||
            event.devices.size < event.limit) offset + event.devices.size else null,
            event.devices.size, query.filter, collecting = query.collect,
            nodeIds = if (event.limit != null) event.devices.map { it.nodeId }.toSet() else null,
            requestedIds = query.ids, localSearch = localSearch)
        if (query.ids != null) {
            event.devices.filter { it.nodeId == active.nodeId }.forEach { collected[it.nodeId] = it }
            targetIndex++
            if (targetIndex < query.ids.size) return Result(next = start(0))
            return collectedResult(page, filterLocally = true)
        }
        if (query.collect || localSearch) {
            val previousSize = collected.size
            event.devices.forEach { collected[it.nodeId] = it }
            val more = event.limit != null && event.devices.size >= event.limit &&
                (event.totalCount == null || offset.toLong() + event.devices.size < event.totalCount)
            if (more && event.devices.isNotEmpty()) {
                if (collected.size == previousSize) return Result(failed = true)
                return Result(next = start(offset + event.devices.size))
            }
            return collectedResult(page, filterLocally = localSearch)
        }
        if (event.devices.isEmpty() && offset > 0 && event.limit != null)
            return Result(next = start((offset - event.limit).coerceAtLeast(0)))
        return Result(event.devices.distinctBy { it.nodeId }, page)
    }
    private fun collectedResult(page: DevicePage, filterLocally: Boolean): Result {
        val devices = collected.values.filter { !filterLocally || it.name.lowercase(Locale.ROOT).contains(desired.query.filter) }
        val result = Result(devices, page.copy(skip = 0, returnedCount = devices.size,
            totalCount = if (filterLocally) devices.size else page.totalCount,
            nodeIds = devices.map { it.nodeId }.toSet()))
        if (!page.serverLimited) return result
        // meshuser.js:889 clears visibleDevices only for skip=0 and count<limit. A deliberately
        // absent ID works even for limit=1 or an empty snapshot, unlike a known device ID.
        // Keep this read in the same flight: external reads/new queries cannot race its callback.
        completedSnapshot = result
        val responseId = "android-nodes-${++serial}"
        val absentId = "android-events-${java.util.UUID.randomUUID()}"
        flight = Flight(desired, 0, responseId, absentId, restoringEvents = true)
        return Result(next = MeshControlMessages.nodes(responseId, 0, pageLimit, "").put("id", absentId))
    }
}

/** Copy the list, change one row, and insert at its sorted position; no full re-sort per event. */
internal object DeviceListUpdates {
    val comparator: Comparator<MeshDevice> = compareBy<MeshDevice> { !it.online }
        .thenBy { it.meshName.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.nodeId }

    fun upsert(devices: List<MeshDevice>, device: MeshDevice): List<MeshDevice> {
        val result = ArrayList<MeshDevice>(devices.size + 1)
        devices.forEach { if (it.nodeId != device.nodeId) result.add(it) }
        val index = result.binarySearch(device, comparator).let { if (it < 0) -it - 1 else it }
        result.add(index, device)
        return result
    }
}
