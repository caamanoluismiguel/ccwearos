package com.caamano.ccwearos.presentation.home

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.WrapperStatus

/**
 * Fires once per finished run, judged by the wrapper's real exit status
 * (/outcome), never by regexing the response text.
 *
 * A run "finishes" when, after we saw it working (RUNNING / AWAITING, or a
 * prompt we sent was picked up), status is IDLE and /outcome holds a value
 * different from the one present when the run started. The wrapper may write
 * /outcome just before or just after flipping to IDLE; both orders fire once.
 *
 * Cold open: an old /outcome cached in RTDB never fires, because nothing was
 * seen working. That is what keeps an old result from looking fresh.
 */
internal class OutcomeCompletionDetector {
    private var armed = false
    private var outcomeAtStart: RunOutcome? = null

    /** A prompt we sent was picked up; treat the run as started. */
    fun arm(currentOutcome: RunOutcome?) {
        if (!armed) {
            armed = true
            outcomeAtStart = currentOutcome
        }
    }

    /** Returns the finished outcome, or null when this update finishes nothing. */
    fun onUpdate(status: WrapperStatus, outcome: RunOutcome?): RunOutcome? {
        val working = status == WrapperStatus.RUNNING || status == WrapperStatus.AWAITING_PERMISSION
        if (working) {
            arm(outcome)
            return null
        }
        if (!armed) return null
        if (status == WrapperStatus.OFFLINE) {
            // Wrapper died mid-run: no outcome is coming. Disarm silently; the
            // MAC_OFFLINE screen tells the story.
            armed = false
            return null
        }
        if (status == WrapperStatus.IDLE && outcome != null && outcome != outcomeAtStart) {
            armed = false
            outcomeAtStart = outcome
            return outcome
        }
        return null
    }
}
