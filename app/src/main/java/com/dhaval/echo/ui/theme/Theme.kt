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
    // Raised cards. See the note on the dark scheme's copy of this role for why
    // both schemes define it explicitly rather than letting Material derive it.
    surfaceContainerLowest = EchoCard,
    surfaceContainerLow = EchoCard,
    surfaceContainer = EchoSurface,
    surfaceContainerHigh = EchoSurface,
    surfaceContainerHighest = EchoMist,
    outline = EchoSlate,
    outlineVariant = EchoSurface,
    error = EchoError,
    onError = EchoPaper,
)

/**
 * Dark scheme — "Night", from the Stitch dark design system.
 *
 * Depth comes from tonal layers rather than shadows: a card is a lighter grey on
 * a near-black canvas, because a drop shadow is invisible against black.
 *
 * The trap this scheme exists to close: in light mode `primary` is a saturated
 * indigo carrying white text, so drawing white on it is fine. Here `primary` is
 * a *light* lavender and the text on it must be dark. Any view that hardcodes
 * white instead of resolving `onPrimary` disappears — which is precisely how
 * card text went invisible before this was written down.
 */
private val EchoDarkColorScheme = darkColorScheme(
    primary = EchoIndigoLight,
    onPrimary = EchoNightOnPrimary,
    // The solid indigo from the design — used for filled accents like the record
    // button, where white-on-indigo is still the correct pairing.
    primaryContainer = EchoIndigo,
    onPrimaryContainer = EchoNightText,
    // Muted-text role on Night — see the light scheme note above.
    secondary = EchoNightSecondary,
    onSecondary = EchoNight,
    secondaryContainer = EchoNightMist,
    onSecondaryContainer = EchoNightText,
    tertiary = EchoLavender,
    onTertiary = EchoNight,
    background = EchoNight,
    onBackground = EchoNightText,
    surface = EchoNight,
    onSurface = EchoNightText,
    surfaceVariant = EchoNightSurface,
    onSurfaceVariant = EchoNightSecondary,
    // A raised card is lighter than the canvas in *both* themes. Screens read
    // this role rather than a literal colour, which also means Material derives
    // the matching `onSurface` text colour for them automatically — the pairing
    // whose absence caused near-white text on white cards.
    surfaceContainerLowest = EchoNightCard,
    surfaceContainerLow = EchoNightCard,
    surfaceContainer = EchoNightSurface,
    surfaceContainerHigh = EchoNightMist,
    surfaceContainerHighest = EchoNightMist,
    outline = EchoNightOutline,
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
