import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./config.js";
import {
  appendAuditEntry,
  clearClaimRequest,
  clearCommand,
  clearCrashCleanup,
  clearPrompt,
  clearStaleState,
  db,
  initFirebase,
  readSharedSession,
  registerCrashCleanup,
  sendFcmWake,
  setActivity,
  setClaimResult,
  setClaudeStatus,
  setFollowups,
  setHeadline,
  setPermissionPrompt,
  setResponse,
  setStatus,
  setTask,
  setTaskKind,
  setToolEvents,
  watchClaimRequest,
  watchCommands,
  watchPrompts,
  writeMetrics,
} from "./firebase.js";
import { extractFollowups, extractTldr, PROMPT_END_MARKER } from "./parser.js";
import { startSessionScanner } from "./sessions-scanner.js";
import { watchSharedSession } from "./firebase.js";
import type { SharedSessionMeta } from "./types/schema.js";
import type { ToolEvent } from "./types/schema.js";
import { startClaude } from "./claude-runner.js";
import { runClaudeForVoice, type VoiceRunner } from "./claude-voice.js";
import { handleClaimRequest } from "./claim-handler.js";
import { isPidAlive } from "./pid-utils.js";

const MODE = process.env["CCWEAROS_MODE"] ?? "interactive";

// Tells Claude to start informational answers with `**TL;DR:** xxx` so the
// watch can render a glanceable headline. Bilingual heuristic — pick the
// prefix language from the user's voice text so Claude doesn't switch tone.
function buildPromptPrefix(userText: string): string {
  const spanishStems =
    /\b(qu[éeè]|c[óo]mo|por qu[éeè]|explica|d[íi]me|qu[éeè] es|cu[áa]l|cu[áa]ndo|d[óo]nde|resume|res[úu]meme)\b/i;
  const isSpanish = spanishStems.test(userText);
  // Context note that goes first: tell Claude he IS running on the user's
  // macOS session with shell access, so "abre X" / "open X" / "ejecuta X"
  // must trigger the Bash tool, not a "I can't do that" refusal.
  const contextEs =
    "Contexto: estás corriendo en el Mac del usuario vía pty. Tienes Bash. Si te dice 'abre/abrir <app>', ejecuta `open -a \"<app>\"`. Si te dice 'ejecuta/corre <comando>', córrelo. Si te dice 'borra/crea/mueve <archivo>', hazlo. NUNCA respondas 'no puedo abrir apps' o 'no tengo control de escritorio' — sí tienes, vía shell.";
  const contextEn =
    "Context: you are running on the user's macOS via pty. You have Bash. If they say 'open <app>', run `open -a \"<app>\"`. If they say 'run/exec <cmd>', do it. If they say 'delete/create/move <file>', do it. NEVER reply 'I can't open apps' or 'I have no desktop access' — you do, via the shell.";
  return isSpanish
    ? `${contextEs} Responde así (solo si NO necesitas usar herramientas para esta tarea): primera línea con \`**TL;DR:**\` (máximo 18 palabras), luego detalles si quieres. Termina SIEMPRE con un bloque corto en una nueva línea: \`Sugerencias:\` seguido de 2-3 viñetas con \`-\`, cada una de máximo 6 palabras, sugiriendo qué preguntar o hacer a continuación.`
    : `${contextEn} Reply like this (only if NO tools are needed for this task): first line \`**TL;DR:**\` with at most 18 words, then details if you want. ALWAYS end with a short block on a new line: \`Followups:\` followed by 2-3 bullets with \`-\`, each at most 6 words, suggesting what to ask or do next.`;
}

// Read-only tools that produce info-like responses. If only these ran AND the
// response is long, classify the run as info, not action.
const READ_ONLY_TOOLS = /^(Read|Grep|Glob|WebFetch|WebSearch)$/i;

function classifyTaskKind(
  toolEventsObserved: ToolEvent[],
  finalResponseLength: number,
): "action" | "info" {
  if (toolEventsObserved.length === 0) return "info";
  const allReadOnly = toolEventsObserved.every((t) =>
    READ_ONLY_TOOLS.test(t.tool.replace(/\s+/g, "")),
  );
  if (allReadOnly && finalResponseLength >= 200) return "info";
  return "action";
}

