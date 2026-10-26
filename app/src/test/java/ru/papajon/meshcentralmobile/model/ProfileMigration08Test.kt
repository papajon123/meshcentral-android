package ru.papajon.meshcentralmobile.model

import org.junit.Assert.*
import org.junit.Test

class ProfileMigration08Test {
    @Test fun oldProfileWithoutPinDefaultsToNull() {
        val profile = ProfileCatalog.decode("""[{"id":"legacy","name":"Legacy","serverUrl":"https://mesh.example/","trustSelfSignedCertificates":true}]""").single()
        assertNull(profile.pinnedSpkiSha256)
        assertTrue(profile.trustSelfSignedCertificates)
    }

    @Test fun pinnedProfileRoundTripsWithoutLeakingToAnotherProfile() {
        val profiles = listOf(
            ServerProfile("first", "First", "https://first.example/", pinnedSpkiSha256 = "ab".repeat(32)),
            ServerProfile("second", "Second", "https://second.example/"),
        )
        assertEquals(profiles, ProfileCatalog.decode(ProfileCatalog.encode(profiles)))
    }

    @Test fun malformedStoredPinIsNeverAcceptedAsTrust() {
        for (pin in listOf("null", "", "AB".repeat(32), "0".repeat(63), "g".repeat(64))) {
            val profile = ProfileCatalog.decode("""[{"id":"test","pinnedSpkiSha256":"$pin"}]""").single()
            assertNull(profile.pinnedSpkiSha256)
        }
    }

    @Test fun changedHostOrPortDoesNotInheritTrust() {
        val original = ServerProfile("first", "First", "https://mesh.example/", pinnedSpkiSha256 = "ab".repeat(32))
        for (url in listOf("https://other.example/", "https://mesh.example:8443/", "https://mesh.example/other/")) {
            assertNull(original.withServerUrl(url).pinnedSpkiSha256)
        }
    }

    @Test fun equivalentNormalizedAddressPreservesTrustWithinTheProfile() {
        val original = ServerProfile("first", "First", "https://mesh.example/", pinnedSpkiSha256 = "ab".repeat(32))
        assertEquals(original.pinnedSpkiSha256, original.withServerUrl("https://MESH.example:443").pinnedSpkiSha256)
    }
}
