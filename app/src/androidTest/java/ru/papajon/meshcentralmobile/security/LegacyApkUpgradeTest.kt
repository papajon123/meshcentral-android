package ru.papajon.meshcentralmobile.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.model.StoredCredentials
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

private const val DEBUG_PACKAGE = "io.github.papajon123.meshcentral.debug"
private const val FIXTURE_NAME = "Upgrade fixture"
private const val FIXTURE_URL = "https://upgrade.example/"
private const val FIXTURE_USERNAME = "fixture"
private const val FIXTURE_PASSWORD = "synthetic-password"
private const val FIXTURE_PIN = "604203"
private const val FIXTURE_PREFS = "legacy_apk_fixture"
private const val FIXTURE_ID_KEY = "profile_id"
private const val FIXTURE_MARKER_KEY = "marker"
private const val FIXTURE_MARKER = "legacy-apk-upgrade-084"
private val FIXTURE_LOGIN_KEY = "1".repeat(160)

/**
 * Seed half of the opt-in 0.8.4 -> current debug upgrade fixture. It is compiled against the
 * current test APK but runs against the old target APK, so every production symbol used here must
 * also exist in 0.8.4. SecureVault is deliberately constructed by reflection because its current
 * constructor has an additional parameter; the old public constructor is (Context, String?, String).
 */
class LegacyApkSeedTest {
    @Test
    fun seedLegacyUpgradeFixture() {
        assumeLegacyUpgrade()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireDebugEmulator(context)

        val (versionName, versionCode) = installedVersion(context)
        assertEquals("0.8.4", versionName)
        assertEquals(23L, versionCode)

        val fixturePrefs = context.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE)
        assertNull("Fixture profile id already exists", fixturePrefs.getString(FIXTURE_ID_KEY, null))
        assertNull("Fixture marker already exists", fixturePrefs.getString(FIXTURE_MARKER_KEY, null))

        val repository = SettingsRepository(context, "app_settings")
        val sourceProfiles = repository.profilesState.value
        assertEquals("Expected one fresh source profile", 1, sourceProfiles.size)
        assertEquals("default", sourceProfiles.single().id)
        val sourceSettings = repository.getSettings()
        assertTrue("Fresh source profile must be empty", sourceSettings.serverUrl.isEmpty())
        assertFalse("Fresh source profile must not be onboarded", sourceSettings.onboardingCompleted)
        assertFalse("Fresh app must not have an app PIN", hasPin(legacyVault(context, "default")))

        val profile = repository.createProfile(FIXTURE_NAME, FIXTURE_URL, true)
        assertTrue("Could not activate the fixture profile", repository.activateProfile(profile.id))
        assertEquals(profile.id, repository.activeProfile.id)
        repository.updateBiometricUnlock(true)

        val vault = legacyVault(context, profile.id)
        saveCredentials(vault, FIXTURE_USERNAME, FIXTURE_PASSWORD, FIXTURE_URL)
        saveLoginKey(vault, FIXTURE_URL, FIXTURE_LOGIN_KEY)
        assertFalse("Fixture must not create an app PIN", hasPin(vault))
        val stored = credentials(vault, FIXTURE_URL)
        assertNotNull("Synthetic credentials were not stored", stored)
        val storedCredentials = requireNotNull(stored)
        assertTrue(storedCredentials.username == FIXTURE_USERNAME)
        assertTrue(storedCredentials.password == FIXTURE_PASSWORD)
        assertTrue(getLoginKey(vault, FIXTURE_URL) == FIXTURE_LOGIN_KEY)

        val fixtureSettings = repository.getSettings()
        assertEquals(FIXTURE_URL, fixtureSettings.serverUrl)
        assertTrue(fixtureSettings.onboardingCompleted)
        assertTrue(fixtureSettings.biometricUnlockEnabled)

        fixturePrefs.edit()
            .putString(FIXTURE_ID_KEY, profile.id)
            .putString(FIXTURE_MARKER_KEY, FIXTURE_MARKER)
            .commit()
        assertEquals(profile.id, fixturePrefs.getString(FIXTURE_ID_KEY, null))
        assertEquals(FIXTURE_MARKER, fixturePrefs.getString(FIXTURE_MARKER_KEY, null))

