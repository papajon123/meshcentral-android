package ru.papajon.meshcentralmobile.backup

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File

class BackupLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun narrowFormsKeepActionsAccessibleAcrossThemesLocalesAndLargeFont() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context, "backup-layout")
        val cases = listOf(Triple("en", false, 1f), Triple("ru", true, 1f), Triple("ar", false, 1.6f), Triple("zh-Hans", true, 1f))
        val current = mutableStateOf(cases.first())
        compose.setContent {
            val (tag, dark, font) = current.value
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)); fontScale = font }
            val localized = context.createConfigurationContext(config)
            key(tag) {
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity,
                    LocalDensity provides Density(context.resources.displayMetrics.density, font),
                    LocalLayoutDirection provides if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MeshCentralMobileTheme(darkTheme = dark) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) { BackupScreen(settings, {}, {}) }
                    }
                }
            }
        }
        cases.forEach { case ->
            compose.runOnIdle { current.value = case }
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(case.first)) }
            val localized = context.createConfigurationContext(config)
            compose.onNodeWithText(localized.getString(R.string.backup_export)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.backup_import)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.backup_title)).performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(200) // Let system-bar appearance catch up with theme changes.
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
            File(context.filesDir, "integration-backup-${case.first}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        context.getSharedPreferences("backup-layout", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }
}
