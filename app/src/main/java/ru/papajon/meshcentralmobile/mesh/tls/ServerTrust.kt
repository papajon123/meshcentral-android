package ru.papajon.meshcentralmobile.mesh.tls

import java.security.MessageDigest
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PSSParameterSpec
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.tls.OkHostnameVerifier

enum class ServerCertificateFailureKind { Untrusted, Changed, Expired, NotYetValid, ServerConfiguration, HostnameMismatch, ExpectedKeyConfirmation }

enum class ServerCertificateIssuerExplanation { MeshCentralRoot, UnknownAuthority }

/** Public certificate metadata only; never a request URL, cookie or authentication material. */
data class ServerCertificateFailure(
    val kind: ServerCertificateFailureKind,
    val spkiSha256: String,
    val subject: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val serverAddress: String,
    val certificateSha256: String,
    val issuerExplanation: ServerCertificateIssuerExplanation? = null,
    val previousSpkiSha256: String? = null,
    val hostnameMismatch: Boolean = false,
    val expectedSpkiSha256: String? = null,
) {
    val canApprove: Boolean get() = kind == ServerCertificateFailureKind.Untrusted || kind == ServerCertificateFailureKind.Changed || kind == ServerCertificateFailureKind.ExpectedKeyConfirmation
}

sealed interface ServerTrustDecision {
    data object PlatformTrusted : ServerTrustDecision
    data object PinnedKey : ServerTrustDecision
    data class Rejected(val failure: ServerCertificateFailure) : ServerTrustDecision
}

fun spkiSha256(certificate: X509Certificate): String =
    sha256(certificate.publicKey.encoded)

fun certificateSha256(certificate: X509Certificate): String = sha256(certificate.encoded)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

fun formatFingerprint(spkiSha256: String): String = spkiSha256.chunked(2).joinToString(":")

/** An existing pin is mandatory, even for a platform-trusted chain. Dates are never bypassed. */
fun evaluateServerTrust(
    platformTrusted: Boolean,
    leaf: X509Certificate,
    pinnedSpkiSha256: String?,
    serverAddress: String,
    expectedSpkiSha256: String? = null,
): ServerTrustDecision = evaluateServerTrust(platformTrusted, listOf(leaf), pinnedSpkiSha256, serverAddress, expectedSpkiSha256)

fun evaluateServerTrust(
    platformTrusted: Boolean,
    chain: List<X509Certificate>,
    pinnedSpkiSha256: String?,
    serverAddress: String,
    expectedSpkiSha256: String? = null,
): ServerTrustDecision {
    require(chain.isNotEmpty()) { "Empty server certificate chain" }
    val leaf = chain.first()
    val datedCertificates = if (platformTrusted && pinnedSpkiSha256 == null && expectedSpkiSha256 == null) listOf(leaf) else chain
    for (certificate in datedCertificates) {
        val dateFailure = try {
            certificate.checkValidity()
            null
        } catch (_: CertificateExpiredException) {
            ServerCertificateFailureKind.Expired
        } catch (_: CertificateNotYetValidException) {
            ServerCertificateFailureKind.NotYetValid
        }
        if (dateFailure != null) return ServerTrustDecision.Rejected(leaf.failure(dateFailure, serverAddress))
    }
    if ((!platformTrusted || expectedSpkiSha256 != null) && (!chain.isInternallyConsistent() || !chain.hasSafeServerAlgorithms())) {
        return ServerTrustDecision.Rejected(leaf.failure(ServerCertificateFailureKind.ServerConfiguration, serverAddress))
    }
    // Compute this before the expected-key branch so its dialog is as informative as Untrusted.
    // Expired, weak and inconsistent certificates still return above and remain unapprovable.
    val issuerExplanation = if (!platformTrusted && leaf.subjectX500Principal != leaf.issuerX500Principal) {
        if (Regex("(?:^|,)CN=MeshCentralRoot-[^,]+(?:,|$)").containsMatchIn(leaf.issuerX500Principal.name))
            ServerCertificateIssuerExplanation.MeshCentralRoot else ServerCertificateIssuerExplanation.UnknownAuthority
    } else null
    if (expectedSpkiSha256 != null) return ServerTrustDecision.Rejected(
        leaf.failure(ServerCertificateFailureKind.ExpectedKeyConfirmation, serverAddress, issuerExplanation)
            .copy(expectedSpkiSha256 = expectedSpkiSha256))
    if (spkiSha256(leaf) == pinnedSpkiSha256) return ServerTrustDecision.PinnedKey
    if (pinnedSpkiSha256 != null) {
        return ServerTrustDecision.Rejected(leaf.failure(
            ServerCertificateFailureKind.Changed, serverAddress, issuerExplanation,
        ).copy(previousSpkiSha256 = pinnedSpkiSha256))
    }
    if (platformTrusted) return ServerTrustDecision.PlatformTrusted
    return ServerTrustDecision.Rejected(leaf.failure(
        ServerCertificateFailureKind.Untrusted,
        serverAddress,
        issuerExplanation,
    ))
}

