package com.caamano.ccwearos.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.ClaimResult
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CcwearosViewModel(
    private val repo: CcwearosRepository = CcwearosRepository(),
) : ViewModel() {

    // ROUTING-CRITICAL flows use SharingStarted.Eagerly: the listener stays
    // alive even when no UI is collecting (i.e., screen off / ambient). Cost
    // is one Firebase value listener kept warm; benefit is no "wrapper not
    // reachable" flicker on wake — observed twice in 24h on real watch with
    // WhileSubscribed(5_000). These three drive screen routing in WearApp
    // and the haptic in PermissionScreen, so a fresh value on wake matters
    // more than the tiny battery cost of an idle listener.
    val status: StateFlow<WrapperStatus> = repo.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, WrapperStatus.OFFLINE)

    val permissionPrompt: StateFlow<String?> = repo.permissionPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sharedSession: StateFlow<SharedSessionMeta?> = repo.sharedSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    val response: StateFlow<String?> = repo.response
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    // Claude Code TUI permission prompts use numbered selection ("1. Yes",
    // "2. Yes, ...", "3. No"). Typing the digit + Enter is the most reliable
    // way to confirm — independent of which option happens to be highlighted.
    fun allow() { viewModelScope.launch { repo.sendCommand("1\r") } }

    // Cancel/stop a running task. Wrapper's watchCommands handler treats
    // ETX ( / SIGINT) specially: it calls runner.kill() instead of
    // forwarding to the pty so Claude exits cleanly. Audit log captures it.
    fun stop() { viewModelScope.launch { repo.sendCommand("\u0003") } }

    // Long-press of the stop button: force-reset stale UI state directly
    // from the watch when the wrapper appears dead. SIGINT via /command
    // goes nowhere if no wrapper is listening, leaving status=RUNNING
    // forever. This writes IDLE + null directly to RTDB so the watch gets
    // out of phantom state regardless of wrapper liveness.
    fun forceReset() { viewModelScope.launch { repo.forceResetUi() } }

    // ESC drops out of the prompt to the "No, and tell Claude what to do
    // differently" branch.
    fun deny() { viewModelScope.launch { repo.sendCommand("\u001B") } }

    // Voice / text input from the watch. Daemon picks it up and runs
    // `claude -p <text>`, streaming the answer back to /response.
    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _sentInSession.value = true
        viewModelScope.launch { repo.sendPrompt(trimmed) }
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
        viewModelScope.launch { repo.sendPrompt("nueva conversación, $trimmed") }
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
        viewModelScope.launch { repo.claimSession(pending.first, pending.second) }
    }

    // Called by ClaimResultBanner after the auto-dismiss timer fires (or
    // user taps the close X). Nulls /claimResult so a stale entry doesn't
    // re-show on next listener reconnect.
    fun dismissClaimResult() {
        viewModelScope.launch { repo.clearClaimResult() }
    }
}
