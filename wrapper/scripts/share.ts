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
// Takeover mode (from /ccwearos-takeover slash command):
//   cc --resume <sessionId>
//
//   Resumes an existing Claude session under wrapper-pty control AND forces
//   --permission-mode dontAsk so the watch is the sole permission gate (no
//   Terminal double-confirm). The takeover flow opens this in a new Terminal
//   window via osascript; see scripts/hooks/enable-takeover.ts.
//
// Exit cleanly with Ctrl+C or `/exit` in the Claude TUI.

import { config } from "../src/config.js";
import { startClaude } from "../src/claude-runner.js";
import {
  appendAuditEntry,
  clearCommand,
  clearCrashCleanup,
  clearStaleState,
  initFirebase,
  readSharedSession,
  registerCrashCleanup,
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
import { isPidAlive } from "../src/pid-utils.js";
import { startSessionScanner } from "../src/sessions-scanner.js";
import { parseShareArgs, type ShareArgs } from "../src/share-args.js";
import type { SharedSessionMeta } from "../src/types/schema.js";
import { readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

async function main(): Promise<void> {
  let parsed: ShareArgs;
  try {
    parsed = parseShareArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`[cc] ${(e as Error).message}`);
    process.exit(2);
  }
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
  // In takeover mode we already know the sessionId — pre-seed /sharedSession
  // so any concurrent PreToolUse hook fire from the OLD Terminal sees
  // kind="wrapper-pty" immediately and bails (vs. trying to bridge to a
  // watch that's about to be re-pointed at us).
  const meta: SharedSessionMeta = {
    sessionId: parsed.resumeSessionId ?? "",
    pid: process.pid,
    cwd,
    startedAt: Date.now(),
    kind: "wrapper-pty",
  };
  await setSharedSession(meta);
  await clearCommand();
  await setStatus("IDLE");

  // Register Firebase server-side cleanup BEFORE we do any real work. If we
  // get SIGKILL'd or the Mac crashes between here and clean shutdown, the
  // server will clear /sharedSession + UI surfaces when our TCP drops —
  // which is the only mechanism that survives `kill -9` / OOM.
  await registerCrashCleanup({ sharedSession: true, uiSurfaces: true });
  // Defensive re-assertion at 8s in case a previous wrapper-pty's
  // onDisconnect fires server-side after we've already written IDLE.
  // See wrapper/src/index.ts:runDaemon for the full race explanation.
  setTimeout(() => {
    void setStatus("IDLE").catch(() => {});
  }, 8_000).unref();

  if (parsed.resumeSessionId) {
    console.log(
      `[cc] Takeover online — resuming sessionId=${parsed.resumeSessionId.slice(0, 8)}… (cwd=${cwd}, pid=${process.pid})`,
    );
    console.log(
      `[cc] permission-mode=dontAsk: el reloj decide solo, sin doble-confirm en Terminal.`,
    );
  } else {
    console.log(`[cc] Shared session online (cwd=${cwd}, pid=${process.pid})`);
  }
  console.log(
    `[cc] Watch will see permission prompts. Ctrl+C or /exit to close.`,
  );

  // Session scanner — surfaces this AND every other Claude session on the
  // Mac to /recentSessions while we're running. The shared session gets
  // shared=true once we know our sessionId.
  let currentSessionId: string | null = parsed.resumeSessionId;
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
      // Clear ALL UI surfaces (status / task / permissionPrompt / activity /
      // response / etc.) — the old "just clear /sharedSession + /status"
      // code was the reason the watch saw phantom "shared session" UI after
      // the self-takeover crash (audit C-2).
      await clearStaleState();
      await setSharedSession(null);
    } catch (e) {
      console.error("[cc] cleanup failed:", (e as Error).message);
    }
    // Cancel the server-side onDisconnect — we already wrote OFFLINE, no
    // need for the server to fire it again when our TCP closes.
    await clearCrashCleanup();
    // Grace period so the cancel reaches the server before our TCP closes.
    // Without this, the server fires the (uncanceled-from-its-POV)
    // onDisconnect on top of our shutdown writes — a race observed on
    // 2026-05-19 that left /status=OFFLINE for a healthy daemon after
    // a LaunchAgent restart.
    await new Promise((r) => setTimeout(r, 250));
  };

  // Build CLI args for `claude`. Takeover mode forces dontAsk so the watch
  // is the SOLE permission gate — no Terminal prompt fallback. Non-takeover
  // `cc` inherits whatever the user's settings.json defaultMode is (usually
  // safe-by-default with permission prompts).
  const extraArgs: string[] = parsed.resumeSessionId
    ? ["--resume", parsed.resumeSessionId, "--permission-mode", "dontAsk"]
    : [];

  const runner = startClaude(
    {
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
    },
    { extraArgs },
  );

  // Permission responses + freeform stdin from the watch arrive via /command.
  // The interactive runner already accepts these via runner.send(text).
  const stopWatching = watchCommands(async (cmd) => {
    const ageSec = (Date.now() - cmd.issuedAt) / 1000;
    if (ageSec > config.commandMaxAgeSeconds) {
      console.warn(`[cc] Stale command (age ${ageSec.toFixed(1)}s) — dropped.`);
      await clearCommand();
      return;
    }
    // SIGINT cancel from the watch: tear down the shared session cleanly.
    if (cmd.text === "\x03") {
      console.log("[cc] SIGINT from watch — closing shared session");
      void appendAuditEntry({
        ts: Date.now(),
        kind: "cc",
        tool: "(cancel)",
        args: "",
        decision: "deny",
        source: "watch",
      });
      await clearCommand();
      await setPermissionPrompt(null);
      runner.kill();
      return;
    }
    void appendAuditEntry({
      ts: Date.now(),
      kind: "cc",
      tool: "(cc-permission)",
      args: cmd.text.slice(0, 60),
      decision: cmd.text.trim().startsWith("1") ? "allow" : "deny",
      source: "watch",
    });
    runner.send(cmd.text);
    await clearCommand();
    await setPermissionPrompt(null);
  });

  // Fire-and-forget: once Claude has booted, read its sessionId from disk
  // and update /sharedSession so the watch can highlight the right row.
  // Skipped in takeover mode — we already know it from --resume <id>.
  if (parsed.resumeSessionId === null)
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
              (s) =>
                s.cwd === cwd && (s.startedAt ?? 0) >= meta.startedAt - 2000,
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
