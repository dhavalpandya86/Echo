package com.dhaval.echo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Echo 2026 Modern Expressive Brand Palette.
 * Vibrant, energetic, and tactile colors for the next generation.
 */

// Core Neutrals (Tinted for depth)
val EchoMidnight = Color(0xFF09090B)   // Deep, tinted neutral black
val EchoSoftPaper = Color(0xFFFDFCFB)  // Warm, elevated neutral white
val EchoGhostGray = Color(0xFFF4F4F5)  // Light tinted surface gray
val EchoSlateMuted = Color(0xFF71717A) // Functional muted text

// Brand Accents (Vibrant & Energetic)
val EchoElectricViolet = Color(0xFF7C3AED) // Primary vibrant violet
val EchoElectricVioletPressed = Color(0xFF6D28D9)
val EchoSoftLavender = Color(0xFFDDD6FE)   // Secondary soft accent
val EchoNeonMint = Color(0xFF10B981)      // Success / Growth accent
val EchoWarningCoral = Color(0xFFFB7185)   // Error / Warning accent

// Legacy / Design System Aliases
val EchoDeepIndigo = Color(0xFF312E81)
val EchoPaperWhite = EchoSoftPaper
val EchoInkBlack = EchoMidnight
val EchoWarmSurface = EchoGhostGray

// Light Theme Palette
val LightBackground = EchoSoftPaper
val LightSurface = EchoGhostGray
val LightSurfaceVariant = EchoGhostGray
val LightPrimary = EchoElectricViolet
val LightOnPrimary = Color.White
val LightSecondary = EchoSoftLavender
val LightOnSurface = EchoMidnight
val LightOutline = EchoSlateMuted
val LightError = EchoWarningCoral

// Dark Theme Palette (Deep Tinted Dark)
val DarkBackground = Color(0xFF020617) // Slate 950
val DarkSurface = Color(0xFF0F172A)    // Slate 900
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkPrimary = Color(0xFFA78BFA)    // Violet 400
val DarkOnPrimary = Color.White
val DarkSecondary = Color(0xFF475569)
val DarkOnSurface = Color(0xFFF8FAFC)
val DarkOutline = Color(0xFF334155)
val DarkError = EchoWarningCoral
