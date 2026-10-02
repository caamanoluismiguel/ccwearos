package com.caamano.ccwearos.presentation.ui

import android.animation.ValueAnimator
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.floor
import kotlin.random.Random

/**
 * What the mascot is telling you. The mascot is the single living status
 * indicator on the watch, so every state has its own motion, eyes and (where
 * it helps) a small pixel prop:
 *
 *  - Idle:    slow 4s breathe (1px), blink every ~6s (randomised, sometimes double).
 *  - Sending: leans forward, signal arcs pulse outward above the head, 3 beats.
 *  - Running: 4-frame walk (one step per 300ms) with pixel dust behind.
 *  - Waiting: 2px hop loop, amber "!" above the head, eyes on you (catchlight).
 *  - Done:    two hops with pixel confetti popping around it, ✓ eyes, then
 *             settles back into Idle motion.
 *  - Error:   X eyes, one damped shake (3 oscillations, 8dp max).
 *  - Blocked: calm. Looks up at a small laptop with a "?", glances back at you.
 *  - Offline: grey, still, eyes closed, a "z" drifts up every few seconds.
 *
 * State changes crossfade (no hard cut). With reduced motion every state is a
 * single static frame that still reads correctly (✓, X, !, ?, z stay drawn).
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

/** True when the user turned animations off (animator duration scale = 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val inPreview = LocalInspectionMode.current
    return remember { inPreview || !ValueAnimator.areAnimatorsEnabled() }
}

/**
 * The pixel mascot. [size] is the footprint WIDTH; the height is
 * `size * 18 / 16` because the top 7 grid rows are headroom for hops and the
 * "!", "?", signal and "z" props, so nothing ever clips.
 *
 * [isDrowsy]: time-of-day mood (hour 22-06). In Idle, blinks are slower and
 * heavier — the mascot is half-asleep. Ignored under reduced motion.
 */
@Composable
fun PixelMascot(
    state: MascotState,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color? = null,
    animate: Boolean = true,
    isDrowsy: Boolean = false,
) {
    val reducedMotion = rememberReducedMotion()
    val shouldAnimate = animate && !reducedMotion
    val description = stringResource(R.string.mascot_cd, stringResource(state.labelRes()))

    Box(
        modifier = modifier
            .size(width = size, height = size * MascotGrid.ROWS / MascotGrid.COLS)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Crossfade(
            targetState = state,
            animationSpec = tween(if (shouldAnimate) Motion.MEDIUM else 0, easing = Motion.StandardEasing),
            label = "mascot-state",
        ) { s ->
            MascotSprite(state = s, color = color, animate = shouldAnimate, isDrowsy = isDrowsy)
        }
    }
}

@Composable
private fun MascotSprite(state: MascotState, color: Color?, animate: Boolean, isDrowsy: Boolean = false) {
    var frame by remember(state) { mutableStateOf(MascotFrame.resting(state, animate)) }
    val shake = remember(state) { Animatable(0f) }
    // Screen off / app hidden: the loop is cancelled, not left ticking.
    val resumed = rememberIsResumed()

    LaunchedEffect(state, animate, resumed) {
        if (!animate || !resumed) return@LaunchedEffect
        runMascotLoop(state, update = { frame = it(frame) }, shake = shake, isDrowsy = isDrowsy)
    }

    val body = color ?: if (state == MascotState.Offline) StatusColors.offline else CcPalette.Coral
    val accent = if (state == MascotState.Waiting) StatusColors.waiting else body

    Canvas(Modifier.fillMaxSize()) {
        // Snap the grid pixel to whole device pixels so edges stay crisp
        // (nearest-neighbour feel), and centre the sprite in the footprint.
        val raw = size.width / MascotGrid.COLS
        val p = if (raw >= 1f) floor(raw) else raw
        val ox = (size.width - p * MascotGrid.COLS) / 2f + shake.value * density
        val oy = (size.height - p * MascotGrid.ROWS) / 2f
        for (px in mascotPixels(state, frame)) {
            val c = when (px.ink) {
                Ink.Body -> body
                Ink.Eye -> CcPalette.Black
                Ink.Glint -> CcPalette.TextPrimary
                Ink.Accent -> accent
                Ink.Muted -> CcPalette.TextSecondary
            }
            drawRect(
                color = c,
                topLeft = Offset(ox + px.x * p, oy + px.y * p),
                size = Size(px.w * p, px.h * p),
            )
        }
    }
}

