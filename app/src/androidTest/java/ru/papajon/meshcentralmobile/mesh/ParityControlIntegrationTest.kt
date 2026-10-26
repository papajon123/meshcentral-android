package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.management.*
import ru.papajon.meshcentralmobile.security.SecureVault
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exact 1.3.1 shapes; reserved/synthetic identities only. Never touches real agents. */
class ParityControlIntegrationTest {
    @Test fun permissionsAndOfflineNodesReachTheController() = fixture { f ->
        f.send("""{"action":"userinfo","userinfo":{"_id":"user//fixture","siteadmin":4294967295,"removeRights":425984}}""")
        await { f.client.clientState.value.devices.single().rights == 1589487 }
        val denied = f.client.clientState.value.devices.single()
        assertFalse(RunCommand.allowed(denied, true))
        assertTrue(DeviceRemovalCommand.available(denied, true).isEmpty())
        f.send("""{"action":"nodes","nodes":{"mesh//example":[{"_id":"node//example","name":"Example","agent":{"id":6},"osdesc":"Linux"}]}}""")
        await { f.client.clientState.value.devices.single().conn == 0 }
        val offline = f.client.clientState.value.devices.single()
        assertTrue(offline.connectivityKnown)
        assertEquals(setOf(DevicePowerAction.Wake), DevicePowerCommand.available(offline, true))
        assertEquals(RemovalObservation.Disconnected, DeviceRemovalCommand.observe(DeviceRemovalAction.UninstallAgent, true, offline))
    }

    @Test fun lateToastDenialCpuRoutingErrorAndLargeCommandOutputRemainScoped() = fixture { f ->
        val controller = DeviceInspectorController(f.client, "node//example", f.scope)
        try {
            controller.sendToast("Title", "Message")
            val toast = f.command("toast")
            f.reply("toast", toast, "ok")
            await { controller.state.value.notice != null && !controller.state.value.loading }
            f.reply("toast", toast, "Access Denied")
            await { controller.state.value.error != null }
            assertNull(controller.state.value.notice)
            assertTrue(controller.state.value.error.toString().contains("Access Denied"))

            controller.loadMetrics()
            val cpu = f.command("msg")
            f.reply("msg", cpu, "Unable to route") // No nodeid and no type, as in meshuser.js:1100.
            await { !controller.state.value.loading && controller.state.value.metricsError }
            assertTrue(controller.state.value.error.toString().contains("Unable to route"))

            controller.loadHistory()
            val events = f.command("events")
            val timeline = f.command("powertimeline")
            assertFalse(controller.state.value.loading) // History must not block the other tabs.
            f.send(JSONObject().put("action", "events").put("nodeid", "node//example").put("tag", events.getString("tag"))
                .put("events", org.json.JSONArray()).toString())
            f.send(JSONObject().put("action", "powertimeline").put("nodeid", "node//example").put("tag", timeline.getString("tag"))
                .put("timeline", org.json.JSONArray()).toString())
            await { !controller.state.value.historyLoading && controller.state.value.powerTimeline != null }
            assertNull(controller.state.value.error) // A prior CPU refusal is not a history failure.

            controller.runCommand("echo PARITY_OK")
            val command = f.command("runcommands")
            val before = f.client.clientState.value.message
            f.send(JSONObject().put("action", "msg").put("type", "runcommands").put("result", "x".repeat(1024 * 1024))
                .put("responseid", command.getString("responseid")).put("nodeid", "node//example").toString())
            await { controller.state.value.commandOutput != null }
            assertEquals(65536, controller.state.value.commandOutput!!.length)
            assertTrue(controller.state.value.commandOutputTruncated)
            assertEquals(before, f.client.clientState.value.message)
            assertNull(controller.state.value.error)
        } finally { controller.close() }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("parity-fixes-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        val commands = LinkedBlockingQueue<JSONObject>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.offer(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { commands.offer(JSONObject(text)) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            server.start()
            client.login(server.url("/").toString(), "fixture-user", "fixture-password")
            val f = Fixture(client, scope, requireNotNull(sockets.poll(5, TimeUnit.SECONDS)), commands)
            f.send("""{"action":"userinfo","userinfo":{"_id":"user//fixture"}}""")
            f.send("""{"action":"meshes","meshes":[{"_id":"mesh//example","name":"Example","links":{"user//fixture":{"rights":4294967295}}}]}""")
            f.send("""{"action":"nodes","nodes":{"mesh//example":[{"_id":"node//example","name":"Example","agent":{"id":6},"osdesc":"Linux","conn":1}]}}""")
            await { client.clientState.value.devicesCurrent && client.clientState.value.devices.singleOrNull()?.rights == MeshRights.FULL }
            block(f)
        } finally { client.close(); scope.cancel(); vault.clearCredentials(); server.shutdown() }
    }
    private class Fixture(val client: MeshCentralClient, val scope: CoroutineScope, val socket: WebSocket,
        val commands: LinkedBlockingQueue<JSONObject>) {
        fun send(text: String) { assertTrue(socket.send(text)) }
        fun reply(action: String, command: JSONObject, result: String) = send(JSONObject().put("action", action)
            .put("responseid", command.getString("responseid")).put("result", result).toString())
        fun command(action: String): JSONObject {
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                val candidate = commands.poll(100, TimeUnit.MILLISECONDS)
                if (candidate?.optString("action") == action) return candidate
            }
            error("Synthetic command did not arrive")
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("Synthetic control state timed out", condition())
    }
}
