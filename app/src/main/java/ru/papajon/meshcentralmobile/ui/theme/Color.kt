package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Dark-first palette for a control-room tool: near-black surfaces, one confident accent and
 * semantic colours for transport state. Role names follow the Material 3 colour scheme so the
 * whole 30+ role set stays meaningful instead of collapsing into "primary everywhere".
 */
private val Primary = Color(0xFF4CC2FF)
private val OnPrimary = Color(0xFF00344A)
private val PrimaryContainer = Color(0xFF004C69)
private val OnPrimaryContainer = Color(0xFFC4E7FF)

private val Secondary = Color(0xFFB4C8DC)
private val OnSecondary = Color(0xFF1E3242)
private val SecondaryContainer = Color(0xFF35495A)
private val OnSecondaryContainer = Color(0xFFD0E4F8)

private val Tertiary = Color(0xFFD8BEE8)
private val OnTertiary = Color(0xFF3A2948)
private val TertiaryContainer = Color(0xFF523F60)
private val OnTertiaryContainer = Color(0xFFF4DAFF)

private val ErrorColor = Color(0xFFFFB4AB)
private val OnError = Color(0xFF690005)
private val ErrorContainer = Color(0xFF93000A)
private val OnErrorContainer = Color(0xFFFFDAD6)

internal val MeshDarkScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = Color(0xFF00658C),
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorColor,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE1E6EC),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE1E6EC),
    surfaceVariant = Color(0xFF3F4850),
    onSurfaceVariant = Color(0xFFBFC8D2),
    surfaceTint = Primary,
    inverseSurface = Color(0xFFE1E6EC),
    inverseOnSurface = Color(0xFF26313A),
    outline = Color(0xFF89929C),
    outlineVariant = Color(0xFF3F4850),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF313941),
    surfaceDim = Color(0xFF0B0F14),
    surfaceContainerLowest = Color(0xFF06090D),
    surfaceContainerLow = Color(0xFF12171D),
    surfaceContainer = Color(0xFF161C23),
    surfaceContainerHigh = Color(0xFF1D242B),
    surfaceContainerHighest = Color(0xFF262E36),
)

/** Colours that carry meaning beyond the Material roles. */
@Immutable
data class MeshColors(
    val online: Color = Color(0xFF5BE49B),
    val onlineContainer: Color = Color(0xFF0F3D28),
    val offline: Color = Color(0xFF7C8794),
    val offlineContainer: Color = Color(0xFF232B33),
    val warning: Color = Color(0xFFFFC46B),
    val warningContainer: Color = Color(0xFF3F2E10),
    val danger: Color = Color(0xFFFF8A80),
    val latencyGood: Color = Color(0xFF5BE49B),
    val latencyFair: Color = Color(0xFFFFC46B),
    val latencyPoor: Color = Color(0xFFFF8A80),
    val viewOnly: Color = Color(0xFFD8BEE8),
    val glass: Color = Color(0xE6141A21),
    val canvas: Color = Color(0xFF000000),
)

val LocalMeshColors = staticCompositionLocalOf { MeshColors() }
