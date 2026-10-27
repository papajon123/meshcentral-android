package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.DesktopQuality
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.TwoFingerAction
import ru.papajon.meshcentralmobile.settings.DesktopPreferences
import ru.papajon.meshcentralmobile.ui.screens.session.SessionOptionsSheet
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DesktopOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun automaticPipDefaultsOffAndSurvivesRepositoryRecreation() {
        val context = compose.activity
        context.getSharedPreferences("desktop_display", android.content.Context.MODE_PRIVATE).edit().remove("pip.automatic").commit()
        try {
            assertFalse(DesktopPreferences(context).loadPipAutomatic())
            DesktopPreferences(context).savePipAutomatic(true)
            assertTrue(DesktopPreferences(context).loadPipAutomatic())
            DesktopPreferences(context).savePipAutomatic(false)
            assertFalse(DesktopPreferences(context).loadPipAutomatic())
        } finally { DesktopPreferences(context).savePipAutomatic(false) }
    }

    @Test fun qualityPresetScaleAndGestureChoicesRemainVisibleAndPersistForOnlyTheirDevice() {
        val prefs = DesktopPreferences(compose.activity)
        val profile = "fixture-${System.nanoTime()}"
        val frame = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        compose.setContent { MeshCentralMobileTheme {
            var quality by remember { mutableStateOf(prefs.load(profile, "one")) }
            var action by remember { mutableStateOf(TwoFingerAction.Default) }
            var pinch by remember { mutableStateOf(true) }
            SessionOptionsSheet(RemoteDesktopState(framebuffer = frame, remoteWidth = 400, remoteHeight = 400,
                quality = quality), false, 1f, {}, {}, {}, false, {},
                onQuality = { q, f -> quality = quality.copy(jpegQuality = q, frameRate = f); prefs.save(profile, "one", quality) },
                onReconnect = {}, onShowHelp = {}, onToggleFullscreen = {}, onHideChrome = {}, onClose = {},
                onScaling = { quality = quality.copy(scaling = it); prefs.save(profile, "one", quality) },
                twoFingerAction = action, onTwoFingerAction = { action = it },
                pinchEnabled = pinch, onPinchEnabled = { pinch = it })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.session_options_preset_saver)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.session_options_image_quality, 35)).assertExists()
        compose.onNodeWithTag("desktop_scale_512").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("desktop_two_finger_Pan").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("desktop_pinch").performScrollTo().performClick().assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(DesktopQuality(35, 12, 512), DesktopPreferences(compose.activity).load(profile, "one"))
            assertEquals(DesktopQuality(), prefs.load(profile, "two"))
            assertEquals(DesktopQuality(), prefs.load("another-$profile", "one"))
        }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "desktop-options.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
