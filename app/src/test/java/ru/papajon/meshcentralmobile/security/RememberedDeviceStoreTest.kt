package ru.papajon.meshcentralmobile.security

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust

class RememberedDeviceStoreTest {
    private var raw: String? = null
    private var now = 1_000L
    private val store = RememberedDeviceStore({ raw }, { raw = it }, { raw = null }, { now })

    @Test fun storesOnlyForTheNormalizedServerAndUsernameUntilExpiry() {
        store.save("https://EXAMPLE.test:443/team", "alice", "fixture-cookie", 7, RememberedDeviceTrust.Platform)
        assertEquals(604_801_000L, store.get("https://example.test/team/", "alice")!!.expiresAt)
        assertNull(store.get("https://example.test/other/", "alice"))
        assertNull(store.get("https://example.test/team/", "bob"))
        now = 604_801_000L
        assertNull(store.get("https://example.test/team/", "alice"))
        assertNull(raw)
    }

    @Test fun survivesReopeningTheEncryptedRecordAndDoesNotExposeTheCookieInDiagnostics() {
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        val reopened = RememberedDeviceStore({ raw }, { raw = it }, { raw = null }, { now })
        assertEquals("fixture-cookie", reopened.get(SERVER, "alice")!!.cookie)
        assertFalse(reopened.get(SERVER, "alice").toString().contains("fixture-cookie"))
    }

    @Test fun clearForLogoutRemovesThePersistedRecord() {
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        store.clear()
        assertNull(raw)
        assertNull(store.get(SERVER, "alice"))
    }

    @Test fun changingServerErasesCookieSoSwitchingBackCannotRestoreIt() {
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        store.bind("https://other.example.test/")
        store.bind(SERVER)
        assertNull(raw)
        assertNull(store.get(SERVER, "alice"))
    }

    @Test fun changingUserErasesCookieButEquivalentEndpointDoesNot() {
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        store.bind("https://EXAMPLE.test:443/team", "alice")
        assertNotNull(store.get(SERVER, "alice"))
        store.bind(SERVER, "bob")
        assertNull(raw)
    }

    @Test fun onlyTheExactControlEndpointGetsTheCookie() {
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        assertEquals("fixture-cookie", cookie("https://example.test/team/control.ashx")?.value)
        listOf("https://other.test/team/control.ashx", "https://example.test:444/team/control.ashx",
            "http://example.test/team/control.ashx", "https://example.test/other/control.ashx",
            "https://example.test/team/meshrelay.ashx", "https://example.test/team/control.ashx/nested",
            "https://example.test/team/control.ashx?auth=synthetic").forEach { assertNull(cookie(it)) }
        assertNull(store.cookieForRequest(SERVER, "bob", "https://example.test/team/control.ashx".toHttpUrl(), false, RememberedDeviceTrust.Platform))
    }

    @Test fun httpRequiresTheExplicitDebugAllowance() {
        val server = "http://example.test/"
        store.save(server, "alice", "fixture-cookie", 30, RememberedDeviceTrust.DebugHttp)
        val target = "http://example.test/control.ashx".toHttpUrl()
        assertNull(store.cookieForRequest(server, "alice", target, false, RememberedDeviceTrust.DebugHttp))
        assertNotNull(store.cookieForRequest(server, "alice", target, true, RememberedDeviceTrust.DebugHttp))
    }

    @Test fun expiredCookieIsNotSentEvenWithoutReadingStatusFirst() {
        store.save(SERVER, "alice", "fixture-cookie", 1, RememberedDeviceTrust.Platform)
        now = 86_401_000L
        assertNull(cookie("https://example.test/team/control.ashx"))
        assertNull(raw)
    }

    @Test fun corruptRecordAndClockRollbackFailClosed() {
        raw = "invalid-json"
        assertNull(store.get(SERVER, "alice"))
        assertNull(raw)
        store.save(SERVER, "alice", "fixture-cookie", 1, RememberedDeviceTrust.Platform)
        now = 999
        assertNull(store.get(SERVER, "alice"))
        assertNull(raw)
    }

