package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.screens.session.*
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PhoneRemoteControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val phone = MeshDevice("node//phone", "mesh//fixture", "Fixture phone", conn = 1, agentId = 14,
        rights = 8, remoteInput = PhoneRemoteInput.Enabled)
    private fun state(device: MeshDevice = phone) = RemoteDesktopState(device = device,
        connectionState = ConnectionState.Connected, handshakeComplete = true)

    @Test fun panelRequiresEnabledCapabilityAndCurrentUnrestrictedSession() {
        val current = mutableStateOf(state())
        compose.setContent { MeshCentralMobileTheme { PhoneControlPanel(current.value, Sink()) } }
        compose.onNodeWithTag("phone_control_panel").assertIsDisplayed()
        for (unavailable in listOf(state(phone.copy(remoteInput = PhoneRemoteInput.Unknown)),
            state(phone.copy(remoteInput = PhoneRemoteInput.Disabled)), state(phone.copy(remoteInput = PhoneRemoteInput.Unsupported)),
            state(phone.copy(rights = 256)), state(phone.copy(rights = 264)), state(phone.copy(rights = null)),
            state(phone.copy(agentId = 3)), state().copy(inputLocked = true), state().copy(handshakeComplete = false),
            state().copy(connectionState = ConnectionState.Reconnecting))) {
            compose.runOnIdle { current.value = unavailable }
            compose.onNodeWithTag("phone_control_panel").assertDoesNotExist()
        }
    }

    @Test fun viewOnlyReasonsAreSpecificAndDisappearForEnabledPhoneAndComputer() {
        val current = mutableStateOf(phone.copy(remoteInput = PhoneRemoteInput.Unknown))
        compose.setContent { MeshCentralMobileTheme { PhoneViewOnlyNotice(current.value) } }
        for ((capability, text) in listOf(PhoneRemoteInput.Unknown to R.string.phone_control_unknown,
            PhoneRemoteInput.Disabled to R.string.phone_control_disabled, PhoneRemoteInput.Unsupported to R.string.phone_control_unsupported)) {
            compose.runOnIdle { current.value = phone.copy(remoteInput = capability) }
            compose.onNodeWithText(compose.activity.getString(text)).assertIsDisplayed()
        }
        compose.runOnIdle { current.value = phone.copy(rights = 264) }
        compose.onNodeWithText(compose.activity.getString(R.string.phone_control_no_permission)).assertIsDisplayed()
        compose.runOnIdle { current.value = phone }
        compose.onNodeWithTag("phone_view_only").assertDoesNotExist()
        compose.runOnIdle { current.value = phone.copy(agentId = 3) }
        compose.onNodeWithTag("phone_view_only").assertDoesNotExist()
    }

    @Test fun labelledButtonsSendNavigationAndUnicodeTextAndRevocationClearsDraft() {
        val input = Sink()
        val current = mutableStateOf(state())
        compose.setContent { MeshCentralMobileTheme {
            Box(Modifier.safeDrawingPadding().widthIn(max = 360.dp)) { PhoneControlPanel(current.value, input) }
        } }
        screenshot("phone-control-panel")
        PhoneNavigationKey.entries.forEach { compose.onNodeWithTag("phone_control_${it.name}").performClick() }
        compose.runOnIdle { assertEquals(listOf(27, 36, 120, 121, 122).flatMap { listOf(it to true, it to false) }, input.keys) }
        compose.onNodeWithTag("phone_control_text").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.phone_control_text_hint)).assertIsDisplayed()
        compose.onNodeWithTag("phone_control_text_field").performTextInput("Привет 😀")
        screenshot("phone-control-text")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("phone_control_send").performClick()
        compose.runOnIdle { assertEquals(listOf("Привет 😀"), input.texts) }
        compose.onNodeWithTag("phone_control_text").performClick()
        compose.onNodeWithTag("phone_control_text_field").performTextInput("unsent")
        compose.runOnIdle { current.value = state(phone.copy(rights = 256)) }
        compose.onNodeWithTag("phone_control_send").assertDoesNotExist()
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithTag("phone_control_text").performClick()
        compose.onNodeWithTag("phone_control_text_field").assert(hasText("unsent").not())
    }

    @Test fun computerKeepsItsKeyboardPointerAndClipboardDock() {
        compose.setContent { MeshCentralMobileTheme {
            Column { PhoneControlPanel(state(phone.copy(agentId = 3)), Sink())
                SessionDock(state(phone.copy(agentId = 3)), RemoteControlMode.Trackpad, false, false, {}, {}, {}, {}, {}, {}) }
        } }
        compose.onNodeWithTag("phone_control_panel").assertDoesNotExist()
        for (label in listOf(R.string.session_dock_typing, R.string.session_dock_keys, R.string.session_dock_clipboard)) {
            compose.onNodeWithText(compose.activity.getString(label)).assertIsDisplayed()
        }
    }

    @Test fun twoFingerPinchNeverSendsPointerOrKeys() {
        val input = Sink()
        canvas(input)
        compose.onNodeWithTag("phone_control_canvas").performTouchInput {
            down(0, Offset(center.x - 20, center.y))
            advanceEventTime(80)
            down(1, Offset(center.x + 20, center.y))
            moveTo(0, Offset(center.x - 80, center.y))
            moveTo(1, Offset(center.x + 80, center.y))
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(input.mouse.isEmpty()); assertTrue(input.keys.isEmpty()) }
    }

    @Test fun directTapHoldAndSwipeSendLeftPointerEvenWhenComputerModeIsTrackpad() {
        val input = Sink()
        canvas(input)
        val node = compose.onNodeWithTag("phone_control_canvas")
        node.performTouchInput { click(center) }
        compose.waitUntil(3000) { input.mouse.count { it == "up" } == 1 }
        node.performTouchInput { longClick(center, durationMillis = 650) }
        compose.waitUntil(4000) { input.mouse.count { it == "up" } == 2 }
        node.performTouchInput { swipe(center, Offset(center.x, center.y / 2), durationMillis = 320) }
        compose.waitUntil(4000) { input.mouse.count { it == "up" } == 3 }
        compose.runOnIdle { assertEquals(3, input.mouse.count { it == "down" }); assertTrue(input.mouse.contains("move")) }
    }

    private fun canvas(input: Sink) {
        val frame = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        compose.setContent { MeshCentralMobileTheme {
            var zoom by remember { mutableStateOf(1f) }
            var x by remember { mutableStateOf(0f) }; var y by remember { mutableStateOf(0f) }
            SessionCanvas(state().copy(framebuffer = frame, remoteWidth = 400, remoteHeight = 800),
                RemoteControlMode.Trackpad, false, {}, zoom, x, y, { z, px, py -> zoom = z; x = px; y = py }, {},
                inputSink = input, modifier = Modifier.size(200.dp, 400.dp))
        } }
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "phone-control-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class Sink : NativeRemoteInputSink {
        val keys = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val texts = CopyOnWriteArrayList<String>()
        val mouse = CopyOnWriteArrayList<String>()
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean { keys += remoteKeyCode to down; return true }
        override fun sendUnicodeText(text: String): Int { texts += text; return text.length * 2 }
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean): Boolean {
            assertEquals(MouseButton.Left, button); mouse += if (down) "down" else "up"; return true
        }
        override fun sendMouseMove(x: Int, y: Int): Boolean { mouse += "move"; return true }
        override fun sendCtrlAltDel() = error("computer shortcut on phone")
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = error("computer click on phone")
        override fun sendMouseDoubleClick(x: Int, y: Int) = error("computer double click on phone")
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = error("computer scroll on phone")
    }
}
