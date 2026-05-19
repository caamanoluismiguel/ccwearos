// Invoked from the `/ccwearos` slash command. Detects the current Claude
// session ID and writes /sharedSession to RTDB with kind="hook" so the
// PreToolUse hook starts bridging permission prompts to the watch.
//
// Detection order:
//   1. $CLAUDE_SESSION_ID env var if Claude Code exposes it
//   2. Most recently active ~/.claude/sessions/<pid>.json whose cwd matches
//      this process's cwd (slash commands inherit Claude's cwd)
//   3. Most recently mtime'd .jsonl file in the matching project dir
//
// On any failure prints a one-line diagnostic so the user sees it in the
// Claude TUI output. Never exits non-zero (the slash command must always
// "succeed" so Claude completes the turn).

import {
  initFirebase,
  setSharedSession,
  readSharedSession,
} from "../../src/firebase.js";
import type { SharedSessionMeta } from "../../src/types/schema.js";
import {
  detectPermissionMode,
  detectSessionId,
  isPidAlive,
} from "./_helpers.js";

async function main(): Promise<void> {
  const cwd = process.cwd();
  initFirebase();

  // Refuse if a wrapper-pty (cc) session is alive — two pty's clobber RTDB.
  const existing = await readSharedSession();
  if (existing && existing.kind === "wrapper-pty" && isPidAlive(existing.pid)) {
    console.log(
      `[ccwearos] Already bridged via cc in ${existing.cwd}. Close that first.`,
    );
    return;
  }

  // Detect this session's ID best-effort. If we can't pin it down precisely
  // (multiple sessions in the same cwd, env var missing, etc.) leave it
  // empty — the PreToolUse hook will claim the wildcard on its first fire
  // using the session_id Claude Code passes in stdin (always correct there).
  const guessedSessionId = detectSessionId(cwd);
  const sessionId = guessedSessionId ?? "";

  const meta: SharedSessionMeta = {
    sessionId,
    pid: process.ppid || process.pid,
    cwd,
    startedAt: Date.now(),
    kind: "hook",
  };
  await setSharedSession(meta);
  const idLabel = sessionId
    ? `sessionId=${sessionId.slice(0, 8)}…`
    : "wildcard (hook will claim on first tool call)";
  console.log(`[ccwearos] ✓ Session bridged (${idLabel}).`);

  // Detect the Claude permission mode. If it's NOT dontAsk (or bypassPermissions),
  // Claude will keep its Terminal prompt visible even after our hook returns
  // "allow" — the watch effectively becomes a double-confirm. Tell the user up
  // front about the canonical alternative: /ccwearos-takeover (one-step handoff
  // to a new Terminal under wrapper-pty control with dontAsk pre-applied).
  const mode = detectPermissionMode();
  if (mode && mode !== "dontAsk" && mode !== "bypassPermissions") {
    console.log("");
    console.log(
      `[ccwearos] ⚠️  Tu Claude está en modo '${mode}'. El reloj puede autorizar`,
    );
    console.log(
      "[ccwearos]    PERO Claude también te preguntará en este Terminal (doble-confirm).",
    );
    console.log("");
    console.log(
      "[ccwearos]    👉 Para irte del Mac sin doble-confirm, usá /ccwearos-takeover:",
    );
    console.log(
      "[ccwearos]       Abre una nueva Terminal con esta sesión resumida bajo `cc`",
    );
    console.log(
      "[ccwearos]       (permission-mode=dontAsk → el reloj es el único gate).",
    );
    console.log(
      "[ccwearos]    Alternativa manual: cerrar Claude y reabrir con",
    );
    console.log("[ccwearos]      claude --permission-mode dontAsk");
  }
  console.log("");
  console.log(
    "[ccwearos] Permission prompts will now appear on your watch. Tap Allow/Deny from your wrist.",
  );
  console.log("[ccwearos] Run /ccwearos-off to disable.");
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.log(`[ccwearos] error: ${(err as Error).message}`);
    process.exit(0); // never fail the slash command
  });
