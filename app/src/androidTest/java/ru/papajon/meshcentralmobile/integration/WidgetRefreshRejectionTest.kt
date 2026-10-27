package ru.papajon.meshcentralmobile.integration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/**
 * Regression coverage for the widget refresh rejection path.
 *
 * Every cold refresh creates a short-lived [MeshCentralClient] with `singleAttempt = true`. A PIN,
 * an HTTP 401/403/429 or a MeshCentral `{action:'close', cause, msg}` frame must leave a persisted
 * latch that prevents the next refresh from sending credentials again. The tests use isolated
 * UUID profiles and restore the original preferences (including the PIN) afterwards.
 */
class WidgetRefreshRejectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val gatePreferences get() = context.getSharedPreferences(GATE_PREFERENCES, Context.MODE_PRIVATE)

    private val preferenceNames = listOf(
        SETTINGS_PREFERENCES, VAULT_PREFERENCES, LAUNCHER_PREFERENCES, GATE_PREFERENCES, STATUS_PREFERENCES,
    )
    private lateinit var originalPreferences: Map<String, Map<String, *>>

    @Before fun captureOriginalState() {
        // Reading the catalog also runs a possible legacy settings migration before the snapshot.
        check(SettingsRepository(context).profilesState.value.isNotEmpty()) {
            "Settings must expose at least one profile"
        }
        originalPreferences = preferenceNames.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
    }

    /**
     * The refresh worker and the gate use fixed preference names. They are restored byte-for-byte,
     * so an existing PIN, credentials or launcher cache from another test is never destroyed.
     */
    @After fun restoreOriginalState() {
        runBlocking {
            runCatching { LauncherCoordinator.awaitIdle() }
            runCatching {
                LauncherCoordinator.clear(context)
                LauncherCoordinator.awaitIdle()
            }
            preferenceNames.forEach { name ->
                runCatching { restorePreferences(name, originalPreferences.getValue(name)) }
            }
            runCatching { LauncherCoordinator.awaitIdle() }
        }
    }

    @Test fun pinWithFilledCacheAndSavedCredentialsPreventsAnyNetworkRequest() = runBlocking {
        prepareColdRefresh()
        val server = MockWebServer()
        server.start()
        try {
            val profile = preparedProfile(server.url("/").toString())
            val workspace = ConnectionWorkspace.existing()
            assertFalse("A fresh UUID profile cannot have a live channel",
                workspace != null && workspace.profileId == profile.id &&
                    workspace.client.clientState.value.socketConnected)
            SecureVault(context).savePin(PIN)
            awaitNoSessionCleanup()
            assertFalse(WidgetRefreshWorker.refresh(context))
            // The PIN guard runs before the gate is claimed and before any client is created.
            assertEquals(0, server.requestCount)
            assertFalse(gatePreferences.contains("${profile.id}.wall"))
            assertFalse(gatePreferences.contains("${profile.id}.blocked"))
        } finally {
            server.close()
        }
    }

    @Test fun http401403429RejectionsLatchTheGateAndStopFurtherLogins() = runBlocking {
        prepareColdRefresh()
        assertFalse(SecureVault(context).hasPin())
        val server = MockWebServer()
        server.start()
        try {
            for (code in listOf(401, 403, 429)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val profile = preparedProfile(server.url("/").toString())
                awaitNoSessionCleanup()
                val before = server.requestCount
                assertFalse("HTTP $code must not update the widget", WidgetRefreshWorker.refresh(context))
                assertEquals("HTTP $code must reach the endpoint once", before + 1, server.requestCount)
                assertTrue("HTTP $code must latch the gate",
                    gatePreferences.getBoolean("${profile.id}.blocked", false))
                // The latch is checked before the temporary client is created.
                assertFalse(WidgetRefreshWorker.refresh(context))
                assertEquals("A latched gate must not log in again", before + 1, server.requestCount)
            }
        } finally {
            server.close()
        }
    }

    @Test fun websocketCloseFramesLatchTheGateAndStopFurtherLogins() = runBlocking {
        prepareColdRefresh()
        assertFalse(SecureVault(context).hasPin())
        val server = MockWebServer()
        server.start()
        try {
            // Frame shapes follow MeshCentral: bad password -> cause noauth + msg noauth-2a,
            // a missing second factor -> msg tokenrequired, a blocked address -> cause banned.
            val cases = listOf(
                Triple("noauth", "noauth-2a", "bad password"),
                Triple("noauth", "tokenrequired", "two-factor required"),
                Triple("banned", "banned", "banned address"),
            )
            for ((cause, message, label) in cases) {
                server.enqueue(MockResponse().withWebSocketUpgrade(closeFrameListener(cause, message)))
                val profile = preparedProfile(server.url("/").toString())
                awaitNoSessionCleanup()
                val before = server.requestCount
                assertFalse("$label must not update the widget", WidgetRefreshWorker.refresh(context))
                assertEquals("$label must reach the endpoint once", before + 1, server.requestCount)
                assertTrue("$label must latch the gate",
                    gatePreferences.getBoolean("${profile.id}.blocked", false))
                assertFalse(WidgetRefreshWorker.refresh(context))
                assertEquals("A latched gate must not reconnect", before + 1, server.requestCount)
            }
        } finally {
            server.close()
        }
    }

    @Test fun selfSignedServerWithForeignPinLatchesTheGateAndDoesNotRetry() = runBlocking {
        prepareColdRefresh()
        val fixture = TlsFixture()
        try {
            val otherCertificate = HeldCertificate.Builder().commonName("other.example").build()
            val foreignPin = spkiSha256(otherCertificate.certificate)
            val profile = preparedProfile(fixture.url)
            SettingsRepository(context).updatePinnedServerKey(foreignPin)
            awaitNoSessionCleanup()
            assertFalse(WidgetRefreshWorker.refresh(context))
            assertEquals("One TLS handshake may be attempted", 1, fixture.connections.get())
            assertEquals("Credentials must not cross a rejected TLS handshake", 0, fixture.server.requestCount)
            assertTrue(gatePreferences.getBoolean("${profile.id}.blocked", false))
            assertFalse(WidgetRefreshWorker.refresh(context))
            assertEquals("A latched gate must not open another TLS connection", 1, fixture.connections.get())
            assertEquals(0, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }

    @Test fun expectedPinnedKeyWithSavedCredentialsAndFilledCacheMakesNoConnection() = runBlocking {
        prepareColdRefresh()
        val fixture = TlsFixture()
        try {
            val settings = SettingsRepository(context)
            val imported = ServerProfile(UUID.randomUUID().toString(), "Synthetic expected pin", fixture.url,
                onboardingCompleted = true, expectedSpkiSha256 = "a".repeat(64))
            settings.appendImportedProfiles(listOf(imported to emptyList()))
            settings.activateProfile(imported.id)
            val profile = settings.currentProfile(imported.id) ?: error("Imported profile missing")
            assertEquals(imported.expectedSpkiSha256, profile.expectedSpkiSha256)
            val nodeIds = listOf(DEVICE.nodeId)
            LauncherStore(context).publish(profile.id,
                LauncherStore.scope(profile.id, profile.serverUrl, USER), listOf(DEVICE), nodeIds.toSet(), nodeIds)
            SecureVault(context, profile.id).saveCredentials(USER, PASSWORD, profile.serverUrl)
            LauncherCoordinator.awaitIdle()
            assertFalse(WidgetRefreshWorker.refresh(context))
            // An imported expected key is not an approved pin: no TCP handshake or HTTP request.
            assertEquals(0, fixture.connections.get())
            assertEquals(0, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }

    @Test fun secondFastRefreshCooldownPreservesWidgetStatusAndRequestCount() = runBlocking {
        prepareColdRefresh()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1000, "fixture close")
            }
        }))
        server.start()
        try {
            preparedProfile(server.url("/").toString())
            awaitNoSessionCleanup()
            assertEquals(WidgetRefreshWorker.RefreshOutcome.UNAVAILABLE,
                WidgetRefreshWorker.runRefresh(context))
            assertEquals(1, server.requestCount)
            val status = context.getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE)
            assertTrue("A failed attempt marks the widget stale", status.getBoolean("needs_app", false))
            // The second tap arrives inside the gate window: no login, no status rewrite.
            assertEquals(WidgetRefreshWorker.RefreshOutcome.COOLDOWN,
                WidgetRefreshWorker.runRefresh(context))
            assertEquals(1, server.requestCount)
            assertTrue("Cooldown must not cancel an existing needs_app flag",
                status.getBoolean("needs_app", false))
        } finally {
            server.close()
        }
    }

    @Test fun temporarySingleAttemptClientDoesNotReconnectAfterServerClose() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1000, "fixture close")
            }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // An isolated vault avoids inheriting a real login key or remembered device.
        val vault = SecureVault(context, "widget-single-${UUID.randomUUID()}")
        val client = MeshCentralClient(vault, scope, singleAttempt = true)
        try {
            val url = server.url("/").toString()
            withContext(Dispatchers.Main) {
                client.configureServer(url)
                client.login(url, "synthetic-user", "synthetic-password")
            }
            withTimeout(10_000) { client.clientState.first { it.connectionState == ConnectionState.Failed } }
            assertEquals(1, server.requestCount)
            // Automatic recovery hooks must all be ignored for a single-attempt temporary client.
            withContext(Dispatchers.Main) {
                client.onNetworkLost()
                client.onNetworkAvailable()
                client.onAppForeground()
            }
            delay(2_500)
            assertEquals("singleAttempt must not schedule an automatic reconnect", 1, server.requestCount)
        } finally {
            withContext(Dispatchers.Main) { client.close() }
            scope.cancel()
            server.close()
        }
    }

    @Test fun gateClaimCooldownIsSixtySecondsAndPersistsAcrossInstances() {
        val id = "gate-${UUID.randomUUID()}"
        val wall = 1_700_000_000_000L
        val elapsed = 10_000L
        assertTrue(WidgetRefreshGate(context).claim(id, wall, elapsed))
        // A new gate object reads the committed cooldown from preferences.
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 59_999, elapsed + 59_999))
        // A backwards wall clock alone does not reopen the window before sixty monotonic seconds.
        assertFalse(WidgetRefreshGate(context).claim(id, wall - 1, elapsed + 59_999))
        // The elapsed-realtime floor wins: after 60 s a corrected backwards wall is allowed.
        assertTrue(WidgetRefreshGate(context).claim(id, wall - 1, elapsed + 60_000))
        // The normal monotonic floor still requires the full minute even with a forward wall.
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 120_000, elapsed + 119_999))
        assertTrue(WidgetRefreshGate(context).claim(id, wall + 120_000, elapsed + 120_000))
    }

    @Test fun rebootRetainsOnlyTheRemainingWallInterval() {
        val id = "gate-${UUID.randomUUID()}"
        val wall = 1_700_000_000_000L
        assertTrue(WidgetRefreshGate(context).claim(id, wall, 500_000L))
        // Reboot after five seconds retains the remaining 55 seconds, not a new minute.
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 5_000, 1_000L))
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 59_999, 1_000L + 54_999))
        assertTrue(WidgetRefreshGate(context).claim(id, wall + 60_000, 1_000L + 55_000))
    }

    @Test fun timedOutWorkerCannotMarkANewActiveProfileUnavailable() = runBlocking {
        prepareColdRefresh()
        val nodesRequested = java.util.concurrent.CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("action", "userinfo")
                    .put("userinfo", JSONObject().put("_id", USER)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("action") == "nodes") nodesRequested.countDown()
                // Deliberately withhold nodes until the real worker timeout closes this client.
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        try {
            preparedProfile(server.url("/").toString())
            val refresh = async(Dispatchers.IO) { WidgetRefreshWorker.runRefresh(context) }
            assertTrue(nodesRequested.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val next = preparedProfile(server.url("/").toString())
            LauncherCoordinator.publish(context, next.id, LauncherStore.scope(next.id, next.serverUrl, USER),
                listOf(DEVICE), setOf(DEVICE.nodeId), listOf(DEVICE.nodeId)).join()
            assertEquals(WidgetRefreshWorker.RefreshOutcome.UNAVAILABLE, withTimeout(15_000) { refresh.await() })
            assertEquals(next.id, LauncherStore(context).read()!!.profileId)
            assertFalse(context.getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE).getBoolean("needs_app", true))
            assertEquals(1, server.requestCount)
        } finally { server.close() }
    }

    @Test fun normalWallFloorAndEpochZeroCannotBypassCooldown() {
        val id = "gate-${UUID.randomUUID()}"
        assertTrue(WidgetRefreshGate(context).claim(id, 0, 10_000))
        assertFalse(WidgetRefreshGate(context).claim(id, 1, 10_001))
        assertFalse(WidgetRefreshGate(context).claim(id, 59_999, 70_000))
        assertTrue(WidgetRefreshGate(context).claim(id, 60_000, 70_000))
    }

    @Test fun gateRejectionLatchOutlivesCooldownAndOnlyAppAuthenticationClearsIt() {
        val id = "gate-${UUID.randomUUID()}"
        val wall = 1_700_000_000_000L
        val elapsed = 20_000L
        val cooldown = 60_000L
        assertTrue(WidgetRefreshGate(context).claim(id, wall, elapsed))
        WidgetRefreshGate(context).reject(id)
        assertTrue(gateBlocked(id))
        // The rejection latch is authoritative even long after the 60 s cooldown.
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 10 * cooldown, elapsed + 10 * cooldown))
        assertTrue(gateBlocked(id))
        // Only a foreground app authentication clears the latch.
        WidgetRefreshGate(context).appAuthenticated(id)
        assertFalse(gateBlocked(id))
        // The old claim is already older than 60 s, so this new claim is accepted.
        assertTrue(WidgetRefreshGate(context).claim(id, wall + 10 * cooldown, elapsed + 10 * cooldown))
        // Clearing a fresh rejection must not bypass the 60 s cooldown of the accepted claim.
        WidgetRefreshGate(context).reject(id)
        WidgetRefreshGate(context).appAuthenticated(id)
        assertFalse(gateBlocked(id))
        assertFalse(WidgetRefreshGate(context).claim(id, wall + 10 * cooldown + 1,
            elapsed + 10 * cooldown + 1))
        assertTrue(WidgetRefreshGate(context).claim(id, wall + 11 * cooldown,
            elapsed + 11 * cooldown))
    }

    private fun prepareColdRefresh() {
        // The real worker reads these two global switches; restore the snapshot in @After.
        SecureVault(context).clearPin()
        SettingsRepository(context).updateBiometricUnlock(false)
    }

    private suspend fun preparedProfile(serverUrl: String): ServerProfile {
        val settings = SettingsRepository(context)
        val profile = settings.createProfile("Synthetic widget ${UUID.randomUUID()}", serverUrl,
            onboardingCompleted = true)
        val nodeIds = listOf(DEVICE.nodeId)
        // Publish before activation so the coordinator's render never sees an empty active profile.
        LauncherStore(context).publish(profile.id,
            LauncherStore.scope(profile.id, profile.serverUrl, USER), listOf(DEVICE), nodeIds.toSet(), nodeIds)
        settings.activateProfile(profile.id)
        if (serverUrl.startsWith("http://")) settings.updateAllowHttpInDebug(true)
        SecureVault(context, profile.id).saveCredentials(USER, PASSWORD, profile.serverUrl)
        LauncherCoordinator.awaitIdle()
        return settings.currentProfile(profile.id) ?: profile
    }

    /** Another test may still be finishing a logout on the application-owned workspace. */
    private suspend fun awaitNoSessionCleanup() {
        val workspace = ConnectionWorkspace.existing() ?: return
        withTimeout(15_000) { while (workspace.clearingSession) delay(25) }
    }

    private fun gateBlocked(id: String): Boolean =
        gatePreferences.getBoolean("$id.blocked", false)

    private fun closeFrameListener(cause: String, message: String?): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val frame = JSONObject().put("action", "close").put("cause", cause)
                if (message != null) frame.put("msg", message)
                if (message == "tokenrequired") {
                    frame.put("email2fa", false).put("sms2fa", false).put("msg2fa", false)
                        .put("twoFactorCookieDays", 30)
                }
                webSocket.send(frame.toString())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, (value as Set<String>).toSet())
                }
            }
        }
        assertTrue("Could not restore $name", editor.commit())
    }

    /** Minimal self-signed TLS server that counts accepted TCP connections before the handshake. */
    private class TlsFixture : AutoCloseable {
        val connections = AtomicInteger()
        val certificate: HeldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val server = MockWebServer()
        val url: String get() = "https://127.0.0.1:${server.port}/"

        init {
            server.serverSocketFactory = object : ServerSocketFactory() {
                override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also { connections.incrementAndGet() }
                }

                override fun createServerSocket(port: Int): ServerSocket =
                    error("Use an unbound socket")

                override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
                    error("Use an unbound socket")

                override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket =
                    error("Use an unbound socket")
            }
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build()
                .sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        override fun close() = server.close()
    }

    private companion object {
        private const val SETTINGS_PREFERENCES = "app_settings"
        private const val VAULT_PREFERENCES = "secure_vault"
        private const val LAUNCHER_PREFERENCES = "launcher_devices"
        private const val GATE_PREFERENCES = "widget_auth_gate"
        private const val STATUS_PREFERENCES = "widget_status"
        private const val PIN = "2468"
        private const val USER = "user//widget-refresh"
        private const val PASSWORD = "synthetic-password"
        private val DEVICE = MeshDevice("node//widget-refresh", "mesh//widget-refresh",
            "Synthetic widget device", conn = 1)
    }
}
