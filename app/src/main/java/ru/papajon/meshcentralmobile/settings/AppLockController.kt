package ru.papajon.meshcentralmobile.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.papajon.meshcentralmobile.security.SecureVault
import android.os.Handler
import android.os.Looper

class AppLockController(
    private val settingsRepository: SettingsRepository,
    private val secureVault: SecureVault,
    private val onStateChanged: () -> Unit = {},
) {
    var isLocked by mutableStateOf(secureVault.hasPin() || settingsRepository.currentBiometricUnlockEnabled())
        private set
    // Production owns one controller in ConnectionWorkspace. Window identities are removed on stop;
    // an Activity, a share task or a configuration change cannot restart another window's deadline.
    private val visibleWindows = mutableMapOf<Any, Boolean>()
    private var backgroundRecorded = false
    private val handler = Handler(Looper.getMainLooper())
    private val deadlineCheck = Runnable { onAppForegrounded() }
    val hasVisibleWindow: Boolean get() = visibleWindows.isNotEmpty()
    val hasInteractiveWindow: Boolean get() = visibleWindows.values.any { !it }

    // Launcher shortcuts and the Favorites widget hide device names while the app is locked or not
    // in front (when the user enabled it); publish every lock or visibility change to them.
    private fun publishPrivacy() = ru.papajon.meshcentralmobile.integration.LauncherPrivacy.update(
        settingsRepository.appContext, isLocked, hasInteractiveWindow)
    private fun changed() { publishPrivacy(); onStateChanged() }
    init { publishPrivacy() }

    fun onWindowStarted(window: Any, inPip: Boolean) {
        onAppForegrounded() // Check even when returning to visible PiP after screen off.
        visibleWindows[window] = inPip
        updateVisibility()
    }

    fun onWindowStopped(window: Any, changingConfigurations: Boolean = false) {
        visibleWindows.remove(window)
        if (!changingConfigurations || backgroundRecorded) updateVisibility()
        else changed()
    }

    fun onPipChanged(window: Any, inPip: Boolean) {
        // Closing PiP with its X button stops the Activity before onPictureInPictureModeChanged(false):
        // a stopped window must not re-register as interactive and reset the lock deadline.
        if (window !in visibleWindows) return
        onAppForegrounded()
        visibleWindows[window] = inPip
        updateVisibility()
    }

    private fun updateVisibility() {
        if (hasInteractiveWindow && !isLocked) {
            backgroundRecorded = false
            settingsRepository.saveLastBackgroundAt(0)
            handler.removeCallbacks(deadlineCheck)
        } else onAppBackgrounded()
        changed()
    }

    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        if (!backgroundRecorded) {
            backgroundRecorded = true
            settingsRepository.saveLastBackgroundAt(nowMillis)
        }
        scheduleDeadline(nowMillis)
    }

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        val shouldUseLock = secureVault.hasPin() || settingsRepository.currentBiometricUnlockEnabled()
        if (!shouldUseLock) {
            isLocked = false
            handler.removeCallbacks(deadlineCheck)
            changed()
            return
        }
        val backgroundedAt = settingsRepository.getLastBackgroundAt()
        val elapsedSeconds = ((nowMillis - backgroundedAt).coerceAtLeast(0L)) / 1000L
        if (backgroundRecorded && (backgroundedAt == 0L || elapsedSeconds >= settingsRepository.currentLockTimeoutSeconds())) {
            isLocked = true
        }
        scheduleDeadline(nowMillis)
        changed()
    }

    private fun scheduleDeadline(nowMillis: Long) {
        handler.removeCallbacks(deadlineCheck)
        if (!backgroundRecorded || isLocked ||
            !(secureVault.hasPin() || settingsRepository.currentBiometricUnlockEnabled())) return
        val remaining = settingsRepository.getLastBackgroundAt() +
            settingsRepository.currentLockTimeoutSeconds() * 1000 - nowMillis
        // Recheck at most once a second so changed settings/wall clock cannot leave a stale timer.
        handler.postDelayed(deadlineCheck, remaining.coerceIn(1L, 1000L))
    }

    fun lockNow() {
        if (secureVault.hasPin() || settingsRepository.currentBiometricUnlockEnabled()) {
            isLocked = true
        }
        handler.removeCallbacks(deadlineCheck)
        changed()
    }

    fun unlockWithPin(pin: String): Boolean {
        val unlocked = secureVault.verifyPin(pin)
        if (unlocked) unlock()
        return unlocked
    }

    fun unlockAfterBiometric() {
        unlock()
    }

    private fun unlock() {
        isLocked = false
        backgroundRecorded = false
        settingsRepository.saveLastBackgroundAt(0)
        updateVisibility()
    }

    fun refreshLockRequirement() {
        if (!secureVault.hasPin() && !settingsRepository.currentBiometricUnlockEnabled()) {
            isLocked = false
            backgroundRecorded = false
            settingsRepository.saveLastBackgroundAt(0)
            handler.removeCallbacks(deadlineCheck)
        }
        changed()
    }
}
