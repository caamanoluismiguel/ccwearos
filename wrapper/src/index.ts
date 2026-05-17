import { config } from "./config.js";
import {
  clearCommand,
  clearPrompt,
  initFirebase,
  sendFcmWake,
  setActivity,
  setClaudeStatus,
  setPermissionPrompt,
  setResponse,
  setStatus,
  setTask,
  watchCommands,
  watchPrompts,
  writeMetrics,
} from "./firebase.js";
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
  ]);
  await setStatus("IDLE");
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
    try {
      console.log("[ccwearos] Handling prompt:", JSON.stringify(p.text));
      await setStatus("RUNNING");
      await setTask(p.text.slice(0, 60));
      await setActivity("Thinking…");
      await setResponse(null);

      const shouldContinue = hasPriorSession && !isResetPrompt(p.text);
      if (!shouldContinue && hasPriorSession) {
        console.log(
          "[ccwearos] Reset phrase detected — starting fresh conversation.",
        );
      }

      activeRunner = runClaudeForVoice(
        p.text,
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
          onResponse: (r) => void setResponse(r),
          onClaudeStatus: (s) => void setClaudeStatus(s),
        },
        { continueSession: shouldContinue },
      );

      const result = await activeRunner.done;
      activeRunner = null;

      if (result.exitCode === 0) hasPriorSession = true;

      console.log(
        `[ccwearos] Voice run done. exit=${result.exitCode} bytes=${result.rawBytes} continue-next=${hasPriorSession}`,
      );
    } catch (e) {
      console.error("[ccwearos] Voice run failed:", e);
    } finally {
      activeRunner = null;
      await setActivity(null);
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
