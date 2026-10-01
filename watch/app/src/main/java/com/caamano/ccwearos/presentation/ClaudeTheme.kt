package com.caamano.ccwearos.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.PixelMascot

// Compatibility aliases for screens that still reference the old palette
// names (PermissionScreen, ClaimResultBanner). New code should read
// MaterialTheme.colorScheme / StatusColors from presentation/theme.
// All values are flat solid colours: no alpha-on-colour text.
val ClaudeCoral = CcPalette.Coral
val ClaudeAmber = StatusColors.waiting
val ClaudeGreen = StatusColors.running
val ClaudeRed = StatusColors.error
val ClaudeDim = CcPalette.TextSecondary

val MonoFamily = FontFamily.Monospace

// Named spacings so overall density can be dialled from one place.
object WatchSpacing {
    val micro = 1.dp
    val tighten = 2.dp
    val compact = 4.dp
    val normal = 6.dp
    val relaxed = 8.dp
    val section = 10.dp
    val pageBreak = 14.dp
    val bottomBleed = 32.dp
}

// Semantic colour roles. Solid values only: the old coral@0.55 / dim@0.55
// text measured ~2.4:1 on black; #9A9A9A is ~7:1.
object WatchColors {
    val accentPrimary = CcPalette.Coral
    val accentSecondary = CcPalette.TextSecondary
    val accentTertiary = CcPalette.TextSecondary
    val divider = CcPalette.Outline
    val textPrimary = CcPalette.TextPrimary
    val textSecondary = Color(0xFFE0E0E0)
    val textTertiary = CcPalette.TextSecondary
    val textMuted = CcPalette.TextSecondary
}

fun shortNum(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

// Legacy entry point kept for PermissionScreen. Delegates to the pixel
// mascot state machine in presentation/ui/PixelMascot.kt.
@Composable
fun ClaudeMascot(
    modifier: Modifier = Modifier,
    width: Dp = 16.dp,
    breathe: Boolean = true,
    bodyColor: Color = ClaudeCoral,
    @Suppress("UNUSED_PARAMETER") eyeColor: Color = Color.Black,
    state: MascotState = MascotState.Idle,
) {
    PixelMascot(
        state = state,
        modifier = modifier,
        size = width,
        color = bodyColor,
        animate = breathe,
    )
}
