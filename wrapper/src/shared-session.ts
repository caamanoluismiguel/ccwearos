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
//     OR the last heartbeat is older than HOOK_STALE_MS, OR (legacy record
//     without heartbeat) it was started more than HOOK_STALE_MS ago.
// `isAlive` is injected (pass isPidAlive from src/pid-utils.ts) so this stays
// pure and testable.
export function isSharedSessionStale(
  meta: SharedSessionMeta,
  now: number,
  isAlive: (pid: number) => boolean,
): boolean {
  if (meta.kind === "wrapper-pty") return !isAlive(meta.pid);
  if (typeof meta.ownerPid === "number" && !isAlive(meta.ownerPid)) return true;
  if (typeof meta.heartbeatAt === "number") {
    return now - meta.heartbeatAt > HOOK_STALE_MS;
  }
  return now - meta.startedAt > HOOK_STALE_MS;
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
