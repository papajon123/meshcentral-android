package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/** Background notification transport policy: system-trusted HTTPS only, no self-signed exception. */
sealed interface NotificationTransportSupport {
    data object Supported : NotificationTransportSupport

    data class Unsupported(val reason: NotificationError) : NotificationTransportSupport
}

object NotificationTransport {
    fun support(context: Context, profileId: String, serverUrl: String): NotificationTransportSupport {
        val profile = SettingsRepository(context).profilesState.value.firstOrNull { it.id == profileId }
            ?: return NotificationTransportSupport.Unsupported(NotificationError.PROFILE_MISSING)
        // Always use platform TLS verification, even when the interactive client allows an exception.
        // That preference does not prove the server actually has an untrusted certificate.
        val url = serverUrl.trim().toHttpUrlOrNull()
            ?: return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        }
        if (NotificationEndpoint.normalizeServerUrl(serverUrl) == null) {
            return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        }
        return NotificationTransportSupport.Supported
    }
}
