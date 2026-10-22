package ru.papajon.meshcentralmobile.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEndpointTest {
    @Test
    fun acceptsOnlySameOriginEventPaths() {
        assertTrue(NotificationEndpoint.isValidPath("/mobilecompanion/events"))
        assertTrue(NotificationEndpoint.isValidPath("/d1/mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1//mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/../mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/%2e%2e/mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/mobilecompanion/events?since=1"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/mobilecompanion/events#frag"))
        assertFalse(NotificationEndpoint.isValidPath("/user@host/mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1\\mobilecompanion/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/other/events"))
        assertFalse(NotificationEndpoint.isValidPath("/d1/mobilecompanion/events/"))
    }

    @Test
    fun resolvesAgainstTheConfiguredOriginOnly() {
        val resolved = NotificationEndpoint.resolve(
            "https://mesh.example/d1/",
            "/d2/mobilecompanion/events",
        )
        assertNotNull(resolved)
        assertEquals("https://mesh.example/d2/mobilecompanion/events", resolved.toString())
        assertEquals("mesh.example", resolved!!.host)
        assertTrue(resolved.isHttps)
        assertNull(NotificationEndpoint.resolve("http://mesh.example/", "/mobilecompanion/events"))
        assertNull(
            NotificationEndpoint.resolve(
                "https://mesh.example/",
                "https://evil.example/mobilecompanion/events",
            ),
        )
        assertNull(
            NotificationEndpoint.resolve(
                "https://user:pass@mesh.example/",
                "/mobilecompanion/events",
            ),
        )
    }

    @Test
    fun scopeIsBoundToProfileOriginAndUser() {
        val profileA = NotificationProfileScope.scopeKey("profileA", "https://mesh.example/", "user/a")
        val profileB = NotificationProfileScope.scopeKey("profileB", "https://mesh.example/", "user/a")
        val otherUser = NotificationProfileScope.scopeKey("profileA", "https://mesh.example/", "user/b")
        val otherServer = NotificationProfileScope.scopeKey("profileA", "https://other.example/", "user/a")
        assertNotNull(profileA)
        assertNotEquals(profileA, profileB)
        assertNotEquals(profileA, otherUser)
        assertNotEquals(profileA, otherServer)
        assertEquals(profileA, NotificationProfileScope.scopeKey("profileA", "https://mesh.example", "user/a"))
        assertNull(NotificationProfileScope.scopeKey("bad id", "https://mesh.example/", "user/a"))
        assertNull(NotificationProfileScope.scopeKey("profileA", "http://mesh.example/", "user/a"))
    }
}
