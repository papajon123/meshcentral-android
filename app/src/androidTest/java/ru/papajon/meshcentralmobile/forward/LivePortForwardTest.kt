package ru.papajon.meshcentralmobile.forward

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.background.PortForwardService
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Opt-in isolated fixture only. Bootstrap credentials are held in memory, never logged. */
class LivePortForwardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun downloadsFiftyMiBAndEchoesThroughPinnedAgentThenStopsFromNotification() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("forwardLive") == "local-4443")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val workspace = ConnectionWorkspace.get(app)
        // A local request may spend up to 20 seconds waiting for the agent relay to pair.
        // OkHttp's default 10-second read timeout would expire before that transport deadline.
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build()
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            val fixture = http.newCall(Request.Builder().url("http://127.0.0.1:19864/fixture").build()).execute().use {
                assertEquals(200, it.code); JSONObject(requireNotNull(it.body).string())
            }
            assertEquals("https://10.0.2.2:4443/", fixture.getString("serverUrl"))
            val serverAddress = if (InstrumentationRegistry.getArguments().getString("forwardReverse") == "true")
                "https://127.0.0.1:4443/" else fixture.getString("serverUrl")
            val node = fixture.getString("nodeId")
            main {
                workspace.closeAll()
                workspace.client.configureServer(serverAddress)
                workspace.client.setPinnedServerKey(fixture.getString("pin"))
                workspace.client.login(serverAddress, fixture.getString("username"), fixture.getString("password"))
            }
            fixture.remove("password")
            await { workspace.client.canForward(node) }
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
            val rule = PortForwardRule(profileId = workspace.profileId, nodeId = node, name = "Live HTTP", kind = ForwardKind.HTTP, remotePort = fixture.getInt("httpPort"))
            main {
                assertTrue(workspace.client.portForwards.start(rule))
                ContextCompat.startForegroundService(compose.activity, Intent(app, PortForwardService::class.java))
            }
            val state = workspace.client.portForwards.states.value.single { it.rule.id == rule.id }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var headerSeconds = 0.0
            val start = System.nanoTime()
            try { http.newCall(Request.Builder().url("http://127.0.0.1:${state.localPort}/payload.bin").build()).execute().use { response ->
                headerSeconds = (System.nanoTime() - start) / 1e9
                assertEquals(200, response.code)
                response.body!!.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n); bytes += n
                    }
                }
            } } catch (failure: Exception) {
                val status = workspace.client.portForwards.states.value.single { it.rule.id == rule.id }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Local fixture failure: kind=${failure.javaClass.simpleName} elapsed=${(System.nanoTime()-start)/1e9} running=${status.running} error=${status.error} connections=${status.connections} sent=${status.bytesSent} received=${status.bytesReceived} control=${workspace.client.clientState.value.socketConnected}\n")
                })
                throw failure
            }
            val seconds = (System.nanoTime() - start) / 1e9
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(52_428_800L, bytes)
            assertEquals("975365ab6f1256348ae8289f6e8f55b2a4db2d4ea3f1efde161216d223cad0fe", sha)
            // The Windows host is a different network endpoint from the WSL Linux agent.
            // Reach the already-running fixture HTTPS service: no new non-loopback listener.
            val lanAddress = fixture.getString("lanAddress")
            assertTrue(lanAddress.startsWith("172.") || lanAddress.startsWith("192.168.") || lanAddress.startsWith("10."))
            assertEquals(4443, fixture.getInt("lanPort"))
            val lan = rule.copy(id = "live-lan", name = "Live agent network HTTPS", kind = ForwardKind.HTTPS, remotePort = 4443, remoteAddress = lanAddress)
            main { assertTrue(workspace.client.portForwards.start(lan)) }
            val lanPort = workspace.client.portForwards.states.value.single { it.rule.id == lan.id }.localPort
            val target = "https://127.0.0.1:$lanPort/".toHttpUrl()
            val targetBuilder = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false)
            ru.papajon.meshcentralmobile.mesh.tls.ServerTls(target, fixture.getString("pin")).applyTo(targetBuilder)
            val targetClient = targetBuilder.build()
            try {
                targetClient.newCall(Request.Builder().url(target).build()).execute().use { response ->
                    assertTrue("The pinned Windows fixture must answer through the agent network", response.code in 200..399)
                }
            } finally { targetClient.connectionPool.evictAll(); targetClient.dispatcher.executorService.shutdown() }
            val echo = rule.copy(id = "live-echo", name = "Live TCP echo", kind = ForwardKind.CUSTOM, remotePort = fixture.getInt("echoPort"), remoteAddress = "127.0.0.1")
            main { assertTrue(workspace.client.portForwards.start(echo)) }
            val echoPort = workspace.client.portForwards.states.value.single { it.rule.id == echo.id }.localPort
            Socket("127.0.0.1", echoPort).use { socket ->
                socket.soTimeout = 15000
                val challenge = ByteArray(32768) { (it % 127).toByte() }
                socket.getOutputStream().write(challenge)
                assertArrayEquals(challenge, socket.getInputStream().readNBytes(challenge.size))
                val notifications = app.getSystemService(NotificationManager::class.java)
                await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
                notifications.activeNotifications.single { it.id == PortForwardService.ID }.notification.actions.single().actionIntent.send()
                await { workspace.client.portForwards.states.value.none { it.running } }
                try { while (socket.getInputStream().read() != -1) { }; fail("Stop must reset an active client") }
                catch (_: java.net.SocketException) { }
                await { notifications.activeNotifications.none { it.id == PortForwardService.ID } }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Live TCP: bytes=$bytes SHA256=$sha headerSeconds=$headerSeconds seconds=$seconds MiBps=${50 / seconds}; echo=32768; tcpaddr loopback and agent network HTTPS=passed; notification stop=passed\n")
            })
        } finally {
            main { workspace.client.logout(true); workspace.closeAll() }
            app.stopService(Intent(app, PortForwardService::class.java))
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 30000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("Local live fixture timed out", condition())
    }
}
