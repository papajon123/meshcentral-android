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

class PhoneCapabilityControlTest {
    @Test fun closingClientReleasesItsObserversWithoutCancellingWorkspaceScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = MeshCentralClient(SecureVault(context), scope)
        try {
            await { scope.coroutineContext[Job]!!.children.any() }
            client.close()
            await { scope.coroutineContext[Job]!!.children.none() }
            assertTrue("The workspace must remain available for the next profile", scope.isActive)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun correlatedSysinfoAndRefreshEventsCannotRestoreSupersededCapability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("phone-capability-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        val requests = LinkedBlockingQueue<JSONObject>()
        var socket: WebSocket? = null
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    if (message.optString("action") == "getsysinfo") requests.offer(message)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }))
            server.start()
            client.login(server.url("/").toString(), "fixture", "fixture-password")
            await { socket != null }
            socket!!.send("""{"action":"userinfo","userinfo":{"_id":"user//fixture"}}""")
            socket!!.send("""{"action":"meshes","meshes":[{"_id":"mesh//test","links":{"user//fixture":{"rights":8}}}]}""")
            socket!!.send("""{"action":"nodes","nodes":{"mesh//test":[{"_id":"node//phone","name":"Phone","agent":{"id":14},"conn":1}]}}""")
            await { client.clientState.value.devicesCurrent && client.clientState.value.devices.singleOrNull()?.rights == 8 }
            fun capability() = client.clientState.value.devices.single().remoteInput
            fun refresh() = socket!!.send("""{"action":"event","event":{"action":"sysinfohash","nodeid":"node//phone","hash":"fixture"}}""")
            fun respond(request: JSONObject, value: String?) {
                val reply = JSONObject().put("action", "getsysinfo").put("nodeid", "node//phone").put("tag", request.getString("tag"))
                if (value != null) reply.put("remoteInput", value)
                socket!!.send(reply.toString())
            }
            assertEquals(PhoneRemoteInput.Unknown, capability())
            refresh()
            respond(requests.poll(5, TimeUnit.SECONDS)!!, "enabled")
            await { capability() == PhoneRemoteInput.Enabled }
            assertTrue(client.clientState.value.devices.single().phoneInputAllowed)
            refresh()
            val old = requests.poll(5, TimeUnit.SECONDS)!!
            await { capability() == PhoneRemoteInput.Unknown }
            refresh()
            // Give the listener the second hash event before completing the first request.
            Thread.sleep(100)
            respond(old, "enabled")
            val fresh = requests.poll(5, TimeUnit.SECONDS)!!
            assertEquals(PhoneRemoteInput.Unknown, capability())
            respond(fresh, "disabled")
            await { capability() == PhoneRemoteInput.Disabled }
            refresh()
            respond(requests.poll(5, TimeUnit.SECONDS)!!, null)
            await { capability() == PhoneRemoteInput.Unknown }
            assertFalse(client.clientState.value.devices.single().phoneInputAllowed)
            refresh()
            val disconnectedReply = requests.poll(5, TimeUnit.SECONDS)!!
            socket!!.send("""{"action":"event","event":{"action":"nodeconnect","nodeid":"node//phone","conn":0,"pwr":0}}""")
            await { client.clientState.value.devices.single().conn == 0 }
            socket!!.send("""{"action":"event","event":{"action":"nodeconnect","nodeid":"node//phone","conn":1,"pwr":1}}""")
            await { client.clientState.value.devices.single().conn == 1 }
            respond(disconnectedReply, "enabled")
            Thread.sleep(100)
            assertEquals(PhoneRemoteInput.Unknown, capability())
        } finally {
            client.close(); scope.cancel(); vault.clearCredentials(); server.shutdown()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Synthetic capability state timed out", condition())
    }
}
