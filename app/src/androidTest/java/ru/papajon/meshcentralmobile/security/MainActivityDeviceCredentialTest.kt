package ru.papajon.meshcentralmobile.security

import android.app.Application
import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Opt-in fixture: sets and removes a known screen PIN on an otherwise unsecured emulator only. */
class MainActivityDeviceCredentialTest {
    @Test fun biometricOnlyUnlocksThroughTheRealDeviceCredentialScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceCredential") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse("Use a dedicated emulator without an existing screen lock", keyguard.isDeviceSecure)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return true
                SystemClock.sleep(100)
            }
            return condition()
        }
        var fixtureInstalled = false
        try {
            shell("locksettings set-pin 604203")
            fixtureInstalled = keyguard.isDeviceSecure
            assertTrue("Could not establish emulator credential fixture", fixtureInstalled)
            SettingsRepository(context).updateBiometricUnlock(true)
            SecureVault(context).clearPin()
            instrumentation.runOnMainSync {
                ConnectionWorkspace.get(context.applicationContext as Application).appLock.lockNow()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                assertTrue("Expected the Android credential screen, not an unavailable biometric prompt",
                    await { hasSystemPasswordField(instrumentation.uiAutomation.rootInActiveWindow, context.packageName, requireFocus = true) })
                assertTrue("System password editor must accept the synthetic fixture input",
                    setSystemFixturePin(instrumentation.uiAutomation.rootInActiveWindow, context.packageName))
                shell("input keyevent KEYCODE_ENTER")
                assertTrue("Android must accept the fixture PIN and close its credential window", await {
                    !hasSystemPasswordField(instrumentation.uiAutomation.rootInActiveWindow, context.packageName)
                })
                assertTrue("Verified device credential must unlock the application", await {
                    var unlocked = false
                    // Login can keep animating while a saved test server is offline.
                    // Read the lock on Main without waiting for the whole UI to become idle.
                    instrumentation.runOnMainSync {
                        unlocked = !ConnectionWorkspace.get(context.applicationContext as Application).appLock.isLocked
                    }
                    unlocked
                })
                assertFalse("Device authentication must not silently create an application PIN", SecureVault(context).hasPin())
                } finally {
                    // A saved offline fixture can keep the login progress indicator animating.
                    // Stop its connection without deleting stored data before Scenario.close waits for idle.
                    instrumentation.runOnMainSync {
                        ConnectionWorkspace.get(context.applicationContext as Application).client
                            .logout(clearStoredCredentials = false, clearRememberedDevice = false)
                    }
                }
            }
        } finally {
            // All values belong to this opt-in emulator fixture, never a physical phone.
            if (fixtureInstalled) shell("locksettings clear --old 604203")
            SettingsRepository(context).updateBiometricUnlock(false)
            context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_unlock", false).commit()
            instrumentation.runOnMainSync {
                ConnectionWorkspace.get(context.applicationContext as Application).appLock.refreshLockRequirement()
            }
        }
    }

    private fun setSystemFixturePin(node: AccessibilityNodeInfo?, appPackage: String): Boolean {
        if (node == null) return false
        if (node.packageName?.toString() != appPackage && node.isEditable && node.isPassword &&
            node.isVisibleToUser && node.isEnabled && node.isFocused) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "604203")
            })
        }
        return (0 until node.childCount).any { setSystemFixturePin(node.getChild(it), appPackage) }
    }

    private fun hasSystemPasswordField(node: AccessibilityNodeInfo?, appPackage: String, requireFocus: Boolean = false): Boolean {
        if (node == null) return false
        if (node.packageName?.toString() != appPackage && node.isEditable && node.isPassword &&
            node.isVisibleToUser && node.isEnabled && (!requireFocus || node.isFocused)) return true
        return (0 until node.childCount).any { hasSystemPasswordField(node.getChild(it), appPackage, requireFocus) }
    }
}
