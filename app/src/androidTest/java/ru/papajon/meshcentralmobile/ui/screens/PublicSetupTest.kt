package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * First-launch contract of the public build: no baked-in server, an empty address never ends
 * onboarding, and a valid example address can be saved without any network access.
 */
class PublicSetupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var savedUrl: String? = null

    @Test fun emptyAddressIsNotSavedAndPersonalServerIsNotPrefilled() {
        showOnboarding()

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )

        compose.onNodeWithText("Сохранить адрес").performScrollTo().performClick()
        compose.waitForIdle()

        assertNull(savedUrl)
    }

    @Test fun validExampleAddressCanBeSavedWithoutNetwork() {
        showOnboarding()

        compose.onNode(hasSetTextAction())
            .performTextInput("https://example.com/meshcentral/")
        compose.onNodeWithText("Сохранить адрес").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals("https://example.com/meshcentral/", savedUrl)
        }
    }

    @Test fun savedExistingAddressIsPrefilledAndPreserved() {
        val existing = "https://existing.example/meshcentral/"
        showOnboarding(AppSettings(serverUrl = existing))

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(existing)),
        )
        compose.onNodeWithText("Сохранить адрес").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(existing, savedUrl)
        }
    }

    private fun showOnboarding(settings: AppSettings = AppSettings()) {
        compose.setContent {
            MeshCentralMobileTheme {
                OnboardingScreen(
                    settings = settings,
                    onServerUrlSaved = { savedUrl = it },
                    onAllowHttpInDebugChanged = {},
                    onTrustSelfSignedChanged = {},
                )
            }
        }
    }
}
