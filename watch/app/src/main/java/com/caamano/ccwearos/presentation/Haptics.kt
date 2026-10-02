package com.caamano.ccwearos.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * One haptic vocabulary for the whole app, so each state can be told apart
 * blind. Uses Vibrator directly instead of View.performHapticFeedback, which
 * is unreliable right after an FCM wake. Every pattern falls back to a plain
 * waveform when the device lacks composition primitives.
 *
 * | Function      | Moment                                   | Feel                          | Priority |
 * |---------------|------------------------------------------|-------------------------------|----------|
 * | [swipe]       | Page / pager change                      | barely-there low tick         | 0        |
 * | [holdStep]    | Hold-to-confirm, every 25%               | light tick                    | 1        |
 * | [tick]        | Any press, deny, light acknowledgement   | crisp single tick             | 1        |
 * | [listening]   | Dictation starts (mic opens)             | slow swell ("te escucho")     | 2        |
 * | [sent]        | The Mac picked up the prompt             | soft-then-firm double tick    | 2        |
 * | [done]        | Run finished OK                          | rise, then a satisfying click | 3        |
 * | [error]       | Something failed                         | two low thuds, spaced         | 3        |
 * | [permission]  | Claude needs a decision (strongest)      | click, click, heavy thud      | 4        |
 *
 * Global rate limit: no two haptics within [MIN_GAP_MS]; inside the window a
 * strictly higher-priority pattern still plays (a permission never gets
 * swallowed by the tick that preceded it). See [HapticGate].
 */
object Haptics {
    /** Two buzzes closer than this blur into mush on the wrist. */
    const val MIN_GAP_MS = 60L

    private val gate = HapticGate(MIN_GAP_MS)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** One composition primitive: id, intensity 0..1, delay before it in ms. */
    private class Prim(val id: Int, val scale: Float, val delayMs: Int = 0)

    // Primitive ids lose the @PrimitiveType IntDef inside Prim; every caller
    // passes Composition.PRIMITIVE_* only. LOW_TICK / SLOW_RISE are API 31
    // constants (inlined ints); unsupported devices take the fallback.
    @SuppressLint("WrongConstant", "InlinedApi")
    private fun play(
        context: Context,
        priority: Int,
        primitives: List<Prim>,
        fallback: LongArray,
    ) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        if (!gate.tryAcquire(SystemClock.uptimeMillis(), priority)) return
        val ids = primitives.map { it.id }.toIntArray()
        if (v.areAllPrimitivesSupported(*ids)) {
            val c = VibrationEffect.startComposition()
            primitives.forEach { c.addPrimitive(it.id, it.scale, it.delayMs) }
            v.vibrate(c.compose())
        } else {
            v.vibrate(VibrationEffect.createWaveform(fallback, -1))
        }
    }

    /** Claude needs a decision: click, click, heavy thud. The strongest pattern. */
    fun permission(context: Context) = play(
        context,
        priority = 4,
        listOf(
            Prim(Composition.PRIMITIVE_CLICK, 1f),
            Prim(Composition.PRIMITIVE_CLICK, 1f, 70),
            Prim(Composition.PRIMITIVE_THUD, 1f, 90),
        ),
        longArrayOf(0, 40, 80, 40, 90, 180),
    )

    /** Run finished: a quick rise that lands on a click. */
    fun done(context: Context) = play(
        context,
        priority = 3,
        listOf(
            Prim(Composition.PRIMITIVE_QUICK_RISE, 0.7f),
            Prim(Composition.PRIMITIVE_CLICK, 0.9f, 40),
        ),
        longArrayOf(0, 60, 50, 30),
    )

    /** Something failed: two low thuds, spaced so they read as two. */
    fun error(context: Context) = play(
        context,
        priority = 3,
        listOf(
            Prim(Composition.PRIMITIVE_THUD, 0.8f),
            Prim(Composition.PRIMITIVE_THUD, 0.8f, 120),
        ),
        longArrayOf(0, 110, 120, 110),
    )

    /** Press / deny / light acknowledgement: a single crisp tick. */
    fun tick(context: Context) = play(
        context,
        priority = 1,
        listOf(Prim(Composition.PRIMITIVE_TICK, 0.7f)),
        longArrayOf(0, 20),
    )

    /** The Mac picked up the prompt: soft then firm double tick ("recibido"). */
    fun sent(context: Context) = play(
        context,
        priority = 2,
        listOf(
            Prim(Composition.PRIMITIVE_TICK, 0.5f),
            Prim(Composition.PRIMITIVE_TICK, 1f, 60),
        ),
        longArrayOf(0, 15, 60, 25),
    )

    /** Hold-to-confirm progress step (fired every 25% of the hold). */
    fun holdStep(context: Context) = play(
        context,
        priority = 1,
        listOf(Prim(Composition.PRIMITIVE_TICK, 0.5f)),
        longArrayOf(0, 12),
    )

    /**
     * Hold-to-confirm step that firms up as the hold progresses
     * ([fraction] 0..1), so the wrist feels the hold approaching commit.
     */
    fun holdStep(context: Context, fraction: Float) = play(
        context,
        priority = 1,
        listOf(Prim(Composition.PRIMITIVE_TICK, 0.35f + 0.5f * fraction.coerceIn(0f, 1f))),
        longArrayOf(0, 12),
    )

    /** Dictation started, the mic is open: a slow swell. */
    @SuppressLint("InlinedApi")
    fun listening(context: Context) = play(
        context,
        priority = 2,
        listOf(Prim(Composition.PRIMITIVE_SLOW_RISE, 0.5f)),
        longArrayOf(0, 90),
    )

    /** Page change: very light, felt only if you're paying attention. */
    @SuppressLint("InlinedApi")
    fun swipe(context: Context) = play(
        context,
        priority = 0,
        listOf(Prim(Composition.PRIMITIVE_LOW_TICK, 0.4f)),
        longArrayOf(0, 8),
    )
}

/**
 * Global haptic rate limiter. A pattern plays when at least [minGapMs] passed
 * since the last one, or when it outranks the last one (higher priority
 * interrupts). Thread-safe; pure so it's unit-testable.
 */
class HapticGate(private val minGapMs: Long) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastPriority = Int.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long, priority: Int): Boolean {
        val ok = nowMs - lastAt >= minGapMs || priority > lastPriority
        if (ok) {
            lastAt = nowMs
            lastPriority = priority
        }
        return ok
    }
}
