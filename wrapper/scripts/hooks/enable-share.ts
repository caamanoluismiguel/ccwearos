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

import { readFileSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import {
  initFirebase,
  setSharedSession,
  readSharedSession,
} from "../../src/firebase.js";
import type { SharedSessionMeta } from "../../src/types/schema.js";

function isPidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function detectSessionId(cwd: string): string | null {
  const envId = process.env["CLAUDE_SESSION_ID"];
  if (envId) return envId;

  const sessionsDir = join(homedir(), ".claude/sessions");
  try {
    const entries = readdirSync(sessionsDir)
      .filter((f) => f.endsWith(".json"))
      .map((f) => {
        try {
          const raw = readFileSync(join(sessionsDir, f), "utf8");
          return JSON.parse(raw) as {
            pid?: number;
            sessionId?: string;
            cwd?: string;
            updatedAt?: number;
          };
        } catch {
          return null;
        }
      })
      .filter(
        (
          e,
        ): e is {
          pid: number;
          sessionId: string;
          cwd: string;
          updatedAt: number;
        } =>
          e !== null &&
          typeof e.pid === "number" &&
          typeof e.sessionId === "string" &&
          e.cwd === cwd &&
          isPidAlive(e.pid),
      )
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));
    if (entries[0]) return entries[0].sessionId;
  } catch {
    // sessions dir missing or unreadable
  }

  // Last resort: scan ~/.claude/projects/<sanitized-cwd>/*.jsonl by mtime.
  try {
    const projDir = join(
      homedir(),
      ".claude/projects",
      "-" + cwd.replace(/^\//, "").replace(/\//g, "-"),
    );
    const jsonls = readdirSync(projDir)
      .filter((f) => f.endsWith(".jsonl"))
      .map((f) => ({
        sessionId: basename(f, ".jsonl"),
        mtime: statSync(join(projDir, f)).mtimeMs,
      }))
      .sort((a, b) => b.mtime - a.mtime);
    if (jsonls[0]) return jsonls[0].sessionId;
  } catch {
    // project dir missing
  }
  return null;
}

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
