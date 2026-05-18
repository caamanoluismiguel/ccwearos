package com.caamano.ccwearos.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        viewModelScope.launch { repo.sendPrompt(trimmed) }
    }
}
