package ru.papajon.meshcentralmobile.mesh.tls

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/**
 * The expected key imported from a backup is confirmation metadata, not a trust grant.
 * Platform trust must never bypass that distinction, and invalid certificates stay unapprovable.
 */
class ExpectedServerKeyTest {
    private val address = "https://mesh.example/"
    private val certificate = HeldCertificate.Builder().commonName("mesh.example")
        .addSubjectAlternativeName("mesh.example").build().certificate
    private val actual = spkiSha256(certificate)

    @Test fun platformTrustDoesNotWaiveMatchingOrMismatchingExpectedKey() {
        for (expected in listOf(actual, "00".repeat(32))) {
            val result = evaluateServerTrust(true, certificate, null, address, expected)
            assertTrue("$expected: $result", result is ServerTrustDecision.Rejected)
            val failure = (result as ServerTrustDecision.Rejected).failure
            assertEquals(ServerCertificateFailureKind.ExpectedKeyConfirmation, failure.kind)
            assertEquals(expected, failure.expectedSpkiSha256)
            assertEquals(actual, failure.spkiSha256)
            assertEquals(address, failure.serverAddress)
            assertNull(failure.previousSpkiSha256)
            assertTrue(failure.canApprove)
        }
    }

    @Test fun platformTrustWithoutAnExpectedKeyStillAcceptsTheLeaf() {
        assertEquals(ServerTrustDecision.PlatformTrusted, evaluateServerTrust(true, certificate, null, address))
    }

    @Test fun expectedKeyConfirmationCarriesThePrivateChainIssuerExplanation() {
        listOf(
            "MeshCentralRoot-fixture" to ServerCertificateIssuerExplanation.MeshCentralRoot,
            "Private example CA" to ServerCertificateIssuerExplanation.UnknownAuthority,
        ).forEach { (commonName, explanation) ->
            val issuer = HeldCertificate.Builder().commonName(commonName).certificateAuthority(0).build()
            val leaf = HeldCertificate.Builder().commonName("mesh.example").signedBy(issuer).build().certificate
            for (expected in listOf(spkiSha256(leaf), "00".repeat(32))) {
                val failure = rejectedWithoutPlatformTrust(leaf, expected)
                assertEquals(commonName, ServerCertificateFailureKind.ExpectedKeyConfirmation, failure.kind)
                assertEquals(commonName, explanation, failure.issuerExplanation)
                assertEquals(expected, failure.expectedSpkiSha256)
                assertEquals(spkiSha256(leaf), failure.spkiSha256)
                assertTrue(failure.canApprove)
            }
        }
    }

    @Test fun selfSignedExpectedKeyConfirmationStillHasNoIssuerExplanation() {
        for (expected in listOf(actual, "00".repeat(32))) {
            val failure = rejectedWithoutPlatformTrust(certificate, expected)
            assertEquals(ServerCertificateFailureKind.ExpectedKeyConfirmation, failure.kind)
            assertNull(failure.issuerExplanation)
        }
    }

    @Test fun expiredCertificateWithAnExpectedKeyIsNotApprovable() {
        val expired = HeldCertificate.Builder().validityInterval(1, 2).build().certificate
        val failure = rejected(expired, spkiSha256(expired))
        assertEquals(ServerCertificateFailureKind.Expired, failure.kind)
        assertFalse(failure.canApprove)
    }

    @Test fun weakCertificateWithAnExpectedKeyIsNotApprovable() {
        val weak = fixture("weak-rsa")
        weak.verify(weak.publicKey) // Real signature; only the key strength forbids approval.
        val failure = rejected(weak, spkiSha256(weak))
        assertEquals(ServerCertificateFailureKind.ServerConfiguration, failure.kind)
        assertFalse(failure.canApprove)
    }

    private fun rejected(leaf: X509Certificate, expected: String): ServerCertificateFailure =
        (evaluateServerTrust(true, leaf, null, address, expected) as ServerTrustDecision.Rejected).failure

    private fun rejectedWithoutPlatformTrust(leaf: X509Certificate, expected: String): ServerCertificateFailure =
        (evaluateServerTrust(false, leaf, null, address, expected) as ServerTrustDecision.Rejected).failure

    private fun fixture(name: String): X509Certificate = requireNotNull(
        javaClass.getResourceAsStream("/tls/$name.crt"),
    ).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
}
