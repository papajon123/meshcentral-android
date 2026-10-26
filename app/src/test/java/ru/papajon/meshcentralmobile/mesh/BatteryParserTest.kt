package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class BatteryParserTest {
    private fun node(battery: String): MeshDevice = (MeshControlParser.parse(
        """{"action":"nodes","nodes":{"mesh//test":[{"_id":"node//phone","agent":{"id":14},"sessions":{"battery":$battery}}]}}"""
    ) as MeshControlEvent.Nodes).devices.single()

    @Test fun nodesCarryLiveBatteryIncludingZero() {
        assertEquals(85.5, node("""{"state":"ac","level":85.5}""").battery?.level)
        assertEquals(true, node("""{"state":"ac","level":85}""").battery?.charging)
        assertEquals(0.0, node("""{"state":"dc","level":0}""").battery?.level)
        assertEquals(false, node("""{"state":"dc"}""").battery?.charging)
    }

    @Test fun missingAndMalformedValuesRemainUnknown() {
        for (value in listOf("null", "{}", """{"level":null}""", """{"level":"80"}""",
            """{"level":-1,"state":"bad"}""", """{"level":101}""", """{"level":true}""")) {
            assertNull(node(value).battery?.level)
            assertNull(node(value).battery?.charging)
        }
    }

    @Test fun sessionsEventUsesNodeIdAndCanClearBattery() {
        val event = (MeshControlParser.parse("""{"action":"event","event":{"action":"devicesessions","nodeid":"node//phone","sessions":{"battery":{"level":20,"state":"dc"}}}}""")
            as MeshControlEvent.NodeUpdated).event as MeshNodeEvent.DeviceSessions
        assertEquals("node//phone", event.nodeId)
        assertEquals(20.0, event.battery?.level)
        val cleared = (MeshControlParser.parse("""{"action":"event","event":{"action":"devicesessions","nodeid":"node//phone","sessions":{}}}""")
            as MeshControlEvent.NodeUpdated).event as MeshNodeEvent.DeviceSessions
        assertNull(cleared.battery)
    }
}
