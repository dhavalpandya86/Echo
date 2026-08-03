@file:OptIn(ExperimentalTextApi::class)

package com.dhaval.echo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dhaval.echo.R

/**
 * Echo's two-typeface system (Stitch "Organic Minimalism"):
 *  • Plus Jakarta Sans — headings & display. Geometric, confident, sits beside
 *    the circular mark.
 *  • Inter — body & captions. A workhorse UI face tuned for on-screen reading.
 * Both are bundled as single variable fonts (weight axis); each weight is derived
 * via FontVariation (API 26+).
 */
private fun variableWeight(resId: Int, weight: FontWeight) = Font(
    resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** Headings & display. */
val Display = FontFamily(
    variableWeight(R.font.plus_jakarta_sans_variable, FontWeight.Medium),
    variableWeight(R.font.plus_jakarta_sans_variable, FontWeight.SemiBold),
    variableWeight(R.font.plus_jakarta_sans_variable, FontWeight.Bold),
    variableWeight(R.font.plus_jakarta_sans_variable, FontWeight.ExtraBold),
)

/** Body & captions. */
val Body = FontFamily(
    variableWeight(R.font.inter_variable, FontWeight.Normal),
    variableWeight(R.font.inter_variable, FontWeight.Medium),
    variableWeight(R.font.inter_variable, FontWeight.SemiBold),
    variableWeight(R.font.inter_variable, FontWeight.Bold),
)

// Back-compat alias for any code referencing the old brand family name.
@Deprecated("Use Display (headings) or Body (text)", ReplaceWith("Body"))
val Manrope = Body

/**
 * Echo type scale — 1.333 ratio on a 15px base. Tracking tightens as size grows
 * (0 → −3%). One voice, many volumes.
 */
val Typography = Typography(
    // Display · 800 · 42/1.12 · −3%
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 42.sp,
        lineHeight = 47.sp,
        letterSpacing = (-0.03).em
    ),
    displayMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.03).em
    ),
    displaySmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.02).em
    ),
    // H1 · 700 · 30/1.2 · −2%
    headlineLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.02).em
    ),
    // H2 · 700 · 22/1.3 · −1.5%
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.015).em
    ),
    headlineSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.01).em
    ),
    // Subhead · 600 · 17/1.4
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // Body · 400 · 15/1.65
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    // Caption · 500 · 12 · Slate
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.01.em
    ),
    labelSmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.02.em
    )
)
