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
import ru.papajon.meshcentralmobile.security.SecureVault
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** In-process synthetic control server only; no MeshCentral deployment or real credentials. */
class AndroidPhoneControlTest {
    @Test fun liveBatteryAndPermissionsAreRecheckedWithoutReplayingCommands() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("phone-test-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        val commands = LinkedBlockingQueue<JSONObject>()
        var socket: WebSocket? = null
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val command = JSONObject(text)
                    if (command.optString("action") in setOf("msg", "poweraction")) commands.offer(command)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            server.start()
            client.login(server.url("/").toString(), "fixture-user", "fixture-password")
            await { socket != null }
            fun send(value: String) { assertTrue(socket!!.send(value)) }
            send("""{"action":"userinfo","userinfo":{"_id":"user//fixture"}}""")
            fun rights(value: Int) = send("""{"action":"meshes","meshes":[{"_id":"mesh//test","name":"Test","links":{"user//fixture":{"rights":$value}}}]}""")
            rights(8)
            send("""{"action":"nodes","nodes":{"mesh//test":[{"_id":"node//phone","name":"Phone","agent":{"id":14,"caps":13},"conn":1,"sessions":{"battery":{"level":85,"state":"ac"}}}]}}""")
            await { client.clientState.value.devicesCurrent && client.clientState.value.devices.singleOrNull()?.rights == 8 }
            fun device() = client.clientState.value.devices.single()
            assertEquals(85.0, device().battery?.level)
            send("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//phone","name":"Renamed phone"}}}""")
            await { device().name == "Renamed phone" }
            assertEquals(85.0, device().battery?.level)
            send("""{"action":"event","event":{"action":"devicesessions","nodeid":"node//phone","sessions":{"battery":{"level":70,"state":"dc"}}}}""")
            await { device().battery?.level == 70.0 }
            assertTrue(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Vibrate)))
            assertEquals(401, commands.poll(3, TimeUnit.SECONDS)!!.getInt("actiontype"))
            assertFalse(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Toast, "Hello")))
            assertFalse(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.OpenUrl, "https://example.com")))
            assertFalse(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Dial, "*#06#")))
            assertNull(commands.poll(150, TimeUnit.MILLISECONDS))
            rights(24)
            await { device().rights == 24 }
            assertTrue(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.OpenUrl, "https://example.com")))
            assertEquals("openbrowser \"https://example.com\"", commands.poll(3, TimeUnit.SECONDS)!!.getString("value"))
            assertTrue(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Dial, "*#06#")))
            assertEquals("dial *%2306%23", commands.poll(3, TimeUnit.SECONDS)!!.getString("value"))
            rights(256)
            await { device().rights == 256 }
            assertFalse(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Flash)))
            assertNull(commands.poll(150, TimeUnit.MILLISECONDS))
            rights(8)
            send("""{"action":"event","event":{"action":"nodeconnect","nodeid":"node//phone","conn":0,"pwr":0}}""")
            await { device().conn == 0 && device().rights == 8 }
            assertNull(device().battery)
            assertFalse(client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Dial, "123")))
            send("""{"action":"event","event":{"action":"nodeconnect","nodeid":"node//phone","conn":1,"pwr":1}}""")
            await { device().conn == 1 }
            assertNull(device().battery)
            assertNull(commands.poll(150, TimeUnit.MILLISECONDS))
            send("""{"action":"event","event":{"action":"devicesessions","nodeid":"node//phone","sessions":{}}}""")
            assertNull(device().battery)
        } finally {
            client.close(); scope.cancel(); vault.clearCredentials(); server.shutdown()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("Synthetic control state timed out", condition())
    }
}
