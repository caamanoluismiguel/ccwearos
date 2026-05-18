// Critical: Claude Code parses the hook's STDOUT as JSON. Anything written
// to stdout (including stray console.log calls from imported modules like
// firebase.ts's sendFcmWake "[fcm] wake sent") will corrupt the response.
// Redirect console.log → stderr; only emit() writes to stdout, exactly once.
console.log = (...args: unknown[]): void => {
  process.stderr.write(
    args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ") +
      "\n",
  );
};

// CCWEAROS PreToolUse hook — bridges permission prompts to the watch for
// Claude Code sessions the user has marked with `/ccwearos`.
//
// Lifecycle:
//   - Installed via wrapper/scripts/install-hooks.ts which appends an entry
//     to ~/.claude/settings.json's `hooks.PreToolUse` array.
//   - Claude Code spawns this script before every tool call. We get the
//     session_id + tool_name + tool_input via stdin (JSON).
//   - If /sharedSession.kind === "hook" AND /sharedSession.sessionId matches
//     the incoming session_id, we publish the pending tool to /permissionPrompt
//     and block waiting for the watch's Allow/Deny response on /command.
//   - Otherwise (no share, or different session) we exit 0 immediately so
//     Claude falls back to its built-in Terminal permission prompt.
//
// Output contract (Claude Code hook format):
//   stdout JSON: { "hookSpecificOutput": { "permissionDecision": "allow"|"deny"|"ask" } }
//   exit 0 always (we never want to crash a Claude session because of us).
//
// Polling budget: 55s. Claude Code defaults to a 60s hook timeout; we leave
// 5s buffer so the script can finish writing JSON before the host kills it.

import { writeSync } from "node:fs";
import {
  initFirebase,
  readSharedSession,
  sendFcmWake,
  setPermissionPrompt,
  setSharedSession,
  setStatus,
} from "../../src/firebase.js";
import { db } from "../../src/firebase.js";
import type { PendingCommand } from "../../src/types/schema.js";

interface HookInput {
  session_id?: string;
  hook_event_name?: string;
  tool_name?: string;
  tool_input?: Record<string, unknown>;
  cwd?: string;
}

interface HookOutput {
  hookSpecificOutput?: {
    permissionDecision?: "allow" | "deny" | "ask";
  };
  systemMessage?: string;
}

const POLL_INTERVAL_MS = 500;
const POLL_BUDGET_MS = 55_000;
const PROMPT_MAX_CHARS = 200;

function emit(out: HookOutput): never {
  // Write SYNCHRONOUSLY to fd 1 — process.stdout.write() buffers when stdout
  // is a pipe (which is how Claude Code invokes hooks), and process.exit()
  // truncates any pending writes. writeSync bypasses the libuv pipe buffer.
  writeSync(1, JSON.stringify(out) + "\n");
  process.exit(0);
}

function passThrough(reason: string): never {
  // When we're NOT taking responsibility for this tool call, returning "ask"
  // tells Claude Code to fall back to its normal behaviour. For non-shared
  // sessions we actually want allow-implicit (no decision), which is the
  // semantics of emitting nothing — Claude Code treats absence as "no
  // opinion" and proceeds with its built-in policy.
  if (process.env["CCWEAROS_HOOK_DEBUG"]) {
    process.stderr.write(`[ccwearos-hook] pass-through: ${reason}\n`);
  }
  process.exit(0);
}

