package com.caamano.ccwearos.presentation.ui

import android.animation.ValueAnimator
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the mascot is telling you. The mascot is the single living status
 * indicator on the watch, so every state has its own motion + eyes:
 *
 *  - Idle:    slow 4s breathe, blink every 6s.
 *  - Running: two-frame walk, 300ms per frame.
 *  - Waiting: 2px jump loop, "!" eyes (a permission needs you).
 *  - Done:    one hop, ✓ eyes.
 *  - Error:   still, X eyes.
 *  - Offline: still, grey body, closed eyes.
 */
// Sending = prompt spoken, waiting for the Mac to pick it up.
// Blocked = something only the Mac can fix (folder trust, login).
enum class MascotState { Idle, Sending, Running, Waiting, Done, Error, Blocked, Offline }

/** Human state word, shared by Page 0 and the mascot's contentDescription. */
@StringRes
fun MascotState.labelRes(): Int = when (this) {
    MascotState.Idle -> R.string.state_idle
    MascotState.Sending -> R.string.state_sending
    MascotState.Running -> R.string.state_running
    MascotState.Waiting -> R.string.state_waiting
    MascotState.Done -> R.string.state_done
    MascotState.Error -> R.string.state_error
    MascotState.Blocked -> R.string.state_blocked
    MascotState.Offline -> R.string.state_offline
}

/** Semantic colour of a state (status dot, gauge accents). */
fun MascotState.statusColor(): Color = when (this) {
    MascotState.Idle -> StatusColors.idle
    MascotState.Sending -> StatusColors.running
    MascotState.Running -> StatusColors.running
    MascotState.Waiting -> StatusColors.waiting
    MascotState.Done -> StatusColors.running
    MascotState.Error -> StatusColors.error
    MascotState.Blocked -> StatusColors.waiting
    MascotState.Offline -> StatusColors.offline
}

fun WrapperStatus.toMascotState(): MascotState = when (this) {
    WrapperStatus.IDLE -> MascotState.Idle
    WrapperStatus.RUNNING -> MascotState.Running
    WrapperStatus.AWAITING_PERMISSION -> MascotState.Waiting
    WrapperStatus.OFFLINE -> MascotState.Offline
}

// Grid: 16 columns × 15 rows. The top 3 rows are headroom for jumps/hops so
// the sprite never clips; the body itself occupies rows 3..10, legs 11..13.
private const val COLS = 16
private const val ROWS = 15
private const val HEADROOM = 3

