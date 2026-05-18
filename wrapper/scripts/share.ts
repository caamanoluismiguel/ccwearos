// Shared-with-watch Claude session. The user types `cc` (or this script
// directly) in any Terminal and gets a normal interactive Claude experience
// — pty mirrored to stdout, stdin forwarded to pty — AND the watch sees
// every state change in real-time and can answer permission prompts.
//
// Internally this is exactly the same `startClaude` runner that `npm start`
// (interactive mode) uses; we just add /sharedSession bookkeeping so the
// daemon knows to back off voice prompts while we're alive, and a
// session-scanner so /recentSessions stays fresh while we run.
//
// Usage:
//   cd ~/projects/anything
//   npx tsx ~/projects/CCWEAROS/wrapper/scripts/share.ts
//
// Or, after installing the shell alias from CLAUDE.md:
//   cc
//
// Exit cleanly with Ctrl+C or `/exit` in the Claude TUI.

import { config } from "../src/config.js";
import { startClaude } from "../src/claude-runner.js";
import {
  clearCommand,
  initFirebase,
  readSharedSession,
  sendFcmWake,
  setActivity,
  setClaudeStatus,
  setPermissionPrompt,
  setResponse,
  setSharedSession,
  setStatus,
  setTask,
  watchCommands,
  writeMetrics,
} from "../src/firebase.js";
import { startSessionScanner } from "../src/sessions-scanner.js";
import type { SharedSessionMeta } from "../src/types/schema.js";
import { readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

function isPidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function main(): Promise<void> {
  initFirebase();

  // Reject if another shared session is alive. Clean stale locks
  // (PID dead) so a previous crash doesn't permanently block the user.
  const existing = await readSharedSession();
  if (existing) {
    if (isPidAlive(existing.pid)) {
      console.error(
        `[cc] Another shared session is already active (pid=${existing.pid}, cwd=${existing.cwd}).`,
      );
      console.error(
        `[cc] Close that one first, or run plain \`${config.claudeCliCommand}\` here without watch monitoring.`,
      );
      process.exit(2);
    }
    console.warn(
      `[cc] Stale /sharedSession lock from pid=${existing.pid} — clearing.`,
    );
    await setSharedSession(null);
  }

  const cwd = process.cwd();
  const meta: SharedSessionMeta = {
    sessionId: "", // filled in once Claude writes its ~/.claude/sessions/<pid>.json
    pid: process.pid,
    cwd,
    startedAt: Date.now(),
  };
  await setSharedSession(meta);
  await clearCommand();
  await setStatus("IDLE");
  console.log(`[cc] Shared session online (cwd=${cwd}, pid=${process.pid})`);
  console.log(
    `[cc] Watch will see permission prompts. Ctrl+C or /exit to close.`,
  );

  // Session scanner — surfaces this AND every other Claude session on the
  // Mac to /recentSessions while we're running. The shared session gets
  // shared=true once we know our sessionId.
  let currentSessionId: string | null = null;
  const stopScanner = startSessionScanner({
    getSharedSessionId: () => currentSessionId,
  });

  let exitRequested = false;
  const cleanup = async (signal: string): Promise<void> => {
    if (exitRequested) return;
    exitRequested = true;
    console.log(`\n[cc] ${signal} — cleaning up shared session.`);
    stopScanner();
    try {
      await setSharedSession(null);
      await setStatus("OFFLINE");
    } catch (e) {
      console.error("[cc] cleanup failed:", (e as Error).message);
    }
  };

  const runner = startClaude({
    onStatus: (s) => {
      void setStatus(s);
    },
    onMetrics: (m) => {
      void writeMetrics(m);
    },
    onPermission: (prompt) => {
      void setPermissionPrompt(prompt);
      void setStatus("AWAITING_PERMISSION");
      void sendFcmWake("permission");
    },
    onActivity: (text) => {
      void setActivity(text);
    },
    onTask: (text) => {
      void setTask(text);
    },
    onResponse: (text) => {
      void setResponse(text);
    },
    onClaudeStatus: (s) => {
      void setClaudeStatus(s);
    },
    onExit: async (code) => {
      console.log(`\n[cc] Claude CLI exited with code ${code}`);
      await cleanup("claude-exit");
      process.exit(code ?? 0);
    },
  });

  // Permission responses + freeform stdin from the watch arrive via /command.
  // The interactive runner already accepts these via runner.send(text).
  const stopWatching = watchCommands(async (cmd) => {
    const ageSec = (Date.now() - cmd.issuedAt) / 1000;
    if (ageSec > config.commandMaxAgeSeconds) {
      console.warn(`[cc] Stale command (age ${ageSec.toFixed(1)}s) — dropped.`);
      await clearCommand();
      return;
    }
    runner.send(cmd.text);
    await clearCommand();
    await setPermissionPrompt(null);
  });

  // Fire-and-forget: once Claude has booted, read its sessionId from disk
  // and update /sharedSession so the watch can highlight the right row.
  void (async () => {
    // pty.pid lives inside startClaude scope; use a tiny delay-then-scan
    // strategy that checks ALL active session files for one matching our
    // wrapper-script pid's child. We don't have direct access to pty.pid
    // from out here, so we just look for the most recently created file
    // whose cwd matches ours.
    const sessionsDir = join(homedir(), ".claude/sessions");
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline && !exitRequested) {
      try {
        const candidates = readdirSync(sessionsDir)
          .filter((f) => f.endsWith(".json"))
          .map((f) => {
            const raw = readFileSync(join(sessionsDir, f), "utf8");
            return JSON.parse(raw) as {
              pid?: number;
              sessionId?: string;
              cwd?: string;
              startedAt?: number;
            };
          })
          .filter(
            (s) => s.cwd === cwd && (s.startedAt ?? 0) >= meta.startedAt - 2000,
          );
        if (candidates.length > 0) {
          // Pick the latest startedAt — most recently spawned matches our run.
          candidates.sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0));
          const found = candidates[0];
          if (found?.sessionId) {
            currentSessionId = found.sessionId;
            await setSharedSession({ ...meta, sessionId: found.sessionId });
            console.log(`[cc] Tracking sessionId=${found.sessionId}`);
            return;
          }
        }
      } catch {
        // sessions dir might not exist yet
      }
      await new Promise((r) => setTimeout(r, 500));
    }
  })();

  const onSignal = (sig: string) => (): void => {
    void (async () => {
      await cleanup(sig);
      stopWatching();
      runner.kill();
      // Give cleanup writes a moment before exiting.
      setTimeout(() => process.exit(0), 200);
    })();
  };
  process.on("SIGINT", onSignal("SIGINT"));
  process.on("SIGTERM", onSignal("SIGTERM"));
}

main().catch(async (err) => {
  console.error("[cc] Fatal:", err);
  try {
    await setSharedSession(null);
  } catch {
    // best effort
  }
  process.exit(1);
});