/** Drives [MascotFrame] for one state. Pixel-stepped: values change in whole grid pixels. */
private suspend fun runMascotLoop(
    state: MascotState,
    update: ((MascotFrame) -> MascotFrame) -> Unit,
    shake: Animatable<Float, *>,
    isDrowsy: Boolean = false,
) = coroutineScope {
    // Blinks are shared by every state whose eyes are open.
    fun launchBlinks(minMs: Long = 5_000, maxMs: Long = 7_000) = launch {
        while (true) {
            delay(Random.nextLong(minMs, maxMs))
            update { it.copy(blink = true) }; delay(130)
            update { it.copy(blink = false) }
            if (Random.nextInt(4) == 0) { // occasional double blink
                delay(160)
                update { it.copy(blink = true) }; delay(110)
                update { it.copy(blink = false) }
            }
        }
    }
    fun launchBreath() = launch {
        while (true) {
            delay(2_000); update { it.copy(breath = 1) }
            delay(2_000); update { it.copy(breath = 0) }
        }
    }
    // KAI: Drowsy blinks — slower interval, longer eye-closed hold (heavy lids).
    // Hour 22-06: the mascot is half-asleep. Re-droops are more frequent.
    fun launchDrowsy() = launch {
        while (true) {
            delay(Random.nextLong(3_000, 5_000))
            update { it.copy(blink = true) }; delay(220)      // slow close
            update { it.copy(blink = false) }
            if (Random.nextInt(2) == 0) {                      // drowsy re-droop
                delay(200)
                update { it.copy(blink = true) }; delay(300)
                update { it.copy(blink = false) }
            }
        }
    }

    when (state) {
        MascotState.Idle -> {
            // NOVA: Wrist-raise greeting — double-blink "I see you". Fires every
            // time the loop restarts (screen-on / resume). Two blinks in ~400ms,
            // then the normal idle loop, so it reads as a greeting, not a tic.
            update { it.copy(blink = true) }; delay(100)
            update { it.copy(blink = false) }; delay(120)
            update { it.copy(blink = true) }; delay(80)
            update { it.copy(blink = false) }

            launchBreath()
            if (isDrowsy) launchDrowsy() else launchBlinks()

            // KAI: Micro-idle look-sideways. Longer gaps when awake (no twitching),
            // shorter + longer hold when drowsy (tired gaze wanders and lingers).
            launch {
                while (true) {
                    delay(
                        Random.nextLong(
                            if (isDrowsy) 15_000 else 30_000,
                            if (isDrowsy) 45_000 else 80_000,
                        ),
                    )
                    update { it.copy(gaze = 1) }
                    delay(if (isDrowsy) 1_500 else 1_000)
                    update { it.copy(gaze = 0) }
                }
            }
        }

        MascotState.Sending -> {
            update { it.copy(lean = 1, beat = -1) }
            launchBlinks()
            launch {
                while (true) {
                    for (b in 0..2) {
                        update { it.copy(beat = b) }; delay(180)
                    }
                    update { it.copy(beat = -1) }; delay(620)
                }
            }
        }

        MascotState.Running -> launch {
            var f = 0
            while (true) {
                update { it.copy(walkFrame = f, lift = if (f % 2 == 1) -1 else 0, dust = f) }
                delay(150)
                f = (f + 1) % 4
            }
        }

        MascotState.Waiting -> {
            launchBlinks(4_000, 6_000)
            launch {
                while (true) {
                    update { it.copy(lift = -1) }; delay(70)
                    update { it.copy(lift = -2) }; delay(160)
                    update { it.copy(lift = -1) }; delay(70)
                    update { it.copy(lift = 0) }; delay(700)
                }
            }
        }

        MascotState.Done -> {
            // Big hop, confetti bursts outward, then a smaller second hop.
            update { it.copy(lift = -1, confetti = 0) }; delay(60)
            update { it.copy(lift = -3, confetti = 1) }; delay(180)
            update { it.copy(lift = -1, confetti = 2) }; delay(60)
            update { it.copy(lift = 0) }; delay(140)
            update { it.copy(lift = -2, confetti = -1) }; delay(120)
            update { it.copy(lift = 0) }
            delay(DONE_CELEBRATE_MS - 560)
            update { it.copy(celebrating = false) }
            launchBreath(); launchBlinks()
        }

        MascotState.Error -> {
            // 3 damped oscillations, 8dp max, ~360ms. Value is in dp.
            val keys = floatArrayOf(8f, -8f, 5f, -5f, 2f, -2f, 0f)
            for (k in keys) shake.animateTo(k, tween(durationMillis = 50))
        }

        MascotState.Blocked -> {
            launchBlinks(6_000, 8_000)
            launch {
                while (true) {
                    delay(Random.nextLong(4_000, 6_000))
                    update { it.copy(gaze = 0) }; delay(1_000) // glance back at you
                    update { it.copy(gaze = 1) }
                }
            }
        }

        MascotState.Offline -> launch {
            while (true) {
                delay(2_600)
                for (z in 0..2) {
                    update { it.copy(zzz = z) }; delay(500)
                }
                update { it.copy(zzz = -1) }
            }
        }
    }
}

