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
// Output contract — PreToolUse decision control, verified 2026-10-01 against
// https://code.claude.com/docs/en/hooks#pretooluse-decision-control :
//   stdout JSON: { "hookSpecificOutput": { "hookEventName": "PreToolUse",
//     "permissionDecision": "allow"|"deny"|"ask",
//     "permissionDecisionReason": "..." } }
//   - "allow" bypasses the permission system for this call (no Terminal
//     prompt, works under any mode); deny RULES in settings still win.
//   - "deny" blocks the call; the reason is shown to Claude.
//   - "ask" shows Claude's normal permission dialog regardless of mode.
//   - Exit 0 with no JSON = "defer" (normal permission flow) — what every
//     pass-through below does for sessions we don't bridge.
//   exit 0 always (we never want to crash a Claude session because of us).
//
// Polling budget: 55s. Kept short on purpose (the documented PreToolUse
// default timeout is 600s): after 55s we hand the decision back to the
// Terminal with "ask" instead of blocking Claude for minutes.
//
// Permission prompt protocol (one-time ids, see src/command-guard.ts):
//   - publishPermissionPrompt() writes /permissionPrompt + a fresh
//     /permissionPromptId; we only accept a /command whose promptId is one we
//     minted (checkCommand). Consumption is a transaction on /command so two
//     overlapping hook runs (parallel tool calls) can't both take one tap.
//   - Parallel tool calls: a later run overwrites the visible prompt. The
//     earlier run keeps polling; once the active id clears (the later run got
//     its answer) it re-publishes its own prompt with a new id. Prompts are
//     thus answered one at a time instead of one silently timing out.
//   - We never blind-clear /command or /permissionPrompt: cleanup clears the
//     prompt only if /permissionPromptId is still one of ours.

import { appendFileSync, writeSync } from "node:fs";
import {
  appendAuditEntry,
  clearCrashCleanup,
  db,
  initFirebase,
  publishPermissionPrompt,
  readSharedSession,
  registerCrashCleanup,
  sendFcmWake,
  setStatus,
} from "../../src/firebase.js";
import { checkCommand } from "../../src/command-guard.js";
import {
  describeToolCall,
  hookDecision,
  type HookOutput,
} from "../../src/shared-session.js";
import type {
  PendingCommand,
  SharedSessionMeta,
} from "../../src/types/schema.js";

// Every hook invocation appends one line here so we can post-mortem when
// things go wrong. Includes timestamp, what we decided, why. Append-only.
const HOOK_LOG_PATH = "/tmp/ccwearos-hook.log";
function dlog(msg: string): void {
  try {
    appendFileSync(
      HOOK_LOG_PATH,
      `${new Date().toISOString()} pid=${process.pid} ${msg}\n`,
    );
  } catch {
    /* best-effort */
  }
}

interface HookInput {
  session_id?: string;
  hook_event_name?: string;
  tool_name?: string;
  tool_input?: Record<string, unknown>;
  cwd?: string;
}

const decision = hookDecision;

const POLL_INTERVAL_MS = 500;
const POLL_BUDGET_MS = 55_000;

// Crash-cleanup bookkeeping. Once armed (registerCrashCleanup), EVERY exit
// path must disarm (clearCrashCleanup) first — otherwise the server-side
// onDisconnect fires when our socket closes and wipes UI state that another
// flow may own by then.
let crashCleanupArmed = false;
async function disarmCrashCleanup(): Promise<void> {
  if (!crashCleanupArmed) return;
  crashCleanupArmed = false;
  try {
    await clearCrashCleanup();
  } catch (e) {
    dlog(`clearCrashCleanup failed: ${(e as Error).message}`);
  }
}

async function exitWith(code: number): Promise<never> {
  await disarmCrashCleanup();
  process.exit(code);
}

async function emit(out: HookOutput): Promise<never> {
  const json = JSON.stringify(out);
  dlog(`emit: ${json}`);
  // Write SYNCHRONOUSLY to fd 1 — process.stdout.write() buffers when stdout
  // is a pipe (which is how Claude Code invokes hooks), and process.exit()
  // truncates any pending writes. writeSync bypasses the libuv pipe buffer.
  writeSync(1, json + "\n");
  return exitWith(0);
}

