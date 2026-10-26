package ru.papajon.meshcentralmobile.settings

import ru.papajon.meshcentralmobile.json.optText

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.model.DEFAULT_LOCK_TIMEOUT_SECONDS
import ru.papajon.meshcentralmobile.model.DEFAULT_SERVER_URL
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.model.CustomShortcut
import ru.papajon.meshcentralmobile.model.FavoriteCommand
import ru.papajon.meshcentralmobile.security.CredentialScope
import ru.papajon.meshcentralmobile.security.SecureVault
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale

class SettingsRepository(context: Context, preferencesName: String = PREFS_NAME) {
    val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    val profilesState = mutableStateOf(loadProfiles())
    val activeProfileIdState = mutableStateOf(
        prefs.getString(KEY_ACTIVE_PROFILE, null)?.takeIf { id -> profilesState.value.any { it.id == id } }
            ?: profilesState.value.first().id,
    )
    val activeProfile: ServerProfile get() = profilesState.value.first { it.id == activeProfileIdState.value }

    val settingsState = mutableStateOf(loadSettings())
    val favoritesState = mutableStateOf(loadFavorites())

    init {
        // The first profile inherits the legacy settings. PIN/privacy and their existing keys stay global.
        if (usesLegacyCatalog()) {
            val legacyFavorites = prefs.getString(KEY_FAVORITES, null)
            val editor = prefs.edit()
                .putString(KEY_PROFILES, ProfileCatalog.encode(profilesState.value))
                .putString(KEY_ACTIVE_PROFILE, activeProfileIdState.value)
            if (legacyFavorites != null) editor.putString(favoriteKey(activeProfile), legacyFavorites)
            editor.apply()
        }
    }

    fun createProfile(name: String, serverUrl: String, onboardingCompleted: Boolean = false): ServerProfile {
        require(profilesState.value.size < ProfileCatalog.MAX_PROFILES) { "Profile limit reached" }
        require(name.isNotBlank() && name.trim().length <= 80) { "Invalid profile name" }
        val profile = ServerProfile(UUID.randomUUID().toString(), name.trim(), serverUrl.trim(),
            onboardingCompleted = onboardingCompleted)
        persistProfiles(profilesState.value + profile, activeProfileIdState.value)
        return profile
    }

    fun activateProfile(id: String): Boolean {
        if (profilesState.value.none { it.id == id }) return false
        if (id != activeProfileIdState.value) SecureVault(appContext, activeProfileIdState.value).rememberedDevice.clear()
        persistProfiles(profilesState.value, id)
        return true
    }

    fun renameProfile(id: String, name: String): Boolean {
        if (name.isBlank() || name.trim().length > 80 || profilesState.value.none { it.id == id }) return false
        persistProfiles(profilesState.value.map { if (it.id == id) it.copy(name = name.trim()) else it }, activeProfileIdState.value)
        return true
    }

