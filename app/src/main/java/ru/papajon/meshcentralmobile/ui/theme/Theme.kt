package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Single theme entry point. The app is dark-first: a control client is used next to a remote
 * desktop, so a bright surface next to a dark canvas is worse than the reverse.
 */
@Composable
fun MeshCentralMobileTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    val colors = MeshColors()
    CompositionLocalProvider(
        LocalMeshColors provides colors,
        LocalMeshMotion provides MeshMotion(),
        LocalMeshSpacing provides MeshSpacing(),
    ) {
        MaterialTheme(
            colorScheme = MeshDarkScheme,
            typography = MeshTypography,
            shapes = MeshShapes,
        ) {
            // Material 3 does not publish LocalContentColor on its own, so text without an explicit
            // colour would fall back to black on a dark background. Painting the background here
            // gives every screen the correct default content colour.
            androidx.compose.material3.Surface(
                color = MeshDarkScheme.background,
                contentColor = MeshDarkScheme.onBackground,
                content = content,
            )
        }
    }
}

/** Shorthand accessors so screens read `MeshTheme.colors.online` instead of long chains. */
object MeshTheme {
    val colors: MeshColors
        @Composable get() = LocalMeshColors.current

    val motion: MeshMotion
        @Composable get() = LocalMeshMotion.current

    val spacing: MeshSpacing
        @Composable get() = LocalMeshSpacing.current
}
