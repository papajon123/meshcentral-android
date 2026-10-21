package ru.papajon.meshcentralmobile.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.model.DEFAULT_LOCK_TIMEOUT_SECONDS
import ru.papajon.meshcentralmobile.model.DEFAULT_SERVER_URL
import ru.papajon.meshcentralmobile.model.FavoriteLink

class SettingsRepository(context: Context) {
    val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val settingsState = mutableStateOf(loadSettings())
    val favoritesState = mutableStateOf(loadFavorites())

    fun getSettings(): AppSettings = settingsState.value

    fun updateServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
        settingsState.value = loadSettings()
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        settingsState.value = loadSettings()
    }

    fun updateAllowHttpInDebug(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_HTTP_DEBUG, enabled).apply()
        settingsState.value = loadSettings()
    }

    fun updateBlockScreenshots(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, enabled).apply()
        settingsState.value = loadSettings()
    }

    fun updateTrustSelfSignedCertificates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRUST_SELF_SIGNED, enabled).apply()
        settingsState.value = loadSettings()
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
        return AppSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
            allowHttpInDebug = prefs.getBoolean(KEY_ALLOW_HTTP_DEBUG, false),
            trustSelfSignedCertificates = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false),
            blockScreenshots = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false),
            biometricUnlockEnabled = prefs.getBoolean(KEY_BIOMETRIC_UNLOCK, false),
            lockTimeoutSeconds = prefs.getLong(KEY_LOCK_TIMEOUT_SECONDS, DEFAULT_LOCK_TIMEOUT_SECONDS),
        )
    }

    private fun loadFavorites(): List<FavoriteLink> {
        val stored = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val array = JSONArray(stored)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name")
                val url = item.optString("url")
                if (name.isNotBlank() && url.isNotBlank()) {
                    add(FavoriteLink(name = name, url = url))
                }
            }
        }
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
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
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
    }
}