/** True when the user turned animations off (animator duration scale = 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val inPreview = LocalInspectionMode.current
    return remember { inPreview || !ValueAnimator.areAnimatorsEnabled() }
}

@Composable
fun PixelMascot(
    state: MascotState,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color? = null,
    animate: Boolean = true,
) {
    val reducedMotion = rememberReducedMotion()
    val shouldAnimate = animate && !reducedMotion

    // Whole-sprite vertical offset in grid pixels (negative = up).
    var lift by remember { mutableIntStateOf(0) }
    // Body-only offset (breathing squash onto the legs).
    var breath by remember { mutableIntStateOf(0) }
    var walkFrame by remember { mutableIntStateOf(0) }
    var blinking by remember { mutableStateOf(false) }

    // Pixel-snapped step animation: values change in whole grid pixels, which
    // keeps the sprite crisp, and only the Canvas redraws (state is read in
    // the draw lambda).
    LaunchedEffect(state, shouldAnimate) {
        lift = 0; breath = 0; walkFrame = 0; blinking = false
        if (!shouldAnimate) return@LaunchedEffect
        when (state) {
            MascotState.Idle -> coroutineScope {
                launch {
                    while (true) {
                        delay(2_000); breath = 1
                        delay(2_000); breath = 0
                    }
                }
                launch {
                    while (true) {
                        delay(6_000); blinking = true
                        delay(140); blinking = false
                    }
                }
            }
            MascotState.Running, MascotState.Sending -> while (true) {
                delay(300)
                walkFrame = 1 - walkFrame
                lift = if (walkFrame == 1) -1 else 0
            }
            MascotState.Waiting -> while (true) {
                lift = -1; delay(70)
                lift = -2; delay(160)
                lift = -1; delay(70)
                lift = 0; delay(700)
            }
            MascotState.Done -> {
                lift = -1; delay(60)
                lift = -3; delay(180)
                lift = -1; delay(60)
                lift = 0
            }
            MascotState.Error, MascotState.Blocked, MascotState.Offline -> Unit
        }
    }

    val body = color ?: if (state == MascotState.Offline) StatusColors.offline else CcPalette.Coral
    val eye = Color.Black
    val description = stringResource(
        R.string.mascot_cd,
        stringResource(state.labelRes()),
    )

    Canvas(
        modifier = modifier
            .size(width = size, height = size * ROWS / COLS)
            .semantics { contentDescription = description },
    ) {
        val p = this.size.width / COLS
        drawMascot(
            p = p,
            state = state,
            body = body,
            eye = eye,
            lift = lift,
            breath = breath,
            walkFrame = walkFrame,
            blinking = blinking,
        )
    }
}

private fun DrawScope.drawMascot(
    p: Float,
    state: MascotState,
    body: Color,
    eye: Color,
    lift: Int,
    breath: Int,
    walkFrame: Int,
    blinking: Boolean,
) {
    fun px(x: Int, y: Int, w: Int = 1, h: Int = 1, c: Color, dy: Int = 0) {
        drawRect(
            color = c,
            topLeft = Offset(x * p, (y + HEADROOM + lift + dy) * p),
            size = Size(w * p, h * p),
        )
    }

    // Legs (4). While walking, alternate pairs lift one pixel.
    val legTop = 8
    val legs = listOf(3, 5, 10, 12)
    legs.forEachIndexed { i, x ->
        val raised = state == MascotState.Running && (i % 2 == walkFrame)
        px(x, legTop, h = if (raised) 2 else 3, c = body)
    }

    // Body + side arms. Breathing sinks the body (and eyes) one pixel.
    px(2, 0, w = 12, h = 8, c = body, dy = breath)
    px(0, 3, w = 2, h = 2, c = body, dy = breath)
    px(14, 3, w = 2, h = 2, c = body, dy = breath)

    // Eyes, per state.
    val b = breath
    when {
        state == MascotState.Offline -> {
            px(5, 4, w = 2, c = eye, dy = b)
            px(9, 4, w = 2, c = eye, dy = b)
        }
        state == MascotState.Error -> {
            for (ox in listOf(4, 9)) {
                px(ox, 2, c = eye, dy = b); px(ox + 2, 2, c = eye, dy = b)
                px(ox + 1, 3, c = eye, dy = b)
                px(ox, 4, c = eye, dy = b); px(ox + 2, 4, c = eye, dy = b)
            }
        }
        state == MascotState.Done -> {
            for (ox in listOf(3, 9)) {
                px(ox, 4, c = eye, dy = b)
                px(ox + 1, 5, c = eye, dy = b)
                px(ox + 2, 4, c = eye, dy = b)
                px(ox + 3, 3, c = eye, dy = b)
            }
        }
        state == MascotState.Waiting -> {
            for (ox in listOf(5, 9)) {
                px(ox, 1, w = 2, h = 3, c = eye, dy = b)
                px(ox, 5, w = 2, h = 1, c = eye, dy = b)
            }
        }
        blinking -> {
            px(5, 4, w = 2, c = eye, dy = b)
            px(9, 4, w = 2, c = eye, dy = b)
        }
        else -> {
            px(5, 2, w = 2, h = 3, c = eye, dy = b)
            px(9, 2, w = 2, h = 3, c = eye, dy = b)
        }
    }
}