internal const val DONE_CELEBRATE_MS = 1_200L

/** Confetti positions per burst phase, inside the 16×18 grid (headroom + sides). */
private val CONFETTI: List<List<Pair<Int, Int>>> = listOf(
    listOf(1 to 6, 14 to 6, 3 to 3, 12 to 3),
    listOf(0 to 4, 15 to 4, 2 to 1, 13 to 1, 7 to 0),
    listOf(0 to 1, 15 to 1, 4 to 0, 11 to 0, 1 to 9, 14 to 9),
)

// ─── Pure sprite model (unit-tested in MascotSpriteTest) ────────────────────

internal object MascotGrid {
    const val COLS = 16
    const val ROWS = 18

    /** Rows above the body for hops (≤3) and props ("!", "?", signal, z). */
    const val HEADROOM = 7
}

internal enum class Ink { Body, Eye, Glint, Accent, Muted }

/** One lit rectangle in absolute grid coordinates. */
internal data class MascotPixel(val x: Int, val y: Int, val w: Int, val h: Int, val ink: Ink)

/**
 * One animation frame. All offsets are in grid pixels.
 *
 * @param lift whole-sprite vertical offset (negative = up).
 * @param breath body-only sink (breathing onto the legs).
 * @param lean head/arms shifted right (Sending leans in).
 * @param gaze eyes shifted right (1) or centred on you (0).
 * @param celebrating Done shows ✓ eyes while true.
 * @param beat Sending signal arc index 0..2, -1 none.
 * @param dust Running dust puff phase 0..3, -1 none.
 * @param zzz Offline "z" phase 0..2, -1 none.
 * @param confetti Done confetti burst phase 0..2 (outward), -1 none.
 * @param allSignal reduced-motion Sending: draw every arc at once.
 */
internal data class MascotFrame(
    val lift: Int = 0,
    val breath: Int = 0,
    val lean: Int = 0,
    val walkFrame: Int = 0,
    val blink: Boolean = false,
    val gaze: Int = 0,
    val celebrating: Boolean = true,
    val beat: Int = -1,
    val dust: Int = -1,
    val zzz: Int = -1,
    val allSignal: Boolean = false,
    val confetti: Int = -1,
) {
    companion object {
        /** First frame of a state; when [animate] is false it's the static readable pose. */
        fun resting(state: MascotState, animate: Boolean): MascotFrame = when (state) {
            MascotState.Sending -> MascotFrame(lean = 1, allSignal = !animate)
            MascotState.Running -> MascotFrame(gaze = 1, dust = if (animate) -1 else 0)
            MascotState.Blocked -> MascotFrame(gaze = 1)
            MascotState.Offline -> MascotFrame(zzz = if (animate) -1 else 1)
            // Still frame keeps one confetti ring so "done" reads without motion.
            MascotState.Done -> MascotFrame(confetti = if (animate) -1 else 1)
            else -> MascotFrame()
        }
    }
}

