package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.ServerSocketFactory
import java.net.Socket
import java.security.Principal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.backup.BackupProfile
import ru.papajon.meshcentralmobile.backup.SettingsBackup
import ru.papajon.meshcentralmobile.backup.SettingsBackupRepository
import ru.papajon.meshcentralmobile.integration.LauncherCoordinator
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

class NotificationTlsPollerTest {
    @Test fun changingTrustDuringHandshakeCancelsBearerWithoutDisablingNewPolicy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val cert = HeldCertificate.Builder().commonName("private.example").build()
        val handshaking = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val keys = HandshakeCertificates.Builder().heldCertificate(cert).build().keyManager
        val delayedKeys = object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
                val alias = keys.chooseServerAlias(type, issuers, socket)
                if (alias != null) { handshaking.countDown(); check(resume.await(15, TimeUnit.SECONDS)) }
                return alias
            }
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = keys.chooseClientAlias(types, issuers, socket)
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = keys.getServerAliases(type, issuers)
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = keys.getClientAliases(type, issuers)
            override fun getCertificateChain(alias: String?) = keys.getCertificateChain(alias)
            override fun getPrivateKey(alias: String?) = keys.getPrivateKey(alias)
        }
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(delayedKeys), null, null) }
        MockWebServer().use { server ->
            server.useHttps(tls.socketFactory, false)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"items":[],"cursor":0,"resetCursor":false}"""))
            server.start()
            val profile = settings.createProfile("TLS race fixture", server.url("/").toString())
            val token = NotificationToken("mcn_0123456789abcdef", "T".repeat(43), "/mobilecompanion/events",
                System.currentTimeMillis() + 86_400_000, "user//fixture")
            val store = requireNotNull(NotificationStore.open(context, profile.id, profile.serverUrl, token.userId))
            try {
                settings.activateProfile(profile.id)
                settings.updatePinnedServerKey(spkiSha256(cert.certificate))
                SecureVault(context, profile.id).saveScopedSecret(NotificationContract.LABEL, profile.serverUrl,
                    requireNotNull(NotificationCodec.encodeToken(token)))
                store.setEnabled(true)
                val polling = async(Dispatchers.IO) { NotificationPoller(context, profile.id).poll() }
                assertTrue("TLS must be in flight", handshaking.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { settings.updatePinnedServerKey("22".repeat(32)) }
                resume.countDown()
                assertEquals(NotificationPollResult.DISABLED, polling.await())
                assertEquals("An obsolete key must receive no HTTP request", 0, server.requestCount)
                assertTrue("The obsolete poll must not disable the new policy", store.isEnabled())
                assertNull(store.lastError())
            } finally {
                resume.countDown()
                NotificationController.disableLocal(context, profile.id)
                NotificationStore.clearProfile(context, profile.id)
                settings.removeProfile(profile.id)
            }
        }
    }

    @Test fun changedKeyStopsPollingUntilExplicitTrustDecision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context)
        val cert = HeldCertificate.Builder().commonName("private.example").build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.start()
            val profile = settings.createProfile("TLS notification fixture", server.url("/").toString())
            val now = System.currentTimeMillis()
            val vault = SecureVault(context, profile.id)
            val token = NotificationToken("mcn_0123456789abcdef", "T".repeat(43), "/mobilecompanion/events", now + 86_400_000, "user//fixture")
            val store = requireNotNull(NotificationStore.open(context, profile.id, profile.serverUrl, token.userId))
            try {
                settings.activateProfile(profile.id)
                settings.updatePinnedServerKey("00".repeat(32))
                vault.saveScopedSecret(NotificationContract.LABEL, profile.serverUrl, requireNotNull(NotificationCodec.encodeToken(token)))
                store.setEnabled(true)
                val poller = NotificationPoller(context, profile.id)
                assertEquals(NotificationPollResult.UNAVAILABLE, poller.poll())
                assertFalse(store.isEnabled())
                assertEquals(NotificationError.TLS_UNTRUSTED, store.lastError())
                assertEquals(0, server.requestCount)
                assertEquals(NotificationPollResult.DISABLED, poller.poll())
                assertEquals(0, server.requestCount)

                // The same poller must read current trust, not retain an old SSLContext or pool.
                settings.updatePinnedServerKey(spkiSha256(cert.certificate))
                store.setEnabled(true)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[],"cursor":0,"resetCursor":false}"""))
                assertEquals(NotificationPollResult.SUCCESS, poller.poll())
                assertEquals(1, server.requestCount)
                assertEquals("Bearer ${token.token}", server.takeRequest().getHeader("Authorization"))
            } finally {
                NotificationController.disableLocal(context, profile.id)
                NotificationStore.clearProfile(context, profile.id)
                settings.removeProfile(profile.id)
            }
        }
    }

    @Test fun importedExpectedKeyRejectsPollBeforeTlsAndHttp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val originalProfileId = settings.activeProfile.id
        val originalPreferences = PREFERENCE_NAMES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val expectedKey = spkiSha256(certificate.certificate)
        val tcpConnections = AtomicInteger()
        val server = MockWebServer()
        server.serverSocketFactory = countingSocketFactory(tcpConnections)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build()
            .sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        var importedId: String? = null
        try {
            val profileName = "Imported expected key ${UUID.randomUUID()}"
            val candidate = BackupProfile(
                ServerProfile(
                    id = "expected-key-profile",
                    name = profileName,
                    serverUrl = "https://127.0.0.1:${server.port}/",
                    onboardingCompleted = true,
                    pinnedSpkiSha256 = expectedKey,
                ),
            )
            val imported = SettingsBackupRepository(settings)
                .importConfirmed(SettingsBackup(listOf(candidate)), applyPreferences = false, skipDuplicates = false)
            assertEquals(1, imported)
            val id = settings.profilesState.value.first { it.name == profileName }.id
            importedId = id
            // The importer must downgrade the pinned metadata to an unapproved expected key.
            val profile = requireNotNull(settings.currentProfile(id))
            assertNull(profile.pinnedSpkiSha256)
            assertEquals(expectedKey, profile.expectedSpkiSha256)

            val token = NotificationToken(
                tokenId = "mcn_0123456789abcdef",
                token = "T".repeat(43),
                endpoint = "/mobilecompanion/events",
                expiresAt = System.currentTimeMillis() + 86_400_000,
                userId = "user//expected-key",
            )
            SecureVault(context, profile.id).saveScopedSecret(
                NotificationContract.LABEL,
                profile.serverUrl,
                requireNotNull(NotificationCodec.encodeToken(token)),
            )
            val store = requireNotNull(NotificationStore.open(context, profile.id, profile.serverUrl, token.userId))
            store.setEnabled(true)
            assertTrue(store.isEnabled())

            assertEquals(NotificationPollResult.UNAVAILABLE, NotificationPoller(context, profile.id).poll())
            assertEquals("The bearer token must not reach HTTP", 0, server.requestCount)
            assertEquals("An expected imported key must not even open TCP/TLS", 0, tcpConnections.get())
            assertEquals(NotificationError.TLS_UNTRUSTED, store.lastError())
            assertFalse("The poll must stop until the user approves the imported key", store.isEnabled())
        } finally {
            importedId?.let { id ->
                runCatching { NotificationController.disableLocal(context, id) }
                runCatching { NotificationStore.clearProfile(context, id) }
                runCatching { SecureVault(context, id).clearCredentials() }
                runCatching { SecureVault(context, id).clearLoginKey() }
                runCatching { SecureVault(context, id).clearScopedSecret(NotificationContract.LABEL) }
                runCatching { SettingsRepository(context).removeProfile(id) }
                runCatching { LauncherCoordinator.awaitIdle() }
            }
            runCatching { server.close() }
            PREFERENCE_NAMES.forEach { name ->
                restorePreferences(context, name, originalPreferences.getValue(name))
            }
        }
        assertEquals(originalProfileId, SettingsRepository(context).activeProfile.id)
    }

    private fun countingSocketFactory(connections: AtomicInteger): ServerSocketFactory =
        object : ServerSocketFactory() {
            override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                override fun accept(): Socket = super.accept().also { connections.incrementAndGet() }
            }

            override fun createServerSocket(port: Int): ServerSocket =
                error("MockWebServer must bind the unbound counting socket")

            override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
                error("MockWebServer must bind the unbound counting socket")

            override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket =
                error("MockWebServer must bind the unbound counting socket")
        }

    private fun restorePreferences(context: Context, name: String, values: Map<String, *>) {
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

    private companion object {
        private val PREFERENCE_NAMES = listOf(
            "app_settings",
            "secure_vault",
            "launcher_devices",
            "widget_auth_gate",
            "widget_status",
            "notification_state",
        )
    }
}
