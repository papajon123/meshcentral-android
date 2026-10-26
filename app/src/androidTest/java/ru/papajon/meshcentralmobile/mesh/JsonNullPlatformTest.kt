package ru.papajon.meshcentralmobile.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.json.optText

class JsonNullPlatformTest {
    @Test fun androidNullSentinelNeverBecomesText() {
        val json = JSONObject("""{"value":null,"literal":"null","number":123,"blank":""}""")
        assertEquals("null", json.optString("value")) // Prove this is the real Android behavior.
        assertEquals("", json.optText("value"))
        assertEquals("fallback", json.optText("value", "fallback"))
        assertEquals("fallback", json.optText("missing", "fallback"))
        assertEquals("null", json.optText("literal"))
        assertEquals("123", json.optText("number"))
        assertEquals("", json.optText("blank", "fallback"))
        assertEquals("fallback", JSONArray("[null]").optText(0, "fallback"))
    }

    @Test fun exactServerFramesKeepUnknownFieldsAbsent() {
        val nodes = MeshControlParser.parse("""{"action":"nodes","nodes":{"mesh//test":[{"_id":"node//test","host":null,"ip":"192.0.2.1","osdesc":null,"agent":{"ver":null}},{"_id":null}]}}""") as MeshControlEvent.Nodes
        val device = nodes.devices.single()
        assertNull(device.host)
        assertNull(device.agentVersion)
        assertEquals("192.0.2.1", device.ipAddress)
        assertEquals("", device.osDescription)
        val cookies = MeshControlParser.parse("""{"action":"authcookie","cookie":null,"rcookie":null}""") as MeshControlEvent.AuthCookie
        assertNull(cookies.cookie)
        assertNull(cookies.relayCookie)
    }

    @Test fun nullFileNameCannotBecomeAnActionableFileCalledNull() {
        val reply = JSONObject("""{"action":"ls","path":"/tmp","dir":[{"n":null,"t":3,"s":0}]}""")
        assertEquals("null", reply.getJSONArray("dir").getJSONObject(0).getString("n"))
        assertThrows(IllegalArgumentException::class.java) {
            ru.papajon.meshcentralmobile.mesh.files.AgentFilesProtocol.entries(reply)
        }
    }
}
