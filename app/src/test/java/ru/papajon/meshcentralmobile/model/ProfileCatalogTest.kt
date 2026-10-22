package ru.papajon.meshcentralmobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    @Test fun catalogRoundTripKeepsIndependentServerOptions() {
        val profiles = listOf(
            ServerProfile("default", "Home", "https://home.example/", true),
            ServerProfile("lab-2", "Lab", "http://lab.example/", false, true, true),
        )
        val stored = ProfileCatalog.encode(profiles)
        assertEquals(profiles, ProfileCatalog.decode(stored))
        assertFalse(stored.contains("password"))
    }

    @Test fun malformedCatalogAndDuplicateIdsCannotCreateAmbiguousProfiles() {
        assertTrue(ProfileCatalog.decode("broken").isEmpty())
        val profiles = ProfileCatalog.decode("""[{"id":"a","name":"One","serverUrl":"https://one.example"},{"id":"a","name":"Other"},{"id":"../../unsafe"}]""")
        assertEquals(listOf("a"), profiles.map { it.id })
        assertEquals("https://one.example", profiles.single().serverUrl)
    }

    @Test fun profileLimitIsBoundedWhenLoadingUntrustedPreferences() {
        val profiles = (1..100).map { ServerProfile("p$it", "Profile $it", "https://example.com") }
        assertEquals(ProfileCatalog.MAX_PROFILES, ProfileCatalog.decode(ProfileCatalog.encode(profiles)).size)
    }
}
