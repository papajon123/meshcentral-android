package ru.papajon.meshcentralmobile.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/** Original call shape retained for every screen/test; preferences default to the system theme. */
@Composable
fun MeshCentralMobileTheme(content: @Composable () -> Unit) = MeshThemeContent(null, content)

/** Explicit mode for deterministic component previews and screenshot coverage. */
@Composable
fun MeshCentralMobileTheme(darkTheme: Boolean, content: @Composable () -> Unit) = MeshThemeContent(darkTheme, content)

@Composable
private fun MeshThemeContent(darkOverride: Boolean?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { AppearancePreferences(context) }
    var appearance by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> appearance = store.read() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = darkOverride ?: when (appearance.mode) {
        MeshThemeMode.System -> systemDark
        MeshThemeMode.Light -> false
        MeshThemeMode.Dark -> true
    }
    val brand = if (dark) MeshDarkScheme else MeshLightScheme
    val colors = if (appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val system = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Personalize neutral/supporting surfaces, keep the action identity and safety pairs stable.
        system.copy(
            primary = brand.primary, onPrimary = brand.onPrimary,
            primaryContainer = brand.primaryContainer, onPrimaryContainer = brand.onPrimaryContainer,
            inversePrimary = brand.inversePrimary, surfaceTint = brand.surfaceTint,
            tertiary = brand.tertiary, onTertiary = brand.onTertiary,
            tertiaryContainer = brand.tertiaryContainer, onTertiaryContainer = brand.onTertiaryContainer,
            error = brand.error, onError = brand.onError,
            errorContainer = brand.errorContainer, onErrorContainer = brand.onErrorContainer,
        )
    } else brand
    val reduced = rememberReducedMotion()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.let { activity ->
            WindowInsetsControllerCompat(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalMeshColors provides meshSemanticColors(dark),
        LocalMeshMotion provides remember(reduced) { MeshMotion(reducedMotion = reduced) },
        LocalMeshSpacing provides MeshSpacing(),
        LocalMeshDarkTheme provides dark,
        LocalMeshAppearance provides MeshAppearanceControls(appearance, store::setMode, store::setDynamicColor),
    ) {
        MaterialTheme(colorScheme = colors, typography = MeshTypography, shapes = MeshShapes) {
            Surface(color = colors.background, contentColor = colors.onBackground, content = content)
        }
    }
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        if (current.baseContext === current) break
        current = current.baseContext
    }
    return current as? Activity
}

object MeshTheme {
    val colors: MeshColors @Composable get() = LocalMeshColors.current
    val motion: MeshMotion @Composable get() = LocalMeshMotion.current
    val spacing: MeshSpacing @Composable get() = LocalMeshSpacing.current
    val isDark: Boolean @Composable get() = LocalMeshDarkTheme.current
}
