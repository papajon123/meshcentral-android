package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText

/**
 * Control channel parsing tests.
 *
 * Payload shapes are copied from MeshCentral 1.2.5 `meshuser.js` responses for `meshes`, `nodes`,
 * `authcookie`, node events, clipboard replies and authentication `close` frames.
 */
class MeshControlParserTest {
    @org.junit.Test fun groupNamesAreResolvedForEveryFreshNodesSnapshot() {
        val device = MeshDevice("node//one", "mesh//team", "Workstation")
        val group = MeshGroup(id = "mesh//team", name = "Team")
        val named = MeshControlParser.resolveRights(listOf(device), listOf(group), null, false).single()
        org.junit.Assert.assertEquals("Team", named.meshName)
        val refreshed = MeshControlParser.resolveRights(listOf(device), listOf(group.copy(name = "Office")), null, false).single()
        org.junit.Assert.assertEquals("Office", refreshed.meshName)
    }
    @Test
    fun parsesServerInfo() {
        val event = MeshControlParser.parse(
            """{"action":"serverinfo","serverinfo":{"name":"MeshCentral","domain":"example"}}""",
        ) as MeshControlEvent.ServerInfo

        assertEquals("MeshCentral", event.name)
        assertEquals("example", event.domain)
    }

    @Test
    fun parsesUserInfoWithUnsignedSiteAdminFlag() {
        val event = MeshControlParser.parse(
            """{"action":"userinfo","userinfo":{"_id":"user//demo-user","name":"demo-user","siteadmin":4294967295}}""",
        ) as MeshControlEvent.UserInfo

        assertEquals("user//demo-user", event.userId)
        assertEquals("demo-user", event.name)
        assertTrue(event.siteAdmin)
    }

    @Test
    fun parsesMeshesWithLinksRights() {
        val event = MeshControlParser.parse(
            """{"action":"meshes","meshes":[{"_id":"mesh//abc","name":"demo-pc","desc":"group",
               "links":{"user//demo-user":{"rights":4294967295}}}]}""",
        ) as MeshControlEvent.Meshes

        assertEquals(1, event.groups.size)
        assertEquals("mesh//abc", event.groups[0].id)
        assertEquals("demo-pc", event.groups[0].name)
        assertEquals(MeshRights.FULL, event.groups[0].rightsFor("user//demo-user"))
    }

    @Test
    fun parsesNodesGroupedByMeshId() {
        val event = MeshControlParser.parse(
            """{"action":"nodes","nodes":{"mesh//abc":[
               {"_id":"node//n1","name":"demo-pc","conn":1,"pwr":1,"osdesc":"Windows 11 Pro",
                "ip":"192.0.2.10","mtype":3,"agent":{"id":7,"ver":"1.2.3","caps":1023}},
               {"_id":"node//n2","name":"offline-pc","conn":0,"pwr":0}]}}""",
        ) as MeshControlEvent.Nodes

        assertEquals(2, event.devices.size)
        val online = event.devices.first { it.nodeId == "node//n1" }
        assertEquals("mesh//abc", online.meshId)
        assertEquals("Windows 11 Pro", online.osDescription)
        assertEquals("192.0.2.10", online.ipAddress)
        assertEquals(7, online.agentId)
        assertEquals("1.2.3", online.agentVersion)
        assertTrue(online.online)

        val offline = event.devices.first { it.nodeId == "node//n2" }
        assertFalse(offline.online)
        assertFalse(offline.canOpenNativeDesktop)
        assertEquals(DesktopCapability.UnsupportedOffline, offline.desktopCapability)
    }

    @Test
    fun viewOnlyRightsOpenDesktopButBlockInput() {
        val groups = listOf(
            MeshGroup(id = "mesh//abc", name = "demo-pc", rightsByUser = mapOf("user//demo-user" to MeshRights.REMOTE_VIEW)),
        )
        val device = MeshDevice(nodeId = "node//n1", meshId = "mesh//abc", name = "demo-pc", conn = 1, agentId = 1)
        val resolved = MeshControlParser.resolveRights(listOf(device), groups, "user//demo-user", siteAdmin = false).single()

        assertTrue(resolved.viewOnly)
        assertTrue(resolved.hasDesktopPermission)
        assertTrue(resolved.canOpenNativeDesktop)
        assertFalse(resolved.inputAllowed)
        assertEquals(UiText.Resource(R.string.runtime_status_online_view_only), resolved.statusLabel)
    }

    @Test
    fun meshWithoutRemoteRightsCannotOpenDesktop() {
        val groups = listOf(
            MeshGroup(id = "mesh//abc", name = "demo-pc", rightsByUser = mapOf("user//demo-user" to 0)),
        )
        val device = MeshDevice(nodeId = "node//n1", meshId = "mesh//abc", name = "demo-pc", conn = 1, agentId = 1)
        val resolved = MeshControlParser.resolveRights(listOf(device), groups, "user//demo-user", siteAdmin = false).single()

        assertFalse(resolved.hasDesktopPermission)
        assertEquals(DesktopCapability.UnsupportedNoPermission, resolved.desktopCapability)
    }

