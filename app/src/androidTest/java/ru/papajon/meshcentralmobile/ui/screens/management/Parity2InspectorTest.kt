package ru.papajon.meshcentralmobile.ui.screens.management

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
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
        compose.onNodeWithTag("parity2_run_review").performScrollTo().performClick()
        assertTrue(sent.isEmpty())
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
        compose.onNodeWithTag("parity2_run_review").performScrollTo().performClick()
        assertTrue(sent.isEmpty())
        compose.onAllNodesWithText(compose.activity.getString(R.string.windows_polish_powershell_utf8)).onLast().assertIsDisplayed()
        capture("windows-utf8-confirm")
        compose.onNodeWithTag("parity2_run_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("Write-Output 'Привет'" to true), sent) }
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
