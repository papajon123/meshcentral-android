package ru.papajon.meshcentralmobile.ui.screens.session

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.*
import ru.papajon.meshcentralmobile.ui.screens.SessionDock
import ru.papajon.meshcentralmobile.ui.theme.*
import java.io.File

/** Actual production controls over a synthetic canvas; no remote connection or account is used. */
class SessionToolbarGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun capturePhoneControlsInThemesAndLargeText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sessionGallery") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("ru"))
        })
        val state = mutableStateOf(RemoteDesktopState(device = MeshDevice("fixture", "group", "WORKSTATION-01", agentId = 3),
            connectionState = ConnectionState.Connected, handshakeComplete = true,
            framebuffer = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888), remoteWidth = 1920, remoteHeight = 1080,
            displays = listOf(65535,1,2), selectedDisplay = 1, framesPerSecond = 19.5f, bytesPerSecond = 254_700))
        val dark = mutableStateOf(false)
        val font = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, font.value)) {
                MeshCentralMobileTheme(darkTheme = dark.value) {
                    Box(Modifier.fillMaxSize().background(MeshTheme.colors.canvas).safeDrawingPadding()) {
                        Text("1920 × 1080", color = MeshTheme.colors.online, modifier = Modifier.align(Alignment.Center))
                        Box(Modifier.align(Alignment.TopCenter)) {
                            SessionTopBar(state.value, {}, { state.value = state.value.copy(selectedDisplay = it) }, {}, {}, {}, onMinimize = {})
                        }
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            SessionDock(state.value, RemoteControlMode.Trackpad, false, false, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        fun shot(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(250)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.cacheDir,"session-toolbar-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        shot("light")
        compose.runOnIdle { dark.value = true }
        shot("dark")
        compose.runOnIdle { font.value = 1.8f }
        shot("large")
        compose.runOnIdle { font.value = 1f; state.value = state.value.copy(connectionState = ConnectionState.Reconnecting) }
        shot("connecting")
        compose.runOnIdle { state.value = state.value.copy(connectionState = ConnectionState.Failed, message = UiText.literal("Соединение с компьютером прервано. Повторите подключение.")) }
        compose.onNodeWithTag("session-connection-status").performClick()
        shot("problem")
    }
}
