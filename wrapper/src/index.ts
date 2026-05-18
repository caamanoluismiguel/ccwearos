import { config } from "./config.js";
import {
  clearCommand,
  clearPrompt,
  initFirebase,
  sendFcmWake,
  setActivity,
  setClaudeStatus,
  setHeadline,
  setPermissionPrompt,
  setResponse,
  setStatus,
  setTask,
  setTaskKind,
  setToolEvents,
  watchCommands,
  watchPrompts,
  writeMetrics,
} from "./firebase.js";
import { extractTldr } from "./parser.js";
import type { ToolEvent } from "./types/schema.js";
import { startClaude } from "./claude-runner.js";
import { runClaudeForVoice, type VoiceRunner } from "./claude-voice.js";

const MODE = process.env["CCWEAROS_MODE"] ?? "interactive";

async function clearStaleState(): Promise<void> {
  await Promise.all([
    clearCommand(),
    clearPrompt(),
    setPermissionPrompt(null),
    setActivity(null),
    setTask(null),
    setResponse(null),
    setTaskKind(null),
    setToolEvents(null),
    setHeadline(null),
  ]);
  await setStatus("IDLE");
}

// Tells Claude to start informational answers with `**TL;DR:** xxx` so the
// watch can render a glanceable headline. Bilingual heuristic — pick the
// prefix language from the user's voice text so Claude doesn't switch tone.
function buildPromptPrefix(userText: string): string {
  const spanishStems =
    /\b(qu[éeè]|c[óo]mo|por qu[éeè]|explica|d[íi]me|qu[éeè] es|cu[áa]l|cu[áa]ndo|d[óo]nde|resume|res[úu]meme)\b/i;
  const isSpanish = spanishStems.test(userText);
  return isSpanish
    ? "Responde así (solo si NO necesitas usar herramientas para esta tarea): primera línea con `**TL;DR:**` (máximo 18 palabras), luego detalles si quieres."
    : "Reply like this (only if NO tools are needed for this task): first line `**TL;DR:**` with at most 18 words, then details if you want.";
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
  await clearStaleState();

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
      await setStatus("OFFLINE");
    } catch (e) {
      console.error("[ccwearos] Failed to mark OFFLINE on shutdown:", e);
    }
    runner.kill();
    process.exit(0);
  };

  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
}

async function runDaemon(): Promise<void> {
  initFirebase();
  console.log("[ccwearos] Daemon online. DB:", config.firebaseDbUrl);
  await clearStaleState();

  let busy = false;
  // True once we've completed at least one oneshot in this daemon run — then
  // subsequent prompts use `claude --continue` to keep conversation context.
  let hasPriorSession = false;
  // Active runner for the current voice task. Permission responses from the
  // watch (via /command) get routed into runner.send() while it's alive.
  let activeRunner: VoiceRunner | null = null;

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
    console.log(
      "[ccwearos] Permission response from watch:",
      JSON.stringify(cmd.text),
    );
    activeRunner.send(cmd.text);
    await clearCommand();
    await setPermissionPrompt(null);
    await setStatus("RUNNING");
  });

  const stopPromptWatching = watchPrompts(async (p) => {
    if (busy) {
      console.log("[ccwearos] Busy — ignoring overlapping prompt:", p.text);
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

      const shouldContinue = hasPriorSession && !isResetPrompt(p.text);
      if (!shouldContinue && hasPriorSession) {
        console.log(
          "[ccwearos] Reset phrase detected — starting fresh conversation.",
        );
      }

      // Wrap the user prompt so Claude opens info answers with **TL;DR:**.
      // Tool-using runs naturally skip the directive (tools come first).
      const wrappedPrompt = `${buildPromptPrefix(p.text)}\n\n${p.text}`;

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
        { continueSession: shouldContinue },
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

      console.log(
        `[ccwearos] Voice run done. exit=${result.exitCode} bytes=${result.rawBytes} kind=${kind} tools=${observedTools.length} continue-next=${hasPriorSession}`,
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
    stopPromptWatching();
    stopCmdWatching();
    try {
      await setStatus("OFFLINE");
    } catch (e) {
      console.error("[ccwearos] Failed to mark OFFLINE on shutdown:", e);
    }
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
