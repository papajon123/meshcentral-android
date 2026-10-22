package ru.papajon.meshcentralmobile.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventPolicyTest {
    private val now = 1_800_000_000_000L

    private fun event(
        id: String,
        kind: NotificationEventKind = NotificationEventKind.DEVICE_ONLINE,
        time: Long = now - 1_000,
        expiresAt: Long = now + 60_000,
    ) = NotificationFeedItem(id, kind, "node/d1/a", null, time, expiresAt)

    @Test
    fun mergeDeduplicatesPrunesTtlAndKeepsNewestBoundedFeed() {
        val existing = (0 until 199).map { event("old$it", time = now - 10_000 + it) } +
            event("expired", expiresAt = now - 1)
        val incoming = listOf(event("old1"), event("new"), event("new"), event("expired-new", expiresAt = now - 1))
        val result = NotificationEventPolicy.merge(existing, incoming, now)
        assertEquals(NotificationEventPolicy.MAX_FEED, result.feed.size)
        assertEquals(listOf("new"), result.added.map { it.id })
        assertTrue(result.feed.none { it.id == "expired" })
        assertTrue(result.feed.any { it.id == "new" })
    }

    @Test
    fun quietPendingIsShownOnlyWhileEnabledAndNotExpired() {
        val feed = listOf(
            event("online"),
            event("alert", kind = NotificationEventKind.DEVICE_ALERT),
            event("expired", expiresAt = now - 1),
        )
        val pending = linkedSetOf("online", "alert", "expired", "missing")
        val shown = NotificationEventPolicy.pendingItems(
            feed,
            pending,
            now,
            setOf(NotificationEventKind.DEVICE_ONLINE),
        )
        assertEquals(listOf("online"), shown.map { it.id })
        assertEquals(
            setOf("online"),
            NotificationEventPolicy.prunePending(
                feed,
                pending,
                now,
                setOf(NotificationEventKind.DEVICE_ONLINE),
            ),
        )
    }

    @Test
    fun notificationIdsAreStableAndCollisionResistantEnough() {
        assertEquals(
            NotificationEventPolicy.stableNotificationId("mce_1"),
            NotificationEventPolicy.stableNotificationId("mce_1"),
        )
        assertNotEquals(
            NotificationEventPolicy.stableNotificationId("mce_1"),
            NotificationEventPolicy.stableNotificationId("mce_2"),
        )
        assertTrue(NotificationEventPolicy.stableNotificationId("mce_1") > 0)
    }
}
