package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateIssuerExplanation
import ru.papajon.meshcentralmobile.mesh.tls.formatFingerprint
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class ServerCertificateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val failure = ServerCertificateFailure(ServerCertificateFailureKind.Untrusted,
        "0123456789abcdef".repeat(4), "CN=mesh.example, O=Example", 1_760_000_000_000, 1_900_000_000_000, "https://mesh.example/", "ab".repeat(32))

    @Test fun unknownCertificateRequiresExplicitTrustAndDisplaysRealMetadata() {
        var trusted = false
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure, false, { trusted = true }, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.tls_server, failure.serverAddress)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_subject, failure.subject)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_previous_fingerprint)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.tls_new_fingerprint)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.tls_fingerprint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(failure.spkiSha256)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(failure.certificateSha256)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_openssl_command)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_independent_warning)).performScrollTo().assertIsDisplayed()
        assertFalse(trusted)
        compose.onNodeWithText(app.getString(R.string.tls_trust)).performClick()
        compose.runOnIdle { assertTrue(trusted) }
    }

    @Test fun cancellingDoesNotTrustOrRequestReplacement() {
        var trusted = false
        var replaced = false
        var cancelled = false
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure, false, { trusted = true }, { replaced = true }, { cancelled = true })
            }
        }
        compose.onNodeWithText(app.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(trusted); assertFalse(replaced) }
    }

    @Test fun unknownIssuerExplanationStillRequiresExplicitTrust() {
        issuerExplanationRequiresTrust(ServerCertificateIssuerExplanation.UnknownAuthority, R.string.tls_issuer_unknown)
    }

    @Test fun meshCentralRootExplanationStillRequiresExplicitTrust() {
        issuerExplanationRequiresTrust(ServerCertificateIssuerExplanation.MeshCentralRoot, R.string.tls_issuer_meshcentral)
    }

    @Test fun approvableHostnameMismatchShowsASeparateWarning() {
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure.copy(hostnameMismatch = true,
                    issuerExplanation = ServerCertificateIssuerExplanation.MeshCentralRoot), false, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.tls_hostname_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_issuer_meshcentral)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_trust)).assertIsDisplayed()
    }

    @Test fun matchingCertificateDoesNotShowHostnameWarning() {
        compose.setContent {
            MeshCentralMobileTheme { ServerCertificateDialog(failure, false, {}, {}, {}) }
        }
        compose.onNodeWithText(app.getString(R.string.tls_hostname_warning)).assertDoesNotExist()
    }

    @Test fun debugBuildOffersDebugWebView() {
        compose.setContent {
            MeshCentralMobileTheme {
                SettingsScreen(AppSettings(), "https://mesh.example/", false, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.settings_debug_webview)).performScrollTo().assertIsDisplayed()
    }

    private fun issuerExplanationRequiresTrust(explanation: ServerCertificateIssuerExplanation, textId: Int) {
        var trusted = false
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure.copy(issuerExplanation = explanation), false, { trusted = true }, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(textId)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(failure.spkiSha256)).performScrollTo().assertIsDisplayed()
        assertFalse(trusted)
        compose.onNodeWithText(app.getString(R.string.tls_trust)).performClick()
        compose.runOnIdle { assertTrue(trusted) }
    }

    @Test fun serverChainErrorExplainsRepairAndOffersNoTrust() {
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure.copy(kind = ServerCertificateFailureKind.ServerConfiguration), false, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.tls_error_chain)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_trust)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.tls_replace)).assertDoesNotExist()
    }

    @Test fun dateErrorCannotBeOverriddenByTrust() {
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure.copy(kind = ServerCertificateFailureKind.Expired), false, {}, {}, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.tls_error_validity)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_trust)).assertDoesNotExist()
    }

    @Test fun changedCertificateRequiresASecondExplicitConfirmation() {
        val confirming = mutableStateOf(false)
        val previousPin = "cd".repeat(32)
        var trusted = false
        compose.setContent {
            MeshCentralMobileTheme {
                ServerCertificateDialog(failure.copy(kind = ServerCertificateFailureKind.Changed, previousSpkiSha256 = previousPin), confirming.value,
                    { trusted = true }, { confirming.value = true }, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.tls_replace_warning)).assertIsDisplayed()
        assertChangedFingerprints(previousPin)
        compose.onNodeWithText(app.getString(R.string.tls_replace)).performClick()
        compose.runOnIdle { assertFalse(trusted) }
        assertChangedFingerprints(previousPin)
        compose.onNodeWithText(app.getString(R.string.tls_confirm_replace)).performClick()
        compose.runOnIdle { assertTrue(trusted) }
    }

    private fun assertChangedFingerprints(previousPin: String) {
        compose.onNodeWithText(app.getString(R.string.tls_fingerprint)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.tls_previous_fingerprint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(previousPin)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_new_fingerprint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(failure.spkiSha256)).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsShowFingerprintAndRequestForget() {
        var requested = false
        compose.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = failure.serverAddress), {}, {}, {},
                    pinnedSpkiSha256 = failure.spkiSha256, onForgetServerKey = { requested = true })
            }
        }
        compose.onNodeWithText(formatFingerprint(failure.spkiSha256)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.tls_forget)).performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

}
