// One-shot Claude Code runner for the daemon flow. Uses
//   claude -p <prompt> --output-format=stream-json --verbose
// and parses the JSON event stream — much cleaner than text-mode parsing
// because we get the raw response text and structured usage metrics
// (model, cost, tokens, rate-limit resets) without any TUI chrome.

import { spawn } from "node:child_process";
import { config } from "./config.js";
import { createMetricsStore } from "./metrics-store.js";
import type { ClaudeStatus, Metrics } from "./types/schema.js";

export interface OneshotCallbacks {
  onMetrics: (m: Metrics) => void;
  onResponse: (r: string) => void;
  onActivity: (a: string | null) => void;
  onClaudeStatus: (s: ClaudeStatus) => void;
}

export interface OneshotResult {
  exitCode: number | null;
  rawBytes: number;
  finalResponse: string;
  totalCostUsd: number;
}

// Normalise Claude Code's model id ("claude-opus-4-7[1m]") into something
// human-readable ("Opus 4.7"). Returns null if it doesn't match.
function prettyModel(raw: string | undefined): string | null {
  if (!raw) return null;
  const m = raw.match(/^claude-(opus|sonnet|haiku)-(\d+)-(\d+)/i);
  if (!m) return raw;
  const tier = (m[1] ?? "").charAt(0).toUpperCase() + (m[1] ?? "").slice(1);
  return `${tier} ${m[2]}.${m[3]}`;
}

function formatContextWindow(n: number | undefined): string | null {
  if (!n || !Number.isFinite(n)) return null;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(0)}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}

function epochToShortTime(epochSec: number | undefined): string | null {
  if (!epochSec) return null;
  const d = new Date(epochSec * 1000);
  // "may 17, 12:00am" style — same vibe as Claude Code's own status line.
  return d
    .toLocaleString("en-US", {
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
    })
    .toLowerCase();
}

interface StreamSystemInit {
  type: "system";
  subtype: "init";
  model?: string;
}
interface StreamAssistant {
  type: "assistant";
  message: {
    content?: Array<{ type: string; text?: string }>;
  };
}
interface StreamRateLimit {
  type: "rate_limit_event";
  rate_limit_info?: {
    resetsAt?: number;
    rateLimitType?: string;
  };
}
interface StreamResult {
  type: "result";
  result?: string;
  total_cost_usd?: number;
  usage?: {
    input_tokens?: number;
    output_tokens?: number;
  };
  modelUsage?: Record<string, { contextWindow?: number }>;
}
type StreamEvent =
  | StreamSystemInit
  | StreamAssistant
  | StreamRateLimit
  | StreamResult
  | { type: string };

