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
import { basename } from "node:path";
import {
  extractActivity,
  extractClaudeStatus,
  extractCurrentTask,
  extractPermissionPrompt,
  extractResponseLines,
  extractSessionCumulative,
  extractTokenCounts,
  extractToolEvents,
} from "./parser.js";
import type {
  ClaudeStatus,
  Metrics,
  ToolEvent,
  WrapperStatus,
} from "./types/schema.js";

export interface VoiceCallbacks {
  onStatus: (s: WrapperStatus) => void;
  onMetrics: (m: Metrics) => void;
  onPermission: (prompt: string) => void;
  onActivity: (a: string | null) => void;
  onTask: (t: string | null) => void;
  onResponse: (r: string) => void;
  onClaudeStatus: (s: ClaudeStatus) => void;
  onToolEvents: (events: ToolEvent[]) => void;
}

// Pretty activity string for the Command page when a tool is the most recent
// thing Claude did. Concrete > whimsical: "Editing parser.ts" beats "Crunching…"
function synthesizeActivityFromTool(ev: ToolEvent): string {
  const arg = ev.arg ?? "";
  const short = arg.length > 0 ? basename(arg).slice(0, 36) : null;
  switch (ev.tool.replace(/\s+/g, "")) {
    case "Bash":
      return "Running bash";
    case "Edit":
      return short ? `Editing ${short}` : "Editing";
    case "Write":
      return short ? `Writing ${short}` : "Writing";
    case "Read":
      return short ? `Reading ${short}` : "Reading";
    case "Grep":
      return "Searching files";
    case "Glob":
      return "Finding files";
    case "WebSearch":
    case "WebFetch":
      return "Searching the web";
    case "Task":
      return arg ? `Sub-agent: ${arg.slice(0, 36)}` : "Running sub-agent";
    default:
      return `Using ${ev.tool}`;
  }
}

export interface VoiceRunner {
  send: (input: string) => void;
  kill: () => void;
  done: Promise<{ exitCode: number | null; rawBytes: number }>;
}

// Cold-start Claude needs ~5s before its input field is ready to accept a
// submission keystroke (auth check + welcome banner render). Below this, the
// pty.write() lands in the field but \r is treated as an in-box newline, not
// submit, so Claude sits idle and we eventually kill it.
const SPAWN_WARMUP_MS = 5000;
// Voice queries can have long silent thinking gaps between prompt send and
// first token output. 30s gives Claude room to think AND still reaps within a
// reasonable window once output stops.
const IDLE_DETECT_MS = 30_000;
// Gap between typing the prompt text and sending the submit-Enter. The TUI
// input editor needs this pause to flush the typed chunk before it recognises
// the trailing \r as submit (vs. just another newline in the buffer).
const SUBMIT_DELAY_MS = 300;
const RESPONSE_BUFFER_MAX = 16 * 1024;

export function runClaudeForVoice(
  prompt: string,
  cb: VoiceCallbacks,
  opts: { continueSession?: boolean; userEcho?: string } = {},
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
      kill: () => {},
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

  // Tool events accumulator + dedupe — capped at 12 (kept on the watch as
  // chips, more than that just clutters). 600ms flush is lighter than
  // response (1.5s) because tool events change state quickly.
  const toolEvents: ToolEvent[] = [];
  let lastToolEventsJson = "";
  let toolEventsTimer: NodeJS.Timeout | null = null;

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
      const extracted = extractResponseLines(
        responseBuffer,
        opts.userEcho ? { userEcho: opts.userEcho } : {},
      );
      if (extracted && extracted !== lastResponseEmitted) {
        lastResponseEmitted = extracted;
        cb.onResponse(extracted);
      }
    }, 1500);
  };

  const flushToolEvents = (): void => {
    toolEventsTimer = null;
    const json = JSON.stringify(toolEvents);
    if (json !== lastToolEventsJson) {
      lastToolEventsJson = json;
      cb.onToolEvents([...toolEvents]);
    }
  };
  const scheduleToolEventsFlush = (): void => {
    if (!toolEventsTimer) toolEventsTimer = setTimeout(flushToolEvents, 600);
  };

  // Send prompt as first stdin input after Claude has had time to render.
  // Two-phase write: type the text first, wait for the TUI to flush, then
  // send a bare \r to submit. Bundling text+\r in one write made the TUI
  // treat \r as an in-box newline and the prompt sat unsubmitted.
  setTimeout(() => {
    if (promptSent) return;
    try {
      pty.write(prompt);
      promptSent = true;
      cb.onActivity("Thinking…");
    } catch {
      /* ignore */
    }
    setTimeout(() => {
      try {
        pty.write("\r");
      } catch {
        /* ignore */
      }
    }, SUBMIT_DELAY_MS);
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

    // Tool events first — they yield concrete activity strings ("Editing
    // parser.ts") that we prefer over Claude's whimsical "Crunching…".
    const newEvents = extractToolEvents(data);
    if (newEvents.length > 0) {
      for (const ev of newEvents) {
        const prev = toolEvents[toolEvents.length - 1];
        if (prev && prev.tool === ev.tool && prev.arg === ev.arg) continue;
        toolEvents.push(ev);
      }
      // Cap at 12 — keep the most recent.
      if (toolEvents.length > 12) {
        toolEvents.splice(0, toolEvents.length - 12);
      }
      scheduleToolEventsFlush();
      // Use the latest tool to drive a concrete activity verb on Page 1.
      const latest = toolEvents[toolEvents.length - 1];
      if (latest) {
        const synth = synthesizeActivityFromTool(latest);
        if (synth !== lastActivity) {
          lastActivity = synth;
          cb.onActivity(synth);
        }
      }
    } else {
      const activity = extractActivity(data);
      if (activity && activity !== lastActivity) {
        lastActivity = activity;
        cb.onActivity(activity);
      }
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
    if (toolEventsTimer) {
      clearTimeout(toolEventsTimer);
      flushToolEvents();
    }
    // Final flush.
    const finalResponse = extractResponseLines(
      responseBuffer,
      opts.userEcho ? { userEcho: opts.userEcho } : {},
    );
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
    kill: () => {
      try {
        pty.kill();
      } catch {
        /* already gone */
      }
    },
    done,
  };
}