// Only used BEFORE crash cleanup is armed (sync exit is safe there).
function passThrough(reason: string): never {
  dlog(`pass-through: ${reason}`);
  if (process.env["CCWEAROS_HOOK_DEBUG"]) {
    process.stderr.write(`[ccwearos-hook] pass-through: ${reason}\n`);
  }
  process.exit(0);
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

// Prompt ids this run has minted (a re-publish mints a new one; an answer to
// an earlier one of ours is still ours to take).
const myPromptIds = new Set<string>();
let currentPromptId: string | null = null;

async function publishMyPrompt(promptText: string): Promise<void> {
  const id = await publishPermissionPrompt(promptText);
  myPromptIds.add(id);
  currentPromptId = id;
  await setStatus("AWAITING_PERMISSION");
  await sendFcmWake("permission");
}

// Clear /permissionPrompt (+ id, + status) only if the active id is still
// one of ours — never wipe a prompt another hook run published meanwhile.
async function clearMyPrompt(): Promise<void> {
  if (myPromptIds.size === 0) return;
  const box = { cleared: false };
  await db()
    .ref("/permissionPromptId")
    .transaction((cur: string | null) => {
      box.cleared = false;
      if (cur === null) return null; // cache miss or already clear → no-op
      if (!myPromptIds.has(cur)) return undefined; // someone else's — abort
      box.cleared = true;
      return null;
    });
  if (box.cleared) {
    // One write so a run waiting to re-publish (it waits for BOTH id and
    // prompt to be null) can't be overwritten by our trailing IDLE.
    await db().ref().update({ permissionPrompt: null, status: "IDLE" });
  }
}

function isMyAnswer(cmd: PendingCommand | null, pollStartedAt: number): boolean {
  if (!cmd || typeof cmd.text !== "string") return false;
  // Defense in depth (hard rule): ignore /command entries written before we
  // started polling, even if the id somehow matched.
  if ((cmd.issuedAt ?? 0) < pollStartedAt - 1_000) return false;
  if (typeof cmd.promptId !== "string" || !myPromptIds.has(cmd.promptId)) {
    return false;
  }
  const verdict = checkCommand(cmd, cmd.promptId);
  return verdict.ok && verdict.kind === "answer";
}

// Atomically take /command iff it answers one of our prompts. Returns the
// answer text, or null if it wasn't ours / someone else consumed it first.
async function tryConsume(pollStartedAt: number): Promise<string | null> {
  const box: { text: string | null } = { text: null };
  const res = await db()
    .ref("/command")
    .transaction((cur: PendingCommand | null) => {
      box.text = null;
      if (cur === null) return null; // cache miss → server re-runs us
      if (!isMyAnswer(cur, pollStartedAt)) return undefined; // not ours
      box.text = cur.text;
      return null; // consume
    });
  return res.committed ? box.text : null;
}

async function pollForAnswer(
  deadline: number,
  pollStartedAt: number,
  promptText: string,
): Promise<string | null> {
  const debug = !!process.env["CCWEAROS_HOOK_DEBUG"];
  let iterations = 0;
  while (Date.now() < deadline) {
    iterations++;
    try {
      const [cmdSnap, idSnap] = await Promise.all([
        db().ref("/command").once("value"),
        db().ref("/permissionPromptId").once("value"),
      ]);
      const cmd = cmdSnap.val() as PendingCommand | null;
      const activeId = idSnap.val() as string | null;
      if (debug && (iterations <= 3 || iterations % 20 === 0)) {
        process.stderr.write(
          `[ccwearos-hook] poll #${iterations}: cmd=${cmd ? "got" : "null"} active=${activeId === currentPromptId ? "mine" : activeId ? "other" : "none"}\n`,
        );
      }
      if (isMyAnswer(cmd, pollStartedAt)) {
        const text = await tryConsume(pollStartedAt);
        if (text !== null) return text;
      } else if (activeId === null) {
        // Our prompt was superseded by a parallel hook run which has since
        // been answered and cleared. Re-publish once the prompt text is
        // gone too (the other run's final write).
        const promptSnap = await db().ref("/permissionPrompt").once("value");
        if (promptSnap.val() === null) {
          dlog("re-publishing prompt after superseding run cleared");
          await publishMyPrompt(promptText);
        }
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
  dlog("hook invoked");
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
  dlog(`stdin parsed: tool=${toolName} session=${sessionId?.slice(0, 8)}`);
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
  if (shared.sessionId && shared.sessionId !== sessionId) {
    passThrough(
      `sessionId mismatch (shared=${shared.sessionId}, mine=${sessionId})`,
    );
  }

  // Atomic claim + heartbeat. Wildcard (sessionId="") → first hook fire
  // takes ownership with the session_id Claude Code passes in stdin (always
  // correct). Matching session → refresh heartbeatAt so the lock never looks
  // stale while this Claude is active (isSharedSessionStale). Anything else
  // (cleared, kind changed, another session won the wildcard) → abort.
  try {
    const claim = await db()
      .ref("/sharedSession")
      .transaction((cur: SharedSessionMeta | null) => {
        if (cur === null) return null; // cache miss → server re-runs us
        if (cur.kind !== "hook") return undefined;
        if (cur.sessionId && cur.sessionId !== sessionId) return undefined;
        return { ...cur, sessionId: sessionId!, heartbeatAt: Date.now() };
      });
    const now = claim.snapshot.val() as SharedSessionMeta | null;
    if (
      !claim.committed ||
      !now ||
      now.kind !== "hook" ||
      now.sessionId !== sessionId
    ) {
      passThrough("lost /sharedSession claim (changed concurrently)");
    }
  } catch (e) {
    passThrough(`claim failed: ${(e as Error).message}`);
  }

  // We're responsible for this tool call. Arm crash cleanup (hard rule) now
  // that we're about to own UI surfaces — not earlier: every tool call of
  // every Claude session on this Mac runs this hook, and arming on the
  // pass-through path would let a host-killed pass-through wipe a live cc
  // session's UI via onDisconnect. From here on, exits go through
  // exitWith()/emit() which disarm first.
  try {
    await registerCrashCleanup({ uiSurfaces: true });
    crashCleanupArmed = true;
  } catch (e) {
    dlog(`registerCrashCleanup failed: ${(e as Error).message}`);
  }

  if (process.env["CCWEAROS_HOOK_DEBUG"]) {
    process.stderr.write(
      `[ccwearos-hook] matched session, publishing prompt\n`,
    );
  }

  // Install signal handlers BEFORE publishing /permissionPrompt. The Claude
  // Code host may kill us with SIGTERM if it hits its 60s hook timeout, or
  // SIGINT if the user Ctrl-C's mid-prompt. Without this handler the script
  // dies mid-poll and leaves /permissionPrompt + /status="AWAITING_PERMISSION"
  // set forever — the watch shows a stale prompt indefinitely. Audit C-5.
  let cleanupRan = false;
  const cleanup = async (reason: string): Promise<void> => {
    if (cleanupRan) return;
    cleanupRan = true;
    dlog(`cleanup (${reason})`);
    try {
      await clearMyPrompt();
    } catch (e) {
      dlog(`cleanup failed: ${(e as Error).message}`);
    }
  };
  const onSignal = (sig: NodeJS.Signals): void => {
    void cleanup(sig).finally(() => void exitWith(0));
  };
  process.on("SIGTERM", onSignal);
  process.on("SIGINT", onSignal);
  process.on("uncaughtException", (err) => {
    dlog(`uncaught: ${err.message}`);
    void cleanup("uncaughtException").finally(() => void exitWith(0));
  });

  // No blind /command clear here: answers are matched by promptId, so an
  // old entry can't be mistaken for ours — and clearing would wipe another
  // overlapping run's freshly tapped answer.
  const promptText = describeToolCall(toolName!, toolInput);
  const pollStartedAt = Date.now();
  try {
    await publishMyPrompt(promptText);
  } catch (e) {
    process.stderr.write(
      `[ccwearos-hook] publish failed: ${(e as Error).message} — falling through to ask\n`,
    );
    await cleanup("publish-failed");
    await emit(
      decision("ask", "No se pudo enviar el permiso al reloj; confirma aquí"),
    );
  }

  const debug = !!process.env["CCWEAROS_HOOK_DEBUG"];
  if (debug) {
    process.stderr.write(
      `[ccwearos-hook] polling /command (max ${POLL_BUDGET_MS}ms)\n`,
    );
  }
  const deadline = pollStartedAt + POLL_BUDGET_MS;
  const reply = await pollForAnswer(deadline, pollStartedAt, promptText);
  if (debug) {
    process.stderr.write(
      `[ccwearos-hook] poll returned: ${JSON.stringify(reply)}\n`,
    );
  }

  // Cleanup regardless of outcome — shared with the signal handlers above
  // so a SIGTERM mid-poll also clears /permissionPrompt + /status.
  await cleanup("normal-exit");

  if (reply === null) {
    // Watch never answered. Fall back to Claude's normal Terminal prompt.
    // AWAIT the audit write — `void` + immediate process.exit drops the
    // RTDB call before the request leaves the socket. Audit AU-1.
    await appendAuditEntry({
      ts: Date.now(),
      kind: "hook",
      tool: toolName!,
      args: promptText.slice(0, 60),
      decision: "timeout",
      source: "auto",
    });
    await emit({
      ...decision("ask", "El reloj no respondió a tiempo; confirma aquí"),
      systemMessage:
        "CCWEAROS watch did not respond in time — defaulting to ask",
    });
  }

  // checkCommand already restricted reply to the TUI vocabulary:
  // "1\r" / "2\r" = allow, "" / ESC = deny.
  const head = reply!.trim().charAt(0);
  if (head === "1" || head === "2") {
    // ALLOW path: an explicit "allow" decision. A bare exit 0 is "defer"
    // per the docs — Claude then runs its own permission check (Terminal
    // double-confirm, or auto-deny under dontAsk). "allow" skips it.
    await appendAuditEntry({
      ts: Date.now(),
      kind: "hook",
      tool: toolName!,
      args: promptText.slice(0, 60),
      decision: "allow",
      source: "watch",
    });
    await emit(decision("allow", "Aprobado desde el reloj"));
  }
  // DENY path: explicit "deny" decision; the reason is shown to Claude.
  await appendAuditEntry({
    ts: Date.now(),
    kind: "hook",
    tool: toolName!,
    args: promptText.slice(0, 60),
    decision: "deny",
    source: "watch",
  });
  await emit(decision("deny", "Rechazado desde el reloj"));
}

main().catch(async (err) => {
  process.stderr.write(`[ccwearos-hook] fatal: ${(err as Error).message}\n`);
  // Never block Claude on hook bugs.
  await exitWith(0);
});
