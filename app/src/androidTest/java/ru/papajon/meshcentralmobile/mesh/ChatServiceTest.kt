package ru.papajon.meshcentralmobile.mesh

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.background.ChatService
import ru.papajon.meshcentralmobile.mesh.chat.ChatPhase
import ru.papajon.meshcentralmobile.mesh.chat.ChatProtocol
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Android service/visibility test; the separate paired fixture validates the wire lifecycle. */
class ChatServiceTest {
    @Test fun remoteMessagingKeepsWaitingAndConversationInBackgroundAndStopsAfterClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val previous = settings.activeProfile.id
        val certificate = HeldCertificate.Builder().commonName("service.example").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        var relay: WebSocket? = null
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (request.path!!.startsWith("/control.ashx")) {
                        webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user//fixture","name":"fixture"}}""")
                        webSocket.send("""{"action":"meshes","meshes":[{"_id":"mesh//fixture","name":"Fixture","links":{"user//fixture":{"rights":4294967295}}}]}""")
                        webSocket.send("""{"action":"authcookie","cookie":"synthetic-cookie"}""")
                        webSocket.send("""{"action":"nodes","nodes":{"mesh//fixture":[{"_id":"node//fixture","meshid":"mesh//fixture","name":"Service fixture","conn":1,"mtype":2,"agent":{"id":6,"caps":7}}]}}""")
                    } else relay = webSocket
                }
                override fun onMessage(webSocket: WebSocket, text: String) { JSONObject(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            })
        }
        server.start()
        val url = "https://127.0.0.1:${server.port}/"
        val profile = settings.createProfile("Chat service fixture", url, true)
        val workspace = ConnectionWorkspace.get(context.applicationContext as Application)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        try {
            onMain {
                settings.activateProfile(profile.id); workspace.switchProfile(profile.id)
                workspace.client.configureServer(url)
                workspace.client.setPinnedServerKey(spkiSha256(certificate.certificate))
                workspace.client.login(url, "fixture", "synthetic-password")
            }
            await { workspace.client.clientState.value.devicesCurrent }
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    workspace.openDevice(workspace.client.clientState.value.devices.single())
                    assertTrue(workspace.selectTab(ru.papajon.meshcentralmobile.ui.DeviceTab.Chat))
                    workspace.selectedSession!!.chat!!.start()
                }
                val chat = workspace.selectedSession!!.chat!!
                await { chat.state.value.phase == ChatPhase.Waiting && relay != null }
                await { notifications.activeNotifications.any { it.id == ChatService.ID } }
                @Suppress("DEPRECATION")
                val serviceInfo = context.packageManager.getServiceInfo(ComponentName(context, ChatService::class.java), 0)
                if (Build.VERSION.SDK_INT >= 34) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING, serviceInfo.foregroundServiceType)
                relay!!.send("c")
                relay!!.send("""{"action":"random","random":0.5}""")
                await { chat.state.value.phase == ChatPhase.Connected }
                scenario.moveToState(Lifecycle.State.CREATED)
                relay!!.send(ChatProtocol.message("background message"))
                await { chat.state.value.unreadCount == 1 && workspace.unreadChats == 1 }
                await { notifications.activeNotifications.any { it.tag?.startsWith("chat:${profile.id}:") == true } }
                repeat(100) { relay!!.send(ChatProtocol.message("burst $it")) }
                await { chat.state.value.unreadCount == 101 }
                await { notifications.activeNotifications.any {
                    it.tag?.startsWith("chat:${profile.id}:") == true && it.notification.number == 101
                } }
                @Suppress("DEPRECATION")
                val running = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                assertTrue(running.getRunningServices(100).any { it.service.className == ChatService::class.java.name && it.foreground })
                assertEquals(ChatPhase.Connected, chat.state.value.phase)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { workspace.closeSession(workspace.selectedSession!!.id) }
                await { notifications.activeNotifications.none { it.id == ChatService.ID } }
                assertEquals(ChatPhase.Closed, chat.state.value.phase)
                assertEquals(0, workspace.unreadChats)
                Thread.sleep(1_100)
                assertTrue(notifications.activeNotifications.none { it.tag?.startsWith("chat:${profile.id}:") == true })
            }
        } finally {
            onMain {
                workspace.closeChats()
                workspace.client.logout(clearStoredCredentials = true)
                context.stopService(Intent(context, ChatService::class.java))
                settings.activateProfile(previous); workspace.switchProfile(previous); settings.removeProfile(profile.id)
            }
            relay?.close(1000, null)
            server.shutdown()
        }
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue("Timed out waiting for chat service", condition())
    }
}
