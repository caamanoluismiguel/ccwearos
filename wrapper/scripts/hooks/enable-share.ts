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

import { db, initFirebase } from "../../src/firebase.js";
import { isSharedSessionStale } from "../../src/shared-session.js";
import type { SharedSessionMeta } from "../../src/types/schema.js";
import {
  detectPermissionMode,
  detectSessionId,
  findOwningClaudePid,
  isPidAlive,
} from "./_helpers.js";

async function main(): Promise<void> {
  const cwd = process.cwd();
  initFirebase();

  // Detect this session's ID best-effort. If we can't pin it down precisely
  // (multiple sessions in the same cwd, env var missing, etc.) leave it
  // empty — the PreToolUse hook will claim the wildcard on its first fire
  // using the session_id Claude Code passes in stdin (always correct there).
  const guessedSessionId = detectSessionId(cwd);
  const sessionId = guessedSessionId ?? "";

  // The real owner is the Claude CLI ancestor — process.ppid is the
  // short-lived shell the slash command runs in, which dies immediately and
  // would make the lock look dead (or, worse, alive forever via pid reuse).
  const ownerPid = findOwningClaudePid();
  const now = Date.now();
  const meta: SharedSessionMeta = {
    sessionId,
    pid: ownerPid ?? (process.ppid || process.pid),
    cwd,
    startedAt: now,
    kind: "hook",
    heartbeatAt: now,
    ...(ownerPid !== null ? { ownerPid } : {}),
  };

  // Atomic claim: refuse if a live wrapper-pty (cc) session holds the lock —
  // two pty's clobber RTDB. A stale lock (dead pid) is taken over. An
  // existing hook bridge is replaced (the user just asked for THIS session).
  let blockedBy: SharedSessionMeta | null = null;
  let replaced: SharedSessionMeta | null = null;
  const result = await db()
    .ref("/sharedSession")
    .transaction((current: SharedSessionMeta | null) => {
      blockedBy = null;
      replaced = current;
      if (
        current &&
        current.kind === "wrapper-pty" &&
        !isSharedSessionStale(current, now, isPidAlive)
      ) {
        blockedBy = current;
        return undefined; // abort — leave the live cc lock untouched
      }
      return meta;
    });
  if (!result.committed) {
    const b = blockedBy as SharedSessionMeta | null;
    console.log(
      `[ccwearos] Already bridged via cc in ${b?.cwd ?? "another Terminal"}. Close that first.`,
    );
    return;
  }
  const prev = replaced as SharedSessionMeta | null;
  if (
    prev &&
    prev.kind === "hook" &&
    prev.sessionId &&
    prev.sessionId !== sessionId &&
    !isSharedSessionStale(prev, now, isPidAlive)
  ) {
    console.log(
      `[ccwearos] (Replaced the previous /ccwearos bridge in ${prev.cwd}.)`,
    );
  }
  const idLabel = sessionId
    ? `sessionId=${sessionId.slice(0, 8)}…`
    : "wildcard (hook will claim on first tool call)";
  console.log(`[ccwearos] ✓ Session bridged (${idLabel}).`);

  // The hook returns an explicit PreToolUse decision ("allow" / "deny"), which
  // per code.claude.com/docs/en/hooks bypasses Claude's own permission check
  // for that call — so a watch answer is final in any mode (no Terminal
  // double-confirm). Only a watch timeout ("ask") brings the question back
  // here. Deny rules in settings.json still win over the hook.
  console.log("");
  console.log(
    "[ccwearos] Lo que respondas en el reloj es definitivo: Claude no te vuelve a preguntar aquí.",
  );
  console.log(
    "[ccwearos] Si el reloj no responde en ~55s, la pregunta vuelve a este Terminal.",
  );
  console.log(
    "[ccwearos] Tus reglas deny de settings.json siguen bloqueando aunque apruebes en el reloj.",
  );
  const mode = detectPermissionMode();
  if (mode === "bypassPermissions") {
    console.log(
      "[ccwearos] Ojo: estás en bypassPermissions, así que ahora el reloj te pedirá TODAS las herramientas.",
    );
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
