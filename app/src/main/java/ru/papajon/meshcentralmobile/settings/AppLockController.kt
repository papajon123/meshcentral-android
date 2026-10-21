package ru.papajon.meshcentralmobile.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.papajon.meshcentralmobile.security.SecureVault

class AppLockController(
    private val settingsRepository: SettingsRepository,
    private val secureVault: SecureVault,
) {
    var isLocked by mutableStateOf(secureVault.hasPin() || settingsRepository.getSettings().biometricUnlockEnabled)
        private set

    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        settingsRepository.saveLastBackgroundAt(nowMillis)
    }

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        val settings = settingsRepository.getSettings()
        val shouldUseLock = secureVault.hasPin() || settings.biometricUnlockEnabled
        if (!shouldUseLock) {
            isLocked = false
            return
        }
        val backgroundedAt = settingsRepository.getLastBackgroundAt()
        val elapsedSeconds = ((nowMillis - backgroundedAt).coerceAtLeast(0L)) / 1000L
        if (backgroundedAt == 0L || elapsedSeconds >= settings.lockTimeoutSeconds) {
            isLocked = true
        }
    }

    fun lockNow() {
        if (secureVault.hasPin() || settingsRepository.getSettings().biometricUnlockEnabled) {
            isLocked = true
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val unlocked = secureVault.verifyPin(pin)
        if (unlocked) isLocked = false
        return unlocked
    }

    fun unlockAfterBiometric() {
        isLocked = false
    }

    fun refreshLockRequirement() {
        if (!secureVault.hasPin() && !settingsRepository.getSettings().biometricUnlockEnabled) {
            isLocked = false
        }
    }
}
