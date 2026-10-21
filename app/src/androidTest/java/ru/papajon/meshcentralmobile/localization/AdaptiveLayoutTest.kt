package ru.papajon.meshcentralmobile.localization

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.TwoFactorChallenge
import ru.papajon.meshcentralmobile.mesh.TwoFactorChannel
import ru.papajon.meshcentralmobile.model.AppDestination
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.components.MeshNavigationBar
import ru.papajon.meshcentralmobile.ui.screens.LoginContent
import ru.papajon.meshcentralmobile.ui.screens.OnboardingScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Regression checks for narrow viewports and large font scales.
 *
 * The viewport is simulated with a fixed-size, clipped Box and a synthetic density/font scale so
 * these cases run on any regular emulator. All callbacks and server state are synthetic: no network
 * is attempted and no production code is changed by these tests.
 */
class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onboardingSetupAt320x480WithFont2StaysReachableInRuDeAr() {
        val state = AdaptiveState(language = "ru", widthDp = 320, heightDp = 480, fontScale = 2f)
        val savedUrls = mutableListOf<String>()
        try {
            show(state) {
                OnboardingScreen(
                    settings = AppSettings(serverUrl = DEMO_SERVER_URL),
                    onServerUrlSaved = { savedUrls += it },
                    onAllowHttpInDebugChanged = {},
                    onTrustSelfSignedChanged = {},
                )
            }
            for (language in listOf("ru", "de", "ar")) {
                compose.runOnIdle {
                    state.language = language
                    savedUrls.clear()
                }
                val context = localizedContext(language, 320, 480, 2f)
                val label = context.getString(R.string.server_save)
                val save = compose.onNodeWithText(label).performScrollTo()

                save.assertIsDisplayed().assertHasClickAction()
                assertGlyphsFit(label, "onboarding save $language 320x480 font2")
                assertFullyInsideViewport(save, "onboarding save $language 320x480 font2")
                save.performClick()
                compose.runOnIdle {
                    assertEquals(
                        "server save callback must run for $language",
                        listOf(DEMO_SERVER_URL),
                        savedUrls,
                    )
                }
            }
        } finally {
            snapshot("onboarding-320x480-font2")
        }
    }

    @Test
    fun loginTwoFactorChannelsAt320x640WithFont1_3StayVisibleAndSendTheRightRequests() {
        val state = AdaptiveState(language = "ru", widthDp = 320, heightDp = 640, fontScale = 1.3f)
        val requested = mutableListOf<TwoFactorChannel>()
        val challenge = TwoFactorChallenge(email = true, sms = true, push = true)
        try {
            show(state) {
                LoginContent(
                    clientState = MeshClientState(twoFactorChallenge = challenge),
                    serverUrl = DEMO_SERVER_URL,
                    onRequestCode = { requested += it },
                )
            }
            for (language in listOf("ru", "de", "ar")) {
                compose.runOnIdle {
                    state.language = language
                    requested.clear()
                }
                val context = localizedContext(language, 320, 640, 1.3f)
                val actions = listOf(
                    context.getString(R.string.login_two_factor_email) to TwoFactorChannel.Email,
                    context.getString(R.string.login_two_factor_sms) to TwoFactorChannel.Sms,
                    context.getString(R.string.login_two_factor_push) to TwoFactorChannel.Push,
                )
                for ((label, channel) in actions) {
                    val button = compose.onNodeWithText(label).performScrollTo()
                    button.assertIsDisplayed().assertHasClickAction()
                    assertGlyphsFit(label, "login $language channel $channel")
                    assertFullyInsideViewport(button, "login $language channel $channel")
                    button.performClick()
                    compose.runOnIdle {
                        assertEquals(
                            "channel callback for $language $channel",
                            channel,
                            requested.lastOrNull(),
                        )
                    }
                }
                compose.runOnIdle {
                    assertEquals(
                        "all channel callbacks for $language",
                        listOf(TwoFactorChannel.Email, TwoFactorChannel.Sms, TwoFactorChannel.Push),
                        requested,
                    )
                }
            }
        } finally {
            snapshot("login-twofactor-320x640-font1_3")
        }
    }

    @Test
    fun navigationLabelsAt320dpWithFont2StayVisibleInDeTaAr() {
        val state = AdaptiveState(language = "de", widthDp = 320, heightDp = 480, fontScale = 2f)
        val current = mutableStateOf(AppDestination.Devices)
        try {
            show(state) {
                Box(Modifier.fillMaxSize()) {
                    MeshNavigationBar(
                        current = current.value,
                        onSelect = { current.value = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            for (language in listOf("de", "ta", "ar")) {
                compose.runOnIdle {
                    state.language = language
                    current.value = AppDestination.Devices
                }
                val context = localizedContext(language, 320, 480, 2f)
                for (destination in AppDestination.entries) {
                    val label = navLabel(context, destination)
                    val tab = compose.onNodeWithContentDescription(label)
                    tab.performClick()
                    compose.waitForIdle()
                    compose.runOnIdle {
                        assertEquals(
                            "tab selection for $language $destination",
                            destination,
                            current.value,
                        )
                    }
                    tab.assertIsSelected()
                    for (other in AppDestination.entries.filter { it != destination }) {
                        compose.onNodeWithContentDescription(navLabel(context, other))
                            .assertIsNotSelected()
                    }

                    val visibleLabel = compose.onNodeWithText(label, useUnmergedTree = true)
                    visibleLabel.assertIsDisplayed()
                    assertGlyphsFit(label, "navigation $language $destination")
                    assertFullyInsideViewport(visibleLabel, "navigation $language $destination")
                }
            }
        } finally {
            snapshot("navigation-320dp-font2")
        }
    }

    @Test
    fun onboardingSurvivesAResizeFrom320x480To640x320WithARealSave() {
        val state = AdaptiveState(language = "ru", widthDp = 320, heightDp = 480, fontScale = 1.3f)
        val savedUrls = mutableListOf<String>()
        try {
            show(state) {
                OnboardingScreen(
                    settings = AppSettings(serverUrl = DEMO_SERVER_URL),
                    onServerUrlSaved = { savedUrls += it },
                    onAllowHttpInDebugChanged = {},
                    onTrustSelfSignedChanged = {},
                )
            }
            val portraitViewport = compose.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()

            // Only mutable state changes: setContent is called once for the whole test.
            compose.runOnIdle {
                state.language = "de"
                state.widthDp = 640
                state.heightDp = 320
            }
            compose.waitForIdle()

            val landscapeViewport = compose.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
            val portraitWidth = portraitViewport.right - portraitViewport.left
            val portraitHeight = portraitViewport.bottom - portraitViewport.top
            val landscapeWidth = landscapeViewport.right - landscapeViewport.left
            val landscapeHeight = landscapeViewport.bottom - landscapeViewport.top
            assertTrue(
                "viewport must become wider after resizing to 640x320: $portraitViewport -> $landscapeViewport",
                landscapeWidth > portraitWidth,
            )
            assertTrue(
                "viewport must become shorter after resizing to 640x320: $portraitViewport -> $landscapeViewport",
                landscapeHeight < portraitHeight,
            )

            val context = localizedContext("de", 640, 320, 1.3f)
            val label = context.getString(R.string.server_save)
            val save = compose.onNodeWithText(label).performScrollTo()
            save.assertIsDisplayed().assertHasClickAction()
            assertGlyphsFit(label, "onboarding landscape de 640x320 font1.3")
            assertFullyInsideViewport(save, "onboarding landscape de 640x320 font1.3")
            save.performClick()
            compose.runOnIdle {
                assertEquals(
                    "landscape server save callback",
                    listOf(DEMO_SERVER_URL),
                    savedUrls,
                )
            }
        } finally {
            snapshot("onboarding-640x320-font1_3")
        }
    }

    private fun show(state: AdaptiveState, content: @Composable () -> Unit) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AdaptiveViewport(state, Modifier.align(Alignment.TopStart), content)
            }
        }
        compose.waitForIdle()
    }

    /**
     * The activity window is much larger than the simulated device. Insets are consumed with
     * [consumeWindowInsets] so the screen's own `windowInsetsPadding` sees zero bars inside this
     * box; otherwise the real status/navigation bars would silently shrink the test viewport.
     * The IME inset is not consumed because no text field is focused in these tests.
     */
    @Composable
    private fun AdaptiveViewport(
        state: AdaptiveState,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        val context = remember(state.language, state.widthDp, state.heightDp, state.fontScale) {
            localizedContext(state.language, state.widthDp, state.heightDp, state.fontScale)
        }
        val configuration = context.resources.configuration
        val layoutDirection = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides layoutDirection,
            LocalDensity provides Density(1f, state.fontScale),
        ) {
            Box(
                modifier = modifier
                    .testTag(VIEWPORT_TAG)
                    .requiredSize(state.widthDp.dp, state.heightDp.dp)
                    .clipToBounds()
                    .consumeWindowInsets(WindowInsets.systemBars)
                    .consumeWindowInsets(WindowInsets.safeDrawing),
            ) {
                MeshCentralMobileTheme {
                    content()
                }
            }
        }
    }

    private fun localizedContext(
        language: String,
        widthDp: Int,
        heightDp: Int,
        scale: Float,
    ): Context {
        val locales = LocaleList.forLanguageTags(language)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
            this.fontScale = scale
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            smallestScreenWidthDp = minOf(widthDp, heightDp)
        }
        return app.createConfigurationContext(configuration)
    }

    private fun navLabel(context: Context, destination: AppDestination): String = when (destination) {
        AppDestination.Devices -> context.getString(R.string.common_nav_devices_short)
        AppDestination.Session -> context.getString(R.string.common_nav_session)
        AppDestination.Settings -> context.getString(R.string.common_nav_settings)
    }

    /**
     * Checks the real paragraph lines, not `hasVisualOverflow`: with `softWrap = false` the
     * paragraph may be measured at its intrinsic width and then constrained when placed, so the
     * layout result's size is the reliable bound for the glyphs that actually fit.
     */
    private fun assertGlyphsFit(label: String, case: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$case: no text layout result for '$label'", layouts.isNotEmpty())
        for (layout in layouts) {
            assertFalse(
                "$case: '$label' is ellipsized",
                (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) },
            )
            for (line in 0 until layout.lineCount) {
                val glyphWidth = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue(
                    "$case: '$label' line $line glyphs ($glyphWidth) exceed node width ${layout.size.width}",
                    glyphWidth <= layout.size.width + 1f,
                )
                assertTrue(
                    "$case: '$label' line $line bottom ${layout.getLineBottom(line)} exceeds node height ${layout.size.height}",
                    layout.getLineBottom(line) <= layout.size.height + 1f,
                )
            }
        }
    }

    private fun assertFullyInsideViewport(node: SemanticsNodeInteraction, case: String) {
        val viewport = compose.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val bounds = node.getUnclippedBoundsInRoot()
        val tolerance = 1.dp
        assertTrue(
            "$case: action left ${bounds.left} is outside viewport ${viewport.left}",
            bounds.left >= viewport.left - tolerance,
        )
        assertTrue(
            "$case: action top ${bounds.top} is outside viewport ${viewport.top}",
            bounds.top >= viewport.top - tolerance,
        )
        assertTrue(
            "$case: action right ${bounds.right} is outside viewport ${viewport.right}",
            bounds.right <= viewport.right + tolerance,
        )
        assertTrue(
            "$case: action bottom ${bounds.bottom} is outside viewport ${viewport.bottom}",
            bounds.bottom <= viewport.bottom + tolerance,
        )
    }

    /** Always called from `finally`: a red test must still leave a picture of the broken layout. */
    private fun snapshot(name: String) {
        try {
            try {
                compose.waitForIdle()
            } catch (_: Throwable) {
                // Keep going: the screenshot may still show the failed frame.
            }
            SystemClock.sleep(250)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(app.cacheDir, "adaptive-$name.png").outputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            image.recycle()
        } catch (_: Throwable) {
            // A missing screenshot must not hide the original assertion failure.
        }
    }
}

private const val DEMO_SERVER_URL = "https://demo.example/"
private const val VIEWPORT_TAG = "adaptive-viewport"

private class AdaptiveState(
    language: String,
    widthDp: Int,
    heightDp: Int,
    fontScale: Float,
) {
    var language by mutableStateOf(language)
    var widthDp by mutableStateOf(widthDp)
    var heightDp by mutableStateOf(heightDp)
    var fontScale by mutableStateOf(fontScale)
}
