package ru.papajon.meshcentralmobile.update

import android.content.Context

/** Public release metadata and non-secret preferences only; no session or account material. */
class UpdatePreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE)

    fun load() = UpdatePreferences(
        enabled = prefs.getBoolean("enabled", false),
        consentVersionCode = prefs.getLong("consent_version_code", 0),
        skippedVersionCode = prefs.getLong("skipped_version_code", 0),
        deferredVersionCode = prefs.getLong("deferred_version_code", 0),
        deferredUntilMillis = prefs.getLong("deferred_until_millis", 0),
        lastAttemptMillis = prefs.getLong("last_attempt_millis", 0),
        lastResult = UpdateResult.entries.firstOrNull { it.name == prefs.getString("last_result", null) } ?: UpdateResult.Never,
    )

    fun save(value: UpdatePreferences) {
        prefs.edit().putBoolean("enabled", value.enabled)
            .putLong("consent_version_code", value.consentVersionCode)
            .putLong("skipped_version_code", value.skippedVersionCode)
            .putLong("deferred_version_code", value.deferredVersionCode)
            .putLong("deferred_until_millis", value.deferredUntilMillis)
            .putLong("last_attempt_millis", value.lastAttemptMillis)
            .putString("last_result", value.lastResult.name).apply()
    }

    fun candidate(): UpdateCandidate? {
        val version = prefs.getString("candidate_version", null) ?: return null
        return UpdateCandidate(
            UpdatePolicy.PACKAGE_NAME, prefs.getLong("candidate_code", 0), version,
            prefs.getString("candidate_sha", "").orEmpty(), prefs.getString("candidate_signer", "").orEmpty(),
        )
    }

    fun saveCandidate(candidate: UpdateCandidate?) {
        prefs.edit().putString("candidate_version", candidate?.versionName)
            .putLong("candidate_code", candidate?.versionCode ?: 0)
            .putString("candidate_sha", candidate?.sha256)
            .putString("candidate_signer", candidate?.signerSha256).apply()
    }
}
