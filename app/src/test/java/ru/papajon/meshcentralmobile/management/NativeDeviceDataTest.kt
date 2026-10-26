package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class NativeDeviceDataTest {
    private val device = MeshDevice("node//example", "mesh//example", "Example", agentId = 4, conn = 1, rights = MeshRights.FULL)
    @Test fun nodesExposeLastBootWithoutSystemInventoryAndRejectMalformedTime() {
        val node = MeshControlParser.parseNode(JSONObject("""{"_id":"node//example","lastbootuptime":1700000000000,"unknown":"ignored"}"""), "mesh//example")!!
        assertEquals(1700000000000L, node.lastBootMillis)
        assertNull(MeshControlParser.parseNode(JSONObject("""{"_id":"node//example","lastbootuptime":"1700000000000"}"""), "")!!.lastBootMillis)
    }
    @Test fun hardwareUsesAuditedFieldsAndIgnoresPrivateAndUnknownData() {
        val result = SystemInfoParser.parse(JSONObject("""{"node":{"lastbootuptime":1700000000000},"hardware":{"identifiers":{"bios_vendor":"Example","bios_version":"1.2","bios_date":"2025-01-01","bios_mode":"UEFI","board_name":"Board","board_vendor":"Vendor","board_version":"2","bios_serial":"private"},"tpm":{"SpecVersion":"2.0","ManufacturerId":123,"ManufacturerVersion":"3","IsEnabled":true,"IsActivated":false,"IsOwned":"true"}}}"""))
        assertEquals("Example", result.hardwareDetails.biosVendor)
        assertEquals("Board", result.hardwareDetails.boardName)
        assertEquals("123", result.hardwareDetails.tpmManufacturer)
        assertEquals(true, result.hardwareDetails.tpmEnabled)
        assertEquals(false, result.hardwareDetails.tpmActivated)
        assertNull(result.hardwareDetails.tpmOwned)
        assertEquals(1700000000000L, result.hardwareDetails.lastBootMillis)
        assertFalse(result.toString().contains("private"))
        assertNull(SystemInfoParser.parse(JSONObject()).hardwareDetails.biosVendor)
    }
    @Test fun softwareIsAStringEncodedArrayAndNeverExposesUninstallCommands() {
        val result = InstalledSoftware.parse(JSONObject().put("value", """[{"name":"Editor","version":"2","publisher":"Example","uninstall":"private","unknown":42},null,{}]"""))
        assertEquals(2, result.size)
        assertEquals("Editor", result.first().name)
        assertNull(result.last().name)
        assertFalse(result.toString().contains("private"))
        assertThrows(IllegalArgumentException::class.java) { InstalledSoftware.parse(JSONObject().put("value", """{"error":"offline"}""")) }
    }
    @Test fun exactReadCommandsAndRights() {
        assertTrue(JSONObject("""{"action":"software","nodeid":"node//example","type":"installedapps"}""").similar(InstalledSoftware.command(device, true)))
        assertTrue(JSONObject("""{"action":"msg","type":"cpuinfo","nodeid":"node//example"}""").similar(NativeMetrics.command(device, true)))
        for (bad in listOf(device.copy(rights = null), device.copy(rights = 0), device.copy(conn = 0), device.copy(agentId = 14), device.copy(connectivityKnown = false))) {
            assertFalse(InstalledSoftware.allowed(bad, true)); assertFalse(NativeMetrics.allowed(bad, true))
        }
        assertFalse(InstalledSoftware.allowed(device.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.NO_SOFTWARE), true))
        assertTrue(InstalledSoftware.allowed(device.copy(rights = MeshRights.REMOTE_VIEW), true))
        assertFalse(InstalledSoftware.allowed(device, false))
    }
    @Test fun metricsRejectOutOfRangeAndWrongTypesInsteadOfInventingZero() {
        val result = NativeMetrics.parse(JSONObject("""{"cpu":{"total":21.5},"memory":{"percentConsumed":45},"unknown":"ignored"}"""), 100L)
        assertEquals(21.5, result.cpuPercent!!, 0.001); assertEquals(45.0, result.memoryPercent!!, 0.001)
        val unknown = NativeMetrics.parse(JSONObject("""{"cpu":{"total":101},"memory":{"percentConsumed":"45"}}"""), 100L)
        assertNull(unknown.cpuPercent); assertNull(unknown.memoryPercent)
    }
}
