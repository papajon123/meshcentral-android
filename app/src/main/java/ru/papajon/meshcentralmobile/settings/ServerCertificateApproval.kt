package ru.papajon.meshcentralmobile.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.security.CredentialScope

/** Binds a confirmation to one profile, endpoint, old pin and certificate; survives the lock screen. */
internal class ServerCertificateApproval(
    private val settings: SettingsRepository,
    private val appLock: AppLockController,
) {
    private val profileId = settings.activeProfile.id
    private val serverUrl = settings.activeProfile.serverUrl
    private var previousPin: String? = null
    var pendingReplacement by mutableStateOf<ServerCertificateFailure?>(null)
        private set
    var pendingForget by mutableStateOf(false)
        private set

    private fun currentProfile(): Boolean = settings.activeProfile.id == profileId &&
        CredentialScope.matches(serverUrl, settings.activeProfile.serverUrl)

    private fun currentServer(failure: ServerCertificateFailure): Boolean = currentProfile() &&
        CredentialScope.matches(serverUrl, failure.serverAddress)

    fun requestReplacement(failure: ServerCertificateFailure) {
        if (!currentServer(failure) || failure.kind != ServerCertificateFailureKind.Changed) return
        previousPin = settings.activeProfile.pinnedSpkiSha256 ?: return
        pendingReplacement = failure
        appLock.lockNow() // Fresh PIN/biometric proof, even if the app was already unlocked.
    }

    fun approve(failure: ServerCertificateFailure): Boolean {
        if (appLock.isLocked || !currentServer(failure)) return false
        val allowed = when (failure.kind) {
            ServerCertificateFailureKind.Untrusted -> settings.activeProfile.pinnedSpkiSha256 == null && settings.activeProfile.expectedSpkiSha256 == null
            ServerCertificateFailureKind.Changed -> pendingReplacement == failure &&
                previousPin != null && settings.activeProfile.pinnedSpkiSha256 == previousPin
            ServerCertificateFailureKind.ExpectedKeyConfirmation -> settings.activeProfile.pinnedSpkiSha256 == null &&
                settings.activeProfile.expectedSpkiSha256 != null && settings.activeProfile.expectedSpkiSha256 == failure.expectedSpkiSha256
            else -> false
        }
        if (!allowed) return false
        settings.updatePinnedServerKey(failure.spkiSha256)
        dismiss()
        return true
    }

    fun requestForget() {
        if (!currentProfile()) return
        previousPin = settings.activeProfile.pinnedSpkiSha256 ?: return
        pendingForget = true
        // Otherwise forgetting and then trusting anew could bypass replacement reauthentication.
        appLock.lockNow()
    }

    fun confirmForget(): Boolean {
        if (appLock.isLocked || !currentProfile() || !pendingForget ||
            settings.activeProfile.pinnedSpkiSha256 != previousPin) return false
        settings.updatePinnedServerKey(null)
        dismiss()
        return true
    }

    fun dismiss() {
        pendingReplacement = null
        pendingForget = false
        previousPin = null
    }
}
