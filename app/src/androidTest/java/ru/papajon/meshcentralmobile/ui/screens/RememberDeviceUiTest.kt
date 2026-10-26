package ru.papajon.meshcentralmobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.TwoFactorChallenge
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class RememberDeviceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun russianDayCountsUseAndroidPluralRules() {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("ru"))
        }
        val resources = compose.activity.createConfigurationContext(configuration).resources
        val forms = mapOf(1 to "день", 2 to "дня", 5 to "дней", 11 to "дней", 21 to "день",
            22 to "дня", 25 to "дней", 101 to "день")
        for ((days, form) in forms) assertEquals("Запомнить это устройство на $days $form",
            resources.getQuantityString(R.plurals.remember_device_for_days, days, days))
    }

    @Test fun checkboxDefaultsOnUsesServerDaysAndResetsWhenServerChanges() {
        val server = mutableStateOf("https://first.example.test/")
        var submitted: Pair<String, Boolean>? = null
        compose.setContent { MeshCentralMobileTheme {
            LoginContent(MeshClientState(twoFactorChallenge = TwoFactorChallenge(twoFactorCookieDays = 7)),
                server.value, onSubmitCode = { code, remember -> submitted = code to remember })
        } }
        compose.onNodeWithTag("remember-device-checkbox").performScrollTo().assertIsOn()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.remember_device_for_days, 7, 7)).assertIsDisplayed()
        compose.onNodeWithTag("remember-device-checkbox").performClick().assertIsOff()
        compose.onNodeWithText(compose.activity.getString(R.string.login_two_factor_code)).performTextInput("123456")
        compose.onNodeWithText(compose.activity.getString(R.string.login_action_sign_in_with_code)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("123456" to false, submitted); server.value = "https://second.example.test/" }
        compose.onNodeWithTag("remember-device-checkbox").performScrollTo().assertIsOn()
    }

    @Test fun serverDisabledRememberingHidesTheCheckbox() {
        compose.setContent { MeshCentralMobileTheme {
            LoginContent(MeshClientState(twoFactorChallenge = TwoFactorChallenge(twoFactorCookieDays = 0)), "https://server.example.test/")
        } }
        compose.onNodeWithTag("remember-device-checkbox").assertDoesNotExist()
    }

    @Test fun settingsShowsExpiryThenUpdatesAfterForget() {
        val expiry = mutableStateOf<Long?>(System.currentTimeMillis() + 86_400_000L)
        compose.setContent { MeshCentralMobileTheme {
            ServerSettingsScreen(AppSettings(serverUrl = "https://server.example.test/"), {}, {}, {},
                rememberedDeviceExpiresAt = expiry.value, onForgetRememberedDevice = { expiry.value = null })
        } }
        compose.onNodeWithTag("forget-remembered-device").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("forget-remembered-device").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.remember_device_not_remembered)).performScrollTo().assertIsDisplayed()
    }

    @Test fun codeStepAndSettingsRemainReadableAcrossLocalesAndThemes() {
        data class Frame(val locale: String = "en", val dark: Boolean = false, val settings: Boolean = false)
        val frame = mutableStateOf(Frame())
        compose.setContent {
            val current = frame.value
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList.forLanguageTags(current.locale))
                fontScale = 1.3f
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f),
                LocalLayoutDirection provides if (current.locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MeshCentralMobileTheme(darkTheme = current.dark) {
                    if (current.settings) ServerSettingsScreen(
                        AppSettings(serverUrl = "https://server.example.test/"), {}, {}, {},
                        modifier = Modifier.widthIn(max = 360.dp),
                        rememberedDeviceExpiresAt = 1_900_000_000_000L,
                    ) else LoginContent(
                        MeshClientState(twoFactorChallenge = TwoFactorChallenge(twoFactorCookieDays = 30)),
                        "https://server.example.test/", modifier = Modifier.widthIn(max = 360.dp),
                    )
                }
            }
        }
        for (locale in listOf("en", "ru", "zh-Hans", "ar")) for (dark in listOf(false, true)) {
            for (settings in listOf(false, true)) {
                compose.runOnIdle { frame.value = Frame(locale, dark, settings) }
                val tag = if (settings) "forget-remembered-device" else "remember-device-checkbox"
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                if (!settings) compose.onNodeWithTag(tag).assertIsOn()
                snapshot("${locale}-${if (dark) "dark" else "light"}-${if (settings) "settings" else "code"}.png")
            }
        }
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "remember-device-ui").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
