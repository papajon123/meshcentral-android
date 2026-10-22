package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Signal: mineral surfaces and a precise teal action identity, in both daylight and low light. */
internal val MeshLightScheme = lightColorScheme(
    primary = Color(0xFF006A63), onPrimary = Color.White,
    primaryContainer = Color(0xFF9AF2E6), onPrimaryContainer = Color(0xFF00201D),
    inversePrimary = Color(0xFF70D9CF),
    secondary = Color(0xFF4B635D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DF), onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFF775A30), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0), onTertiaryContainer = Color(0xFF2A1800),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAF5), onBackground = Color(0xFF17201C),
    surface = Color(0xFFF5FAF5), onSurface = Color(0xFF17201C),
    surfaceVariant = Color(0xFFDBE5DE), onSurfaceVariant = Color(0xFF404A44),
    surfaceTint = Color(0xFF006A63),
    inverseSurface = Color(0xFF2C352F), inverseOnSurface = Color(0xFFEDF3ED),
    outline = Color(0xFF6F7973), outlineVariant = Color(0xFFBEC9C1), scrim = Color.Black,
    surfaceBright = Color(0xFFF5FAF5), surfaceDim = Color(0xFFD5DCD5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF5EF),
    surfaceContainer = Color(0xFFE9EFE9), surfaceContainerHigh = Color(0xFFE3E9E3),
    surfaceContainerHighest = Color(0xFFDDE3DD),
)

internal val MeshDarkScheme = darkColorScheme(
    primary = Color(0xFF70D9CF), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B), onPrimaryContainer = Color(0xFF95F5EA),
    inversePrimary = Color(0xFF006A63),
    secondary = Color(0xFFB3CBC5), onSecondary = Color(0xFF1F3530),
    secondaryContainer = Color(0xFF354B46), onSecondaryContainer = Color(0xFFCFE8E1),
    tertiary = Color(0xFFF0C48E), onTertiary = Color(0xFF452B08),
    tertiaryContainer = Color(0xFF5F411E), onTertiaryContainer = Color(0xFFFFDDB7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE5DF),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE5DF),
    surfaceVariant = Color(0xFF404A44), onSurfaceVariant = Color(0xFFBEC9C1),
    surfaceTint = Color(0xFF70D9CF),
    inverseSurface = Color(0xFFDDE5DF), inverseOnSurface = Color(0xFF2C352F),
    outline = Color(0xFF89958C), outlineVariant = Color(0xFF404A44), scrim = Color.Black,
    surfaceBright = Color(0xFF343D37), surfaceDim = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF09100D), surfaceContainerLow = Color(0xFF17201B),
    surfaceContainer = Color(0xFF1B241F), surfaceContainerHigh = Color(0xFF252E29),
    surfaceContainerHighest = Color(0xFF303A34),
)

/** Non-Material semantic roles. Status is always also named, never conveyed by hue alone. */
@Immutable
data class MeshColors(
    val online: Color = Color(0xFF80DBAD),
    val onlineContainer: Color = Color(0xFF123D2A),
    val offline: Color = Color(0xFFB4C5BC),
    val offlineContainer: Color = Color(0xFF26362E),
    val warning: Color = Color(0xFFF0C48E),
    val warningContainer: Color = Color(0xFF4D3518),
    val danger: Color = Color(0xFFFFB4AB),
    val latencyGood: Color = Color(0xFF80DBAD),
    val latencyFair: Color = Color(0xFFF0C48E),
    val latencyPoor: Color = Color(0xFFFFB4AB),
    val viewOnly: Color = Color(0xFFF0C48E),
    val glass: Color = Color(0xF01B241F),
    val canvas: Color = Color.Black,
)

internal fun meshSemanticColors(dark: Boolean) = if (dark) MeshColors() else MeshColors(
    online = Color(0xFF146342), onlineContainer = Color(0xFFC2EED6),
    offline = Color(0xFF465D52), offlineContainer = Color(0xFFE0EBE3),
    warning = Color(0xFF77501C), warningContainer = Color(0xFFFFE2B9),
    danger = Color(0xFFBA1A1A), latencyGood = Color(0xFF146342),
    latencyFair = Color(0xFF77501C), latencyPoor = Color(0xFFBA1A1A),
    viewOnly = Color(0xFF775A30), glass = Color(0xF0EFF5EF),
)

val LocalMeshColors = staticCompositionLocalOf { MeshColors() }
