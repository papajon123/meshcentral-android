package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.security.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class NetworkWaitingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstConnectionShowsWhyItIsWaiting() = devices(emptyList())
    @Test fun cachedDevicesShowNetworkWaitInsteadOfAnOldError() = devices(listOf(
        MeshDevice(nodeId = "node/test/fixture", meshId = "mesh/test/fixture", name = "Fixture")))

    private fun devices(devices: List<MeshDevice>) {
        compose.setContent {
            MeshCentralMobileTheme {
                DevicesContent(clientState = MeshClientState(connectionState = ConnectionState.Reconnecting,
                    requiresSignIn = false, devices = devices, message = UiText.Resource(R.string.reliability_waiting_network)),
                    favorites = emptyList(), onAddFavorite = { _, _ -> }, onRemoveFavorite = {},
                    onConnect = {}, onRefresh = {}, onOpenSettings = {}, onSignIn = {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.reliability_waiting_network), substring = true).assertIsDisplayed()
    }

    @Test fun desktopShowsNetworkWaitInsteadOfAttemptZero() {
        compose.setContent { MeshCentralMobileTheme {
            ConnectionOverlay(RemoteDesktopState(connectionState = ConnectionState.Reconnecting,
                message = UiText.Resource(R.string.reliability_waiting_network)), {}, {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.reliability_waiting_network)).assertIsDisplayed()
    }

    @Test fun offlineSignInShowsTheWaitReason() {
        val vault = SecureVault(compose.activity, preferencesName = "network-waiting-ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = MeshCentralClient(vault, scope)
        try {
            compose.runOnIdle { client.onNetworkLost(); client.login("https://mesh.example/", "fixture", "synthetic") }
            compose.setContent { MeshCentralMobileTheme { LoginScreen(client, vault, "https://mesh.example/") } }
            compose.onNodeWithText(compose.activity.getString(R.string.reliability_waiting_network)).assertIsDisplayed()
        } finally { client.close(); scope.cancel(); vault.clearCredentials() }
    }
}
