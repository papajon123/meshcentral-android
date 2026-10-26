package ru.papajon.meshcentralmobile.notifications

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.Socket
import java.security.Principal
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
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
}
