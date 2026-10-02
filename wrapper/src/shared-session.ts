// Pure helpers for /sharedSession locks and the PreToolUse hook bridge.
//
// Living in src/ (not scripts/hooks/) so vitest can import them without the
// scripts' Firebase side effects firing on import.

import type { SharedSessionMeta } from "./types/schema.js";

// A kind="hook" lock with no heartbeat for this long is considered abandoned.
// The hook refreshes heartbeatAt on every tool call, so 30 min of silence
// means the bridged Claude is gone or idle enough that voice should win.
export const HOOK_STALE_MS = 30 * 60 * 1000;

// Decide whether an existing /sharedSession lock may be ignored / taken over.
//   - wrapper-pty (cc / takeover placeholder): stale iff its pid is dead.
//   - hook (/ccwearos): stale iff the owning Claude pid is known and dead,
//     OR the last sign of life (heartbeatAt, else startedAt) is older than
//     HOOK_STALE_MS — whatever any pid says.
// The age rule is the watch's SharedSessionStaleness.isStale (RtdbModels.kt)
// on purpose: a heartbeatAt that is missing, 0 or not a finite number falls
// back to startedAt, and no usable timestamp at all can't be judged by age.
// So whenever the watch hides a hook lock as stale (and offers the ask
// button), the daemon also ignores it instead of silently dropping the
// prompt. The dead-owner rule only makes the daemon MORE permissive.
// `isAlive` is injected (pass isPidAlive from src/pid-utils.ts) so this stays
// pure and testable.
export function isSharedSessionStale(
  meta: SharedSessionMeta,
  now: number,
  isAlive: (pid: number) => boolean,
): boolean {
  if (meta.kind === "wrapper-pty") return !isAlive(meta.pid);
  if (typeof meta.ownerPid === "number" && !isAlive(meta.ownerPid)) return true;
  const last = lastHookSignal(meta);
  if (last === null) return false;
  return now - last > HOOK_STALE_MS;
}

const positiveTs = (v: unknown): number | null =>
  typeof v === "number" && Number.isFinite(v) && v > 0 ? v : null;

// heartbeatAt when usable, else startedAt, else null (mirrors the watch).
export function lastHookSignal(meta: SharedSessionMeta): number | null {
  return positiveTs(meta.heartbeatAt) ?? positiveTs(meta.startedAt);
}

// Hint the daemon publishes as /blocker {kind:"other"} when it drops a voice
// prompt because a live shared session owns Claude on the Mac.
export const SHARED_SESSION_BLOCKER_HINT =
  "Hay una sesión compartida activa en tu Mac (cc o /ccwearos). Ciérrala para preguntar desde el reloj.";

// Transaction updater for /sharedSession that removes `expected` only if the
// server still holds that same lock (pid + startedAt) AND it is still stale
// — a hook that refreshed heartbeatAt meanwhile, or a new owner, is left
// alone. Returns null to delete, undefined to abort. A null `cur` (cold
// cache or already gone) returns null: the server re-runs us with the real
// value, and deleting an empty path is a no-op.
export function staleLockRemover(
  expected: SharedSessionMeta,
  now: number,
  isAlive: (pid: number) => boolean,
): (cur: SharedSessionMeta | null) => null | undefined {
  return (cur) => {
    if (cur === null) return null;
    if (cur.pid !== expected.pid || cur.startedAt !== expected.startedAt) {
      return undefined;
    }
    return isSharedSessionStale(cur, now, isAlive) ? null : undefined;
  };
}

export interface PsEntry {
  ppid: number;
  comm: string;
}

// True for the Claude Code CLI process name as `ps -o comm=` reports it:
// "claude" (PATH launch), "/path/to/claude", or the native installer's
// versioned binary ".../claude/versions/<ver>".
export function isClaudeComm(comm: string): boolean {
  const c = comm.trim();
  const base = c.slice(c.lastIndexOf("/") + 1);
  return base === "claude" || /\/claude\/versions\/[^/]+$/.test(c);
}

