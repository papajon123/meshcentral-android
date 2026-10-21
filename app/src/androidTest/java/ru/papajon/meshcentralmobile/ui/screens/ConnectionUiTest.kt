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
        compose.onAllNodesWithContentDescription("Подключиться").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("статус неизвестен").assertCountEquals(2)
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        snapshot("devices-offline.png")
    }

    @Test fun computerCardsAndNavigationFitTheScreen() {
        devices(connected = true)
        compose.onAllNodesWithContentDescription("Подключиться").assertCountEquals(2)
        compose.onNodeWithText("ПК").assertIsDisplayed()
        snapshot("devices-online.png")
    }

    @Test fun twoFactorCanBeSubmittedWithoutRetypingThePassword() {
        var submitted = ""
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(
                    clientState = MeshClientState(twoFactorChallenge = TwoFactorChallenge()),
                    serverUrl = "https://server.example/", storedUsername = "test-user",
                    onSubmitCode = { submitted = it },
                )
            }
        }
        compose.onNodeWithText("Код подтверждения").performTextInput("123456")
        compose.onNodeWithText("Войти с\u00A0кодом").performScrollTo().assertIsEnabled().performClick()
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
        compose.onNodeWithContentDescription("Изменить адрес сервера").assertIsDisplayed()
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
                            lastError = if (connected) null else "Не удалось найти сервер. Проверьте интернет, VPN и адрес сервера.",
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
