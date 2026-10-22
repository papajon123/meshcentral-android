package ru.papajon.meshcentralmobile.notifications

import org.json.JSONObject

class NotificationProtocolException(message: String) : java.io.IOException(message)

/**
 * Strict parser for the read-only events page.
 *
 * Unknown object fields are ignored, unknown event kinds are skipped, and every retained field has
 * a length/grammar bound. A malformed root, cursor or item list never advances the stored cursor.
 */
object NotificationEventParser {
    const val MAX_BODY_BYTES = 128 * 1024
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    const val MAX_ITEMS = 200

    private val EVENT_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:/@$-]{1,256}")

    fun parse(body: String, previousCursor: Long, now: Long): NotificationEventPage {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw NotificationProtocolException("body too large")
        }
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw NotificationProtocolException("invalid json")
        }
        if (!root.has("cursor")) throw NotificationProtocolException("cursor missing")
        val cursor = try {
            root.getLong("cursor")
        } catch (_: Exception) {
            throw NotificationProtocolException("cursor invalid")
        }
        if (cursor < 0L || cursor > MAX_SAFE_INTEGER) {
            throw NotificationProtocolException("cursor out of range")
        }
        val resetCursor = root.optBoolean("resetCursor", false)
        if (!resetCursor && cursor < previousCursor) {
            throw NotificationProtocolException("cursor moved backwards")
        }
        if (root.has("items") && root.optJSONArray("items") == null) {
            throw NotificationProtocolException("items is not an array")
        }
        val array = root.optJSONArray("items")
        if (array != null && array.length() > MAX_ITEMS) {
            throw NotificationProtocolException("too many items")
        }
        val items = ArrayList<NotificationFeedItem>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val objectValue = array.optJSONObject(index) ?: continue
                val id = objectValue.optString("id")
                val kind = NotificationEventKind.fromWire(objectValue.optString("kind")) ?: continue
                val time = objectValue.optLong("time", -1L)
                val expiresAt = objectValue.optLong("expiresAt", -1L)
                if (!EVENT_ID.matches(id) || time <= 0L || expiresAt <= 0L || expiresAt <= now) continue
                val nodeId = objectValue.optString("nodeid")
                    .takeIf { !objectValue.isNull("nodeid") && IDENTIFIER.matches(it) }
                val jobId = objectValue.optString("jobId")
                    .takeIf { !objectValue.isNull("jobId") && IDENTIFIER.matches(it) }
                items += NotificationFeedItem(id, kind, nodeId, jobId, time, expiresAt)
            }
        }
        return NotificationEventPage(
            items = items.distinctBy { it.id }.sortedBy { it.time }.take(MAX_ITEMS),
            cursor = cursor,
            resetCursor = resetCursor,
        )
    }
}
