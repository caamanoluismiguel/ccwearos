// Shared helpers for the hook-side scripts (enable-share, enable-takeover,
// disable-share). Centralizes Claude session detection and process probing.
// No side effects on import — pure functions only.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { isPidAlive } from "../../src/pid-utils.js";

export { isPidAlive };

export interface SessionDetectionResult {
  sessionId: string;
  // How we found it. Useful for callers that want to warn (e.g., takeover
  // from the .jsonl fallback is risky — see the JSDoc on detectSessionId).
  source: "env" | "session-file" | "jsonl-mtime";
  // The pid that owns the session file (null if from jsonl-mtime fallback).
  ownerPid: number | null;
}

// Best-effort: locate the Claude session the user is currently inside.
// Detection order:
//   1. $CLAUDE_SESSION_ID env var if Claude Code exposes it. Most precise.
//   2. Most recently active ~/.claude/sessions/<pid>.json whose cwd matches
//      the caller's cwd and whose pid is still alive. Reliable.
//   3. Most recently mtime'd .jsonl file in the matching project dir
//      (~/.claude/projects/<sanitized-cwd>/). Fragile: it'll happily return
//      the sessionId of the Claude process that's currently invoking the
//      caller via a slash command — including this very conversation. Use
//      with eyes open; callers should refuse to re-spawn an already-live
//      session under wrapper-pty control (you can't `claude --resume` an
//      id that's already locked by another Claude process).
// Returns null when nothing matches.
export function detectSessionId(cwd: string): string | null {
  const detailed = detectSessionIdDetailed(cwd);
  return detailed?.sessionId ?? null;
}

// Same as detectSessionId but returns provenance info so the caller can
// decide policy (e.g., "refuse jsonl-mtime in takeover, warn elsewhere").
export function detectSessionIdDetailed(
  cwd: string,
): SessionDetectionResult | null {
  const envId = process.env["CLAUDE_SESSION_ID"];
  if (envId) {
    return { sessionId: envId, source: "env", ownerPid: null };
  }

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
    if (entries[0]) {
      return {
        sessionId: entries[0].sessionId,
        source: "session-file",
        ownerPid: entries[0].pid,
      };
    }
  } catch {
    // sessions dir missing or unreadable
  }

  // Last resort: scan ~/.claude/projects/<sanitized-cwd>/*.jsonl by mtime.
  // No way to know the owning pid from a .jsonl filename alone; the file may
  // belong to a dead session OR to a live one whose ~/.claude/sessions entry
  // we couldn't read. Callers in lock-sensitive flows (takeover) should
  // treat this provenance as "needs human confirmation" — see jsdoc above.
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
    if (jsonls[0]) {
      return {
        sessionId: jsonls[0].sessionId,
        source: "jsonl-mtime",
        ownerPid: null,
      };
    }
  } catch {
    // project dir missing
  }
  return null;
}

// Reads ~/.claude/settings.local.json then settings.json (local wins, same
// merge order Claude itself uses) for permissions.defaultMode. Returns null
// when neither file is present or the field is unset.
export function detectPermissionMode(): string | null {
  for (const fname of ["settings.local.json", "settings.json"]) {
    try {
      const path = join(homedir(), ".claude", fname);
      const raw = readFileSync(path, "utf8");
      const parsed = JSON.parse(raw) as {
        permissions?: { defaultMode?: string };
      };
      const m = parsed.permissions?.defaultMode;
      if (typeof m === "string" && m.length > 0) return m;
    } catch {
      // file missing or malformed — try the next one
    }
  }
  return null;
}
