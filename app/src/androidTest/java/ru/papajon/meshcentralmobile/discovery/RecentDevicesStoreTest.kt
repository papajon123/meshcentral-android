package ru.papajon.meshcentralmobile.discovery

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RecentDevicesStoreTest {
    private val store = RecentDevicesStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun recordKeepsNewestFirstAndMovesDuplicatesToTheFront() {
        val scope = newScope()
        try {
            store.record(scope, "a", nowMillis = 1)
            store.record(scope, "b", nowMillis = 2)
            assertEquals(listOf("b", "a"), store.ids(scope))

            store.record(scope, "a", nowMillis = 3)
            assertEquals(listOf("a", "b"), store.ids(scope))
        } finally {
            store.clear(scope)
        }
    }

    @Test
    fun historyIsCappedAtTwentyEntries() {
        val scope = newScope()
        try {
            repeat(25) { index ->
                store.record(scope, "id$index", nowMillis = index.toLong())
            }

            val ids = store.ids(scope)
            assertEquals(20, ids.size)
            assertEquals("id24", ids.first())
            assertTrue(ids.none { it == "id4" })
        } finally {
            store.clear(scope)
        }
    }

    @Test
    fun clearOnlyAffectsTheGivenScope() {
        val first = newScope()
        val second = newScope()
        try {
            store.record(first, "one", nowMillis = 1)
            store.record(second, "two", nowMillis = 2)

            store.clear(first)

            assertTrue(store.ids(first).isEmpty())
            assertEquals(listOf("two"), store.ids(second))
        } finally {
            store.clear(first)
            store.clear(second)
        }
    }

    private fun newScope(): String = "instrumentation-${UUID.randomUUID()}"
}
