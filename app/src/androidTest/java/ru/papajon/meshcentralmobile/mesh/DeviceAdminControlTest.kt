package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.management.DeviceAdmin
import ru.papajon.meshcentralmobile.security.SecureVault

class DeviceAdminControlTest {
    @Test fun burstOfMoveEventsRunsOneSnapshotAndOneTrailingSnapshot() = runBlocking {
        withFixture { client, fixture ->
            await { client.clientState.value.devicesCurrent && fixture.actions.count { it == "nodes" } >= 1 }
            delay(50)
            val beforeNodes = fixture.actions.count { it == "nodes" }
            val beforeMeshes = fixture.actions.count { it == "meshes" }
            val beforeInfo = fixture.actions.count { it == "serverinfo" }
            val beforeCookies = fixture.actions.count { it == "authcookie" }
            fixture.holdSnapshots = true
            repeat(300) { fixture.pushMove() }
            await { fixture.heldNodes.isNotEmpty() }
            repeat(300) { fixture.pushMove("mesh//source") }
            delay(100)
            assertEquals(beforeNodes + 1, fixture.actions.count { it == "nodes" })
            fixture.holdSnapshots = false
            fixture.releaseNodes()
            await { client.eventSnapshotRefreshIdle }
            assertEquals(beforeNodes + 2, fixture.actions.count { it == "nodes" })
            assertEquals(beforeMeshes + 2, fixture.actions.count { it == "meshes" })
            assertEquals(beforeInfo, fixture.actions.count { it == "serverinfo" })
            assertEquals(beforeCookies, fixture.actions.count { it == "authcookie" })
            assertEquals("mesh//source", client.clientState.value.devices.single().meshId)
        }
    }
    @Test fun renameDoesNotOverwriteFieldsChangedSinceEditorOpened() = runBlocking {
        withFixture { client, fixture ->
            val original = client.clientState.value.devices.single()
            fixture.pushMetadata("Other administrator", listOf("concurrent"))
            await { client.clientState.value.devices.single().description == "Other administrator" }
            client.editDevice(original.nodeId, "Renamed", original.description, original.tags.joinToString(","), original)
            val updated = client.clientState.value.devices.single()
            assertEquals("Other administrator", updated.description)
            assertEquals(listOf("concurrent"), updated.tags)
            assertEquals(setOf("action", "nodeid", "name", "tag", "responseid"), fixture.lastEdit!!.keys().asSequence().toSet())
        }
    }
    @Test fun metadataVerificationDoesNotReplaceUnrelatedDeviceState() = runBlocking {
        withFixture { client, fixture ->
            fixture.extraVerificationDevice = true
            client.editDevice("node//test", "Changed", "", "")
            assertEquals("node//test", fixture.lastVerification!!.getString("id"))
            assertEquals(0, fixture.lastVerification!!.getInt("skip"))
            assertEquals("", fixture.lastVerification!!.getString("filter"))
            assertEquals(listOf("node//test"), client.clientState.value.devices.map { it.nodeId })
            assertEquals("Changed", client.clientState.value.devices.single().name)
        }
    }
    @Test fun realControlClientCreatesInvitationEditsNotesAndMovesByServerEvents() = runBlocking {
        withFixture { client, fixture ->
            val state = client.clientState.value
            assertTrue(DeviceAdmin.canInvite(state.groups.first(), state))
            val invite = client.createDeviceInvitation("mesh//source", 8)
            assertTrue(invite.url.contains("/agentinvite?c="))
            assertTrue(invite.active())
            client.editDevice("node//test", "New", "Description", "a, b")
            await { client.clientState.value.devices.single().name == "New" }
            assertEquals("Description", client.clientState.value.devices.single().description)
            assertEquals(listOf("a", "b"), client.clientState.value.devices.single().tags)
            assertEquals("Before", client.readDeviceNotes("node//test"))
            client.saveDeviceNotes("node//test", "After")
            assertEquals("After", fixture.notes)
            client.moveDevice("node//test", "mesh//target")
            await { client.clientState.value.devices.single().meshName == "Target" }
            assertEquals("mesh//target", client.clientState.value.devices.single().meshId)
            client.moveDevice("node//test", "mesh//source")
            client.editDevice("node//test", "Original", "", "")
            client.saveDeviceNotes("node//test", "Before")
            assertEquals("Before", client.readDeviceNotes("node//test"))
        }
    }
    @Test fun serverRejectionNeverChangesTheCachedDevice() = runBlocking {
        withFixture { client, fixture ->
            fixture.rejectChanges = true
            try { client.editDevice("node//test", "Wrong", "", ""); fail("Denied change accepted") }
            catch (error: ru.papajon.meshcentralmobile.mesh.control.ControlRequestException) { assertEquals("server_rejected", error.code) }
            assertEquals("Original", client.clientState.value.devices.single().name)
        }
    }
    @Test fun pushedMoveWithoutResponseUpdatesGroupAndPermissions() = runBlocking {
        withFixture { client, fixture ->
            fixture.pushMove()
            await { client.clientState.value.devices.single().meshId == "mesh//target" }
            assertEquals("Target", client.clientState.value.devices.single().meshName)
            assertEquals(133, client.clientState.value.devices.single().rights)
        }
    }
    @Test fun moveOutOfVisibleGroupsRemovesTheDeviceOnFreshSnapshot() = runBlocking {
        withFixture { client, fixture ->
            fixture.visible = false
            fixture.pushMove()
            await { client.clientState.value.devices.isEmpty() }
        }
    }
    private suspend fun withFixture(block: suspend (MeshCentralClient, Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("device-admin-test-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val fixture = Fixture()
        try {
            client.login(fixture.server.url("/").toString(), "synthetic", "synthetic-password")
            await { client.clientState.value.devicesCurrent && client.clientState.value.groups.size == 2 }
            block(client, fixture)
        } finally { client.close(); scope.cancel(); vault.clearCredentials(); fixture.server.shutdown() }
    }
    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10000) { while (!condition()) delay(25) }
    }
    private class Fixture {
        val server = MockWebServer()
        val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var holdSnapshots = false
        val heldNodes = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        @Volatile var lastEdit: JSONObject? = null
        @Volatile var lastVerification: JSONObject? = null
        var notes = "Before"
        var rejectChanges = false
        var visible = true
        var extraVerificationDevice = false
        private lateinit var socket: WebSocket
        private val node = JSONObject().put("_id", "node//test").put("meshid", "mesh//source").put("name", "Original")
            .put("mtype", 2).put("conn", 0).put("agent", JSONObject().put("id", 6))
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        send(JSONObject("""{"action":"serverinfo","serverinfo":{"name":"local","port":${server.port},"https":false,"features":0}}"""))
                        send(JSONObject("""{"action":"userinfo","userinfo":{"_id":"user//test","siteadmin":0}}"""))
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Acknowledge the client's close so MockWebServer can finish its reader
                        // queue before shutdown, rather than waiting for the WebSocket timeout.
                        webSocket.close(1000, null)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        val action = request.getString("action")
                        actions.add(action)
                        fun reply(result: JSONObject) { if (request.has("responseid")) result.put("responseid", request.get("responseid")); send(result) }
                        when (action) {
                            "meshes" -> reply(JSONObject("""{"action":"meshes","meshes":[{"_id":"mesh//source","name":"Source","mtype":2,"links":{"user//test":{"rights":133}}},{"_id":"mesh//target","name":"Target","mtype":2,"links":{"user//test":{"rights":133}}}]}"""))
                            "nodes" -> {
                                if (ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker.isIsolatedSnapshot(request)) lastVerification = request
                                val respond = { reply(JSONObject().put("action", "nodes").put("nodes", JSONObject().put(node.getString("meshid"), JSONArray().apply {
                                if (visible) put(node)
                                if (extraVerificationDevice && ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker.isIsolatedSnapshot(request)) {
                                    put(JSONObject().put("_id", "node//unrelated").put("name", "Unrelated snapshot").put("meshid", "mesh//source"))
                                }
                                }))) }
                                if (holdSnapshots && !ru.papajon.meshcentralmobile.mesh.control.ControlRequestBroker.isIsolatedSnapshot(request)) heldNodes.add(respond) else respond()
                            }
                            "createInviteLink" -> reply(JSONObject().put("action", action).put("meshid", request.getString("meshid")).put("expire", request.getInt("expire")).put("cookie", "synthetic@cookie$").put("url", "/agentinvite?c=synthetic"))
                            "changedevice", "changeDeviceMesh" -> {
                                if (rejectChanges) { reply(JSONObject().put("action", action).put("result", "Access Denied")); return }
                                if (action == "changedevice") {
                                    lastEdit = request
                                    listOf("name", "desc", "tags").filter(request::has).forEach { node.put(it, request.get(it)) }
                                    send(JSONObject().put("action", "event").put("event", JSONObject().put("action", "changenode").put("node", node)))
                                } else { node.put("meshid", request.getString("meshid")); pushMove(node.getString("meshid")) }
                                reply(JSONObject().put("action", action).put("result", "ok"))
                            }
                            "getNotes" -> send(JSONObject().put("action", action).put("id", request.getString("id")).put("notes", notes))
                            "setNotes" -> notes = request.getString("notes")
                        }
                    }
                })
            }
            server.start()
        }
        fun pushMove(target: String = "mesh//target") {
            node.put("meshid", target)
            send(JSONObject().put("action", "event").put("event", JSONObject().put("action", "nodemeshchange").put("node", node).put("newMeshId", target)))
        }
        fun releaseNodes() { while (true) (heldNodes.poll() ?: return).invoke() }
        fun pushMetadata(description: String, tags: List<String>) {
            node.put("desc", description).put("tags", JSONArray(tags))
            send(JSONObject().put("action", "event").put("event", JSONObject().put("action", "changenode").put("node", node)))
        }
        private fun send(message: JSONObject) { socket.send(message.toString()) }
    }
}
