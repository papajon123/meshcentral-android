package ru.papajon.meshcentralmobile.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDevicesCodecTest {
    @Test fun malformedJsonDecodesToEmptyList() {
        assertTrue(RecentDevicesCodec.decode(null).isEmpty())
        assertTrue(RecentDevicesCodec.decode("").isEmpty())
        assertTrue(RecentDevicesCodec.decode("{not json").isEmpty())
        assertTrue(RecentDevicesCodec.decode("[1, 2, 3]").isEmpty())
    }

    @Test fun decodeSkipsCorruptEntriesAndDuplicates() {
        val decoded = RecentDevicesCodec.decode(
            """
            [
              {"nodeId":"a","lastOpenedAt":1},
              {"nodeId":"","lastOpenedAt":2},
              {"nodeId":"missing-time"},
              {"nodeId":5,"lastOpenedAt":3},
              {"nodeId":"string-time","lastOpenedAt":"4"},
              "not-an-object",
              {"nodeId":"b","lastOpenedAt":5},
              {"nodeId":"a","lastOpenedAt":6}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("a", "b"), decoded.map { it.nodeId })
        assertEquals(listOf(1L, 5L), decoded.map { it.lastOpenedAt })
    }

    @Test fun recordMovesExistingNodeToFrontAndUpdatesTime() {
        val history = listOf(
            RecentDevice("a", 1),
            RecentDevice("b", 2),
            RecentDevice("c", 3),
        )

        val updated = RecentDevicesCodec.record(history, "b", nowMillis = 99)

        assertEquals(listOf("b", "a", "c"), updated.map { it.nodeId })
        assertEquals(99L, updated.first().lastOpenedAt)
    }

    @Test fun recordCapsHistoryAtTwentyAndDropsTheOldestEntry() {
        val history = (1..20).map { RecentDevice("id$it", it.toLong()) }

        val updated = RecentDevicesCodec.record(history, "new", nowMillis = 100)

        assertEquals(20, updated.size)
        assertEquals("new", updated.first().nodeId)
        assertTrue(updated.none { it.nodeId == "id20" })
    }

    @Test fun blankNodeIdDoesNotChangeHistory() {
        val history = listOf(RecentDevice("a", 1))

        assertEquals(history, RecentDevicesCodec.record(history, "   ", nowMillis = 2))
        assertEquals("[]", RecentDevicesCodec.encode(emptyList()))
    }

    @Test fun codecRoundTripKeepsOrderAndTimestamps() {
        val history = listOf(
            RecentDevice("one", 10),
            RecentDevice("two", 20),
        )

        val decoded = RecentDevicesCodec.decode(RecentDevicesCodec.encode(history))

        assertEquals(history, decoded)
    }

    @Test fun encodeAndDecodeRespectTheTwentyEntryLimit() {
        val history = (1..25).map { RecentDevice("id$it", it.toLong()) }

        val decoded = RecentDevicesCodec.decode(RecentDevicesCodec.encode(history))

        assertEquals(20, decoded.size)
        assertEquals("id1", decoded.first().nodeId)
        assertEquals("id20", decoded.last().nodeId)
    }
}
