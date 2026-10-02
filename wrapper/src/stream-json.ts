// Parser for `claude -p --output-format stream-json --verbose` (one JSON
// event per line). The daemon's voice runs use this instead of scraping the
// interactive TUI: the final answer comes verbatim from the `result` event,
// tool calls arrive as structured `tool_use` blocks. Pure — the runner in
// src/claude-voice.ts feeds it stdout and acts on what it returns.

import { basename } from "node:path";
import type { BlockingDialog } from "./parser.js";
import type { ToolEvent } from "./types/schema.js";

export interface StreamResult {
  text: string; // `result` — the final answer (or the error text)
  isError: boolean;
  subtype: string | null; // "success", "error_max_turns", …
  numTurns: number | null;
  totalCostUsd: number | null;
  tokens: number; // input + output (+ cache) tokens of the run
  contextWindow: number | null;
  permissionDenials: number;
}

// What one event changed. Every field is optional; the runner applies it.
export interface StreamUpdate {
  model?: string;
  text?: string; // newest top-level assistant text block (live preview)
  tools?: ToolEvent[]; // tool_use blocks in this event, in order
  rateLimit?: { kind: "session" | "weekly" | "other"; resetsAt: number };
  result?: StreamResult;
}

type Json = Record<string, unknown>;
const obj = (v: unknown): Json | null =>
  v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : null;
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);
const num = (v: unknown): number | null =>
  typeof v === "number" && Number.isFinite(v) ? v : null;

const ARG_MAX = 60;

// Keep the END of a path (the file name is what matters on a wrist).
function tail(s: string, max: number = ARG_MAX): string {
  return s.length <= max ? s : "…" + s.slice(s.length - (max - 1));
}
function head(s: string, max: number = ARG_MAX): string {
  return s.length <= max ? s : s.slice(0, max - 1) + "…";
}

// Short, human argument for a tool chip: file path, command head, query…
export function toolArg(name: string, input: Json): string | null {
  const s = (k: string): string | null => {
    const v = str(input[k]);
    return v && v.trim().length > 0 ? v.trim() : null;
  };
  const h = (k: string): string | null => {
    const v = s(k);
    return v ? head(v) : null;
  };
  switch (name) {
    case "Bash": {
      const cmd = s("command");
      return cmd ? head(cmd.split("\n")[0] ?? cmd) : null;
    }
    case "Edit":
    case "MultiEdit":
    case "Write":
    case "Read":
    case "NotebookEdit": {
      const p = s("file_path") ?? s("notebook_path");
      return p ? tail(p) : null;
    }
    case "Grep":
    case "Glob":
      return h("pattern");
    case "WebFetch":
      return h("url");
    case "WebSearch":
      return h("query");
    case "Task":
    case "Agent":
      return h("description");
    default: {
      for (const v of Object.values(input)) {
        if (typeof v === "string" && v.trim()) return head(v.trim());
      }
      return null;
    }
  }
}

// Page 1 activity line for the newest tool ("Editando parser.ts").
export function activityForTool(ev: ToolEvent): string {
  const file = ev.arg ? basename(ev.arg).slice(0, 36) : null;
  switch (ev.tool) {
    case "Bash":
      return "Ejecutando un comando";
    case "Edit":
    case "MultiEdit":
    case "NotebookEdit":
      return file ? `Editando ${file}` : "Editando";
    case "Write":
      return file ? `Escribiendo ${file}` : "Escribiendo";
    case "Read":
      return file ? `Leyendo ${file}` : "Leyendo";
    case "Grep":
    case "Glob":
      return "Buscando archivos";
    case "WebSearch":
    case "WebFetch":
      return "Buscando en la web";
    case "Task":
    case "Agent":
      return ev.arg ? `Subagente: ${ev.arg.slice(0, 36)}` : "Subagente";
    default:
      return `Usando ${ev.tool}`;
  }
}

