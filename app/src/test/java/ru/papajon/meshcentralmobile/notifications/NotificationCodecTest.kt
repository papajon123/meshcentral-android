package ru.papajon.meshcentralmobile.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCodecTest {
    private val now = 1_800_000_000_000L
    private val serviceUrl = "https://mesh.example/d1/"

    private fun token(expiresAt: Long = now + 30L * 24 * 60 * 60 * 1000) = NotificationToken(
        tokenId = "mcn_0123456789abcdef",
        token = "A".repeat(43),
        endpoint = "/d1/mobilecompanion/events",
        expiresAt = expiresAt,
        userId = "user/d1/alice",
    )

    @Test
    fun tokenRoundTripsAndRejectsInvalidFields() {
        val encoded = requireNotNull(NotificationCodec.encodeToken(token()))
        assertEquals(token(), NotificationCodec.decodeToken(encoded))
        assertNull(NotificationCodec.decodeToken("{}"))
        assertNull(NotificationCodec.decodeToken(encoded.replace("A".repeat(43), "short")))
        assertNull(NotificationCodec.decodeToken(encoded.replace("/d1/mobilecompanion/events", "/../events")))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= NotificationCodec.MAX_SECRET_BYTES)
    }

    @Test
    fun feedRoundTripsOnlyWhitelistedFields() {
        val item = NotificationFeedItem(
            "mce_12",
            NotificationEventKind.JOB_COMPLETED,
            "node/d1/a",
            "mc_1",
            now - 1000,
            now + 1000,
        )
        val decoded = NotificationCodec.decodeFeed(NotificationCodec.encodeFeed(listOf(item)))
        assertEquals(listOf(item), decoded)
        val withoutIds = item.copy(nodeId = null, jobId = null)
        assertEquals(listOf(withoutIds), NotificationCodec.decodeFeed(NotificationCodec.encodeFeed(listOf(withoutIds))))
        assertTrue(NotificationCodec.decodeFeed("not json").isEmpty())
    }

    @Test
    fun settingsKeepAnExplicitEmptyFilterSet() {
        val empty = NotificationSettings(kinds = emptySet())
        assertEquals(empty, NotificationCodec.decodeSettings(NotificationCodec.encodeSettings(empty)))
        assertEquals(
            NotificationSettings(),
            NotificationCodec.decodeSettings(null),
        )
        val quiet = NotificationSettings(
            kinds = setOf(NotificationEventKind.DEVICE_ALERT),
            quietHours = QuietHours(enabled = true, startHour = 0, endHour = 23),
        )
        assertEquals(quiet, NotificationCodec.decodeSettings(NotificationCodec.encodeSettings(quiet)))
    }

    @Test
    fun registrationParserChecksEndpointTtlAndTokenShape() {
        val data = JSONObject()
            .put("token", "B".repeat(43))
            .put("tokenId", "mcn_0123456789abcdef")
            .put("endpoint", "/d1/mobilecompanion/events")
            .put("expiresAt", now + 30L * 24 * 60 * 60 * 1000)
        val parsed = NotificationRegistrationParser.parse(data, serviceUrl, "user/d1/alice", now)
        assertEquals("/d1/mobilecompanion/events", parsed.endpoint)
        assertEquals("user/d1/alice", parsed.userId)
        assertThrows(NotificationProtocolException::class.java) {
            NotificationRegistrationParser.parse(data.put("endpoint", "//evil/mobilecompanion/events"), serviceUrl, "user/d1/alice", now)
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationRegistrationParser.parse(
                data.put("endpoint", "/d1/mobilecompanion/events")
                    .put("expiresAt", now + NotificationContract.MAX_TOKEN_TTL_MILLIS + 1),
                serviceUrl,
                "user/d1/alice",
                now,
            )
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationRegistrationParser.parse(
                data.put("expiresAt", now).put("token", "short"),
                serviceUrl,
                "user/d1/alice",
                now,
            )
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationRegistrationParser.parse(
                data.put("token", "C".repeat(43)).put("expiresAt", now + 1000),
                "http://mesh.example/",
                "user/d1/alice",
                now,
            )
        }
    }

    @Test
    fun pendingIdsAreBoundedAndValidated() {
        val ids = setOf("mce_1", "../bad", "mce_2")
        val decoded = NotificationCodec.decodePending(NotificationCodec.encodePending(ids))
        assertEquals(setOf("mce_1", "mce_2"), decoded)
        assertFalse(decoded.contains("../bad"))
    }
}
