package ru.papajon.meshcentralmobile.integration

import android.content.Context
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/**
 * Privacy gate for launcher-facing surfaces. State is in-memory and updated before the queue
 * writes anything, so backgrounding hides stale names immediately instead of waiting for the
 * PIN timeout to expire. A fresh process starts locked/backgrounded.
 */
object LauncherPrivacy {
    @Volatile private var locked = false
    @Volatile private var foreground = false

    fun update(context: Context, locked: Boolean, foreground: Boolean) {
        this.locked = locked
        this.foreground = foreground
        // Resolve preferences/vault only inside the serialized IO queue.
        LauncherCoordinator.render(context)
    }

    fun hidden(context: Context): Boolean {
        val settings = SettingsRepository(context).getSettings()
        if (!settings.hideDevicesWhenLocked) return false
        if (!locked && foreground) return false
        return SecureVault(context).hasPin() || settings.biometricUnlockEnabled
    }

    internal fun resetForTests() {
        locked = false
        foreground = false
    }
}
