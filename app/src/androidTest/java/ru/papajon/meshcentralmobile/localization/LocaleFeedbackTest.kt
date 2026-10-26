package ru.papajon.meshcentralmobile.localization

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.components.UpdateSettings
import ru.papajon.meshcentralmobile.ui.screens.LockScreen
import ru.papajon.meshcentralmobile.ui.screens.SettingsScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.update.UpdateCandidate
import ru.papajon.meshcentralmobile.update.UpdateUiState

class LocaleFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsErrorAlreadyShownChangesLanguageWithoutRetryingTheAction() {
        val language = mutableStateOf("en")
        var openAttempts = 0
        compose.setContent {
            val localized = contextFor(language.value)
            // Exercise the real error callback without opening a browser on the test device.
            val unavailableBrowser = object : ContextWrapper(localized) {
                override fun startActivity(intent: Intent) {
                    openAttempts += 1
                    throw ActivityNotFoundException("Synthetic unavailable browser")
                }
            }
            CompositionLocalProvider(
                LocalContext provides unavailableBrowser,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                MeshCentralMobileTheme {
                    SettingsScreen(
                        settings = AppSettings(),
                        serverUrl = "https://mesh.example.com",
                        hasPin = false,
                        onScreenshotBlockingChanged = {},
                        onBiometricUnlockChanged = {},
                        onLockTimeoutChanged = {},
                        onPinSaved = {},
                        onPinCleared = {},
                        onLockNow = {},
                        onClearSession = {},
                        updates = {
                            // Settings receives its production update section from the app.
                            // Supply a synthetic candidate; no release lookup or browser is used.
                            UpdateSettings(
                                state = UpdateUiState(candidate = UpdateCandidate(
                                    packageName = "io.github.papajon123.meshcentral",
                                    versionCode = 19, versionName = "0.7.4",
                                    sha256 = "0".repeat(64), signerSha256 = "1".repeat(64),
                                )),
                                onEnabled = {}, onCheck = {}, onLater = {}, onSkip = {},
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithText(contextFor("en").getString(R.string.update_download))
            .performScrollTo().performClick()
        assertFeedbackChangesLanguage(R.string.update_browser_error) {
            language.value = "ru"
        }
        compose.runOnIdle { assertEquals(1, openAttempts) }
    }

    @Test fun rejectedPinAlreadyShownChangesLanguageWithoutSubmittingAgain() {
        val language = mutableStateOf("en")
        val submittedPins = mutableListOf<String>()
        compose.setContent {
            val localized = contextFor(language.value)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                MeshCentralMobileTheme {
                    LockScreen(
                        biometricEnabled = false,
                        onPinSubmit = { pin -> submittedPins += pin; false },
                        onBiometricUnlockRequested = {},
                    )
                }
            }
        }

        compose.onNodeWithText("1").performScrollTo().performClick()
        compose.onNodeWithText(contextFor("en").getString(R.string.lock_action_unlock))
            .performScrollTo().performClick()
        assertFeedbackChangesLanguage(R.string.lock_invalid_pin) {
            language.value = "ru"
        }
        compose.runOnIdle { assertEquals(listOf("1"), submittedPins) }
    }

    private fun assertFeedbackChangesLanguage(resourceId: Int, changeLanguage: () -> Unit) {
        val english = contextFor("en").getString(resourceId)
        val russian = contextFor("ru").getString(resourceId)
        assertNotEquals("The test needs distinct translations", english, russian)
        compose.onNodeWithText(english).performScrollTo().assertIsDisplayed()
        // Change only composition locals: the screen and its remembered feedback stay alive.
        compose.runOnIdle(changeLanguage)
        compose.onNodeWithText(russian).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(english).assertCountEquals(0)
    }

    private fun contextFor(tag: String): Context {
        val locales = LocaleList.forLanguageTags(tag)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return app.createConfigurationContext(configuration)
    }
}
