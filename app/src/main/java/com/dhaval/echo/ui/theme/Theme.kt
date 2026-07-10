package com.dhaval.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Echo Premium Dark Color Scheme.
 */
private val EchoDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSurface,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    outline = DarkOutline,
    error = DarkError
)

/**
 * Echo Premium Light Color Scheme.
 * Warm paper white background with soft indigo accents.
 */
private val EchoLightColorScheme = lightColorScheme(
    primary = EchoDeepIndigo,
    onPrimary = EchoPaperWhite,
    secondary = EchoSoftLavender,
    background = EchoPaperWhite,
    surface = EchoWarmSurface,
    onBackground = EchoInkBlack,
    onSurface = EchoInkBlack,
)

/**
 * Accessor object for Echo theme attributes.
 */
object EchoTheme {
    val spacing: EchoSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val elevation: EchoElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalElevation.current
}

/**
 * The unified Design System entry point for Echo.
 */
@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) EchoDarkColorScheme else EchoLightColorScheme

    CompositionLocalProvider(
        LocalSpacing provides EchoSpacing(),
        LocalElevation provides EchoElevation()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
