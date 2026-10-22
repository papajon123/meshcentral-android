package ru.papajon.meshcentralmobile.discovery

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Stores only the identity and last-open time of recently opened devices.
 *
 * Every caller supplies an opaque [scope]. The root screen should compose it from the active
 * profile id and the signed-in user id, for example `profileId + "\u0000" + userId`; the store never
 * parses or displays it. The scope is hashed before it becomes a preference key, so neither the
 * profile nor the user id is written in readable form. Device names, host names, addresses and
 * credentials are never stored.
 *
 * The history is capped at [MAX_ENTRIES] and keeps the most recent entry first.
 */
class RecentDevicesStore(context: Context, private val profileId: String? = null) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Recently opened node ids for [scope], newest first. Unknown or corrupt data reads as empty. */
    @Synchronized
    fun ids(scope: String): List<String> {
        requireScope(scope)
        return RecentDevicesCodec.decode(prefs.getString(keyFor(scope), null))
            .map { it.nodeId }
    }

    /** Moves [nodeId] to the front of the [scope] history and records [nowMillis]. */
    @Synchronized
    fun record(scope: String, nodeId: String, nowMillis: Long = System.currentTimeMillis()) {
        requireScope(scope)
        if (nodeId.isBlank()) return

        val key = keyFor(scope)
        val current = RecentDevicesCodec.decode(prefs.getString(key, null))
        val updated = RecentDevicesCodec.record(current, nodeId, nowMillis)
        prefs.edit()
            .putString(key, RecentDevicesCodec.encode(updated))
            .apply()
    }

    /** Removes the history for one [scope] only; other profiles and users keep their entries. */
    @Synchronized
    fun clear(scope: String) {
        requireScope(scope)
        prefs.edit().remove(keyFor(scope)).apply()
    }

    /** Removes this profile's history across its accounts and addresses. */
    @Synchronized
    fun clearProfile() {
        require(profileId != null) { "A profile is required for profile deletion" }
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(profilePrefix()) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun requireScope(scope: String) {
        require(scope.isNotBlank()) { "Recent devices scope must not be blank" }
    }

    private fun keyFor(scope: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
        return profilePrefix() + hex
    }

    private fun profilePrefix(): String = profileId?.let { id ->
        "profile_" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) } + "_"
    } ?: KEY_PREFIX

    companion object {
        internal const val PREFERENCES_NAME = "mesh_recent_devices"
        private const val KEY_PREFIX = "scope_"
        const val MAX_ENTRIES = 20
    }
}
