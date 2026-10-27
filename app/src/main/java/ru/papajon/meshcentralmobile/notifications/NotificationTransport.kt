package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.model.ServerProfile

/** Background HTTPS uses the same endpoint and approved leaf-key pin as the interactive client. */
sealed interface NotificationTransportSupport {
    data class Supported(val server: HttpUrl, val pinnedSpkiSha256: String?) : NotificationTransportSupport

    data class Unsupported(val reason: NotificationError) : NotificationTransportSupport
}

object NotificationTransport {
    fun support(context: Context, profileId: String, serverUrl: String): NotificationTransportSupport {
        val profile = SettingsRepository(context).profilesState.value.firstOrNull { it.id == profileId }
            ?: return NotificationTransportSupport.Unsupported(NotificationError.PROFILE_MISSING)
        return support(profile, serverUrl)
    }

    internal fun support(profile: ServerProfile, serverUrl: String = profile.serverUrl): NotificationTransportSupport {
        if (profile.expectedSpkiSha256 != null) return NotificationTransportSupport.Unsupported(NotificationError.TLS_UNTRUSTED)
        val url = serverUrl.trim().toHttpUrlOrNull()
            ?: return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        }
        val normalized = NotificationEndpoint.normalizeServerUrl(serverUrl)
        if (normalized == null || normalized != NotificationEndpoint.normalizeServerUrl(profile.serverUrl)) {
            return NotificationTransportSupport.Unsupported(NotificationError.INSECURE_TRANSPORT)
        }
        return NotificationTransportSupport.Supported(url, profile.pinnedSpkiSha256)
    }
}
