package com.caamano.ccwearos.presentation.home

/**
 * One error haptic per blocked run. A crashed or trust-blocked run produces
 * both a /blocker and a failed /outcome, in either order and close together;
 * without this the wrist felt two or three error buzzes for one problem.
 *
 * Rules:
 *  - each distinct blocker ts buzzes at most once;
 *  - each distinct failed outcome ts buzzes at most once, and never while a
 *    blocker is present (the blocker screen carries the failure);
 *  - two error buzzes closer than [windowMs] collapse into the first, which
 *    covers the outcome-then-blocker order.
 *
 * Pure and clock-injected so it is unit-tested (ErrorBuzzDedupeTest).
 */
internal class ErrorBuzzDedupe(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var lastBlockerTs: Long? = null
    private var lastOutcomeTs: Long? = null
    private var lastBuzzAt: Long? = null

    /** A blocker with [ts] is on screen. True = play the error haptic now. */
    fun onBlocker(ts: Long, nowMs: Long): Boolean {
        if (ts == lastBlockerTs) return false
        lastBlockerTs = ts
        return buzz(nowMs)
    }

    /** A run failed with outcome [ts]. True = play the error haptic now. */
    fun onFailedOutcome(ts: Long, blockerPresent: Boolean, nowMs: Long): Boolean {
        if (ts == lastOutcomeTs) return false
        lastOutcomeTs = ts
        // The blocker screen owns this failure (its own buzz, once).
        if (blockerPresent) return false
        return buzz(nowMs)
    }

    private fun buzz(nowMs: Long): Boolean {
        val last = lastBuzzAt
        if (last != null && nowMs - last < windowMs) return false
        lastBuzzAt = nowMs
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
    }
}
