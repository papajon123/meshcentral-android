package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret per-profile notification state in a private preferences file.
 *
 * The key includes the profile id and a scope hash over the normalized server URL and user id, so a
 * profile that later points at another account/address cannot reuse the previous feed or cursor.
 * The bearer token never enters this file.
 */
class NotificationStore private constructor(
    private val prefs: SharedPreferences,
    val profileId: String,
    private val scopeKey: String,
) {
    private fun key(name: String) = "p.$profileId.$name"

    private fun ensureScope() {
        val stored = prefs.getString(key(KEY_SCOPE), null)
        if (stored == scopeKey) return
        val editor = prefs.edit()
        DATA_KEYS.forEach { name -> editor.remove(key(name)) }
        editor.putString(key(KEY_SCOPE), scopeKey)
            .putBoolean(key(KEY_ENABLED), false)
        editor.commit()
    }

    private fun rememberUser(userId: String) {
        if (userId.isBlank()) return
        prefs.edit().putString(key(KEY_USER), userId).apply()
    }

    fun settings(): NotificationSettings = NotificationCodec.decodeSettings(prefs.getString(key(KEY_SETTINGS), null))

    fun saveSettings(settings: NotificationSettings) {
        prefs.edit().putString(key(KEY_SETTINGS), NotificationCodec.encodeSettings(settings)).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(key(KEY_ENABLED), false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(key(KEY_ENABLED), enabled).apply()
    }

    fun feed(): List<NotificationFeedItem> = NotificationCodec.decodeFeed(prefs.getString(key(KEY_FEED), null))

    fun pending(): Set<String> = NotificationCodec.decodePending(prefs.getString(key(KEY_PENDING), null))

    fun cursor(): Long = prefs.getLong(key(KEY_CURSOR), 0L).coerceAtLeast(0L)

    fun lastCheckAt(): Long? = prefs.getLong(key(KEY_LAST_CHECK), 0L).takeIf { it > 0L }

    fun lastError(): NotificationError? = prefs.getString(key(KEY_STATUS), null)
        ?.let { runCatching { NotificationError.valueOf(it) }.getOrNull() }

    /** Persists the feed, quiet-hour pending ids and cursor before any notification is posted. */
    fun commitCheck(
        feed: List<NotificationFeedItem>,
        pending: Set<String>,
        cursor: Long,
        checkedAt: Long,
    ): Boolean = prefs.edit()
        .putString(key(KEY_FEED), NotificationCodec.encodeFeed(feed))
        .putString(key(KEY_PENDING), NotificationCodec.encodePending(pending))
        .putLong(key(KEY_CURSOR), cursor.coerceAtLeast(0L))
        .putLong(key(KEY_LAST_CHECK), checkedAt)
        .remove(key(KEY_STATUS))
        .commit()

    fun markError(error: NotificationError, checkedAt: Long) {
        prefs.edit()
            .putString(key(KEY_STATUS), error.name)
            .putLong(key(KEY_LAST_CHECK), checkedAt)
            .apply()
    }

    fun clearStatus() {
        prefs.edit().remove(key(KEY_STATUS)).apply()
    }

    fun savePending(pending: Set<String>) {
        prefs.edit().putString(key(KEY_PENDING), NotificationCodec.encodePending(pending)).apply()
    }

    fun clearFeed() {
        prefs.edit().remove(key(KEY_FEED)).remove(key(KEY_PENDING)).apply()
    }

    companion object {
        const val PREFS_NAME = "notification_state"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_FEED = "feed"
        private const val KEY_PENDING = "pending"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_STATUS = "status"
        private const val KEY_SCOPE = "scope"
        private const val KEY_USER = "userId"

        private val DATA_KEYS = listOf(
            KEY_ENABLED,
            KEY_SETTINGS,
            KEY_FEED,
            KEY_PENDING,
            KEY_CURSOR,
            KEY_LAST_CHECK,
            KEY_STATUS,
            KEY_USER,
        )

        fun open(
            context: Context,
            profileId: String,
            serverUrl: String,
            userId: String,
        ): NotificationStore? {
            if (!profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
            val scope = NotificationProfileScope.scopeKey(profileId, serverUrl, userId) ?: return null
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return NotificationStore(prefs, profileId, scope).also {
                it.ensureScope()
                it.rememberUser(userId)
            }
        }

        fun lastUserId(context: Context, profileId: String): String? {
            if (!profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString("p.$profileId.$KEY_USER", null)
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/@-]{1,256}")) }
        }

        /** Used by delete/logout/URL-change cleanup; removes the whole profile scope. */
        fun clearProfile(context: Context, profileId: String) {
            if (!profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = "p.$profileId."
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
            editor.apply()
        }
    }
}
