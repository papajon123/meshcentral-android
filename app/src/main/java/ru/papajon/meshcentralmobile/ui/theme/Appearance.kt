package ru.papajon.meshcentralmobile.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Appearance shares the settings transaction; each ordinary edit still changes only its own key. */
enum class MeshThemeMode { System, Light, Dark }

@Immutable
data class MeshAppearance(val mode: MeshThemeMode = MeshThemeMode.System, val dynamicColor: Boolean = false)

internal class AppearancePreferences(context: Context, name: String = "app_settings") {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    init {
        if (name == "app_settings" && !preferences.getBoolean("appearance_migrated", false)) {
            val legacy = context.applicationContext.getSharedPreferences("mesh_appearance", Context.MODE_PRIVATE)
            val editor = preferences.edit().putBoolean("appearance_migrated", true)
            if (!preferences.contains("theme_mode")) editor.putString("theme_mode", legacy.getString("theme_mode", "System"))
            if (!preferences.contains("dynamic_color")) editor.putBoolean("dynamic_color", legacy.getBoolean("dynamic_color", false))
            editor.apply()
        }
    }
    fun read() = MeshAppearance(
        mode = runCatching { MeshThemeMode.valueOf(preferences.getString("theme_mode", "System")!!) }.getOrDefault(MeshThemeMode.System),
        dynamicColor = preferences.getBoolean("dynamic_color", false),
    )
    fun setMode(mode: MeshThemeMode) { preferences.edit().putString("theme_mode", mode.name).apply() }
    fun setDynamicColor(enabled: Boolean) { preferences.edit().putBoolean("dynamic_color", enabled).apply() }
}

internal data class MeshAppearanceControls(
    val appearance: MeshAppearance,
    val setMode: (MeshThemeMode) -> Unit,
    val setDynamicColor: (Boolean) -> Unit,
)
internal val LocalMeshAppearance = staticCompositionLocalOf<MeshAppearanceControls?> { null }
internal val LocalMeshDarkTheme = staticCompositionLocalOf { true }
