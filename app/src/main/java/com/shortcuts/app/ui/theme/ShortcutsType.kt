package com.shortcuts.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shortcuts.app.R

/**
 * Schibsted Grotesk, bundled in res/font (SIL Open Font License, redistributable).
 * Bundled rather than loaded from Google Fonts so the app renders identically offline and
 * on first launch — a downloadable font flashes a fallback while it fetches.
 */
val SchibstedGrotesk = FontFamily(
    Font(R.font.schibsted_grotesk_regular, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_medium, FontWeight.Medium),
    Font(R.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.schibsted_grotesk_bold, FontWeight.Bold)
)

/**
 * The type ramp from the approved design. Sizes are the design's literal values — do not
 * round them to a scale. Negative tracking on the large sizes is deliberate: Schibsted
 * Grotesk sets loose at display sizes.
 */
val ShortcutsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp, lineHeight = 39.sp, letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 12.5f.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 11.5f.sp, lineHeight = 15.sp
    )
)
