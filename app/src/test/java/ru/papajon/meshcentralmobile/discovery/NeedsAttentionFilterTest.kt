package ru.papajon.meshcentralmobile.discovery

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class NeedsAttentionFilterTest {
    private fun matches(raw: String) = NeedsAttentionFilter.matches(MeshDevice("node//example", "mesh//example", "Example", osDescription = "Windows 11",
        windowsSecurity = WindowsSecurityParser.parse(JSONObject(raw))))

    @Test fun healthyAndUnknownDevicesAreNotProblems() {
        for (raw in listOf("{}", """{"wsc":{"antiVirus":"OK","autoUpdate":"OK","firewall":"OK"}}""",
            """{"wsc":{"antiVirus":"NA","firewall":"unexpected"},"av":[{}]}""",
            """{"av":[{"enabled":true,"updated":true}]}""")) assertFalse(raw, matches(raw))
    }
    @Test fun eachCenterProblemAndEachProductProblemNeedsAttention() {
        for (field in listOf("antiVirus", "autoUpdate", "firewall")) assertTrue(matches("""{"wsc":{"$field":"PROBLEM"}}"""))
        assertTrue(matches("""{"av":[{"enabled":false,"updated":true}]}"""))
        assertTrue(matches("""{"av":[{"enabled":true,"updated":false}]}"""))
    }
    @Test fun offlineDevicesKeepTheirLastReportedProblem() {
        assertTrue(matches("""{"wsc":{"firewall":"PROBLEM"}}"""))
    }
}