// One parsed stream-json event → what changed. Unknown events → {}.
export function parseStreamEvent(raw: unknown, now: number = Date.now()): StreamUpdate {
  const ev = obj(raw);
  if (!ev) return {};
  const type = str(ev["type"]);

  if (type === "system" && ev["subtype"] === "init") {
    const model = str(ev["model"]);
    return model ? { model } : {};
  }

  if (type === "assistant") {
    const msg = obj(ev["message"]);
    const content = Array.isArray(msg?.["content"]) ? (msg["content"] as unknown[]) : [];
    // Sub-agent messages carry parent_tool_use_id: their tools count, their
    // text is the sub-agent talking to Claude, not the answer.
    const topLevel = ev["parent_tool_use_id"] === null || ev["parent_tool_use_id"] === undefined;
    const out: StreamUpdate = {};
    const tools: ToolEvent[] = [];
    let text = "";
    for (const b of content) {
      const block = obj(b);
      if (!block) continue;
      if (block["type"] === "text" && topLevel) {
        const t = str(block["text"]);
        if (t && t.trim()) text = t.trim();
      } else if (block["type"] === "tool_use") {
        const name = str(block["name"]);
        if (!name) continue;
        tools.push({ tool: name, arg: toolArg(name, obj(block["input"]) ?? {}), ts: now });
      }
    }
    if (text) out.text = text;
    if (tools.length > 0) out.tools = tools;
    return out;
  }

  if (type === "rate_limit_event") {
    const info = obj(ev["rate_limit_info"]);
    const resetsAt = num(info?.["resetsAt"]);
    if (resetsAt === null) return {};
    const t = str(info?.["rateLimitType"]);
    const kind = t === "five_hour" ? "session" : t?.startsWith("seven_day") || t === "weekly" ? "weekly" : "other";
    return { rateLimit: { kind, resetsAt } };
  }

  if (type === "result") {
    const usage = obj(ev["usage"]);
    const tokens = ["input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"]
      .map((k) => num(usage?.[k]) ?? 0)
      .reduce((a, b) => a + b, 0);
    let contextWindow: number | null = null;
    const mu = obj(ev["modelUsage"]);
    for (const v of Object.values(mu ?? {})) {
      const cw = num(obj(v)?.["contextWindow"]);
      if (cw) {
        contextWindow = cw;
        break;
      }
    }
    const denials = Array.isArray(ev["permission_denials"]) ? ev["permission_denials"].length : 0;
    return {
      result: {
        text: (str(ev["result"]) ?? "").trim(),
        isError: ev["is_error"] === true,
        subtype: str(ev["subtype"]),
        numTurns: num(ev["num_turns"]),
        totalCostUsd: num(ev["total_cost_usd"]),
        tokens,
        contextWindow,
        permissionDenials: denials,
      },
    };
  }
  return {};
}

// Splits a stdout chunk stream into JSON events (lines may be split across
// chunks). Non-JSON lines are ignored.
export class StreamJsonLines {
  private buf = "";

  push(chunk: string): unknown[] {
    this.buf += chunk;
    const lines = this.buf.split("\n");
    this.buf = lines.pop() ?? "";
    return lines.flatMap((l) => parseLine(l));
  }

  flush(): unknown[] {
    const rest = this.buf;
    this.buf = "";
    return parseLine(rest);
  }
}

function parseLine(line: string): unknown[] {
  const t = line.trim();
  if (!t.startsWith("{")) return [];
  try {
    return [JSON.parse(t) as unknown];
  } catch {
    return [];
  }
}

// ─── Blockers a -p run can still hit ─────────────────────────────────────────
// No dialogs in -p mode (the trust dialog is skipped, per `claude --help`),
// but a logged-out Mac still fails every run. Detected from the result's
// error text or, when there is no result event, from stderr.
const AUTH_RE =
  /invalid api key|please run \/login|oauth token (?:has )?expired|not logged in|authentication_error|credit balance is too low/i;
const TRUST_RE = /trust (?:this|the) (?:folder|workspace|directory)|workspace trust/i;

export function detectRunBlocker(a: {
  result: StreamResult | null;
  stderr: string;
}): BlockingDialog | null {
  const firstMatch = (text: string, re: RegExp): string | null => {
    const line = text.split("\n").find((l) => re.test(l));
    return line ? line.trim().slice(0, 200) : null;
  };
  if (a.result) {
    if (!a.result.isError) return null;
    const auth = firstMatch(a.result.text, AUTH_RE);
    return auth ? { kind: "auth", detail: auth } : null;
  }
  const auth = firstMatch(a.stderr, AUTH_RE);
  if (auth) return { kind: "auth", detail: auth };
  if (TRUST_RE.test(a.stderr)) return { kind: "trust", detail: "" };
  return null;
}
