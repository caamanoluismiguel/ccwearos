// Contract between the daemon's voice runner (src/claude-voice.ts, which
// spawns `claude -p … --output-format stream-json`) and the PreToolUse hook
// (scripts/hooks/pre-tool-use.ts), which is how a -p run asks the watch for
// permission. Pure — no Firebase, no fs — so vitest covers it.
//
// Env contract (set by the daemon on the spawned claude; hooks inherit it):
//   CCWEAROS_VOICE_RUN=1            this claude is a daemon voice run
//   CCWEAROS_VOICE_RUN_ID=<uuid>    one id per run (hook logs + audit)
//   CCWEAROS_VOICE_HOOK_WAIT_MS=n   how long the hook waits for the watch
// The voice run also gets its OWN PreToolUse entry through `--settings`
// whose command is prefixed `CCWEAROS_HOOK_ROLE=voice`. Only that entry
// acts; the globally installed /ccwearos entry (timeout 60s, no role) sees
// CCWEAROS_VOICE_RUN=1 and passes through, so a tool call is never asked
// twice and the long wait isn't killed by the global entry's 60s timeout.

import { isAbsolute, relative, resolve } from "node:path";
import { shSingleQuote } from "./sh-escape.js";

export const VOICE_RUN_ENV = "CCWEAROS_VOICE_RUN";
export const VOICE_RUN_ID_ENV = "CCWEAROS_VOICE_RUN_ID";
export const VOICE_HOOK_WAIT_ENV = "CCWEAROS_VOICE_HOOK_WAIT_MS";
export const HOOK_ROLE_ENV = "CCWEAROS_HOOK_ROLE";
export const HOOK_ROLE_VOICE = "voice";

export const DEFAULT_VOICE_HOOK_WAIT_MS = 120_000;
const MIN_WAIT_MS = 10_000;
const MAX_WAIT_MS = 590_000; // under Claude Code's 600s hook ceiling

// Reason Claude sees when the watch never answered (a -p run can't "ask").
export const VOICE_HOOK_TIMEOUT_REASON = "Sin respuesta desde el reloj";

type Env = Record<string, string | undefined>;

//   share           — not a voice run: the /ccwearos (hook share) flow.
//   voice           — the voice run's own hook entry: ask the watch.
//   voice-duplicate — the global entry firing inside a voice run: pass.
export type HookMode = "share" | "voice" | "voice-duplicate";

export function hookMode(env: Env): HookMode {
  if (env[VOICE_RUN_ENV] !== "1") return "share";
  return env[HOOK_ROLE_ENV] === HOOK_ROLE_VOICE ? "voice" : "voice-duplicate";
}

export function voiceHookWaitMs(env: Env): number {
  const n = Number(env[VOICE_HOOK_WAIT_ENV]);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_VOICE_HOOK_WAIT_MS;
  return Math.min(MAX_WAIT_MS, Math.max(MIN_WAIT_MS, Math.round(n)));
}

// `--settings` JSON for a voice run: one PreToolUse entry for every tool,
// whose timeout leaves the hook room to finish its wait and clean up.
export function voiceHookSettings(a: {
  tsxBin: string;
  hookScript: string;
  waitMs: number;
}): string {
  const command = `${HOOK_ROLE_ENV}=${HOOK_ROLE_VOICE} ${shSingleQuote(a.tsxBin)} ${shSingleQuote(a.hookScript)}`;
  return JSON.stringify({
    hooks: {
      PreToolUse: [
        {
          matcher: "*",
          hooks: [
            {
              type: "command",
              command,
              timeout: Math.ceil(a.waitMs / 1000) + 30,
            },
          ],
        },
      ],
    },
  });
}

// Bookkeeping tools that never touch the Mac: never worth a wrist prompt.
const NO_PROMPT_TOOLS: ReadonlySet<string> = new Set([
  "TodoWrite",
  "TodoRead",
  "Task",
  "Agent",
  "TaskCreate",
  "TaskUpdate",
  "TaskList",
  "TaskGet",
  "ToolSearch",
  "EnterPlanMode",
  "ExitPlanMode",
]);

// Read-only tools: Claude Code allows them without asking INSIDE the
// working directory, so the hook defers there. Outside it they go to the
// watch (the voice cwd is an empty folder, so "read ~/Downloads" asks).
const READ_ONLY_TOOLS: ReadonlySet<string> = new Set([
  "Read",
  "Grep",
  "Glob",
  "LS",
  "NotebookRead",
]);

function isInside(cwd: string, p: string): boolean {
  if (p.startsWith("~")) return false; // not expanded by resolve()
  const rel = relative(resolve(cwd), resolve(cwd, p));
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

// Whether the voice hook must put this call on the watch (true) or defer to
// Claude Code's own rules (false). Everything not listed above asks:
// Bash, Edit, Write, WebFetch, WebSearch, MCP tools, …
export function voiceToolNeedsWatch(
  toolName: string,
  toolInput: Record<string, unknown>,
  cwd: string | undefined,
): boolean {
  if (NO_PROMPT_TOOLS.has(toolName)) return false;
  if (!READ_ONLY_TOOLS.has(toolName) || !cwd) return true;
  const raw =
    toolInput["file_path"] ?? toolInput["path"] ?? toolInput["notebook_path"];
  // Grep / Glob without a path search the working directory.
  if (raw === undefined || raw === null || raw === "") {
    return toolName === "Read" || toolName === "NotebookRead";
  }
  if (typeof raw !== "string") return true;
  return !isInside(cwd, raw);
}
