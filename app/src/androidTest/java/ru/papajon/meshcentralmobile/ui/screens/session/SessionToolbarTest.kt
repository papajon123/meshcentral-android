package ru.papajon.meshcentralmobile.ui.screens.session

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.screens.SessionDock
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class SessionToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selected = mutableListOf<Int>()
    private val frame = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private fun ready() = RemoteDesktopState(device = MeshDevice("fixture", "group", "Workstation", agentId = 3),
        connectionState = ConnectionState.Connected, handshakeComplete = true, framebuffer = frame,
        remoteWidth = 1920, remoteHeight = 1080, displays = listOf(65535, 1, 2), selectedDisplay = 1)

    @Test fun twoPhysicalMonitorsHaveThreeDirectButtonsWithoutAMenu() {
        val state = mutableStateOf(ready())
        compose.setContent { MeshCentralMobileTheme {
            SessionDisplaySelector(state.value, { selected += it; state.value = state.value.copy(selectedDisplay = it) })
        } }
        compose.onNodeWithTag("session-display-menu").assertDoesNotExist()
        listOf(1, 2, 65535).forEach { id ->
            compose.onNodeWithTag("session-display-$id").assertIsDisplayed().performClick().assertIsSelected()
        }
        compose.runOnIdle { assertEquals(listOf(1, 2, 65535), selected) }
    }

    @Test fun threeMonitorsUseTheMenuAndPreserveNonConsecutiveIds() {
        compose.setContent { MeshCentralMobileTheme {
            SessionDisplaySelector(ready().copy(displays = listOf(65535, 2, 7, 9)), selected::add)
        } }
        compose.onNodeWithTag("session-display-2").assertDoesNotExist()
        compose.onNodeWithTag("session-display-menu").performClick()
        compose.onNodeWithTag("session-display-choice-7").performClick()
        compose.runOnIdle { assertEquals(listOf(7), selected) }
        compose.onNodeWithTag("session-display-choice-7").assertDoesNotExist()
    }

    @Test fun oneMonitorDoesNotOfferInventedMonitorIds() {
        compose.setContent { MeshCentralMobileTheme { SessionDisplaySelector(ready().copy(displays = listOf(7)), selected::add) } }
        compose.onNodeWithTag("session-display-7").performClick()
        compose.onNodeWithTag("session-display-2").assertDoesNotExist()
        compose.onNodeWithTag("session-display-menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(7), selected) }
    }

    @Test fun allIsAvailableForTwoMonitorsAndReconnectDisablesSwitching() {
        val state = mutableStateOf(ready().copy(displays = listOf(1, 2)))
        compose.setContent { MeshCentralMobileTheme { SessionDisplaySelector(state.value, selected::add) } }
        compose.onNodeWithTag("session-display-65535").performClick()
        compose.runOnIdle { assertEquals(listOf(65535), selected); state.value = state.value.copy(connectionState = ConnectionState.Reconnecting) }
        compose.onNodeWithTag("session-display-1").assertIsNotEnabled()
        compose.onNodeWithTag("session-display-65535").assertIsNotEnabled()
    }

    @Test fun statusUsesAnIndicatorAndOpensTheActualProblemWithRetry() {
        var retried = 0
        val reason = "Synthetic connection refused"
        val state = mutableStateOf(ready())
        compose.setContent { MeshCentralMobileTheme {
            SessionConnectionStatus(state.value, onReconnect = { retried++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.session_connection_ready)).assertDoesNotExist()
        compose.onNodeWithTag("session-connection-status").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
            compose.activity.getString(R.string.session_connection_ready)))
        compose.runOnIdle { state.value = state.value.copy(framebuffer = null) }
        compose.onNodeWithTag("session-connection-status").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
            compose.activity.getString(R.string.session_connection_connecting)))
        compose.runOnIdle { state.value = state.value.copy(connectionState = ConnectionState.Failed, message = UiText.literal(reason)) }
        compose.onNodeWithText(reason).assertDoesNotExist()
        compose.onNodeWithTag("session-connection-status").performClick()
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retried) }
        compose.onNodeWithText(compose.activity.getString(R.string.session_options_reconnect)).performClick()
        compose.runOnIdle { assertEquals(1, retried) }
        compose.onNodeWithTag("session-connection-details").assertDoesNotExist()
    }

    @Test fun topBarKeepsLongDetailsOutOfTheCanvasUntilRequested() {
        val longText = "Synthetic diagnostic details ".repeat(20)
        compose.setContent { MeshCentralMobileTheme {
            Box(Modifier.width(320.dp)) { SessionTopBar(ready(), {}, {}, {}, {}, {}, debugDetails = longText) }
        } }
        compose.onNodeWithText(longText).assertDoesNotExist()
        compose.onNodeWithTag("session-stream-rate").assertIsDisplayed()
        compose.onNodeWithTag("session-connection-status").performClick()
        compose.onNodeWithText(longText).performScrollTo().assertIsDisplayed()
    }

    @Test fun bottomHeightIsStableAcrossModesDragAndLargeFont() {
        val mode = mutableStateOf(RemoteControlMode.Trackpad)
        val drag = mutableStateOf(false)
        val font = mutableStateOf(1f)
        compose.setContent { MeshCentralMobileTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) {
                Box(Modifier.width(320.dp).testTag("toolbar-fixture")) {
                    SessionDock(ready(), mode.value, drag.value, false, {}, {}, {}, {}, {}, {})
                }
            }
        } }
        val height = compose.onNodeWithTag("session-dock").fetchSemanticsNode().boundsInRoot.height
        RemoteControlMode.entries.forEach { next ->
            compose.runOnIdle { mode.value = next; drag.value = true; font.value = 2f }
            assertEquals(height, compose.onNodeWithTag("session-dock").fetchSemanticsNode().boundsInRoot.height, 0.5f)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.session_dock_typing)).assertIsDisplayed()
        }
    }
}
