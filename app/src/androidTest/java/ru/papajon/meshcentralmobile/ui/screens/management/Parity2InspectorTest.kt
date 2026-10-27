package ru.papajon.meshcentralmobile.ui.screens.management

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.*
import ru.papajon.meshcentralmobile.model.FavoriteCommand
import ru.papajon.meshcentralmobile.ui.screens.TerminalFavorites
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class Parity2InspectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun removalRequiresTwoConfirmationsAndExactName() {
        val sent = mutableListOf<Pair<DeviceRemovalAction, String>>()
        show { RemovalActions("Example PC", DeviceRemovalAction.entries.toSet(), false) { a, n -> sent.add(a to n) } }
        compose.onNodeWithTag("parity2_removedevices").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("parity2_remove_confirm").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("parity2_remove_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("parity2_confirm_name").performTextInput("Wrong")
        compose.onNodeWithTag("parity2_remove_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("parity2_confirm_name").performTextReplacement("Example PC")
        hideKeyboard()
        compose.onNodeWithTag("parity2_remove_confirm").performClick()
        assertEquals(listOf(DeviceRemovalAction.RemoveDevice to "Example PC"), sent)
    }

    @Test fun revokedRemovalPermissionDisablesTheOpenConfirmation() {
        val actions = mutableStateOf(DeviceRemovalAction.entries.toSet())
        var calls = 0
        show { RemovalActions("Example PC", actions.value, false) { _, _ -> calls++ } }
        compose.onNodeWithTag("parity2_uninstallagent").performClick()
        compose.onNodeWithTag("parity2_remove_confirm").performClick()
        compose.onNodeWithTag("parity2_confirm_name").performClick().performTextInput("Example PC")
        compose.runOnIdle { actions.value = emptySet() }
        compose.onNodeWithTag("parity2_remove_confirm").assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun cancellingRemovalSendsNothing() {
        var calls = 0
        show { RemovalActions("Example PC", DeviceRemovalAction.entries.toSet(), false) { _, _ -> calls++ } }
        compose.onNodeWithTag("parity2_uninstallagent").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        assertEquals(0, calls)
    }

    @Test fun favoriteSelectionOnlyEditsDraftAndExplicitInsertionHasNoEnter() {
        val sent = mutableListOf<String>()
        show { TerminalFavorites(listOf(FavoriteCommand("Example", "echo example")), true, { true }, {}, { sent.add(it); true }, {}) }
        compose.onNodeWithTag("parity2_favorite_0").performClick()
        compose.onNodeWithTag("parity2_favorite_draft").assertTextContains("echo example")
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("parity2_favorite_insert").performClick()
        assertEquals(listOf("echo example"), sent)
    }

    @Test fun quickCommandRequiresReviewThenConfirmationAndShowsOutputAsText() {
        val sent = mutableListOf<String>()
        show { RunCommandContent("node//example", true, false, null, false, { command, utf8 -> assertFalse(utf8); sent.add(command) }, {}, Modifier.fillMaxSize()) }
        compose.onNodeWithTag("parity2_command").performTextInput("echo example")
        hideKeyboard()
        compose.onNodeWithTag("parity2_run_screen").performScrollToNode(hasTestTag("parity2_run_review"))
        compose.onNodeWithTag("parity2_run_review").assertIsDisplayed().performClick()
        assertTrue(sent.isEmpty())
        compose.onNode(isDialog()).assertExists()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        capture("run-confirm")
        compose.onNodeWithTag("parity2_run_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("echo example"), sent) }
    }

    @Test fun windowsUtf8ModeIsOptInAndIncludedInConfirmation() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        show { RunCommandContent("node//windows", true, false, null, false,
            { command, utf8 -> sent.add(command to utf8) }, {}, Modifier.fillMaxSize(), windows = true) }
        compose.onNodeWithTag("windows_polish_powershell_utf8").performScrollTo().assertIsOff().performClick()
        capture("windows-utf8-mode")
        compose.onNodeWithTag("parity2_command").performScrollTo().performTextInput("Write-Output 'Привет'")
        hideKeyboard()
        compose.onNodeWithTag("parity2_run_screen").performScrollToNode(hasTestTag("parity2_run_review"))
        compose.onNodeWithTag("parity2_run_review").assertIsDisplayed().performClick()
        assertTrue(sent.isEmpty())
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.windows_polish_powershell_utf8)) and
            hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("windows-utf8-confirm")
        compose.onNodeWithTag("parity2_run_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("Write-Output 'Привет'" to true), sent) }
    }

    @Test fun commandReviewIsReachableWithKeyboardInTheAppWindow() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        show { RunCommandContent("node//windows", true, false, null, false,
            { command, utf8 -> sent.add(command to utf8) }, {}, Modifier.fillMaxSize(), windows = true) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithTag("parity2_command").performScrollTo().performClick().performTextInput("echo example")
        // The editor's first IME request can race window focus/served-view registration after
        // another test closes its keyboard. Request it after Compose and the focused window idle.
        compose.runOnIdle {
            compose.activity.currentFocus?.let { editor ->
                editor.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        var lastImeRequest = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(timeoutMillis = 10_000) {
            val visible = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val now = android.os.SystemClock.uptimeMillis()
            if (!visible && compose.activity.hasWindowFocus() && now - lastImeRequest >= 500) {
                compose.runOnUiThread {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                android.os.SystemClock.sleep(250)
                compose.runOnUiThread {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                lastImeRequest = now
            }
            visible
        }
        // Android delivers IME insets outside Compose's test clock; let the viewport settle first.
        var previousBounds: androidx.compose.ui.geometry.Rect? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(timeoutMillis = 10_000) {
            val bounds = compose.onNodeWithTag("parity2_run_screen").fetchSemanticsNode().boundsInWindow
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previousBounds) stableSince = now
            previousBounds = bounds
            now - stableSince >= 300
        }
        val review = compose.onNodeWithTag("parity2_run_review")
        compose.onNodeWithTag("parity2_run_screen").performScrollToNode(hasTestTag("parity2_run_review"))
        capture("run-keyboard-app-window")
        review.assertIsDisplayed()
        val keyboard = requireNotNull(automation.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }) { "This check requires a visible software keyboard" }
        val keyboardBounds = android.graphics.Rect().also(keyboard::getBoundsInScreen)
        val windowOrigin = IntArray(2)
        compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(windowOrigin) }
        val actionBounds = review.fetchSemanticsNode().boundsInWindow
        assertTrue("Review action $actionBounds overlaps keyboard $keyboardBounds",
            actionBounds.height >= 40 * compose.activity.resources.displayMetrics.density - 1 &&
                actionBounds.bottom + windowOrigin[1] <= keyboardBounds.top)
        // Deliberately keep the keyboard open: the production layout must make this click reachable.
        review.performClick()
        assertTrue(sent.isEmpty())
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("echo example") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("parity2_run_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("echo example" to false), sent) }
    }

    @Test fun softwareDoesNotAutoFetchAndDisabledActionCannotRead() {
        var calls = 0
        show { SoftwareContent(listOf(InstalledProgram("Example editor", null, "Example")), false, false, false, { calls++ }, null, Modifier.fillMaxSize()) }
        compose.onNodeWithText("Example editor").assertIsDisplayed()
        compose.onNodeWithTag("parity2_software_load").assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun visualScreensHaveReachableActions() {
        val screen = mutableStateOf("hardware")
        show {
            when (screen.value) {
                "hardware" -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("parity2_visual_scroll")) {
                    item { HardwareDetailsContent(HardwareDetails(biosVendor = "Example", boardName = "Example board", tpmSpec = "2.0", tpmEnabled = true), true) }
                    item { NativeMetricsContent(MetricsSample(23.0, 42.0, 1793000000000), false, true, {}) }
                }
                "history" -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("parity2_visual_scroll")) {
                    item { PowerHistoryChart(listOf(PowerTransition(1792600000000, 0, 1), PowerTransition(1792900000000, 1, 6)), 1793000000000) }
                }
                "software" -> SoftwareContent(listOf(InstalledProgram("Example editor", "1.2", "Example")), false, false, true, {}, null, Modifier.fillMaxSize())
                "run" -> RunCommandContent("node//example", true, false, "example output\nNo exit code reported", true, { _, _ -> }, {}, Modifier.fillMaxSize())
                "remove" -> RemovalActions("Example PC", DeviceRemovalAction.entries.toSet(), false) { _, _ -> }
                "favorites" -> TerminalFavorites(listOf(FavoriteCommand("Example", "echo example")), true, { true }, {}, { true }, {})
            }
        }
        capture("hardware")
        compose.onNodeWithTag("parity2_visual_scroll").performScrollToNode(hasTestTag("parity2_metrics"))
        compose.onNodeWithTag("parity2_metrics_load").performScrollTo().assertIsDisplayed()
        capture("metrics")
        compose.runOnIdle { screen.value = "history" }; capture("history")
        compose.runOnIdle { screen.value = "software" }
        compose.onNodeWithTag("parity2_software_load").assertIsDisplayed(); capture("software")
        compose.runOnIdle { screen.value = "run" }; capture("run")
        compose.onNodeWithTag("parity2_run_screen").performScrollToNode(hasTestTag("parity2_output"))
        compose.onNodeWithTag("parity2_output").assertIsDisplayed(); capture("output")
        compose.runOnIdle { screen.value = "remove" }
        compose.onNodeWithTag("parity2_removedevices").performClick()
        compose.onNodeWithTag("parity2_remove_confirm").performClick()
        compose.onNodeWithTag("parity2_confirm_name").performClick().performTextInput("Example PC")
        capture("remove")
        compose.onNodeWithTag("parity2_remove_confirm").assertIsDisplayed()
        compose.onNodeWithTag("parity2_confirm_name").performScrollTo().assertIsDisplayed().assertIsFocused().assertTextContains("Example PC")
        compose.runOnIdle { screen.value = "favorites" }
        compose.onNodeWithTag("parity2_favorite_0").performClick()
        compose.onNodeWithTag("parity2_favorite_draft").performScrollTo().assertIsDisplayed()
        capture("favorites")
        compose.onNodeWithTag("parity2_favorite_insert").assertIsDisplayed()
    }

    private fun show(content: @Composable () -> Unit) {
        // Match MainActivity and AndroidManifest.xml so imePadding receives the real IME insets.
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val args = InstrumentationRegistry.getArguments()
        val locale = args.getString("parity2Locale") ?: "en"
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        Locale.setDefault(Locale.forLanguageTag(locale))
        // Dialog windows obtain their resources from the Activity view context, not only LocalContext.
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(config, compose.activity.resources.displayMetrics)
        val context = compose.activity.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalLayoutDirection provides if (locale in listOf("ar", "fa", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MeshCentralMobileTheme(darkTheme = args.getString("parity2Dark") == "true") {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
                }
            }
        }
    }
    private fun hideKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        inst.waitForIdleSync()
        // The compositor/IME runs outside Compose's test clock. Allow it to present the dialog surface.
        Thread.sleep(500)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        File(inst.targetContext.cacheDir, "parity2-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
