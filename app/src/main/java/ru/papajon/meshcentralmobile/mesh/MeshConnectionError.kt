package ru.papajon.meshcentralmobile.mesh

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure

/** User-facing diagnostics must not expose exception text, URLs or authentication material. */
internal object MeshConnectionError {
    fun describeCertificate(failure: ServerCertificateFailure): UiText.Resource = UiText.Resource(
        when (failure.kind) {
            ServerCertificateFailureKind.Untrusted -> R.string.tls_error_untrusted
            ServerCertificateFailureKind.Changed -> R.string.tls_error_changed
            ServerCertificateFailureKind.Expired, ServerCertificateFailureKind.NotYetValid -> R.string.tls_error_validity
            ServerCertificateFailureKind.ServerConfiguration -> R.string.tls_error_chain
            ServerCertificateFailureKind.HostnameMismatch -> R.string.tls_error_hostname
            ServerCertificateFailureKind.ExpectedKeyConfirmation -> R.string.backup_key_approval_title
        },
    )

    fun describe(error: Throwable, httpCode: Int? = null): UiText = UiText.Resource(
        when {
            error.serverCertificateFailure() != null -> describeCertificate(error.serverCertificateFailure()!!).id
            httpCode == 401 || httpCode == 403 -> R.string.runtime_error_auth_rejected
            error is UnknownHostException -> R.string.runtime_error_host_not_found
            error is SocketTimeoutException -> R.string.runtime_error_timeout
            error is ConnectException -> R.string.runtime_error_unreachable
            error is SSLException -> R.string.runtime_error_tls
            else -> R.string.runtime_connection_lost
        },
    )
}
