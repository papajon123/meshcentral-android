package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.model.ConnectionState

class PhoneRemoteInputTest {
    private val phone = MeshDevice("node//phone", "mesh//test", "Phone", conn = 1, agentId = 14, rights = 8)

    @Test fun onlyExactCapabilityValuesAreAccepted() {
        assertEquals(PhoneRemoteInput.Enabled, PhoneRemoteInput.fromSystemInfo(JSONObject("""{"remoteInput":"enabled"}""")))
        assertEquals(PhoneRemoteInput.Disabled, PhoneRemoteInput.fromSystemInfo(JSONObject("""{"remoteInput":"disabled"}""")))
        assertEquals(PhoneRemoteInput.Unsupported, PhoneRemoteInput.fromSystemInfo(JSONObject("""{"remoteInput":"unsupported"}""")))
        for (json in listOf("{}", """{"remoteInput":null}""", """{"remoteInput":true}""", """{"remoteInput":8}""",
            """{"remoteInput":"ENABLED"}""", """{"remoteInput":" enabled "}""", """{"remoteInput":{}}""",
            """{"hardware":{"remoteInput":"enabled"}}""", """{"remoteInput":"enabled","noinfo":true}""")) {
            assertEquals(json, PhoneRemoteInput.Unknown, PhoneRemoteInput.fromSystemInfo(JSONObject(json)))
        }
    }

    @Test fun phoneRequiresCapabilityAndExplicitUnrestrictedControlRights() {
        for (capability in PhoneRemoteInput.entries) {
            for (rights in listOf(null, 0, 8, 256, 264, 8 or 4096, 8 or 65536, -2, MeshRights.FULL)) {
                val target = phone.copy(remoteInput = capability, rights = rights)
                val allowed = capability == PhoneRemoteInput.Enabled && rights in listOf(8, MeshRights.FULL)
                assertEquals("$capability / $rights", allowed, target.inputAllowed)
                assertEquals(allowed, RemoteDesktopState(device = target, connectionState = ConnectionState.Connected,
                    handshakeComplete = true).inputEnabled)
            }
        }
        assertFalse(phone.copy(remoteInput = PhoneRemoteInput.Enabled, conn = 0).inputAllowed)
        assertFalse(phone.copy(remoteInput = PhoneRemoteInput.Enabled, conn = 2).inputAllowed)
        assertFalse(phone.copy(remoteInput = PhoneRemoteInput.Enabled, connectivityKnown = false).inputAllowed)
    }

    @Test fun computerPolicyStaysUnchanged() {
        val pc = phone.copy(agentId = 3)
        assertTrue(pc.supportsRemoteInput)
        assertTrue(pc.inputAllowed)
        assertFalse(pc.copy(rights = 256).inputAllowed)
        assertTrue(pc.copy(rights = null).inputAllowed)
    }
}