    @Test
    fun siteAdminGetsFullRights() {
        val device = MeshDevice(nodeId = "node//n1", meshId = "mesh//abc", name = "demo-pc", conn = 1, agentId = 1)
        val resolved = MeshControlParser.resolveRights(listOf(device), emptyList(), "user//demo-user", siteAdmin = true).single()

        assertEquals(MeshRights.FULL, resolved.rights)
        assertTrue(resolved.inputAllowed)
    }

    @Test
    fun parsesNodeConnectEvent() {
        val event = MeshControlParser.parse(
            """{"action":"event","event":{"action":"nodeconnect","nodeid":"node//n1","conn":0,"pwr":0}}""",
        ) as MeshControlEvent.NodeUpdated

        val connected = event.event as MeshNodeEvent.NodeConnected
        assertEquals("node//n1", connected.nodeId)
        assertEquals(0, connected.conn)
    }

    @Test
    fun parsesChangeNodeEvent() {
        val event = MeshControlParser.parse(
            """{"action":"event","event":{"action":"changenode","node":{"_id":"node//n1","name":"demo-pc","conn":1}}}""",
        ) as MeshControlEvent.NodeUpdated

        val changed = event.event as MeshNodeEvent.NodeChanged
        assertEquals("node//n1", changed.device.nodeId)
        assertTrue(changed.device.online)
    }

    @Test
    fun parsesRemoveNodeEvent() {
        val event = MeshControlParser.parse(
            """{"action":"event","event":{"action":"removenode","nodeid":"node//n1"}}""",
        ) as MeshControlEvent.NodeUpdated

        assertEquals("node//n1", (event.event as MeshNodeEvent.NodeRemoved).nodeId)
    }

    @Test
    fun parsesAuthCookie() {
        val event = MeshControlParser.parse(
            """{"action":"authcookie","cookie":"COOKIE","rcookie":"RELAY"}""",
        ) as MeshControlEvent.AuthCookie

        assertEquals("COOKIE", event.cookie)
        assertEquals("RELAY", event.relayCookie)
    }

    @Test
    fun parsesTwoFactorChallengeFromClose() {
        val event = MeshControlParser.parse(
            """{"action":"close","cause":"noauth","msg":"tokenrequired","email2fa":true,"sms2fa":false,
               "twoFactorCookieDays":30}""",
        ) as MeshControlEvent.Close

        assertEquals("noauth", event.cause)
        assertEquals("tokenrequired", event.message)
        val challenge = event.twoFactorChallenge ?: error("challenge expected")
        assertTrue(challenge.email)
        assertFalse(challenge.sms)
        assertTrue(challenge.requestable)
        assertEquals(
            UiText.Resource(R.string.runtime_two_factor_required_with_channels, listOf("email")),
            challenge.label,
        )
    }

    @Test
    fun badCredentialsCloseHasNoChallenge() {
        val event = MeshControlParser.parse(
            """{"action":"close","cause":"noauth","msg":"noauth-2a"}""",
        ) as MeshControlEvent.Close

        assertNull(event.twoFactorChallenge)
        assertEquals(MeshAuthFailure.BadCredentials, MeshAuthDiagnostics.classify(event.cause, event.message))
    }

    @Test
    fun parsesClipboardResponses() {
        val fetched = MeshControlParser.parse(
            """{"action":"msg","type":"getclip","sessionid":"s1","data":"hello","tag":"t1","success":true}""",
        ) as MeshControlEvent.ClipMessage
        assertEquals("getclip", fetched.type)
        assertEquals("hello", fetched.data)
        assertEquals("t1", fetched.tag)

        val stored = MeshControlParser.parse(
            """{"action":"msg","type":"setclip","sessionid":"s1","success":true}""",
        ) as MeshControlEvent.ClipMessage
        assertEquals("setclip", stored.type)
        assertEquals(true, stored.success)
        assertNull(stored.data)
    }

    @Test
    fun parsesRoutingFailure() {
        val event = MeshControlParser.parse(
            """{"action":"msg","result":"Unable to route","responseid":"android-tunnel","tag":"t"}""",
        ) as MeshControlEvent.RoutingResult

        assertEquals("Unable to route", event.result)
        assertEquals("android-tunnel", event.responseId)
    }

    @Test
    fun unknownActionAndBrokenJsonAreTolerated() {
        val unknown = MeshControlParser.parse("""{"action":"meshctrl-whatever","x":1}""") as MeshControlEvent.Unknown
        assertEquals("meshctrl-whatever", unknown.action)

        val broken = MeshControlParser.parse("not json at all") as MeshControlEvent.Unknown
        assertNull(broken.action)
    }
}
