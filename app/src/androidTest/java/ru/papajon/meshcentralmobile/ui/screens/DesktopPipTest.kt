package ru.papajon.meshcentralmobile.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipHarnessActivity
import ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipPreview

class DesktopPipTest {
    @get:Rule val compose = createAndroidComposeRule<DesktopPipHarnessActivity>()

    @Test fun realPipHasOnlyPassivePreviewAndRestoresSessionState() = exercisePip(automatic = false)

    @Test fun homeAutomaticallyEntersPassivePipAndRestoresSessionState() = exercisePip(automatic = true)

    private fun exercisePip(automatic: Boolean) {
        val frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff116655.toInt()) }
        val state = RemoteDesktopState(framebuffer = frame, remoteWidth = 640, remoteHeight = 480)
        compose.setContent {
            var retained by remember { mutableStateOf("session-kept") }
            if (compose.activity.pip.active) DesktopPipPreview(state, Modifier.fillMaxSize())
            else Text(retained)
        }
        compose.onNodeWithText("session-kept").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(500)
        compose.runOnUiThread {
            assertTrue(compose.activity.pip.supported)
            compose.activity.pip.configure(true, automatic, 640, 480)
            if (!automatic) assertTrue(compose.activity.pip.enter())
        }
        if (automatic) android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
        ).use { it.readBytes() }
        compose.waitUntil(8000) { compose.activity.isInPictureInPictureMode && compose.activity.pip.active }
        // Paused roots are not driven by the test rule's idle loop; explicitly run their frames.
        compose.mainClock.advanceTimeBy(1000)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(1000)
        // ComposeTestRule only indexes resumed roots. PiP is PAUSED: inspect actual pixels here,
        // then use semantics again after returning to RESUMED.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue("PiP host must track the active platform mode", compose.activity.pip.active)
        var framePixels = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            if (bitmap.getPixel(x, y) and 0x00ffffff == 0x00116655) framePixels++
        }
        assertTrue("PiP must display the framebuffer, not the local controls: pixels=$framePixels", framePixels > 1000)
        java.io.File(compose.activity.getExternalFilesDir(null), if (automatic) "desktop-pip-auto.png" else "desktop-pip.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, DesktopPipHarnessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.waitUntil(8000) { !compose.activity.isInPictureInPictureMode }
        compose.onNodeWithText("session-kept").assertExists()
        compose.onNodeWithTag("desktop_pip_preview").assertDoesNotExist()
    }

    @Test fun ineligibleDesktopDoesNotEnterPip() {
        compose.runOnUiThread {
            compose.activity.pip.configure(false)
            assertFalse(compose.activity.pip.enter())
            assertFalse(compose.activity.isInPictureInPictureMode)
        }
    }
}
