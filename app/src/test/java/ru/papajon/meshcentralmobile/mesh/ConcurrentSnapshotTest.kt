package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class ConcurrentSnapshotTest {
    @Test fun removalBetweenSizeAndIterationDoesNotCrashTeardown() {
        // A ConcurrentHashMap key set may lose its last entry after a size read.
        val removingSet = object : AbstractSet<String>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String> = emptyList<String>().iterator()
        }
        assertEquals(emptyList<String>(), concurrentSnapshot(removingSet))
    }

    @Test fun callbacksCanRemoveEntriesWithoutChangingTheSnapshot() {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("a", "b")) }
        val snapshot = concurrentSnapshot(set)
        snapshot.forEach(set::remove)
        assertEquals(setOf("a", "b"), snapshot.toSet())
        assertEquals(0, set.size)
    }
}
