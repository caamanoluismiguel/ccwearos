package com.caamano.ccwearos.data

import com.google.firebase.database.IgnoreExtraProperties

enum class WrapperStatus {
    IDLE,
    RUNNING,
    AWAITING_PERMISSION,
    OFFLINE,
}

@IgnoreExtraProperties
data class Metrics(
    val dailyTokens: Long = 0,
    val weeklyTokens: Long = 0,
    val monthlyTokens: Long = 0,
    val updatedAt: Long = 0,
)

@IgnoreExtraProperties
data class PendingCommand(
    val text: String = "",
    val issuedAt: Long = 0,
)

@IgnoreExtraProperties
data class ClaudeStatus(
    val model: String? = null,
    val contextSize: String? = null,
    val contextPct: Double? = null,
    val sessionPct: Double? = null,
    val sessionResets: String? = null,
    val weeklyPct: Double? = null,
    val weeklyResets: String? = null,
    val monthlyCost: String? = null,
    val monthlyResets: String? = null,
)

// Single tool invocation surfaced from claude-voice TUI parsing.
@IgnoreExtraProperties
data class ToolEvent(
    val tool: String = "",
    val arg: String? = null,
    val ts: Long = 0,
)

// Whether the most recent voice run resolved as an "action" (Claude used
// tools — Bash, Edit, Write, etc.) or an "info" textual answer. Used by the
// dashboard to pick the right Page 3 layout.
enum class TaskKind { ACTION, INFO;
    companion object {
        fun fromRaw(raw: String?): TaskKind? = when (raw?.lowercase()) {
            "action" -> ACTION
            "info" -> INFO
            else -> null
        }
    }
}

// Metadata about a Claude session currently bridged to the watch via the
// `cc` shell alias / scripts/share.ts. While non-null, the daemon refuses
// voice prompts and Page 0's button is disabled.
@IgnoreExtraProperties
data class SharedSessionMeta(
    val sessionId: String = "",
    val pid: Long = 0,
    val cwd: String = "",
    val startedAt: Long = 0,
    // "wrapper-pty" → cc-spawned, wrapper owns the pty.
    // "hook"        → user's Terminal, hook bridges permission prompts via RTDB.
    // Empty string for legacy entries written before Camino E.
    val kind: String = "",
)

// Snapshot of one Claude Code session on the Mac. Scanned every ~15s from
// ~/.claude/sessions/*.json (active PIDs) + ~/.claude/projects/*/*.jsonl
// (recent transcripts by mtime). Page 5 lists these grouped by projectName.
@IgnoreExtraProperties
data class RecentSession(
    val sessionId: String = "",
    val cwd: String = "",
    val projectName: String = "",
    val mtime: Long = 0,
    val active: Boolean = false,
    val shared: Boolean = false,
    val lastUserMessage: String? = null,
)
