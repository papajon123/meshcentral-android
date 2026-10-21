package ru.papajon.meshcentralmobile.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialScopeTest {
    @Test fun normalizesHostDefaultPortAndTrailingSlash() {
        assertTrue(CredentialScope.matches("https://EXAMPLE.com:443/team", "https://example.com/team/"))
    }

    @Test fun neverReusesCredentialsAcrossHostsPortsSchemesOrDomains() {
        val original = "https://example.com/team/"
        listOf("https://other.example/team/", "https://example.com:8443/team/",
            "http://example.com/team/", "https://example.com/other/", "https://example.com/Team/")
            .forEach { assertFalse(CredentialScope.matches(original, it)) }
    }

    @Test fun unscopedLegacyCredentialsRequireManualLogin() {
        assertFalse(CredentialScope.matches(null, "https://example.com/"))
        assertFalse(CredentialScope.matches("", "https://example.com/"))
    }

    @Test fun malformedOrCredentialBearingEndpointsAreRejected() {
        listOf("", "not a URL", "https://user:pass@example.com/", "https://example.com/?key=test",
            "https://example.com/#fragment").forEach {
            assertFalse(CredentialScope.matches(it, it))
        }
    }
}
