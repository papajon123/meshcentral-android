package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.activity.ComponentActivity
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DevicePowerAction
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.management.ManagedProcess
import ru.papajon.meshcentralmobile.management.ManagedService
import ru.papajon.meshcentralmobile.management.ServiceAction
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DeviceInspectorScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val snapshot = DeviceSystemSnapshot(
        deviceName = "Night PC",
        osName = "Windows 11 Pro",
        cpu = "Intel Core i7",
        memoryModules = listOf(
            DeviceSystemSnapshot.MemoryModule(capacityBytes = 16L * 1024 * 1024 * 1024, speed = "3200"),
        ),
        disks = listOf(
            DeviceSystemSnapshot.Disk(name = "Disk 0", model = "NVMe", capacityBytes = 512L * 1024 * 1024 * 1024),
        ),
        networkAddresses = listOf(
            DeviceSystemSnapshot.NetworkAddress(interfaceName = "eth0", address = "192.168.1.20", family = "IPv4"),
        ),
    )

    private val spooler = ManagedService(
        name = "Spooler",
        displayName = "Print Spooler",
        state = "Running",
        startMode = "Automatic",
    )
    private val audio = ManagedService(
        name = "Audiosrv",
        displayName = "Windows Audio",
        state = "Stopped",
        startMode = "Manual",
    )
    private val browser = ManagedProcess(pid = 4242, name = "browser", cpu = "2%", memory = "180 MB")

    @Test
    fun confirmingRestartSendsTheExactActionAndTarget() {
        val actions = mutableListOf<DevicePowerAction>()
        show(
            availablePowerActions = setOf(DevicePowerAction.Restart, DevicePowerAction.Shutdown),
            onPower = { actions += it },
        )

        compose.onNodeWithTag(DeviceInspectorTestTags.PowerMenu).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.power(DevicePowerAction.Restart)).performClick()

        val title = compose.activity.getString(
            R.string.manage_confirm_power_title,
            compose.activity.getString(R.string.manage_action_restart),
            "Night PC",
        )
        compose.onNodeWithTag(DeviceInspectorTestTags.ConfirmDialog).assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.onNodeWithTag(DeviceInspectorTestTags.Confirm).performClick()

        compose.runOnIdle { assertEquals(listOf(DevicePowerAction.Restart), actions) }
    }

    @Test
    fun cancellingPowerConfirmationCallsNothing() {
        val actions = mutableListOf<DevicePowerAction>()
        show(
            availablePowerActions = setOf(DevicePowerAction.Restart, DevicePowerAction.Shutdown),
            onPower = { actions += it },
        )

        compose.onNodeWithTag(DeviceInspectorTestTags.PowerMenu).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.power(DevicePowerAction.Shutdown)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.Cancel).performClick()

        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag(DeviceInspectorTestTags.ConfirmDialog).assertDoesNotExist()
    }

    @Test
    fun serviceStopConfirmationNamesAndSendsOnlyTheChosenService() {
        val calls = mutableListOf<Pair<String, ServiceAction>>()
        show(
            services = listOf(spooler, audio),
            canManageServices = true,
            onServiceAction = { name, action -> calls += name to action },
        )

        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.serviceMenu("Audiosrv"))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(
            DeviceInspectorTestTags.serviceAction("Audiosrv", ServiceAction.Stop),
        ).performClick()

        val title = compose.activity.getString(
            R.string.manage_confirm_service_title,
            compose.activity.getString(R.string.manage_action_stop),
            "Windows Audio",
        )
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.onNodeWithTag(DeviceInspectorTestTags.Confirm).performClick()

        compose.runOnIdle {
            assertEquals(listOf("Audiosrv" to ServiceAction.Stop), calls)
        }
    }

    @Test
    fun deniedPowerAndServiceControlsAreNotAvailable() {
        show(
            services = listOf(spooler),
            availablePowerActions = emptySet(),
            canManageServices = false,
        )

        compose.onNodeWithTag(DeviceInspectorTestTags.PowerMenu).assertDoesNotExist()
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.ServicesReadOnly).assertIsDisplayed()
        compose.onNodeWithTag(DeviceInspectorTestTags.service("Spooler")).assertIsDisplayed()
        compose.onNodeWithTag(DeviceInspectorTestTags.serviceMenu("Spooler")).assertDoesNotExist()
    }

    @Test
    fun pendingActionIsShownAsWaitingNotAsSuccess() {
        show(pendingAction = "restart requested")

        compose.onNodeWithTag(DeviceInspectorTestTags.Pending).assertIsDisplayed()
        compose.onNodeWithText(
            compose.activity.getString(R.string.manage_pending, "restart requested"),
        ).assertIsDisplayed()
    }

    @Test
    fun refreshLoadsTheVisibleListInsteadOfGeneralDetails() {
        var detailsReads = 0
        var serviceReads = 0
        var processReads = 0
        compose.setContent {
            var services by remember { mutableStateOf(listOf(spooler)) }
            var processes by remember { mutableStateOf(listOf(browser)) }
            MeshCentralMobileTheme {
                DeviceInspectorScreen(
                    snapshot, services, processes, false, null, emptySet(), false,
                    onRefresh = { detailsReads++ },
                    onLoadServices = {
                        serviceReads++
                        if (serviceReads > 1) services = listOf(spooler.copy(displayName = "Updated service"))
                    },
                    onLoadProcesses = {
                        processReads++
                        if (processReads > 1) processes = listOf(browser.copy(name = "updated-process"))
                    },
                    onPower = {}, onServiceAction = { _, _ -> }, onBack = {},
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
        compose.onNodeWithTag(DeviceInspectorTestTags.Refresh).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithText("Print Spooler").assertIsDisplayed()
        compose.onNodeWithTag(DeviceInspectorTestTags.Refresh).performClick()
        compose.onNodeWithText("Updated service").assertIsDisplayed()
        compose.onNodeWithText("Print Spooler").assertDoesNotExist()
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Processes)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.Refresh).performClick()
        compose.onNodeWithText("updated-process").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, detailsReads) }
    }

    @Test
    fun errorActionsRefreshTheSelectedListAndOpenHelpWithoutMutations() {
        var detailsReads = 0
        var serviceReads = 0
        var helpOpened = false
        var mutations = 0
        show(
            error = compose.activity.getString(R.string.companion_job_error_timeout),
            onRefresh = { detailsReads++ },
            onLoadServices = { serviceReads++ },
            onOpenHelp = { helpOpened = true },
            onPower = { mutations++ }, onServiceAction = { _, _ -> mutations++ },
        )
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.ErrorRefresh).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.ErrorHelp).performClick()
        compose.runOnIdle {
            assertEquals(2, serviceReads)
            assertEquals(0, detailsReads)
            assertEquals(0, mutations)
            assertTrue(helpOpened)
        }
    }

    @Test
    fun errorActionsAreDisabledWhileARequestIsPending() {
        show(loading = true, error = "request failed", onOpenHelp = {})
        compose.onNodeWithTag(DeviceInspectorTestTags.ErrorRefresh).assertIsNotEnabled()
        compose.onNodeWithTag(DeviceInspectorTestTags.ErrorHelp).assertIsNotEnabled()
    }

    @Test
    fun serviceNamedStatusDoesNotCollideWithTheErrorItem() {
        show(services = listOf(spooler.copy(name = "status", displayName = "Status service")), error = "read failed")
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithTag(DeviceInspectorTestTags.ContentList).performScrollToNode(hasTestTag(DeviceInspectorTestTags.service("status")))
        compose.onNodeWithTag(DeviceInspectorTestTags.service("status")).assertIsDisplayed()
    }

    @Test
    fun localizedErrorAndRecoveryActionsFitTheScreen() {
        val args = InstrumentationRegistry.getArguments()
        val locale = args.getString("inspectorLocale") ?: "en"
        val dark = args.getString("inspectorDark") == "true"
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        }
        val context = compose.activity.createConfigurationContext(configuration)
        val message = context.getString(R.string.companion_job_error_server_restart)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                MeshCentralMobileTheme(darkTheme = dark) {
                    DeviceInspectorScreen(
                        snapshot, listOf(spooler), listOf(browser), false, message, emptySet(), false,
                        onRefresh = {}, onLoadServices = {}, onLoadProcesses = {},
                        onPower = {}, onServiceAction = { _, _ -> }, onBack = {}, onOpenHelp = {},
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
        compose.onNodeWithTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services)).performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        capture("inspector-error.png")
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        for (tag in listOf(DeviceInspectorTestTags.ErrorRefresh, DeviceInspectorTestTags.ErrorHelp)) {
            val action = compose.onNodeWithTag(tag)
            action.performScrollTo()
            action.assertIsDisplayed()
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("Recovery action must fit vertically", bounds.top >= root.top && bounds.bottom <= root.bottom)
            assertTrue("Recovery action must fit horizontally", bounds.left >= root.left && bounds.right <= root.right)
        }
        compose.onNodeWithTag(DeviceInspectorTestTags.ContentList).performScrollToNode(hasTestTag(DeviceInspectorTestTags.service("Spooler")))
        compose.onNodeWithTag(DeviceInspectorTestTags.service("Spooler")).assertIsDisplayed()
        capture("inspector-list.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Some API 26 emulator renderers cannot capture the display through UiAutomation.
        // Capture the actual Compose window in that case; never skip the visual assertions.
        val display = instrumentation.uiAutomation.takeScreenshot()
        val bitmap = display ?: compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Screenshot must contain pixels", bitmap.width > 0 && bitmap.height > 0)
        File(instrumentation.targetContext.cacheDir, "inspector-capture-method.txt")
            .writeText(if (display != null) "UiAutomation" else "ComposeWindow")
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun show(
        snapshot: DeviceSystemSnapshot? = this.snapshot,
        services: List<ManagedService> = emptyList(),
        processes: List<ManagedProcess> = listOf(browser),
        loading: Boolean = false,
        error: String? = null,
        availablePowerActions: Set<DevicePowerAction> = setOf(DevicePowerAction.Restart),
        canManageServices: Boolean = false,
        pendingAction: String? = null,
        onRefresh: () -> Unit = {},
        onLoadServices: () -> Unit = {},
        onLoadProcesses: () -> Unit = {},
        onPower: (DevicePowerAction) -> Unit = {},
        onServiceAction: (String, ServiceAction) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
        onOpenHelp: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceInspectorScreen(
                    snapshot = snapshot,
                    services = services,
                    processes = processes,
                    loading = loading,
                    error = error,
                    availablePowerActions = availablePowerActions,
                    canManageServices = canManageServices,
                    onRefresh = onRefresh,
                    onLoadServices = onLoadServices,
                    onLoadProcesses = onLoadProcesses,
                    onPower = onPower,
                    onServiceAction = onServiceAction,
                    onBack = onBack,
                    pendingAction = pendingAction,
                    onOpenHelp = onOpenHelp,
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
        compose.waitForIdle()
    }
}
