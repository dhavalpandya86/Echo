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

// ── Dark theme — "Night" ──────────────────────────────────────────────
val EchoNight = Color(0xFF16151A)         // dark canvas
val EchoNightSurface = Color(0xFF211F26)  // dark cards
val EchoNightMist = Color(0xFF2A2830)     // dark selected tint
val EchoIndigoLight = Color(0xFF8B8DF5)   // indigo lightened for dark contrast
val EchoNightText = Color(0xFFF3EFEA)     // text on night
val EchoNightSecondary = Color(0xFFA5A1AC) // secondary text on night

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
