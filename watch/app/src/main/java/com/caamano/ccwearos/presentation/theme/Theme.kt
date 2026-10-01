package com.caamano.ccwearos.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

// ─────────────────────────────────────────────────────────────────────────────
// CCWEAROS design tokens.
//
// A quiet wrist instrument: true black, ONE coral signal colour, monospace
// numerals. Every colour here is a flat solid value. No alpha-on-colour text
// (coral at 55% alpha read ~2.4:1 on black) and no gradients anywhere.
// ─────────────────────────────────────────────────────────────────────────────

/** Raw palette. Prefer [MaterialTheme.colorScheme] or [StatusColors] at call sites. */
object CcPalette {
    val Coral = Color(0xFFCC785C)
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val SurfaceHigh = Color(0xFF1E1E1E)
    val Outline = Color(0xFF2A2A2A)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9A9A9A)
}

/**
 * Semantic status tokens. Colour is never the only carrier of meaning: every
 * use is paired with a word or a contentDescription.
 */
object StatusColors {
    val running = Color(0xFF30D158)
    val waiting = Color(0xFFFFB340)
    val error = Color(0xFFFF6961)
    val idle = Color(0xFF8E8E93)
    val offline = Color(0xFF5A5A5A)
}

val CcColorScheme = ColorScheme(
    primary = CcPalette.Coral,
    primaryDim = CcPalette.Coral,
    primaryContainer = CcPalette.Coral,
    onPrimary = CcPalette.Black,
    onPrimaryContainer = CcPalette.Black,
    secondary = CcPalette.TextPrimary,
    secondaryDim = CcPalette.TextSecondary,
    secondaryContainer = CcPalette.Surface,
    onSecondary = CcPalette.Black,
    onSecondaryContainer = CcPalette.TextPrimary,
    tertiary = StatusColors.running,
    tertiaryDim = StatusColors.running,
    tertiaryContainer = CcPalette.Surface,
    onTertiary = CcPalette.Black,
    onTertiaryContainer = CcPalette.TextPrimary,
    surfaceContainerLow = CcPalette.Surface,
    surfaceContainer = CcPalette.Surface,
    surfaceContainerHigh = CcPalette.SurfaceHigh,
    onSurface = CcPalette.TextPrimary,
    onSurfaceVariant = CcPalette.TextSecondary,
    outline = CcPalette.Outline,
    outlineVariant = CcPalette.Outline,
    background = CcPalette.Black,
    onBackground = CcPalette.TextPrimary,
    error = StatusColors.error,
    errorDim = StatusColors.error,
    errorContainer = CcPalette.Surface,
    onError = CcPalette.Black,
    onErrorContainer = StatusColors.error,
)

private val Mono = FontFamily.Monospace
private val Sans = FontFamily.Default

// Tabular numerals: every digit has the same advance, so the 800ms token
// count-up and live percentages don't jitter horizontally.
private const val TNUM = "tnum"

private val Numeral = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = TNUM,
)

/**
 * Type scale. Floor is 12sp everywhere: nothing on this watch renders smaller.
 *
 *  - display*: tokens / cost, 34sp mono bold, tabular.
 *  - title*:   state words and headlines, sans.
 *  - body*:    reading text, 14sp / 20sp line height.
 *  - label*:   12sp mono, +1sp tracking. Callers uppercase the text
 *              (TextStyle has no text-transform), see `MonoLabel`.
 */
val CcTypography = Typography(
    defaultFontFamily = Sans,
    displayLarge = Numeral.copy(fontSize = 34.sp, lineHeight = 38.sp),
    displayMedium = Numeral.copy(fontSize = 28.sp, lineHeight = 32.sp),
    displaySmall = Numeral.copy(fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    bodyExtraSmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
    numeralExtraLarge = Numeral.copy(fontSize = 44.sp),
    numeralLarge = Numeral.copy(fontSize = 34.sp),
    numeralMedium = Numeral.copy(fontSize = 28.sp),
    numeralSmall = Numeral.copy(fontSize = 22.sp),
    numeralExtraSmall = Numeral.copy(fontSize = 16.sp),
)

@Composable
fun CCWEAROSTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CcColorScheme,
        typography = CcTypography,
        content = content,
    )
}
