package ru.papajon.meshcentralmobile.notifications

import org.json.JSONArray
import org.json.JSONObject

/**
 * Allowlisted JSON codecs. Persisted feed records contain only the server-provided identifiers,
 * kind, timestamps and expiry; no message text, hostname, address, result or diagnostic output.
 */
object NotificationCodec {
    const val MAX_FEED = 200
    const val MAX_PENDING = 200
    const val MAX_SECRET_BYTES = 8192

    private val EVENT_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:/@$-]{1,256}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    private val TOKEN_ID = Regex("mcn_[0-9a-f]{16}")
    private val USER_ID = Regex("[A-Za-z0-9_.:/@-]{1,256}")

    fun encodeFeed(items: List<NotificationFeedItem>): String {
        val array = JSONArray()
        items.takeLast(MAX_FEED).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind.wire)
                    .put("nodeId", item.nodeId ?: JSONObject.NULL)
                    .put("jobId", item.jobId ?: JSONObject.NULL)
                    .put("time", item.time)
                    .put("expiresAt", item.expiresAt),
            )
        }
        return array.toString()
    }

    fun decodeFeed(raw: String?): List<NotificationFeedItem> = runCatching {
        val array = JSONArray(raw ?: "[]")
        val result = ArrayList<NotificationFeedItem>(minOf(array.length(), MAX_FEED))
        for (index in 0 until minOf(array.length(), MAX_FEED)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val kind = NotificationEventKind.fromWire(item.optString("kind")) ?: continue
            val time = item.optLong("time", -1L)
            val expiresAt = item.optLong("expiresAt", -1L)
            if (!EVENT_ID.matches(id) || time <= 0L || expiresAt <= 0L) continue
            val nodeId = item.optString("nodeId")
                .takeIf { !item.isNull("nodeId") && IDENTIFIER.matches(it) }
            val jobId = item.optString("jobId")
                .takeIf { !item.isNull("jobId") && IDENTIFIER.matches(it) }
            result += NotificationFeedItem(id, kind, nodeId, jobId, time, expiresAt)
        }
        result.distinctBy { it.id }.sortedBy { it.time }.takeLast(MAX_FEED)
    }.getOrDefault(emptyList())

    fun encodePending(ids: Set<String>): String {
        val array = JSONArray()
        ids.asSequence().filter { EVENT_ID.matches(it) }.take(MAX_PENDING).forEach { array.put(it) }
        return array.toString()
    }

    fun decodePending(raw: String?): Set<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildSet {
            for (index in 0 until minOf(array.length(), MAX_PENDING)) {
                val id = array.optString(index)
                if (EVENT_ID.matches(id)) add(id)
            }
        }
    }.getOrDefault(emptySet())

    fun encodeSettings(settings: NotificationSettings): String = JSONObject()
        .put("kinds", JSONArray().apply { settings.kinds.sortedBy { it.ordinal }.forEach { put(it.wire) } })
        .put("quietEnabled", settings.quietHours.enabled)
        .put("quietStart", settings.quietHours.startHour)
        .put("quietEnd", settings.quietHours.endHour)
        .toString()

    fun decodeSettings(raw: String?): NotificationSettings {
        if (raw.isNullOrBlank()) return NotificationSettings()
        return runCatching {
            val objectValue = JSONObject(raw)
            val kindsArray = objectValue.optJSONArray("kinds")
            val kinds = if (kindsArray == null) {
                NotificationEventKind.entries.toSet()
            } else {
                buildSet {
                    for (index in 0 until kindsArray.length()) {
                        NotificationEventKind.fromWire(kindsArray.optString(index))?.let(::add)
                    }
                }
            }
            val start = objectValue.optInt("quietStart", QuietHours.DEFAULT_START_HOUR)
            val end = objectValue.optInt("quietEnd", QuietHours.DEFAULT_END_HOUR)
            NotificationSettings(
                kinds = kinds,
                quietHours = QuietHours(
                    enabled = objectValue.optBoolean("quietEnabled", false),
                    startHour = start.takeIf { it in 0..23 } ?: QuietHours.DEFAULT_START_HOUR,
                    endHour = end.takeIf { it in 0..23 } ?: QuietHours.DEFAULT_END_HOUR,
                ),
            )
        }.getOrDefault(NotificationSettings())
    }

    fun encodeToken(token: NotificationToken): String? {
        val objectValue = JSONObject()
            .put("tokenId", token.tokenId)
            .put("token", token.token)
            .put("endpoint", token.endpoint)
            .put("expiresAt", token.expiresAt)
            .put("userId", token.userId)
        val encoded = objectValue.toString()
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES }
    }

    fun decodeToken(raw: String?): NotificationToken? {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_SECRET_BYTES) return null
        return runCatching {
            val objectValue = JSONObject(raw)
            val tokenId = objectValue.optString("tokenId")
            val token = objectValue.optString("token")
            val endpoint = objectValue.optString("endpoint")
            val expiresAt = objectValue.optLong("expiresAt", -1L)
            val userId = objectValue.optString("userId")
            if (!TOKEN_ID.matches(tokenId) || !TOKEN.matches(token) ||
                !USER_ID.matches(userId) || expiresAt <= 0L ||
                !NotificationEndpoint.isValidPath(endpoint)
            ) {
                return null
            }
            NotificationToken(tokenId, token, endpoint, expiresAt, userId)
        }.getOrNull()
    }
}
