package ru.papajon.meshcentralmobile.notifications

import java.net.InetAddress
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Handshake
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.mesh.tls.ServerTls
import ru.papajon.meshcentralmobile.model.ServerProfile

class NotificationTlsTest {
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val platform = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    @Test fun publiclyTrustedReplacementCannotReceiveBearer() = withServer(
        HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(root).build(),
    ) { server ->
        NotificationHttpClient.forServer(server.url("/"), "00".repeat(32), platform).use { client ->
            val error = runCatching { runBlocking { client.fetch(server.url("/events"), "fixture-token", 0) } }.exceptionOrNull()
            assertTrue(error is SSLException)
            assertEquals(ServerCertificateFailureKind.Changed, error?.serverCertificateFailure()?.kind)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun approvedPrivateKeyReceivesBearerWithoutCookies() {
        val privateCert = HeldCertificate.Builder().commonName("private.example").build()
        withServer(privateCert) { server ->
            val url = server.url("/")
            val profile = ServerProfile("fixture", "Fixture", url.toString(), pinnedSpkiSha256 = spkiSha256(privateCert.certificate))
            val support = NotificationTransport.support(profile) as NotificationTransportSupport.Supported
            NotificationHttpClient.forServer(support.server, support.pinnedSpkiSha256, platform).use { client ->
                runBlocking { assertEquals(0L, client.fetch(server.url("/events"), "fixture-token", 0).cursor) }
                val request = server.takeRequest()
                assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun unapprovedPrivateKeyCannotReceiveBearer() = withServer(HeldCertificate.Builder().build()) { server ->
        NotificationHttpClient.forServer(server.url("/"), null, platform).use { client ->
            val error = runCatching { runBlocking { client.fetch(server.url("/events"), "fixture-token", 0) } }.exceptionOrNull()
            assertEquals(ServerCertificateFailureKind.Untrusted, error?.serverCertificateFailure()?.kind)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun transportDoesNotUseAnotherEndpointsPinOrDebugBypass() {
        val profile = ServerProfile("fixture", "Fixture", "https://mesh.example/", trustSelfSignedCertificates = true)
        assertTrue(NotificationTransport.support(profile, "https://other.example/") is NotificationTransportSupport.Unsupported)
        assertNull((NotificationTransport.support(profile) as NotificationTransportSupport.Supported).pinnedSpkiSha256)
    }

    @Test fun policyChangeAtHandshakeCompletionCannotSendBearer() {
        val certificate = HeldCertificate.Builder().build()
        withServer(certificate) { server ->
            var current = true
            val http = OkHttpClient.Builder()
                .apply { ServerTls(server.url("/"), spkiSha256(certificate.certificate), platform = platform).applyTo(this) }
                .eventListener(object : EventListener() {
                    override fun secureConnectEnd(call: Call, handshake: Handshake?) { current = false }
                }).build()
            NotificationHttpClient(http, isCurrentPolicy = { current }).use { client ->
                val result = runCatching { runBlocking { client.fetch(server.url("/events"), "fixture-token", 0) } }
                assertTrue(result.exceptionOrNull() is NotificationHttpException)
                assertEquals(0, server.requestCount)
            }
        }
    }

    private fun withServer(certificate: HeldCertificate, block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"items":[],"cursor":0,"resetCursor":false}"""))
            server.start(InetAddress.getByName("localhost"), 0)
            block(server)
        }
    }
}
