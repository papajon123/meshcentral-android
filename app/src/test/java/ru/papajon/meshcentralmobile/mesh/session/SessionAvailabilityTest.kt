package ru.papajon.meshcentralmobile.mesh.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class SessionAvailabilityTest {
    private val node = MeshDevice("node//test", "mesh//test", "Test", conn = 1, mtype = 2, agentId = 6, agentCaps = 7, rights = 8)
    private val ssh = MeshServerCapabilities(features2 = 512)

    @Test fun agentRequiresAgentConnectivityRatherThanAmtConnectivity() {
        assertEquals(SessionAvailability.Offline, node.copy(conn = 2).sessionAvailability(RelayProtocol.AgentTerminal))
        assertEquals(SessionAvailability.Available, node.copy(conn = 3).sessionAvailability(RelayProtocol.AgentTerminal))
    }

    @Test fun absentMetadataIsNotKnownDenialOrKnownSupport() {
        assertEquals(SessionAvailability.Unknown, node.copy(rights = null).sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Unknown, node.copy(agentCaps = null).sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Unknown, node.copy(conn = 0, connectivityKnown = false).sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Unknown, node.sessionAvailability(RelayProtocol.SshTerminal))
        assertTrue(SessionAvailability.Unknown.canAttempt)
    }

    @Test fun fullRightsBypassesDenyBitsButNotCapabilities() {
        assertEquals(SessionAvailability.Available, node.copy(rights = -1).sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Unsupported, node.copy(rights = -1, agentCaps = 1).sessionAvailability(RelayProtocol.AgentFiles))
    }

    @Test fun terminalAndFileDenyBitsAreDistinctAnd4096IsNotViewOnly() {
        val deniedFiles = node.copy(rights = 8 or 1024)
        assertEquals(SessionAvailability.Denied, deniedFiles.sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Available, deniedFiles.sessionAvailability(RelayProtocol.AgentTerminal))
        assertEquals(SessionAvailability.Available, node.copy(rights = 8 or 4096).sessionAvailability(RelayProtocol.AgentTerminal))
        assertFalse(node.copy(rights = 8 or 4096).viewOnly)
    }

    @Test fun sshFilesUsesNoterminalAsActualServerDoes() {
        assertEquals(SessionAvailability.Available, node.copy(rights = 8 or 1024).sessionAvailability(RelayProtocol.SshFiles, ssh))
        assertEquals(SessionAvailability.Denied, node.copy(rights = 8 or 512).sessionAvailability(RelayProtocol.SshFiles, ssh))
    }

    @Test fun localSshDoesNotRequireAgentConnectionOrAgentCapabilities() {
        assertEquals(SessionAvailability.Available, node.copy(conn = 0, mtype = 3, agentCaps = 0).sessionAvailability(RelayProtocol.SshTerminal, ssh))
        assertEquals(SessionAvailability.Unsupported, node.copy(mtype = 3).sessionAvailability(RelayProtocol.AgentTerminal))
    }

    @Test fun serverFlagsAreNullableAndDistinguishDisabledSshFromDisabledSftpButton() {
        assertNull(MeshServerCapabilities().sshEnabled)
        assertEquals(SessionAvailability.Unsupported, node.sessionAvailability(RelayProtocol.SshTerminal, MeshServerCapabilities(0)))
        assertEquals(SessionAvailability.Unsupported, node.sessionAvailability(RelayProtocol.SshFiles, MeshServerCapabilities(512L or 0x800000L)))
        assertEquals(SessionAvailability.Available, node.sessionAvailability(RelayProtocol.SshTerminal, MeshServerCapabilities(512L or 0x800000L)))
    }

    @Test fun parserRetainsSshMetadataWithoutCredentials() {
        val parsed = MeshControlParser.parseNode(JSONObject("""{"_id":"node//test","host":"host.example","sshport":2222,"ssh":{"secret":"not-copied"}}"""), "mesh//test")!!
        assertEquals("host.example", parsed.host)
        assertEquals(2222, parsed.sshPort)
        assertFalse(parsed.connectivityKnown)
        assertFalse(parsed.toString().contains("not-copied"))
        val server = MeshControlParser.parse("""{"action":"serverinfo","serverinfo":{"features2":512,"consent":3,"linuxshell":"bash","domainsuffix":"team"}}""") as MeshControlEvent.ServerInfo
        assertEquals(true, server.capabilities.sshEnabled)
        assertEquals(3, server.capabilities.consent)
    }

    @Test fun partialSiteAdministrationIsNotFullDeviceAccess() {
        val event = MeshControlParser.parse("""{"action":"userinfo","userinfo":{"_id":"user//test","siteadmin":32}}""") as MeshControlEvent.UserInfo
        assertFalse(event.siteAdmin)
    }

    @Test fun nodeAndUserGroupGrantsAreCombinedAndRemovedGrantsDoNotStayCached() {
        val user = "user//test"
        val group = MeshGroup("mesh//test", "Test", rightsByUser = mapOf(user to 0, "ugrp//a" to 8))
        val resolved = MeshControlParser.resolveRights(listOf(node), listOf(group), user, false, setOf("ugrp//a")).single()
        assertEquals(8, resolved.rights)
        val removed = MeshControlParser.resolveRights(listOf(resolved), emptyList(), user, false).single()
        assertNull(removed.rights)
        val direct = MeshControlParser.resolveRights(listOf(node.copy(rightsByUser = mapOf(user to 8))), emptyList(), user, false).single()
        assertEquals(8, direct.rights)
    }
}
