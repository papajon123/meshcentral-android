package ru.papajon.meshcentralmobile.discovery

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class RecentDevice(
    val nodeId: String,
    val lastOpenedAt: Long,
)

/**
 * JSON codec and list policy for [RecentDevicesStore]. Kept free of Android APIs so the ordering,
 * duplicate and corruption rules are covered by local unit tests.
 */
internal object RecentDevicesCodec {
    fun decode(raw: String?, limit: Int = RecentDevicesStore.MAX_ENTRIES): List<RecentDevice> {
        if (raw.isNullOrBlank() || limit <= 0) return emptyList()

        val array = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }

        val seen = HashSet<String>()
        val result = ArrayList<RecentDevice>(minOf(array.length(), limit))
        for (index in 0 until array.length()) {
            if (result.size >= limit) break
            val item = array.optJSONObject(index) ?: continue
            val nodeId = item.opt("nodeId") as? String ?: continue
            if (nodeId.isBlank()) continue
            val lastOpenedAt = (item.opt("lastOpenedAt") as? Number)?.toLong() ?: continue
            if (!seen.add(nodeId)) continue
            result += RecentDevice(nodeId = nodeId, lastOpenedAt = lastOpenedAt)
        }
        return result
    }

    fun encode(entries: List<RecentDevice>, limit: Int = RecentDevicesStore.MAX_ENTRIES): String {
        val array = JSONArray()
        entries.asSequence()
            .filter { it.nodeId.isNotBlank() }
            .distinctBy { it.nodeId }
            .take(limit.coerceAtLeast(0))
            .forEach { entry ->
                array.put(
                    JSONObject()
                        .put("nodeId", entry.nodeId)
                        .put("lastOpenedAt", entry.lastOpenedAt),
                )
            }
        return array.toString()
    }

    /** Pure record rule: de-duplicate, prepend and cap the history. */
    fun record(
        entries: List<RecentDevice>,
        nodeId: String,
        nowMillis: Long,
        limit: Int = RecentDevicesStore.MAX_ENTRIES,
    ): List<RecentDevice> {
        val normalized = nodeId.trim()
        if (normalized.isEmpty()) return entries.take(limit.coerceAtLeast(0))
        return (listOf(RecentDevice(normalized, nowMillis)) + entries.filterNot { it.nodeId == normalized })
            .take(limit.coerceAtLeast(0))
    }
}
