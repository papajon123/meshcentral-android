package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
                )
            }
        }
        compose.waitForIdle()
    }
}
