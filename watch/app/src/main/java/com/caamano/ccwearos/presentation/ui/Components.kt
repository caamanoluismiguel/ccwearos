package com.caamano.ccwearos.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors

/** 12sp mono uppercase label with +1sp tracking (theme `labelMedium`). */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        textAlign = textAlign,
        maxLines = 1,
    )
}

/**
 * Solid status dot. When [description] is null the dot is decorative (the
 * adjacent text already says the state); otherwise it carries its own label
 * so colour is never the only signal.
 */
@Composable
fun StatusDot(
    color: Color,
    description: String?,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val semanticsModifier = if (description != null) {
        Modifier.semantics { contentDescription = description }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .then(semanticsModifier),
    )
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** Colour for a usage percentage: coral normally, amber ≥75%, red ≥90%. */
fun usageColor(pct: Double): Color = when {
    pct >= 90.0 -> StatusColors.error
    pct >= 75.0 -> StatusColors.waiting
    else -> CcPalette.Coral
}

/**
 * Thin 270° arc gauge (open at the bottom), solid stroke on a solid track.
 * Value sits in the centre in tabular mono; the label sits in the opening.
 */
@Composable
fun ArcGauge(
    fraction: Float,
    valueText: String,
    label: String,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
    stroke: Dp = 3.dp,
) {
    val track = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(diameter)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val inset = s / 2f
            val arcSize = Size(size.width - s, size.height - s)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = s, cap = StrokeCap.Round),
            )
            val sweep = 270f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = s, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.numeralExtraSmall,
                maxLines = 1,
            )
        }
        MonoLabel(
            text = label,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The "Terminado" moment: a 1dp coral ring sweeps once around the round
 * screen edge, then fades. Stroke only, no glow. Re-runs whenever [trigger]
 * changes to a positive value. Skipped entirely with reduced motion (the
 * haptic still fires).
 */
@Composable
fun CompletionRing(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    if (rememberReducedMotion()) return
    val sweep = remember(trigger) { Animatable(0f) }
    val alpha = remember(trigger) { Animatable(1f) }
    LaunchedEffect(trigger) {
        sweep.animateTo(360f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        alpha.animateTo(0f, tween(durationMillis = 300, easing = LinearEasing))
    }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (alpha.value <= 0f) return@Canvas
            val s = 1.dp.toPx()
            drawArc(
                color = CcPalette.Coral,
                startAngle = -90f,
                sweepAngle = sweep.value,
                useCenter = false,
                topLeft = Offset(s, s),
                size = Size(size.width - 2 * s, size.height - 2 * s),
                style = Stroke(width = s),
                alpha = alpha.value,
            )
        }
    }
}
