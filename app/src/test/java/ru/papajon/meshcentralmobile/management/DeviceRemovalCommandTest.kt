package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class DeviceRemovalCommandTest {
    private val device = MeshDevice("node//example", "mesh//example", "Example", agentId = 4, conn = 1, rights = 32768)
    @Test fun exactMessagesAndTwoStageNameConfirmation() {
        for ((action, wire) in listOf(DeviceRemovalAction.UninstallAgent to "uninstallagent", DeviceRemovalAction.RemoveDevice to "removedevices")) {
            assertTrue(JSONObject("""{"action":"$wire","nodeids":["node//example"]}""").similar(DeviceRemovalCommand.create(device, true, action, true, "Example")))
            assertThrows(IllegalArgumentException::class.java) { DeviceRemovalCommand.create(device, true, action, false, "Example") }
            assertThrows(IllegalArgumentException::class.java) { DeviceRemovalCommand.create(device, true, action, true, "example") }
        }
    }
    @Test fun staleRightsPhonesAndUnknownAgentsFailClosed() {
        for (denied in listOf(device.copy(rights = null), device.copy(rights = 8), device.copy(agentId = 14), device.copy(agentId = null))) {
            assertTrue(DeviceRemovalCommand.available(denied, true).isEmpty())
        }
        assertTrue(DeviceRemovalCommand.available(device, false).isEmpty())
        assertFalse(DeviceRemovalAction.UninstallAgent in DeviceRemovalCommand.available(device.copy(conn = 0), true))
        assertTrue(DeviceRemovalAction.RemoveDevice in DeviceRemovalCommand.available(device.copy(conn = 0), true))
        assertFalse(DeviceRemovalAction.UninstallAgent in DeviceRemovalCommand.available(device.copy(connectivityKnown = false), true))
    }
    @Test fun observationDoesNotClaimUninstallationOrDeletionFromDisconnect() {
        assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observe(DeviceRemovalAction.RemoveDevice, false, null))
        assertEquals(RemovalObservation.Absent, DeviceRemovalCommand.observe(DeviceRemovalAction.RemoveDevice, true, null))
        assertEquals(RemovalObservation.Disconnected, DeviceRemovalCommand.observe(DeviceRemovalAction.UninstallAgent, true, device.copy(conn = 0)))
        assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observe(DeviceRemovalAction.UninstallAgent, true, null))
        assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observe(DeviceRemovalAction.RemoveDevice, true, device.copy(conn = 0)))
    }
    @Test fun malformedOrStaleDeviceListsNeverEstablishAbsence() {
        for (raw in listOf("{}", """{"nodes":{"mesh//example":{}}}""", """{"nodes":{"mesh//example":[{}]}}""")) {
            assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observeReply(DeviceRemovalAction.RemoveDevice, device.nodeId, JSONObject(raw), true))
        }
        assertEquals(RemovalObservation.Absent, DeviceRemovalCommand.observeReply(DeviceRemovalAction.RemoveDevice, device.nodeId, JSONObject("""{"nodes":{},"future":true}"""), true))
        assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observeReply(DeviceRemovalAction.RemoveDevice, device.nodeId, JSONObject("""{"nodes":{}}"""), false))
    }
}
