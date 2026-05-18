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
} as const;
