package com.caamano.ccwearos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which permission prompt id was already answered, so a prompt is
 * answered at most once no matter where the tap comes from (PermissionScreen
 * double tap, or the screen plus the notification action).
 *
 * The process-wide instance lives in [AnswerGate.Companion.shared]; tests build
 * their own.
 */
class AnswerGate {
    private val state = MutableStateFlow<String?>(null)

    /** Id of the last prompt answered from this process, or null. */
    val answeredId: StateFlow<String?> = state.asStateFlow()

    /** True exactly once per id: the caller now owns the answer for it. */
    fun tryClaim(id: String): Boolean {
        while (true) {
            val prev = state.value
            if (prev == id) return false
            if (state.compareAndSet(prev, id)) return true
        }
    }

    /** Undo a claim whose write failed, so the user can try again. */
    fun release(id: String) {
        state.compareAndSet(id, null)
    }

    companion object {
        val shared = AnswerGate()
    }
}

/** Whether MainActivity is started (visible). Set from onStart / onStop. */
object AppVisibility {
    val foreground = MutableStateFlow(false)
}
