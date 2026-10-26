package ru.papajon.meshcentralmobile.management

import java.time.Instant
import org.json.JSONObject

data class DeviceHistoryEvent(val timeMillis: Long?, val action: String?, val message: String?)
data class PowerTransition(val timeMillis: Long, val previous: Int?, val current: Int?)

/** Whitelisted, bounded display data. Raw event objects (including user/session data) are not retained. */
object DeviceHistoryParser {
    const val EVENT_LIMIT = 50
    const val TIMELINE_LIMIT = 100

    fun events(reply: JSONObject): List<DeviceHistoryEvent> {
        val items = requireNotNull(reply.optJSONArray("events"))
        return (0 until minOf(items.length(), EVENT_LIMIT)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            DeviceHistoryEvent(timestamp(item.opt("time")), text(item, "action", 80), text(item, "msg", 1024))
        }
    }

    fun timeline(reply: JSONObject, limit: Int = TIMELINE_LIMIT): List<PowerTransition> {
        require(limit in 1..10_000)
        val items = requireNotNull(reply.optJSONArray("timeline"))
        if (items.length() == 0) return emptyList()
        require(items.length() >= 3 && items.length() % 2 == 1)
        val start = (items.opt(1) as? Number)?.toDouble() ?: error("Invalid timeline time")
        require(start.isFinite() && start >= 0)
        // MeshCentral 1.3.1 uses Date.now() only for its three-element no-records fallback.
        var time = if (start >= 100_000_000_000.0) start else start * 1000
        require(time <= 4_102_444_800_000.0)
        var previous = power(items.opt(0))
        val transitions = ArrayDeque<PowerTransition>()
        for (index in 2 until items.length() step 2) {
            if (index > 2) {
                val delta = (items.opt(index - 1) as? Number)?.toDouble() ?: error("Invalid timeline delta")
                require(delta.isFinite() && delta >= 0)
                time += delta * 1000
                require(time <= 4_102_444_800_000.0)
            }
            val current = power(items.opt(index))
            if (previous != current) transitions.addLast(PowerTransition(time.toLong(), previous, current))
            if (transitions.size > limit) transitions.removeFirst()
            previous = current
        }
        return transitions.reversed()
    }

    private fun text(item: JSONObject, key: String, limit: Int): String? =
        (item.opt(key) as? String)?.trim()?.take(limit)?.takeIf { it.isNotEmpty() }

    private fun timestamp(value: Any?): Long? = when (value) {
        is String -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0 && it <= 4_102_444_800_000.0 }?.toLong()
        else -> null
    }

    private fun power(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf { it in 0.0..8.0 && it % 1 == 0.0 }?.toInt()
}
