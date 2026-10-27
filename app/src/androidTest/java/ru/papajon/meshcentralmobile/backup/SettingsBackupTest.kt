package ru.papajon.meshcentralmobile.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.files.FixtureDocumentsProvider
import ru.papajon.meshcentralmobile.model.*
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking

class SettingsBackupTest {
    @Test fun rejectsAndroidLenientJsonBeforeParsingNestedDataOrImporting() {
        val valid = BackupCodec.encode(backup).decodeToString()
        listOf(valid.replace("\"format\":", "format:"), valid.replace("\"format\":", "'format':"),
            "{\"format\":/*\"*/" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw.toByteArray()) }
        }
        val encrypted = BackupCrypto.encrypt(valid.toByteArray(), "synthetic-pass".toCharArray()).decodeToString()
            .replace("\"format\":", "'format':").toByteArray()
        assertFalse(BackupCrypto.isEncrypted(encrypted))
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(encrypted, "synthetic-pass".toCharArray()) }
        assertEquals(1, SettingsRepository(context, name).profilesState.value.size)
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "backup-test-${UUID.randomUUID()}"
    private val settings = SettingsRepository(context, name)
    private val ids = mutableListOf<String>()
    private val backup = SettingsBackup(listOf(BackupProfile(ServerProfile("source", "Synthetic", "https://mesh.example.invalid/team/",
        onboardingCompleted = true, pinnedSpkiSha256 = "a".repeat(64),
        customShortcuts = listOf(CustomShortcut("Example", "R", win = true)),
        favoriteCommands = listOf(FavoriteCommand("Example", "echo example"))), listOf(FavoriteLink("Workstation", "node//example")),
        "synthetic-login-key")), BackupPreferences(blockScreenshots = true, lockTimeoutSeconds = 15))

    @After fun cleanup() {
        settings.profilesState.value.filter { it.id != "default" }.forEach { ids += it.id }
        ids.distinct().forEach { SecureVault(context, it).apply { clearLoginKey(); clearCredentials() } }
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        FixtureDocumentsProvider.resetForTest(context)
    }

    @Test fun additiveImportPreservesExistingProfileAndRestoresOwnedDataAfterReread() = runBlocking {
        settings.updateServerUrl("https://existing.example.invalid/")
        settings.updateBlockScreenshots(false)
        val old = settings.activeProfile
        val repository = SettingsBackupRepository(settings)
        assertEquals(1, repository.importConfirmed(backup, applyPreferences = false))
        val reread = SettingsRepository(context, name)
        assertEquals(old, reread.activeProfile)
        assertFalse(reread.getSettings().blockScreenshots)
        val restored = reread.profilesState.value.single { it.id != old.id }
        assertNotEquals("source", restored.id)
        assertNull(restored.pinnedSpkiSha256)
        assertEquals("a".repeat(64), restored.expectedSpkiSha256)
        assertFalse(restored.allowHttpInDebug)
        assertFalse(restored.trustSelfSignedCertificates)
        assertEquals(backup.profiles.single().favorites, reread.favoritesForProfile(restored))
        assertEquals(backup.profiles.single().profile.customShortcuts, restored.customShortcuts)
        assertEquals(backup.profiles.single().profile.favoriteCommands, restored.favoriteCommands)
        assertEquals("synthetic-login-key", SecureVault(context, restored.id).getLoginKey(restored.serverUrl))
        assertNull(SecureVault(context, restored.id).getCredentials(restored.serverUrl))
        assertNull(SecureVault(context, restored.id).rememberedDevice.get(restored.serverUrl, "synthetic-user"))
        assertEquals(old.id, reread.activeProfileIdState.value)
        repository.importConfirmed(backup, applyPreferences = true)
        assertTrue(SettingsRepository(context, name).getSettings().blockScreenshots)
        assertEquals(15L, SettingsRepository(context, name).getSettings().lockTimeoutSeconds)
    }

    @Test fun malformedInputAndProfileCapacityCannotPartiallyImport() {
        repeat(ProfileCatalog.MAX_PROFILES - 1) { settings.createProfile("Profile $it", "https://example.invalid/$it/") }
        val before = settings.profilesState.value
        assertFalse(SettingsBackupRepository(settings).canImport(backup))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SettingsBackupRepository(settings).importConfirmed(backup, true) } }
        assertEquals(before, SettingsRepository(context, name).profilesState.value)
        val invalid = backup.copy(profiles = backup.profiles + backup.profiles.single().copy(profile = backup.profiles.single().profile.copy(serverUrl = "https://user:secret@example.invalid/")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SettingsBackupRepository(settings).importConfirmed(invalid, true) } }
        assertEquals(before, SettingsRepository(context, name).profilesState.value)
    }

    @Test fun exportAllowlistsFieldsAndRequiresEncryptionForKeysAtTheDocumentBoundary() = runBlocking {
        val profile = settings.createProfile("Synthetic", "https://example.invalid/")
        val vault = SecureVault(context, profile.id)
        vault.saveCredentials("synthetic-user", "synthetic-password", profile.serverUrl)
        vault.saveLoginKey(profile.serverUrl, "synthetic-login-key")
        val repository = SettingsBackupRepository(settings)
        val plain = BackupCodec.encode(repository.snapshot(includeLoginKeys = false)).decodeToString()
        listOf("synthetic-user", "synthetic-password", "synthetic-login-key", "cookie", "token", "biometric", "\"pin\"").forEach { assertFalse(it, plain.contains(it)) }
        val encrypted = BackupCrypto.encrypt(BackupCodec.encode(repository.snapshot(true), true), "synthetic-pass".toCharArray())
        assertFalse(encrypted.decodeToString().contains("synthetic-login-key"))
        val parsed = BackupCodec.decode(BackupCrypto.decrypt(encrypted, "synthetic-pass".toCharArray()), true)
        assertEquals("synthetic-login-key", parsed.profiles.single().loginKey)
    }

    @Test fun safDocumentRoundTripTruncatesAndRejectsNonContentUris() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val uri = FixtureDocumentsProvider.addForTest(context, "backup.json", ByteArray(8000), true, true)
        assertTrue(DocumentsContract.isDocumentUri(context, uri))
        val create = ActivityResultContracts.CreateDocument("application/json").createIntent(context, "backup.json")
        val open = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("application/json", "application/octet-stream"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.action)
        val bytes = BackupCrypto.encrypt(BackupCodec.encode(backup, true), "synthetic-pass".toCharArray())
        BackupDocuments.write(context.contentResolver, uri, bytes)
        assertArrayEquals(bytes, BackupDocuments.read(context.contentResolver, uri))
        val restored = BackupCodec.decode(BackupCrypto.decrypt(BackupDocuments.read(context.contentResolver, uri), "synthetic-pass".toCharArray()), true)
        assertEquals(backup, restored)
        assertEquals(1, SettingsBackupRepository(settings).importConfirmed(restored, false))
        assertEquals(2, SettingsRepository(context, name).profilesState.value.size)
        assertThrows(IllegalArgumentException::class.java) { BackupDocuments.read(context.contentResolver, Uri.parse("file:///not-allowed")) }
        assertThrows(IllegalArgumentException::class.java) { BackupDocuments.write(context.contentResolver, uri, ByteArray(BackupDocuments.MAX_FILE_BYTES + 1)) }
        Unit
    }

    @Test fun readOnlyRefreshVaultCannotResurrectCredentialsOrMutateStoredSecrets() {
        val profile = settings.createProfile("Read-only", "https://example.invalid/")
        val writable = SecureVault(context, profile.id)
        writable.saveCredentials("synthetic-user", "synthetic-password", profile.serverUrl)
        writable.saveLoginKey(profile.serverUrl, "synthetic-key")
        val readOnly = SecureVault(context, profile.id, readOnly = true)
        assertNotNull(readOnly.getCredentials(profile.serverUrl))
        readOnly.clearAll()
        readOnly.clearCredentials()
        readOnly.clearLoginKey()
        assertEquals("synthetic-key", writable.getLoginKey(profile.serverUrl))
        assertNotNull(writable.getCredentials(profile.serverUrl))
        writable.clearCredentials()
        readOnly.saveCredentials("late-user", "late-password", profile.serverUrl)
        readOnly.saveLoginKey(profile.serverUrl, "late-key")
        assertNull(writable.getCredentials(profile.serverUrl))
        assertEquals("synthetic-key", writable.getLoginKey(profile.serverUrl))
    }
}
