import { appendFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { spawn as ptySpawn, type IPty } from "node-pty";
import { config } from "./config.js";
import { createMetricsStore } from "./metrics-store.js";
import {
  extractActivity,
  extractClaudeStatus,
  extractCurrentTask,
  extractPermissionPrompt,
  extractResponseLines,
  extractSessionCumulative,
  extractTokenCounts,
} from "./parser.js";
import type { ClaudeStatus, Metrics, WrapperStatus } from "./types/schema.js";

export interface RunnerEvents {
  onStatus: (status: WrapperStatus) => void;
  onMetrics: (metrics: Metrics) => void;
  onPermission: (prompt: string) => void;
  onActivity: (text: string | null) => void;
  onTask: (text: string | null) => void;
  onResponse: (text: string) => void;
  onClaudeStatus: (s: ClaudeStatus) => void;
  onExit: (code: number | null) => void;
}

export interface ClaudeRunner {
  send(input: string): void;
  kill(): void;
}

export function startClaude(events: RunnerEvents): ClaudeRunner {
  let pty: IPty;
  try {
    // node-pty's posix_spawnp can fail on some macOS Mach-O binaries (Bun-
    // compiled ones like Claude Code, for example). Workaround: spawn /bin/sh
    // which always works, then `exec` the real target so it replaces sh and
    // inherits the pty directly. No extra subshell stays alive.
    pty = ptySpawn("/bin/sh", ["-c", `exec ${config.claudeCliCommand}`], {
      name: "xterm-256color",
      cols: process.stdout.columns ?? 120,
      rows: process.stdout.rows ?? 30,
      cwd: process.cwd(),
      env: { ...process.env } as Record<string, string>,
    });
  } catch (err) {
    console.error(
      `[ccwearos] Failed to spawn '${config.claudeCliCommand}':`,
      (err as Error).message,
    );
    events.onExit(null);
    return { send() {}, kill() {} };
  }

  const store = createMetricsStore();
  let metricsTimer: NodeJS.Timeout | null = null;
  let lastPromptEmitted: string | null = null;
  // If we've emitted a permission prompt but Claude's output has gone quiet
  // (no prompt patterns) for PROMPT_IDLE_MS, assume the user answered via the
  // terminal (not the watch) and downgrade status back to RUNNING so the watch
  // doesn't stay stuck on the permission screen.
  // Track the highest session-cumulative we've seen so we only add the delta.
  // Resets when wrapper restarts; first non-zero value contributes the whole
  // amount, subsequent ones contribute the diff.
  let lastSessionCumulative = 0;
  let lastActivityEmitted: string | null = null;
  let lastTaskEmitted: string | null = null;
  const accumulatedStatus: ClaudeStatus = {
    model: null,
    contextSize: null,
    contextPct: null,
    sessionPct: null,
    sessionResets: null,
    weeklyPct: null,
    weeklyResets: null,
    monthlyCost: null,
    monthlyResets: null,
  };
  let lastStatusJson = "";
  // Rolling buffer of recent pty output for response extraction. Capped so we
  // don't blow up memory on long sessions.
  let responseBuffer = "";
  const RESPONSE_BUFFER_MAX = 8 * 1024;
  let responseTimer: NodeJS.Timeout | null = null;
  let lastResponseEmitted = "";

  // Optional: capture raw pty output to a file for parser tuning later.
  // Enable with: CCWEAROS_CAPTURE_FILE=fixtures/captures/session.log npm run dev
  const captureFile = process.env["CCWEAROS_CAPTURE_FILE"];
  if (captureFile) {
    try {
      mkdirSync(dirname(captureFile), { recursive: true });
      console.error(`[ccwearos] Capturing pty output to ${captureFile}`);
    } catch {
      /* best-effort */
    }
  }

  const flushMetrics = (): void => {
    store.persist();
    events.onMetrics(store.snapshot());
    metricsTimer = null;
  };

  const scheduleMetricsFlush = (): void => {
    if (metricsTimer) return;
    metricsTimer = setTimeout(flushMetrics, config.metricsDebounceMs);
  };

  pty.onData((data: string) => {
    // Mirror to the user's terminal so Claude renders normally (colors, TUI).
    process.stdout.write(data);

    if (captureFile) {
      try {
        appendFileSync(captureFile, data);
      } catch {
        /* keep going even if capture fails */
      }
    }

    // Prefer cumulative tracking when Claude exposes "(49k/1.0m)"-style totals —
    // it avoids double-counting incremental "↓ N tokens" notifications that
    // also stream through.
    const cumulative = extractSessionCumulative(data);
    if (cumulative !== null) {
      const delta = Math.max(0, cumulative - lastSessionCumulative);
      if (delta > 0) {
        store.add(delta);
        scheduleMetricsFlush();
      }
      lastSessionCumulative = cumulative;
    } else {
      const tokens = extractTokenCounts(data);
      if (tokens.length > 0) {
        for (const t of tokens) store.add(t);
        scheduleMetricsFlush();
      }
    }

    const prompt = extractPermissionPrompt(data);
    if (prompt && prompt !== lastPromptEmitted) {
      lastPromptEmitted = prompt;
      events.onPermission(prompt);
    }

    const activity = extractActivity(data);
    if (activity && activity !== lastActivityEmitted) {
      lastActivityEmitted = activity;
      events.onActivity(activity);
    }

    const task = extractCurrentTask(data);
    if (task !== null && task !== lastTaskEmitted) {
      lastTaskEmitted = task;
      events.onTask(task);
    }

    // Merge any newly-discovered status-line fields, then emit if anything
    // actually changed (compared by JSON shape).
    const partial = extractClaudeStatus(data);
    let changed = false;
    for (const k of Object.keys(partial) as (keyof ClaudeStatus)[]) {
      const v = partial[k];
      if (v !== undefined && v !== null && accumulatedStatus[k] !== v) {
        (accumulatedStatus as unknown as Record<string, unknown>)[k] = v;
        changed = true;
      }
    }
    if (changed) {
      const json = JSON.stringify(accumulatedStatus);
      if (json !== lastStatusJson) {
        lastStatusJson = json;
        events.onClaudeStatus({ ...accumulatedStatus });
      }
    }

    // Append to the rolling response buffer and schedule a debounced flush.
    responseBuffer += data;
    if (responseBuffer.length > RESPONSE_BUFFER_MAX) {
      responseBuffer = responseBuffer.slice(-RESPONSE_BUFFER_MAX);
    }
    if (!responseTimer) {
      responseTimer = setTimeout(() => {
        responseTimer = null;
        const extracted = extractResponseLines(responseBuffer);
        if (extracted && extracted !== lastResponseEmitted) {
          lastResponseEmitted = extracted;
          events.onResponse(extracted);
        }
      }, config.metricsDebounceMs);
    }
  });

  pty.onExit(({ exitCode }) => {
    if (metricsTimer) clearTimeout(metricsTimer);
    if (responseTimer) clearTimeout(responseTimer);
    flushMetrics();
    cleanupTerminal();
    events.onExit(exitCode);
  });

  // Forward terminal resize events to the child.
  const resizeListener = (): void => {
    try {
      pty.resize(process.stdout.columns ?? 120, process.stdout.rows ?? 30);
    } catch {
      /* pty may be gone */
    }
  };
  process.stdout.on("resize", resizeListener);

  // Forward parent's keystrokes to the child pty so Claude is fully interactive.
  if (process.stdin.isTTY) {
    process.stdin.setRawMode(true);
  }
  process.stdin.resume();
  const stdinListener = (data: Buffer | string): void => {
    pty.write(typeof data === "string" ? data : data.toString("utf8"));
    // If a permission prompt is currently active and the user just typed
    // *anything* in the terminal, assume they're answering Claude there and
    // clear the watch permission state. The watch will route back to dashboard.
    if (lastPromptEmitted !== null) {
      lastPromptEmitted = null;
      events.onStatus("RUNNING");
    }
  };
  process.stdin.on("data", stdinListener);

  function cleanupTerminal(): void {
    try {
      process.stdin.removeListener("data", stdinListener);
      process.stdout.removeListener("resize", resizeListener);
      if (process.stdin.isTTY) {
        process.stdin.setRawMode(false);
      }
      process.stdin.pause();
    } catch {
      /* best-effort */
    }
  }

  // Defensive: ensure raw mode is reset even on unclean exit.
  process.on("exit", cleanupTerminal);

  events.onStatus("RUNNING");

  return {
    send(input) {
      pty.write(input);
      lastPromptEmitted = null;
      events.onStatus("RUNNING");
    },
    kill() {
      pty.kill();
    },
  };
}
