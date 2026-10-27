package ru.papajon.meshcentralmobile.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.files.FixtureDocumentsProvider
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.util.UUID

/** Real SAF URI streams, contracts and production UI; the chooser result is deterministic. */
class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "backup-ui-${UUID.randomUUID()}"
    private val locked = androidx.compose.runtime.mutableStateOf(false)
    private lateinit var settings: SettingsRepository
    private val registry = object : ActivityResultRegistry() {
        var uri: Uri? = null
        var action: String? = null
        var deferred = false
        var pendingCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            action = contract.createIntent(context, input).action
            if (deferred) pendingCode = requestCode
            else dispatchResult(requestCode, if (uri != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent().setData(uri))
        }
    }

    @After fun cleanup() {
        if (::settings.isInitialized) settings.profilesState.value.filter { it.id != "default" }.forEach {
            SecureVault(context, it.id).clearLoginKey()
        }
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        FixtureDocumentsProvider.resetForTest(context)
    }

    private fun show() {
        settings = SettingsRepository(context, name)
        settings.updateServerUrl("https://example.invalid/")
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MeshCentralMobileTheme { BackupScreen(settings, {}, {}, locked = locked.value) }
            }
        }
    }
    private fun click(id: Int) = compose.onNodeWithText(context.getString(id)).performScrollTo().performClick()
    private fun awaitText(id: Int) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(context.getString(id)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun document(bytes: ByteArray = ByteArray(0)) = FixtureDocumentsProvider.addForTest(context, "synthetic-backup.json", bytes, true, true)

    @Test fun exportThroughSafExcludesKeysByDefaultAndEncryptsOnlyAfterExplicitSelection() {
        show()
        SecureVault(context, "default").saveLoginKey("https://example.invalid/", "synthetic-ui-key")
        try {
            registry.uri = document()
            click(R.string.backup_export)
            awaitText(R.string.backup_saved)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.action)
            val plain = BackupDocuments.read(context.contentResolver, registry.uri!!)
            assertNull(BackupCodec.decode(plain).profiles.single().loginKey)
            assertFalse(plain.decodeToString().contains("synthetic-ui-key"))
            click(R.string.backup_include_keys)
            click(R.string.backup_export)
            awaitText(R.string.backup_password_mismatch)
            compose.onNodeWithText(context.getString(R.string.backup_password)).performScrollTo().performTextInput("synthetic-pass")
            compose.onNodeWithText(context.getString(R.string.backup_confirm_password)).performScrollTo().performTextInput("synthetic-pass")
            registry.uri = document()
            click(R.string.backup_export)
            awaitText(R.string.backup_saved)
            val encrypted = BackupDocuments.read(context.contentResolver, registry.uri!!)
            assertTrue(BackupCrypto.isEncrypted(encrypted))
            assertFalse(encrypted.decodeToString().contains("synthetic-ui-key"))
            assertEquals("synthetic-ui-key", BackupCodec.decode(BackupCrypto.decrypt(encrypted, "synthetic-pass".toCharArray()), true).profiles.single().loginKey)
        } finally { SecureVault(context, "default").clearLoginKey() }
    }

    @Test fun importRequiresPreviewAndConfirmationAndCancelLeavesProfilesUntouched() {
        show()
        capture("integration-backup.png")
        val backup = SettingsBackup(listOf(BackupProfile(ServerProfile("external", "Imported synthetic", "https://import.example.invalid/", true))))
        registry.uri = document(BackupCodec.encode(backup))
        click(R.string.backup_import)
        awaitText(R.string.backup_preview)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.action)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
        capture("integration-backup-preview.png")
        click(R.string.cancel)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
        click(R.string.backup_import)
        awaitText(R.string.backup_preview)
        click(R.string.backup_confirm_import)
        compose.waitUntil(10_000) { SettingsRepository(context, name).profilesState.value.size == 2 }
        assertEquals("https://example.invalid/", SettingsRepository(context, name).activeProfile.serverUrl)
        assertEquals("Imported synthetic", SettingsRepository(context, name).profilesState.value.last().name)
    }

    @Test fun corruptAndWrongPasswordDocumentsDoNotWriteProfiles() {
        show()
        registry.uri = document("{invalid".toByteArray())
        click(R.string.backup_import)
        awaitText(R.string.backup_error_corrupt)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
        val bytes = BackupCodec.encode(SettingsBackup(listOf(BackupProfile(ServerProfile("external", "Synthetic", "https://example.invalid/")))))
        registry.uri = document(BackupCrypto.encrypt(bytes, "synthetic-pass".toCharArray()))
        click(R.string.backup_import)
        awaitText(R.string.backup_decrypt)
        compose.onNodeWithText(context.getString(R.string.backup_password)).performTextInput("wrong-password")
        compose.onAllNodesWithText(context.getString(R.string.backup_decrypt)).onLast().performClick()
        awaitText(R.string.backup_error_auth)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
        compose.onNodeWithText(context.getString(R.string.backup_password)).performTextInput("synthetic-pass")
        compose.onAllNodesWithText(context.getString(R.string.backup_decrypt)).onLast().performClick()
        awaitText(R.string.backup_preview)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
    }

    @Test fun safResultStaysRegisteredBehindLockAndImportCannotConfirmWhileLocked() {
        show()
        val incoming = SettingsBackup(listOf(BackupProfile(ServerProfile("external", "Locked import", "https://locked.example.invalid/"))))
        registry.uri = document(BackupCodec.encode(incoming))
        registry.deferred = true
        click(R.string.backup_import)
        compose.waitUntil { registry.pendingCode != null }
        compose.runOnIdle {
            locked.value = true
            registry.dispatchResult(registry.pendingCode!!, Activity.RESULT_OK, Intent().setData(registry.uri))
        }
        compose.waitUntil(10_000) {
            androidx.lifecycle.ViewModelProvider(compose.activity)[BackupSession::class.java].preview != null
        }
        compose.onNodeWithText(context.getString(R.string.backup_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.backup_confirm_import)).assertDoesNotExist()
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
        compose.runOnIdle { locked.value = false }
        awaitText(R.string.backup_preview)
        click(R.string.backup_confirm_import)
        compose.waitUntil { SettingsRepository(context, name).profilesState.value.size == 2 }
    }

    @Test fun legacyDraftEnvelopeIsRejectedBeforePasswordAndCreatesNoProfile() {
        show()
        val encoder = java.util.Base64.getEncoder()
        val legacy = org.json.JSONObject()
            .put("format", "meshcentral-settings-encrypted")
            .put("version", 1)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("cipher", "AES-256-GCM")
            .put("iterations", 210_000)
            .put("salt", encoder.encodeToString(ByteArray(16) { (it + 1).toByte() }))
            .put("iv", encoder.encodeToString(ByteArray(12) { (it + 3).toByte() }))
            .put("ciphertext", encoder.encodeToString(ByteArray(32) { (it + 7).toByte() }))
        registry.uri = document(legacy.toString().toByteArray())
        click(R.string.backup_import)
        awaitText(R.string.backup_error_unsupported)

        val session = androidx.lifecycle.ViewModelProvider(compose.activity)[BackupSession::class.java]
        assertNull("A legacy draft must never reach the password step", session.encryptedInput)
        assertNull("A legacy draft must never expose a preview", session.preview)
        compose.onNodeWithText(context.getString(R.string.backup_decrypt)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.backup_preview)).assertDoesNotExist()
        assertEquals("A rejected envelope must not add profiles", 1, SettingsRepository(context, name).profilesState.value.size)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun futureVersionExplainsRequiredUpdateWithoutWriting() {
        show()
        val json = org.json.JSONObject(BackupCodec.encode(SettingsBackup(emptyList())).decodeToString()).put("version", 2)
        registry.uri = document(json.toString().toByteArray())
        click(R.string.backup_import)
        awaitText(R.string.backup_error_newer)
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
    }

    @Test fun previewShowsSkippedExtensionsDuplicatesAndSecurityPreferences() {
        show()
        val incoming = SettingsBackup(listOf(BackupProfile(ServerProfile("external", "Duplicate", "https://example.invalid/"))),
            BackupPreferences(blockScreenshots = false, lockTimeoutSeconds = 300, hideDevicesWhenLocked = true))
        val json = org.json.JSONObject(BackupCodec.encode(incoming).decodeToString())
            .put("extensions", org.json.JSONObject().put("portForwards", org.json.JSONArray()))
        registry.uri = document(json.toString().toByteArray())
        click(R.string.backup_import)
        awaitText(R.string.backup_preview)
        compose.onNodeWithText(context.getString(R.string.backup_extension_skipped, "portForwards")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_duplicate)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_skip_duplicates)).performScrollTo().assertIsDisplayed()
        val on = context.getString(R.string.backup_value_on)
        val off = context.getString(R.string.backup_value_off)
        compose.onNodeWithText(context.getString(R.string.backup_preferences_preview, off, 300L,
            context.getString(R.string.settings_theme_system), off, on, off, on)).performScrollTo().assertIsDisplayed()
        click(R.string.backup_confirm_import)
        awaitText(R.string.backup_title)
        compose.waitUntil { !androidx.lifecycle.ViewModelProvider(compose.activity)[BackupSession::class.java].busy }
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
    }

    @Test fun passwordInputsAreClearedWhenLocked() {
        show()
        compose.onNodeWithText(context.getString(R.string.backup_password)).performTextInput("temporary-password")
        compose.onNodeWithText(context.getString(R.string.backup_confirm_password)).performScrollTo().performTextInput("temporary-password")
        compose.runOnIdle { locked.value = true }
        compose.waitForIdle()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithText(context.getString(R.string.backup_password)).assertTextEquals(context.getString(R.string.backup_password), "")
        compose.onNodeWithText(context.getString(R.string.backup_confirm_password)).assertDoesNotExist()
    }
}
