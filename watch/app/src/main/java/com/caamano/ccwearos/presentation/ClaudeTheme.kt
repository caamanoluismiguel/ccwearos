package com.caamano.ccwearos.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

// Claude Code terminal palette — Anthropic coral accent, terminal-inspired status colors.
val ClaudeCoral = Color(0xFFCC785C)
val ClaudeAmber = Color(0xFFFF9F0A)
val ClaudeGreen = Color(0xFF34C759)
val ClaudeRed = Color(0xFFFF453A)
val ClaudeDim = Color(0xFFB8B8B8)

val MonoFamily = FontFamily.Monospace

fun shortNum(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

// Claude Code pixel mascot — coral body, two black eyes, three little legs.
// Drawn from primitives so we don't ship any image assets.
@Composable
fun ClaudeMascot(
    modifier: Modifier = Modifier,
    width: Dp = 16.dp,
    breathe: Boolean = true,
    bodyColor: Color = ClaudeCoral,
    eyeColor: Color = Color.Black,
) {
    val transition = rememberInfiniteTransition(label = "mascot")
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (breathe) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mascot-bounce",
    )
    Canvas(modifier = modifier.size(width = width, height = width * 0.72f)) {
        val w = size.width
        val h = size.height
        val p = w / 14f                      // grid pixel width
        val yOffset = bounce * (p * 0.4f)    // ~half a pixel of breathing

        // Body — rounded-feeling rectangle (rows 0-4, cols 1-12 of a 14-wide grid).
        drawRect(
            color = bodyColor,
            topLeft = Offset(p, yOffset),
            size = Size(p * 12, p * 5),
        )

        // Eyes — two black rectangles inside the body.
        val eye = Size(p * 1.6f, p * 2f)
        drawRect(eyeColor, Offset(p * 3.4f, p * 1.2f + yOffset), eye)
        drawRect(eyeColor, Offset(p * 8.2f, p * 1.2f + yOffset), eye)

        // Three legs hanging below the body (cols 2, 6.5, 11 of the grid).
        val legTop = p * 5 + yOffset
        val legSize = Size(p * 1.2f, p * 2.2f)
        drawRect(bodyColor, Offset(p * 2.3f, legTop), legSize)
        drawRect(bodyColor, Offset(p * 6.4f, legTop), legSize)
        drawRect(bodyColor, Offset(p * 10.5f, legTop), legSize)
    }
}

@Composable
fun BlinkingCursor(color: Color, fontSize: TextUnit = 11.sp) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )
    Text(
        text = "▌",
        color = color.copy(alpha = alpha),
        fontFamily = MonoFamily,
        fontSize = fontSize,
    )
}
