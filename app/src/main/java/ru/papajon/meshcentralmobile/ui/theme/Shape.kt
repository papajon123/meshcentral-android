package ru.papajon.meshcentralmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale with deliberate variety instead of one radius everywhere: soft cards, pill chips and
 * buttons, generous sheets, plus a squircle used for the session action button.
 */
val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** Rounded square anchor for the primary floating action (never a plain circle). */
val SquircleShape = RoundedCornerShape(22.dp)

/** Pill used by chips, status badges and segmented controls. */
val PillShape = RoundedCornerShape(percent = 50)

/** Sheet corner: large top radius, small bottom radius, matching Material sheets. */
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
