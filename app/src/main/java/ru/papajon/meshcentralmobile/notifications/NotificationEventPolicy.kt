package ru.papajon.meshcentralmobile.notifications

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Pure dedupe, TTL, cursor-reset and quiet-hours decisions, shared by the worker and tests. */
object NotificationEventPolicy {
    const val MAX_FEED = NotificationCodec.MAX_FEED

    data class MergeResult(
        val feed: List<NotificationFeedItem>,
        val added: List<NotificationFeedItem>,
    )

    fun merge(
        existing: List<NotificationFeedItem>,
        incoming: List<NotificationFeedItem>,
        now: Long,
    ): MergeResult {
        val retained = existing.asSequence()
            .filter { it.expiresAt > now }
            .distinctBy { it.id }
            .toMutableList()
        val known = retained.mapTo(HashSet()) { it.id }
        val added = incoming.asSequence()
            .filter { it.expiresAt > now }
            .filter { known.add(it.id) }
            .distinctBy { it.id }
            .sortedBy { it.time }
            .toList()
        retained += added
        return MergeResult(
            feed = retained.sortedWith(compareBy<NotificationFeedItem> { it.time }.thenBy { it.id })
                .takeLast(MAX_FEED),
            added = added,
        )
    }

    /** Pending quiet-hour events that are still in the bounded feed, still enabled and not expired. */
    fun pendingItems(
        feed: List<NotificationFeedItem>,
        pendingIds: Set<String>,
        now: Long,
        kinds: Set<NotificationEventKind>,
    ): List<NotificationFeedItem> = pendingIds.asSequence()
        .mapNotNull { id -> feed.firstOrNull { it.id == id } }
        .filter { it.expiresAt > now && it.kind in kinds }
        .distinctBy { it.id }
        .sortedBy { it.time }
        .toList()

    fun prunePending(
        feed: List<NotificationFeedItem>,
        pendingIds: Set<String>,
        now: Long,
        kinds: Set<NotificationEventKind>,
    ): Set<String> = pendingItems(feed, pendingIds, now, kinds).mapTo(LinkedHashSet()) { it.id }

    /** Android replaces a notification with the same id instead of stacking a duplicate. */
    fun stableNotificationId(eventId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(eventId.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
        return (value and 0x7fffffff).takeIf { it != 0 } ?: 1
    }
}

object NotificationQuietHours {
    fun isValid(startHour: Int, endHour: Int): Boolean =
        startHour in 0..23 && endHour in 0..23

    /**
     * Equal start/end means "no quiet window"; callers must also keep [QuietHours.enabled] in mind.
     * The current local hour is evaluated at polling time, not at event time.
     */
    fun isQuiet(
        quietHours: QuietHours,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!quietHours.enabled || quietHours.startHour == quietHours.endHour) return false
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return if (quietHours.startHour < quietHours.endHour) {
            hour in quietHours.startHour until quietHours.endHour
        } else {
            hour >= quietHours.startHour || hour < quietHours.endHour
        }
    }
}
