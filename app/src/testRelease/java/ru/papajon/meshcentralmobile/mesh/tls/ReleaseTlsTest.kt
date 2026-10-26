package ru.papajon.meshcentralmobile.mesh.tls

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig

class ReleaseTlsTest {
    @Test fun releaseIgnoresTheDebugBypassFlag() {
        assertFalse(BuildConfig.DEBUG)
        val cert = HeldCertificate.Builder().commonName("mesh.example").build().certificate
        val client = OkHttpClient.Builder().apply {
            ServerTls("https://mesh.example/".toHttpUrl(), null, debugAllowSelfSigned = true).applyTo(this)
        }.build()
        val error = runCatching { client.x509TrustManager!!.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }.exceptionOrNull()
        assertEquals(ServerCertificateFailureKind.Untrusted, error?.serverCertificateFailure()?.kind)
    }
}
