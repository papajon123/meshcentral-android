package ru.papajon.meshcentralmobile.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import ru.papajon.meshcentralmobile.R

/**
 * App-specific language, kept in one place for every entry point.
 *
 * Android 13 and newer persist the selection in [LocaleManager], which is also the source behind
 * the system "App language" screen. Older releases store only one validated tag and apply it in
 * `attachBaseContext`; clearing that tag therefore falls back to the untouched system context
 * instead of remembering the previous override.
 */
object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val PREFERENCE_TAG = "tag"
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    @Volatile
    private var cachedTags: List<String>? = null

    /** Tags declared in `locales_config.xml`, in declaration order. */
    fun supportedTags(context: Context): List<String> {
        cachedTags?.let { return it }
        return synchronized(this) {
            cachedTags ?: readConfiguredTags(context.applicationContext ?: context).also { cachedTags = it }
        }
    }

    /**
     * The selected supported tag, or an empty string when the system language is in effect.
     *
     * Platform locale lists are resolved to the nearest declared tag; a corrupt legacy preference
     * is treated as the system default instead of being shown as a selectable language.
     */
    fun currentTag(context: Context): String {
        val supported = supportedTags(context)
        val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationLocales(context)
        } else {
            savedTag(context)?.takeIf { it in supported }?.let(::listOf).orEmpty()
        }
        for (tag in selected) {
            matchSupportedTag(tag, supported)?.let { return it }
        }
        return ""
    }

    /**
     * Adds the saved legacy locale to the base context. On API 33+ the framework applies
     * [LocaleManager] itself, so the base context is already correct.
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return context
            val locales = manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
            if (context.resources.configuration.locales == locales) return context
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(locales)
            return context.createConfigurationContext(configuration)
        }
        val tag = savedTag(context) ?: return context
        if (tag !in supportedTags(context)) return context
        val configuration = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(tag)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    /**
     * Applies a supported tag. An empty tag resets to the system language. Unsupported tags are
     * ignored without touching the stored selection or the Activity.
     */
    fun apply(activity: Activity, tag: String) {
        val supported = supportedTags(activity)
        if (tag.isNotEmpty() && tag !in supported) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyPlatform(activity, tag)
            return
        }

        val saved = savedTag(activity)
        if (tag.isEmpty() && saved == null) return
        if (saved == tag) return
        val editor = preferences(activity).edit()
        if (tag.isEmpty()) {
            editor.remove(PREFERENCE_TAG)
        } else {
            editor.putString(PREFERENCE_TAG, tag)
        }
        editor.apply()
        activity.recreate()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyPlatform(activity: Activity, tag: String) {
        val manager = activity.getSystemService(LocaleManager::class.java) ?: return
        val next = if (tag.isEmpty()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tag)
        }
        if (manager.applicationLocales.toLanguageTags() == next.toLanguageTags()) return
        manager.applicationLocales = next
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applicationLocales(context: Context): List<String> {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return emptyList()
        val locales = manager.applicationLocales
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }

    private fun savedTag(context: Context): String? =
        preferences(context).getString(PREFERENCE_TAG, null)

    private fun preferences(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun readConfiguredTags(context: Context): List<String> {
        val tags = LinkedHashSet<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(tags::add)
                }
                parser.next()
            }
        }
        return tags.toList()
    }

    /**
     * Maps a platform locale such as `en-US` or `zh-CN` to its declared tag without changing the
     * tag that is stored or written back.
     */
    private fun matchSupportedTag(tag: String, supported: List<String>): String? {
        if (tag.isBlank()) return null
        supported.firstOrNull { it == tag }?.let { return it }

        val locale = Locale.forLanguageTag(tag)
        val language = canonicalLanguage(locale.language)
        if (language.isEmpty()) return null

        supported.firstOrNull {
            val candidate = Locale.forLanguageTag(it)
            canonicalLanguage(candidate.language) == language && candidate.script == locale.script
        }?.let { return it }

        if (language == "zh") {
            val script = if (locale.country == "TW" || locale.country == "HK" || locale.country == "MO") {
                "Hant"
            } else {
                "Hans"
            }
            supported.firstOrNull {
                val candidate = Locale.forLanguageTag(it)
                canonicalLanguage(candidate.language) == "zh" && candidate.script == script
            }?.let { return it }
        }

        return supported.firstOrNull { canonicalLanguage(Locale.forLanguageTag(it).language) == language }
    }

    private fun canonicalLanguage(language: String): String = when (language) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> language
    }
}