/** These checks apply to user-approved private chains; Android owns public path policy. */
private fun List<X509Certificate>.hasSafeServerAlgorithms(): Boolean {
    for (certificate in this) {
        when (val key = certificate.publicKey) {
            is RSAPublicKey -> if (key.modulus.bitLength() < 2048) return false
            is ECPublicKey -> if (key.params.curve.field.fieldSize < 256 || key.params.order.bitLength() < 256) return false
        }
    }
    val leaf = first()
    val signature = leaf.sigAlgName.uppercase(java.util.Locale.ROOT).replace("-", "")
    if (signature.contains("MD5") || signature.contains("SHA1")) return false
    if (leaf.sigAlgOID in setOf("1.2.840.113549.1.1.4", "1.2.840.113549.1.1.5", "1.2.840.10045.4.1",
            "1.2.840.10040.4.3", "1.3.14.3.2.29", "1.3.14.3.2.13")) return false
    if (leaf.sigAlgOID == "1.2.840.113549.1.1.10") {
        val digest = runCatching {
            AlgorithmParameters.getInstance("RSASSA-PSS").apply { init(leaf.sigAlgParams) }
                .getParameterSpec(PSSParameterSpec::class.java).digestAlgorithm.replace("-", "")
        }.getOrNull() ?: return false
        if (digest.equals("SHA1", true) || digest.equals("MD5", true)) return false
    }
    return runCatching {
        leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") ?: true // id-kp-serverAuth
    }.getOrDefault(false)
}

/** Check only the supplied links. A missing root cannot be verified here and still needs user approval. */
private fun List<X509Certificate>.isInternallyConsistent(): Boolean {
    for ((index, certificate) in withIndex()) {
        if (certificate.subjectX500Principal == certificate.issuerX500Principal &&
            runCatching { certificate.verify(certificate.publicKey) }.isFailure) return false
        val issuer = getOrNull(index + 1) ?: continue
        if (certificate.issuerX500Principal != issuer.subjectX500Principal) return false
        if (runCatching { certificate.verify(issuer.publicKey) }.isFailure) return false
        val pathLength = issuer.basicConstraints
        if (pathLength < 0) return false
        val usage = issuer.keyUsage
        if (usage != null && (usage.size <= 5 || !usage[5])) return false // keyCertSign
        val subordinateCas = subList(1, index + 1).count {
            it.subjectX500Principal != it.issuerX500Principal
        }
        if (subordinateCas > pathLength) return false
    }
    return true
}

internal fun X509Certificate.failure(
    kind: ServerCertificateFailureKind,
    serverAddress: String,
    issuerExplanation: ServerCertificateIssuerExplanation? = null,
) =
    ServerCertificateFailure(
        kind = kind,
        spkiSha256 = spkiSha256(this),
        certificateSha256 = certificateSha256(this),
        subject = subjectX500Principal.name,
        notBeforeMillis = notBefore.time,
        notAfterMillis = notAfter.time,
        serverAddress = serverAddress,
        issuerExplanation = issuerExplanation,
        // Use the same SAN/IP/wildcard rules as OkHttp's TLS verifier; never infer identity from CN.
        hostnameMismatch = serverAddress.toHttpUrlOrNull()?.host?.let { !OkHostnameVerifier.verify(it, this) } ?: false,
    )

class ServerCertificateException(val failure: ServerCertificateFailure) :
    CertificateException("Server certificate ${failure.kind.name}")

/** TLS providers wrap CertificateException; retain the typed reason without exposing raw messages. */
fun Throwable.serverCertificateFailure(): ServerCertificateFailure? =
    generateSequence(this) { it.cause }.take(16)
        .filterIsInstance<ServerCertificateException>().firstOrNull()?.failure