function describeToolCall(
  toolName: string,
  toolInput: Record<string, unknown>,
): string {
  // Pretty one-liner for the watch's PermissionScreen.
  const fragments: string[] = [];
  for (const [k, v] of Object.entries(toolInput)) {
    let str: string;
    if (typeof v === "string") str = v;
    else if (v === null || v === undefined) str = "";
    else str = JSON.stringify(v);
    if (str.length === 0) continue;
    fragments.push(`${k}=${str.length > 80 ? str.slice(0, 77) + "…" : str}`);
    if (fragments.join(" ").length > PROMPT_MAX_CHARS) break;
  }
  const args = fragments.join(" · ");
  const line = `${toolName}: ${args}`;
  return line.length > PROMPT_MAX_CHARS
    ? line.slice(0, PROMPT_MAX_CHARS - 1) + "…"
    : line;
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function pollForCommand(deadline: number): Promise<string | null> {
  const ref = db().ref("/command");
  const debug = !!process.env["CCWEAROS_HOOK_DEBUG"];
  let iterations = 0;
  while (Date.now() < deadline) {
    iterations++;
    try {
      const snap = await ref.once("value");
      const val = snap.val() as PendingCommand | null;
      if (debug && (iterations <= 3 || iterations % 20 === 0)) {
        process.stderr.write(
          `[ccwearos-hook] poll #${iterations}: ${val ? "got" : "null"}\n`,
        );
      }
      if (val && typeof val.text === "string" && val.text.length > 0) {
        return val.text;
      }
    } catch (e) {
      process.stderr.write(
        `[ccwearos-hook] poll #${iterations} threw: ${(e as Error).message}\n`,
      );
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  return null;
}

async function main(): Promise<void> {
  let raw: string;
  try {
    raw = await readStdin();
  } catch {
    passThrough("stdin read failed");
  }
  if (!raw.trim()) passThrough("empty stdin");

  let input: HookInput;
  try {
    input = JSON.parse(raw) as HookInput;
  } catch {
    passThrough("stdin not JSON");
  }

  const sessionId = input.session_id;
  const toolName = input.tool_name;
  const toolInput = input.tool_input ?? {};
  if (!sessionId || !toolName) passThrough("missing session_id or tool_name");

  // Initialize Firebase (uses the wrapper's service-account key). If this
  // throws (no key, no network), pass-through so Claude isn't blocked.
  try {
    initFirebase();
  } catch (e) {
    passThrough(`firebase init failed: ${(e as Error).message}`);
  }

  let shared: Awaited<ReturnType<typeof readSharedSession>>;
  try {
    shared = await readSharedSession();
  } catch (e) {
    passThrough(`readSharedSession failed: ${(e as Error).message}`);
  }
  if (!shared) passThrough("no /sharedSession");
  if (shared.kind !== "hook") passThrough(`kind=${shared.kind}, not hook`);
  // Wildcard claim: enable-share.ts couldn't pin down the session ID at
  // /ccwearos time (multiple sessions in cwd, etc.) so it wrote sessionId="".
  // First hook fire grabs ownership using the session_id Claude Code passes
  // in stdin (always correct), writes it back, and proceeds.
  if (!shared.sessionId) {
    try {
      await setSharedSession({ ...shared, sessionId: sessionId! });
    } catch (e) {
      passThrough(`claim failed: ${(e as Error).message}`);
    }
  } else if (shared.sessionId !== sessionId) {
    passThrough(
      `sessionId mismatch (shared=${shared.sessionId}, mine=${sessionId})`,
    );
  }

  // We're responsible for this tool call. Publish + wait.
  if (process.env["CCWEAROS_HOOK_DEBUG"]) {
    process.stderr.write(
      `[ccwearos-hook] matched session, publishing prompt\n`,
    );
  }
  const promptText = describeToolCall(toolName!, toolInput);
  try {
    await setPermissionPrompt(promptText);
    await setStatus("AWAITING_PERMISSION");
    await sendFcmWake("permission");
  } catch (e) {
    process.stderr.write(
      `[ccwearos-hook] publish failed: ${(e as Error).message} — falling through to ask\n`,
    );
    emit({ hookSpecificOutput: { permissionDecision: "ask" } });
  }

  // Clear any stale /command before polling so we don't immediately consume
  // a leftover Allow from a previous run.
  try {
    await db().ref("/command").set(null);
  } catch {
    // best-effort
  }

  const debug = !!process.env["CCWEAROS_HOOK_DEBUG"];
  if (debug) {
    process.stderr.write(
      `[ccwearos-hook] polling /command (max ${POLL_BUDGET_MS}ms)\n`,
    );
  }
  const deadline = Date.now() + POLL_BUDGET_MS;
  const reply = await pollForCommand(deadline);
  if (debug) {
    process.stderr.write(
      `[ccwearos-hook] poll returned: ${JSON.stringify(reply)}\n`,
    );
  }

  // Cleanup regardless of outcome.
  try {
    await setPermissionPrompt(null);
    await setStatus("IDLE");
    await db().ref("/command").set(null);
  } catch {
    // best-effort
  }

  if (reply === null) {
    // Watch never answered. Fall back to Claude's normal Terminal prompt.
    emit({
      hookSpecificOutput: { permissionDecision: "ask" },
      systemMessage:
        "CCWEAROS watch did not respond in time — defaulting to ask",
    });
  }

  // The Claude TUI permission convention is "1" / "1\r" = allow,
  // "" / ESC () = deny. Our watch sends those exact bytes via
  // /command. Map to the hook's decision vocabulary.
  const head = reply!.trim().charAt(0);
  if (head === "1" || head === "2" || head === "y" || head === "Y") {
    emit({
      hookSpecificOutput: { permissionDecision: "allow" },
      systemMessage: "approved via CCWEAROS watch",
    });
  }
  emit({
    hookSpecificOutput: { permissionDecision: "deny" },
    systemMessage: "denied via CCWEAROS watch",
  });
}

main().catch((err) => {
  process.stderr.write(`[ccwearos-hook] fatal: ${(err as Error).message}\n`);
  // Never block Claude on hook bugs.
  process.exit(0);
});
