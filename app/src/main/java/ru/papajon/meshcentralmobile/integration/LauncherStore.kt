package ru.papajon.meshcentralmobile.integration

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.security.CredentialScope
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class LauncherDevice(val token: String, val nodeId: String, val name: String, val online: Boolean,
    val favorite: Boolean, val recentRank: Int)

data class LauncherSnapshot(val profileId: String, val scope: String, val generation: String,
    val updatedAt: Long, val devices: List<LauncherDevice>)

/**
 * Only device display metadata stays here. No endpoint, account name or authentication material.
 *
 * Snapshots are stored per profile and the active profile id is kept separately, so switching a
 * profile never invalidates another profile's opaque launcher tokens. The previous single-snapshot
 * format is read and migrated in place.
 */
class LauncherStore(context: Context, name: String = "launcher_devices") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The snapshot of the active profile, or null when the active profile has no cached devices. */
    fun read(): LauncherSnapshot? = synchronized(monitor) {
        migrateLegacy()
        val profileId = prefs.getString(KEY_ACTIVE, null) ?: return@synchronized null
        snapshot(profileId)
    }

    /** Every cached profile snapshot. Used to decide whether a token is still referenced anywhere. */
    fun all(): List<LauncherSnapshot> = synchronized(monitor) {
        migrateLegacy()
        allUnlocked()
    }

    /** Finds the profile that still owns an opaque launcher token, including pinned entries of other profiles. */
    fun find(token: String): LauncherSnapshot? = synchronized(monitor) {
        migrateLegacy()
        allUnlocked().firstOrNull { snapshot -> snapshot.devices.any { it.token == token } }
    }

    /**
     * Switching a profile only changes which cached catalog is active. Snapshots and opaque
     * tokens of other profiles stay in place, so pinned entries survive A -> B -> A.
     */
    fun activate(profileId: String) = synchronized(monitor) {
        migrateLegacy()
        setActive(profileId)
        Unit
    }

    fun publish(profileId: String, scope: String, devices: List<MeshDevice>, favoriteIds: Set<String>,
        recentIds: List<String>, now: Long = System.currentTimeMillis()): LauncherSnapshot = synchronized(monitor) {
        migrateLegacy()
        val previous = snapshot(profileId)?.takeIf { it.scope == scope }
        val recentRanks = recentIds.withIndex().associate { (index, nodeId) -> nodeId to index }
        val selected = devices.filter { it.nodeId in favoriteIds || it.nodeId in recentIds }.take(MAX_DEVICES)
        // MeshDevice fields other than identity, display name, reachability, favorite and recent
        // rank (rights, icons, addresses, agent data...) must not rewrite the launcher catalog.
        val projected = selected.map { device ->
            ProjectedDevice(device.nodeId, cleanName(device.name), device.online,
                device.nodeId in favoriteIds, recentRanks[device.nodeId] ?: -1)
        }
        if (previous != null && sameProjection(previous.devices, projected)) {
            setActive(profileId)
            return@synchronized previous
        }
        val old = previous?.devices.orEmpty().associateBy { it.nodeId }
        val snapshot = LauncherSnapshot(profileId, scope,
            previous?.generation ?: UUID.randomUUID().toString(), now,
            projected.map { device ->
                // Tokens are stable for the life of one account/endpoint scope. A scope change is
                // the only event that rotates them (previous is null in that case).
                val token = if (previous == null) UUID.randomUUID().toString()
                else old[device.nodeId]?.token ?: UUID.randomUUID().toString()
                LauncherDevice(token, device.nodeId, device.name, device.online, device.favorite, device.recentRank)
            })
        write(snapshot)
        setActive(profileId)
        snapshot
    }

    /** A late response cannot recreate metadata after logout, profile deletion or account change. */
    fun refresh(expected: LauncherSnapshot, devices: List<MeshDevice>, now: Long = System.currentTimeMillis()): Boolean = synchronized(monitor) {
        migrateLegacy()
        if (prefs.getString(KEY_ACTIVE, null) != expected.profileId) return@synchronized false
        val current = snapshot(expected.profileId) ?: return@synchronized false
        if (current.generation != expected.generation || current.scope != expected.scope) return@synchronized false
        val byId = devices.associateBy { it.nodeId }
        val updated = current.devices.mapNotNull { entry ->
            byId[entry.nodeId]?.let { entry.copy(name = cleanName(it.name), online = it.online) }
        }
        write(current.copy(updatedAt = now, devices = updated))
        true
    }

    /**
     * clear(profileId) removes only that profile's catalog and leaves other profiles intact.
     * clear(null) removes every profile. Disabling pinned copies of the removed tokens is done by
     * [DeviceShortcuts.clear] / [LauncherCoordinator.clear] after this read/write step.
     */
    fun clear(profileId: String? = null) = synchronized(monitor) {
        migrateLegacy()
        val editor = prefs.edit()
        if (profileId == null) {
            editor.clear()
        } else {
            editor.remove(snapshotKey(profileId))
            if (prefs.getString(KEY_ACTIVE, null) == profileId) editor.remove(KEY_ACTIVE)
        }
        editor.apply()
        Unit
    }

    private fun allUnlocked(): List<LauncherSnapshot> = prefs.all.keys
        .asSequence()
        .filter { it.startsWith(SNAPSHOT_PREFIX) }
        .mapNotNull { snapshot(it.removePrefix(SNAPSHOT_PREFIX)) }
        .sortedBy { it.profileId }
        .toList()

    private fun snapshot(profileId: String): LauncherSnapshot? {
        if (!PROFILE_ID.matches(profileId)) return null
        val raw = prefs.getString(snapshotKey(profileId), null) ?: return null
        return decode(raw)?.takeIf { it.profileId == profileId }
    }

    private fun setActive(profileId: String) {
        if (prefs.getString(KEY_ACTIVE, null) != profileId) prefs.edit().putString(KEY_ACTIVE, profileId).apply()
    }

    private fun write(snapshot: LauncherSnapshot) {
        prefs.edit().putString(snapshotKey(snapshot.profileId), encode(snapshot)).apply()
    }

    /** Read the pre-multi-profile snapshot once, then keep the per-profile format. */
    private fun migrateLegacy() {
        val raw = prefs.getString(KEY_LEGACY, null) ?: return
        val legacy = decode(raw)
        val editor = prefs.edit().remove(KEY_LEGACY)
        if (legacy != null) {
            if (!prefs.contains(snapshotKey(legacy.profileId))) {
                editor.putString(snapshotKey(legacy.profileId), encode(legacy))
            }
            val active = prefs.getString(KEY_ACTIVE, null)
            if (active == null || !prefs.contains(snapshotKey(active))) {
                editor.putString(KEY_ACTIVE, legacy.profileId)
            }
        }
        editor.apply()
    }

    private fun decode(raw: String): LauncherSnapshot? = runCatching {
        val json = JSONObject(raw)
        val profileId = json.getString("profile")
        require(PROFILE_ID.matches(profileId))
        val scope = json.getString("scope")
        val generation = json.getString("generation")
        require(scope.isNotBlank() && generation.isNotBlank())
        val array = json.getJSONArray("devices")
        require(array.length() <= MAX_DEVICES)
        val devices = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val token = item.getString("token")
                require(TOKEN.matches(token))
                val nodeId = item.getString("node")
                require(nodeId.isNotBlank() && nodeId.length <= MAX_NODE_ID)
                add(LauncherDevice(token, nodeId, cleanName(item.getString("name")), item.getBoolean("online"),
                    item.getBoolean("favorite"), item.getInt("recent").coerceAtLeast(-1)))
            }
        }
        LauncherSnapshot(profileId, scope.take(MAX_METADATA), generation.take(MAX_METADATA),
            json.getLong("updatedAt"), devices)
    }.getOrNull()

    private fun encode(snapshot: LauncherSnapshot): String = JSONObject()
        .put("profile", snapshot.profileId)
        .put("scope", snapshot.scope)
        .put("generation", snapshot.generation)
        .put("updatedAt", snapshot.updatedAt)
        .put("devices", JSONArray().apply {
            snapshot.devices.forEach {
                put(JSONObject().put("token", it.token).put("node", it.nodeId).put("name", it.name)
                    .put("online", it.online).put("favorite", it.favorite).put("recent", it.recentRank))
            }
        }).toString()

    private fun sameProjection(previous: List<LauncherDevice>, next: List<ProjectedDevice>): Boolean =
        previous.size == next.size && previous.indices.all { index ->
            val old = previous[index]
            val current = next[index]
            old.nodeId == current.nodeId && old.name == current.name && old.online == current.online &&
                old.favorite == current.favorite && old.recentRank == current.recentRank
        }

    private fun snapshotKey(profileId: String): String = SNAPSHOT_PREFIX + profileId

    private data class ProjectedDevice(val nodeId: String, val name: String, val online: Boolean,
        val favorite: Boolean, val recentRank: Int)

    companion object {
        private val monitor = Any()
        private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val TOKEN = Regex("[a-f0-9-]{36}")
        private const val SNAPSHOT_PREFIX = "snapshot."
        private const val KEY_ACTIVE = "active"
        private const val KEY_LEGACY = "snapshot"
        private const val MAX_DEVICES = 520
        private const val MAX_NODE_ID = 512
        private const val MAX_METADATA = 256

        fun scope(profileId: String, serverUrl: String, userId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$profileId\u0000${CredentialScope.normalize(serverUrl)}\u0000$userId".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}

private fun cleanName(value: String): String =
    value.filterNot(Char::isISOControl).take(120).ifBlank { "MeshCentral" }
