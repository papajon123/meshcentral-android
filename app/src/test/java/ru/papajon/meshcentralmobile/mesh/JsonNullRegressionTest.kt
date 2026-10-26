package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class JsonNullRegressionTest {
    // Android's org.json coerces its NULL sentinel to "null"; the JVM dependency does not.
    // Reproduce that platform behavior at the parser boundary rather than testing a different library.
    private class AndroidJson(text: String) : JSONObject(text) {
        override fun optString(name: String): String = if (has(name) && isNull(name)) "null" else super.optString(name)
        override fun optString(name: String, fallback: String): String = if (has(name) && isNull(name)) "null" else super.optString(name, fallback)
        override fun getString(name: String): String = if (has(name) && isNull(name)) "null" else super.getString(name)
    }

    @Test fun nullHostDoesNotOverrideKnownAddress() {
        val device = MeshControlParser.parseNode(AndroidJson("""{"_id":"node//test","host":null,"ip":"192.0.2.1","osdesc":null,"name":null,"meshid":null}"""), "mesh//test")!!
        assertNull(device.host)
        assertEquals("192.0.2.1", device.ipAddress)
        assertEquals("", device.osDescription)
        assertEquals("node//test", device.name)
        assertEquals("mesh//test", device.meshId)
    }

    @Test fun nullIdentityAndCookiesAreAbsentButLiteralNullIsPreserved() {
        assertNull(MeshControlParser.parseNode(AndroidJson("""{"_id":null}"""), ""))
        val cookies = MeshControlParser.parse(AndroidJson("""{"action":"authcookie","cookie":null,"rcookie":null}""")) as MeshControlEvent.AuthCookie
        assertNull(cookies.cookie)
        assertNull(cookies.relayCookie)
        assertEquals("null", MeshControlParser.parseNode(AndroidJson("""{"_id":"node//test","host":"null"}"""), "")!!.host)
    }

    @Test fun nullFileNameIsRejectedOnAndroidInsteadOfBecomingAnActionTarget() {
        val reply = JSONObject().put("dir", JSONArray().put(AndroidJson("""{"n":null,"t":3,"s":0}""")))
        assertThrows(IllegalArgumentException::class.java) {
            ru.papajon.meshcentralmobile.mesh.files.AgentFilesProtocol.entries(reply)
        }
    }
}