    @Test fun rejectsHeaderInjectionInvalidScopesAndDisabledDurations() {
        listOf("", "x; other=y", "x\r\nInjected: y", "has space", "\"quoted\"", "x".repeat(8193)).forEach {
            assertFalse(store.save(SERVER, "alice", it, 30, RememberedDeviceTrust.Platform))
        }
        assertFalse(store.save("https://u:p@example.test/", "alice", "fixture", 30, RememberedDeviceTrust.Platform))
        assertFalse(store.save(SERVER, "", "fixture", 30, RememberedDeviceTrust.Platform))
        assertFalse(store.save(SERVER, "alice", "fixture", 0, RememberedDeviceTrust.Platform))
        assertNull(raw)
    }

    @Test fun durationMatchesServerDefaultsAndRoundsDownFractionalDays() {
        listOf(null, "7", -1, 0.5, Double.NaN).forEach { assertEquals(30, RememberedDeviceStore.durationDays(it)) }
        assertEquals(0, RememberedDeviceStore.durationDays(0))
        assertEquals(7, RememberedDeviceStore.durationDays(7))
        assertEquals(2, RememberedDeviceStore.durationDays(2.9))
    }

    @Test fun onlyTheIssuingTrustModeAndKeyCanSendTheCookie() {
        val modes = listOf(RememberedDeviceTrust.Platform, RememberedDeviceTrust.Pinned("a".repeat(64)),
            RememberedDeviceTrust.Pinned("b".repeat(64)))
        val target = "https://example.test/team/control.ashx".toHttpUrl()
        for (issued in modes) {
            assertTrue(store.save(SERVER, "alice", "fixture-cookie", 30, issued))
            val reopened = RememberedDeviceStore({ raw }, { raw = it }, { raw = null }, { now })
            assertEquals(issued, reopened.get(SERVER, "alice")!!.trust)
            for (current in modes) {
                assertEquals(issued == current,
                    reopened.cookieForRequest(SERVER, "alice", target, false, current) != null)
            }
            assertNull(reopened.cookieForRequest(SERVER, "alice", target, true, null))
            assertNull(reopened.cookieForRequest(SERVER, "alice", target, true, RememberedDeviceTrust.DebugHttp))
        }
    }

    @Test fun restoringTheSamePinPreservesCookieButChangingTrustErasesIt() {
        val pin = RememberedDeviceTrust.Pinned("a".repeat(64))
        store.save(SERVER, "alice", "fixture-cookie", 30, pin)
        store.bindTrust(pin)
        assertNotNull(store.get(SERVER, "alice"))
        store.bindTrust(RememberedDeviceTrust.Platform)
        store.bindTrust(pin)
        assertNull(raw)
        store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
        store.bindTrust(pin)
        assertNull(raw)
        store.save(SERVER, "alice", "fixture-cookie", 30, pin)
        store.bindTrust(RememberedDeviceTrust.Pinned("b".repeat(64)))
        assertNull(raw)
    }

    @Test fun legacyOrMalformedTrustIsDiscardedInsteadOfAssumingPlatformTrust() {
        for (trust in listOf(null, "", "spki:invalid", "future-mode")) {
            store.save(SERVER, "alice", "fixture-cookie", 30, RememberedDeviceTrust.Platform)
            val json = org.json.JSONObject(raw!!)
            if (trust == null) json.remove("trust") else json.put("trust", trust)
            raw = json.toString()
            assertNull(cookie("https://example.test/team/control.ashx"))
            assertNull(raw)
        }
    }

    private fun cookie(url: String) = store.cookieForRequest(SERVER, "alice", url.toHttpUrl(), false, RememberedDeviceTrust.Platform)
    private companion object { const val SERVER = "https://example.test/team/" }
}
