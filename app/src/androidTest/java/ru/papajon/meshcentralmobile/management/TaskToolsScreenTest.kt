package ru.papajon.meshcentralmobile.management

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.management.tasks.*
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.ui.screens.management.TaskToolsContent
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class TaskToolsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val process = AgentProcess(42, "fixture-sleep", "fixture", "/usr/bin/fixture-sleep")
    private val service = AgentService("fixture", "Fixture service", "RUNNING", "AUTO_START", 100)
    private var kills = 0
    private var changes = 0
    private var refreshes = 0
    private var selectedAction: TaskServiceAction? = null
    private var rights by mutableIntStateOf(8)
    private var current by mutableStateOf(true)
    private var features2 by mutableStateOf<Long?>(null)
    private var confirmationPaused = false
    private var screenState by mutableStateOf(TaskToolsState())
    private fun show(services: Boolean = false, state: TaskToolsState = TaskToolsState(processes = listOf(process), services = listOf(service), loaded = true), rtlLarge: Boolean = false) {
        screenState = state
        compose.setContent {
            val density = LocalDensity.current
            val context = LocalContext.current
            val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                if (rtlLarge) setLocale(java.util.Locale.forLanguageTag("ar"))
            }
            val translated = remember(context, rtlLarge) { context.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides translated, LocalConfiguration provides configuration, LocalLayoutDirection provides if (rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (rtlLarge) 1.8f else 1f)) {
                MeshCentralMobileTheme {
                    TaskToolsContent(screenState, MeshDevice("node//fixture", "mesh//fixture", "Fixture computer", conn = 1, agentId = 6, rights = rights), current, services,
                        {}, { refreshes++ }, { p, confirmed -> assertEquals(process.pid, p.pid); assertTrue(confirmed); kills++ },
                        { _, action, confirmed -> assertTrue(confirmed); selectedAction = action; changes++ }, {}, {},
                        onConfirmationChanged = { confirmationPaused = it }, features2 = features2)
                }
            }
        }
    }
    @Test fun processActionRequiresConfirmationAndShowsIdentity() {
        show()
        compose.onNodeWithTag("kill_42").performScrollTo().performClick()
        assertEquals(0, kills)
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.tasks_kill_confirm, "fixture-sleep", 42L)).assertExists()
        compose.onNodeWithTag("tasks_confirm").performClick()
        assertEquals(1, kills)
    }
    @Test fun viewOnlyAndMixedRightsHaveNoMutationButtons() {
        rights = 256; show()
        compose.onNodeWithTag("tasks_read_only").assertExists()
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
        compose.runOnIdle { rights = 264 }
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
    }
    @Test fun rightsRevokedWhileConfirmationIsOpenDisableConfirm() {
        show(); compose.onNodeWithTag("kill_42").performScrollTo().performClick()
        compose.runOnIdle { rights = 256 }
        compose.onNodeWithTag("tasks_confirm").assertIsNotEnabled()
        assertEquals(0, kills)
    }
    @Test fun offlineHidesActionsAndCanRetry() {
        current = false; show()
        compose.onNodeWithTag("tasks_offline").assertExists()
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
        compose.onNodeWithTag("tasks_refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
    }
    @Test fun serviceActionsUseConfirmation() {
        show(services = true)
        compose.onNodeWithTag("service_Restart_fixture").performScrollTo().performClick()
        assertEquals(0, changes)
        compose.onNodeWithTag("tasks_confirm").performClick()
        assertEquals(TaskServiceAction.Restart, selectedAction)
        assertEquals(1, changes)
    }
    @Test fun serviceReadOnlyHasNoButtons() {
        rights = 256; show(services = true)
        compose.onNodeWithTag("service_Restart_fixture").assertDoesNotExist()
        compose.onNodeWithTag("tasks_read_only").assertExists()
    }
    @Test fun criticalServiceWarningIsVisible() {
        show(services = true, state = TaskToolsState(services = listOf(service.copy(name = "Mesh Agent")), loaded = true))
        compose.onNodeWithTag("service_Stop_Mesh Agent").performScrollTo().performClick()
        compose.onNodeWithTag("tasks_critical").assertExists()
    }
    @Test fun searchHandlesFiveHundredProcessesAndFindsLast() {
        show(state = TaskToolsState(processes = (1..600).map { AgentProcess(it.toLong(), "fixture-$it", "fixture") }, loaded = true))
        compose.onNodeWithTag("tasks_search").performTextInput("fixture-600")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("process_600").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("process_600").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("process_1").assertDoesNotExist()
    }
    @Test fun processAndServiceErrorStatesKeepRetry() {
        show(services = true, state = TaskToolsState(error = ru.papajon.meshcentralmobile.R.string.tasks_unsupported))
        compose.onNodeWithTag("tasks_error").assertExists()
        compose.onNodeWithTag("tasks_read_only").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.tasks_auto_refresh)).assertDoesNotExist()
        compose.onNodeWithTag("tasks_refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
    }
    @Test fun largeFontRtlKeepsActionsReachable() {
        show(services = true, rtlLarge = true)
        compose.onNodeWithTag("service_Restart_fixture").performScrollTo().assertIsDisplayed()
        snapshot("tasks-services-rtl-large.png")
        compose.onNodeWithTag("service_Restart_fixture").performClick()
        compose.onNodeWithTag("tasks_confirm").assertIsDisplayed()
        val arabic = compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ar"))
        })
        compose.onNodeWithText(arabic.getString(ru.papajon.meshcentralmobile.R.string.tasks_confirm)).assertIsDisplayed()
        snapshot("tasks-confirm-rtl-large.png")
    }
    @Test fun processScreenScreenshot() {
        show()
        compose.onNodeWithTag("kill_42").performScrollTo().assertIsDisplayed()
        snapshot("tasks-processes.png")
    }
    @Test fun restrictedInputAndNoDesktopHideMutationsButKeepLists() {
        rights = 8 or 4096; show()
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
        compose.onNodeWithTag("tasks_read_only").assertExists()
        compose.runOnIdle { rights = 8 or 65536 }
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
        compose.runOnIdle { rights = -1; features2 = 0x2000 }
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
    }
    @Test fun confirmationPausesPollingAndStaysEnabledDuringExistingRead() {
        show(); compose.onNodeWithTag("kill_42").performScrollTo().performClick()
        assertTrue(confirmationPaused)
        compose.runOnIdle { screenState = screenState.copy(loading = true) }
        compose.onNodeWithTag("tasks_confirm").assertIsEnabled().performClick()
        assertEquals(1, kills); assertFalse(confirmationPaused)
    }
    @Test fun pidZeroIsHiddenEvenInUiState() {
        show(state = TaskToolsState(processes = listOf(AgentProcess(0, "idle"), process), loaded = true))
        compose.onNodeWithTag("process_0").assertDoesNotExist()
        compose.onNodeWithTag("kill_0").assertDoesNotExist()
    }
    @Test fun uncertainSnapshotHasNoMutationButtons() {
        show(state = TaskToolsState(processes = listOf(process), loaded = true, verificationReady = false,
            error = ru.papajon.meshcentralmobile.R.string.tasks_uncertain_reply))
        compose.onNodeWithTag("kill_42").assertDoesNotExist()
        compose.onNodeWithTag("tasks_error").assertExists()
        compose.onNodeWithTag("tasks_read_only").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.tasks_auto_refresh)).assertDoesNotExist()
    }
    @Test fun taskTabsRequireAComputerOrSuccessfulAndroidProbe() {
        var device by mutableStateOf(MeshDevice("node//visibility", "mesh//test", "Fixture", agentId = 14, conn = 1, rights = 8))
        var probed by mutableStateOf(false)
        compose.setContent { MeshCentralMobileTheme {
            ru.papajon.meshcentralmobile.ui.screens.DeviceScreen(device,
                ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities(), true,
                ru.papajon.meshcentralmobile.ui.DeviceTab.Overview, {}, {}, {},
                taskToolsSupported = probed || TaskToolsProtocol.visible(device)) {}
        } }
        val processes = compose.activity.getString(ru.papajon.meshcentralmobile.R.string.tasks_processes)
        compose.onAllNodesWithText(processes).assertCountEquals(0)
        compose.runOnIdle { probed = true }
        assertTrue(compose.onAllNodesWithText(processes).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { probed = false; device = device.copy(agentId = 6) }
        assertTrue(compose.onAllNodesWithText(processes).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { device = device.copy(agentId = 11) }
        assertTrue(compose.onAllNodesWithText(processes).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { device = device.copy(agentId = null) }
        compose.onAllNodesWithText(processes).assertCountEquals(0)
    }
    @Test fun fieldsStartupAndByteSizesAreFormattedForTheLocale() {
        val context = compose.activity
        assertEquals(context.getString(ru.papajon.meshcentralmobile.R.string.tasks_start_auto), TaskToolsPresentation.startup(context, "AUTO_START"))
        val details = TaskToolsPresentation.details(context, mapOf("workingSet" to "1048576", "totalProcessorTime" to "12.5", "PriorityBoostEnabled" to "true"))
        assertEquals(context.getString(ru.papajon.meshcentralmobile.R.string.tasks_field_working_set), details[0].first)
        assertFalse(details[0].second.contains("1048576"))
        assertFalse(details.any { it.first == "workingSet" || it.first == "totalProcessorTime" })
        assertEquals(context.getString(ru.papajon.meshcentralmobile.R.string.tasks_yes), details.last().second)
    }
    private fun snapshot(name: String) {
        // AGP uninstalls the test app after a run. Shell-owned fixture screenshots survive that
        // cleanup and contain only the synthetic data rendered by this test.
        compose.waitForIdle()
        // Dialog window enter animations are outside the Compose test clock.
        android.os.SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }
}
