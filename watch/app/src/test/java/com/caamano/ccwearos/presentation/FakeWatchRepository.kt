package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.ClaimResult
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.PromptMode
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WatchRepository
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakeWatchRepository : WatchRepository {
    override val status = MutableStateFlow(WrapperStatus.IDLE)
    override val metrics = MutableStateFlow(Metrics())
    override val permissionPrompt = MutableStateFlow<String?>(null)
    override val permissionPromptId = MutableStateFlow<String?>(null)
    override val connected = MutableStateFlow(true)
    override val activity = MutableStateFlow<String?>(null)
    override val task = MutableStateFlow<String?>(null)
    override val response = MutableStateFlow<String?>(null)
    override val claudeStatus = MutableStateFlow<ClaudeStatus?>(null)
    override val taskKind = MutableStateFlow<TaskKind?>(null)
    override val headline = MutableStateFlow<String?>(null)
    override val toolEvents = MutableStateFlow<List<ToolEvent>>(emptyList())
    override val followups = MutableStateFlow<List<String>>(emptyList())
    override val sharedSession = MutableStateFlow<SharedSessionMeta?>(null)
    override val recentSessions = MutableStateFlow<List<RecentSession>>(emptyList())
    override val claimResult = MutableStateFlow<ClaimResult?>(null)
    override val blocker = MutableStateFlow<Blocker?>(null)
    override val outcome = MutableStateFlow<RunOutcome?>(null)
    override val conversationActive = MutableStateFlow(false)

    val commands = mutableListOf<Pair<String, String?>>()
    var failNextCommand = false

    override suspend fun sendCommand(text: String, promptId: String?) {
        if (failNextCommand) {
            failNextCommand = false
            throw IllegalStateException("write failed")
        }
        commands += text to promptId
    }

    val prompts = mutableListOf<String>()
    val promptModes = mutableListOf<PromptMode>()
    var failNextPrompt = false
    var resets = 0

    override suspend fun sendPrompt(text: String, mode: PromptMode) {
        if (failNextPrompt) {
            failNextPrompt = false
            throw IllegalStateException("write failed")
        }
        prompts += text
        promptModes += mode
    }

    override suspend fun claimSession(sessionId: String, cwd: String) = Unit
    override suspend fun clearClaimResult() = Unit
    override suspend fun forceResetUi() {
        resets++
    }
}
