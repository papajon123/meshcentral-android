package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Readability contracts for the actual shipped color pairs, independent of screenshot recording. */
class ThemeContrastTest {
    @Test fun lightAndDarkTextPairsMeetWcagNormalTextContrast() {
        listOf("light" to MeshLightScheme, "dark" to MeshDarkScheme).forEach { (name, c) ->
            listOf(
                "primary" to (c.onPrimary to c.primary),
                "primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
                "secondary" to (c.onSecondary to c.secondary),
                "secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
                "tertiary" to (c.onTertiary to c.tertiary),
                "tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
                "error" to (c.onError to c.error),
                "errorContainer" to (c.onErrorContainer to c.errorContainer),
                "background" to (c.onBackground to c.background),
                "surface" to (c.onSurface to c.surface),
                "surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
                "lowSurface" to (c.onSurfaceVariant to c.surfaceContainerLow),
                "highSurface" to (c.onSurfaceVariant to c.surfaceContainerHighest),
            ).forEach { (role, pair) -> assertContrast("$name/$role", pair.first, pair.second) }
        }
    }

    @Test fun connectionStatesRemainReadableInBothThemes() {
        listOf(false, true).forEach { dark ->
            val c = meshSemanticColors(dark)
            assertContrast("online/$dark", c.online, c.onlineContainer)
            assertContrast("offline/$dark", c.offline, c.offlineContainer)
            assertContrast("warning/$dark", c.warning, c.warningContainer)
        }
    }

    private fun assertContrast(role: String, foreground: Color, background: Color) {
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
        }
        val a = luminance(foreground); val b = luminance(background)
        val ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
        assertTrue("$role contrast $ratio is below 4.5:1", ratio >= 4.5)
    }
}
