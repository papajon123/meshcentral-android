package ru.papajon.meshcentralmobile.management

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.*

class ToastCommandTest {
    private val device = MeshDevice("node//example", "mesh//example", "Example", conn = 1, agentId = 4, rights = MeshRights.CHAT_NOTIFY)
    @Test fun exactJsonContainsOnlyAuditedFieldsAndEscapesUserText() {
        val title = "Title \"quoted\""
        val message = "Hello\nsecond line"
        val result = ToastCommand.create(device, true, title, message)
        val expected = JSONObject().put("action", "toast").put("nodeids", org.json.JSONArray().put(device.nodeId)).put("title", title).put("msg", message)
        assertTrue(expected.similar(JSONObject(result.toString())))
        assertEquals(4, result.length())
    }
    @Test fun deniesMissingWrongAndRevokedRights() {
        for (rights in listOf(null, 0, MeshRights.REMOTE_CONTROL, MeshRights.REMOTE_VIEW, MeshRights.RESET_OFF)) {
            val denied = device.copy(rights = rights)
            assertFalse(ToastCommand.allowed(denied, true))
            assertThrows(IllegalArgumentException::class.java) { ToastCommand.create(denied, true, "Title", "Message") }
        }
        assertTrue(ToastCommand.allowed(device.copy(rights = MeshRights.FULL), true))
    }
    @Test fun deniesAndroidNonAgentsOfflineStaleAndUnknownConnectivity() {
        for (denied in listOf(device.copy(agentId = 14), device.copy(agentId = null), device.copy(conn = 0),
            device.copy(conn = 2), device.copy(connectivityKnown = false))) assertFalse(ToastCommand.allowed(denied, true))
        assertFalse(ToastCommand.allowed(device, false))
    }
    @Test fun enforcesServerLengthBoundariesAndRejectsBlank() {
        assertTrue(ToastCommand.valid("x", "x"))
        assertTrue(ToastCommand.valid("x".repeat(512), "x".repeat(4096)))
        for ((title, msg) in listOf("" to "x", " " to "x", "x" to "", "x" to "\n",
            "x".repeat(513) to "x", "x" to "x".repeat(4097))) {
            assertFalse(ToastCommand.valid(title, msg))
            assertThrows(IllegalArgumentException::class.java) { ToastCommand.create(device, true, title, msg) }
        }
        // Kotlin/JavaScript lengths both count UTF-16 code units, including surrogate pairs.
        assertFalse(ToastCommand.valid("😀".repeat(257), "x"))
    }
}
