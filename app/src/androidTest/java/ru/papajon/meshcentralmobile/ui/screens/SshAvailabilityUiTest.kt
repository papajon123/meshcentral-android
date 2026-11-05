package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class SshAvailabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selected = mutableListOf<DeviceTab>()
    private fun show(features: Long?, rights: Int = MeshRights.FULL) {
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceScreen(
                    MeshDevice("node//availability", "mesh//fixture", "Fixture", conn = 1, agentId = 3,
                        mtype = 2, connectivityKnown = true, rights = rights),
                    MeshServerCapabilities(features2 = features), true, DeviceTab.Overview,
                    onSelect = { selected += it }, onBack = {}, onWebPanel = {}, sessionContent = {},
                )
            }
        }
    }
    @Test fun disabledServerSftpDoesNotDisableSupportedSsh() {
        show(0x200L or 0x800000L)
        compose.onNodeWithTag("device-tool-Sftp").performScrollTo().assertIsNotEnabled()
            .assertTextContains(compose.activity.getString(R.string.relay_unsupported))
        compose.onNodeWithTag("device-tool-Ssh").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(DeviceTab.Ssh), selected) }
    }
    @Test fun unknownCapabilityIsAnExplicitAttemptWithAnHonestLabel() {
        show(null)
        compose.onNodeWithTag("device-tool-Ssh").performScrollTo().assertIsEnabled()
            .assertTextContains(compose.activity.getString(R.string.relay_availability_unknown)).performClick()
        compose.runOnIdle { assertEquals(listOf(DeviceTab.Ssh), selected) }
    }
    @Test fun deniedTerminalRightsDisableBothSshAndSftp() {
        show(0x200L, MeshRights.REMOTE_CONTROL or MeshRights.NO_TERMINAL)
        for (tab in listOf("Ssh", "Sftp")) {
            compose.onNodeWithTag("device-tool-$tab").performScrollTo().assertIsNotEnabled()
                .assertTextContains(compose.activity.getString(R.string.runtime_capability_no_permission))
        }
        compose.runOnIdle { assertEquals(emptyList<DeviceTab>(), selected) }
    }
}
