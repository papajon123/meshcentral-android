package ru.papajon.meshcentralmobile.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.settings.ServerUrlValidator
import ru.papajon.meshcentralmobile.ui.screens.OnboardingScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class LocalizedScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allLanguagesRenderAReachableUnclippedSetupAction() {
        val tag = mutableStateOf("en")
        compose.setContent {
            val localized = contextFor(tag.value)
            val configuration = localized.resources.configuration
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCentralMobileTheme {
                    OnboardingScreen(AppSettings(), {}, {}, {})
                }
            }
        }
        val overflows = mutableListOf<String>()
        for (language in listOf("en", "ru", "zh-Hans", "zh-Hant", "es", "fr", "pt", "de", "ar", "hi", "bn",
            "ur", "ja", "ko", "id", "tr", "vi", "th", "it", "fa", "ta", "te", "mr")) {
            compose.runOnIdle { tag.value = language }
            val label = contextFor(language).getString(R.string.server_save)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // Compose may retain the paragraph's maximum constraint after measuring wrap-content.
            // Compare the actual glyph lines with the measured node, not that unused constraint.
            val clipped = layouts.filter { result ->
                (0 until result.lineCount).any { line ->
                    result.isLineEllipsized(line) ||
                        result.getLineRight(line) - result.getLineLeft(line) > result.size.width + 1f ||
                        result.getLineBottom(line) > result.size.height + 1f
                }
            }
            clipped.forEach {
                overflows += "$language: size=${it.size}, lines=${it.lineCount}"
            }
            if (language in setOf("en", "ru", "zh-Hans", "zh-Hant", "ar", "de", "hi", "ta") || clipped.isNotEmpty()) screenshot(language)
        }
        assertTrue("Setup action clipped: $overflows", overflows.isEmpty())
    }

    @Test fun validationAlreadyOnScreenChangesWithTheLocale() {
        val tag = mutableStateOf("en")
        compose.setContent {
            val localized = contextFor(tag.value)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                MeshCentralMobileTheme { OnboardingScreen(AppSettings(), {}, {}, {}) }
            }
        }
        compose.onNodeWithText(contextFor("en").getString(R.string.server_save)).performScrollTo().performClick()
        val error = ServerUrlValidator.validate("", false)!!
        compose.onAllNodesWithText(error.asString(contextFor("en")))[0].assertExists()
        compose.runOnIdle { tag.value = "ru" }
        compose.onAllNodesWithText(error.asString(contextFor("ru")))[0].assertExists()
        compose.onAllNodesWithText(error.asString(contextFor("en"))).assertCountEquals(0)
    }

    private fun contextFor(tag: String): Context {
        val locales = LocaleList.forLanguageTags(tag)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return app.createConfigurationContext(configuration)
    }

    private fun screenshot(language: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(200)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.cacheDir, "locale-$language.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
