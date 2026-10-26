package ru.papajon.meshcentralmobile.update

import java.io.IOException

data class InstalledRelease(val packageName: String, val versionCode: Long, val signerSha256: String)

data class UpdateCandidate(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val signerSha256: String,
) {
    // Never use a URL supplied by release metadata for the browser action.
    val releasePage: String get() = "https://github.com/papajon123/meshcentral-android/releases/tag/v$versionName"
}

data class UpdatePreferences(
    val enabled: Boolean = false,
    val consentVersionCode: Long = 0,
    val skippedVersionCode: Long = 0,
    val deferredVersionCode: Long = 0,
    val deferredUntilMillis: Long = 0,
    val lastAttemptMillis: Long = 0,
    val lastResult: UpdateResult = UpdateResult.Never,
)

enum class UpdateResult { Never, UpToDate, Available, Suppressed, Network, RateLimited, InvalidMetadata, WrongSigner }
enum class UpdateProblem { Network, RateLimited, InvalidMetadata, WrongSigner }

sealed interface UpdateDecision {
    data class Available(val candidate: UpdateCandidate) : UpdateDecision
    data object UpToDate : UpdateDecision
    data object Suppressed : UpdateDecision
    data object NotDue : UpdateDecision
    data object Disabled : UpdateDecision
    data class Failure(val problem: UpdateProblem) : UpdateDecision
}

fun interface LatestReleaseSource {
    /** Includes prereleases. Null means a successfully read, empty public release list. */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun latest(): UpdateCandidate?
}

class ReleaseRateLimitException : IOException("Release service rate limit")

/** Adapted from the private client's policy, without downloads, installation or manual bypasses. */
object UpdatePolicy {
    const val INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    /** A manual check may run any time, but not twice within a minute. */
    const val MANUAL_COOLDOWN_MILLIS = 60 * 1000L
    const val PACKAGE_NAME = "io.github.papajon123.meshcentral"
    const val CERTIFICATE_SHA256 = "5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f"
    val VERSION = Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")

    /** The answer is asked once and survives app updates; the switch in Settings changes it. */
    fun hasConsent(preferences: UpdatePreferences): Boolean = preferences.enabled && preferences.consentVersionCode > 0

    fun needsConsent(preferences: UpdatePreferences): Boolean = preferences.consentVersionCode <= 0

    /** Automatic checks run once a day; a manual check only waits out a short cooldown. */
    fun shouldCheck(preferences: UpdatePreferences, nowMillis: Long, manual: Boolean = false): Boolean {
        val last = preferences.lastAttemptMillis
        if (last <= 0) return true
        if (manual) return nowMillis < last || nowMillis - last >= MANUAL_COOLDOWN_MILLIS
        return nowMillis >= last && nowMillis - last >= INTERVAL_MILLIS
    }

    fun evaluate(candidate: UpdateCandidate, installed: InstalledRelease, preferences: UpdatePreferences, nowMillis: Long): UpdateDecision {
        if (candidate.packageName != PACKAGE_NAME ||
            candidate.versionCode <= 0 || !VERSION.matches(candidate.versionName) || normalizedFingerprint(candidate.sha256) == null
        ) return UpdateDecision.Failure(UpdateProblem.InvalidMetadata)
        if (candidate.packageName != installed.packageName || normalizedFingerprint(candidate.signerSha256) != CERTIFICATE_SHA256 ||
            normalizedFingerprint(installed.signerSha256) != CERTIFICATE_SHA256
        ) return UpdateDecision.Failure(UpdateProblem.WrongSigner)
        if (candidate.versionCode <= installed.versionCode) return UpdateDecision.UpToDate
        if (candidate.versionCode == preferences.skippedVersionCode ||
            candidate.versionCode == preferences.deferredVersionCode && nowMillis < preferences.deferredUntilMillis
        ) return UpdateDecision.Suppressed
        return UpdateDecision.Available(candidate)
    }

    fun skip(preferences: UpdatePreferences, versionCode: Long) = preferences.copy(skippedVersionCode = versionCode)
    fun later(preferences: UpdatePreferences, versionCode: Long, nowMillis: Long) =
        preferences.copy(deferredVersionCode = versionCode, deferredUntilMillis = nowMillis + INTERVAL_MILLIS)

    fun normalizedFingerprint(value: String): String? = value.replace(":", "").lowercase().takeIf {
        it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' }
    }
}

class UpdateChecker(private val source: LatestReleaseSource) {
    fun check(installed: InstalledRelease, preferences: UpdatePreferences, nowMillis: Long, manual: Boolean = false): UpdateDecision {
        if (!UpdatePolicy.hasConsent(preferences)) return UpdateDecision.Disabled
        if (!UpdatePolicy.shouldCheck(preferences, nowMillis, manual)) return UpdateDecision.NotDue
        val candidate = try {
            source.latest()
        } catch (_: ReleaseRateLimitException) {
            return UpdateDecision.Failure(UpdateProblem.RateLimited)
        } catch (_: IOException) {
            return UpdateDecision.Failure(UpdateProblem.Network)
        } catch (_: IllegalArgumentException) {
            return UpdateDecision.Failure(UpdateProblem.InvalidMetadata)
        }
        return candidate?.let { UpdatePolicy.evaluate(it, installed, preferences, nowMillis) } ?: UpdateDecision.UpToDate
    }
}
