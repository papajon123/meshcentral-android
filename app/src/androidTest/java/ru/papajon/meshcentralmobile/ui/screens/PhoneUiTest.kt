package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.screens.management.PhoneSection
import ru.papajon.meshcentralmobile.ui.screens.management.DeviceInspectorScreen
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.ui.screens.session.PhoneViewOnlyNotice
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class PhoneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val phone = MeshDevice("node//phone", "mesh//test", "Test phone", conn = 1, mtype = 2,
        agentId = 14, agentCaps = 13, rights = 24, battery = PhoneBattery(85.0, "ac"))

    @Test fun controlOnlyAllowsLocateActionsAndExplainsConsolePermission() {
        compose.setContent { MeshCentralMobileTheme { PhoneSection(phone.copy(rights = 8), true, false) { error("Not confirmed") } } }
        for (action in listOf(PhoneAction.Vibrate, PhoneAction.Flash)) compose.onNodeWithTag("phone_action_${action.name}").assertIsEnabled()
        for (action in listOf(PhoneAction.Alert, PhoneAction.Toast, PhoneAction.Dial, PhoneAction.OpenUrl)) {
            compose.onNodeWithTag("phone_action_${action.name}").assertIsNotEnabled()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.phone_console_required)).assertIsDisplayed()
        screenshot("phone-console-permission")
    }

    @Test fun revokingOnlyConsolePermissionClosesAnOpenMessage() {
        val device = mutableStateOf(phone)
        compose.setContent { MeshCentralMobileTheme { PhoneSection(device.value, true, false) { error("Forbidden") } } }
        compose.onNodeWithTag("phone_action_Toast").performClick()
        compose.onNodeWithTag("phone_dialog").assertExists()
        compose.runOnIdle { device.value = phone.copy(rights = 8) }
        compose.onNodeWithTag("phone_dialog").assertDoesNotExist()
        compose.onNodeWithTag("phone_action_Vibrate").assertIsEnabled()
    }

    @Test fun phoneBannerAndDockDoNotOfferKeyboardPointerOrClipboard() {
        compose.setContent {
            MeshCentralMobileTheme {
                Column(Modifier.safeDrawingPadding()) {
                    PhoneViewOnlyNotice(phone)
                    SessionDock(RemoteDesktopState(device = phone, connectionState = ConnectionState.Connected,
                        handshakeComplete = true), RemoteControlMode.PanZoom, false, false,
                        {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("phone_view_only").assertIsDisplayed()
        for (label in listOf(R.string.session_dock_typing, R.string.session_dock_keys, R.string.session_dock_clipboard)) {
            compose.onNodeWithText(compose.activity.getString(label)).assertDoesNotExist()
        }
        screenshot("phone-view-only")
    }

    @Test fun viewOnlyUserHasNoPhoneActions() {
        compose.setContent { MeshCentralMobileTheme { PhoneSection(phone.copy(rights = 256), true, false) { error("Forbidden") } } }
        for (action in PhoneAction.entries) compose.onNodeWithTag("phone_action_${action.name}").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.phone_actions_unavailable)).assertIsDisplayed()
    }

    @Test fun actionRequiresReviewThenExplicitConfirmation() {
        val sent = mutableListOf<PhoneRequest>()
        compose.setContent { MeshCentralMobileTheme {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) { PhoneSection(phone, true, false) { sent += it } }
        } }
        screenshot("phone-actions")
        compose.onNodeWithTag("phone_action_Alert").performScrollTo().performClick()
        compose.onNodeWithTag("phone_message_text").performTextInput("Привет \"телефон\"\n😀")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("phone_review").performClick()
        compose.onNodeWithTag("phone_preview").assertTextEquals("Привет ”телефон”\n😀")
        screenshot("phone-confirmation")
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("phone_confirm").performClick()
        compose.runOnIdle { assertEquals(1, sent.size); assertEquals(PhoneAction.Alert, sent.single().action) }
    }

    @Test fun revokingRightsWhileConfirmingClosesDialogWithoutSending() {
        val device = mutableStateOf(phone)
        var sent = false
        compose.setContent { MeshCentralMobileTheme { PhoneSection(device.value, true, false) { sent = true } } }
        compose.onNodeWithTag("phone_action_Vibrate").performClick()
        compose.onNodeWithTag("phone_confirm").assertExists()
        compose.runOnIdle { device.value = phone.copy(rights = 256) }
        compose.onNodeWithTag("phone_confirm").assertDoesNotExist()
        compose.runOnIdle { assertFalse(sent) }
    }

    @Test fun offlineAndUnknownRightsHideAllActions() {
        val device = mutableStateOf(phone.copy(conn = 0))
        compose.setContent { MeshCentralMobileTheme { PhoneSection(device.value, true, false) { error("Forbidden") } } }
        compose.onNodeWithTag("phone_action_Vibrate").assertDoesNotExist()
        compose.runOnIdle { device.value = phone.copy(rights = null) }
        compose.onNodeWithTag("phone_action_Vibrate").assertDoesNotExist()
    }

    @Test fun russianInspectorShowsPhoneInformationInDarkTheme() {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val localized = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                MeshCentralMobileTheme(darkTheme = true) {
                    DeviceInspectorScreen(
                        snapshot = DeviceSystemSnapshot(deviceName = "Test phone", osName = "Android", osVersion = "16",
                            model = "Synthetic phone", networkAddresses = listOf(DeviceSystemSnapshot.NetworkAddress("wlan0", "192.0.2.2", "IPv4"))),
                        services = emptyList(), processes = emptyList(), loading = false, error = null,
                        availablePowerActions = emptySet(), canManageServices = false, onRefresh = {}, onLoadServices = {},
                        onLoadProcesses = {}, onPower = {}, onServiceAction = { _, _ -> }, onBack = {},
                        phoneDevice = phone, devicesCurrent = true,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
        compose.onNodeWithTag("phone_section").assertIsDisplayed()
        compose.onNodeWithText("Заряжается").assertIsDisplayed()
        screenshot("phone-inspector-ru-dark")
        compose.onNodeWithTag("device_inspector_list").performScrollToNode(hasText("192.0.2.2", substring = true))
        compose.onNodeWithText("192.0.2.2", substring = true).assertIsDisplayed()
        screenshot("phone-network-ru-dark")
    }

    @Test fun arabicActionsRemainReachableWithLargeTextAndNarrowWidth() {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) }
        val localized = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density.density, 1.5f)) {
                MeshCentralMobileTheme(darkTheme = false) {
                    Column(Modifier.safeDrawingPadding().widthIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        PhoneSection(phone, true, false) {}
                    }
                }
            }
        }
        for (action in PhoneAction.entries) compose.onNodeWithTag("phone_action_${action.name}").performScrollTo().assertIsDisplayed()
        screenshot("phone-ar-large")
    }

    @Test fun switchingFromComputerServicesToPhoneShowsPhoneDetails() {
        val selectedPhone = mutableStateOf<MeshDevice?>(null)
        compose.setContent { MeshCentralMobileTheme {
            DeviceInspectorScreen(snapshot = DeviceSystemSnapshot(deviceName = "Test device"),
                services = emptyList(), processes = emptyList(), loading = false, error = null,
                availablePowerActions = emptySet(), canManageServices = false, onRefresh = {},
                onLoadServices = {}, onLoadProcesses = {}, onPower = {}, onServiceAction = { _, _ -> }, onBack = {},
                phoneDevice = selectedPhone.value, devicesCurrent = true)
        } }
        compose.onNodeWithTag("device_inspector_tab_services").performClick()
        compose.runOnIdle { selectedPhone.value = phone }
        compose.onNodeWithTag("phone_section").assertIsDisplayed()
        compose.onNodeWithTag("device_inspector_tab_services").assertDoesNotExist()
    }

    @Test fun switchingFromComputerHistoryToPhoneKeepsPhoneActionsAndRefresh() {
        val selectedPhone = mutableStateOf<MeshDevice?>(null)
        var historyLoads = 0
        var refreshes = 0
        compose.setContent { MeshCentralMobileTheme {
            DeviceInspectorScreen(snapshot = DeviceSystemSnapshot(deviceName = "Test device"),
                services = emptyList(), processes = emptyList(), loading = false, error = null,
                availablePowerActions = emptySet(), canManageServices = false, onRefresh = { refreshes++ },
                onLoadServices = {}, onLoadProcesses = {}, onPower = {}, onServiceAction = { _, _ -> }, onBack = {},
                phoneDevice = selectedPhone.value, devicesCurrent = true, onLoadHistory = { historyLoads++ })
        } }
        compose.onNodeWithTag("device_inspector_tab_history").performClick()
        compose.runOnIdle { assertEquals(1, historyLoads); selectedPhone.value = phone }
        compose.onNodeWithTag("phone_section").assertIsDisplayed()
        compose.onNodeWithTag("phone_action_Vibrate").assertIsDisplayed()
        compose.onNodeWithTag("device_inspector_tab_history").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.manage_refresh)).performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(1, historyLoads) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null), "phone-evidence").apply { mkdirs() }
        // PixelCopy captures a dialog with an incorrect window offset on this emulator. Use the
        // display for that case; use the Compose window elsewhere to avoid compositor-frame races.
        val display = if (name == "phone-confirmation") instrumentation.uiAutomation.takeScreenshot() else null
        val bitmap = display ?: compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
