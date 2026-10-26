package ru.papajon.meshcentralmobile.mesh.tls

import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class ServerTrustTest {
    private val certificate = HeldCertificate.Builder().commonName("mesh.example").build().certificate
    private val address = "https://mesh.example/"
    // Independent library calculation: OkHttp pins SHA-256 of SPKI, encoded as base64.
    private val pin = Base64.getDecoder().decode(CertificatePinner.pin(certificate).removePrefix("sha256/"))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun acceptsPlatformTrustedChain() {
        assertEquals(ServerTrustDecision.PlatformTrusted, evaluateServerTrust(true, certificate, null, address))
    }

    @Test fun platformTrustWithoutPinChecksOnlyLeafDates() {
        val expiredExtra = HeldCertificate.Builder().validityInterval(1, 2).build().certificate
        assertEquals(ServerTrustDecision.PlatformTrusted,
            evaluateServerTrust(true, listOf(certificate, expiredExtra), null, address))
        assertEquals(ServerCertificateFailureKind.Expired,
            (evaluateServerTrust(true, listOf(expiredExtra, certificate), null, address) as ServerTrustDecision.Rejected).failure.kind)
    }

    @Test fun rejectsUnknownSelfSignedWithoutPin() {
        val result = evaluateServerTrust(false, certificate, null, address) as ServerTrustDecision.Rejected
        assertEquals(ServerCertificateFailureKind.Untrusted, result.failure.kind)
        assertEquals(pin, result.failure.spkiSha256)
        assertNull(result.failure.previousSpkiSha256)
        assertEquals(certificate.subjectX500Principal.name, result.failure.subject)
        assertEquals(certificate.notBefore.time, result.failure.notBeforeMillis)
        assertEquals(certificate.notAfter.time, result.failure.notAfterMillis)
        assertEquals(address, result.failure.serverAddress)
    }

    @Test fun acceptsPinnedSelfSigned() {
        assertEquals(ServerTrustDecision.PinnedKey, evaluateServerTrust(false, certificate, pin, address))
    }

    @Test fun meshCentralRootSignedLeafIsApprovable() {
        val root = HeldCertificate.Builder().commonName("MeshCentralRoot-fixture").certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().commonName("mesh.example").signedBy(root).build().certificate
        val result = evaluateServerTrust(false, leaf, null, address) as ServerTrustDecision.Rejected
        assertEquals(ServerCertificateFailureKind.Untrusted, result.failure.kind)
        assertTrue(result.failure.canApprove)
        assertEquals(spkiSha256(leaf), result.failure.spkiSha256)
        assertEquals(ServerCertificateIssuerExplanation.MeshCentralRoot, result.failure.issuerExplanation)
    }

    @Test fun unknownIssuerShowsExplanation() {
        val root = HeldCertificate.Builder().commonName("Private example CA").certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).build().certificate
        val failure = rejected(listOf(leaf))
        assertEquals(ServerCertificateFailureKind.Untrusted, failure.kind)
        assertEquals(ServerCertificateIssuerExplanation.UnknownAuthority, failure.issuerExplanation)
        assertTrue(failure.canApprove)
        assertNull(rejected(listOf(certificate)).issuerExplanation)
    }

    @Test fun weakKeysSignaturesAndNonServerEkuCannotBeApprovedOrPinned() {
        for (name in listOf("weak-rsa", "weak-ec", "md5-leaf", "sha1-leaf", "client-auth-only")) {
            val cert = fixture(name)
            // SunEC itself rejects P-224; the other fixtures have verifiable signatures.
            if (name != "weak-ec") cert.verify(cert.publicKey)
            for (pin in listOf(null, spkiSha256(cert), "00".repeat(32))) {
                val failure = rejected(listOf(cert), pin)
                assertEquals(name, ServerCertificateFailureKind.ServerConfiguration, failure.kind)
                assertFalse(name, failure.canApprove)
            }
        }
    }

    @Test fun strongKeysAndServerAuthAreApprovableWithoutPlatformTrust() {
        for (name in listOf("strong-rsa", "strong-ec", "server-auth")) {
            val cert = fixture(name)
            assertEquals(name, ServerCertificateFailureKind.Untrusted, rejected(listOf(cert)).kind)
            assertEquals(ServerTrustDecision.PinnedKey, evaluateServerTrust(false, cert, spkiSha256(cert), address))
        }
    }

    @Test fun weakEcLeafWithValidStrongIssuerSignatureCannotBeApproved() {
        // The issuer signs the weak leaf with RSA-2048, so SunEC's inability to verify
        // P-224 signatures cannot make this test pass by accident.
        val chain = requireNotNull(javaClass.getResourceAsStream("/tls/weak-ec-strong-issuer-chain.crt")).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).map { cert -> cert as X509Certificate }
        }
        val weakLeaf = chain.first()
        weakLeaf.verify(chain[1].publicKey)
        for (pin in listOf(null, spkiSha256(weakLeaf))) {
            assertEquals(ServerCertificateFailureKind.ServerConfiguration, rejected(listOf(weakLeaf), pin).kind)
        }
    }

    @Test fun approvalReportsHostnameMismatchUsingSanRatherThanSubjectName() {
        val matching = HeldCertificate.Builder().commonName("misleading.example").addSubjectAlternativeName("mesh.example").build().certificate
        val wrong = HeldCertificate.Builder().commonName("mesh.example").addSubjectAlternativeName("other.example").build().certificate
        assertFalse(rejected(listOf(matching)).hostnameMismatch)
        assertTrue(rejected(listOf(wrong)).hostnameMismatch)
        assertTrue(rejected(listOf(wrong), "00".repeat(32)).hostnameMismatch)
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/tls/$name.crt")).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    @Test fun brokenChainSignatureIsServerConfiguration() {
        val issuer = HeldCertificate.Builder().commonName("Private example CA").certificateAuthority(0).build()
        val wrongKey = HeldCertificate.Builder().commonName("Private example CA").certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(issuer).build().certificate
        for (pin in listOf(null, spkiSha256(leaf))) {
            val failure = rejected(listOf(leaf, wrongKey.certificate), pin)
            assertEquals(ServerCertificateFailureKind.ServerConfiguration, failure.kind)
            assertFalse(failure.canApprove)
        }
    }

    @Test fun brokenSelfSignatureCannotBeApprovedOrPinned() {
        val bytes = certificate.encoded
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val broken = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        for (pin in listOf(null, spkiSha256(broken))) {
            assertEquals(ServerCertificateFailureKind.ServerConfiguration, rejected(listOf(broken), pin).kind)
        }
    }

    @Test fun consistentPrivateChainWithOrWithoutRootIsApprovable() {
        val root = HeldCertificate.Builder().certificateAuthority(1).build()
        val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(root).build()
        val leaf = HeldCertificate.Builder().signedBy(intermediate).build().certificate
        for (chain in listOf(listOf(leaf, intermediate.certificate), listOf(leaf, intermediate.certificate, root.certificate))) {
            assertEquals(ServerCertificateFailureKind.Untrusted, rejected(chain).kind)
            assertEquals(ServerTrustDecision.PinnedKey, evaluateServerTrust(false, chain, spkiSha256(leaf), address))
        }
    }

    @Test fun nonCaIssuerAndExceededPathLengthCannotBeApproved() {
        for (root in listOf(HeldCertificate.Builder().build(), HeldCertificate.Builder().certificateAuthority(0).build())) {
            val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(root).build()
            val leaf = HeldCertificate.Builder().signedBy(intermediate).build().certificate
            val failure = rejected(listOf(leaf, intermediate.certificate, root.certificate))
            assertEquals(ServerCertificateFailureKind.ServerConfiguration, failure.kind)
            assertFalse(failure.canApprove)
        }
    }

    @Test fun issuerWithoutKeyCertSignCannotBeApproved() {
        val chain = requireNotNull(javaClass.getResourceAsStream("/tls/invalid-key-usage-chain.crt")).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).map { cert -> cert as X509Certificate }
        }
        chain[0].verify(chain[1].publicKey) // Real, valid signature; only issuer usage forbids signing.
        assertTrue(chain[1].basicConstraints >= 0)
        assertFalse(chain[1].keyUsage[5])
        assertEquals(ServerCertificateFailureKind.ServerConfiguration, rejected(chain).kind)
    }

    @Test fun issuerNameMustMatchEvenIfTheKeyMatches() {
        val issuer = HeldCertificate.Builder().commonName("Original CA").certificateAuthority(0).build()
        val renamed = HeldCertificate.Builder().commonName("Other CA").keyPair(issuer.keyPair).certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(issuer).build().certificate
        leaf.verify(renamed.certificate.publicKey)
        assertEquals(ServerCertificateFailureKind.ServerConfiguration, rejected(listOf(leaf, renamed.certificate)).kind)
    }

    @Test fun matchingPinDoesNotBypassExpiredIssuer() {
        val issuer = HeldCertificate.Builder().certificateAuthority(0).validityInterval(1, 2).build()
        val leaf = HeldCertificate.Builder().signedBy(issuer).build().certificate
        val failure = rejected(listOf(leaf, issuer.certificate), spkiSha256(leaf))
        assertEquals(ServerCertificateFailureKind.Expired, failure.kind)
        assertFalse(failure.canApprove)
    }

    private fun rejected(chain: List<X509Certificate>, pin: String? = null) =
        (evaluateServerTrust(false, chain, pin, address) as ServerTrustDecision.Rejected).failure

    @Test fun rejectsChangedCertificateAfterPin() {
        val other = HeldCertificate.Builder().commonName("mesh.example").build().certificate
        val result = evaluateServerTrust(false, other, pin, address) as ServerTrustDecision.Rejected
        assertEquals(ServerCertificateFailureKind.Changed, result.failure.kind)
        assertNotEquals(pin, result.failure.spkiSha256)
        assertEquals(pin, result.failure.previousSpkiSha256)
    }

    @Test fun formatsFingerprintInPairs() {
        val value = "0123456789abcdef".repeat(4)
        assertEquals(List(4) { "01:23:45:67:89:ab:cd:ef" }.joinToString(":"), formatFingerprint(value))
    }

    @Test fun trustedChainCannotBypassAnExistingPin() {
        val result = evaluateServerTrust(true, certificate, "00".repeat(32), address) as ServerTrustDecision.Rejected
        assertEquals(ServerCertificateFailureKind.Changed, result.failure.kind)
        assertEquals("00".repeat(32), result.failure.previousSpkiSha256)
    }

    @Test fun matchingPinAlsoAppliesToATrustedChain() {
        assertEquals(ServerTrustDecision.PinnedKey, evaluateServerTrust(true, certificate, pin, address))
    }

    @Test fun expiredCertificateCannotBeAcceptedEvenWithMatchingPin() {
        val expired = HeldCertificate.Builder().validityInterval(1, 2).build().certificate
        assertTrue(evaluateServerTrust(false, expired, spkiSha256(expired), address) is ServerTrustDecision.Rejected)
    }

    @Test fun notYetValidCertificateCannotBeAcceptedEvenWithMatchingPin() {
        val start = System.currentTimeMillis() + 86_400_000
        val future = HeldCertificate.Builder().validityInterval(start, start + 86_400_000).build().certificate
        assertTrue(evaluateServerTrust(false, future, spkiSha256(future), address) is ServerTrustDecision.Rejected)
    }

    @Test fun pinningUsesTheLeafKeyNotItsIssuer() {
        val issuer = HeldCertificate.Builder().certificateAuthority(1).build()
        val leaf = HeldCertificate.Builder().signedBy(issuer).build()
        assertTrue(evaluateServerTrust(false, leaf.certificate, spkiSha256(issuer.certificate), address) is ServerTrustDecision.Rejected)
    }

    @Test fun renewalWithTheSameKeyRetainsTrust() {
        val original = HeldCertificate.Builder().build()
        val renewed = HeldCertificate.Builder().keyPair(original.keyPair).serialNumber(2).build()
        assertEquals(ServerTrustDecision.PinnedKey,
            evaluateServerTrust(false, renewed.certificate, spkiSha256(original.certificate), address))
    }
}
