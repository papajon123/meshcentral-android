package ru.papajon.meshcentralmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlValidatorTest {
    @Test fun acceptsHttpsRootAndMeshCentralSubPath() {
        assertNull(ServerUrlValidator.validate("https://example.com/", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("https://example.com", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("https://example.com/meshcentral/", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("https://example.com/meshcentral", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("  https://example.com:8443/mesh/  ", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("https://[::1]/", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("https://[::1]:8443/mesh/", allowHttpInDebug = false))
    }

    @Test fun acceptsUppercaseSchemeAndHost() {
        assertNull(ServerUrlValidator.validate("HTTPS://EXAMPLE.COM/", allowHttpInDebug = false))
    }

    @Test fun rejectsEmptyAddress() {
        assertNotNull(ServerUrlValidator.validate("", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("   ", allowHttpInDebug = false))
    }

    @Test fun httpIsAllowedOnlyWhenDebugOptInIsPassed() {
        assertNotNull(ServerUrlValidator.validate("http://example.com/", allowHttpInDebug = false))
        assertNull(ServerUrlValidator.validate("http://example.com/", allowHttpInDebug = true))
        assertNull(ServerUrlValidator.validate("http://192.0.2.10:8080/mesh/", allowHttpInDebug = true))
    }

    @Test fun nonHttpSchemeIsRejected() {
        assertNotNull(ServerUrlValidator.validate("ftp://example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("example.com/mesh/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("//example.com/", allowHttpInDebug = false))
    }

    @Test fun rejectsUserInfoQueryAndFragment() {
        assertNotNull(ServerUrlValidator.validate("https://user:secret@example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://user@example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://example.com/?next=1", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://example.com?", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://example.com/#section", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://example.com#", allowHttpInDebug = false))
    }

    @Test fun rejectsMissingOrMalformedHost() {
        assertNotNull(ServerUrlValidator.validate("https://", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https:///meshcentral/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://exa_mple.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://-bad.example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://bad-.example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://.example.com/", allowHttpInDebug = false))
        assertNotNull(ServerUrlValidator.validate("https://example..com/", allowHttpInDebug = false))
    }

    @Test fun rejectsInvalidPortWithPortSpecificMessage() {
        listOf(
            "https://example.com:0/",
            "https://example.com:65536/",
            "https://example.com:abc/",
            "https://example.com:/",
        ).forEach { address ->
            val error = ServerUrlValidator.validate(address, allowHttpInDebug = false)
            assertNotNull(address, error)
            assertTrue("$address -> $error", error!!.contains("Порт"))
        }
        assertNull(ServerUrlValidator.validate("https://example.com:443/", allowHttpInDebug = false))
    }

    @Test fun rejectsMalformedSyntaxWithRussianMessage() {
        val error = ServerUrlValidator.validate("https://example.com/mesh central/", allowHttpInDebug = false)
        assertNotNull(error)
        assertTrue(error!!.contains("неверно"))
    }

    @Test fun httpRejectionExplainsTheDebugOnlyRule() {
        val error = ServerUrlValidator.validate("http://example.com/", allowHttpInDebug = false)
        assertEquals("Обычный HTTP разрешён только в\u00A0отладочной сборке. Используйте https://.", error)
    }
}
