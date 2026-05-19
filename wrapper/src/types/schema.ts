// Firebase Realtime Database schema. Source of truth for both /wrapper (TS) and
// /watch (Kotlin — manually mirrored). Any change here must be mirrored on the
// watch side.

export type WrapperStatus =
  | "IDLE"
  | "RUNNING"
  | "AWAITING_PERMISSION"
  | "OFFLINE";

export interface Metrics {
  dailyTokens: number;
  weeklyTokens: number;
  monthlyTokens: number;
  updatedAt: number; // unix epoch ms
}

export interface PendingCommand {
  text: string; // raw string piped into stdin, include trailing "\n" if needed
  issuedAt: number; // unix epoch ms — wrapper drops anything older than COMMAND_MAX_AGE_SECONDS
}

export interface RtdbRoot {
  status: WrapperStatus;
  metrics: Metrics;
  command: PendingCommand | null;
  permissionPrompt: string | null;
  // Whimsical action verb Claude is currently showing ("Crunching…",
  // "Razzmatazzing…", "Worked for 33s"). Updated as Claude streams.
  activity: string | null;
  // The current task description, parsed from Claude's terminal title
  // (e.g. "Fetch mylettering.com website").
  task: string | null;
  // The last ~1.5KB of Claude's text response, filtered to skip TUI frames,
  // status lines, and the input prompt. Updated with a 5s debounce.
  response: string | null;
  // A new prompt written by the watch (voice input or button). The daemon
  // picks this up, spawns `claude -p <text>`, streams the answer back to
  // /response, then clears /prompt. Includes a timestamp so the daemon can
  // ignore stale prompts on restart.
  prompt: PendingPrompt | null;
  // Watch-registered FCM token. The wrapper reads this when it needs to wake
  // the watch out of ambient (e.g. permission prompt arrived).
  fcmToken: string | null;
  // Computed at the end of each voice run — action (tools used) vs info (just
  // a textual answer). Drives the watch's ResponsePage layout branching.
  taskKind: TaskKind | null;
  // Up to 12 tool invocations observed during the current run, in order. Null
  // when no tools have been seen yet this run.
  toolEvents: ToolEvent[] | null;
  // One-line summary for informational answers, extracted from Claude's
  // "**TL;DR:** ..." line. Null for action tasks.
  headline: string | null;
  // 2-3 contextual follow-up suggestions Claude generated at the end of its
  // response (parsed from a "Followups:" / "Sugerencias:" bullet block).
  // Surfaced as tappable chips on the watch's Page 4. Null when Claude didn't
  // include the block (e.g. tool-heavy action runs).
  followups: string[] | null;
  // Metadata about the currently-bridged Claude session — written by the
  // `cc` shell alias / share.ts script while it runs. While non-null, the
  // daemon refuses voice prompts (would conflict with the shared pty) and
  // the watch's Page 0 button shows a disabled "shared session active" state.
  // Cleared on script exit. See wrapper/scripts/share.ts.
  sharedSession: SharedSessionMeta | null;
  // Snapshot of Claude Code sessions on the Mac, scanned every ~15s from
  // ~/.claude/sessions/*.json (active PIDs) and ~/.claude/projects/*/*.jsonl
  // (recent transcripts by mtime). Surfaced as Page 5 on the watch.
  recentSessions: RecentSession[] | null;
}

export interface SharedSessionMeta {
  sessionId: string; // UUID of the Claude session (filled once Claude emits one)
  pid: number; // PID of the wrapper script (NOT the Claude pty child)
  cwd: string; // working directory where the session is anchored
  startedAt: number; // unix epoch ms
  // How the bridge is wired:
  //   "wrapper-pty" — the wrapper script (cc / scripts/share.ts) owns Claude's
  //     pty, can pipe Allow/Deny via runner.send(). Voice prompts disabled.
  //   "hook"        — the user's own Terminal owns Claude's pty. A PreToolUse
  //     hook publishes permission prompts to RTDB and waits on /command for
  //     the watch's reply. Voice prompts also disabled (only one shared
  //     session at a time).
  kind: "wrapper-pty" | "hook";
}

export interface RecentSession {
  sessionId: string; // UUID, matches the .jsonl filename
  cwd: string; // working directory the session was started in
  projectName: string; // basename(cwd) — for grouping on the watch
  mtime: number; // unix epoch ms of the .jsonl file's last modification
  active: boolean; // true if a live PID owns this sessionId right now
  shared: boolean; // true if currently under cc/share.ts wrapper control
  lastUserMessage: string | null; // last user-turn text, capped at 60 chars
}

// Rolling audit of every Claude tool the wrapper saw a permission decision
// for. Written across all three flows (voice/cc/hook). Capped at AUDIT_LOG_MAX
// most-recent entries to keep RTDB cheap. Viewable via `scripts/audit.ts`.
export interface AuditEntry {
  ts: number; // unix epoch ms
  kind: "voice" | "cc" | "hook"; // which flow handled it
  tool: string; // "Bash" / "Edit" / "Read" / etc
  args: string; // first line / key arg, capped at 60 chars
  decision: "allow" | "deny" | "timeout" | "pre-approved"; // outcome
  source: "watch" | "terminal" | "auto"; // who chose
}

export interface PendingPrompt {
  text: string;
  issuedAt: number;
}

// Surfaced from claude-voice TUI parsing — one entry per tool invocation we
// see in Claude's "⏺ ToolName(args)" output. The watch uses these to classify
// the task (action vs informational) and to render breadcrumbs / progress.
export interface ToolEvent {
  tool: string; // "Bash" | "Edit" | "Read" | "Write" | "WebFetch" | "WebSearch" | "Grep" | "Glob" | "Task" | string
  arg: string | null; // first-line argument summary, capped at 60 chars
  ts: number; // unix epoch ms when observed
}

// Whether the most recent voice prompt resolved as an action (Claude used
// tools to change something) or an informational answer. Decided at finish.
export type TaskKind = "action" | "info";

// Parsed equivalent of Claude Code's TUI status line. Optional fields stay
// null until Claude actually surfaces them.
export interface ClaudeStatus {
  model: string | null; // "Opus 4.7"
  contextSize: string | null; // "1M" / "200k"
  contextPct: number | null; // 0–100 within current task/context window
  sessionPct: number | null; // 0–100 of session quota
  sessionResets: string | null; // "11:30pm"
  weeklyPct: number | null; // 0–100 of weekly quota
  weeklyResets: string | null; // "may 17, 12:00am"
  monthlyCost: string | null; // "$0.66"
  monthlyResets: string | null; // "jun 1"
}

export const RTDB_PATHS = {
  status: "/status",
  metrics: "/metrics",
  command: "/command",
  permissionPrompt: "/permissionPrompt",
  activity: "/activity",
  task: "/task",
  response: "/response",
  prompt: "/prompt",
  claudeStatus: "/claudeStatus",
  fcmToken: "/fcmToken",
  taskKind: "/taskKind",
  toolEvents: "/toolEvents",
  headline: "/headline",
  followups: "/followups",
  sharedSession: "/sharedSession",
  recentSessions: "/recentSessions",
  auditLog: "/auditLog",
} as const;
