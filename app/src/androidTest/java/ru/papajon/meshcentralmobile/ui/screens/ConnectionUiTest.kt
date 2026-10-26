package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.AppDestination
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.components.MeshNavigationBar
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File

class ConnectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnectedComputersCannotBeOpenedOrReportedOnline() {
        devices(connected = false)
        val unknown = compose.activity.getString(R.string.connection_status_unknown)
        val online = compose.activity.getString(R.string.common_status_online)
        val connect = compose.activity.getString(R.string.devices_action_connect)
        val list = compose.onNode(hasScrollToIndexAction())
        for (nodeId in listOf("one", "two")) {
            val rowTag = "device-row-$nodeId"
            list.performScrollToNode(hasTestTag(rowTag))
            compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(
                hasContentDescription(unknown) and hasAnyAncestor(hasTestTag(rowTag)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            compose.onAllNodes(
                hasContentDescription(online) and hasAnyAncestor(hasTestTag(rowTag)),
                useUnmergedTree = true,
            ).assertCountEquals(0)
            compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertHasNoClickAction()
            compose.onAllNodes(
                hasContentDescription(connect) and hasAnyAncestor(hasTestTag(rowTag)),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
        compose.onAllNodesWithContentDescription(connect).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(online, useUnmergedTree = true).assertCountEquals(0)
        list.performScrollToNode(hasText(compose.activity.getString(R.string.connection_retry)))
        compose.onNodeWithText(compose.activity.getString(R.string.connection_retry)).assertIsDisplayed()
        snapshot("devices-offline.png")
    }

    @Test fun computerCardsAndNavigationFitTheScreen() {
        devices(connected = true)
        val connect = compose.activity.getString(R.string.devices_action_connect)
        val list = compose.onNode(hasScrollToIndexAction())
        for (nodeId in listOf("one", "two")) {
            val rowTag = "device-row-$nodeId"
            list.performScrollToNode(hasTestTag(rowTag))
            compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(
                hasClickAction() and hasAnyAncestor(hasTestTag(rowTag)) and
                    hasAnyDescendant(hasContentDescription(connect)),
                useUnmergedTree = true,
            ).assertIsDisplayed().assertIsEnabled()
            compose.onNode(
                hasContentDescription(connect) and hasAnyAncestor(hasTestTag(rowTag)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.common_nav_devices_short)).assertIsDisplayed()
        snapshot("devices-online.png")
    }

    @Test fun twoFactorCanBeSubmittedWithoutRetypingThePassword() {
        var submitted = ""
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(
                    clientState = MeshClientState(twoFactorChallenge = TwoFactorChallenge()),
                    serverUrl = "https://server.example/", storedUsername = "test-user",
                    onSubmitCode = { code, _ -> submitted = code },
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.login_two_factor_code)).performTextInput("123456")
        compose.onNodeWithText(compose.activity.getString(R.string.login_action_sign_in_with_code)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("123456", submitted) }
    }

    @Test fun loginShowsReadableConnectionFailure() {
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(
                    clientState = MeshClientState(connectionState = ConnectionState.Failed,
                        lastError = MeshConnectionError.describe(java.net.UnknownHostException())),
                    serverUrl = "https://server.example/", storedUsername = "test-user",
                    onContinueWithoutSignIn = {},
                )
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.login_edit_server)).assertIsDisplayed()
        snapshot("login-offline.png")
    }

    private fun devices(connected: Boolean) {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                it.window.isNavigationBarContrastEnforced = false
            }
        }
        compose.setContent {
            MeshCentralMobileTheme {
                Scaffold(bottomBar = { MeshNavigationBar(AppDestination.Devices, {}) }) { padding ->
                    DevicesContent(
                        clientState = MeshClientState(
                            socketConnected = connected, devicesCurrent = connected, requiresSignIn = false,
                            connectionState = if (connected) ConnectionState.Authenticated else ConnectionState.Reconnecting,
                            serverName = "server.example",
                            lastError = if (connected) null else UiText.Resource(R.string.runtime_error_host_not_found),
                            groups = listOf(MeshGroup("fixture", "Рабочие компьютеры")),
                            devices = listOf(
                                MeshDevice("one", "fixture", "Рабочий компьютер", conn = 1, agentId = 4, osDescription = "Майкрософт Windows 11 Профессиональная"),
                                MeshDevice("two", "fixture", "Домашний компьютер", conn = 1, agentId = 4, osDescription = "Майкрософт Windows 11 Домашняя"),
                            ),
                        ),
                        favorites = emptyList(), onAddFavorite = { _, _ -> }, onRemoveFavorite = {},
                        onConnect = {}, onRefresh = {}, onOpenSettings = {}, onSignIn = {},
                        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    )
                }
            }
        }
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        // Semantic/layout readiness can precede SurfaceFlinger's first visible frame.
        android.os.SystemClock.sleep(400)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