export function runClaudeOneshot(
  prompt: string,
  cb: OneshotCallbacks,
  opts: { continueSession?: boolean } = {},
): Promise<OneshotResult> {
  return new Promise((resolve) => {
    // --continue resumes the most recent conversation in cwd, giving the
    // voice flow turn-by-turn context. First prompt of a daemon run must
    // skip it (no prior conversation yet) — caller controls via opts.
    const args = ["-p", prompt, "--output-format=stream-json", "--verbose"];
    if (opts.continueSession) args.push("--continue");

    const child = spawn(config.claudeCliCommand, args, {
      stdio: ["ignore", "pipe", "pipe"],
      env: process.env,
    });

    const store = createMetricsStore();
    let stdoutBuf = "";
    let stderrBuf = "";
    let totalBytes = 0;
    let responseText = "";
    let responseTimer: NodeJS.Timeout | null = null;

    const accumulated: ClaudeStatus = {
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

    const emitStatusIfChanged = (): void => {
      const json = JSON.stringify(accumulated);
      if (json !== lastStatusJson) {
        lastStatusJson = json;
        cb.onClaudeStatus({ ...accumulated });
      }
    };

    const scheduleResponseFlush = (): void => {
      if (responseTimer) return;
      responseTimer = setTimeout(() => {
        responseTimer = null;
        cb.onResponse(responseText.trim());
      }, 800);
    };

    const handleEvent = (ev: StreamEvent): void => {
      if (ev.type === "system" && (ev as StreamSystemInit).subtype === "init") {
        const init = ev as StreamSystemInit;
        const pretty = prettyModel(init.model);
        if (pretty) accumulated.model = pretty;
        cb.onActivity("Thinking…");
        emitStatusIfChanged();
        return;
      }

      if (ev.type === "assistant") {
        const asst = ev as StreamAssistant;
        for (const block of asst.message?.content ?? []) {
          if (block.type === "text" && block.text) {
            responseText += block.text;
          }
        }
        scheduleResponseFlush();
        return;
      }

      if (ev.type === "rate_limit_event") {
        const rl = (ev as StreamRateLimit).rate_limit_info;
        const reset = epochToShortTime(rl?.resetsAt);
        if (reset) {
          // Map rate-limit-type to the matching field on our status line.
          if (rl?.rateLimitType === "five_hour")
            accumulated.sessionResets = reset;
          else if (rl?.rateLimitType === "weekly")
            accumulated.weeklyResets = reset;
          else accumulated.monthlyResets = reset;
          emitStatusIfChanged();
        }
        return;
      }

      if (ev.type === "result") {
        const r = ev as StreamResult;
        if (typeof r.total_cost_usd === "number") {
          accumulated.monthlyCost = `$${r.total_cost_usd.toFixed(2)}`;
        }
        if (r.modelUsage && accumulated.model) {
          // Try to pull contextWindow for the active model.
          for (const [, usage] of Object.entries(r.modelUsage)) {
            const cw = formatContextWindow(usage?.contextWindow);
            if (cw) {
              accumulated.contextSize = cw;
              break;
            }
          }
        }
        const used =
          (r.usage?.input_tokens ?? 0) + (r.usage?.output_tokens ?? 0);
        if (used > 0) {
          store.add(used);
          cb.onMetrics(store.snapshot());
        }
        // Final response text — overrides any partial buffers.
        if (r.result) {
          responseText = r.result;
          cb.onResponse(responseText.trim());
        }
        emitStatusIfChanged();
        return;
      }
    };

    const processLines = (buf: string): string => {
      let i = 0;
      let lineStart = 0;
      while (i < buf.length) {
        if (buf[i] === "\n") {
          const line = buf.slice(lineStart, i).trim();
          lineStart = i + 1;
          if (line.length > 0) {
            try {
              handleEvent(JSON.parse(line) as StreamEvent);
            } catch {
              // Probably a partial line or non-JSON noise — ignore.
            }
          }
        }
        i++;
      }
      return buf.slice(lineStart);
    };

    child.stdout.on("data", (raw: Buffer) => {
      const chunk = raw.toString("utf8");
      totalBytes += chunk.length;
      stdoutBuf += chunk;
      stdoutBuf = processLines(stdoutBuf);
    });

    child.stderr.on("data", (raw: Buffer) => {
      const chunk = raw.toString("utf8");
      stderrBuf += chunk;
      // Mirror so the LaunchAgent log captures any claude errors.
      process.stderr.write(chunk);
    });

    child.on("error", (err) => {
      console.error("[oneshot] spawn error:", err.message);
      cb.onActivity(null);
      resolve({
        exitCode: null,
        rawBytes: totalBytes,
        finalResponse: "",
        totalCostUsd: 0,
      });
    });

    child.on("exit", (code) => {
      if (responseTimer) clearTimeout(responseTimer);
      // Flush any leftover partial line + final response.
      if (stdoutBuf.trim().length > 0) {
        try {
          handleEvent(JSON.parse(stdoutBuf.trim()) as StreamEvent);
        } catch {
          /* ignore */
        }
      }
      cb.onResponse(responseText.trim());
      cb.onActivity(null);
      if (code !== 0 && stderrBuf.length > 0) {
        console.error(
          `[oneshot] non-zero exit ${code}; stderr:`,
          stderrBuf.slice(0, 400),
        );
      }
      const finalCost = accumulated.monthlyCost
        ? Number(accumulated.monthlyCost.replace("$", "")) || 0
        : 0;
      resolve({
        exitCode: code,
        rawBytes: totalBytes,
        finalResponse: responseText.trim(),
        totalCostUsd: finalCost,
      });
    });
  });
}
