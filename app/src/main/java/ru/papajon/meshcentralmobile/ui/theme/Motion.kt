package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp

/**
 * Motion tokens: springs only, never fixed-duration tweens, so an interrupted animation retargets
 * from its current velocity instead of snapping. Spatial springs are used for position, size and
 * rotation; effect springs (critically damped) are used for colour and opacity, because a bouncy
 * alpha overshoots past 100% and looks broken.
 */
@Immutable
data class MeshMotion(
    val spatialFast: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessHigh),
    val spatialDefault: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
    val spatialSlow: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
    val sizeDefault: FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
    val effectFast: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
    val effectDefault: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
    /** Critique: colour must never bounce, so this is a critically damped spring, not a tween. */
    val effectColor: AnimationSpec<Color> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
    val pressScale: Float = 0.96f,
)

val LocalMeshMotion = staticCompositionLocalOf { MeshMotion() }

/**
 * Spacing scale (4 dp base). Exposed as tokens so padding stays consistent across screens instead
 * of drifting per file.
 */
@Immutable
data class MeshSpacing(
    val xs: Dp = androidx.compose.ui.unit.Dp(4f),
    val sm: Dp = androidx.compose.ui.unit.Dp(8f),
    val md: Dp = androidx.compose.ui.unit.Dp(12f),
    val lg: Dp = androidx.compose.ui.unit.Dp(16f),
    val xl: Dp = androidx.compose.ui.unit.Dp(24f),
    val xxl: Dp = androidx.compose.ui.unit.Dp(32f),
)

val LocalMeshSpacing = staticCompositionLocalOf { MeshSpacing() }
