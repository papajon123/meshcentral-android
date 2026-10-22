package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DeviceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = MeshDevice(nodeId = "node//menu-fixture", meshId = "mesh//lab", name = "Build server",
        meshName = "Lab", conn = 1, pwr = 1, osDescription = "Linux", mtype = 2,
        agentId = 6, agentCaps = 7, rights = MeshRights.FULL)

    @Test fun menuReachesSftpWithoutHorizontalTabScrollingAndBackStillWorks() {
        var selected by mutableStateOf(DeviceTab.Overview)
        var backs = 0
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceScreen(device, MeshServerCapabilities(features2 = 512), true, selected,
                    { selected = it }, { backs++ }, {}) { Text("active:${selected.name}") }
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.workspace_tools)).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.device_sftp)) and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("active:Sftp").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DeviceTab.Sftp, selected) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.device_back)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun menuDoesNotBypassDeniedSessionRights() {
        var selections = 0
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceScreen(device.copy(rights = MeshRights.NO_DESKTOP), MeshServerCapabilities(features2 = 512),
                    true, DeviceTab.Overview, { selections++ }, {}, {}) {}
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.workspace_tools)).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.device_sftp)) and hasAnyAncestor(isPopup())).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, selections) }
    }
}
