package com.dhaval.echo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom elevation system for Echo.
 * Uses minimal elevation to maintain a flat, "Timeless" aesthetic.
 * In Material 3, these values are used for tonal overlays.
 */
@Immutable
data class EchoElevation(
    val none: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp
)

val LocalElevation = staticCompositionLocalOf { EchoElevation() }
