package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.discovery.NeedsAttentionFilter
import ru.papajon.meshcentralmobile.management.*

class ParityFixesProtocolTest {
    @Test fun linuxAgentTypeIdentifiesUbuntuAndMissingOsDescriptions() {
        for (os in listOf("Ubuntu 24.04", "")) {
            val device = (MeshControlParser.parse("""{"action":"nodes","nodes":{"mesh//example":[{"_id":"node//example","agent":{"id":6},"osdesc":"$os"}]}}""") as MeshControlEvent.Nodes).devices.single()
            assertTrue(device.isLinux)
            assertFalse(device.isWindows)
        }
    }
    private fun node(fields: String = ""): MeshDevice = (MeshControlParser.parse(
        """{"action":"nodes","nodes":{"mesh//example":[{"_id":"node//example","name":"Example","agent":{"id":3}$fields}]}}"""
    ) as MeshControlEvent.Nodes).devices.single()

    @Test fun agentSecurityHealthValuesAreNotWebUiLabels() {
        for ((wire, expected) in mapOf("OK" to "Ok", "WARNING" to "Warning", "PROBLEM" to "Problem", "UNKNOWN" to "Unknown", "BAD" to "Unknown")) {
            val status = WindowsSecurityParser.parse(JSONObject("""{"wsc":{"firewall":"$wire"}}""")).center.firewall
            assertEquals(wire, expected, status.name)
        }
    }
    @Test fun inactiveDefenderDoesNotHideHealthyActiveAntivirus() {
        val security = WindowsSecurityParser.parse(JSONObject("""{"av":[{"product":"Windows Defender","enabled":false,"updated":false},{"product":"Example AV","enabled":true,"updated":true}]}"""))
        assertFalse(security.needsAttention)
        assertTrue(WindowsSecurityParser.parse(JSONObject("""{"av":[{"enabled":false,"updated":true}]}""")).needsAttention)
        assertTrue(WindowsSecurityParser.parse(JSONObject("""{"av":[{"enabled":true,"updated":false}]}""")).needsAttention)
    }
    @Test fun linuxClamAvDoesNotEnterWindowsAttentionFilter() {
        assertFalse(NeedsAttentionFilter.matches(node(""", "osdesc":"Linux", "av":[{"product":"ClamAV","enabled":false,"updated":false}]""")))
    }
    @Test fun freshNodesWithoutConnMeansOfflineAndAllowsWake() {
        val device = node().copy(rights = MeshRights.WAKE_DEVICE)
        assertTrue(device.connectivityKnown)
        assertEquals(setOf(DevicePowerAction.Wake), DevicePowerCommand.available(device, true))
        assertTrue(DevicePowerCommand.available(device, false).isEmpty())
    }
    @Test fun uninstallObservationUsesActualOfflineNodesFrame() {
        val reply = JSONObject("""{"action":"nodes","nodes":{"mesh//example":[{"_id":"node//example","agent":{"id":3}}]}}""")
        assertEquals(RemovalObservation.Disconnected, DeviceRemovalCommand.observeReply(DeviceRemovalAction.UninstallAgent, "node//example", reply, true))
        assertEquals(RemovalObservation.Unknown, DeviceRemovalCommand.observeReply(DeviceRemovalAction.UninstallAgent, "node//example", reply, false))
    }
    @Test fun siteAdministratorWithoutAllGroupsHasOnlyLinkedRights() {
        val device = MeshControlParser.resolveRights(listOf(node()), emptyList(), "user//example", true).single()
        assertEquals(MeshRights.NONE, device.rights)
        assertTrue(DeviceRemovalCommand.available(device, true).isEmpty())
    }
    @Test fun serverInfoAndUserRestrictionsDetermineEffectiveRights() {
        val server = MeshControlParser.parse("""{"action":"serverinfo","serverinfo":{"manageAllDeviceGroups":true}}""") as MeshControlEvent.ServerInfo
        val user = MeshControlParser.parse("""{"action":"userinfo","userinfo":{"_id":"user//example","siteadmin":4294967295,"removeRights":425984}}""") as MeshControlEvent.UserInfo
        val device = MeshControlParser.resolveRights(listOf(node(",\"conn\":1")), emptyList(), user.userId, user.siteAdmin,
            manageAllDeviceGroups = server.capabilities.manageAllDeviceGroups, removeRights = user.removeRights).single()
        assertEquals(1589487, device.rights) // FULL base 2015471 minus reset/off, command and uninstall.
        assertFalse(RunCommand.allowed(device, true))
        assertTrue(DeviceRemovalCommand.available(device, true).isEmpty())
        assertTrue(DevicePowerCommand.available(device, true).isEmpty())
    }
    @Test fun eachRemoveRightsBitMatchesServerAddAndSubtractMasks() {
        val groups = listOf(MeshGroup("mesh//example", "Example", rightsByUser = mapOf("user//example" to MeshRights.FULL)))
        val fullBase = 2015471
        for (bit in listOf(8,16,32768,131072,64,262144)) {
            val device = MeshControlParser.resolveRights(listOf(node()), groups, "user//example", false, removeRights = bit).single()
            assertEquals(fullBase and bit.inv(), device.rights)
        }
        for (bit in listOf(65536,256,512,1024,4194304,8388608)) {
            val device = MeshControlParser.resolveRights(listOf(node()), groups, "user//example", false, removeRights = bit).single()
            assertEquals(fullBase or bit, device.rights)
        }
    }
    @Test fun partialChangenodeStillDoesNotOverwriteConnectivity() {
        val event = MeshControlParser.parse("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//example","name":"Renamed"}}}""") as MeshControlEvent.NodeUpdated
        assertFalse((event.event as MeshNodeEvent.NodeChanged).device.connectivityKnown)
    }
}