        // The old APK owns this resource id; do not use the current APK's R class.
        val lockMessageId = context.resources.getIdentifier(
            "lock_message_locked", "string", context.packageName,
        )
        assertTrue("Legacy lock message resource is missing", lockMessageId != 0)
        val lockMessage = context.resources.getString(lockMessageId)

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("Legacy lock screen text did not appear", await(20_000) {
                treeContainsText(instrumentation.uiAutomation.rootInActiveWindow, lockMessage)
            })
            captureScreenshot("legacy-084-lock.png")
        }

        // The Activity is closed; all fixture data stays in place for the in-place upgrade.
        assertFalse("Legacy fixture must not create an app PIN", hasPin(legacyVault(context, profile.id)))
    }

    private fun legacyVault(context: Context, namespace: String): Any {
        val type = Class.forName(
            "ru.papajon.meshcentralmobile.security.SecureVault", false, context.classLoader,
        )
        val constructor = type.getConstructor(Context::class.java, String::class.java, String::class.java)
        return constructor.newInstance(context, namespace, "secure_vault")
    }

    private fun saveCredentials(vault: Any, username: String, password: String, serverUrl: String) {
        vault.javaClass.getMethod(
            "saveCredentials", String::class.java, String::class.java, String::class.java,
        ).invoke(vault, username, password, serverUrl)
    }

    private fun credentials(vault: Any, serverUrl: String): StoredCredentials? =
        vault.javaClass.getMethod("getCredentials", String::class.java)
            .invoke(vault, serverUrl) as StoredCredentials?

    private fun saveLoginKey(vault: Any, serverUrl: String, value: String) {
        vault.javaClass.getMethod("saveLoginKey", String::class.java, String::class.java)
            .invoke(vault, serverUrl, value)
    }

    private fun getLoginKey(vault: Any, serverUrl: String): String? =
        vault.javaClass.getMethod("getLoginKey", String::class.java)
            .invoke(vault, serverUrl) as String?

    private fun hasPin(vault: Any): Boolean =
        vault.javaClass.getMethod("hasPin").invoke(vault) as Boolean
}

/**
 * Verify half of the opt-in fixture. It runs only after `adb install -r` upgraded the target in
 * place. It adds one temporary device PIN, proves the real system credential screen unlocks the
 * application, and then removes only the values it created.
 */
class LegacyApkVerifyTest {
    @Test
    fun verifyUpgradePreservesFixtureAndDeviceCredentialUnlock() {
        assumeLegacyUpgrade()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireDebugEmulator(context)

        val fixturePrefs = context.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE)
        val marker = fixturePrefs.getString(FIXTURE_MARKER_KEY, null)
        val fixtureId = fixturePrefs.getString(FIXTURE_ID_KEY, null)
        assertTrue("Fixture marker is missing", marker == FIXTURE_MARKER)
        assertNotNull("Fixture profile id is missing", fixtureId)
        val profileId = requireNotNull(fixtureId)

        val repository = SettingsRepository(context, "app_settings")
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        assertFalse("Use a dedicated emulator without an existing screen lock", keyguard.isDeviceSecure)
        val installed = installedVersion(context)
        assertTrue("The target APK was not upgraded", installed.second > 23)
        assertEquals(ru.papajon.meshcentralmobile.BuildConfig.VERSION_NAME, installed.first)
        assertTrue("Fixture ownership changed", repository.currentProfile(profileId)?.let {
            it.name == FIXTURE_NAME && it.serverUrl == FIXTURE_URL
        } == true)

        var phonePinIssued = false
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val profile = repository.currentProfile(profileId)
            assertNotNull("Fixture profile is missing", profile)
            val fixtureProfile = requireNotNull(profile)
            assertEquals(FIXTURE_NAME, fixtureProfile.name)
            assertEquals(FIXTURE_URL, fixtureProfile.serverUrl)
            assertEquals(profileId, repository.activeProfile.id)
            assertTrue(
                "Original default profile is missing",
                repository.profilesState.value.any { it.id == "default" },
            )
            verifyPersisted(context, repository, profileId)

            phonePinIssued = true
            shell("locksettings set-pin $FIXTURE_PIN")
            assertTrue("Could not establish the emulator credential fixture", await(10_000) {
                keyguard.isDeviceSecure
            })

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            assertTrue("Expected the system credential screen", await(20_000) {
                hasSystemPasswordField(instrumentation.uiAutomation.rootInActiveWindow, context.packageName)
            })
            captureScreenshot("upgraded-device-auth.png")

            shell("input text $FIXTURE_PIN")
            shell("input keyevent KEYCODE_ENTER")
            assertTrue("System device credential did not unlock the app", await(20_000) {
                var unlocked = false
                activeScenario.onActivity { activity ->
                    unlocked = !ConnectionWorkspace.get(activity.application).appLock.isLocked
                }
                unlocked
            })

