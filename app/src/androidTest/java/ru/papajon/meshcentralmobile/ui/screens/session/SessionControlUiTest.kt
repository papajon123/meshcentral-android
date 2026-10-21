package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class SessionControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun ctrlAChipSendsSelectAllWithoutEnablingTheCtrlToggle() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            MeshCentralMobileTheme {
                KeyboardSheet(input = recordingSink(keys), onClose = {})
            }
        }

        compose.onNodeWithText("Ctrl+A").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(17 to true, 65 to true, 65 to false, 17 to false), keys)
        }
    }

    @Test fun scrollModeIsSelectableFromThePointerSheet() {
        var selected: RemoteControlMode? = null
        compose.setContent {
            MeshCentralMobileTheme {
                MouseModeSheet(RemoteControlMode.DirectTouch, { selected = it }, {}, {}, {})
            }
        }

        compose.onNodeWithText("Прокрутка").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(RemoteControlMode.Scroll, selected) }
    }

    private fun recordingSink(keys: MutableList<Pair<Int, Boolean>>) = object : NativeRemoteInputSink {
        override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean): Boolean {
            keys += remoteKeyCode to down
            return true
        }
        override fun sendUnicodeText(text: String) = 0
        override fun sendCtrlAltDel() = false
        override fun sendMouseMove(x: Int, y: Int) = false
        override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = false
        override fun sendMouseClick(x: Int, y: Int, button: MouseButton) = false
        override fun sendMouseDoubleClick(x: Int, y: Int) = false
        override fun sendMouseScroll(x: Int, y: Int, delta: Float) = false
    }
}
