package com.caamano.ccwearos.presentation.permission

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caamano.ccwearos.presentation.Haptics
import kotlinx.coroutines.flow.collectLatest

const val HOLD_TO_CONFIRM_MS = 1200

/**
 * Drives press-and-hold confirmation from a button's [interactionSource].
 * Press starts a linear 0→1 fill over [HOLD_TO_CONFIRM_MS]; release or cancel
 * (finger slid into a scroll) drains it back to 0. Reaching 1 fires
 * [onConfirm] exactly once per press.
 *
 * Returns the progress Animatable so the caller can draw the ring.
 */
@Composable
fun rememberHoldToConfirm(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onConfirm: () -> Unit,
): Animatable<Float, *> {
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    val confirm by rememberUpdatedState(onConfirm)
    LaunchedEffect(interactionSource, enabled) {
        if (!enabled) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        // collectLatest cancels the running fill as soon as Release/Cancel
        // arrives, which is exactly "letting go early cancels".
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    var stepsFired = 0
                    progress.snapTo(0f)
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(HOLD_TO_CONFIRM_MS, easing = LinearEasing),
                    ) {
                        // Ticks at 25 / 50 / 75 %. 100 % gets the confirm pattern below.
                        val reached = (value * 4).toInt().coerceAtMost(3)
                        while (stepsFired < reached) {
                            stepsFired++
                            Haptics.holdStep(context)
                        }
                    }
                    Haptics.permission(context)
                    confirm()
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (progress.value < 1f) progress.animateTo(0f, tween(180))
                }
            }
        }
    }
    return progress
}

/**
 * Solid ring that hugs the round bezel and fills clockwise from 12 o'clock.
 * Flat color on a flat track; no gradient, no glow.
 */
@Composable
fun HoldRing(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    if (progress <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f + 1.dp.toPx()
        val diameter = size.minDimension - inset * 2
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }
}
