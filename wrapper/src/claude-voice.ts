// Interactive claude runner for the daemon's voice flow. Unlike claude-oneshot
// (which uses `claude -p` and gets clean stream-json out but can't surface
// permission prompts), this spawns interactive `claude` in a pseudo-TTY,
// sends the voice prompt as the first stdin input, parses the TUI output for
// everything (response, activity, task, permission), and exposes a send()
// for permission responses ("1\r" / "") routed in from the watch.
//
// Auto-exit heuristic: if no new pty data arrives for IDLE_DETECT_MS after
// the prompt was sent, send "/exit" and kill. Empirically Claude's TUI
// renders the response, then sits at its input prompt — silence = done.

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

export interface VoiceCallbacks {
  onStatus: (s: WrapperStatus) => void;
  onMetrics: (m: Metrics) => void;
  onPermission: (prompt: string) => void;
  onActivity: (a: string | null) => void;
  onTask: (t: string | null) => void;
  onResponse: (r: string) => void;
  onClaudeStatus: (s: ClaudeStatus) => void;
}

export interface VoiceRunner {
  send: (input: string) => void;
  done: Promise<{ exitCode: number | null; rawBytes: number }>;
}

const SPAWN_WARMUP_MS = 2500;
const IDLE_DETECT_MS = 10_000;
const RESPONSE_BUFFER_MAX = 16 * 1024;

export function runClaudeForVoice(
  prompt: string,
  cb: VoiceCallbacks,
  opts: { continueSession?: boolean } = {},
): VoiceRunner {
  const continueArg = opts.continueSession ? " --continue" : "";
  const cmd = `${config.claudeCliCommand}${continueArg}`;

  let pty: IPty;
  try {
    pty = ptySpawn("/bin/sh", ["-c", `exec ${cmd}`], {
      name: "xterm-256color",
      cols: 120,
      rows: 30,
      cwd: process.cwd(),
      env: { ...process.env } as Record<string, string>,
    });
  } catch (err) {
    console.error(
      `[voice] Failed to spawn '${config.claudeCliCommand}':`,
      (err as Error).message,
    );
    return {
      send: () => {},
      done: Promise.resolve({ exitCode: null, rawBytes: 0 }),
    };
  }

  const store = createMetricsStore();
  let lastSessionCumulative = 0;
  let responseBuffer = "";
  let lastResponseEmitted = "";
  let lastPromptEmitted: string | null = null;
  let lastActivity: string | null = null;
  let lastTask: string | null = null;
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

  let lastDataTime = Date.now();
  let totalBytes = 0;
  let promptSent = false;

  let metricsTimer: NodeJS.Timeout | null = null;
  let responseTimer: NodeJS.Timeout | null = null;

  const flushMetrics = (): void => {
    store.persist();
    cb.onMetrics(store.snapshot());
    metricsTimer = null;
  };
  const scheduleMetricsFlush = (): void => {
    if (!metricsTimer) {
      metricsTimer = setTimeout(flushMetrics, config.metricsDebounceMs);
    }
  };

  const scheduleResponseFlush = (): void => {
    if (responseTimer) return;
    responseTimer = setTimeout(() => {
      responseTimer = null;
      const extracted = extractResponseLines(responseBuffer);
      if (extracted && extracted !== lastResponseEmitted) {
        lastResponseEmitted = extracted;
        cb.onResponse(extracted);
      }
    }, 1500);
  };

  // Send prompt as first stdin input after Claude has had time to render.
  setTimeout(() => {
    if (!promptSent) {
      try {
        pty.write(prompt + "\r");
        promptSent = true;
        cb.onActivity("Thinking…");
      } catch {
        /* ignore */
      }
    }
  }, SPAWN_WARMUP_MS);

  // Idle-detection: if Claude's output has been silent for IDLE_DETECT_MS
  // AFTER we sent the prompt, assume the response is done and exit gracefully.
  const idleCheck = setInterval(() => {
    if (!promptSent) return;
    if (Date.now() - lastDataTime > IDLE_DETECT_MS) {
      clearInterval(idleCheck);
      try {
        pty.write("/exit\r");
      } catch {
        /* ignore */
      }
      setTimeout(() => {
        try {
          pty.kill();
        } catch {
          /* ignore */
        }
      }, 1500);
    }
  }, 2000);

  pty.onData((data: string) => {
    lastDataTime = Date.now();
    totalBytes += data.length;

    // Token counts (cumulative preferred, falls back to incrementals).
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

    // Permission prompt — surfaces to /permissionPrompt + AWAITING_PERMISSION.
    const promptText = extractPermissionPrompt(data);
    if (promptText && promptText !== lastPromptEmitted) {
      lastPromptEmitted = promptText;
      cb.onPermission(promptText);
    }

    const activity = extractActivity(data);
    if (activity && activity !== lastActivity) {
      lastActivity = activity;
      cb.onActivity(activity);
    }

    const task = extractCurrentTask(data);
    if (task !== null && task !== lastTask) {
      lastTask = task;
      cb.onTask(task);
    }

    // Claude status line — model, cost, reset times.
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
        cb.onClaudeStatus({ ...accumulatedStatus });
      }
    }

    // Response buffer for the watch.
    responseBuffer += data;
    if (responseBuffer.length > RESPONSE_BUFFER_MAX) {
      responseBuffer = responseBuffer.slice(-RESPONSE_BUFFER_MAX);
    }
    scheduleResponseFlush();
  });

  let resolveDone: (r: { exitCode: number | null; rawBytes: number }) => void;
  const done = new Promise<{ exitCode: number | null; rawBytes: number }>(
    (r) => {
      resolveDone = r;
    },
  );

  pty.onExit(({ exitCode }) => {
    clearInterval(idleCheck);
    if (metricsTimer) clearTimeout(metricsTimer);
    if (responseTimer) clearTimeout(responseTimer);
    // Final flush.
    const finalResponse = extractResponseLines(responseBuffer);
    if (finalResponse && finalResponse !== lastResponseEmitted) {
      cb.onResponse(finalResponse);
    }
    cb.onActivity(null);
    resolveDone({ exitCode, rawBytes: totalBytes });
  });

  return {
    send: (input) => {
      lastDataTime = Date.now();
      lastPromptEmitted = null; // user answered — next prompt is fresh
      try {
        pty.write(input);
      } catch {
        /* pty may be gone */
      }
    },
    done,
  };
}
