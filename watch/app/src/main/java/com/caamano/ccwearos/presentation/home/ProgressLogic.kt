package com.caamano.ccwearos.presentation.home

// Pure, Android-free logic for the long-task experience.
// All functions here are unit-tested in ProgressLogicTest.kt.
//
// Design contract:
//   - Every function is total: never throws, whatever input it receives.
//   - No Android imports: runs in any JVM test without an emulator.
//   - Time is always passed in (nowMs / ageSec), never read from the clock.

// ─── Liveness ────────────────────────────────────────────────────────────────

/** Whether Claude's signal is fresh, going quiet, or gone. */
enum class LivenessState { FRESH, STALE, VERY_STALE }

/** Fresh: mascot + halo run normally. */
const val LIVENESS_FRESH_MS = 30_000L

/** Soft stale: still calm, but halo dims slightly. No message yet. */
const val LIVENESS_STALE_SOFT_MS = 90_000L

/**
 * Hard stale: "Sin señales de Claude hace N min" appears and Detener/Reiniciar
 * surfaces without a tap. Replaces the blind 3-min timer when
 * RunProgress.lastEventAt is available.
 */
const val LIVENESS_STALE_HARD_MS = 3 * 60_000L

/**
 * Liveness relative to [nowMs].
 * When [lastEventAtMs] is 0 (progress absent / unknown) the function returns
 * FRESH so callers fall back to the existing timer-based stale logic.
 */
fun livenessState(lastEventAtMs: Long, nowMs: Long): LivenessState {
    if (lastEventAtMs <= 0L) return LivenessState.FRESH
    val age = (nowMs - lastEventAtMs).coerceAtLeast(0L)
    return when {
        age < LIVENESS_STALE_SOFT_MS -> LivenessState.FRESH
        age < LIVENESS_STALE_HARD_MS -> LivenessState.STALE
        else -> LivenessState.VERY_STALE
    }
}

/**
 * Minutes since the last liveness signal.
 * Returns 0 when [lastEventAtMs] is zero (no signal known).
 * Always at least 1 when the signal is stale enough to display.
 */
fun signalAbsentMinutes(lastEventAtMs: Long, nowMs: Long): Int {
    if (lastEventAtMs <= 0L) return 0
    val age = (nowMs - lastEventAtMs).coerceAtLeast(0L)
    return (age / 60_000L).toInt().coerceAtLeast(1)
}

// ─── Step meta line ──────────────────────────────────────────────────────────

/**
 * One-line summary of the current step: "Paso 3 · Buscando archivos · ~/Docs"
 * Returns null when step == 0 (thinking, before first tool call) or label is blank.
 */
fun stepMetaLine(step: Long, label: String, detail: String?): String? {
    if (step <= 0L || label.isBlank()) return null
    val suffix = detail?.trimEnd('/')?.takeIf { it.isNotBlank() }
    return if (suffix == null) "Paso $step · $label" else "Paso $step · $label · $suffix"
}

// ─── Long-step explanation ───────────────────────────────────────────────────

/** How long a step must run before we explain why. */
const val LONG_STEP_EXPLAIN_MS = 20_000L

/** How long before we add "Puedes bajar la muñeca". */
const val LONG_STEP_WRIST_MS = 60_000L

/** The reason a step is slow, mapped from its label. */
enum class StepReason { FILES, COMMAND, WEB, THINKING, GENERIC }

/** What the long-step line should say, and whether to add the wrist hint. */
data class StepWhy(val reason: StepReason, val wristDown: Boolean)

/**
 * Explanation for a slow step, or null when it is not slow yet.
 * [label] is the RunProgress.label (e.g. "Buscando archivos", "Corriendo un comando").
 * [stepAgeMs] is how long the current step has been running.
 */
fun stepWhyCopy(label: String, stepAgeMs: Long): StepWhy? {
    if (stepAgeMs < LONG_STEP_EXPLAIN_MS) return null
    val l = label.lowercase()
    val reason = when {
        // WEB checked before FILES because "Buscando en la web" is a web step,
        // not a file step — both contain "busca" so order matters.
        l.contains("internet") || l.contains("la web") || l.contains("webfetch") ||
            l.contains("fetch") -> StepReason.WEB
        l.contains("corriendo") || l.contains("comando") || l.contains("proyecto") ||
            l.contains("ejecu") -> StepReason.COMMAND
        l.contains("buscan") || l.contains("leyend") || l.contains("archiv") ||
            l.contains("grep") || l.contains("busca") -> StepReason.FILES
        l.contains("pensand") -> StepReason.THINKING
        else -> StepReason.GENERIC
    }
    return StepWhy(reason, wristDown = stepAgeMs >= LONG_STEP_WRIST_MS)
}

// ─── Recap on wrist raise ────────────────────────────────────────────────────

/**
 * Text shown for a few seconds when the user raises the wrist after steps
 * completed while the screen was off.
 * [newSteps] is current step minus the step count when the wrist went down.
 * Returns null when nothing new happened (newSteps <= 0).
 */
fun recapText(newSteps: Int): String? {
    if (newSteps <= 0) return null
    return "Mientras no mirabas: $newSteps paso${if (newSteps == 1) " más" else "s más"}"
}

// ─── Cancel confirm guard ────────────────────────────────────────────────────

/**
 * True when the run is long enough that a cancel should ask for confirmation
 * before firing Detener. Short runs (<10s) cancel immediately.
 */
const val STOP_CONFIRM_THRESHOLD_MS = 10_000L

fun requiresStopConfirm(runElapsedMs: Long): Boolean = runElapsedMs >= STOP_CONFIRM_THRESHOLD_MS
