package ru.papajon.meshcentralmobile.mesh.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ChatNotificationUpdatesTest {
    @Test fun burstPublishesFirstAndTrailingLatestAtMostOncePerSecond() = runBlocking {
        val published = CopyOnWriteArrayList<Pair<Long, Int>>()
        val updates = ChatNotificationUpdates(this)
        updates.submit { published += System.nanoTime() to 1 }
        yield()
        repeat(99) { index -> updates.submit { published += System.nanoTime() to index + 2 } }
        delay(1_150)
        assertEquals(listOf(1, 100), published.map { it.second })
        assertTrue(published[1].first - published[0].first >= 990_000_000)
        updates.cancel()
    }

    @Test fun closingCancelsTrailingUpdateAndReadingChecksLatestCount() = runBlocking {
        var unread = 1
        val published = mutableListOf<Int>()
        val updates = ChatNotificationUpdates(this, intervalMillis = 40)
        val publish = { if (unread > 0) published.add(unread); Unit }
        updates.submit(publish); yield()
        unread = 20; updates.submit(publish)
        unread = 0
        delay(60)
        assertEquals(listOf(1), published)
        unread = 30; updates.submit(publish); updates.cancel()
        delay(60)
        assertEquals(listOf(1), published)
    }
}
