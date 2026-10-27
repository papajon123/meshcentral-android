package ru.papajon.meshcentralmobile.backup

import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.security.CredentialScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UnsupportedExportProfile : IllegalArgumentException()

/** Explicit projection, never a preferences/vault dump. Existing profile IDs are never reused. */
class SettingsBackupRepository(private val settings: SettingsRepository) {
    private val context = settings.appContext

    suspend fun snapshot(includeLoginKeys: Boolean): SettingsBackup {
        val profiles = settings.profilesState.value.filter { it.serverUrl.isNotBlank() }
        if (profiles.any { ru.papajon.meshcentralmobile.settings.ServerUrlValidator.validate(it.serverUrl, false) != null })
            throw UnsupportedExportProfile()
        val prefs = settings.getSettings()
        return withContext(Dispatchers.IO) {
        val appearance = settings.readAppearance()
        SettingsBackup(profiles.map { profile ->
            BackupProfile(profile.copy(pinnedSpkiSha256 = profile.pinnedSpkiSha256 ?: profile.expectedSpkiSha256,
                expectedSpkiSha256 = null), settings.favoritesForProfile(profile), if (includeLoginKeys)
                SecureVault(context, profile.id).getLoginKey(profile.serverUrl) else null)
        }, BackupPreferences(prefs.blockScreenshots, prefs.lockTimeoutSeconds, prefs.stylusPrecisionEnabled,
            prefs.stylusHintSeen, appearance.mode.name, appearance.dynamicColor, prefs.hideDevicesWhenLocked))
        }
    }

    /**
     * IDs that duplicate an already configured endpoint or repeat the same normalized endpoint
     * later in this file. The first new occurrence of an endpoint is not a duplicate.
     */
    fun duplicateIds(backup: SettingsBackup): Set<String> {
        val existing = existingScopes()
        val seen = HashSet<String>()
        val duplicates = LinkedHashSet<String>()
        for (entry in backup.profiles) {
            val scope = CredentialScope.normalize(entry.profile.serverUrl) ?: continue
            if (scope in existing || !seen.add(scope)) duplicates += entry.profile.id
        }
        return duplicates
    }

    /** Kept for single-entry callers; [duplicateIds] additionally covers repeats inside the file. */
    fun isDuplicate(entry: BackupProfile): Boolean =
        CredentialScope.normalize(entry.profile.serverUrl)?.let { it in existingScopes() } == true

    /**
     * With [skipDuplicates] only the first new endpoint survives, so a repeated endpoint in the
     * file cannot silently create a second account. Turning it off keeps intentional separate
     * accounts for the same endpoint.
     */
    fun selectedProfiles(backup: SettingsBackup, skipDuplicates: Boolean): List<BackupProfile> {
        if (!skipDuplicates) return backup.profiles
        val existing = existingScopes()
        val seen = HashSet<String>()
        return backup.profiles.filter { entry ->
            val scope = CredentialScope.normalize(entry.profile.serverUrl)
            scope == null || (scope !in existing && seen.add(scope))
        }
    }

    fun canImport(backup: SettingsBackup, skipDuplicates: Boolean = true): Boolean =
        backup.profiles.isNotEmpty() && settings.profilesState.value.size + selectedProfiles(backup, skipDuplicates).size <= ProfileCatalog.MAX_PROFILES

    private fun existingScopes(): Set<String> =
        settings.profilesState.value.mapNotNullTo(HashSet<String>()) {
            CredentialScope.normalize(it.serverUrl)
        }

    /** Called only after the user has reviewed the decoded file and explicitly confirmed. */
    suspend fun importConfirmed(backup: SettingsBackup, applyPreferences: Boolean, skipDuplicates: Boolean = true): Int {
        // Revalidate even when called outside the UI. Whole-document validation precedes any write.
        val validated = withContext(Dispatchers.IO) { BackupCodec.decode(BackupCodec.encode(backup, includeLoginKeys = true), allowLoginKeys = true) }
        require(canImport(validated, skipDuplicates))
        return withContext(kotlinx.coroutines.NonCancellable) {
        val additions = selectedProfiles(validated, skipDuplicates).map { it.copy(profile = it.profile.copy(id = UUID.randomUUID().toString(),
            pinnedSpkiSha256 = null, expectedSpkiSha256 = it.profile.pinnedSpkiSha256,
            allowHttpInDebug = false, trustSelfSignedCertificates = false)) }
        try {
            // Prepare only new namespaces first. A Keystore failure cannot leave partial profiles.
            withContext(Dispatchers.IO) { additions.forEach { entry -> entry.loginKey?.let {
                SecureVault(context, entry.profile.id).saveLoginKey(entry.profile.serverUrl, it)
            } } }
            settings.appendImportedProfiles(additions.map { it.profile to it.favorites }, validated.preferences.takeIf { applyPreferences })
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                additions.forEach { runCatching { SecureVault(context, it.profile.id).clearLoginKey() } }
            }
            throw error
        }
        additions.size
        }
    }
}
