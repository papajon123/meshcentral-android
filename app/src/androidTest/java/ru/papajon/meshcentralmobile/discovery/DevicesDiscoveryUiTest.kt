package ru.papajon.meshcentralmobile.discovery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DevicesDiscoveryUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchWithoutResultsHasItsOwnMessageAndClearRestoresTheList() {
        showDevices(listOf(device("one", "Alpha")))

        compose.onNode(hasSetTextAction()).performTextInput("missing")
        compose.onNodeWithText(text(R.string.browser_search_empty_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.browser_search_clear)).performClick()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun emptyFilterResultUsesAFilterSpecificMessage() {
        showDevices(listOf(device("one", "Alpha")), recentIds = emptyList())

        compose.onNodeWithText(text(R.string.browser_filter_recent), substring = true).performScrollTo().assertIsDisplayed().performClick()

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.browser_recent_empty_title)))
        compose.onNodeWithText(text(R.string.browser_recent_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.browser_search_empty_title)).assertDoesNotExist()
    }

    @Test
    fun recentFilterFollowsSavedOrderAndHidesUnknownIds() {
        showDevices(
            devices = listOf(
                device("one", "One"),
                device("two", "Two"),
                device("three", "Three"),
            ),
            recentIds = listOf("three", "one", "not-in-the-current-list"),
        )

        compose.onNodeWithText(text(R.string.browser_filter_recent), substring = true).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Two").assertDoesNotExist()

        val threeTop = compose.onNodeWithText("Three").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val oneTop = compose.onNodeWithText("One").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("Recent order must put Three before One", threeTop < oneTop)

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.browser_recent_clear)))
        compose.onNodeWithText(text(R.string.browser_recent_clear)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.browser_recent_empty_title)))
        compose.onNodeWithText(text(R.string.browser_recent_empty_title)).assertIsDisplayed()
    }

    @Test
    fun searchKeepsOnlineStatusAndConnectPermission() {
        showDevices(listOf(device("one", "Alpha")))

        compose.onNode(hasSetTextAction()).performTextInput("alpha")

        compose.onNodeWithText(text(R.string.common_status_online)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.devices_action_connect)).assertIsDisplayed()
    }

    @Test
    fun searchFieldIsLabelledForTalkBackAndLongNamesStayAccessible() {
        val longName = "LONG-UNIQUE-FRAGMENT " + "Очень длинное имя компьютера ".repeat(8)
        showDevices(listOf(device("long", longName)))

        compose.onNodeWithContentDescription(text(R.string.browser_search_label)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("long-unique-fragment")
        compose.onNodeWithText(longName).assertIsDisplayed()
    }

    private fun showDevices(devices: List<MeshDevice>, recentIds: List<String> = emptyList()) {
        compose.setContent {
            MeshCentralMobileTheme {
                var recents by remember { mutableStateOf(recentIds) }
                DevicesContent(
                    clientState = MeshClientState(
                        socketConnected = true,
                        devicesCurrent = true,
                        requiresSignIn = false,
                        connectionState = ConnectionState.Authenticated,
                        serverName = "server.example",
                        groups = listOf(MeshGroup(id = "mesh", name = "Office")),
                        devices = devices,
                    ),
                    favorites = emptyList(),
                    onAddFavorite = { _, _ -> },
                    onRemoveFavorite = {},
                    onConnect = {},
                    onRefresh = {},
                    onOpenSettings = {},
                    onSignIn = {},
                    recentDeviceIds = recents,
                    onClearRecents = { recents = emptyList() },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun device(nodeId: String, name: String): MeshDevice = MeshDevice(
        nodeId = nodeId,
        meshId = "mesh",
        name = name,
        agentId = 1,
        conn = 1,
    )

    private fun text(id: Int): String = compose.activity.getString(id)
}