async function runInteractive(): Promise<void> {
  initFirebase();
  console.log(
    "[ccwearos] Wrapper online (interactive). DB:",
    config.firebaseDbUrl,
  );
  await clearStaleState("IDLE");
  // onDisconnect handlers on the Firebase server clear UI surfaces if our
  // TCP drops (SIGKILL, OOM, network blip, machine sleep) — the only
  // cleanup mechanism that survives those exit modes.
  await registerCrashCleanup({ uiSurfaces: true });
  // Defensive re-assertion at 8s — see runDaemon for why.
  setTimeout(() => {
    void setStatus("IDLE").catch(() => {});
  }, 8_000).unref();

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
      console.log(`[ccwearos] Claude CLI exited with code ${code}`);
      await setStatus("OFFLINE");
      process.exit(code ?? 0);
    },
  });

  const stopWatching = watchCommands(async (cmd) => {
    const ageSec = (Date.now() - cmd.issuedAt) / 1000;
    if (ageSec > config.commandMaxAgeSeconds) {
      console.warn(
        `[ccwearos] Ignoring stale command (age ${ageSec.toFixed(1)}s):`,
        cmd.text,
      );
      await clearCommand();
      return;
    }
    console.log(
      "[ccwearos] Received command from watch:",
      JSON.stringify(cmd.text),
    );
    runner.send(cmd.text);
    await clearCommand();
    await setPermissionPrompt(null);
  });

  const shutdown = async (signal: string): Promise<void> => {
    console.log(`[ccwearos] ${signal} received — shutting down.`);
    stopWatching();
    try {
      // Full state clear, not just /status, so the watch doesn't see phantom
      // task / permissionPrompt / activity after we exit. Audit C-3.
      await clearStaleState("OFFLINE");
    } catch (e) {
      console.error("[ccwearos] Failed to clear state on shutdown:", e);
    }
    await clearCrashCleanup();
    runner.kill();
    // Same grace period as the daemon shutdown — see runDaemon comment.
    await new Promise((r) => setTimeout(r, 250));
    process.exit(0);
  };

  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
}

