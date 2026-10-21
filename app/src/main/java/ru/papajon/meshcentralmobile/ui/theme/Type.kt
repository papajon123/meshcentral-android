package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale tuned for a dense technical tool: tighter line heights, stronger weights for the
 * "editorial" moments (titles, metrics, primary actions) and tabular figures for numbers that
 * change while the user watches them (latency, resolution, byte counters).
 */
private val Default = Typography()

/** Heavier variant used for hero text, matching the emphasised styles of newer Material versions. */
fun TextStyle.emphasized(): TextStyle = copy(fontWeight = FontWeight.ExtraBold)

/** Fixed-width digits so live metrics do not jitter. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

internal val MeshTypography = Typography(
    displaySmall = Default.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = Default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Default.bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = Default.bodySmall.copy(lineHeight = 16.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = Default.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = Default.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
).withCroppedLineHeights()

private fun Typography.withCroppedLineHeights(): Typography {
    val style = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    fun TextStyle.trimmed() = copy(lineHeightStyle = style)
    return copy(
        displaySmall = displaySmall.trimmed(),
        headlineLarge = headlineLarge.trimmed(),
        headlineMedium = headlineMedium.trimmed(),
        headlineSmall = headlineSmall.trimmed(),
        titleLarge = titleLarge.trimmed(),
        titleMedium = titleMedium.trimmed(),
        titleSmall = titleSmall.trimmed(),
    )
}

/** Monospace style for identifiers, node ids and log-like text. */
val MeshMono: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)
