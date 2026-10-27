package ru.papajon.meshcentralmobile.forward

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.screens.DeviceScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class PortForwardVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun englishActiveRules() = capture("en", false, 1f, false)
    @Test fun russianDarkLargeText() = capture("ru", true, 1.35f, false)
    @Test fun arabicRuleForm() = capture("ar", false, 1f, true)
    private fun capture(language: String, dark: Boolean, scale: Float, dialog: Boolean) {
        val context = compose.activity
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(config)
        val store = PortForwardStore(context)
        val manager = PortForwardManager({ true }, { _, _, _, _ -> error("No client in visual fixture") })
        val rule = PortForwardRule(id = "visual-$language", profileId = "visual", nodeId = "node//visual", name = "Office — Remote Desktop", kind = ForwardKind.RDP, remotePort = 3389)
        store.save(rule)
        try {
            manager.start(rule)
            compose.setContent {
                CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides localized,
                    LocalDensity provides Density(LocalDensity.current.density, scale),
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MeshCentralMobileTheme(darkTheme = dark) {
                        Box(Modifier.safeDrawingPadding()) {
                        DeviceScreen(MeshDevice("node//visual", "mesh//visual", "Office PC", conn = 1, rights = 8, mtype = 2),
                            MeshServerCapabilities(), true, DeviceTab.PortForwarding, {}, {}, {}) {
                            PortForwardScreen("visual", "node//visual", manager, store, { manager.start(it) }, {})
                        }
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (dialog) compose.onNodeWithTag(PortForwardTestTags.Add).performClick()
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val folder = File(context.getExternalFilesDir(null), "forward-evidence").apply { mkdirs() }
            File(folder, "$language.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } finally { manager.close(); store.delete(rule.id) }
    }
}
