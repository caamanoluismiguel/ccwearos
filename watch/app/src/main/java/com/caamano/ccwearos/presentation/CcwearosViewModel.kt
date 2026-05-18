package com.caamano.ccwearos.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caamano.ccwearos.data.CcwearosRepository
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

    val status: StateFlow<WrapperStatus> = repo.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WrapperStatus.OFFLINE)

    val metrics: StateFlow<Metrics> = repo.metrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Metrics())

    val permissionPrompt: StateFlow<String?> = repo.permissionPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    val sharedSession: StateFlow<SharedSessionMeta?> = repo.sharedSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentSessions: StateFlow<List<RecentSession>> = repo.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
