package ru.papajon.meshcentralmobile.ui.screens

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateIssuerExplanation
import ru.papajon.meshcentralmobile.mesh.tls.formatFingerprint
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File

/** Uses real app locales/font configuration: a Dialog has its own Android window/context. */
@SdkSuppress(minSdkVersion = 33)
class ServerCertificateGalleryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun certificateDialogsAcrossLocalesAndThemes() {
        if (Build.VERSION.SDK_INT < 33) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val locales = app.getSystemService(LocaleManager::class.java)
        val oldLocales = locales.applicationLocales
        val oldResourceLocales = app.resources.configuration.locales
        val oldFontScale = Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, 1f)
        val failure = ServerCertificateFailure(ServerCertificateFailureKind.Untrusted,
            "0123456789abcdef".repeat(4), "CN=mesh.example, O=Example", 1_760_000_000_000, 1_900_000_000_000, "https://mesh.example/", "ab".repeat(32))
        val previousPin = "cd".repeat(32)
        data class Preview(val tag: String, val dark: Boolean, val changed: Boolean, val fontScale: Float = 1f)
        fun setFontScale(value: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $value").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        try {
            for (choice in listOf(Preview("ru", false, false), Preview("en", true, true),
                Preview("zh-Hans", false, false), Preview("ar", true, true), Preview("ru", true, true, 1.4f))) {
                locales.applicationLocales = LocaleList.forLanguageTags(choice.tag)
                setFontScale(choice.fontScale)
                compose.waitUntil(10_000) {
                    app.resources.configuration.locales[0].language == choice.tag.substringBefore('-') &&
                        kotlin.math.abs(app.resources.configuration.fontScale - choice.fontScale) < 0.05f
                }
                instrumentation.waitForIdleSync()
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    var title = ""
                    var button = ""
                    scenario.onActivity { activity ->
                        title = activity.getString(if (choice.changed) R.string.tls_replace_title else R.string.tls_untrusted_title)
                        button = activity.getString(if (choice.changed) R.string.tls_confirm_replace else R.string.tls_trust)
                        check(activity.resources.configuration.locales[0].language == choice.tag.substringBefore('-'))
                        check(kotlin.math.abs(activity.resources.configuration.fontScale - choice.fontScale) < 0.05f)
                        activity.setContent {
                            MeshCentralMobileTheme(darkTheme = choice.dark) {
                                Surface(color = MaterialTheme.colorScheme.background) {
                                    Box(Modifier.fillMaxSize()) {
                                        LoginContent(clientState = MeshClientState(), serverUrl = failure.serverAddress)
                                        ServerCertificateDialog(
                                            failure.copy(
                                                kind = if (choice.changed) ServerCertificateFailureKind.Changed else ServerCertificateFailureKind.Untrusted,
                                                previousSpkiSha256 = if (choice.changed) previousPin else null,
                                                hostnameMismatch = true,
                                                issuerExplanation = if (choice.changed) ServerCertificateIssuerExplanation.UnknownAuthority
                                                    else ServerCertificateIssuerExplanation.MeshCentralRoot,
                                            ),
                                            choice.changed, {}, {}, {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                    compose.waitForIdle()
                    compose.onNodeWithText(title).assertIsDisplayed()
                    compose.onNodeWithText(button).assertIsDisplayed()
                    fun capture(suffix: String = "") {
                        compose.waitForIdle()
                        android.os.SystemClock.sleep(250)
                        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        File(app.cacheDir, "tls-${choice.tag}-${choice.fontScale}$suffix.png").outputStream().use {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                    }
                    capture()
                    if (choice.changed) {
                        compose.onNodeWithText(formatFingerprint(previousPin)).performScrollTo().assertIsDisplayed()
                        capture("-previous-spki")
                    }
                    compose.onNodeWithText(formatFingerprint(failure.spkiSha256)).performScrollTo().assertIsDisplayed()
                    capture("-spki")
                    compose.onNodeWithText(formatFingerprint(failure.certificateSha256)).performScrollTo().assertIsDisplayed()
                    capture("-certificate")
                    compose.onNodeWithText(app.getString(R.string.tls_openssl_command)).performScrollTo().assertIsDisplayed()
                    capture("-instructions")
                }
            }
        } finally {
            locales.applicationLocales = oldLocales
            setFontScale(oldFontScale)
            // Settings writes finish before the configuration reaches the application. Do not
            // let that late update recreate the next test's Activity after its setContent.
            compose.waitUntil(10_000) {
                app.resources.configuration.locales == oldResourceLocales &&
                    kotlin.math.abs(app.resources.configuration.fontScale - oldFontScale) < 0.05f
            }
            instrumentation.waitForIdleSync()
        }
    }
}
