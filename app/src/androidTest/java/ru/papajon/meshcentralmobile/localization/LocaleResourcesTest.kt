package ru.papajon.meshcentralmobile.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R

class LocaleResourcesTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun systemLocaleSelectionAndEnglishFallbackResolveAppResources() {
        assertEquals("Cancel", localized("en").getString(R.string.cancel))
        assertEquals("Отмена", localized("ru-RU").getString(R.string.cancel))
        assertEquals("取消", localized("zh-CN").getString(R.string.cancel))
        assertEquals("取消", localized("zh-TW").getString(R.string.cancel))
        assertEquals("Cancel", localized("nl-NL").getString(R.string.cancel))
        // A WebView loaded earlier in this process adds its own locales (Dutch included) to the app's
        // assets, so the unsupported first locale must be one WebView does not ship either.
        assertEquals("Отмена", localized("fy-NL,ru-RU").getString(R.string.cancel))
    }

    @Test fun chineseScriptsAndRtlLocalesHaveDistinctResourcesAndDirection() {
        val simplified = localized("zh-CN").getString(R.string.common_nav_settings)
        val traditional = localized("zh-TW").getString(R.string.common_nav_settings)
        assertTrue(simplified.contains("设"))
        assertTrue(traditional.contains("設"))
        for (tag in listOf("ar", "fa", "ur")) {
            val context = localized(tag)
            assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
            assertNotEquals("Cancel", context.getString(R.string.cancel))
            assertNotEquals("Отмена", context.getString(R.string.cancel))
        }
    }

    @Test fun everyDeclaredLocaleHasFormattedMessagesWithoutExposingResourceObjects() {
        val tags = mutableListOf<String>()
        app.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                    tags += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                }
                parser.next()
            }
        }
        assertEquals(23, tags.size)
        for (tag in tags) {
            val context = localized(tag)
            assertTrue(context.getString(R.string.server_save).isNotBlank())
            val text = context.getString(R.string.devices_subtitle_counts, 1, 2)
            val numbers = java.text.NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
            assertTrue("Online count in $tag", text.contains(numbers.format(1)))
            assertTrue("Total count in $tag", text.contains(numbers.format(2)))
            assertFalse(text.contains("demo.example"))
            assertFalse(text.contains("%1\$"))
            assertFalse(text.contains("%2\$"))
            val status = UiText.resource(R.string.runtime_opening_device, "Demo computer").asString(context)
            assertTrue(status.contains("Demo computer"))
            assertFalse(status.contains("Resource(id="))
        }
    }

    @Test fun pendingRuntimeMessageIsResolvedForTheCurrentLocale() {
        val message = UiText.resource(R.string.runtime_connecting_server)
        val english = message.asString(localized("en"))
        val russian = message.asString(localized("ru"))
        val chinese = message.asString(localized("zh-CN"))
        assertNotEquals(english, russian)
        assertNotEquals(english, chinese)
        assertEquals("demo.example", UiText.literal("demo.example").asString(localized("ar")))
    }

    private fun localized(tags: String): Context {
        val locales = LocaleList.forLanguageTags(tags)
        val config = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0] ?: Locale.ENGLISH)
        }
        return app.createConfigurationContext(config)
    }
}
