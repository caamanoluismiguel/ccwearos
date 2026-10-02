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
    // Echo of /permissionPromptId at tap time. Required by the wrapper for
    // allow/deny answers; stale or mismatched ids are dropped.
    val promptId: String? = null,
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
    // kind="hook" only: unix epoch ms of the last PreToolUse hook run. Older
    // than 30 min → stale lock (see SharedSessionStaleness). Null on entries
    // written before the wrapper added it.
    val heartbeatAt: Long? = null,
    // kind="hook" only: PID of the Claude CLI process that owns the session.
    val ownerPid: Long? = null,
)

/**
 * A `kind == "hook"` lock whose owner stopped running hooks (Terminal closed,
 * Claude crashed) stays in RTDB until the daemon clears it, and while it is
 * there the dashboard hides the ask button. Treat it as absent once its
 * heartbeat (or startedAt, for entries without one) is older than
 * [STALE_AFTER_MS]. Mirrors isSharedSessionStale in wrapper/src/shared-session.ts.
 */
object SharedSessionStaleness {
    const val STALE_AFTER_MS = 30 * 60 * 1000L

    fun isStale(meta: SharedSessionMeta, nowMs: Long): Boolean {
        if (meta.kind != "hook") return false
        val last = meta.heartbeatAt?.takeIf { it > 0 } ?: meta.startedAt
        // No timestamp at all → can't judge; keep the lock. A timestamp in the
        // future (watch clock behind the Mac) yields a negative age → fresh.
        if (last <= 0) return false
        return nowMs - last > STALE_AFTER_MS
    }

    /** [meta], or null when it is a stale hook lock. */
    fun visible(meta: SharedSessionMeta?, nowMs: Long): SharedSessionMeta? =
        meta?.takeUnless { isStale(it, nowMs) }
}

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

// Sprint 4n — tap-to-claim. Watch writes /claimRequest when the user taps a
// session row + confirms the dialog. Daemon reads it and spawns `cc --resume
// <sessionId>` in a new Mac Terminal via osascript. Schema mirror is in
// wrapper/src/types/schema.ts — keep in sync.
@IgnoreExtraProperties
data class PendingClaim(
    val sessionId: String = "",
    val cwd: String = "",
    val issuedAt: Long = 0,
)

// Daemon's response, written to /claimResult after handling a claim. The
// watch reads it to show a success / error banner. `sessionId` echoes the
// originating request so the watch can ignore stale results from prior taps.
@IgnoreExtraProperties
data class ClaimResult(
    val ok: Boolean = false,
    val reason: String? = null,
    val sessionId: String = "",
    val ts: Long = 0,
)

// Mirrors wrapper/src/types/schema.ts Blocker. Something only the Mac can
// resolve (folder trust, login, crash, timeout); the watch shows a designed
// "Claude necesita tu Mac" screen, never raw text.
data class Blocker(
    val kind: BlockerKind = BlockerKind.OTHER,
    val hint: String = "",
    val cwd: String? = null,
    val ts: Long = 0,
)

enum class BlockerKind { TRUST, LOGIN, CRASH, TIMEOUT, OTHER }

// Mirrors RunOutcome: the real exit status of the last voice run.
// [stopped] is true when the run ended because someone asked it to stop
// (Detener on the watch, or ^C on the Mac); optional on the wire.
data class RunOutcome(
    val ok: Boolean = false,
    val exitCode: Long = 0,
    val ts: Long = 0,
    val stopped: Boolean = false,
)
