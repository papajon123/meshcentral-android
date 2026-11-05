package ru.papajon.meshcentralmobile.testing

import java.security.Provider
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.tls.HandshakeCertificates
import org.conscrypt.Conscrypt

/**
 * API 26 reports null requestedServerNames for legacy server sessions, which crashes
 * MockWebServer while recording a handshake. Conscrypt reports an empty list instead.
 * This provider is used only for test server sockets; it is never installed globally.
 */
private val fixtureTlsProvider: Provider by lazy { Conscrypt.newProvider() }

internal fun HandshakeCertificates.fixtureServerSocketFactory(): SSLSocketFactory =
    fixtureServerSocketFactory(keyManager, trustManager)

/** Custom-key variant for the delayed-handshake fixture; still server sockets only. */
internal fun fixtureServerSocketFactory(
    keyManager: X509KeyManager,
    trustManager: X509TrustManager?,
): SSLSocketFactory =
    SSLContext.getInstance("TLS", fixtureTlsProvider).apply {
        init(
            arrayOf<KeyManager>(keyManager),
            trustManager?.let { arrayOf<TrustManager>(it) },
            null,
        )
    }.socketFactory
