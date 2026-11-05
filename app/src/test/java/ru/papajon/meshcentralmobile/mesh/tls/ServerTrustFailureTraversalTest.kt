package ru.papajon.meshcentralmobile.mesh.tls

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ServerTrustFailureTraversalTest {
    @Test fun aLaterConnectFailureMustNotHideAnEarlierCertificateRejection() {
        val failure = ServerCertificateFailure(ServerCertificateFailureKind.Changed, "01".repeat(32),
            "CN=fixture", 1, 2, "https://mesh.example/", "02".repeat(32))
        val tls = SSLHandshakeException("fixture handshake").apply { initCause(ServerCertificateException(failure)) }
        val lastAddress = IOException("fixture connection").apply { addSuppressed(tls) }
        assertSame(failure, lastAddress.serverCertificateFailure())
    }
    @Test fun exceptionCyclesWithoutCertificateMetadataTerminate() {
        val first = IOException("fixture one")
        val second = IOException("fixture two")
        first.initCause(second); second.initCause(first)
        first.addSuppressed(second)
        assertNull(first.serverCertificateFailure())
    }
}
