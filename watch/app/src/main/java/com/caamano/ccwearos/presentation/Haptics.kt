package com.caamano.ccwearos.presentation

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// One haptic vocabulary for the whole app, so each state can be told apart
// without looking at the watch. Uses Vibrator directly instead of
// View.performHapticFeedback, which is unreliable right after an FCM wake.
// Every pattern falls back to a plain waveform when the device doesn't
// support composition primitives.
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    private fun play(
        context: Context,
        primitives: List<Pair<Int, Float>>,
        fallback: LongArray,
    ) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        val ids = primitives.map { it.first }.toIntArray()
        if (v.areAllPrimitivesSupported(*ids)) {
            val c = VibrationEffect.startComposition()
            primitives.forEachIndexed { i, (id, scale) -> c.addPrimitive(id, scale, if (i == 0) 0 else 70) }
            v.vibrate(c.compose())
        } else {
            v.vibrate(VibrationEffect.createWaveform(fallback, -1))
        }
    }

    /** Claude needs a decision: click, click, thud. */
    fun permission(context: Context) = play(
        context,
        listOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK to 1f,
            VibrationEffect.Composition.PRIMITIVE_CLICK to 1f,
            VibrationEffect.Composition.PRIMITIVE_THUD to 1f,
        ),
        longArrayOf(0, 40, 80, 40, 80, 160),
    )

    /** Run finished: a quick rise. */
    fun done(context: Context) = play(
        context,
        listOf(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.8f),
        longArrayOf(0, 60, 60, 120),
    )

    /** Something failed: two low thuds. */
    fun error(context: Context) = play(
        context,
        listOf(
            VibrationEffect.Composition.PRIMITIVE_THUD to 0.9f,
            VibrationEffect.Composition.PRIMITIVE_THUD to 0.9f,
        ),
        longArrayOf(0, 120, 100, 120),
    )

    /** User denied / light acknowledgement: a single tick. */
    fun tick(context: Context) = play(
        context,
        listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.7f),
        longArrayOf(0, 20),
    )

    /** Hold-to-confirm progress step (fired every 25% of the hold). */
    fun holdStep(context: Context) = play(
        context,
        listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.5f),
        longArrayOf(0, 12),
    )
}
