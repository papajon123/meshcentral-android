package ru.papajon.meshcentralmobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val installed = InstalledRelease(UpdatePolicy.PACKAGE_NAME, 18, UpdatePolicy.CERTIFICATE_SHA256)
    private val candidate = UpdateCandidate(installed.packageName, 20, "0.8.0", "a".repeat(64), installed.signerSha256)
    private val prefs = UpdatePreferences(enabled = true, consentVersionCode = 18)
    private val now = 2 * UpdatePolicy.INTERVAL_MILLIS

    @Test fun enforcesDailyIntervalIncludingFailuresAndClockRollback() {
        val attempted = prefs.copy(lastAttemptMillis = now, lastResult = UpdateResult.Network)
        assertFalse(UpdatePolicy.shouldCheck(attempted, now + UpdatePolicy.INTERVAL_MILLIS - 1))
        assertFalse(UpdatePolicy.shouldCheck(attempted, now - 1))
        assertTrue(UpdatePolicy.shouldCheck(attempted, now + UpdatePolicy.INTERVAL_MILLIS))
    }
    @Test fun skipsVersion() { assertEquals(UpdateDecision.Suppressed, evaluate(UpdatePolicy.skip(prefs, 20))) }
    @Test fun equalVersionIsNotAnUpdate() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.evaluate(candidate.copy(versionCode = 18), installed, prefs, now))
    }
    @Test fun lowerVersionIsNotAnUpdate() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.evaluate(candidate.copy(versionCode = 17), installed, prefs, now))
    }
    @Test fun laterExpiresAfterOneDay() {
        val later = UpdatePolicy.later(prefs, 20, now)
        assertEquals(UpdateDecision.Suppressed, evaluate(later))
        assertTrue(UpdatePolicy.evaluate(candidate, installed, later, now + UpdatePolicy.INTERVAL_MILLIS) is UpdateDecision.Available)
    }
    @Test fun neverContactsSourceWithoutConsent() {
        val checker = UpdateChecker { fail("No request without consent"); null }
        assertEquals(UpdateDecision.Disabled, checker.check(installed, UpdatePreferences(), now))
        assertEquals(UpdateDecision.Disabled, checker.check(installed, prefs.copy(enabled = false), now))
    }
    @Test fun consentIsAskedOnceAndSurvivesAppUpdates() {
        assertTrue(UpdatePolicy.needsConsent(UpdatePreferences()))
        assertFalse(UpdatePolicy.needsConsent(prefs))
        assertFalse(UpdatePolicy.needsConsent(prefs.copy(enabled = false)))
        val updatedApp = installed.copy(versionCode = 19)
        val checker = UpdateChecker { null }
        assertEquals(UpdateDecision.UpToDate, checker.check(updatedApp, prefs, now))
    }
    @Test fun manualCheckSkipsDailyIntervalButKeepsCooldown() {
        val attempted = prefs.copy(lastAttemptMillis = now)
        assertFalse(UpdatePolicy.shouldCheck(attempted, now + UpdatePolicy.MANUAL_COOLDOWN_MILLIS - 1, manual = true))
        assertTrue(UpdatePolicy.shouldCheck(attempted, now + UpdatePolicy.MANUAL_COOLDOWN_MILLIS, manual = true))
        assertFalse(UpdatePolicy.shouldCheck(attempted, now + UpdatePolicy.MANUAL_COOLDOWN_MILLIS))
        assertTrue(UpdatePolicy.shouldCheck(attempted, now - 1, manual = true))
    }
    @Test fun manualCheckContactsSourceInsideDailyInterval() {
        var requests = 0
        val checker = UpdateChecker { requests++; null }
        val attempted = prefs.copy(lastAttemptMillis = now)
        assertEquals(UpdateDecision.NotDue, checker.check(installed, attempted, now + UpdatePolicy.MANUAL_COOLDOWN_MILLIS))
        assertEquals(UpdateDecision.UpToDate, checker.check(installed, attempted, now + UpdatePolicy.MANUAL_COOLDOWN_MILLIS, manual = true))
        assertEquals(1, requests)
    }
    @Test fun neverContactsSourceBeforeInterval() {
        val checker = UpdateChecker { fail("No request inside interval"); null }
        assertEquals(UpdateDecision.NotDue, checker.check(installed, prefs.copy(lastAttemptMillis = now), now + 1))
    }
    @Test fun rejectsDifferentInstalledSigner() {
        assertEquals(UpdateDecision.Failure(UpdateProblem.WrongSigner), UpdatePolicy.evaluate(candidate, installed.copy(signerSha256 = "b".repeat(64)), prefs, now))
    }
    @Test fun rejectsDifferentCandidateSigner() {
        assertEquals(UpdateDecision.Failure(UpdateProblem.WrongSigner), UpdatePolicy.evaluate(candidate.copy(signerSha256 = "b".repeat(64)), installed, prefs, now))
    }
    @Test fun newerVersionSurvivesSkipOfPreviousVersion() { assertTrue(evaluate(prefs.copy(skippedVersionCode = 19)) is UpdateDecision.Available) }
    @Test fun debugPackageIsNotCompatibleEvenWithPublicMetadata() {
        assertEquals(UpdateDecision.Failure(UpdateProblem.WrongSigner), UpdatePolicy.evaluate(candidate, installed.copy(packageName = installed.packageName + ".debug"), prefs, now))
    }
    @Test fun generatedBrowserPageUsesOnlyValidatedVersion() {
        assertEquals("https://github.com/papajon123/meshcentral-android/releases/tag/v0.8.0", candidate.releasePage)
        assertEquals(UpdateDecision.Failure(UpdateProblem.InvalidMetadata), UpdatePolicy.evaluate(candidate.copy(versionName = "0.8.0?token=example"), installed, prefs, now))
    }
    @Test fun mapsRateLimitWithoutExposingException() {
        assertEquals(UpdateDecision.Failure(UpdateProblem.RateLimited), UpdateChecker { throw ReleaseRateLimitException() }.check(installed, prefs, now))
    }
    private fun evaluate(preferences: UpdatePreferences) = UpdatePolicy.evaluate(candidate, installed, preferences, now)
}