// Walk up the process tree from `startPid` and
// return the first ancestor whose comm is the Claude CLI, or null.
// Slash-command scripts run as claude → shell → tsx → node, so
// process.ppid is NOT Claude; this finds the real owner. `ps` is injected
// (returns null when the pid doesn't exist) so the walk is unit-testable.
export function findClaudeAncestorPid(
  startPid: number,
  ps: (pid: number) => PsEntry | null,
  maxDepth = 16,
): number | null {
  let pid = startPid;
  for (let i = 0; i < maxDepth; i++) {
    const entry = ps(pid);
    if (!entry) return null;
    if (pid !== startPid && isClaudeComm(entry.comm)) return pid;
    if (!Number.isInteger(entry.ppid) || entry.ppid <= 1 || entry.ppid === pid) {
      return null;
    }
    pid = entry.ppid;
  }
  return null;
}

// --- PreToolUse hook output --------------------------------------------

// Schema verified 2026-10-01 against
// https://code.claude.com/docs/en/hooks#pretooluse-decision-control :
// "allow" bypasses the permission system for the call (deny rules still
// apply), "deny" blocks it and shows the reason to Claude, "ask" shows the
// normal permission dialog regardless of mode.
export interface HookOutput {
  hookSpecificOutput: {
    hookEventName: "PreToolUse";
    permissionDecision: "allow" | "deny" | "ask";
    permissionDecisionReason: string;
  };
  systemMessage?: string;
}

export function hookDecision(
  permissionDecision: "allow" | "deny" | "ask",
  permissionDecisionReason: string,
): HookOutput {
  return {
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision,
      permissionDecisionReason,
    },
  };
}

// --- PreToolUse hook prompt formatting ---------------------------------

export const PROMPT_MAX_CHARS = 1500;

// Keep head and tail of a long string so the end of a chained command
// (`... && rm -rf build`) stays visible. Result length <= max.
export function truncateMiddle(s: string, max: number = PROMPT_MAX_CHARS): string {
  if (s.length <= max) return s;
  const sep = " … ";
  const keep = max - sep.length;
  if (keep <= 0) return s.slice(0, max);
  const head = Math.ceil(keep * 0.6);
  const tail = keep - head;
  return s.slice(0, head) + sep + (tail > 0 ? s.slice(s.length - tail) : "");
}

function argToString(v: unknown): string {
  if (typeof v === "string") return v;
  if (v === null || v === undefined) return "";
  return JSON.stringify(v) ?? "";
}

const FILE_TOOLS: ReadonlySet<string> = new Set([
  "Edit",
  "Write",
  "MultiEdit",
  "NotebookEdit",
  "Read",
]);
const PER_ARG_MAX = 200;

// Text for the watch's PermissionScreen. The user is approving the exact
// command, so Bash shows the FULL command and file tools the FULL path;
// only the overall length is capped (head + " … " + tail).
export function describeToolCall(
  toolName: string,
  toolInput: Record<string, unknown>,
): string {
  if (toolName === "Bash") {
    const cmd = argToString(toolInput["command"]);
    if (cmd) return truncateMiddle(`Bash: ${cmd}`);
  }
  if (FILE_TOOLS.has(toolName)) {
    const path = argToString(toolInput["file_path"] ?? toolInput["notebook_path"]);
    if (path) return truncateMiddle(`${toolName}: ${path}`);
  }
  const fragments: string[] = [];
  for (const [k, v] of Object.entries(toolInput)) {
    const str = argToString(v);
    if (str.length === 0) continue;
    fragments.push(`${k}=${truncateMiddle(str, PER_ARG_MAX)}`);
  }
  return truncateMiddle(`${toolName}: ${fragments.join(" · ")}`);
}

// Transaction handler for /status after a voice-run permission answer:
// AWAITING_PERMISSION -> RUNNING, never over anything else (e.g. the
// daemon's IDLE after a Stop). A cold local cache hands us `null` first;
// returning undefined there would ABORT the transaction without ever seeing
// the server value (status stuck on AWAITING_PERMISSION). Returning null
// makes the server re-run us with the real value; if the path really is
// empty, writing null is a no-op.
export function awaitingToRunning(cur: string | null): string | null | undefined {
  if (cur === null) return null;
  return cur === "AWAITING_PERMISSION" ? "RUNNING" : undefined;
}
