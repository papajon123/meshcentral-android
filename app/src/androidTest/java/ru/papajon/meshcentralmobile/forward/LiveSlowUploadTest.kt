package ru.papajon.meshcentralmobile.forward

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.background.PortForwardService
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Opt-in local 4443 fixture only; the target pauses its HTTP request reader for twenty seconds. */
class LiveSlowUploadTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun uploadFiftyMiBSurvivesSlowTargetAndRelayBackpressure() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("forwardUpload") == "local-4443")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val workspace = ConnectionWorkspace.get(app)
        val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            val fixture = http.newCall(Request.Builder().url("http://127.0.0.1:19864/fixture").build()).execute().use {
                assertEquals(200, it.code); JSONObject(it.body!!.string())
            }
            assertEquals("https://10.0.2.2:4443/", fixture.getString("serverUrl"))
            assertEquals(18064, fixture.getInt("httpPort"))
            val server = "https://127.0.0.1:19466/" // transparent fixture TCP shaper; TLS stays pinned
            val node = fixture.getString("nodeId")
            main {
                workspace.closeAll(); workspace.client.configureServer(server)
                workspace.client.setPinnedServerKey(fixture.getString("pin"))
                workspace.client.login(server, fixture.getString("username"), fixture.getString("password"))
            }
            fixture.remove("password")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!workspace.client.canForward(node) && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(workspace.client.canForward(node))
            http.newCall(Request.Builder().url("http://127.0.0.1:19866/arm")
                .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { assertEquals(200, it.code) }
            val rule = PortForwardRule(profileId = workspace.profileId, nodeId = node, name = "Slow upload fixture", remotePort = 18064)
            main {
                assertTrue(workspace.client.portForwards.start(rule))
                ContextCompat.startForegroundService(compose.activity, Intent(app, PortForwardService::class.java))
            }
            val port = workspace.client.portForwards.states.value.single { it.rule.id == rule.id }.localPort
            Socket().use { local ->
                local.sendBufferSize = 16 * 1024
                local.connect(InetSocketAddress("127.0.0.1", port)); local.soTimeout = 90_000
                val output = local.getOutputStream()
                val size = 50 * 1024 * 1024
                output.write("POST /slow-upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                val block = ByteArray(32768) { (it % 251).toByte() }
                val digest = MessageDigest.getInstance("SHA-256")
                val started = System.nanoTime()
                var slowestWrite = 0L
                repeat(size / block.size) {
                    val before = System.nanoTime(); output.write(block)
                    slowestWrite = maxOf(slowestWrite, System.nanoTime() - before)
                    digest.update(block)
                }
                val response = local.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue("Expected HTTP200; response bytes=${response.length}; longestWrite=${slowestWrite / 1e9}s", response.startsWith("HTTP/1.1 200"))
                val result = JSONObject(response.substringAfter("\r\n\r\n"))
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("787a6d1a512fef0e64315b93a770bc2d294062ddcdceeeb5c2b3b99e4618d0bb", sha)
                assertEquals(size, result.getInt("received")); assertEquals(sha, result.getString("sha256"))
                assertEquals(20, result.getInt("pauseSeconds"))
                assertTrue("The target must actually backpressure the Android producer for >10s", slowestWrite > TimeUnit.SECONDS.toNanos(10))
                val seconds = (System.nanoTime() - started) / 1e9
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "Live slow upload: bytes=$size SHA256=$sha readerPause=20s relayReadPause=15s longestWrite=${slowestWrite / 1e9}s elapsed=${seconds}s MiBps=${50 / seconds}; complete HTTP response verified\n") })
            }
        } finally {
            main { workspace.client.logout(true); workspace.closeAll() }
            app.stopService(Intent(app, PortForwardService::class.java))
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
