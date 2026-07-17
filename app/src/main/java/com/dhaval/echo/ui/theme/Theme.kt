package com.dhaval.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Light scheme — Paper canvas, warm Surface cards, Echo Indigo primary.
 * Indigo is deliberately scarce: one primary action per screen.
 */
private val EchoLightColorScheme = lightColorScheme(
    primary = EchoIndigo,
    onPrimary = EchoPaper,
    primaryContainer = EchoMist,
    onPrimaryContainer = EchoIndigoDeep,
    // `secondary` is the app's muted-text role (nav labels, timestamps, captions),
    // so it must be Slate — 5.6:1 on Paper. Lavender is highlight-only and lives
    // in `tertiary`; using it here washes secondary text out across every screen.
    secondary = EchoSlate,
    onSecondary = EchoPaper,
    secondaryContainer = EchoMist,
    onSecondaryContainer = EchoIndigoDeep,
    tertiary = EchoLavender,
    onTertiary = EchoInk,
    background = EchoPaper,
    onBackground = EchoInk,
    surface = EchoPaper,
    onSurface = EchoInk,
    surfaceVariant = EchoSurface,
    onSurfaceVariant = EchoSlate,
    outline = EchoSlate,
    outlineVariant = EchoSurface,
    error = EchoError,
    onError = EchoPaper,
)

/**
 * Dark scheme — "Night". Indigo lightens for contrast, mark goes Lavender.
 */
private val EchoDarkColorScheme = darkColorScheme(
    primary = EchoIndigoLight,
    onPrimary = EchoNight,
    primaryContainer = EchoNightMist,
    onPrimaryContainer = EchoLavender,
    // Muted-text role on Night — see the light scheme note above.
    secondary = EchoNightSecondary,
    onSecondary = EchoNight,
    secondaryContainer = EchoNightMist,
    onSecondaryContainer = EchoLavender,
    tertiary = EchoLavender,
    onTertiary = EchoNight,
    background = EchoNight,
    onBackground = EchoNightText,
    surface = EchoNight,
    onSurface = EchoNightText,
    surfaceVariant = EchoNightSurface,
    onSurfaceVariant = EchoNightSecondary,
    outline = EchoNightSecondary,
    outlineVariant = EchoNightSurface,
    error = EchoError,
    onError = EchoNightText,
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
