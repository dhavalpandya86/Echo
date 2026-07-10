package com.dhaval.echo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom spacing system for Echo.
 * Emphasizes whitespace to create a "Calm" and "Premium" feel.
 */
@Immutable
data class EchoSpacing(
    val default: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val huge: Dp = 48.dp,
    val massive: Dp = 64.dp
)

val LocalSpacing = staticCompositionLocalOf { EchoSpacing() }
