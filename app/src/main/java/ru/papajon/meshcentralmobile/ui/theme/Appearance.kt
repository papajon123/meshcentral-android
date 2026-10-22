package ru.papajon.meshcentralmobile.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Separate storage: changing visual preferences never rewrites server, PIN or session data. */
enum class MeshThemeMode { System, Light, Dark }

@Immutable
data class MeshAppearance(val mode: MeshThemeMode = MeshThemeMode.System, val dynamicColor: Boolean = false)

internal class AppearancePreferences(context: Context, name: String = "mesh_appearance") {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
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
