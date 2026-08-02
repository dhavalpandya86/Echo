package com.dhaval.echo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Echo Brand System v2.0 palette.
 *
 * A warm, quiet, premium identity: paper canvas, indigo spent on one action per
 * screen, ink text, lavender highlights. See the brand kit for usage rules.
 */

// ── Core light palette ────────────────────────────────────────────────
val EchoPaper = Color(0xFFFAF8F5)     // canvas / backgrounds (~70%)
val EchoSurface = Color(0xFFF3EFEA)   // cards / surfaces (~15%)
val EchoMist = Color(0xFFECE8F3)      // selected / active tint
val EchoIndigo = Color(0xFF5A5DF0)    // primary — one action per screen
val EchoIndigoDeep = Color(0xFF4045D8) // pressed, links, running indigo text
val EchoLavender = Color(0xFFB6AEFF)  // highlight only
val EchoInk = Color(0xFF1A1A1A)       // primary text
val EchoSlate = Color(0xFF666666)     // secondary text

/**
 * Raised cards — the Stitch "floating card" look.
 *
 * Deliberately *lighter* than [EchoPaper] so a card lifts off the canvas. This
 * is the one place pure white is correct, and it must never be written as
 * `Color.White` at a call site: a literal cannot flip for dark mode, and the
 * text drawn on it will, which is how cards end up with invisible content.
 * Reach for `MaterialTheme.colorScheme.surfaceContainerLowest` instead.
 */
val EchoCard = Color(0xFFFFFFFF)

// ── Dark theme — "Night" ──────────────────────────────────────────────
// Values from the Stitch "Echo Narrative Dark" design system, so the dark theme
// is the one that was designed rather than one derived by darkening the light
// palette. Depth here comes from tonal layers, not shadows — a shadow is close
// to invisible on a near-black canvas.

val EchoNight = Color(0xFF1C1B1B)         // dark canvas
val EchoNightSurface = Color(0xFF34343D)  // muted containers / dividers
val EchoNightMist = Color(0xFF464554)     // selected / active tint
val EchoIndigoLight = Color(0xFFC2C1FF)   // primary on dark — a light lavender
val EchoNightText = Color(0xFFFCF9F8)     // text on night
val EchoNightSecondary = Color(0xFFC7C4D7) // secondary text on night
val EchoNightOutline = Color(0xFF918F9A)  // borders on night

/**
 * Text and icons drawn *on* [EchoIndigoLight].
 *
 * Dark mode inverts the usual relationship: the primary colour is now lighter
 * than the text that sits on it. Anything still drawing white on primary — which
 * was correct in light mode — turns invisible here, so it must resolve through
 * `onPrimary` rather than a literal.
 */
val EchoNightOnPrimary = Color(0xFF0C006B)

/**
 * The dark-mode counterpart of [EchoCard]: a raised card is *lighter* than the
 * canvas in both themes. Inverting that relationship is exactly what a hardcoded
 * white card did — it stayed white while the text on it went near-white.
 */
val EchoNightCard = Color(0xFF313030)

// ── Semantic ──────────────────────────────────────────────────────────
val EchoSuccess = Color(0xFF3E7C4F)
val EchoWarning = Color(0xFFA8742C)
val EchoError = Color(0xFFC4402F)
val EchoInfo = Color(0xFF4045D8)

// ── Legacy aliases (kept so existing references keep compiling) ─────────
@Deprecated("Use EchoInk", ReplaceWith("EchoInk")) val EchoMidnight = EchoInk
@Deprecated("Use EchoPaper", ReplaceWith("EchoPaper")) val EchoSoftPaper = EchoPaper
@Deprecated("Use EchoSurface", ReplaceWith("EchoSurface")) val EchoGhostGray = EchoSurface
@Deprecated("Use EchoSlate", ReplaceWith("EchoSlate")) val EchoSlateMuted = EchoSlate
@Deprecated("Use EchoIndigo", ReplaceWith("EchoIndigo")) val EchoElectricViolet = EchoIndigo
@Deprecated("Use EchoIndigoDeep", ReplaceWith("EchoIndigoDeep")) val EchoElectricVioletPressed = EchoIndigoDeep
@Deprecated("Use EchoLavender", ReplaceWith("EchoLavender")) val EchoSoftLavender = EchoLavender
@Deprecated("Use EchoSuccess", ReplaceWith("EchoSuccess")) val EchoNeonMint = EchoSuccess
@Deprecated("Use EchoError", ReplaceWith("EchoError")) val EchoWarningCoral = EchoError
@Deprecated("Use EchoIndigo", ReplaceWith("EchoIndigo")) val EchoDeepIndigo = EchoIndigo
@Deprecated("Use EchoPaper", ReplaceWith("EchoPaper")) val EchoPaperWhite = EchoPaper
@Deprecated("Use EchoInk", ReplaceWith("EchoInk")) val EchoInkBlack = EchoInk
@Deprecated("Use EchoSurface", ReplaceWith("EchoSurface")) val EchoWarmSurface = EchoSurface
