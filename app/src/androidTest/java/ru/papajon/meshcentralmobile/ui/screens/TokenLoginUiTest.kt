package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshAuthDiagnostics
import ru.papajon.meshcentralmobile.mesh.MeshAuthFailure
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class TokenLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun helpExplainsThePairAndFormSubmitsTheTokenUsernameUnchanged() {
        var submitted: Triple<String, String, String?>? = null
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(MeshClientState(), "https://mesh.example/",
                    onLogin = { user, password, code -> submitted = Triple(user, password, code) })
            }
        }
        compose.onNodeWithTag("login-token-help").performScrollTo().performClick()
        compose.onNodeWithTag("login-token-instructions").performScrollTo()
            .assertTextEquals(compose.activity.getString(R.string.token_login_help_body))
        compose.onNodeWithText(compose.activity.getString(R.string.login_username)).performScrollTo().performTextInput("~t:SyntheticMixedCASE")
        compose.onNodeWithText(compose.activity.getString(R.string.login_password)).performScrollTo().performTextInput("synthetic-token-password")
        compose.onNode(hasText(compose.activity.getString(R.string.login_action_sign_in)) and hasClickAction())
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Triple("~t:SyntheticMixedCASE", "synthetic-token-password", null), submitted)
        }
    }

    @Test fun missingKeyOffersServerSettingsInsteadOfTheCodeStep() {
        var opened = false
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(MeshClientState(connectionState = ConnectionState.Failed,
                    lastError = MeshAuthDiagnostics.describe(MeshAuthFailure.AccessKeyRequired)),
                    "https://mesh.example/", onEditServerSettings = { opened = true })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.login_two_factor_code)).assertDoesNotExist()
        compose.onNodeWithTag("login-server-settings").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun accessKeyIsMaskedAndSavedWithItsServer() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://mesh.example/team/"), {}, {}, {},
                    onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        compose.onNodeWithTag("server-login-key").performScrollTo().performTextInput("synthetic +&= key")
        compose.onNodeWithTag("server-login-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://mesh.example/team/" to "synthetic +&= key", saved) }
    }

    @Test fun changingEndpointDoesNotCarryThePreviousServersKey() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://mesh.example/"), {}, {}, {},
                    initialLoginKey = "synthetic-old-key", onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.server_url_label)).performScrollTo()
            .performTextReplacement("https://other.example/")
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://other.example/" to "", saved) }
    }

    @Test fun unsavedAccessKeyIsNotPutInSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        var saved: String? = null
        restoration.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://mesh.example/"), {}, {}, {},
                    onLoginKeySaved = { _, key -> saved = key })
            }
        }
        compose.onNodeWithTag("server-login-key").performScrollTo().performTextInput("synthetic-unsaved-key")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", saved) }
    }

    @Test fun restoringAnEditedAddressDoesNotRestoreTheOriginalServersKey() {
        val restoration = StateRestorationTester(compose)
        var saved: Pair<String, String>? = null
        restoration.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://mesh.example/"), {}, {}, {},
                    initialLoginKey = "synthetic-original-key", onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.server_url_label)).performScrollTo()
            .performTextReplacement("https://other.example/")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://other.example/" to "", saved) }
    }

    @Test fun pastedKeyIsSeparatedAndSavedDuringOnboarding() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MeshCentralMobileTheme {
                OnboardingScreen(AppSettings(), {}, {}, {},
                    onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.server_url_label)).performScrollTo()
            .performTextReplacement("https://mesh.example/team/?key=SyntheticKey123")
        compose.onNodeWithText(compose.activity.getString(R.string.server_url_label))
            .assertTextContains("https://mesh.example/team/")
        compose.onNodeWithTag("server-login-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://mesh.example/team/" to "SyntheticKey123", saved) }
    }

    @Test fun pastedKeyNeverSurvivesInSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        var saved: Pair<String, String>? = null
        restoration.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://previous.example/a/very/long/path/for/the/previous/server/"), {}, {}, {},
                    onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.server_url_label)).performScrollTo()
            .performTextReplacement("https://mesh.example/team/?key=SyntheticKey123")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://mesh.example/team/" to "", saved) }
    }

    @Test fun returningToTheOriginalAddressKeepsItsKey() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MeshCentralMobileTheme {
                ServerSettingsScreen(AppSettings(serverUrl = "https://mesh.example/"), {}, {}, {},
                    initialLoginKey = "synthetic-original-key", onLoginKeySaved = { url, key -> saved = url to key })
            }
        }
        val address = compose.onNodeWithText(compose.activity.getString(R.string.server_url_label))
        address.performScrollTo().performTextReplacement("https://other.example/")
        address.performScrollTo().performTextReplacement("https://mesh.example/")
        compose.onNodeWithText(compose.activity.getString(R.string.server_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://mesh.example/" to "synthetic-original-key", saved) }
    }
}
