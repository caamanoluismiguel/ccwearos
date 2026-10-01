package com.caamano.ccwearos.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.ClaimResult
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WatchRepository
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// User-visible error copy (Spanish, tuteo).
internal object ErrorCopy {
    const val OFFLINE = "Sin conexión. Espera a que el reloj se reconecte."
    const val PROMPT_GONE = "Esa solicitud ya no está activa."
    const val ANSWER_FAILED = "No se pudo enviar tu respuesta. Intenta de nuevo."
    const val STOP_FAILED = "No se pudo detener la tarea. Intenta de nuevo."
    const val RESET_FAILED = "No se pudo reiniciar la pantalla. Intenta de nuevo."
    const val PROMPT_FAILED = "No se pudo enviar tu mensaje. Intenta de nuevo."
    const val CLAIM_FAILED = "No se pudo abrir la sesión. Intenta de nuevo."
}

class CcwearosViewModel(
    private val repo: WatchRepository = CcwearosRepository(),
    private val answers: AnswerGate = AnswerGate.shared,
) : ViewModel() {

    // ROUTING-CRITICAL flows use SharingStarted.Eagerly: the listener stays
    // alive even when no UI is collecting (i.e., screen off / ambient). Cost
    // is one Firebase value listener kept warm; benefit is no "wrapper not
    // reachable" flicker on wake — observed twice in 24h on real watch with
    // WhileSubscribed(5_000). These drive screen routing in WearApp and the
    // allow/deny guards below, so a fresh value on wake matters more than the
    // tiny battery cost of an idle listener.
    val status: StateFlow<WrapperStatus> = repo.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, WrapperStatus.OFFLINE)

    val permissionPrompt: StateFlow<String?> = repo.permissionPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val permissionPromptId: StateFlow<String?> = repo.permissionPromptId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live Firebase socket state. Allow/deny are refused while false. */
    val connected: StateFlow<Boolean> = repo.connected
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sharedSession: StateFlow<SharedSessionMeta?> = repo.sharedSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True once the CURRENT prompt id was answered (here or from the notification). */
    val answered: StateFlow<Boolean> = combine(permissionPromptId, answers.answeredId) { id, done ->
        id != null && id == done
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Last action failure, in Spanish, for a dismissible banner. Null = none.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    // NON-ROUTING flows: stay on WhileSubscribed(5_000) — the listener
    // pauses when no UI is observing, and stale display on wake is fine
    // (these only show after the user has navigated to a page that uses
    // them, by which point Firebase has reconnected anyway).
    val metrics: StateFlow<Metrics> = repo.metrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Metrics())

    val activity: StateFlow<String?> = repo.activity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val task: StateFlow<String?> = repo.task
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eagerly (not WhileSubscribed): the completion watcher in init collects
    // it continuously anyway, so WhileSubscribed never actually paused it.
    val response: StateFlow<String?> = repo.response
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val claudeStatus: StateFlow<ClaudeStatus?> = repo.claudeStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val taskKind: StateFlow<TaskKind?> = repo.taskKind
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val headline: StateFlow<String?> = repo.headline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val toolEvents: StateFlow<List<ToolEvent>> = repo.toolEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val followups: StateFlow<List<String>> = repo.followups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentSessions: StateFlow<List<RecentSession>> = repo.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Sprint 4n — tap-to-claim. Daemon writes /claimResult after each
    // claim attempt; UI drives the success / error banner from this.
    val claimResult: StateFlow<ClaimResult?> = repo.claimResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Pending confirmation dialog state. Pair<sessionId, cwd> when set;
    // null when no dialog is showing. Driven entirely from the watch side
    // (UI tap → set; user confirms or cancels → cleared).
    private val _confirmingClaim = MutableStateFlow<Pair<String, String>?>(null)
    val confirmingClaim: StateFlow<Pair<String, String>?> =
        _confirmingClaim.asStateFlow()

    // Per-app-session flag: did THIS app launch / ViewModel instance send a
    // regular prompt yet? Resets on app cold start (process death recreates
    // the ViewModel) so opening the watch after a while feels "fresh" even
    // though /response still has the previous run's content in RTDB.
    //
    //   • sendPrompt() sets it true — "you're now in a thread you started"
    //   • askWithReset() leaves it false — "you explicitly asked to start over"
    //
    // The CommandPage button uses it to decide between "ask claude" (fresh)
    // and "continuar" (mid-session). Wrapper-side behaviour is unchanged: the
    // daemon always auto-continues via `claude --continue` unless the prompt
    // text contains a RESET_PHRASES match.
    private val _sentInSession = MutableStateFlow(false)
    val sentInSession: StateFlow<Boolean> = _sentInSession.asStateFlow()

    // v7 — Task completion event. Fires ONCE per "wasWorking → IDLE + response"
    // transition. UI collects this and triggers haptic + auto-nav to Page 2.
    //
    // CRITICAL: this state lives in the ViewModel (not a Composable's
    // `remember`) because WearApp.kt routes through AnimatedContent keyed by
    // `status`. Every IDLE↔RUNNING transition re-creates DashboardScreen and
    // wipes any composable-scoped state — so the "I was just RUNNING" memory
    // would never survive a real completion. ViewModel scope outlives screen
    // routing, so the transition watcher stays continuous.
    private val _taskCompleted = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val taskCompleted: SharedFlow<Unit> = _taskCompleted.asSharedFlow()

    init {
        viewModelScope.launch {
            val detector = CompletionDetector()
            combine(status, response) { s, r -> s to r }
                .collect { (s, r) -> if (detector.onUpdate(s, r)) _taskCompleted.emit(Unit) }
        }
    }

    // Claude Code TUI permission prompts use numbered selection ("1. Yes",
    // "2. Yes, ...", "3. No"). Typing the digit + Enter is the most reliable
    // way to confirm — independent of which option happens to be highlighted.
    fun allow() = answer(CommandText.ALLOW)

    // ESC drops out of the prompt to the "No, and tell Claude what to do
    // differently" branch.
    fun deny() = answer(CommandText.DENY)

    // Answers the CURRENT prompt id at most once. Refused while offline (an
    // offline write is queued and replayed later, possibly onto a different
    // prompt) and when there is no id (the wrapper would drop it anyway).
    // The claim happens synchronously on the main thread, so a double tap
    // can't produce two writes.
    private fun answer(text: String) {
        val id = permissionPromptId.value
        if (id == null) {
            _lastError.value = ErrorCopy.PROMPT_GONE
            return
        }
        if (!connected.value) {
            _lastError.value = ErrorCopy.OFFLINE
            return
        }
        if (!answers.tryClaim(id)) return
        viewModelScope.launch {
            try {
                repo.sendCommand(text, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "answer failed: ${e.message}")
                answers.release(id)
                _lastError.value = ErrorCopy.ANSWER_FAILED
            }
        }
    }

    // Cancel/stop a running task. Wrapper's watchCommands handler treats
    // ETX (^C / SIGINT) specially: it calls runner.kill() instead of
    // forwarding to the pty so Claude exits cleanly. Audit log captures it.
    // No promptId: stop isn't an answer to a prompt.
    fun stop() = launchAction(ErrorCopy.STOP_FAILED) { repo.sendCommand(CommandText.STOP, null) }

    // Long-press of the stop button: force-reset stale UI state directly
    // from the watch when the wrapper appears dead. SIGINT via /command
    // goes nowhere if no wrapper is listening, leaving status=RUNNING
    // forever. This writes IDLE + null directly to RTDB so the watch gets
    // out of phantom state regardless of wrapper liveness.
    fun forceReset() = launchAction(ErrorCopy.RESET_FAILED) { repo.forceResetUi() }

    // Voice / text input from the watch. Daemon picks it up and runs
    // `claude -p <text>`, streaming the answer back to /response.
    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _sentInSession.value = true
        launchAction(ErrorCopy.PROMPT_FAILED) { repo.sendPrompt(trimmed) }
    }

    // Reset the conversation: prepend a phrase the wrapper's RESET_PHRASES
    // detects ("nueva conversación") so the next run does NOT use --continue.
    // Wrapper-side logic lives in wrapper/src/index.ts (RESET_PHRASES + the
    // isResetPrompt check before computing shouldContinue).
    //
    // Note: does NOT set _sentInSession=true. The user explicitly asked to
    // start over, so after the reset run completes the Page 0 button should
    // still read "ask claude" — until they tap it again via sendPrompt().
    fun askWithReset(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _sentInSession.value = false
        launchAction(ErrorCopy.PROMPT_FAILED) { repo.sendPrompt("nueva conversación, $trimmed") }
    }

    // Sprint 4n — tap-to-claim actions.
    //
    // requestClaimConfirmation() is called when SessionRow is tapped on
    // Page 5; it raises the confirmation dialog without yet writing to
    // RTDB. confirmClaim() / cancelClaim() resolve that dialog.
    fun requestClaimConfirmation(sessionId: String, cwd: String) {
        _confirmingClaim.value = sessionId to cwd
    }

    fun cancelClaim() {
        _confirmingClaim.value = null
    }

    fun confirmClaim() {
        val pending = _confirmingClaim.value ?: return
        _confirmingClaim.value = null
        launchAction(ErrorCopy.CLAIM_FAILED) { repo.claimSession(pending.first, pending.second) }
    }

    // Called by ClaimResultBanner after the auto-dismiss timer fires (or
    // user taps the close X). Nulls /claimResult so a stale entry doesn't
    // re-show on next listener reconnect. Failure is silent: worst case the
    // stale banner is filtered by its 10s freshness check anyway.
    fun dismissClaimResult() = launchAction(null) { repo.clearClaimResult() }

    private fun launchAction(errorCopy: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "action failed: ${e.message}")
                if (errorCopy != null) _lastError.value = errorCopy
            }
        }
    }

    private companion object {
        const val TAG = "ccwearos-vm"
    }
}

/**
 * Fires once per "was working → IDLE with a new response" transition.
 * Pure state machine so it can be unit-tested without Firebase.
 */
internal class CompletionDetector {
    private var previousStatus: WrapperStatus? = null
    private var lastFiredResponse: String? = null

    /** Returns true when this update completes a run. */
    fun onUpdate(currentStatus: WrapperStatus, currentResponse: String?): Boolean {
        val wasWorking = previousStatus == WrapperStatus.RUNNING ||
            previousStatus == WrapperStatus.AWAITING_PERMISSION

        // Preserve "was working" memory across the transient
        // (IDLE + null response) gap the wrapper produces between
        // clearing stale state and writing the new response.
        val inTransientIdleGap = wasWorking &&
            currentStatus == WrapperStatus.IDLE &&
            currentResponse.isNullOrBlank()
        if (!inTransientIdleGap) {
            previousStatus = currentStatus
        }

        val completed = wasWorking &&
            currentStatus == WrapperStatus.IDLE &&
            !currentResponse.isNullOrBlank() &&
            currentResponse != lastFiredResponse
        if (completed) {
            lastFiredResponse = currentResponse
            previousStatus = currentStatus
        }
        return completed
    }
}