async function runDaemon(): Promise<void> {
  initFirebase();
  console.log("[ccwearos] Daemon online. DB:", config.firebaseDbUrl);
  await clearStaleState("IDLE");
  // Server-side cleanup if the daemon dies abruptly (LaunchAgent SIGKILL,
  // OOM, Mac power loss). UI surfaces are reset; daemon does NOT claim
  // /sharedSession so we leave that path alone.
  await registerCrashCleanup({ uiSurfaces: true });
  // Defensive re-assertion. If a previous daemon was kill -9'd, its
  // onDisconnect handler fires server-side ~5-10s AFTER we've already
  // written IDLE — overwriting us with OFFLINE. Schedule a second IDLE
  // write at 8s to win the race. Audit observation 2026-05-19.
  setTimeout(() => {
    void setStatus("IDLE").catch(() => {
      // best-effort; if this fails, the watch's long-press force-reset
      // is the user-facing recovery.
    });
  }, 8_000).unref();

  let busy = false;
  // True once we've completed at least one oneshot in this daemon run — then
  // subsequent prompts use `claude --continue` to keep conversation context.
  let hasPriorSession = false;
  // Active runner for the current voice task. Permission responses from the
  // watch (via /command) get routed into runner.send() while it's alive.
  let activeRunner: VoiceRunner | null = null;
  // Currently-shared session (from `cc` / scripts/share.ts), if any. While
  // non-null we refuse voice prompts to avoid two pty's writing to the same
  // RTDB paths and clobbering each other. The watch's Page 0 button is also
  // disabled visually based on the same /sharedSession value.
  let sharedSession: SharedSessionMeta | null = null;
  const stopWatchingShared = watchSharedSession((meta) => {
    sharedSession = meta;
    if (meta) {
      console.log(
        `[ccwearos] /sharedSession active (pid=${meta.pid}, cwd=${meta.cwd}) — voice prompts paused.`,
      );
    } else {
      console.log("[ccwearos] /sharedSession cleared — voice prompts resumed.");
    }
  });

  // Periodically scan ~/.claude/sessions + projects and publish /recentSessions
  // so the watch's Page 5 shows what's happening across all projects on the Mac.
  const stopSessionScanner = startSessionScanner({
    getSharedSessionId: () => sharedSession?.sessionId || null,
  });

  // Sprint 4o — runtime heartbeat. The Sprint 4m fix only re-asserts IDLE
  // at startup (8s defensive setTimeout); it doesn't help if the daemon's
  // TCP blips DURING runtime — observed 3 times in 2 days (2026-05-19,
  // 2026-05-20 x2). When the Mac's WiFi titubea, the Firebase server fires
  // our onDisconnect handler (writes OFFLINE), then the SDK reconnects,
  // but the daemon doesn't know to re-assert IDLE.
  //
  // Fix: every 30s, atomically replace "OFFLINE" → "IDLE" via transaction
  // (only when we know we're not busy and no shared session owns status).
  // Transaction is mandatory — a plain setStatus would race with concurrent
  // voice-handler writes ("RUNNING") and could overwrite RUNNING with
  // a stale IDLE. The transaction's `current !== "OFFLINE"` check aborts
  // in any other case, leaving correct states untouched.
  const HEARTBEAT_MS = 30_000;
  const heartbeat = setInterval(() => {
    if (busy) return;
    if (sharedSession !== null) return;
    void db()
      .ref("/status")
      .transaction((current: string | null) => {
        // Returning `undefined` aborts the transaction — the path is
        // untouched. So we only overwrite OFFLINE, never RUNNING or
        // AWAITING_PERMISSION (those are managed by the voice / permission
        // flows). Null (path absent) is also treated as needing IDLE.
        if (current !== "OFFLINE" && current !== null) return undefined;
        return "IDLE";
      })
      .catch(() => {
        // best-effort; next tick retries. Failure could be network blip
        // (the very thing we're trying to recover from) — wait and retry.
      });
  }, HEARTBEAT_MS);
  heartbeat.unref();

  // Voice phrases that signal "start a fresh conversation, ignore previous
  // turns". Spanish + English, case-insensitive substring match.
  const RESET_PHRASES = [
    "/new",
    "/reset",
    "nueva conversación",
    "nueva conversacion",
    "nuevo chat",
    "empezar de nuevo",
    "olvida todo",
    "olvida lo anterior",
    "new chat",
    "new conversation",
    "start over",
    "forget everything",
    "forget all",
  ];
  const isResetPrompt = (text: string): boolean => {
    const t = text.toLowerCase();
    return RESET_PHRASES.some((p) => t.includes(p));
  };

  // Permission responses from the watch: only forwarded if there's an active
  // runner. /command is otherwise unused in daemon mode.
  const stopCmdWatching = watchCommands(async (cmd) => {
    // E-2 (hook-mode share): the user's standalone Claude session has a
    // PreToolUse hook polling /command for the watch's Allow/Deny. The
    // daemon must NOT consume that write — yield ownership while a hook
    // share is active.
    if (sharedSession?.kind === "hook") {
      console.log(
        `[ccwearos] /command ignored (hook share active for ${sharedSession.cwd}): ${cmd.text}`,
      );
      return;
    }
    // D (wrapper-pty share): the `cc` alias owns its own pty and has its
    // own /command watcher. If the daemon listener fires first and clears
    // /command, cc never reads the user's tap. Yield ownership here too —
    // cc's watchCommands callback consumes + clears.
    if (sharedSession?.kind === "wrapper-pty") {
      console.log(
        `[ccwearos] /command ignored (cc share active for ${sharedSession.cwd}): ${cmd.text}`,
      );
      return;
    }
    if (!activeRunner) {
      await clearCommand();
      return;
    }
    const ageSec = (Date.now() - cmd.issuedAt) / 1000;
    if (ageSec > config.commandMaxAgeSeconds) {
      console.warn(
        `[ccwearos] Stale permission cmd (age ${ageSec.toFixed(1)}s):`,
        cmd.text,
      );
      await clearCommand();
      return;
    }
    // Cancel/Stop: watch sends \x03 (ETX, SIGINT) when the user taps the
    // detener button on Page 0. Kill the runner — Claude exits cleanly.
    if (cmd.text === "\x03") {
      console.log("[ccwearos] SIGINT from watch — stopping current run");
      void appendAuditEntry({
        ts: Date.now(),
        kind: "voice",
        tool: "(cancel)",
        args: "",
        decision: "deny",
        source: "watch",
      });
      activeRunner.kill();
      await clearCommand();
      await setPermissionPrompt(null);
      await setActivity(null);
      await setStatus("IDLE");
      return;
    }
    console.log(
      "[ccwearos] Permission response from watch:",
      JSON.stringify(cmd.text),
    );
    void appendAuditEntry({
      ts: Date.now(),
      kind: "voice",
      tool: "(voice-permission)",
      args: cmd.text.slice(0, 60),
      decision: cmd.text.trim().startsWith("1") ? "allow" : "deny",
      source: "watch",
    });
    activeRunner.send(cmd.text);
    await clearCommand();
    await setPermissionPrompt(null);
    await setStatus("RUNNING");
  });

  // Sprint 4n — watch-initiated tap-to-claim. When the user taps a session
  // row on Page 5 and confirms the dialog, the watch writes /claimRequest;
  // the handler validates + spawns `cc --resume <id>` in a new Terminal via
  // osascript (same machinery as /ccwearos-takeover). Single-flight gated
  // by claimBusy so two rapid taps don't open two Terminals.
  const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
  const WRAPPER_ROOT = resolve(MODULE_DIR, "..");
  const SHARE_SCRIPT = join(WRAPPER_ROOT, "scripts/share.ts");
  const TSX_BIN = join(WRAPPER_ROOT, "node_modules/.bin/tsx");
  // Snapshot once at daemon startup — $TERM_PROGRAM is from the LaunchAgent
  // env, which doesn't change at runtime. Watch users will get Terminal.app
  // unless their daemon was launched from iTerm with that env preserved.
  const TERM_PROGRAM = process.env["TERM_PROGRAM"];
  let claimBusy = false;
  const stopClaimWatching = watchClaimRequest(async (claim) => {
    await handleClaimRequest(claim, {
      readSharedSession,
      setClaimResult,
      clearClaimRequest,
      appendAuditEntry,
      isPidAlive,
      spawn: (cmd, args, options) => {
        const r = spawnSync(cmd, args as readonly string[], options);
        return {
          status: r.status,
          signal: r.signal,
          stderr: String(r.stderr ?? ""),
        };
      },
      commandMaxAgeSeconds: config.commandMaxAgeSeconds,
      tsxBin: TSX_BIN,
      shareScript: SHARE_SCRIPT,
      termProgram: TERM_PROGRAM,
      getClaimBusy: () => claimBusy,
      setClaimBusy: (v) => {
        claimBusy = v;
      },
    });
  });

  const stopPromptWatching = watchPrompts(async (p) => {
    if (busy) {
      console.log("[ccwearos] Busy — ignoring overlapping prompt:", p.text);
      return;
    }
    // Refuse voice prompts while a shared session (cc / share.ts) is alive
    // — both would spawn Claude in pty and clobber the same RTDB paths. The
    // watch UI also disables Page 0's button based on the same signal, but
    // this is a defensive double-check in case stale UI state slips through.
    if (sharedSession) {
      console.log(
        `[ccwearos] /sharedSession active in ${sharedSession.cwd} — voice prompt dropped: ${p.text}`,
      );
      await clearPrompt();
      return;
    }
    const ageSec = (Date.now() - p.issuedAt) / 1000;
    if (ageSec > config.commandMaxAgeSeconds) {
      console.warn(
        `[ccwearos] Stale prompt (age ${ageSec.toFixed(1)}s), clearing:`,
        p.text,
      );
      await clearPrompt();
      return;
    }
    busy = true;
    // Per-run accumulators — closed over by callbacks below, reset on each
    // new prompt so taskKind classification is correct on every voice tap.
    let observedTools: ToolEvent[] = [];
    let lastResponseSeen = "";
    try {
      console.log("[ccwearos] Handling prompt:", JSON.stringify(p.text));
      await setStatus("RUNNING");
      await setTask(p.text.slice(0, 60));
      await setActivity("Thinking…");
      await setResponse(null);
      await setToolEvents(null);
      await setTaskKind(null);
      await setHeadline(null);
      await setFollowups(null);

      const shouldContinue = hasPriorSession && !isResetPrompt(p.text);
      if (!shouldContinue && hasPriorSession) {
        console.log(
          "[ccwearos] Reset phrase detected — starting fresh conversation.",
        );
      }

      // Wrap the user prompt so Claude opens info answers with **TL;DR:**.
      // Tool-using runs naturally skip the directive (tools come first).
      // PROMPT_END_MARKER lets the parser slice the response away from the
      // TUI welcome banner + prompt-prefix echo + user-text echo cleanly.
      // CRITICAL: Claude Code's TUI treats embedded `\n` as in-box line
      // breaks, NOT submit — so the wrap is flattened to a single line
      // (joined with " · ") and the runner appends a trailing `\r` to submit.
      const wrappedPrompt = `${buildPromptPrefix(p.text)} · ${p.text} · ${PROMPT_END_MARKER}`;

      activeRunner = runClaudeForVoice(
        wrappedPrompt,
        {
          onStatus: (s) => void setStatus(s),
          onMetrics: (m) => void writeMetrics(m),
          onPermission: (prompt) => {
            void setPermissionPrompt(prompt);
            void setStatus("AWAITING_PERMISSION");
            void sendFcmWake("permission");
          },
          onActivity: (a) => void setActivity(a),
          onTask: (t) => void setTask(t),
          onResponse: (r) => {
            lastResponseSeen = r;
            void setResponse(r);
          },
          onClaudeStatus: (s) => void setClaudeStatus(s),
          onToolEvents: (e) => {
            observedTools = e;
            void setToolEvents(e);
          },
        },
        { continueSession: shouldContinue, userEcho: p.text },
      );

      const result = await activeRunner.done;
      activeRunner = null;

      if (result.exitCode === 0) hasPriorSession = true;

      // Classify the run and surface either a TL;DR headline (info) or leave
      // the tool chips alone (action). Race-safe: only set after done.
      const kind = classifyTaskKind(observedTools, lastResponseSeen.length);
      await setTaskKind(kind);
      if (kind === "info") {
        const tldr = extractTldr(lastResponseSeen);
        if (tldr) await setHeadline(tldr);
      }

      // Followups: contextual suggestions Claude appended to the response.
      // The watch's Page 4 renders these as tappable chips. Falls back to
      // null when Claude didn't include the block (tool-heavy runs usually
      // skip it, by design of the prompt prefix).
      const followups = extractFollowups(lastResponseSeen);
      await setFollowups(followups.length > 0 ? followups : null);

      console.log(
        `[ccwearos] Voice run done. exit=${result.exitCode} bytes=${result.rawBytes} kind=${kind} tools=${observedTools.length} followups=${followups.length} continue-next=${hasPriorSession}`,
      );
    } catch (e) {
      console.error("[ccwearos] Voice run failed:", e);
    } finally {
      activeRunner = null;
      await setActivity(null);
      await setTask(null);
      await setPermissionPrompt(null);
      await setStatus("IDLE");
      await clearPrompt();
      busy = false;
    }
  });

  const shutdown = async (signal: string): Promise<void> => {
    console.log(`[ccwearos] ${signal} received — daemon shutting down.`);
    // Stop the heartbeat FIRST — otherwise it could race with our
    // clearStaleState("OFFLINE") write below and re-assert IDLE during
    // shutdown, leaving the watch thinking the daemon is alive after
    // we've actually exited.
    clearInterval(heartbeat);
    stopPromptWatching();
    stopCmdWatching();
    stopWatchingShared();
    stopSessionScanner();
    stopClaimWatching();
    // Kill any in-flight voice runner so the pty child doesn't orphan to
    // init (and so its own onExit gets a chance to clear UI surfaces).
    try {
      activeRunner?.kill();
    } catch {
      // already gone
    }
    try {
      // Full state clear (status / task / permissionPrompt / activity /
      // claudeStatus / etc.) — not just /status — so the watch doesn't see
      // stale data from the last run after the daemon exits. Audit C-7.
      await clearStaleState("OFFLINE");
    } catch (e) {
      console.error("[ccwearos] Failed to clear state on shutdown:", e);
    }
    await clearCrashCleanup();
    // Grace period: clearCrashCleanup sends an "onDisconnect cancel" message
    // to the Firebase server. The cancel is async — if we process.exit
    // immediately, our TCP closes before the cancel is ACK'd and the server
    // fires the (uncanceled) onDisconnect anyway. 250ms is enough for the
    // round-trip on a local network without making shutdown feel sluggish.
    await new Promise((r) => setTimeout(r, 250));
    process.exit(0);
  };

  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));

  // Keep the daemon alive forever.
  await new Promise<void>(() => {
    /* never resolve */
  });
}

async function main(): Promise<void> {
  if (MODE === "daemon") {
    await runDaemon();
  } else {
    await runInteractive();
  }
}

main().catch((err) => {
  console.error("[ccwearos] Fatal:", err);
  process.exit(1);
});
