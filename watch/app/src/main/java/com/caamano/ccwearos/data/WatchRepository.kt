package com.caamano.ccwearos.data

import kotlinx.coroutines.flow.Flow

// Seam between the ViewModel and Firebase so the ViewModel can be unit-tested
// with a fake. CcwearosRepository is the only production implementation.
interface WatchRepository {
    val status: Flow<WrapperStatus>
    val metrics: Flow<Metrics>
    val permissionPrompt: Flow<String?>

    /** One-time id the wrapper mints with every prompt; echoed on allow/deny. */
    val permissionPromptId: Flow<String?>

    /** Live socket state from `.info/connected`. False while offline. */
    val connected: Flow<Boolean>
    val activity: Flow<String?>
    val task: Flow<String?>
    val response: Flow<String?>
    val claudeStatus: Flow<ClaudeStatus?>
    val taskKind: Flow<TaskKind?>
    val headline: Flow<String?>
    val toolEvents: Flow<List<ToolEvent>>
    val followups: Flow<List<String>>
    val sharedSession: Flow<SharedSessionMeta?>
    val recentSessions: Flow<List<RecentSession>>
    val claimResult: Flow<ClaimResult?>

    /** Mac-only problem blocking Claude (trust/login/crash/timeout); null when none. */
    val blocker: Flow<Blocker?>

    /** Real exit status of the last voice run; null while running / before any run. */
    val outcome: Flow<RunOutcome?>

    /** True while voice prompts continue a thread (wrapper passes --continue). */
    val conversationActive: Flow<Boolean>

    /** Writes /command. `promptId` is included only when non-null (allow/deny). */
    suspend fun sendCommand(text: String, promptId: String?)
    /** Writes /prompt {text, mode, issuedAt}; [mode] picks a new thread or the current one. */
    suspend fun sendPrompt(text: String, mode: PromptMode)
    suspend fun claimSession(sessionId: String, cwd: String)
    suspend fun clearClaimResult()
    suspend fun forceResetUi()
}

/**
 * /prompt.mode: whether the daemon starts a NEW conversation or CONTINUEs
 * the current one (`--continue`). Replaces the old "nueva conversación, "
 * text prefix. Absent on the wire = the wrapper's legacy reset-phrase rule.
 */
enum class PromptMode(val wire: String) {
    NEW("new"),
    CONTINUE("continue"),
}

// Byte sequences the wrapper's command allowlist accepts (wrapper/src/command-guard.ts).
object CommandText {
    /** Claude Code TUI option "1. Yes" + Enter. */
    const val ALLOW = "1\r"

    /** ESC: "No, and tell Claude what to do differently". */
    const val DENY = "\u001B"

    /** ETX / SIGINT: wrapper kills the runner. */
    const val STOP = "\u0003"
}
