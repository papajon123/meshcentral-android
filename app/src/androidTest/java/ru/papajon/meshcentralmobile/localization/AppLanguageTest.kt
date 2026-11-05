package ru.papajon.meshcentralmobile.localization

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.components.AppLanguagePicker
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Language selection is verified through the public contract only: the configured tag list, the
 * persisted selection, the context wrapper and the platform LocaleManager. Applying a different
 * language recreates the Activity, so this suite opens a disposable activity for each write and
 * never asserts against its composition.
 */
class AppLanguageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = instrumentation.targetContext
    private val preferences get() = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var originalLocales: LocaleList = LocaleList.getEmptyLocaleList()
    private var originalSavedTag: String? = null

    @Before fun rememberSelection() {
        originalLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformLocales()
        } else {
            LocaleList.getEmptyLocaleList()
        }
        originalSavedTag = preferences.getString(PREFERENCE_TAG, null)
    }

    @After fun restoreSelection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setPlatformLocales(originalLocales)
            // LocaleManager updates its stored selection before the configuration reaches this
            // process. Do not let that late change recreate the next test's Compose Activity.
            val expectedLanguage = (if (originalLocales.isEmpty)
                android.content.res.Resources.getSystem().configuration.locales else originalLocales)[0].language
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (app.applicationContext.resources.configuration.locales[0].language != expectedLanguage &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals(expectedLanguage, app.applicationContext.resources.configuration.locales[0].language)
            instrumentation.waitForIdleSync()
        } else {
            val editor = preferences.edit()
            if (originalSavedTag == null) {
                editor.remove(PREFERENCE_TAG)
            } else {
                editor.putString(PREFERENCE_TAG, originalSavedTag)
            }
            editor.commit()
        }
    }

    @Test fun supportedTagsComeFromLocaleConfigAndKeepTheDeclaredChineseVariants() {
        val tags = AppLanguage.supportedTags(app)
        assertEquals(readConfiguredTags(), tags)
        assertEquals(23, tags.size)
        assertEquals(tags.size, tags.toSet().size)
        for (tag in listOf("en", "ru", "de", "zh-Hans", "zh-Hant")) {
            assertTrue("Missing declared tag $tag", tag in tags)
        }
        assertFalse("zh alone is not a declared app locale", "zh" in tags)
        assertFalse("zh-CN is not a declared app locale", "zh-CN" in tags)
        assertFalse("en-US is not a declared app locale", "en-US" in tags)
    }

    @Test fun unsupportedTagsAreRejectedWithoutChangingTheSelection() {
        apply("")
        awaitCurrentTag("")

        val before = rawSelection()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (tag in listOf("en-US", "zh", "zh-CN", "not-a-language", "EN", " en")) {
                    AppLanguage.apply(activity, tag)
                }
            }
        }
        instrumentation.waitForIdleSync()

        assertEquals("", AppLanguage.currentTag(app))
        assertEquals(before, rawSelection())
    }

    @Test fun supportedTagIsAppliedRereadAndEmptyTagClearsTheOverride() {
        apply("")
        awaitCurrentTag("")

        val override = if (app.resources.configuration.locales[0].language == "de") "ru" else "de"
        apply(override)
        awaitCurrentTag(override)
        awaitResourceLanguage(override)
        assertEquals(
            override,
            AppLanguage.currentTag(app.createConfigurationContext(app.resources.configuration)),
        )

        apply("")
        awaitCurrentTag("")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            assertSame("An empty tag must fall back to the untouched base context", app, AppLanguage.wrap(app))
            assertFalse(preferences.contains(PREFERENCE_TAG))
        } else {
            assertTrue(platformLocales().isEmpty)
        }
    }

    @Test fun legacyWrapAppliesTheSavedTagAndResetReturnsTheBaseContext() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        apply("")
        assertSame(app, AppLanguage.wrap(app))

        val override = if (app.resources.configuration.locales[0].language == "de") "ru" else "de"
        apply(override)
        awaitCurrentTag(override)
        val wrapped = AppLanguage.wrap(app)
        assertNotEquals("A saved tag must produce a configured context", app, wrapped)
        assertEquals(override, wrapped.resources.configuration.locales[0].language)

        apply("")
        awaitCurrentTag("")
        assertSame(app, AppLanguage.wrap(app))
    }

    @Test fun platformLocaleManagerTracksAndClearsTheSelection() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        apply("de")
        awaitCurrentTag("de")
        assertEquals("de", platformLocales().toLanguageTags())

        apply("")
        awaitCurrentTag("")
        assertTrue(platformLocales().isEmpty)
    }

    private fun apply(tag: String) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> AppLanguage.apply(activity, tag) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitCurrentTag(expected: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (AppLanguage.currentTag(app) == expected) return
            SystemClock.sleep(POLL_MS)
        }
        assertEquals(expected, AppLanguage.currentTag(app))
    }

    private fun awaitResourceLanguage(expected: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (AppLanguage.wrap(app).resources.configuration.locales[0].language == expected) return
            SystemClock.sleep(POLL_MS)
        }
        assertEquals(expected, AppLanguage.wrap(app).resources.configuration.locales[0].language)
    }

    private fun rawSelection(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        platformLocales().toLanguageTags()
    } else {
        preferences.getString(PREFERENCE_TAG, null).orEmpty()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformLocales(): LocaleList = requireNotNull(
        app.getSystemService(LocaleManager::class.java),
    ) { "LocaleManager is available on API 33+" }.applicationLocales

    // JUnit reflects method return types on API 26 too; keep API-33 classes out of signatures.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setPlatformLocales(value: LocaleList) {
        requireNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales = value
    }

    private fun readConfiguredTags(): List<String> {
        val tags = mutableListOf<String>()
        app.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(tags::add)
                }
                parser.next()
            }
        }
        return tags
    }

    private companion object {
        const val PREFERENCES = "app_language"
        const val PREFERENCE_TAG = "tag"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}

/**
 * The dialog itself is safe to exercise because this test only dismisses it. Choosing a language
 * writes the real app locale and needs a dedicated Activity-recreation test for API 26-32.
 */
class AppLanguagePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun thePickerListsTheSystemOptionAndEveryConfiguredTagAndBackKeepsTheChoice() {
        val context = compose.activity
        val before = AppLanguage.currentTag(context)
        compose.setContent {
            MeshCentralMobileTheme {
                AppLanguagePicker()
            }
        }

        compose.onNodeWithTag("app-language-picker").assertIsDisplayed().performClick()
        compose.onNodeWithTag("app-language-option-system").assertIsDisplayed()
        for (tag in AppLanguage.supportedTags(context)) {
            compose.onNodeWithTag("app-language-option-$tag").assertExists()
        }

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("app-language-option-system").assertDoesNotExist()
        compose.onNodeWithTag("app-language-picker").assertIsDisplayed()
        assertEquals(before, AppLanguage.currentTag(context))
    }
}
