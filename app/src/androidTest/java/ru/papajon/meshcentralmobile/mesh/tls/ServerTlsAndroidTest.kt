package ru.papajon.meshcentralmobile.mesh.tls

import android.net.http.X509TrustManagerExtensions
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Exercises the reflective Android API that OkHttp's Android chain cleaner actually uses. */
class ServerTlsAndroidTest {
    private val endpoint = "https://mesh.example/".toHttpUrl()
    private val root = HeldCertificate.Builder().certificateAuthority(1).build()
    private val leaf = HeldCertificate.Builder().signedBy(root).build().certificate
    private val platform = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    private fun extensions(pin: String?) = X509TrustManagerExtensions(
        ServerPinTrustManager(endpoint, pin, endpoint.toString(), false, platform),
    )

    @Test fun androidChainCleanerAcceptsMatchingPinnedLeafWithoutKnownIssuer() {
        val cert = HeldCertificate.Builder().build().certificate
        assertEquals(listOf(cert), extensions(spkiSha256(cert)).checkServerTrusted(arrayOf(cert), "RSA", endpoint.host))
    }

    @Test fun androidChainCleanerRejectsDifferentKeyEvenWithTrustedIssuer() {
        val error = runCatching { extensions("00".repeat(32)).checkServerTrusted(arrayOf(leaf), "RSA", endpoint.host) }.exceptionOrNull()
        assertEquals(ServerCertificateFailureKind.Changed, error?.serverCertificateFailure()?.kind)
    }

    @Test fun androidChainCleanerRetainsPlatformValidationWithoutPin() {
        val chain = extensions(null).checkServerTrusted(arrayOf(leaf), "RSA", endpoint.host)
        assertEquals(leaf, chain.first())
        assertEquals(root.certificate, chain.last())
    }

    @Test fun androidBuildsTrustedPathDespiteUnrelatedExtraCertificate() {
        val extra = HeldCertificate.Builder().build().certificate
        val chain = extensions(null).checkServerTrusted(arrayOf(leaf, root.certificate, extra), "RSA", endpoint.host)
        assertEquals(listOf(leaf, root.certificate), chain)
    }

    @Test fun androidBuildsTrustedPathDespiteReorderedIntermediates() {
        val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(root).build()
        val cert = HeldCertificate.Builder().signedBy(intermediate).build().certificate
        val chain = extensions(null).checkServerTrusted(arrayOf(cert, root.certificate, intermediate.certificate), "RSA", endpoint.host)
        assertEquals(listOf(cert, intermediate.certificate, root.certificate), chain)
    }

    @Test fun androidBuildsTrustedPathDespiteExpiredCrossCertificate() {
        val otherRoot = HeldCertificate.Builder().certificateAuthority(2).build()
        val expired = HeldCertificate.Builder().keyPair(root.keyPair).certificateAuthority(1)
            .commonName(root.certificate.subjectX500Principal.name.removePrefix("CN="))
            .signedBy(otherRoot).validityInterval(1, 2).build().certificate
        val chain = extensions(null).checkServerTrusted(arrayOf(leaf, expired), "RSA", endpoint.host)
        assertEquals(listOf(leaf, root.certificate), chain)
    }

    @Test fun androidChainCleanerRejectsPinForAnotherHost() {
        assertNotNull(runCatching {
            extensions(spkiSha256(leaf)).checkServerTrusted(arrayOf(leaf), "RSA", "other.example")
        }.exceptionOrNull())
    }

    @Test fun androidChainCleanerRejectsExpiredPin() {
        val cert = HeldCertificate.Builder().validityInterval(1, 2).build().certificate
        val error = runCatching { extensions(spkiSha256(cert)).checkServerTrusted(arrayOf(cert), "RSA", endpoint.host) }.exceptionOrNull()
        assertEquals(ServerCertificateFailureKind.Expired, error?.serverCertificateFailure()?.kind)
    }
}
