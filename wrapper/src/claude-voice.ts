// Voice runner for the daemon: `claude -p <prompt> --output-format
// stream-json --verbose` as a plain child process (no pty, nothing is ever
// typed). The prompt is argv, so no dialog can swallow a keystroke; the
// answer comes verbatim from the stream's `result` event; tool calls arrive
// as structured `tool_use` blocks. Permission prompts go through the
// PreToolUse hook (see src/voice-run.ts for the env contract).
//
// The interactive `npm start` / `cc` paths keep their pty runner
// (src/claude-runner.ts); this file is only the daemon's voice path.

import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import { constants as osConstants } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./config.js";
import { createMetricsStore } from "./metrics-store.js";
import type { BlockingDialog } from "./parser.js";
import { ProgressPublisher, ProgressTracker } from "./progress.js";
import {
  activityForTool,
  detectRunBlocker,
  parseStreamEvent,
  StreamJsonLines,
  type StreamResult,
} from "./stream-json.js";
import type { ClaudeStatus, Metrics, RunProgress, ToolEvent } from "./types/schema.js";
import {
  VOICE_HOOK_WAIT_ENV,
  VOICE_RUN_ENV,
  VOICE_RUN_ID_ENV,
  voiceHookSettings,
  voiceHookWaitMs,
} from "./voice-run.js";

export interface VoiceCallbacks {
  onMetrics: (m: Metrics) => void;
  onActivity: (a: string | null) => void;
  // Live preview: the newest top-level assistant text. The caller publishes
  // the final answer from VoiceRunResult.result.
  onResponse: (r: string) => void;
  onClaudeStatus: (s: ClaudeStatus) => void;
  onToolEvents: (events: ToolEvent[]) => void;
  // Live /progress snapshots (src/progress.ts): right after spawn, then on
  // every step change, ≤1/s otherwise, and every 10s while nothing happens.
  // Never called after `done` resolves.
  onProgress?: (p: RunProgress) => void;
}

export interface VoiceRunResult {
  // Process exit code; a signal death maps to 128 + signal number (SIGINT →
  // 130). null only when claude could not be spawned at all.
  exitCode: number | null;
  rawBytes: number;
  // The stream's final `result` event, or null (crash, kill before the end).
  result: StreamResult | null;
  // Something only the Mac can fix (login, trust) — see detectRunBlocker.
  blocked: BlockingDialog | null;
  timedOut: boolean; // hit maxRunMs and was stopped
  stopped: boolean; // stop() was called (the watch's Detener)
  toolEvents: ToolEvent[];
}

export interface VoiceRunner {
  // User stop: SIGINT, then SIGTERM after STOP_GRACE_MS, then SIGKILL.
  stop: () => void;
  kill: () => void;
  done: Promise<VoiceRunResult>;
}

// Hard cap for one voice run (tool-heavy tasks included).
export const VOICE_MAX_RUN_MS = 15 * 60_000;
export const STOP_GRACE_MS = 3_000;
const TOOL_EVENTS_MAX = 12;
const RESPONSE_DEBOUNCE_MS = 800;

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const WRAPPER_ROOT = resolve(MODULE_DIR, "..");
export const VOICE_HOOK_SCRIPT = join(WRAPPER_ROOT, "scripts/hooks/pre-tool-use.ts");
export const VOICE_TSX_BIN = join(WRAPPER_ROOT, "node_modules/.bin/tsx");

// "claude-opus-4-7[1m]" → "Opus 4.7". Unknown ids pass through.
export function prettyModel(raw: string): string {
  const m = raw.match(/^claude-(opus|sonnet|haiku)-(\d+)-(\d+)/i);
  if (!m) return raw;
  const tier = (m[1] ?? "").charAt(0).toUpperCase() + (m[1] ?? "").slice(1).toLowerCase();
  return `${tier} ${m[2]}.${m[3]}`;
}

