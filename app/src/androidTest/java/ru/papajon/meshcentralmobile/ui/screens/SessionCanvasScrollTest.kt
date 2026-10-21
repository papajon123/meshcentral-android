package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink
import ru.papajon.meshcentralmobile.mesh.MouseButton
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.RemoteControlMode
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class SessionCanvasScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oneFingerSwipesScrollBothDirectionsWithoutClicking() {
        val wheel = mutableListOf<Float>()
        var clicks = 0
        val input = object : NativeRemoteInputSink {
            override fun sendRemoteKey(remoteKeyCode: Int, down: Boolean, extended: Boolean) = false
            override fun sendUnicodeText(text: String) = 0
            override fun sendCtrlAltDel() = false
            override fun sendMouseMove(x: Int, y: Int) = false
            override fun sendMouseButton(x: Int, y: Int, button: MouseButton, down: Boolean) = false
            override fun sendMouseClick(x: Int, y: Int, button: MouseButton): Boolean { clicks++; return true }
            override fun sendMouseDoubleClick(x: Int, y: Int) = false
            override fun sendMouseScroll(x: Int, y: Int, delta: Float): Boolean { wheel += delta; return true }
        }
        val frame = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MeshCentralMobileTheme {
                SessionCanvas(
                    desktopState = RemoteDesktopState(
                        connectionState = ConnectionState.Connected,
                        framebuffer = frame,
                        remoteWidth = 800,
                        remoteHeight = 600,
                        handshakeComplete = true,
                    ),
                    mode = RemoteControlMode.Scroll,
                    dragArmed = false,
                    onDragConsumed = {},
                    zoom = 1f,
                    panX = 0f,
                    panY = 0f,
                    onViewportChanged = { _, _, _ -> },
                    onCanvasSizeChanged = {},
                    inputSink = input,
                    modifier = Modifier.fillMaxSize().testTag("remote-canvas"),
                )
            }
        }

        compose.onNodeWithTag("remote-canvas").performTouchInput { swipeUp() }
        compose.onNodeWithTag("remote-canvas").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertTrue("Swipe up must scroll the remote page", wheel.any { it > 0f })
            assertTrue("Swipe down must reverse the remote wheel", wheel.any { it < 0f })
            assertEquals(0, clicks)
        }
    }
}
