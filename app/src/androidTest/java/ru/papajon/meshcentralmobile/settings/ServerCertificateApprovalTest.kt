package ru.papajon.meshcentralmobile.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.security.SecureVault
import java.util.UUID

class ServerCertificateApprovalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "tls-approval-${UUID.randomUUID()}"
    private val settings = SettingsRepository(context, name).also { it.updateServerUrl("https://mesh.example/") }
    private val vault = SecureVault(context, preferencesName = "$name-vault")
    private val appLock = AppLockController(settings, vault)
    private val pin = "ab".repeat(32)
    private val newPin = "cd".repeat(32)
    private fun failure(kind: ServerCertificateFailureKind) = ServerCertificateFailure(
        kind, newPin, "CN=mesh.example", 0, 2_000_000_000_000, "https://mesh.example/", "cd".repeat(32),
    )

    @After fun clearFixtures() {
        vault.clearAll()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("$name-vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun unknownCertificateIsSavedOnlyOnExplicitApproval() {
        val approval = ServerCertificateApproval(settings, appLock)
        assertNull(settings.activeProfile.pinnedSpkiSha256)
        assertTrue(approval.approve(failure(ServerCertificateFailureKind.Untrusted)))
        assertEquals(newPin, SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
    }

    @Test fun validityChainAndNameFailuresCannotBeApproved() {
        val approval = ServerCertificateApproval(settings, appLock)
        for (kind in listOf(ServerCertificateFailureKind.Expired, ServerCertificateFailureKind.NotYetValid,
            ServerCertificateFailureKind.ServerConfiguration, ServerCertificateFailureKind.HostnameMismatch)) {
            assertFalse(approval.approve(failure(kind)))
            assertNull(SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
        }
    }

    @Test fun changedCertificateRequiresFreshUnlockAndConfirmation() {
        settings.updatePinnedServerKey(pin)
        vault.savePin("1234")
        val approval = ServerCertificateApproval(settings, appLock)
        val failure = failure(ServerCertificateFailureKind.Changed)
        assertFalse(approval.approve(failure))
        approval.requestReplacement(failure)
        assertTrue(appLock.isLocked)
        assertFalse(approval.approve(failure))
        assertFalse(appLock.unlockWithPin("9999"))
        assertFalse(approval.approve(failure))
        assertTrue(appLock.unlockWithPin("1234"))
        assertEquals(pin, settings.activeProfile.pinnedSpkiSha256)
        assertTrue(approval.approve(failure))
        assertEquals(newPin, SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
    }

    @Test fun cancellingReplacementKeepsOldKey() {
        settings.updatePinnedServerKey(pin)
        val approval = ServerCertificateApproval(settings, appLock)
        val failure = failure(ServerCertificateFailureKind.Changed)
        approval.requestReplacement(failure)
        approval.dismiss()
        assertFalse(approval.approve(failure))
        assertEquals(pin, settings.activeProfile.pinnedSpkiSha256)
    }

    @Test fun staleDialogCannotSaveToAnotherProfileEvenAtSameAddress() {
        val approval = ServerCertificateApproval(settings, appLock)
        val other = settings.createProfile("Other", "https://mesh.example/")
        settings.activateProfile(other.id)
        assertFalse(approval.approve(failure(ServerCertificateFailureKind.Untrusted)))
        assertNull(settings.activeProfile.pinnedSpkiSha256)
    }

    @Test fun changedEndpointClearsPinAndInvalidatesDialog() {
        settings.updatePinnedServerKey(pin)
        val approval = ServerCertificateApproval(settings, appLock)
        val failure = failure(ServerCertificateFailureKind.Changed)
        approval.requestReplacement(failure)
        settings.updateServerUrl("https://other.example/")
        assertNull(settings.activeProfile.pinnedSpkiSha256)
        assertFalse(approval.approve(failure))
    }

    @Test fun changedCandidateOrPinInvalidatesReplacement() {
        settings.updatePinnedServerKey(pin)
        val approval = ServerCertificateApproval(settings, appLock)
        val failure = failure(ServerCertificateFailureKind.Changed)
        approval.requestReplacement(failure)
        assertFalse(approval.approve(failure.copy(spkiSha256 = "ef".repeat(32))))
        settings.updatePinnedServerKey("00".repeat(32))
        assertFalse(approval.approve(failure))
    }

    @Test fun forgettingAlsoRequiresUnlockAndCannotBypassReplacementGuard() {
        settings.updatePinnedServerKey(pin)
        vault.savePin("1234")
        val approval = ServerCertificateApproval(settings, appLock)
        approval.requestForget()
        assertTrue(appLock.isLocked)
        assertFalse(approval.confirmForget())
        assertTrue(appLock.unlockWithPin("1234"))
        assertTrue(approval.confirmForget())
        assertNull(SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
    }

    @Test fun forgottenPinCannotReturnFromCatalogBackup() {
        settings.updatePinnedServerKey(pin)
        settings.updatePinnedServerKey(null)
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("server_profiles_v1", "corrupt-json").commit()
        assertNull(SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
    }

    @Test fun replacingPinAlsoRevokesOldPinInCatalogBackup() {
        settings.updatePinnedServerKey(pin)
        settings.updatePinnedServerKey(newPin)
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("server_profiles_v1", "corrupt-json").commit()
        assertEquals(newPin, SettingsRepository(context, name).activeProfile.pinnedSpkiSha256)
    }
}
