package ru.papajon.meshcentralmobile.forward

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists forwarding rules as a single versioned JSON document in [android.content.SharedPreferences].
 *
 * Only configuration is stored: no running state, traffic counters, credentials or clipboard data.
 * The store is deliberately small and synchronous: every mutation is a synchronized
 * read-modify-write and reports the result of [android.content.SharedPreferences.Editor.commit].
 */
class PortForwardStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** All rules owned by [profileId], including devices absent from the current server snapshot. */
    fun rules(profileId: String): List<PortForwardRule> = synchronized(LOCK) {
        if (profileId.isBlank()) emptyList() else read().filter { it.profileId == profileId }
    }

    /** Device-scoped view for starting routes. Profile-wide management remains available offline. */
    fun rules(profileId: String, nodeId: String): List<PortForwardRule> = synchronized(LOCK) {
        if (profileId.isBlank() || nodeId.isBlank()) {
            emptyList()
        } else {
            read().filter { it.profileId == profileId && it.nodeId == nodeId }
        }
    }

    /**
     * Inserts [rule] or replaces an existing rule with the same id.
     *
     * Returns false for an invalid rule, for a failed [android.content.SharedPreferences.Editor.commit],
     * when the 64-rule cap would be exceeded, or when the id already belongs to another profile or
     * device. An existing rule is never silently re-owned.
     */
    fun save(rule: PortForwardRule): Boolean = synchronized(LOCK) {
        if (!rule.valid()) return@synchronized false

        val current = read()
        val existing = current.firstOrNull { it.id == rule.id }
        if (existing != null && (existing.profileId != rule.profileId || existing.nodeId != rule.nodeId)) {
            return@synchronized false
        }

        val updated = if (existing == null) {
            if (current.size >= MAX_RULES) return@synchronized false
            current + rule
        } else {
            current.map { stored -> if (stored.id == rule.id) rule else stored }
        }
        if (updated.size > MAX_RULES) return@synchronized false
        write(updated)
    }

    /** Deletes [id] when it exists. A missing id or a failed commit returns false. */
    fun delete(id: String): Boolean = synchronized(LOCK) {
        if (id.isBlank()) return@synchronized false
        val current = read()
        if (current.none { it.id == id }) return@synchronized false
        write(current.filterNot { it.id == id })
    }

    /** Profile removal must also release its saved-rule slots and device metadata. */
    fun deleteProfile(profileId: String): Boolean = synchronized(LOCK) {
        if (profileId.isBlank()) return@synchronized false
        val current = read()
        if (current.none { it.profileId == profileId }) return@synchronized true
        write(current.filterNot { it.profileId == profileId })
    }

    private fun read(): List<PortForwardRule> =
        PortForwardCodec.decode(preferences.getString(KEY_RULES, null))

    private fun write(rules: List<PortForwardRule>): Boolean =
        preferences.edit().putString(KEY_RULES, PortForwardCodec.encode(rules)).commit()

    companion object {
        /** Total number of rules across all profiles and devices. */
        const val MAX_RULES = 64

        internal const val PREFERENCES_NAME = "mesh_port_forwarding"
        private const val KEY_RULES = "rules"
        private val LOCK = Any()
    }
}

/**
 * JSON codec for [PortForwardStore]. Kept free of Android APIs so corruption, schema and cap rules
 * can be exercised without a device.
 */
internal object PortForwardCodec {
    internal const val SCHEMA_VERSION = 1

    /** Unknown schema, corrupt JSON and individually malformed rules read as absent. */
    fun decode(raw: String?): List<PortForwardRule> {
        if (raw.isNullOrBlank()) return emptyList()

        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        if ((root.opt("schema") as? Number)?.toInt() != SCHEMA_VERSION) return emptyList()
        val array = root.optJSONArray("rules") ?: return emptyList()

        val result = ArrayList<PortForwardRule>(minOf(array.length(), PortForwardStore.MAX_RULES))
        val seenIds = HashSet<String>()
        for (index in 0 until array.length()) {
            if (result.size >= PortForwardStore.MAX_RULES) break
            val item = array.optJSONObject(index) ?: continue
            val rule = runCatching { decodeRule(item) }.getOrNull() ?: continue
            if (!rule.valid() || !seenIds.add(rule.id)) continue
            result += rule
        }
        return result
    }

    fun encode(rules: List<PortForwardRule>): String {
        val array = JSONArray()
        rules.take(PortForwardStore.MAX_RULES).forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("profileId", rule.profileId)
                    .put("nodeId", rule.nodeId)
                    .put("name", rule.name)
                    .put("kind", rule.kind.name)
                    .put("remotePort", rule.remotePort)
                    .put("remoteAddress", rule.remoteAddress)
                    .put("localPort", rule.localPort),
            )
        }
        return JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("rules", array)
            .toString()
    }

    private fun decodeRule(item: JSONObject): PortForwardRule? {
        val id = item.opt("id") as? String ?: return null
        val profileId = item.opt("profileId") as? String ?: return null
        val nodeId = item.opt("nodeId") as? String ?: return null
        val name = item.opt("name") as? String ?: return null
        val kindName = item.opt("kind") as? String ?: return null
        val kind = ForwardKind.entries.firstOrNull { it.name == kindName } ?: return null
        val remotePort = port(item.opt("remotePort")) ?: return null
        val remoteAddress = item.opt("remoteAddress") as? String ?: return null
        val localPort = port(item.opt("localPort")) ?: return null
        return PortForwardRule(
            id = id,
            profileId = profileId,
            nodeId = nodeId,
            name = name,
            kind = kind,
            remotePort = remotePort,
            remoteAddress = remoteAddress,
            localPort = localPort,
        )
    }

    private fun port(value: Any?): Int? {
        if (value !is Int && value !is Long) return null
        val number = (value as Number).toLong()
        return number.takeIf { it in 0..65535 }?.toInt()
    }
}
