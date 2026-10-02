package com.caamano.ccwearos.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.CcStroke
import com.caamano.ccwearos.presentation.theme.CcType
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// Feedback kit: the building blocks of the Motion.kt feedback contract.
//   ¿Qué hice?         Modifier.pressFeedback (scale + Haptics.tick)
//   ¿Qué está pasando? ProgressHalo, ElapsedTimer, PixelMascot
//   ¿Salió bien?       SuccessRing (with Haptics.done), Modifier.shake (with Haptics.error)
//   Arrival            StaggeredReveal / Modifier.staggeredReveal
// Everything honours reduced motion: decoration is dropped, state stays readable.

/**
 * Instant press response: the control scales to [Motion.PRESSED_SCALE] on a
 * spring and [Haptics.tick] fires on press-down. Pass the SAME
 * [interactionSource] you give the clickable / Button.
 *
 * ```
 * val src = remember { MutableInteractionSource() }
 * Button(onClick = ..., interactionSource = src, modifier = Modifier.pressFeedback(src))
 * ```
 * Reduced motion: no scale, the tick still fires.
 */
fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    haptic: Boolean = true,
): Modifier = composed {
    val reduced = rememberReducedMotion()
    val context = LocalContext.current
    val hapticOn by rememberUpdatedState(haptic)
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.press(),
        label = "press-scale",
    )
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is PressInteraction.Press && hapticOn) Haptics.tick(context)
        }
    }
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The "¿salió bien?" moment: a thin coral stroke sweeps once around the
 * round bezel in [Motion.CELEBRATE] ms, then fades. No glow. Re-runs every
 * time [trigger] changes to a positive value; pair it with Haptics.done.
 * Reduced motion: the full ring appears for the same duration, no sweep.
 */
@Composable
fun SuccessRing(
    trigger: Int,
    modifier: Modifier = Modifier,
    color: Color = CcPalette.Coral,
    strokeWidth: Dp = CcStroke.emphasis,
) {
    if (trigger <= 0) return
    val reduced = rememberReducedMotion()
    val sweep = remember(trigger) { Animatable(if (reduced) 360f else 0f) }
    val alpha = remember(trigger) { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (reduced) {
            delay(Motion.CELEBRATE.toLong())
            alpha.snapTo(0f)
        } else {
            sweep.animateTo(360f, tween(Motion.CELEBRATE, easing = Motion.StandardEasing))
            alpha.animateTo(0f, tween(Motion.MEDIUM, easing = LinearEasing))
        }
    }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { }) {
        if (alpha.value <= 0f) return@Canvas
        drawBezelArc(color, -90f, sweep.value, strokeWidth.toPx(), alpha.value, StrokeCap.Butt)
    }
}

/**
 * "¿Qué está pasando?" for running work: a short arc segment travels slowly
 * around the screen edge (one lap per [periodMs]). Calm, not a spinner.
 * Fades in/out with [active]. Reduced motion: a static segment at 12 o'clock.
 */
@Composable
fun ProgressHalo(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = CcPalette.Coral,
    strokeWidth: Dp = CcStroke.emphasis,
    segmentDegrees: Float = 36f,
    periodMs: Int = Motion.HALO_LAP,
    showTrack: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = active,
        modifier = modifier,
        enter = fadeIn(Motion.enter(Motion.SLOW)),
        exit = fadeOut(Motion.exit(Motion.MEDIUM)),
        label = "progress-halo",
    ) {
        val angle = if (reduced) {
            0f
        } else {
            val t = rememberInfiniteTransition(label = "halo")
            val a by t.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
                label = "halo-angle",
            )
            a
        }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val s = strokeWidth.toPx()
            if (showTrack) drawBezelArc(CcPalette.Outline, 0f, 360f, CcStroke.hairline.toPx(), 1f, StrokeCap.Butt, inset = s / 2f)
            drawBezelArc(color, -90f - segmentDegrees / 2f + angle, segmentDegrees, s, 1f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawBezelArc(
    color: Color,
    start: Float,
    sweep: Float,
    strokePx: Float,
    alpha: Float,
    cap: StrokeCap,
    inset: Float = strokePx / 2f,
) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        style = Stroke(width = strokePx, cap = cap),
        alpha = alpha,
    )
}

/**
 * Error shake: [oscillations] damped horizontal swings, [maxOffset] peak,
 * whenever [trigger] changes to a positive value. Pair with Haptics.error.
 * Reduced motion: no-op (the error text/X eyes carry the meaning).
 */
fun Modifier.shake(
    trigger: Int,
    maxOffset: Dp = 8.dp,
    oscillations: Int = 3,
    durationMs: Int = SHAKE_DURATION_MS,
): Modifier = composed {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0 && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
        }
    }
    val maxPx = with(LocalDensity.current) { maxOffset.toPx() }
    graphicsLayer { translationX = shakeOffset(progress.value, oscillations) * maxPx }
}

internal const val SHAKE_DURATION_MS = 360

/** Normalised damped shake at [progress] 0..1: starts and ends at 0, |value| ≤ 1. */
internal fun shakeOffset(progress: Float, oscillations: Int): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return 0f
    return (sin(p * oscillations * 2.0 * PI) * (1f - p)).toFloat()
}

/** Delay before item [index] reveals; capped so long lists don't drag. */
internal fun staggerDelayMs(index: Int, maxSteps: Int = STAGGER_MAX_STEPS): Long =
    index.coerceIn(0, maxSteps).toLong() * Motion.STAGGER

internal const val STAGGER_MAX_STEPS = 8

/**
 * List-item arrival: fades in and rises 8dp after `index * Motion.STAGGER`
 * ms. Reduced motion: shown immediately.
 */
fun Modifier.staggeredReveal(index: Int, visible: Boolean = true): Modifier = composed {
    val reduced = rememberReducedMotion()
    val v = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(visible, reduced) {
        when {
            reduced -> v.snapTo(if (visible) 1f else 0f)
            visible -> {
                delay(staggerDelayMs(index))
                v.animateTo(1f, Motion.enter(Motion.MEDIUM))
            }
            else -> v.animateTo(0f, Motion.exit())
        }
    }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    graphicsLayer {
        alpha = v.value
        translationY = (1f - v.value) * rise
    }
}

/** Composable form of [staggeredReveal]. */
@Composable
fun StaggeredReveal(
    index: Int,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier.staggeredReveal(index, visible)) { content() }
}

/**
 * Live elapsed time since [startedAtMillis] (wall clock, ms) as `mm:ss`
 * (`h:mm:ss` past an hour), tabular numerals so digits never jitter. Freezes
 * at [endedAtMillis] when given. Ticks on the second boundary; it is
 * information, so it keeps ticking under reduced motion.
 */
@Composable
fun ElapsedTimer(
    startedAtMillis: Long,
    modifier: Modifier = Modifier,
    endedAtMillis: Long? = null,
    style: TextStyle = CcType.numeral,
    color: Color = MaterialTheme.colorScheme.onSurface,
    clock: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(startedAtMillis, endedAtMillis) {
        if (endedAtMillis != null) return@LaunchedEffect
        while (true) {
            now = clock()
            val intoSecond = (now - startedAtMillis).mod(1_000L)
            delay(1_000L - intoSecond)
        }
    }
    Text(
        text = formatElapsed((endedAtMillis ?: now) - startedAtMillis),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

/** `mm:ss`, or `h:mm:ss` from one hour. Negative durations clamp to 00:00. */
internal fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1_000L
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val s = total % 60
    // Locale.ROOT: always ASCII digits and ':' regardless of device locale.
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
}
