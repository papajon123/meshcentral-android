package ru.papajon.meshcentralmobile.management

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class DeviceAdminTest {
    @Test fun unchangedLegacyFieldsDoNotBlockValidEditsToOtherFields() {
        val original = device.copy(name = "n".repeat(200), description = "Old\n" + "d".repeat(2000), tags = listOf("home"))
        val command = DeviceAdmin.edit(original, true, original.name, original.description, "new", true)
        assertFalse(command.has("name")); assertFalse(command.has("desc"))
        assertEquals("new", command.getJSONArray("tags").getString(0))
        val rename = DeviceAdmin.edit(original, true, "Short name", original.description, "home", true)
        assertTrue(rename.has("name")); assertFalse(rename.has("desc")); assertFalse(rename.has("tags"))
        assertFalse(DeviceAdmin.validMetadataEdit(original, original.name, "Changed\n", "home"))
        assertFalse(DeviceAdmin.validMetadataEdit(original, original.name, original.description, "home\n"))
    }
    @Test fun invitationUsesAdvertisedNamePortAndProtocolLikeTheWebClient() {
        val local = "https://192.0.2.4:4443/domain/".toHttpUrl()
        val info = MeshServerCapabilities(advertisedName = "support.example.com", advertisedPort = 8443, advertisedHttps = true, features = 0)
        val invite = DeviceAdmin.parseInvite(invitationReply(), group.id, 24, local, 0, "secret-key", info)
        val url = invite.url.toHttpUrl()
        assertEquals("support.example.com", url.host)
        assertEquals(8443, url.port)
        assertEquals("/domain/agentinvite", url.encodedPath)
        assertEquals("secret-key", url.queryParameter("key"))
        assertEquals("support.example.com:8443", invite.endpointLabel)
        assertFalse(invite.endpointLabel.contains("cookie"))
        val plain = DeviceAdmin.invitationEndpoint(local, info.copy(advertisedHttps = false, advertisedPort = 8080))
        assertEquals("http", plain.scheme)
        assertEquals(8080, plain.port)
    }
    @Test fun unqualifiedAndLanNamesUseConnectionHostButAdvertisedPort() {
        val local = "https://[2001:db8::1]:4443/domain/".toHttpUrl()
        val info = MeshServerCapabilities(advertisedName = "local", advertisedPort = 443, advertisedHttps = true)
        assertEquals("2001:db8::1", DeviceAdmin.invitationEndpoint(local, info).host)
        assertEquals(443, DeviceAdmin.invitationEndpoint(local, info).port)
        assertEquals("2001:db8::1", DeviceAdmin.invitationEndpoint(local, info.copy(advertisedName = "external.example.com", features = 2)).host)
    }
    @Test fun serverInfoParserRetainsAdvertisedEndpoint() {
        val info = (MeshControlParser.parse("""{"action":"serverinfo","serverinfo":{"name":"support.example.com","port":8443,"https":true,"features":0}}""") as MeshControlEvent.ServerInfo).capabilities
        assertEquals("support.example.com", info.advertisedName)
        assertEquals(8443, info.advertisedPort)
        assertEquals(true, info.advertisedHttps)
    }
    @Test fun renameBasedOnOriginalDraftPreservesConcurrentDescriptionAndTags() {
        val original = device.copy(description = "Before", tags = listOf("before"))
        val current = original.copy(description = "Another administrator", tags = listOf("other"))
        val command = DeviceAdmin.edit(current, true, "New", original.description, "before", true, original)
        assertTrue(command.has("name"))
        assertFalse(command.has("desc"))
        assertFalse(command.has("tags"))
        assertTrue(DeviceAdmin.editMatches(command, current.copy(name = "New")))
        assertEquals(2, DeviceAdmin.edit(current, true, original.name, original.description, "before", true, original).length())
    }
    @Test fun untouchedLegacyNameIsNotTrimmedOrOverwrittenByDescriptionEdit() {
        val original = device.copy(name = " Legacy name ")
        val current = original.copy(name = "Concurrent rename")
        val command = DeviceAdmin.edit(current, true, original.name, "New description", "", true, original)
        assertFalse(command.has("name"))
        assertEquals("New description", command.getString("desc"))
    }
    @Test fun movingUsesGroupTypesEvenWhenNodeTypeIsWrongOrAbsent() {
        assertTrue(DeviceAdmin.canMove(device.copy(mtype = 0), source, group, true))
        assertTrue(DeviceAdmin.canMove(device.copy(mtype = 1), source, group, true))
        assertFalse(DeviceAdmin.canMove(device, source.copy(mtype = 1), group, true))
        assertFalse(DeviceAdmin.canMove(device, null, group, true))
        assertFalse(DeviceAdmin.canMove(device, source.copy(id = "mesh//wrong"), group, true))
    }
    @Test fun reviewRejectsControlAndDirectionalCharacters() {
        for (bad in listOf("\n", "\r", "\t", "\u0000", "\u007f", "\u0085", "\u202a", "\u202e", "\u2066", "\u2069")) {
            assertFalse("Unsafe name", DeviceAdmin.validMetadata("PC${bad}", "", ""))
            assertFalse("Unsafe tag", DeviceAdmin.validMetadata("PC", "", "home${bad}"))
        }
    }
    @Test fun reviewUsesWebMetadataLimits() {
        assertFalse(DeviceAdmin.validMetadata("n".repeat(65), "", ""))
        assertFalse(DeviceAdmin.validMetadata("PC", "d".repeat(65), ""))
        assertTrue(DeviceAdmin.validMetadata("n".repeat(64), "d".repeat(64), "t".repeat(63)))
    }
    @Test fun reviewDoesNotSendUnchangedFields() {
        val original = MeshDevice("node//a", "mesh//a", "Original", rights = 4, description = "Other admin description", tags = listOf("home"))
        val command = DeviceAdmin.edit(original, true, "Renamed", original.description, "home", true)
        assertFalse(command.has("desc"))
        assertFalse(command.has("tags"))
    }
    private val device = MeshDevice("node//test", "mesh//source", "Before", mtype = 2, rights = 5)
    private val group = MeshGroup("mesh//target", "Target", mtype = 2, rights = 5)
    private val source = group.copy(id = device.meshId)
    private val state = MeshClientState(socketConnected = true, devicesCurrent = true, agentInstallAllowed = true,
        serverCapabilities = MeshServerCapabilities(features = 0))

    @Test fun metadataUsesAuditedCommandAndNormalizesTags() {
        val command = DeviceAdmin.edit(device, true, " New ", "Description", "one, two, one, ", true)
        assertEquals("changedevice", command.getString("action"))
        assertEquals(device.nodeId, command.getString("nodeid"))
        assertEquals("New", command.getString("name"))
        assertEquals("Description", command.getString("desc"))
        assertEquals(listOf("one", "two"), (0..1).map { command.getJSONArray("tags").getString(it) })
    }
    @Test fun emptyTagsAreAnExplicitArrayForClearing() {
        assertEquals(0, DeviceAdmin.edit(device.copy(tags = listOf("old")), true, "New", "", "", true).getJSONArray("tags").length())
    }
    @Test fun mutationRequiresConfirmationCurrentStateAndRights() {
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.edit(device, true, "X", "", "", false) }
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.edit(device, false, "X", "", "", true) }
        listOf(null, 0, 8, 128).forEach { assertFalse(DeviceAdmin.canEdit(device.copy(rights = it), true)) }
        assertTrue(DeviceAdmin.canEdit(device.copy(rights = -1), true))
    }
    @Test fun metadataLimitsMatchWhatTheEditorCanSend() {
        assertFalse(DeviceAdmin.validMetadata(" ", "", ""))
        assertFalse(DeviceAdmin.validMetadata("a".repeat(257), "", ""))
        assertFalse(DeviceAdmin.validMetadata("a", "x".repeat(4097), ""))
        assertFalse(DeviceAdmin.validMetadata("a", "", "t".repeat(64)))
        assertTrue(DeviceAdmin.validMetadata("a", "", "t".repeat(63)))
    }
    @Test fun moveRequiresEditOnSourceAndEditPlusManageOnMatchingDestination() {
        assertTrue(DeviceAdmin.canMove(device, source, group, true))
        assertFalse(DeviceAdmin.canMove(device.copy(rights = 4), source, group, true))
        assertFalse(DeviceAdmin.canMove(device, source, group.copy(rights = 1), true))
        assertFalse(DeviceAdmin.canMove(device, source, group.copy(rights = 4), true))
        assertFalse(DeviceAdmin.canMove(device, source, group.copy(mtype = 1), true))
        assertFalse(DeviceAdmin.canMove(device, source, group.copy(id = device.meshId), true))
        assertFalse(DeviceAdmin.canMove(device, source, group, false))
        val request = DeviceAdmin.move(device, source, group, true, true)
        assertEquals("changeDeviceMesh", request.getString("action"))
        assertEquals(group.id, request.getString("meshid"))
        assertEquals(device.nodeId, request.getJSONArray("nodeids").getString(0))
    }
    @Test fun notePermissionIsSeparateFromManagingDevices() {
        assertFalse(DeviceAdmin.canNote(device, true))
        val notesDevice = device.copy(rights = MeshRights.SET_NOTES)
        assertTrue(DeviceAdmin.canNote(notesDevice, true))
        assertFalse(DeviceAdmin.canEdit(notesDevice, true))
        val request = DeviceAdmin.notes(notesDevice, true, "", true)
        assertEquals("setNotes", request.getString("action"))
        assertEquals(device.nodeId, request.getString("id"))
        assertEquals("", request.getString("notes"))
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.notes(notesDevice, true, "x".repeat(16001), true) }
    }
    @Test fun noteReplyMustReferToTheRequestedNode() {
        val reply = JSONObject().put("action", "getNotes").put("id", device.nodeId).put("notes", JSONObject.NULL)
        assertEquals("", DeviceAdmin.parseNotes(reply, device.nodeId))
        assertEquals("text", DeviceAdmin.parseNotes(reply.put("notes", "text"), device.nodeId))
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.parseNotes(reply, "node//other") }
        reply.remove("notes")
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.parseNotes(reply, device.nodeId) }
    }
    @Test fun inviteRequiresAgentGroupManagerCurrentStateAndInstallationPermission() {
        assertTrue(DeviceAdmin.canInvite(group, state))
        assertFalse(DeviceAdmin.canInvite(group.copy(mtype = 1), state))
        assertFalse(DeviceAdmin.canInvite(group.copy(rights = 8), state))
        assertFalse(DeviceAdmin.canInvite(group, state.copy(agentInstallAllowed = false)))
        assertFalse(DeviceAdmin.canInvite(group, state.copy(devicesCurrent = false)))
        assertFalse(DeviceAdmin.canInvite(group, state.copy(serverCapabilities = MeshServerCapabilities(features = 2))))
        assertFalse(DeviceAdmin.canInvite(group, state.copy(serverCapabilities = MeshServerCapabilities())))
    }
    @Test fun inviteUsesHoursAndDefaultWebFlags() {
        val request = DeviceAdmin.invite(group, state, 24)
        assertEquals("createInviteLink", request.getString("action"))
        assertEquals(24, request.getInt("expire"))
        assertEquals(0, request.getInt("flags"))
        assertEquals(0, request.getInt("agents"))
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.invite(group, state, 0) }
    }
    private fun invitationReply() = JSONObject().put("action", "createInviteLink").put("meshid", group.id)
        .put("expire", 24).put("cookie", "synthetic@cookie$").put("url", "https://untrusted.invalid/agentinvite?c=wrong")
    @Test fun invitationUsesAuthenticatedDomainAndEscapesCookieAndAccessKey() {
        val invitation = DeviceAdmin.parseInvite(invitationReply(), group.id, 24,
            "https://example.com:4443/domain/?old=1".toHttpUrl(), 100, "synthetic & key")
        val url = invitation.url.toHttpUrl()
        assertEquals("example.com", url.host)
        assertEquals("/domain/agentinvite", url.encodedPath)
        assertEquals("synthetic@cookie$", url.queryParameter("c"))
        assertEquals("synthetic & key", url.queryParameter("key"))
        assertNull(url.queryParameter("old"))
        assertEquals(86_400_100L, invitation.expiresAtMillis)
        assertFalse(invitation.active(invitation.expiresAtMillis))
        assertFalse(invitation.toString().contains("cookie"))
    }
    @Test fun invitationRejectsErrorsMismatchAndMalformedCookies() {
        val server = "https://example.com/".toHttpUrl()
        for (reply in listOf(invitationReply().put("result", "Permission denied"), invitationReply().put("meshid", "mesh//other"),
            invitationReply().put("expire", 0), invitationReply().put("cookie", "bad\nvalue"), invitationReply().put("cookie", ""))) {
            assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.parseInvite(reply, group.id, 24, server, 0) }
        }
    }
    @Test fun onlyExplicitOkAcknowledgesChange() {
        DeviceAdmin.requireSuccess(JSONObject().put("result", "ok"))
        listOf("Access Denied", "Device groups are of different types", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.requireSuccess(JSONObject().put("result", it)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DeviceAdmin.requireSuccess(JSONObject()) }
    }
    @Test fun parserKeepsMetadataAndMoveEventWithoutMeshid() {
        val event = MeshControlParser.parse("""{"action":"event","event":{"action":"nodemeshchange","newMeshId":"mesh//target","node":{"_id":"node//test","name":"New","meshid":"mesh//target","mtype":2,"desc":"Text","tags":["one"]}}}""") as MeshControlEvent.NodeUpdated
        assertEquals("mesh//target", (event.event as MeshNodeEvent.MeshChanged).meshId)
        val changed = MeshControlParser.parse("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//test","name":"New","meshid":"mesh//target","mtype":2,"desc":"Text","tags":["one"]}}}""") as MeshControlEvent.NodeUpdated
        val updated = (changed.event as MeshNodeEvent.NodeChanged).device
        assertEquals("mesh//target", updated.meshId)
        assertEquals("Text", updated.description)
        assertEquals(listOf("one"), updated.tags)
    }
    @Test fun parserRecognizesGroupTypeAndSiteInstallationRestriction() {
        val groups = MeshControlParser.parse("""{"action":"meshes","meshes":[{"_id":"mesh//a","name":"A","mtype":2}]}""") as MeshControlEvent.Meshes
        assertEquals(2, groups.groups.single().mtype)
        fun user(flags: Long) = MeshControlParser.parse("""{"action":"userinfo","userinfo":{"_id":"user//a","siteadmin":$flags}}""") as MeshControlEvent.UserInfo
        assertFalse(user(4096).agentInstallAllowed)
        assertTrue(user(0).agentInstallAllowed)
        assertTrue(user(4294967295).agentInstallAllowed)
    }
    @Test fun groupGrantDoesNotBorrowDeviceSpecificPermissions() {
        val groups = listOf(group.copy(rightsByUser = mapOf("ugrp//team" to 5)))
        val resolved = MeshControlParser.resolveRights(listOf(MeshDevice("", group.id, "")), groups, "user//a", false, setOf("ugrp//team"))
        assertEquals(5, resolved.single().rights)
    }
}