function formatContextWindow(n: number): string {
  if (n >= 1_000_000) return `${Math.round(n / 1_000_000)}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}

function shortTime(epochSec: number): string {
  return new Date(epochSec * 1000)
    .toLocaleString("en-US", {
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
    })
    .toLowerCase();
}

function exitCodeOf(code: number | null, signal: NodeJS.Signals | null): number | null {
  if (code !== null) return code;
  if (signal) {
    const n = (osConstants.signals as Record<string, number>)[signal];
    return typeof n === "number" ? 128 + n : 1;
  }
  return null;
}

// argv for claude. The prompt goes last; the daemon's prompt prefix keeps it
// from starting with "-" or a subcommand name.
export function voiceArgs(prompt: string, opts: { continueSession: boolean; settings: string }): string[] {
  const args = ["-p", "--output-format", "stream-json", "--verbose", "--settings", opts.settings];
  if (opts.continueSession) args.push("--continue");
  args.push(prompt);
  return args;
}

export function runClaudeForVoice(
  prompt: string,
  cb: VoiceCallbacks,
  opts: {
    continueSession?: boolean;
    cwd?: string;
    maxRunMs?: number;
    runId?: string;
  } = {},
): VoiceRunner {
  // Never the wrapper repo (see resolveVoiceCwd in config.ts). `--continue`
  // continuity is per-cwd, so this folder also scopes the voice thread.
  const cwd = opts.cwd ?? config.voiceCwd;
  const maxRunMs = opts.maxRunMs ?? VOICE_MAX_RUN_MS;
  const runId = opts.runId ?? randomUUID();
  const waitMs = voiceHookWaitMs(process.env);
  const args = voiceArgs(prompt, {
    continueSession: opts.continueSession === true,
    settings: voiceHookSettings({ tsxBin: VOICE_TSX_BIN, hookScript: VOICE_HOOK_SCRIPT, waitMs }),
  });

  let resolveDone: (r: VoiceRunResult) => void = () => {};
  const done = new Promise<VoiceRunResult>((r) => {
    resolveDone = r;
  });

  const toolEvents: ToolEvent[] = [];
  let result: StreamResult | null = null;
  let stderr = "";
  let rawBytes = 0;
  let timedOut = false;
  let stopped = false;
  let exited = false;
  let finished = false;
  const timers: NodeJS.Timeout[] = [];

  const store = createMetricsStore();
  const status: ClaudeStatus = {
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
  let lastStatusJson = JSON.stringify(status);
  const emitStatus = (): void => {
    const json = JSON.stringify(status);
    if (json === lastStatusJson) return;
    lastStatusJson = json;
    cb.onClaudeStatus({ ...status });
  };

  let pendingText: string | null = null;
  let responseTimer: NodeJS.Timeout | null = null;
  const flushResponse = (): void => {
    responseTimer = null;
    if (pendingText !== null) cb.onResponse(pendingText);
    pendingText = null;
  };

  const progress = cb.onProgress
    ? new ProgressPublisher(new ProgressTracker({ runStartedAt: Date.now() }), cb.onProgress)
    : null;

  const finish = (exitCode: number | null): void => {
    if (finished) return;
    finished = true;
    progress?.stop();
    for (const t of timers) clearTimeout(t);
    if (responseTimer) clearTimeout(responseTimer);
    cb.onActivity(null);
    resolveDone({
      exitCode,
      rawBytes,
      result,
      blocked: detectRunBlocker({ result, stderr }),
      timedOut,
      stopped,
      toolEvents: [...toolEvents],
    });
  };

  let child: ChildProcess;
  try {
    // /bin/sh -c 'exec <cmd> "$@"' keeps CLAUDE_CLI_COMMAND usable as a
    // command line while every argument stays a separate argv entry.
    child = spawn("/bin/sh", ["-c", `exec ${config.claudeCliCommand} "$@"`, "sh", ...args], {
      cwd,
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        [VOICE_RUN_ENV]: "1",
        [VOICE_RUN_ID_ENV]: runId,
        [VOICE_HOOK_WAIT_ENV]: String(waitMs),
      },
    });
  } catch (err) {
    console.error(`[voice] Failed to spawn '${config.claudeCliCommand}' in ${cwd}:`, (err as Error).message);
    finish(null);
    return { stop: () => {}, kill: () => {}, done };
  }

  progress?.start();

  const signal = (sig: NodeJS.Signals): void => {
    if (exited) return;
    try {
      child.kill(sig);
    } catch {
      /* already gone */
    }
  };
  // SIGINT/SIGTERM first so Claude can stop its hooks and save the
  // transcript; SIGKILL only if it ignores both.
  const escalate = (first: NodeJS.Signals): void => {
    signal(first);
    if (first === "SIGINT") timers.push(setTimeout(() => signal("SIGTERM"), STOP_GRACE_MS));
    timers.push(setTimeout(() => signal("SIGKILL"), STOP_GRACE_MS * 2));
  };

  timers.push(
    setTimeout(() => {
      timedOut = true;
      console.warn(`[voice] Run exceeded ${Math.round(maxRunMs / 1000)}s — stopping it.`);
      escalate("SIGTERM");
    }, maxRunMs),
  );

  const lines = new StreamJsonLines();
  const handle = (raw: unknown): void => {
    progress?.ingest(raw);
    const u = parseStreamEvent(raw);
    if (u.model) {
      status.model = prettyModel(u.model);
      emitStatus();
    }
    if (u.tools) {
      toolEvents.push(...u.tools);
      if (toolEvents.length > TOOL_EVENTS_MAX) toolEvents.splice(0, toolEvents.length - TOOL_EVENTS_MAX);
      cb.onToolEvents([...toolEvents]);
      const latest = u.tools[u.tools.length - 1];
      if (latest) cb.onActivity(activityForTool(latest));
    }
    if (u.text) {
      pendingText = u.text;
      responseTimer ??= setTimeout(flushResponse, RESPONSE_DEBOUNCE_MS);
    }
    if (u.rateLimit) {
      const t = shortTime(u.rateLimit.resetsAt);
      if (u.rateLimit.kind === "session") status.sessionResets = t;
      else if (u.rateLimit.kind === "weekly") status.weeklyResets = t;
      else status.monthlyResets = t;
      emitStatus();
    }
    if (u.result) {
      result = u.result;
      if (u.result.contextWindow) status.contextSize = formatContextWindow(u.result.contextWindow);
      emitStatus();
      if (u.result.tokens > 0) {
        store.add(u.result.tokens);
        store.persist();
        cb.onMetrics(store.snapshot());
      }
      // The final answer supersedes any pending preview.
      pendingText = null;
    }
  };

  child.stdout?.on("data", (chunk: Buffer) => {
    const s = chunk.toString("utf8");
    rawBytes += s.length;
    for (const ev of lines.push(s)) handle(ev);
  });
  child.stderr?.on("data", (chunk: Buffer) => {
    const s = chunk.toString("utf8");
    stderr = (stderr + s).slice(-8_192);
    process.stderr.write(s); // LaunchAgent log
  });
  child.on("error", (err) => {
    console.error("[voice] claude process error:", err.message);
    exited = true;
    finish(null);
  });
  child.on("close", (code, sig) => {
    exited = true;
    for (const ev of lines.flush()) handle(ev);
    const exitCode = exitCodeOf(code, sig);
    if (exitCode !== 0 && !stopped) {
      console.error(`[voice] claude exited ${exitCode}${sig ? ` (${sig})` : ""}; stderr: ${stderr.slice(-400)}`);
    }
    finish(exitCode);
  });

  return {
    stop: () => {
      if (exited || stopped) return;
      stopped = true;
      escalate("SIGINT");
    },
    kill: () => signal("SIGKILL"),
    done,
  };
}
