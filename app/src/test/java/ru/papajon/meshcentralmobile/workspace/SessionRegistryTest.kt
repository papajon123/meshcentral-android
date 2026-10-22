package ru.papajon.meshcentralmobile.workspace

import org.junit.Assert.*
import org.junit.Test

class SessionRegistryTest {
    private class Session : AutoCloseable { var closed = false; override fun close() { closed = true } }
    @Test fun capacityDoesNotEvictOrCreateAnotherConnection() {
        val registry = SessionRegistry<Session>()
        val first = registry.admit("first") { Session() }!!
        val second = registry.admit("second") { Session() }!!
        assertNull(registry.admit("third") { error("must not allocate") })
        assertSame(first, registry.admit("first") { error("must reuse") })
        registry.close("first")
        assertTrue(first.closed)
        assertFalse(second.closed)
        assertNotNull(registry.admit("third") { Session() })
        registry.closeAll()
        assertTrue(second.closed)
        assertEquals(0, registry.size)
    }
}