internal fun mascotPixels(state: MascotState, f: MascotFrame): List<MascotPixel> {
    val out = ArrayList<MascotPixel>(48)
    val top = MascotGrid.HEADROOM

    // Sprite-relative pixel: rides lift (and breath / lean when asked).
    fun sprite(x: Int, y: Int, w: Int = 1, h: Int = 1, ink: Ink = Ink.Body, dx: Int = 0, dy: Int = 0) {
        out += MascotPixel(x + dx, top + f.lift + y + dy, w, h, ink)
    }
    // Absolute pixel: props pinned to the canvas (ground dust, "!", "?", z).
    fun fixed(x: Int, y: Int, w: Int = 1, h: Int = 1, ink: Ink) {
        out += MascotPixel(x, y, w, h, ink)
    }

    val b = f.breath
    val lean = f.lean

    // Legs (4). Walking cycles: A raised, passing, B raised, passing.
    val legs = intArrayOf(3, 5, 10, 12)
    legs.forEachIndexed { i, x ->
        val raised = state == MascotState.Running &&
            ((f.walkFrame == 0 && i % 2 == 0) || (f.walkFrame == 2 && i % 2 == 1))
        sprite(x, 8, h = if (raised) 2 else 3)
    }

    // Body: the top rows (head) carry the lean; shoulders, arms and hips stay
    // planted so the sprite never leaves the 16-column grid.
    sprite(2, 0, w = 12, h = 3, dx = lean, dy = b)
    sprite(2, 3, w = 12, h = 5, dy = b)
    sprite(0, 3, w = 2, h = 2, dy = b)
    sprite(14, 3, w = 2, h = 2, dy = b)

    // Eyes.
    val ex = lean + f.gaze
    fun openEyes(h: Int = 3, y: Int = 2) {
        sprite(5, y, w = 2, h = h, ink = Ink.Eye, dx = ex, dy = b)
        sprite(9, y, w = 2, h = h, ink = Ink.Eye, dx = ex, dy = b)
    }
    fun closedEyes() {
        sprite(5, 4, w = 2, ink = Ink.Eye, dx = ex, dy = b)
        sprite(9, 4, w = 2, ink = Ink.Eye, dx = ex, dy = b)
    }
    when (state) {
        MascotState.Offline -> closedEyes()
        MascotState.Error -> for (ox in intArrayOf(4, 9)) {
            sprite(ox, 2, ink = Ink.Eye, dy = b); sprite(ox + 2, 2, ink = Ink.Eye, dy = b)
            sprite(ox + 1, 3, ink = Ink.Eye, dy = b)
            sprite(ox, 4, ink = Ink.Eye, dy = b); sprite(ox + 2, 4, ink = Ink.Eye, dy = b)
        }
        MascotState.Done -> if (f.celebrating) {
            for (ox in intArrayOf(3, 9)) { // ✓ ✓
                sprite(ox, 4, ink = Ink.Eye, dy = b)
                sprite(ox + 1, 5, ink = Ink.Eye, dy = b)
                sprite(ox + 2, 4, ink = Ink.Eye, dy = b)
                sprite(ox + 3, 3, ink = Ink.Eye, dy = b)
            }
        } else if (f.blink) closedEyes() else openEyes()
        MascotState.Waiting -> if (f.blink) closedEyes() else {
            openEyes()
            // Catchlights: eyes on you.
            sprite(5, 2, ink = Ink.Glint, dy = b)
            sprite(9, 2, ink = Ink.Glint, dy = b)
        }
        MascotState.Sending -> if (f.blink) closedEyes() else openEyes(h = 2, y = 3) // focused squint
        MascotState.Blocked -> if (f.blink) closedEyes() else openEyes(h = 2, y = if (f.gaze > 0) 1 else 2)
        else -> if (f.blink) closedEyes() else openEyes()
    }

    // Props.
    when (state) {
        MascotState.Sending -> {
            val arcs = if (f.allSignal) 0..2 else if (f.beat >= 0) f.beat..f.beat else IntRange.EMPTY
            for (a in arcs) when (a) {
                0 -> sprite(7, -2, w = 2, ink = Ink.Accent, dx = lean)
                1 -> {
                    sprite(5, -3, ink = Ink.Accent, dx = lean)
                    sprite(6, -4, w = 4, ink = Ink.Accent, dx = lean)
                    sprite(10, -3, ink = Ink.Accent, dx = lean)
                }
                2 -> {
                    sprite(3, -5, ink = Ink.Accent, dx = lean)
                    sprite(4, -6, ink = Ink.Accent, dx = lean)
                    sprite(5, -7, w = 6, ink = Ink.Accent, dx = lean)
                    sprite(11, -6, ink = Ink.Accent, dx = lean)
                    sprite(12, -5, ink = Ink.Accent, dx = lean)
                }
            }
        }
        MascotState.Running -> if (f.dust >= 0) {
            val ground = top + 10
            if (f.dust % 2 == 0) fixed(1, ground, ink = Ink.Muted) else fixed(0, ground - 1, ink = Ink.Muted)
        }
        MascotState.Waiting -> { // "!" pinned above the head; clears a 2px hop.
            fixed(7, 0, w = 2, h = 3, ink = Ink.Accent)
            fixed(7, 4, w = 2, ink = Ink.Accent)
        }
        MascotState.Blocked -> {
            // "?" (3×5), upper left.
            fixed(2, 0, w = 3, ink = Ink.Muted)
            fixed(4, 1, ink = Ink.Muted)
            fixed(3, 2, w = 2, ink = Ink.Muted)
            fixed(3, 4, ink = Ink.Muted)
            // Tiny laptop, upper right: screen + base.
            fixed(11, 2, w = 4, h = 3, ink = Ink.Muted)
            fixed(12, 3, w = 2, ink = Ink.Eye) // dark screen face
            fixed(10, 5, w = 6, ink = Ink.Muted)
        }
        MascotState.Done -> if (f.confetti >= 0) {
            // Solid pixels, alternating coral and white, flying outward per phase.
            val ring = CONFETTI[f.confetti.coerceIn(0, CONFETTI.size - 1)]
            ring.forEachIndexed { i, (x, y) -> fixed(x, y, ink = if (i % 2 == 0) Ink.Accent else Ink.Glint) }
        }
        MascotState.Offline -> if (f.zzz >= 0) {
            val zx = 11 + f.zzz
            val zy = 4 - f.zzz * 2
            fixed(zx, zy, w = 3, ink = Ink.Muted)
            fixed(zx + 1, zy + 1, ink = Ink.Muted)
            fixed(zx, zy + 2, w = 3, ink = Ink.Muted)
        }
        else -> Unit
    }
    return out
}
