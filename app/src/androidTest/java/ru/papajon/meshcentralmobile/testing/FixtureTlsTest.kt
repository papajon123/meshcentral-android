package ru.papajon.meshcentralmobile.testing

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_HOST = "tls.example.invalid"

class FixtureTlsTest {
    private fun certificate() = HeldCertificate.Builder()
        .commonName(TEST_HOST)
        .addSubjectAlternativeName(TEST_HOST)
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    private fun clientFor(certificate: HeldCertificate): OkHttpClient {
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            // Legacy Android providers may omit SNI for single-label localhost.
            // Use a real DNS-shaped name while keeping the fixture entirely on loopback.
            .dns { host ->
                check(host == TEST_HOST)
                listOf(InetAddress.getByName("127.0.0.1"))
            }
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun close(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun hostnameNegotiatesHttp2AndRecordsSni() {
        val certificate = certificate()
        val server = MockWebServer()
        val client = clientFor(certificate)
        try {
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(certificate).build()
                    .fixtureServerSocketFactory(),
            )
            server.enqueue(MockResponse(body = "localhost-fixture"))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            client.newCall(Request.Builder().url("https://$TEST_HOST:${server.port}/").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(Protocol.HTTP_2, response.protocol)
                assertNotNull(response.handshake)
                assertEquals("localhost-fixture", requireNotNull(response.body).string())
            }
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(
                "Expected fixture hostname SNI, got ${request.handshakeServerNames}",
                request.handshakeServerNames.contains(TEST_HOST),
            )
        } finally {
            close(client)
            server.close()
        }
    }

    @Test fun loopbackAddressOmitsSniAndRemainsEncrypted() {
        val certificate = certificate()
        val server = MockWebServer()
        val client = clientFor(certificate)
        try {
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(certificate).build()
                    .fixtureServerSocketFactory(),
            )
            server.enqueue(MockResponse(body = "loopback-fixture"))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            client.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(Protocol.HTTP_2, response.protocol)
                assertNotNull(response.handshake)
                assertEquals("loopback-fixture", requireNotNull(response.body).string())
            }
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), request.handshakeServerNames)
        } finally {
            close(client)
            server.close()
        }
    }
}