    fun removeProfile(id: String): Boolean {
        if (profilesState.value.size <= 1 || profilesState.value.none { it.id == id }) return false
        SecureVault(appContext, id).rememberedDevice.clear()
        val remaining = profilesState.value.filterNot { it.id == id }
        val active = activeProfileIdState.value.takeIf { it != id } ?: remaining.first().id
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("profile_favorites.$id.") }.forEach(editor::remove)
        editor.apply()
        persistProfiles(remaining, active, removedId = id)
        return true
    }

    fun getSettings(): AppSettings = settingsState.value

    /** Read persisted policy, including changes made by another repository instance. */
    fun currentProfile(id: String): ServerProfile? = loadProfiles().firstOrNull { it.id == id }

    /** Keep the listener strongly referenced until the caller closes its in-flight operation. */
    fun observeProfile(id: String, onChanged: (ServerProfile?) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PROFILES || key == null) onChanged(currentProfile(id))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun addFavoriteCommand(profileId: String, command: FavoriteCommand): Boolean {
        val profile = profilesState.value.firstOrNull { it.id == profileId } ?: return false
        if (!command.valid || profile.favoriteCommands.size >= FavoriteCommand.LIMIT) return false
        persistProfiles(profilesState.value.map {
            if (it.id == profileId) it.copy(favoriteCommands = it.favoriteCommands + command) else it
        }, activeProfileIdState.value)
        return true
    }

    fun removeFavoriteCommand(profileId: String, index: Int) {
        persistProfiles(profilesState.value.map {
            if (it.id == profileId) it.copy(favoriteCommands = it.favoriteCommands.filterIndexed { i, _ -> i != index }) else it
        }, activeProfileIdState.value)
    }

    fun addShortcut(profileId: String, shortcut: CustomShortcut): Boolean {
        val profile = profilesState.value.firstOrNull { it.id == profileId } ?: return false
        if (!shortcut.valid || profile.customShortcuts.size >= CustomShortcut.MAX_SHORTCUTS) return false
        persistProfiles(profilesState.value.map {
            if (it.id == profileId) it.copy(customShortcuts = it.customShortcuts + shortcut) else it
        }, activeProfileIdState.value)
        return true
    }

    fun removeShortcut(profileId: String, index: Int) {
        persistProfiles(profilesState.value.map {
            if (it.id == profileId) it.copy(customShortcuts = it.customShortcuts.filterIndexed { i, _ -> i != index }) else it
        }, activeProfileIdState.value)
    }

    fun updateServerUrl(url: String) {
        if (!CredentialScope.matches(activeProfile.serverUrl, url)) {
            SecureVault(appContext, activeProfile.id).rememberedDevice.clear()
        }
        updateActiveProfile { it.withServerUrl(url) }
    }

    fun updatePinnedServerKey(spkiSha256: String?) {
        require(spkiSha256 == null || spkiSha256.matches(Regex("[0-9a-f]{64}")))
        SecureVault(appContext, activeProfile.id).rememberedDevice.clear()
        updateActiveProfile { it.copy(pinnedSpkiSha256 = spkiSha256) }
    }

    fun completeOnboarding() {
        updateActiveProfile { it.copy(onboardingCompleted = true) }
    }

    fun updateAllowHttpInDebug(enabled: Boolean) {
        updateActiveProfile { it.copy(allowHttpInDebug = enabled) }
    }

    fun updateBlockScreenshots(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, enabled).apply()
        settingsState.value = loadSettings()
    }

    fun updateTrustSelfSignedCertificates(enabled: Boolean) {
        if (activeProfile.trustSelfSignedCertificates != enabled) {
            SecureVault(appContext, activeProfile.id).rememberedDevice.clear()
        }
        updateActiveProfile { it.copy(trustSelfSignedCertificates = enabled) }
    }

    fun updateBiometricUnlock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, enabled).apply()
        settingsState.value = loadSettings()
    }

    fun updateLockTimeoutSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_LOCK_TIMEOUT_SECONDS, seconds).apply()
        settingsState.value = loadSettings()
    }

    fun saveLastBackgroundAt(timestampMillis: Long) {
        prefs.edit().putLong(KEY_LAST_BACKGROUND_AT, timestampMillis).apply()
    }

    fun getLastBackgroundAt(): Long = prefs.getLong(KEY_LAST_BACKGROUND_AT, 0L)

    fun addFavorite(name: String, url: String) {
        val updated = favoritesState.value
            .filterNot { it.url == url }
            .plus(FavoriteLink(name = name.ifBlank { url }, url = url))
        saveFavorites(updated)
    }

    fun removeFavorite(url: String) {
        saveFavorites(favoritesState.value.filterNot { it.url == url })
    }

    private fun loadSettings(): AppSettings {
        val profile = activeProfile
        return AppSettings(
            serverUrl = profile.serverUrl,
            onboardingCompleted = profile.onboardingCompleted,
            allowHttpInDebug = profile.allowHttpInDebug,
            trustSelfSignedCertificates = profile.trustSelfSignedCertificates,
            blockScreenshots = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false),
            biometricUnlockEnabled = prefs.getBoolean(KEY_BIOMETRIC_UNLOCK, false),
            lockTimeoutSeconds = prefs.getLong(KEY_LOCK_TIMEOUT_SECONDS, DEFAULT_LOCK_TIMEOUT_SECONDS),
        )
    }

    private fun loadFavorites(profile: ServerProfile = activeProfile): List<FavoriteLink> {
        val stored = prefs.getString(favoriteKey(profile), null)
            ?: (if (usesLegacyCatalog() && profile.id == ProfileCatalog.DEFAULT_ID) prefs.getString(KEY_FAVORITES, null) else null)
            ?: return emptyList()
        return runCatching {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optText("name")
                val url = item.optText("url")
                if (name.isNotBlank() && url.isNotBlank()) {
                    add(FavoriteLink(name = name, url = url))
                }
            }
        }
        }.getOrDefault(emptyList())
    }

    private fun saveFavorites(favorites: List<FavoriteLink>) {
        val array = JSONArray()
        favorites.forEach { favorite ->
            array.put(
                JSONObject()
                    .put("name", favorite.name)
                    .put("url", favorite.url),
            )
        }
        prefs.edit().putString(favoriteKey(activeProfile), array.toString())
            .putString(KEY_FAVORITES, array.toString()).apply()
        favoritesState.value = favorites
    }

    private fun loadProfiles(): List<ServerProfile> {
        val current = ProfileCatalog.decode(prefs.getString(KEY_PROFILES, null))
        if (current.isNotEmpty()) return current
        val previous = ProfileCatalog.decode(prefs.getString(KEY_PROFILES_BACKUP, null))
        if (previous.isNotEmpty()) return previous
        val url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return listOf(ServerProfile(ProfileCatalog.DEFAULT_ID,
            runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "MeshCentral",
            url, prefs.getBoolean(KEY_ONBOARDING_DONE, false), prefs.getBoolean(KEY_ALLOW_HTTP_DEBUG, false),
            prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false)))
    }

    private fun usesLegacyCatalog(): Boolean =
        ProfileCatalog.decode(prefs.getString(KEY_PROFILES, null)).isEmpty() &&
            ProfileCatalog.decode(prefs.getString(KEY_PROFILES_BACKUP, null)).isEmpty()

    private fun favoriteKey(profile: ServerProfile): String {
        val server = CredentialScope.normalize(profile.serverUrl) ?: profile.serverUrl.trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(server.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return "profile_favorites.${profile.id}.$digest"
    }

    private fun updateActiveProfile(change: (ServerProfile) -> ServerProfile) {
        persistProfiles(profilesState.value.map { if (it.id == activeProfileIdState.value) change(it) else it }, activeProfileIdState.value)
    }

    private fun persistProfiles(profiles: List<ServerProfile>, activeId: String, removedId: String? = null) {
        val profile = profiles.first { it.id == activeId }
        val old = prefs.getString(KEY_PROFILES, null)
        val favorites = loadFavorites(profile)
        val editor = prefs.edit()
        val backup = ProfileCatalog.decode(old).filterNot { it.id == removedId }.map { previous ->
            val current = profiles.firstOrNull { it.id == previous.id }
            // Recovery may restore old display metadata, but never resurrect revoked trust or
            // apply a new endpoint's key to the previous endpoint.
            previous.copy(pinnedSpkiSha256 = current?.pinnedSpkiSha256?.takeIf {
                CredentialScope.matches(previous.serverUrl, current.serverUrl)
            })
        }
        if (backup.isNotEmpty()) editor.putString(KEY_PROFILES_BACKUP, ProfileCatalog.encode(backup))
        editor.putString(KEY_PROFILES, ProfileCatalog.encode(profiles))
            .putString(KEY_ACTIVE_PROFILE, activeId)
            .putString(KEY_SERVER_URL, profile.serverUrl)
            .putBoolean(KEY_ONBOARDING_DONE, profile.onboardingCompleted)
            .putBoolean(KEY_ALLOW_HTTP_DEBUG, profile.allowHttpInDebug)
            .putBoolean(KEY_TRUST_SELF_SIGNED, profile.trustSelfSignedCertificates)
            .putString(KEY_FAVORITES, JSONArray().apply {
                favorites.forEach { put(JSONObject().put("name", it.name).put("url", it.url)) }
            }.toString()).apply()
        profilesState.value = profiles
        activeProfileIdState.value = activeId
        settingsState.value = loadSettings()
        favoritesState.value = favorites
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ALLOW_HTTP_DEBUG = "allow_http_debug"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_BIOMETRIC_UNLOCK = "biometric_unlock"
        private const val KEY_LOCK_TIMEOUT_SECONDS = "lock_timeout_seconds"
        private const val KEY_LAST_BACKGROUND_AT = "last_background_at"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PROFILES = "server_profiles_v1"
        private const val KEY_PROFILES_BACKUP = "server_profiles_v1_previous"
        private const val KEY_ACTIVE_PROFILE = "active_server_profile"
    }
}
