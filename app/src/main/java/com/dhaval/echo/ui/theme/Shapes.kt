package com.dhaval.echo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Echo Brand shape scale. Three surface radii — flat surface (10), card (14),
 * floating (18) — plus pills for buttons (applied per-component as CircleShape).
 */
val Shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
