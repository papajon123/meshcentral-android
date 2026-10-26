package ru.papajon.meshcentralmobile.mesh.tls

import android.net.http.X509TrustManagerExtensions
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import ru.papajon.meshcentralmobile.BuildConfig

/** Immutable for one profile's endpoint/pin. Replace the client and SSLContext on policy changes. */
internal class ServerTls(
    private val server: HttpUrl?,
    private val pinnedSpkiSha256: String?,
    debugAllowSelfSigned: Boolean = false,
    platform: X509TrustManager = platformTrustManager(),
) {
    private val debugBypass = BuildConfig.DEBUG && debugAllowSelfSigned && pinnedSpkiSha256 == null
    private val serverAddress = server?.newBuilder()?.username("")?.password("")
        ?.query(null)?.fragment(null)?.build()?.toString().orEmpty()
    private val trustManager = ServerPinTrustManager(server, pinnedSpkiSha256, serverAddress, debugBypass, platform)

    fun applyTo(builder: OkHttpClient.Builder) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        // OkHttp's Android chain cleaner must receive the very same host-aware manager.
        builder.sslSocketFactory(context.socketFactory, trustManager)
        builder.addInterceptor { chain ->
            val target = chain.request().url
            if (server == null || target.host != server.host || target.port != server.port || target.scheme != server.scheme) {
                throw SSLPeerUnverifiedException("TLS policy belongs to a different endpoint")
            }
            chain.proceed(chain.request())
        }
        builder.hostnameVerifier { hostname, session ->
            // Evidence comes only from this TLS session, never a shared flag/cache of prior decisions.
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            } ?: return@hostnameVerifier false
            if (hostname != server?.host || session.peerPort != server.port) return@hostnameVerifier false
            val decision = evaluateServerTrust(true, leaf, pinnedSpkiSha256, serverAddress)
            if (decision is ServerTrustDecision.Rejected) throw peerFailure(decision.failure)
            if (decision == ServerTrustDecision.PinnedKey || debugBypass) return@hostnameVerifier true
            if (!standardVerifier.verify(hostname, session)) {
                throw peerFailure(leaf.failure(ServerCertificateFailureKind.HostnameMismatch, serverAddress))
            }
            true
        }
    }

    private fun peerFailure(failure: ServerCertificateFailure) =
        SSLPeerUnverifiedException("Server certificate ${failure.kind}").apply { initCause(ServerCertificateException(failure)) }

    private companion object {
        val standardVerifier = OkHttpClient().hostnameVerifier
    }
}

/** Public JVM method signatures are required by Android's X509TrustManagerExtensions reflection. */
internal class ServerPinTrustManager(
    private val server: HttpUrl?,
    private val pin: String?,
    private val address: String,
    private val debugBypass: Boolean,
    private val platform: X509TrustManager,
) : X509TrustManager {
    // Dalvik includes ART. On Android, failure to obtain host-aware platform validation is fatal;
    // do not silently drop Network Security Config domain rules. JVM tests use their real JDK TM.
    private val platformExtensions = if (System.getProperty("java.vm.name") == "Dalvik")
        X509TrustManagerExtensions(platform) else null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        checkServerTrusted(chain, authType, server?.host ?: throw CertificateException("Missing server endpoint"))
    }

    @Suppress("unused") // Called reflectively by Android and OkHttp's AndroidCertificateChainCleaner.
    @Throws(CertificateException::class)
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        if (chain.isEmpty()) throw CertificateException("Empty server certificate chain")
        if (host != server?.host) throw CertificateException("TLS policy belongs to a different host")
        val leaf = chain.first()
        // Saved pins keep their strict supplied-chain checks. With no pin, let Android build
        // the trusted path first: servers can send unrelated or obsolete cross certificates.
        val preliminary = if (pin != null || debugBypass) evaluateServerTrust(false, chain.toList(), pin, address) else null
        if (preliminary is ServerTrustDecision.Rejected && !preliminary.failure.canApprove) {
            throw ServerCertificateException(preliminary.failure)
        }
        if (preliminary == ServerTrustDecision.PinnedKey || debugBypass) return chain.toList()
        val validatedChain = try {
            platformExtensions?.checkServerTrusted(chain, authType, host) ?: run {
                platform.checkServerTrusted(chain, authType)
                chain.toList()
            }
        } catch (cause: CertificateException) {
            // Platform rejection alone never grants trust. Validate the supplied private chain
            // before offering explicit approval, including dates, usage and key strength.
            val rejected = (preliminary ?: evaluateServerTrust(false, chain.toList(), pin, address)) as ServerTrustDecision.Rejected
            throw ServerCertificateException(rejected.failure).apply { initCause(cause) }
        }
        // A valid platform chain does not waive a saved pin. Keep this rejection outside the
        // platform catch so it is never mistaken for an unknown issuer/configuration failure.
        val decision = evaluateServerTrust(true, leaf, pin, address)
        if (decision is ServerTrustDecision.Rejected) throw ServerCertificateException(decision.failure)
        return validatedChain
    }
}

private fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}
