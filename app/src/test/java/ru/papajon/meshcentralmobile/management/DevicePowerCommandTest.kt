package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class DevicePowerCommandTest {
    private val device = MeshDevice("node//example", "mesh//example", "Example", conn = 1, agentId = 4, rights = MeshRights.RESET_OFF)
    @Test fun sleepUsesAuditedPowerActionFour() {
        assertTrue(JSONObject("""{"action":"poweraction","nodeids":["node//example"],"actiontype":4}""").similar(DevicePowerCommand.create(device, true, DevicePowerAction.Sleep)))
    }
    @Test fun sleepRequiresResetOffFreshConnectivityAndComputerAgent() {
        for (denied in listOf(device.copy(rights = null), device.copy(rights = MeshRights.REMOTE_CONTROL),
            device.copy(conn = 0), device.copy(conn = 2), device.copy(agentId = 14), device.copy(agentId = null), device.copy(connectivityKnown = false))) {
            assertFalse(DevicePowerAction.Sleep in DevicePowerCommand.available(denied, true))
            assertThrows(IllegalArgumentException::class.java) { DevicePowerCommand.create(denied, true, DevicePowerAction.Sleep) }
        }
        assertFalse(DevicePowerAction.Sleep in DevicePowerCommand.available(device, false))
        assertTrue(DevicePowerAction.Sleep in DevicePowerCommand.available(device.copy(rights = MeshRights.FULL), true))
    }
    @Test fun existingPowerActionsKeepTheirWireCodes() {
        assertEquals(2, DevicePowerCommand.create(device, true, DevicePowerAction.Shutdown).getInt("actiontype"))
        assertEquals(3, DevicePowerCommand.create(device, true, DevicePowerAction.Restart).getInt("actiontype"))
        val wake = DevicePowerCommand.create(device.copy(conn = 0, rights = MeshRights.WAKE_DEVICE), true, DevicePowerAction.Wake)
        assertEquals("wakedevices", wake.getString("action")); assertFalse(wake.has("actiontype"))
    }
    @Test fun officialPhonesNeverExposeComputerPowerActions() {
        for (conn in listOf(0, 1, 2, 3)) {
            val phone = device.copy(agentId = 14, conn = conn, rights = MeshRights.FULL)
            assertTrue(DeviceInspectorController.availablePowerActions(phone, true).isEmpty())
            for (action in DevicePowerAction.entries) {
                assertThrows(IllegalArgumentException::class.java) { DevicePowerCommand.create(phone, true, action) }
            }
        }
    }
}
