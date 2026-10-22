package ru.papajon.meshcentralmobile.notifications

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventParserTest {
    private val now = 1_800_000_000_000L

    @Test fun meshAgentBase64IdentifiersSurviveParsingAndPersistence() {
        val nodeId = "node/d1/abc${'$'}def@ghi"
        val page = NotificationEventParser.parse(body(12, item(node = nodeId)), 0, now)
        assertEquals(nodeId, page.items.single().nodeId)
        val saved = NotificationCodec.encodeFeed(page.items)
        assertEquals(nodeId, NotificationCodec.decodeFeed(saved).single().nodeId)
    }

    private fun item(
        id: String = "mce_12",
        kind: String = "job-completed",
        expiresAt: Long = now + 60_000,
        time: Long = now - 1_000,
        node: String? = "node/d1/abc",
        job: String? = "mc_123",
    ) = JSONObject()
        .put("id", id)
        .put("sequence", 12)
        .put("kind", kind)
        .put("nodeid", node ?: JSONObject.NULL)
        .put("jobId", job ?: JSONObject.NULL)
        .put("time", time)
        .put("expiresAt", expiresAt)
        .put("msg", "must not be retained")
        .put("result", JSONObject().put("secret", "must not be retained"))

    private fun body(cursor: Long, vararg items: JSONObject, reset: Boolean = false) = JSONObject()
        .put("items", JSONArray().apply { items.forEach { put(it) } })
        .put("cursor", cursor)
        .put("resetCursor", reset)
        .toString()

    @Test
    fun keepsOnlyAllowlistedFieldsAndSkipsUnknownKindsAndExpiredItems() {
        val page = NotificationEventParser.parse(
            body(
                9,
                item(),
                item(id = "mce_13", kind = "future-kind"),
                item(id = "mce_14", expiresAt = now - 1),
                item(id = "<script>", kind = "device-alert"),
            ),
            previousCursor = 3,
            now = now,
        )
        assertEquals(9, page.cursor)
        assertFalse(page.resetCursor)
        assertEquals(1, page.items.size)
        val event = page.items.single()
        assertEquals("mce_12", event.id)
        assertEquals(NotificationEventKind.JOB_COMPLETED, event.kind)
        assertEquals("node/d1/abc", event.nodeId)
        assertEquals("mc_123", event.jobId)
    }

    @Test
    fun acceptsCursorResetAndRejectsBackwardsCursorWithoutReset() {
        val reset = NotificationEventParser.parse(body(2, reset = true), 100, now)
        assertTrue(reset.resetCursor)
        assertEquals(2, reset.cursor)
        assertThrows(NotificationProtocolException::class.java) {
            NotificationEventParser.parse(body(2), 100, now)
        }
    }

    @Test
    fun rejectsMalformedOrOversizedBodies() {
        assertThrows(NotificationProtocolException::class.java) {
            NotificationEventParser.parse("not json", 0, now)
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationEventParser.parse("""{"items":[]}""", 0, now)
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationEventParser.parse("""{"items":[],"cursor":-1}""", 0, now)
        }
        assertThrows(NotificationProtocolException::class.java) {
            NotificationEventParser.parse("x".repeat(NotificationEventParser.MAX_BODY_BYTES + 1), 0, now)
        }
    }

    @Test
    fun duplicateItemsAreCollapsedById() {
        val page = NotificationEventParser.parse(
            body(5, item(), item(id = "mce_12", kind = "device-offline")),
            0,
            now,
        )
        assertEquals(1, page.items.size)
        assertEquals(NotificationEventKind.JOB_COMPLETED, page.items.single().kind)
    }
}
