package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateIssuerExplanation
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.mesh.tls.certificateSha256
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** No debug TLS bypass: the same pin policy is installed in debug and release. */
class SelfSignedPinningTest {
    @Test fun rememberedDeviceIsNotSentAfterConfirmingAChangedKey() = withClient { client ->
        Fixture(requireTwoFactor = true).use { server ->
            onMain {
                client.configureServer(server.url)
                client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.twoFactorChallenge != null }
            onMain { client.submitTwoFactorCode("123456") }
            await { client.clientState.value.rememberedDeviceExpiresAt != null }
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.devicesCurrent }
            assertTrue(server.rememberedCookies.last())
            server.replaceCertificate()
            val requests = server.web.requestCount
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(ServerCertificateFailureKind.Changed, client.clientState.value.certificateFailure!!.kind)
            assertEquals(requests, server.web.requestCount)
            onMain {
                client.setPinnedServerKey(client.clientState.value.certificateFailure!!.spkiSha256)
                client.retryAfterCertificateTrust()
            }
            await { client.clientState.value.twoFactorChallenge != null }
            assertFalse(server.rememberedCookies.last())
            assertNull(client.clientState.value.rememberedDeviceExpiresAt)
        }
    }

    @Test fun restoringPinnedTrustAfterClientRestartKeepsRememberedDevice() = withClientAndVault { client, vault ->
        Fixture(requireTwoFactor = true).use { server ->
            onMain {
                client.configureServer(server.url)
                client.setAllowSelfSignedCertificates(true)
                client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.twoFactorChallenge != null }
            onMain { client.submitTwoFactorCode("123456") }
            await { client.clientState.value.rememberedDeviceExpiresAt != null }
            onMain { client.close() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val next = MeshCentralClient(vault, scope)
            try {
                onMain {
                    next.configureServer(server.url)
                    next.setAllowSelfSignedCertificates(true)
                    next.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                    assertTrue(next.loginWithStoredCredentials(server.url))
                }
                await { next.clientState.value.devicesCurrent }
                assertTrue(server.rememberedCookies.last())
                assertNull(next.clientState.value.twoFactorChallenge)
                onMain { next.setPinnedServerKey(null) }
                assertNull(vault.rememberedDevice.get(server.url, "fixture-user"))
            } finally { onMain { next.close(); scope.cancel() } }
        }
    }

    @Test fun platformCookieIsNotSentAfterFirstPinApproval() = withClientAndVault { client, vault ->
        Fixture(requireTwoFactor = true).use { server ->
            onMain {
                client.configureServer(server.url)
                vault.rememberedDevice.save(server.url, "fixture-user", "fixture-remembered", 30, RememberedDeviceTrust.Platform)
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(0, server.web.requestCount)
            onMain {
                client.setPinnedServerKey(client.clientState.value.certificateFailure!!.spkiSha256)
                client.retryAfterCertificateTrust()
            }
            await { client.clientState.value.twoFactorChallenge != null }
            assertFalse(server.rememberedCookies.single())
            assertNull(vault.rememberedDevice.get(server.url, "fixture-user"))
        }
    }

    @Test fun debugTlsBypassCannotIssueOrSendRememberedCookies() = withClientAndVault { client, vault ->
        Fixture(requireTwoFactor = true).use { server ->
            onMain {
                client.configureServer(server.url)
                client.setAllowSelfSignedCertificates(true)
                vault.rememberedDevice.save(server.url, "fixture-user", "fixture-remembered", 30, RememberedDeviceTrust.Platform)
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.twoFactorChallenge != null }
            assertFalse(server.rememberedCookies.single())
            onMain { client.submitTwoFactorCode("123456") }
            await { client.clientState.value.devicesCurrent }
            Thread.sleep(250)
            assertEquals(0, server.cookieRequests.get())
            assertNull(vault.rememberedDevice.get(server.url, "fixture-user"))
        }
    }

    @Test fun meshCentralStyleChainCanBePinned() = withClient { client ->
        val root = HeldCertificate.Builder().commonName("MeshCentralRoot-fixture").certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().commonName("localhost").signedBy(root).build()
        // Fixture sends only the leaf, just like the default MeshCentral web server.
        Fixture(certificate = leaf).use { server ->
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            val failure = client.clientState.value.certificateFailure!!
            assertEquals(ServerCertificateFailureKind.Untrusted, failure.kind)
            assertTrue(failure.canApprove)
            assertEquals(ServerCertificateIssuerExplanation.MeshCentralRoot, failure.issuerExplanation)
            assertEquals(spkiSha256(leaf.certificate), failure.spkiSha256)
            assertEquals(certificateSha256(leaf.certificate), failure.certificateSha256)
            assertNoRetries(client, server)
            onMain {
                client.setPinnedServerKey(failure.spkiSha256)
                client.retryAfterCertificateTrust()
            }
            await { client.clientState.value.devicesCurrent }
            assertTrue(client.clientState.value.socketConnected)
            assertFalse(client.clientState.value.requiresSignIn)
            assertNull(client.clientState.value.certificateFailure)
            Thread.sleep(2_500)
            assertEquals(2, server.connections.get())
            assertEquals(1, server.web.requestCount)
        }
    }

    @Test fun firstConnectShowsFingerprint() = withClient { client ->
        Fixture().use { server ->
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            val failure = client.clientState.value.certificateFailure!!
            assertEquals(ServerCertificateFailureKind.Untrusted, failure.kind)
            assertEquals(spkiSha256(server.certificate.certificate), failure.spkiSha256)
            assertEquals(certificateSha256(server.certificate.certificate), failure.certificateSha256)
            assertEquals(server.url, failure.serverAddress)
            assertEquals(UiText.Resource(R.string.tls_error_untrusted), client.clientState.value.lastError)
            assertNoRetries(client, server)
        }
    }

    @Test fun confirmedPinOpensControlChannel() = withClient { client ->
        Fixture().use { server ->
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            onMain {
                client.setPinnedServerKey(client.clientState.value.certificateFailure!!.spkiSha256)
                client.retryAfterCertificateTrust()
            }
            await { client.clientState.value.devicesCurrent }
            assertFalse(client.clientState.value.requiresSignIn)
            assertNull(client.clientState.value.certificateFailure)
            assertEquals(2, server.connections.get())
            assertEquals(1, server.web.requestCount)
        }
    }

    @Test fun changedCertificateBlocks() = withClient { client ->
        Fixture().use { server ->
            val old = HeldCertificate.Builder().commonName("old.example").build()
            onMain {
                client.configureServer(server.url)
                client.setPinnedServerKey(spkiSha256(old.certificate))
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(ServerCertificateFailureKind.Changed, client.clientState.value.certificateFailure!!.kind)
            assertEquals(UiText.Resource(R.string.tls_error_changed), client.clientState.value.lastError)
            assertNoRetries(client, server)
        }
    }

    @Test fun ipHostWorksOnlyWithPin() = withClient { client ->
        Fixture("different.example").use { server ->
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            assertFalse(client.clientState.value.socketConnected)
            assertEquals(0, server.web.requestCount) // Credentials never crossed the failed TLS handshake.
            onMain {
                client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                client.retryAfterCertificateTrust()
            }
            await { client.clientState.value.devicesCurrent }
            assertTrue(client.clientState.value.socketConnected)
        }
    }

    @Test fun forgettingPinClosesSessionAndRejectsNextConnection() = withClient { client ->
        Fixture().use { server ->
            onMain {
                client.configureServer(server.url)
                client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.devicesCurrent }
            val device = client.clientState.value.devices.single()
            val tools = RelayProtocol.entries.map { client.createRelayConnection(device, it) }
            val expectedConnections = tools.size + 2 // Control, every tool protocol, desktop.
            onMain { tools.forEach { it.connect() }; client.connectDesktop(device) }
            await { tools.all { it.state.value.phase == RelayPhase.Connected } &&
                client.desktopState.value.connectionState == ConnectionState.Connected }
            onMain { client.setPinnedServerKey(null) }
            assertFalse(client.clientState.value.socketConnected)
            await { server.closedSockets.get() == expectedConnections }
            // A listener can observe the revoked session before the explicit close reaches it.
            // Both terminal states are valid; server-side closure and rejected reuse are checked.
            assertTrue(tools.map { it.protocol to it.state.value.phase }.toString(), tools.all {
                it.state.value.phase in setOf(RelayPhase.Closed, RelayPhase.Failed)
            })
            assertEquals(ConnectionState.Offline, client.desktopState.value.connectionState)
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(ServerCertificateFailureKind.Untrusted, client.clientState.value.certificateFailure!!.kind)
            assertEquals(expectedConnections, server.web.requestCount)
        }
    }

    @Test fun changingEndpointDoesNotCarryThePin() = withClient { client ->
        Fixture().use { first ->
            Fixture(certificate = first.certificate).use { second ->
                onMain {
                    client.configureServer(first.url)
                    client.setPinnedServerKey(spkiSha256(first.certificate.certificate))
                    client.login(first.url, "fixture-user", "fixture-password")
                }
                await { client.clientState.value.devicesCurrent }
                onMain { client.login(second.url, "fixture-user", "fixture-password") }
                await { client.clientState.value.certificateFailure != null }
                assertEquals(ServerCertificateFailureKind.Untrusted, client.clientState.value.certificateFailure!!.kind)
                assertEquals(second.url, client.clientState.value.certificateFailure!!.serverAddress)
                assertEquals(0, second.web.requestCount)
            }
        }
    }

    @Test fun pinnedKeyOpensDesktopAndAllToolRelays() = withClient { client ->
        Fixture().use { server ->
            signInPinned(client, server)
            val device = client.clientState.value.devices.single()
            for (protocol in RelayProtocol.entries) {
                val relay = client.createRelayConnection(device, protocol)
                onMain { relay.connect() }
                await { relay.state.value.phase == RelayPhase.Connected }
                onMain { relay.close() }
            }
            onMain { client.connectDesktop(device) }
            await { client.desktopState.value.connectionState == ConnectionState.Connected }
            assertEquals(RelayProtocol.entries.size + 2, server.web.requestCount) // Control, every tool protocol, desktop; all over pinned TLS.
            assertNull(client.clientState.value.certificateFailure)
        }
    }

    @Test fun changedKeyOnToolRelayBlocksWholeSessionWithoutRetry() = withClient { client ->
        for (protocol in RelayProtocol.entries) Fixture().use { server ->
            signInPinned(client, server)
            server.replaceCertificate()
            val relay = client.createRelayConnection(client.clientState.value.devices.single(), protocol)
            onMain { relay.connect() }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(ServerCertificateFailureKind.Changed, client.clientState.value.certificateFailure!!.kind)
            assertRelayRejectionClosesSession(client, server)
        }
    }

    @Test fun changedKeyOnDesktopBlocksWholeSessionWithoutRetry() = withClient { client ->
        Fixture().use { server ->
            signInPinned(client, server)
            server.replaceCertificate()
            onMain { client.connectDesktop(client.clientState.value.devices.single()) }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(ServerCertificateFailureKind.Changed, client.clientState.value.certificateFailure!!.kind)
            assertRelayRejectionClosesSession(client, server)
        }
    }

    @Test fun changingEndpointDuringTlsCannotApplyTheOldDecision() = withClient { client ->
        val release = CountDownLatch(1)
        Fixture(beforeHandshake = { check(release.await(10, TimeUnit.SECONDS)) }).use { first ->
            Fixture(certificate = first.certificate).use { second ->
                onMain {
                    client.configureServer(first.url)
                    client.setPinnedServerKey(spkiSha256(first.certificate.certificate))
                    client.login(first.url, "fixture-user", "fixture-password")
                }
                await { first.connections.get() == 1 }
                try {
                    onMain { client.login(second.url, "fixture-user", "fixture-password") }
                    await { client.clientState.value.certificateFailure != null }
                } finally { release.countDown() }
                Thread.sleep(500)
                assertEquals(second.url, client.clientState.value.certificateFailure!!.serverAddress)
                assertEquals(ServerCertificateFailureKind.Untrusted, client.clientState.value.certificateFailure!!.kind)
                assertFalse(client.clientState.value.socketConnected)
                assertEquals(0, first.web.requestCount)
                assertEquals(0, second.web.requestCount)
            }
        }
    }

    @Test fun dateFailuresDoNotRetryOrOfferTrust() = withClient { client ->
        val now = System.currentTimeMillis()
        val cases = listOf(
            HeldCertificate.Builder().validityInterval(1, 2).build() to ServerCertificateFailureKind.Expired,
            HeldCertificate.Builder().validityInterval(now + 86_400_000, now + 172_800_000).build() to ServerCertificateFailureKind.NotYetValid,
        )
        for ((certificate, kind) in cases) Fixture(certificate = certificate).use { server ->
            onMain {
                client.configureServer(server.url)
                client.setPinnedServerKey(spkiSha256(certificate.certificate))
                client.login(server.url, "fixture-user", "fixture-password")
            }
            await { client.clientState.value.certificateFailure != null }
            assertEquals(kind, client.clientState.value.certificateFailure!!.kind)
            assertFalse(client.clientState.value.certificateFailure!!.canApprove)
            assertNoRetries(client, server)
        }
    }

    @Test fun switchingProfileDuringTlsCannotApplyOldTrustEvenAtTheSameEndpoint() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val workspace = ConnectionWorkspace.get(application)
        val originalProfile = workspace.profileId
        val firstId = "tls-race-first"
        val secondId = "tls-race-second"
        val release = CountDownLatch(1)
        try {
            Fixture(beforeHandshake = { check(release.await(10, TimeUnit.SECONDS)) }).use { server ->
                onMain {
                    workspace.switchProfile(firstId)
                    workspace.client.configureServer(server.url)
                    workspace.client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
                    workspace.client.login(server.url, "fixture-user", "fixture-password")
                }
                val oldClient = workspace.client
                await { server.connections.get() == 1 }
                onMain {
                    workspace.switchProfile(secondId)
                    workspace.client.login(server.url, "fixture-user", "fixture-password")
                }
                release.countDown()
                await { workspace.client.clientState.value.certificateFailure != null }
                assertNotSame(oldClient, workspace.client)
                assertEquals(ServerCertificateFailureKind.Untrusted, workspace.client.clientState.value.certificateFailure!!.kind)
                assertFalse(oldClient.clientState.value.socketConnected)
                assertNull(oldClient.clientState.value.certificateFailure)
                assertNull(SecureVault(application, firstId).getCredentials(server.url))
                assertEquals(0, server.web.requestCount)
                assertNoRetries(workspace.client, server, connections = 2)
            }
        } finally {
            release.countDown()
            onMain {
                workspace.switchProfile(originalProfile)
                SecureVault(application, firstId).clearCredentials()
                SecureVault(application, secondId).clearCredentials()
            }
        }
    }

    private fun assertRelayRejectionClosesSession(client: MeshCentralClient, server: Fixture) {
        await { server.closedSockets.get() == 1 }
        assertFalse(client.clientState.value.socketConnected)
        val device = client.clientState.value.devices.single()
        onMain {
            client.reconnectDesktop()
            client.connectDesktop(device)
            for (protocol in RelayProtocol.entries) client.createRelayConnection(device, protocol).connect()
        }
        assertNoRetries(client, server, connections = 2, requests = 1)
        assertNotEquals(ConnectionState.Connected, client.desktopState.value.connectionState)
    }

    @Test fun dismissalKeepsTheAutomaticSignInGuard() = withClient { client ->
        Fixture().use { server ->
            onMain { client.login(server.url, "fixture-user", "fixture-password") }
            await { client.clientState.value.certificateFailure != null }
            onMain { client.dismissCertificateFailure() }
            assertNull(client.clientState.value.certificateFailure)
            assertTrue(client.certificateTrustRequired)
            assertNoRetries(client, server)
        }
    }

    private fun signInPinned(client: MeshCentralClient, server: Fixture) {
        onMain {
            client.configureServer(server.url)
            client.setPinnedServerKey(spkiSha256(server.certificate.certificate))
            client.login(server.url, "fixture-user", "fixture-password")
        }
        await { client.clientState.value.devicesCurrent }
        assertEquals(MeshRights.FULL, client.clientState.value.devices.single().rights)
    }

    private fun assertNoRetries(client: MeshCentralClient, server: Fixture, connections: Int = 1, requests: Int = 0) {
        for (recover in listOf(client::retryConnection, client::onNetworkAvailable, client::onAppForeground)) {
            onMain(recover)
            Thread.sleep(250)
            assertEquals(connections, server.connections.get())
        }
        Thread.sleep(2_500)
        assertEquals(connections, server.connections.get())
        assertEquals(requests, server.web.requestCount)
        assertEquals(ConnectionState.Failed, client.clientState.value.connectionState)
        assertTrue(client.clientState.value.requiresSignIn)
    }

    private fun withClient(block: (MeshCentralClient) -> Unit) = withClientAndVault { client, _ -> block(client) }

    private fun withClientAndVault(block: (MeshCentralClient, SecureVault) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("tls-test-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        try { block(client, vault) } finally {
            onMain { client.close(); scope.cancel(); vault.clearCredentials() }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for TLS/control state", condition())
    }

    private class Fixture(
        name: String = "localhost",
        val certificate: HeldCertificate = HeldCertificate.Builder().commonName(name).addSubjectAlternativeName(name).build(),
        val beforeHandshake: () -> Unit = {},
        val requireTwoFactor: Boolean = false,
    ) : AutoCloseable {
        val connections = AtomicInteger()
        val closedSockets = AtomicInteger()
        val cookieRequests = AtomicInteger()
        val rememberedCookies = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val web = MockWebServer()
        val url: String get() = "https://127.0.0.1:${web.port}/"

        init {
            web.serverSocketFactory = object : ServerSocketFactory() {
                override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also { connections.incrementAndGet(); beforeHandshake() }
                }
                override fun createServerSocket(port: Int) = error("Use unbound socket")
                override fun createServerSocket(port: Int, backlog: Int) = error("Use unbound socket")
                override fun createServerSocket(port: Int, backlog: Int, address: InetAddress) = error("Use unbound socket")
            }
            web.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (request.path?.substringBefore('?') == "/control.ashx") {
                            val remembered = request.getHeader("Cookie")?.split("; ")?.contains("twofactor=fixture-remembered") == true
                            rememberedCookies += remembered
                            if (requireTwoFactor && !remembered && request.getHeader("x-meshauth")?.split(',')?.size != 3) {
                                webSocket.send("""{"action":"close","cause":"noauth","msg":"tokenrequired","twoFactorCookieDays":30}""")
                                webSocket.close(1000, null)
                                return
                            }
                            webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture","name":"fixture-user"}}""")
                            webSocket.send("""{"action":"meshes","meshes":[{"_id":"mesh/test/group","name":"Fixture","links":{"user/test/fixture":{"rights":4294967295}}}]}""")
                            webSocket.send("""{"action":"authcookie","cookie":"synthetic-cookie","rcookie":"synthetic-relay"}""")
                            webSocket.send("""{"action":"nodes","nodes":{"mesh/test/group":[{"_id":"node/test/device","meshid":"mesh/test/group","name":"Fixture PC","conn":1,"mtype":2,"agent":{"id":3,"caps":7}}]}}""")
                        } else {
                            webSocket.send("c")
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (requireTwoFactor && org.json.JSONObject(text).optString("action") == "twoFactorCookie") {
                            cookieRequests.incrementAndGet()
                            webSocket.send("""{"action":"twoFactorCookie","cookie":"fixture-remembered"}""")
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closedSockets.incrementAndGet() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closedSockets.incrementAndGet() }
                })
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun replaceCertificate() {
            val replacement = HeldCertificate.Builder().commonName("changed.example").build()
            web.useHttps(HandshakeCertificates.Builder().heldCertificate(replacement).build().sslSocketFactory(), false)
        }

        override fun close() = web.close()
    }
}
