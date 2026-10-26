package ru.papajon.meshcentralmobile.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.*
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.*
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.management.*
import ru.papajon.meshcentralmobile.ui.screens.session.KeyboardSheet
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Synthetic data only. No server or remote agent is contacted. */
class ParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = MeshDevice("node//example", "mesh//example", "Example PC", conn = 1, agentId = 4,
        osDescription = "Windows 11", rights = MeshRights.FULL,
        windowsSecurity = WindowsSecurity(WindowsSecurityCenter(SecurityStatus.Unknown, SecurityStatus.Ok, SecurityStatus.Problem),
            listOf(AntivirusProduct("Example AV", false, null)), setOf("wsc", "av")))
    private val args get() = InstrumentationRegistry.getArguments()
    private val locale get() = args.getString("parityLocale") ?: "en"
    private val localContext: Context get() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(this@ParityUiTest.locale))
    })
    private fun text(id: Int) = localContext.getString(id)

    private fun show(content: @Composable () -> Unit) {
        args.getString("parityOrientation")?.let { orientation ->
            val landscape = orientation == "landscape"
            compose.runOnUiThread {
                compose.activity.requestedOrientation = if (landscape)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.orientation == if (landscape)
                    Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
        }
        val context = localContext
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, args.getString("parityFontScale")?.toFloatOrNull() ?: 1f)) {
                MeshCentralMobileTheme(darkTheme = args.getString("parityDark") == "true") {
                    Surface(Modifier.safeDrawingPadding()) { content() }
                }
            }
        }
    }

    @Composable private fun Inspector(
        canSend: Boolean = true, onToast: (String, String) -> Unit = { _, _ -> },
        onPower: (DevicePowerAction) -> Unit = {}, onHistory: () -> Unit = {},
        target: MeshDevice = device,
    ) {
        DeviceInspectorScreen(snapshot = null, services = emptyList(), processes = emptyList(), loading = false,
            error = null, availablePowerActions = setOf(DevicePowerAction.Sleep), canManageServices = false,
            onRefresh = {}, onLoadServices = {}, onLoadProcesses = {}, onPower = onPower,
            onServiceAction = { _, _ -> }, onBack = {}, device = target, canSendToast = canSend,
            onSendToast = onToast, onLoadHistory = onHistory, events = emptyList(), timelineError = true)
    }

    @Test fun securityRemainsVisibleWithoutSystemInfo() {
        show { Inspector() }
        compose.onNodeWithTag("parity_security").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity_antivirus) + ": " + text(R.string.parity_unknown)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity_firewall) + ": " + text(R.string.parity_needs_attention)).assertIsDisplayed()
        capture("security")
    }

    @Test fun toastRequiresPreviewAndExplicitSend() {
        val sent = mutableListOf<Pair<String, String>>()
        show { Inspector(onToast = { title, msg -> sent += title to msg }) }
        compose.onNodeWithTag("parity_toast_open").performScrollTo().performClick()
        compose.onNodeWithTag("parity_toast_review").assertIsNotEnabled()
        compose.onNodeWithTag("parity_toast_title").performTextInput("Scheduled maintenance")
        compose.onNodeWithTag("parity_toast_body").performTextInput("Please save your work.")
        assertActionsAboveKeyboard("parity_toast_body", "parity_toast_review", "toast-editor")
        val previewBounds = compose.onNodeWithTag("parity_toast_review").fetchSemanticsNode().boundsInRoot
        val reviewedAt = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithTag("parity_toast_review").performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithText("Please save your work.").assertIsDisplayed()
        capture("toast-confirm")
        val sendBounds = compose.onNodeWithTag("parity_toast_confirm").fetchSemanticsNode().boundsInRoot
        assertNotEquals(previewBounds.center, sendBounds.center)
        compose.waitUntil(3000) { android.os.SystemClock.elapsedRealtime() - reviewedAt >= 800 &&
            compose.onAllNodes(hasTestTag("parity_toast_confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("parity_toast_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("Scheduled maintenance" to "Please save your work."), sent) }
    }

    @Test fun toastPermissionRevocationDisablesConfirmation() {
        var allowed by mutableStateOf(true)
        var sent = false
        show { Inspector(canSend = allowed, onToast = { _, _ -> sent = true }) }
        compose.onNodeWithTag("parity_toast_open").performScrollTo().performClick()
        compose.onNodeWithTag("parity_toast_title").performTextInput("Title")
        compose.onNodeWithTag("parity_toast_body").performTextInput("Message")
        compose.onNodeWithTag("parity_toast_review").performClick()
        compose.runOnIdle { allowed = false }
        compose.onNodeWithTag("parity_toast_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(sent) }
    }

    @Test fun doubleTapReviewDoesNotSendTheMessage() {
        var sent = 0
        show { ToastComposer("Example PC", true, { _, _ -> sent++ }, {}) }
        compose.onNodeWithTag("parity_toast_title").performTextInput("Title")
        compose.onNodeWithTag("parity_toast_body").performTextInput("Message")
        compose.onNodeWithTag("parity_toast_review").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(0, sent) }
    }

    @Test fun linuxCpuShowsItsSamplingInterval() {
        show { NativeMetricsContent(MetricsSample(25.0, 50.0, 1793000000000), false, true, {}, linux = true) }
        compose.onNodeWithText(text(R.string.parity_fixes_linux_cpu_average)).assertIsDisplayed()
        capture("linux-metrics")
    }

    @Test fun linuxAntivirusDoesNotCreateAWindowsSecurityCard() {
        show { Inspector(target = device.copy(agentId = 6, osDescription = "Linux")) }
        compose.onNodeWithTag("parity_security").assertDoesNotExist()
    }

    @Test fun commandScreenShowsServerRefusalAndSharedOutputWarning() {
        show { RunCommandContent("node//example", true, false, null, false, { _, _ -> }, {}, Modifier,
            status = { androidx.compose.material3.Text("Access denied") }) }
        compose.onNodeWithText("Access denied").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity2_run_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity_fixes_run_warning)).assertIsDisplayed()
    }

    @Test fun historySeparatesEmptyEventsFromFailedPowerHistory() {
        var requests = 0
        show { Inspector(onHistory = { requests++ }) }
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.History)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.parity_history_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity_history_error)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
        capture("history")
    }

    @Test fun sleepCancellationAndConfirmationAreExplicit() {
        val actions = mutableListOf<DevicePowerAction>()
        show { Inspector(onPower = { actions += it }) }
        compose.onNodeWithTag(DeviceInspectorTestTags.PowerMenu).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.power(DevicePowerAction.Sleep)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.Cancel).performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag(DeviceInspectorTestTags.PowerMenu).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.power(DevicePowerAction.Sleep)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.Confirm).performClick()
        compose.runOnIdle { assertEquals(listOf(DevicePowerAction.Sleep), actions) }
    }

    @Test fun shortcutCreatedInSheetSurvivesReloadAndCanBeRemoved() {
        val name = "parity-shortcuts-ui"
        val context = compose.activity
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsRepository(context, name)
        val profileId = store.activeProfile.id
        show {
            val profiles by store.profilesState
            KeyboardSheet(null, {}, customShortcuts = profiles.first { it.id == profileId }.customShortcuts,
                onAddShortcut = { store.addShortcut(profileId, it) }, onRemoveShortcut = { store.removeShortcut(profileId, it) })
        }
        compose.onNodeWithTag("parity_shortcut_add").performScrollTo().performClick()
        compose.onNodeWithText("Win+R").performClick()
        compose.onNodeWithTag("parity_shortcut_label").performTextReplacement("Run dialog")
        assertActionsAboveKeyboard("parity_shortcut_label", "parity_shortcut_save", "shortcut-editor")
        compose.onNodeWithTag("parity_shortcut_save").performClick()
        compose.onNodeWithText("Run dialog · Win+R").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(CustomShortcut("Run dialog", "R", win = true)), SettingsRepository(context, name).activeProfile.customShortcuts)
        }
        capture("shortcuts")
        compose.onNodeWithText(localContext.getString(R.string.parity_remove_shortcut, "Run dialog")).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(SettingsRepository(context, name).activeProfile.customShortcuts.isEmpty()) }
    }

    @Test fun shortcutLimitAndServerIsolationPersistInPreferences() {
        val context = compose.activity
        val name = "parity-shortcuts-storage"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsRepository(context, name)
        val first = store.activeProfile.id
        val second = store.createProfile("Second", "https://second.example.com").id
        repeat(12) { assertTrue(store.addShortcut(first, CustomShortcut("Shortcut $it", "R", win = true))) }
        assertFalse(store.addShortcut(first, CustomShortcut("Thirteenth", "R")))
        assertFalse(store.addShortcut(first, CustomShortcut("Bad", "INVALID")))
        store.activateProfile(second)
        assertTrue(store.activeProfile.customShortcuts.isEmpty())
        // Saving by explicit id cannot put a stale dialog's shortcut into the newly active server.
        store.removeShortcut(first, 0)
        assertTrue(store.addShortcut(first, CustomShortcut("Replacement", "L", win = true)))
        val reloaded = SettingsRepository(context, name)
        assertEquals(second, reloaded.activeProfile.id)
        assertTrue(reloaded.activeProfile.customShortcuts.isEmpty())
        reloaded.activateProfile(first)
        assertEquals(12, reloaded.activeProfile.customShortcuts.size)
        assertEquals("Replacement", reloaded.activeProfile.customShortcuts.last().label)
    }

    @Test fun attentionFilterExcludesHealthyAndUnknownComputers() {
        val bad = device.copy(name = "Needs repair")
        val unknown = device.copy(nodeId = "node//unknown", name = "Unknown PC", windowsSecurity = WindowsSecurity())
        val healthy = device.copy(nodeId = "node//healthy", name = "Healthy PC", windowsSecurity = WindowsSecurity(WindowsSecurityCenter(SecurityStatus.Ok, SecurityStatus.Ok, SecurityStatus.Ok)))
        show {
            DevicesContent(MeshClientState(connectionState = ConnectionState.Authenticated, socketConnected = true, requiresSignIn = false,
                devicesCurrent = true, devices = listOf(bad, unknown, healthy)), emptyList(), { _, _ -> }, {}, {}, {}, {}, {})
        }
        compose.onNodeWithTag("device_filter_NeedsAttention").performScrollTo().performClick()
        compose.onNodeWithText("Needs repair").assertIsDisplayed()
        compose.onNodeWithText("Unknown PC").assertDoesNotExist()
        compose.onNodeWithText("Healthy PC").assertDoesNotExist()
        capture("filter")
    }

    private fun assertActionsAboveKeyboard(fieldTag: String, actionTag: String, scene: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // Semantics text input alone need not show the real IME. Focus/click the field and
        // require an actual keyboard window; a missing keyboard must never pass this check.
        compose.onNodeWithTag(fieldTag).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        // Insets and the IME animation run on Android's clock, not Compose's test clock.
        // Require both the keyboard and action geometry to settle before comparing them.
        var previousKeyboard: android.graphics.Rect? = null
        var previousAction: androidx.compose.ui.geometry.Rect? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(timeoutMillis = 10_000) {
            val keyboard = automation.windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
            val action = compose.onNodeWithTag(actionTag).fetchSemanticsNode().boundsInWindow
            val now = android.os.SystemClock.uptimeMillis()
            if (keyboard == null || keyboard != previousKeyboard || action != previousAction) stableSince = now
            previousKeyboard = keyboard
            previousAction = action
            keyboard != null && now - stableSince >= 300
        }
        compose.waitForIdle()
        // Preserve visual evidence even when the geometry assertion below fails.
        capture(scene)
        val ime = requireNotNull(automation.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }) { "Keyboard disappeared before checking dialog actions" }
        val keyboardBounds = android.graphics.Rect().also(ime::getBoundsInScreen)
        val form = isDialog() and hasAnyDescendant(hasTestTag(actionTag))
        val dialogBounds = compose.onNode(form).fetchSemanticsNode().boundsInWindow
        val dialogWindow = automation.windows.filter {
            // Text selection handles are TYPE_APPLICATION windows too. Locate the
            // window large enough to host the dialog root, excluding those popups.
            val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                bounds.width() >= dialogBounds.right && bounds.height() >= dialogBounds.bottom
        }.maxByOrNull { it.layer }
        requireNotNull(dialogWindow) { "No application window contains form root $dialogBounds" }
        val windowBounds = android.graphics.Rect().also(dialogWindow::getBoundsInScreen)
        val actions = listOf(
            compose.onNodeWithTag(actionTag),
            compose.onNode(hasText(text(R.string.manage_cancel)) and hasAnyAncestor(form)),
        )
        actions.forEach { action ->
            action.assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInWindow
            assertTrue("Action $bounds is clipped or collapsed in form $dialogBounds",
                bounds.width > 0 && bounds.height >= 40 * compose.activity.resources.displayMetrics.density - 1)
            assertTrue("Action $bounds in $windowBounds overlaps keyboard $keyboardBounds",
                bounds.bottom + windowBounds.top <= keyboardBounds.top)
            assertTrue("Action $bounds is outside dialog window $windowBounds",
                bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= windowBounds.width() && bounds.bottom <= windowBounds.height())
        }
    }

    private fun capture(scene: String) {
        compose.waitForIdle()
        // Dialog and IME transitions use Android's clock, outside Compose's test clock.
        android.os.SystemClock.sleep(400)
        // IME/window insets arrive on Android's clock. Drain the resulting Compose layout too.
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val name = "parity-$scene-$locale-${args.getString("parityDark") ?: "false"}.png"
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
