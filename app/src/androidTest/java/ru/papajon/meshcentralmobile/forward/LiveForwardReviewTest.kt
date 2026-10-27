package ru.papajon.meshcentralmobile.forward

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.background.PortForwardService
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Opt-in integration with the owned, transparent loopback fault proxy in front of fixture 4443. */
class LiveForwardReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val workspace get() = ConnectionWorkspace.get(app)
    private val api = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    @Test fun resetIdleSshNotificationAndDefaultIdleExpiry() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("forwardReview") == "local-4443")
        try {
            val fixture = request("/fixture")
            assertEquals("https://10.0.2.2:4443/", fixture.getString("serverUrl"))
            assertEquals("https://127.0.0.1:19464/", fixture.getString("proxyUrl"))
            val node = fixture.getString("nodeId")
            main {
                workspace.closeAll()
                workspace.client.configureServer(fixture.getString("proxyUrl"))
                workspace.client.setPinnedServerKey(fixture.getString("pin"))
                workspace.client.login(fixture.getString("proxyUrl"), fixture.getString("username"), fixture.getString("password"))
            }
            fixture.remove("password")
            await(30_000) { workspace.client.canForward(node) }
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
            val manager = workspace.client.portForwards
            val notifications = app.getSystemService(NotificationManager::class.java)
            fun notificationAtCount(count: Int): android.service.notification.StatusBarNotification {
                val label = app.getString(ru.papajon.meshcentralmobile.R.string.pf_connections, count)
                await { notifications.activeNotifications.any { item -> item.id == PortForwardService.ID &&
                    item.notification.extras.getCharSequenceArray("android.textLines")?.any { it.toString().contains(label) } == true } }
                return notifications.activeNotifications.single { it.id == PortForwardService.ID }
            }
            val httpRule = PortForwardRule(profileId = workspace.profileId, nodeId = node, name = "Review HTTP", kind = ForwardKind.HTTP, remotePort = fixture.getInt("httpPort"))
            start(httpRule)
            val localPort = state(httpRule).localPort
            await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
            Socket("::1", localPort).use { healthy ->
                healthy.soTimeout = 15_000
                val healthyInput = healthy.getInputStream().buffered()
                ping(healthy, healthyInput)
                request("/arm-cut", "{}")
                Socket("127.0.0.1", localPort).use { broken ->
                    broken.soTimeout = 15_000
                    broken.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".toByteArray())
                    val input = broken.getInputStream().buffered()
                    val header = headers(input)
                    assertFalse(header.lowercase().contains("content-length:"))
                    assertFalse(header.lowercase().contains("transfer-encoding:"))
                    var bytes = 0L
                    var reset = false
                    try {
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n }
                    } catch (_: SocketException) { reset = true }
                    assertTrue("A failed EOF-delimited download must raise an error, not return a short file", reset)
                    assertTrue(bytes in 1 until 52_428_800L)
                    proof("Interrupted EOF-delimited HTTP: RST=true bytes=$bytes")
                }
                await { state(httpRule).connections == 1 }
                assertTrue(state(httpRule).running)
                assertEquals(ForwardError.CONNECTION_LOST, state(httpRule).error)
                val posted = notificationAtCount(1).postTime
                ping(healthy, healthyInput)
                assertEquals(posted, notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime)
            }
            main { manager.stop(httpRule.id) }
            await { notifications.activeNotifications.none { it.id == PortForwardService.ID } }

            val sshRule = PortForwardRule(profileId = workspace.profileId, nodeId = node, name = "Review SSH", kind = ForwardKind.SSH, remotePort = fixture.getInt("sshPort"))
            start(sshRule)
            await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
            request("/ssh-start", JSONObject().put("port", state(sshRule).localPort).toString())
            await(30_000) { request("/ssh-state").optBoolean("idleReady") }
            val created = request("/stats").getInt("created")
            await(110_000, 1000) { request("/ssh-state").optBoolean("done") }
            val ssh = request("/ssh-state")
            assertTrue("Idle SSH must fail alone while the active SSH session continues", ssh.optBoolean("passed"))
            assertTrue(ssh.getDouble("idleSeconds") >= 75)
            await { state(sshRule).connections == 1 }
            assertTrue(state(sshRule).running)
            assertEquals(ForwardError.CONNECTION_LOST, state(sshRule).error)
            assertEquals(created, request("/stats").getInt("created"))
            val sshNotification = notificationAtCount(1).postTime
            val received = state(sshRule).bytesReceived
            Thread.sleep(1500)
            assertTrue(state(sshRule).bytesReceived > received)
            assertEquals(sshNotification, notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime)
            proof("SSH idle ${ssh.getDouble("idleSeconds")}s; proxy cut only idle relay; active ticks=${ssh.getInt("ticks")}; notification unchanged while count is stable")

            request("/ssh-stop", "{}")
            await(15_000) { state(sshRule).connections == 0 }
            val idleStart = SystemClock.elapsedRealtime()
            val idlePosted = notificationAtCount(0).postTime
            await(330_000, 1000) {
                if (state(sshRule).running) {
                    assertEquals(idlePosted, notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime)
                    false
                } else true
            }
            val elapsed = SystemClock.elapsedRealtime() - idleStart
            assertTrue("The full default inactivity period must elapse", elapsed >= TimeUnit.MINUTES.toMillis(5) - 2000)
            assertTrue(state(sshRule).autoStopped)
            await { notifications.activeNotifications.none { it.id == PortForwardService.ID } }
            LoopbackListeners.bind(state(sshRule).localPort, 1).forEach { it.close() }
            proof("Default idle stop: ${elapsed}ms; both loopbacks released; notification removed")
        } finally {
            runCatching { request("/ssh-stop", "{}") }
            main { workspace.client.logout(true); workspace.closeAll() }
            app.stopService(Intent(app, PortForwardService::class.java))
            api.connectionPool.evictAll(); api.dispatcher.executorService.shutdown()
        }
    }

    private fun state(rule: PortForwardRule) = workspace.client.portForwards.states.value.single { it.rule.id == rule.id }
    private fun start(rule: PortForwardRule) = main {
        assertTrue(workspace.client.portForwards.start(rule))
        ContextCompat.startForegroundService(compose.activity, Intent(app, PortForwardService::class.java))
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun proof(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Live review: $text\n") })
    private fun request(path: String, body: String? = null): JSONObject {
        val request = Request.Builder().url("http://127.0.0.1:19864$path").apply { if (body != null) post(body.toRequestBody()) }.build()
        return api.newCall(request).execute().use { response ->
            assertEquals("Local fixture helper must answer", 200, response.code)
            JSONObject(requireNotNull(response.body).string())
        }
    }
    private fun ping(socket: Socket, input: InputStream) {
        socket.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: fixture\r\n\r\n".toByteArray())
        assertTrue(headers(input).startsWith("HTTP/1.1 200"))
        assertArrayEquals("pong".toByteArray(), input.readNBytes(4))
    }
    private fun headers(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 8192) {
            val next = input.read(); check(next >= 0) { "Fixture closed before headers" }
            bytes.write(next); tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) return bytes.toString("US-ASCII")
        }
        error("Fixture headers too large")
    }
    private fun await(timeout: Long = 10_000, interval: Long = 20, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(interval)
        assertTrue("Live review condition timed out", condition())
    }
}
