package ru.papajon.meshcentralmobile.mesh.tls

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Real loopback TLS, real JDK chain validation and OkHttp hostname verification. No trust-all mocks. */
class ServerTlsTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(1).build()
    private fun leaf(name: String = "localhost") = HeldCertificate.Builder().commonName(name)
        .addSubjectAlternativeName(name).signedBy(ca).build()

    @Test fun platformTrustedDifferentKeyIsBlockedBeforeSendingHttp() = withServer(leaf()) { web, url ->
        val client = client(url, spkiSha256(leaf().certificate))
        assertFailure(client, url, ServerCertificateFailureKind.Changed)
        assertEquals(0, web.requestCount)
    }

    @Test fun sameKeyAfterRenewalWorksEvenWhenNameDoesNotMatch() {
        val old = leaf()
        val renewed = HeldCertificate.Builder().keyPair(old.keyPair).serialNumber(2)
            .commonName("different.example").addSubjectAlternativeName("different.example").signedBy(ca).build()
        withServer(renewed) { _, url ->
            get(client(url, spkiSha256(old.certificate)), url)
            assertNotEquals(certificateSha256(old.certificate), certificateSha256(renewed.certificate))
        }
    }

    @Test fun trustedChainWithWrongNameAndNoPinIsBlocked() = withServer(leaf("wrong.example")) { web, url ->
        assertFailure(client(url, null), url, ServerCertificateFailureKind.HostnameMismatch)
        assertEquals(0, web.requestCount)
    }

    @Test fun trustedChainAndCorrectNameWithoutPinWorks() = withServer(leaf()) { _, url -> get(client(url, null), url) }

    @Test fun platformTrustedChainIgnoresAnUnrelatedExtraCertificate() {
        checkPlatformChain(leaf().certificate, ca.certificate, HeldCertificate.Builder().build().certificate)
    }

    @Test fun platformBuildsAPathFromReorderedIntermediates() {
        val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(ca).build()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .signedBy(intermediate).build()
        checkPlatformChain(cert.certificate, ca.certificate, intermediate.certificate)
    }

    // A JDK server KeyStore rejects reordered/unrelated certificates before serving them. Exercise
    // the actual trust-manager boundary with the same real platform path builder instead.
    private fun checkPlatformChain(vararg chain: java.security.cert.X509Certificate) {
        val endpoint = "https://localhost/".toHttpUrl()
        val manager = ServerPinTrustManager(endpoint, null, endpoint.toString(), false,
            HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build().trustManager)
        assertEquals(chain.first(), manager.checkServerTrusted(arrayOf(*chain), "ECDHE_ECDSA", endpoint.host).first())
    }

    @Test fun platformTrustedPathIgnoresAnExpiredCrossSignedCertificate() {
        val oldRoot = HeldCertificate.Builder().certificateAuthority(2).build()
        val expiredCrossSign = HeldCertificate.Builder().keyPair(ca.keyPair)
            .commonName(ca.certificate.subjectX500Principal.name.removePrefix("CN="))
            .certificateAuthority(1).signedBy(oldRoot).validityInterval(1, 2).build()
        withServer(leaf(), arrayOf(expiredCrossSign.certificate)) { _, url -> get(client(url, null), url) }
    }

    @Test fun pinnedSelfSignedWorksWithARealHandshake() {
        val cert = HeldCertificate.Builder().commonName("self.example").build()
        withServer(cert) { _, url -> get(client(url, spkiSha256(cert.certificate)), url) }
    }

    @Test fun debugDisabledDoesNotAcceptAnUnknownKey() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        withServer(cert) { web, url ->
            assertFailure(client(url, null), url, ServerCertificateFailureKind.Untrusted)
            assertEquals(0, web.requestCount)
        }
    }

    @Test fun anotherTlsSessionCannotReuseAPreviousPinMatch() = withServer(leaf()) { _, url ->
        val first = leaf()
        val other = leaf()
        val client = client(url, spkiSha256(first.certificate))
        fun session(cert: java.security.cert.X509Certificate) = java.lang.reflect.Proxy.newProxyInstance(
            javax.net.ssl.SSLSession::class.java.classLoader, arrayOf(javax.net.ssl.SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf(cert)
                "getPeerPort" -> url.port
                else -> error("Unexpected session operation: ${method.name}")
            }
        } as javax.net.ssl.SSLSession
        assertTrue(client.hostnameVerifier.verify(url.host, session(first.certificate)))
        val error = runCatching { client.hostnameVerifier.verify(url.host, session(other.certificate)) }.exceptionOrNull()
        assertEquals(ServerCertificateFailureKind.Changed, error?.serverCertificateFailure()?.kind)
        assertFalse(client.hostnameVerifier.verify("other.example", session(first.certificate)))
    }

    @Test fun expiredMatchingKeyHasADistinctNonApprovableFailure() {
        val cert = HeldCertificate.Builder().validityInterval(1, 2).build()
        withServer(cert) { web, url ->
            assertFailure(client(url, spkiSha256(cert.certificate)), url, ServerCertificateFailureKind.Expired)
            assertEquals(0, web.requestCount)
        }
    }

    @Test fun futureMatchingKeyHasADistinctNonApprovableFailure() {
        val start = System.currentTimeMillis() + 86_400_000
        val cert = HeldCertificate.Builder().validityInterval(start, start + 86_400_000).build()
        withServer(cert) { _, url -> assertFailure(client(url, spkiSha256(cert.certificate)), url, ServerCertificateFailureKind.NotYetValid) }
    }

    @Test fun unknownIssuerRequiresExplicitPinBeforeSendingHttp() {
        val unknownCa = HeldCertificate.Builder().certificateAuthority(1).build()
        val cert = HeldCertificate.Builder().signedBy(unknownCa).build()
        withServer(cert) { web, url ->
            assertFailure(client(url, null), url, ServerCertificateFailureKind.Untrusted)
            assertEquals(0, web.requestCount)
            get(client(url, spkiSha256(cert.certificate)), url)
            assertEquals(1, web.requestCount)
        }
    }

    @Test fun changedKeyWithUnknownIssuerRequiresExplicitReplacement() {
        val unknownCa = HeldCertificate.Builder().certificateAuthority(1).build()
        val cert = HeldCertificate.Builder().signedBy(unknownCa).build()
        withServer(cert) { _, url ->
            assertFailure(client(url, "00".repeat(32)), url, ServerCertificateFailureKind.Changed)
        }
    }

    @Test fun brokenPresentedChainCannotPassTlsEvenWithMatchingPin() {
        val signer = HeldCertificate.Builder().commonName("Private example CA").certificateAuthority(0).build()
        val wrongKey = HeldCertificate.Builder().commonName("Private example CA").certificateAuthority(0).build()
        val cert = HeldCertificate.Builder().commonName("localhost").signedBy(signer).build()
        withServer(cert, arrayOf(wrongKey.certificate)) { web, url ->
            for (pin in listOf(null, spkiSha256(cert.certificate))) {
                assertFailure(client(url, pin), url, ServerCertificateFailureKind.ServerConfiguration)
            }
            assertEquals(0, web.requestCount)
        }
    }

    @Test fun pinCannotBeUsedForAnotherHostOrPort() = withServer(leaf()) { web, url ->
        for (other in listOf(url.newBuilder().host("127.0.0.1").build(), url.newBuilder().port(url.port + 1).build())) {
            val result = runCatching { get(client(url, spkiSha256(leaf().certificate)), other) }
            assertTrue(result.exceptionOrNull() is javax.net.ssl.SSLPeerUnverifiedException)
        }
        assertEquals(0, web.requestCount)
    }

    private fun client(url: HttpUrl, pin: String?) = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .apply { ServerTls(url, pin, platform = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build().trustManager).applyTo(this) }
        .build()

    private fun get(client: OkHttpClient, url: HttpUrl) {
        try { client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) } }
        finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    private fun assertFailure(client: OkHttpClient, url: HttpUrl, kind: ServerCertificateFailureKind) {
        val error = runCatching { get(client, url) }.exceptionOrNull()
        assertTrue("Expected a TLS failure, got $error", error is IOException)
        val failure = error!!.serverCertificateFailure()
        assertEquals(kind, failure?.kind)
        assertEquals(kind == ServerCertificateFailureKind.Changed || kind == ServerCertificateFailureKind.Untrusted, failure!!.canApprove)
    }

    private fun withServer(
        certificate: HeldCertificate,
        chain: Array<java.security.cert.X509Certificate> = emptyArray(),
        block: (MockWebServer, HttpUrl) -> Unit,
    ) {
        MockWebServer().use { web ->
            web.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate, *chain).build().sslSocketFactory(), false)
            web.enqueue(MockResponse().setBody("fixture"))
            web.start(InetAddress.getByName("localhost"), 0)
            block(web, web.url("/").newBuilder().host("localhost").build())
        }
    }
}