            captureScreenshot("upgraded-unlocked.png")
            verifyPersisted(context, repository, profileId)
        } finally {
            runCatching { scenario?.close() }
            // `--old` clears only if the temporary PIN is still the one this test installed.
            if (phonePinIssued) {
                shell("locksettings clear --old $FIXTURE_PIN")
                assertTrue("Temporary screen lock was not removed", await(10_000) { !keyguard.isDeviceSecure })
            }
            cleanupFixture(context, repository, fixturePrefs, profileId)
            assertNull("Fixture cleanup did not finish", fixturePrefs.getString(FIXTURE_MARKER_KEY, null))
        }
    }

    private fun verifyPersisted(context: Context, repository: SettingsRepository, profileId: String) {
        val profile = repository.currentProfile(profileId)
        assertNotNull("Fixture profile is missing", profile)
        assertEquals(FIXTURE_URL, requireNotNull(profile).serverUrl)
        assertTrue("Biometric unlock must stay enabled", repository.getSettings().biometricUnlockEnabled)

        val vault = SecureVault(context, profileId)
        assertFalse("Fixture must not have an app PIN", vault.hasPin())
        val stored = vault.getCredentials(FIXTURE_URL)
        assertNotNull("Synthetic credentials are missing", stored)
        val credentials = requireNotNull(stored)
        assertTrue(credentials.username == FIXTURE_USERNAME && credentials.password == FIXTURE_PASSWORD)
        assertTrue(vault.getLoginKey(FIXTURE_URL) == FIXTURE_LOGIN_KEY)
    }

    private fun cleanupFixture(
        context: Context,
        repository: SettingsRepository,
        fixturePrefs: SharedPreferences,
        profileId: String,
    ) {
        val credentialsCleared = runCatching {
            val vault = SecureVault(context, profileId)
            vault.clearCredentials()
            vault.clearLoginKey()
        }.isSuccess
        val biometricDisabled = runCatching { repository.updateBiometricUnlock(false) }.isSuccess

        val fresh = SettingsRepository(context, "app_settings")
        val defaultActivated = runCatching { fresh.activateProfile("default") }.getOrDefault(false)
        val removed = runCatching { fresh.removeProfile(profileId) }.getOrDefault(false)
        val profileGone = fresh.profilesState.value.none { it.id == profileId }
        if (credentialsCleared && biometricDisabled && defaultActivated && (removed || profileGone)) {
            // Instrumentation can terminate the process before apply's delayed disk writes.
            // Flush the fixture cleanup before removing its durable ownership marker.
            assertTrue(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("biometric_unlock", false).commit())
            assertTrue(context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE).edit().commit())
            fixturePrefs.edit().remove(FIXTURE_ID_KEY).remove(FIXTURE_MARKER_KEY).commit()
        }
    }
}

private fun assumeLegacyUpgrade() {
    assumeTrue(
        "Pass -e legacyUpgrade true to enable the upgrade fixture",
        InstrumentationRegistry.getArguments().getString("legacyUpgrade") == "true",
    )
}

private fun requireDebugEmulator(context: Context) {
    assertEquals(DEBUG_PACKAGE, context.packageName)
    assertTrue(
        "A debuggable target is required",
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
    val model = Build.MODEL.orEmpty()
    val fingerprint = Build.FINGERPRINT.orEmpty()
    val emulator = model.startsWith("sdk_", ignoreCase = true) ||
        model.contains("emulator", ignoreCase = true) ||
        model.contains("android sdk built for", ignoreCase = true) ||
        fingerprint.startsWith("generic", ignoreCase = true) ||
        fingerprint.startsWith("google_sdk", ignoreCase = true) ||
        fingerprint.contains("emulator", ignoreCase = true)
    assertTrue("A dedicated emulator is required", emulator)
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): Pair<String?, Long> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    return info.versionName to code
}

private fun shell(command: String) {
    // Drain the pipe without printing: reading to EOF also waits for the shell command to finish.
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}

private fun await(timeoutMillis: Long = 15_000, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return true
        SystemClock.sleep(150)
    }
    return condition()
}

private fun treeContainsText(node: AccessibilityNodeInfo?, text: String): Boolean {
    if (node == null) return false
    if (node.text?.toString()?.contains(text) == true) return true
    if (node.contentDescription?.toString()?.contains(text) == true) return true
    for (index in 0 until node.childCount) {
        if (treeContainsText(node.getChild(index), text)) return true
    }
    return false
}

private fun hasSystemPasswordField(node: AccessibilityNodeInfo?, appPackage: String): Boolean {
    if (node == null) return false
    val packageName = node.packageName?.toString()
    if (packageName != null && packageName != appPackage && node.isEditable && node.isPassword) return true
    for (index in 0 until node.childCount) {
        if (hasSystemPasswordField(node.getChild(index), appPackage)) return true
    }
    return false
}

/** Optional screenshots are accepted only below the fixed Download prefix and never contain `..`. */
private fun screenshotDirectory(): String? {
    val raw = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.trim().orEmpty()
    if (raw.isEmpty() || !raw.startsWith("/sdcard/Download/meshpilot-") || raw.contains("..")) return null
    if (!raw.matches(Regex("/sdcard/Download/meshpilot-[A-Za-z0-9._/-]*"))) return null
    return raw.trimEnd('/')
}

private fun captureScreenshot(name: String) {
    val directory = screenshotDirectory() ?: return
    shell("mkdir -p $directory")
    shell("screencap -p $directory/$name")
}
