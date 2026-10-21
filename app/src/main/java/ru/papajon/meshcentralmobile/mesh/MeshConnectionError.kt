package ru.papajon.meshcentralmobile.mesh

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText

/** User-facing diagnostics must not expose exception text, URLs or authentication material. */
internal object MeshConnectionError {
    fun describe(error: Throwable, httpCode: Int? = null): UiText = UiText.Resource(
        when {
            httpCode == 401 || httpCode == 403 -> R.string.runtime_error_auth_rejected
            error is UnknownHostException -> R.string.runtime_error_host_not_found
            error is SocketTimeoutException -> R.string.runtime_error_timeout
            error is ConnectException -> R.string.runtime_error_unreachable
            error is SSLException -> R.string.runtime_error_tls
            else -> R.string.runtime_connection_lost
        },
    )
}
