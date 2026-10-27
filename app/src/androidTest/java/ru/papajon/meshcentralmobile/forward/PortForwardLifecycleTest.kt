package ru.papajon.meshcentralmobile.forward

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.background.PortForwardService
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

class PortForwardLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val workspace get() = ConnectionWorkspace.get(app)

    @Test fun notificationStopsListenerAndExistingConnections() = withFixture { server ->
        instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
        val rule = rule()
        onMain { assertTrue(workspace.client.portForwards.start(rule)); startService() }
        val port = workspace.client.portForwards.states.value.single { it.rule.id == rule.id }.localPort
        Socket("127.0.0.1", port).use { local ->
            local.soTimeout = 5000
            local.getOutputStream().write(byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), local.getInputStream().readNBytes(3))
            val notifications = app.getSystemService(NotificationManager::class.java)
            await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
            val notification = notifications.activeNotifications.single { it.id == PortForwardService.ID }.notification
            assertTrue(notification.extras.getCharSequenceArray("android.textLines")!!.any { it.toString().contains("127.0.0.1:$port") })
            notification.actions.single().actionIntent.send()
            await { workspace.client.portForwards.states.value.none { it.running } }
            assertReset(local)
            await { notifications.activeNotifications.none { it.id == PortForwardService.ID } }
            assertEquals(1, server.relayRequests.get())
        }
    }

    @Test fun revokedRightsCloseRouteWithoutWaitingForAnotherConnection() = withFixture { server ->
        onMain { assertTrue(workspace.client.portForwards.start(rule())) }
        server.control!!.send(server.meshes(0))
        await { workspace.client.portForwards.states.value.none { it.running } }
        assertEquals(ForwardError.UNAVAILABLE, workspace.client.portForwards.states.value.last().error)
    }

    @Test fun controlLossStopsAllRoutesAndDoesNotRestartThem() = withFixture { server ->
        onMain { assertTrue(workspace.client.portForwards.start(rule())) }
        server.control!!.close(1000, null)
        await { workspace.client.portForwards.states.value.none { it.running } }
        Thread.sleep(1200)
        assertFalse(workspace.client.portForwards.states.value.any { it.running })
    }

    @Test fun logoutAndProfileChangeCloseOldManagers() = withFixture {
        val first = workspace.client.portForwards
        onMain { assertTrue(first.start(rule())); workspace.client.logout(clearStoredCredentials = true) }
        assertFalse(first.states.value.any { it.running })
        val old = workspace.profileId
        onMain { workspace.switchProfile("forward-test-other") }
        assertNotSame(first, workspace.client.portForwards)
        assertFalse(first.start(rule().copy(profileId = old)))
        onMain { workspace.switchProfile(old) }
    }

    @Test fun serverRejectionStopsRuleAndNeverRetries() = withFixture { server ->
        server.reject = true
        val rule = rule()
        onMain { assertTrue(workspace.client.portForwards.start(rule)) }
        val port = workspace.client.portForwards.states.value.last().localPort
        Socket("127.0.0.1", port).use { it.soTimeout = 5000; assertReset(it) }
        await { workspace.client.portForwards.states.value.none { it.running } }
        assertEquals(ForwardError.CONNECT_FAILED, workspace.client.portForwards.states.value.last().error)
        Thread.sleep(500)
        assertEquals(1, server.relayRequests.get())
    }

    @Test fun serviceDestructionStopsRoutesAndDoesNotRestart() = withFixture {
        onMain { assertTrue(workspace.client.portForwards.start(rule())); startService() }
        await { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == PortForwardService.ID } }
        app.stopService(Intent(app, PortForwardService::class.java))
        await { workspace.client.portForwards.states.value.none { it.running } }
        Thread.sleep(500)
        assertFalse(workspace.client.portForwards.states.value.any { it.running })
    }

    @Test fun trafficAndRepeatedStartDoNotRepostUnchangedNotification() = withFixture {
        onMain { assertTrue(workspace.client.portForwards.start(rule())); startService() }
        val notifications = app.getSystemService(NotificationManager::class.java)
        await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
        val port = workspace.client.portForwards.states.value.last().localPort
        Socket(workspace.client.portForwards.boundEndpoints(workspace.client.portForwards.states.value.last().rule.id).last().address, port).use { local ->
            local.soTimeout = 5000
            local.getOutputStream().write(66)
            assertEquals(66, local.getInputStream().read())
            val connectionLabel = app.getString(ru.papajon.meshcentralmobile.R.string.pf_connections, 1)
            await { notifications.activeNotifications.single { it.id == PortForwardService.ID }.notification.extras.getCharSequenceArray("android.textLines")!!.any { it.toString().contains(connectionLabel) } }
            val initial = notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime
            repeat(20) {
                local.getOutputStream().write(67)
                assertEquals(67, local.getInputStream().read())
                Thread.sleep(100)
            }
            onMain { startService() }
            Thread.sleep(700)
            assertEquals(initial, notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime)
        }
    }

    @Test fun slowLocalConsumerSurvivesAHeartbeatWindow() = withFixture { server ->
        server.pushOnOpen = true
        onMain { assertTrue(workspace.client.portForwards.start(rule())) }
        val port = workspace.client.portForwards.states.value.last().localPort
        Socket().use { local ->
            local.receiveBufferSize = 1024
            local.connect(java.net.InetSocketAddress("127.0.0.1", port))
            local.soTimeout = 15000
            await { server.relayRequests.get() == 1 }
            Thread.sleep(65_000)
            assertTrue("Backpressure must not be mistaken for lost WebSocket Pong", workspace.client.portForwards.states.value.last().running)
            val bytes = local.getInputStream().readNBytes(4 * 1024 * 1024)
            assertEquals(4 * 1024 * 1024, bytes.size)
            assertTrue(bytes.all { it == 37.toByte() })
        }
    }

    @Test fun managerStopAllowsImmediateFixedPortRestartAfterBlockedAccept() {
        PortForwardManager({ true }, { _, _, _, _ -> error("Unused") }).use { manager ->
            val rule = rule()
            repeat(100) {
                assertTrue(manager.start(rule))
                val port = manager.states.value.single().localPort
                val endpoints = manager.boundEndpoints(rule.id)
                Thread.sleep(2) // establish a blocked accept, never wait after stop
                manager.stop(rule.id)
                for (endpoint in endpoints) {
                    java.net.ServerSocket().use { probe ->
                        probe.reuseAddress = false
                        probe.bind(endpoint)
                    }
                }
                assertTrue(manager.start(rule.copy(localPort = port)))
                manager.stop(rule.id)
            }
        }
    }

    @Test fun notificationPublishesFinalCountAfterBurstAndRepeatedServiceStarts() = withFixture {
        onMain { assertTrue(workspace.client.portForwards.start(rule())); startService() }
        val port = workspace.client.portForwards.states.value.last().localPort
        val clients = mutableListOf<Socket>()
        try {
            repeat(8) {
                val local = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
                clients.add(local)
                local.getOutputStream().write(63); assertEquals(63, local.getInputStream().read())
                onMain { startService() }
            }
            clients.drop(1).forEach { it.setSoLinger(true, 0); it.close() }
            await { workspace.client.portForwards.states.value.last().connections == 1 }
            onMain { repeat(20) { startService() } }
            val notifications = app.getSystemService(NotificationManager::class.java)
            val label = app.getString(ru.papajon.meshcentralmobile.R.string.pf_connections, 1)
            await { notifications.activeNotifications.any { item -> item.id == PortForwardService.ID &&
                item.notification.extras.getCharSequenceArray("android.textLines")?.any { it.toString().endsWith(label) } == true } }
            val posted = notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime
            Thread.sleep(1200)
            assertEquals(posted, notifications.activeNotifications.single { it.id == PortForwardService.ID }.postTime)
            clients.first().getOutputStream().write(64)
            assertEquals(64, clients.first().getInputStream().read())
        } finally { clients.forEach { it.close() } }
    }

    @Test fun sixteenRealOkHttpConnectionsAreIndependentAndSeventeenthIsRejected() = withFixture { server ->
        onMain { assertTrue(workspace.client.portForwards.start(rule())) }
        val port = workspace.client.portForwards.states.value.last().localPort
        val clients = mutableListOf<Socket>()
        try {
            repeat(16) { index ->
                val endpoints = workspace.client.portForwards.boundEndpoints(workspace.client.portForwards.states.value.last().rule.id)
                val socket = Socket(endpoints[index % endpoints.size].address, port).apply { soTimeout = 5000 }
                clients.add(socket)
                socket.getOutputStream().write(index)
                assertEquals(index, socket.getInputStream().read())
            }
            assertEquals(16, workspace.client.portForwards.states.value.last().connections)
            Socket("127.0.0.1", port).use { it.soTimeout = 5000; assertReset(it) }
            assertEquals(16, server.relayRequests.get())
            clients.forEach { it.getOutputStream().write(99); assertEquals(99, it.getInputStream().read()) }
        } finally { clients.forEach { it.close() } }
    }

    @Test fun newPairingRefusalPreservesRealExistingRelay() = withFixture { server ->
        onMain { assertTrue(workspace.client.portForwards.start(rule())) }
        val port = workspace.client.portForwards.states.value.last().localPort
        Socket("127.0.0.1", port).use { established ->
            established.soTimeout = 5000
            established.getOutputStream().write(31); assertEquals(31, established.getInputStream().read())
            server.reject = true
            Socket("127.0.0.1", port).use { it.soTimeout = 5000; assertReset(it) }
            await { workspace.client.portForwards.states.value.last().connections == 1 }
            established.getOutputStream().write(32); assertEquals(32, established.getInputStream().read())
            assertTrue(workspace.client.portForwards.states.value.last().running)
        }
    }

    @Test fun fixedPortCanRestartAfterServerInitiatedFinWithoutAllowingAnotherListener() {
        val listeners = LoopbackListeners.bind(0, 4)
        val port = listeners.first().localPort
        try {
            listeners.forEach { listener ->
                assertTrue(listener.reuseAddress)
                Socket(listener.inetAddress, port).use { peer ->
                    peer.soTimeout = 3000
                    listener.accept().use { accepted -> accepted.getOutputStream().write(57) }
                    assertEquals(57, peer.getInputStream().read())
                    assertEquals(-1, peer.getInputStream().read()) // server active-close enters TIME_WAIT
                }
                java.net.ServerSocket().use { competing ->
                    competing.reuseAddress = true
                    try { competing.bind(listener.localSocketAddress); fail("SO_REUSEADDR must not share listeners") }
                    catch (_: java.net.BindException) { }
                }
            }
        } finally { listeners.forEach { it.close() } }
        LoopbackListeners.bind(port, 4).forEach { it.close() }
    }

    @Test fun androidIdleExpiryReleasesBothBindingsButAnOpenConnectionPreventsIt() {
        PortForwardManager({ true }, { _, socket, _, finished ->
            object : PortConnection {
                override fun start() = Unit
                override fun close() { resetLocalSocket(socket); finished(null) }
            }
        }, idleTimeoutMillis = 300).use { manager ->
            val first = rule(); assertTrue(manager.start(first))
            val port = manager.states.value.single().localPort
            Socket(manager.boundEndpoints(first.id).last().address, port).use {
                await { manager.states.value.single().connections == 1 }
                Thread.sleep(650)
                assertTrue(manager.states.value.single().running)
                manager.stop(first.id)
            }
            assertTrue(manager.start(first.copy(localPort = port)))
            await { !manager.states.value.single().running }
            assertTrue(manager.states.value.single().autoStopped)
            LoopbackListeners.bind(port, 4).forEach { it.close() }
        }
    }

    /** Run separately after pm revoke POST_NOTIFICATIONS; revoking during instrumentation kills its UID. */
    @Test fun deniedNotificationsDoNotStopUserStartedTunnel() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("forwardNotificationsDenied") == "true")
        withFixture(grantNotifications = false) {
            assertFalse(app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            onMain { assertTrue(workspace.client.portForwards.start(rule())); startService() }
            val port = workspace.client.portForwards.states.value.last().localPort
            Socket("127.0.0.1", port).use { local ->
                local.soTimeout = 5000
                local.getOutputStream().write(73); assertEquals(73, local.getInputStream().read())
                assertTrue(workspace.client.portForwards.states.value.last().running)
            }
            app.stopService(Intent(app, PortForwardService::class.java))
            await { workspace.client.portForwards.states.value.none { it.running } }
        }
    }

    @Test fun pinLockedProductionScreenKeepsTunnelAndNotificationStopWorking() = withFixture { server ->
        val settings = ru.papajon.meshcentralmobile.settings.SettingsRepository(app)
        val oldUrl = settings.getSettings().serverUrl
        val oldPin = settings.activeProfile.pinnedSpkiSha256
        val vaultName = "forward-lock-${java.util.UUID.randomUUID()}"
        val vault = ru.papajon.meshcentralmobile.security.SecureVault(app, preferencesName = vaultName)
        vault.savePin("2468")
        val lock = ru.papajon.meshcentralmobile.settings.AppLockController(settings, vault)
        val show = androidx.compose.runtime.mutableStateOf(true)
        try {
            onMain {
                settings.updateServerUrl(server.url)
                settings.updatePinnedServerKey(spkiSha256(server.certificate.certificate))
                assertTrue(workspace.client.portForwards.start(rule())); startService()
            }
            val port = workspace.client.portForwards.states.value.last().localPort
            Socket("127.0.0.1", port).use { local ->
                local.soTimeout = 5000
                local.getOutputStream().write(17); assertEquals(17, local.getInputStream().read())
                compose.setContent {
                    ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme {
                        if (show.value) ru.papajon.meshcentralmobile.ui.MeshCentralApp(settings, vault, lock, {}, {}, {})
                    }
                }
                compose.onNodeWithText(app.getString(ru.papajon.meshcentralmobile.R.string.lock_title)).assertIsDisplayed()
                assertTrue(lock.isLocked)
                local.getOutputStream().write(18); assertEquals(18, local.getInputStream().read())
                val notifications = app.getSystemService(NotificationManager::class.java)
                await { notifications.activeNotifications.any { it.id == PortForwardService.ID } }
                notifications.activeNotifications.single { it.id == PortForwardService.ID }.notification.actions.single().actionIntent.send()
                await { workspace.client.portForwards.states.value.none { it.running } }
                assertReset(local)
                assertTrue(lock.isLocked)
            }
        } finally {
            try { onMain { show.value = false }; compose.waitForIdle() } finally {
                onMain { settings.updateServerUrl(oldUrl); settings.updatePinnedServerKey(oldPin) }
                vault.clearPin(); app.deleteSharedPreferences(vaultName)
            }
        }
    }

    private fun rule() = PortForwardRule(profileId = workspace.profileId, nodeId = NODE, name = "Fixture TCP", remotePort = 8080)
    private fun startService() { ContextCompat.startForegroundService(compose.activity, Intent(app, PortForwardService::class.java)) }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun withFixture(grantNotifications: Boolean = true, block: (Fixture) -> Unit) {
        if (grantNotifications) instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
        Fixture().use { server ->
            try {
                onMain {
                    workspace.closeAll()
                    workspace.client.configureServer(server.url)
                    workspace.client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                    workspace.client.login(server.url, "fixture-user", "fixture-password")
                }
                await { workspace.client.canForward(NODE) }
                block(server)
            } finally {
                onMain { workspace.client.logout(true); workspace.closeAll() }
                app.stopService(Intent(app, PortForwardService::class.java))
            }
        }
    }
    private class Fixture : AutoCloseable {
        val certificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
        val server = MockWebServer()
        val url get() = "https://127.0.0.1:${server.port}/"
        @Volatile var control: WebSocket? = null
        @Volatile var reject = false
        @Volatile var pushOnOpen = false
        val relayRequests = AtomicInteger()
        private val sockets = CopyOnWriteArrayList<WebSocket>()
        fun meshes(rights: Int) = """{"action":"meshes","meshes":[{"_id":"mesh/test/group","mtype":2,"name":"Fixture","links":{"user/test/fixture":{"rights":$rights}}}]}"""
        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val isControl = request.requestUrl?.encodedPath == "/control.ashx"
                    if (!isControl) {
                        relayRequests.incrementAndGet()
                        if (reject) return MockResponse().setResponseCode(403)
                        assertEquals("synthetic-cookie", request.requestUrl?.queryParameter("auth"))
                        assertEquals("14", request.requestUrl?.queryParameter("p"))
                    }
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets.add(webSocket)
                            if (isControl) {
                                control = webSocket
                                webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture","name":"fixture-user"}}""")
                                webSocket.send(meshes(8))
                                webSocket.send("""{"action":"authcookie","cookie":"synthetic-cookie"}""")
                                webSocket.send("""{"action":"nodes","nodes":{"mesh/test/group":[{"_id":"$NODE","meshid":"mesh/test/group","name":"Fixture PC","conn":1,"mtype":2,"agent":{"id":3,"caps":7}}]}}""")
                            } else {
                                webSocket.send("c")
                                if (pushOnOpen) repeat(128) { webSocket.send(ByteString.of(*ByteArray(32768) { 37 })) }
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (!isControl) webSocket.send(bytes) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    })
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }
        override fun close() { sockets.forEach { it.close(1000, null) }; server.close() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("Fixture state timed out", condition())
    }
    private fun assertReset(socket: Socket) {
        try { while (socket.getInputStream().read() != -1) { }; fail("Expected reset, not successful EOF") }
        catch (_: java.net.SocketException) { }
    }
    companion object { private const val NODE = "node/test/forward" }
}
