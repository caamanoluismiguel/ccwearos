// Live progress of a voice run (`/progress`, RunProgress in types/schema.ts).
// A long task (a slow `find` over ~/Downloads, say) used to show only
// "Trabajando" on the watch for minutes; this turns the stream-json events
// into "paso 3 · Buscando archivos · ~/Downloads" plus Claude's own latest
// sentence.
//
// ProgressTracker is pure (fed parsed stream-json events + a clock).
// ProgressPublisher decides WHEN to write: immediately on a step change,
// otherwise at most once per second, plus a liveness re-write every 10s so
// the watch gets fresh data during a long silent tool. lastEventAt is never
// faked: it is always the time of the last real stream event.

import { homedir } from "node:os";
import { basename } from "node:path";
import type { RunProgress } from "./types/schema.js";

export const LABEL_MAX = 28;
export const DETAIL_MAX = 40;
export const INTENT_MAX = 90;

export const LABEL_THINKING = "Pensando";
export const LABEL_NEXT_STEP = "Pensando el siguiente paso";

type Json = Record<string, unknown>;
const obj = (v: unknown): Json | null =>
  v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : null;
const str = (v: unknown): string | null =>
  typeof v === "string" && v.trim().length > 0 ? v.trim() : null;

function clipHead(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max - 1).trimEnd() + "…";
}
function clipTail(s: string, max: number): string {
  return s.length <= max ? s : "…" + s.slice(s.length - (max - 1));
}

// "/Users/me/Library/Mobile Documents/x" → "…/Mobile Documents/x"; home → ~;
// short paths stay whole ("~/Downloads").
export function shortPath(p: string, home: string = homedir()): string {
  let s = p.trim().replace(/\/+$/, "") || "/";
  if (home && (s === home || s.startsWith(home + "/"))) s = "~" + s.slice(home.length);
  const rooted = s.startsWith("/") || s.startsWith("~");
  const root = s.startsWith("~") ? "~" : s.startsWith("/") ? "" : null;
  const parts = s.split("/").filter((x, i) => x.length > 0 && !(i === 0 && x === "~"));
  let out: string;
  if (parts.length <= 2) out = rooted ? `${root}/${parts.join("/")}` : parts.join("/");
  else out = "…/" + parts.slice(-2).join("/");
  if (out === "~/") out = "~";
  return clipTail(out, DETAIL_MAX);
}

function domainOf(url: string): string | null {
  try {
    const host = new URL(url).hostname.replace(/^www\./, "");
    return host ? clipHead(host, DETAIL_MAX) : null;
  } catch {
    return null;
  }
}

// Whitespace / quote aware split of one shell line (good enough for labels).
function shellWords(line: string): string[] {
  const out: string[] = [];
  const re = /"((?:[^"\\]|\\.)*)"|'([^']*)'|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line)) !== null) out.push(m[1] ?? m[2] ?? m[3] ?? "");
  return out;
}

const BASH_LABELS: [RegExp, string][] = [
  [/^(find|mdfind|fd|ls|grep|rg|egrep|locate|tree)$/, "Buscando archivos"],
  [/^(cat|head|tail|sed|less|more|awk|wc)$/, "Leyendo archivos"],
  [/^(mv|cp|mkdir|rsync|ditto)$/, "Moviendo archivos"],
  [/^git$/, "Usando git"],
  [/^(npm|pnpm|yarn|npx|node|tsx|bun)$/, "Corriendo el proyecto"],
  [/^(osascript|open|shortcuts)$/, "Usando apps del Mac"],
  [/^(curl|wget)$/, "Consultando internet"],
];

// The command that matters: first segment that isn't `cd …`, minus env
// assignments and sudo/time/nice prefixes.
function commandWords(command: string): string[] {
  const first = command.split("\n")[0] ?? "";
  const segments = first.split(/&&|\|\||;|\|/).map((x) => x.trim()).filter(Boolean);
  for (const seg of segments) {
    const words = shellWords(seg);
    while (words.length > 0 && /^([A-Za-z_]\w*=|sudo$|time$|nice$|command$|exec$)/.test(words[0] ?? "")) {
      words.shift();
    }
    if (words.length === 0) continue;
    if (words[0] === "cd") continue;
    return words;
  }
  return [];
}

function bashStep(command: string, home: string): { label: string; detail?: string } {
  const words = commandWords(command);
  const prog = basename(words[0] ?? "");
  const label = BASH_LABELS.find(([re]) => re.test(prog))?.[1] ?? "Corriendo un comando";
  const args = words.slice(1);
  if (prog === "curl" || prog === "wget") {
    for (const a of args) {
      const d = /^https?:\/\//i.test(a) ? domainOf(a) : null;
      if (d) return { label, detail: d };
    }
    return { label };
  }
  // First path-like argument (never the whole command).
  const path = args.find((a) => !a.startsWith("-") && /^(\/|~|\.{1,2}\/)/.test(a));
  return path ? { label, detail: shortPath(path, home) } : { label };
}

// "mcp__claude_ai_Gmail__send" → "Gmail"; "mcp__github__x" → "github".
function mcpServer(name: string): string {
  const server = name.slice("mcp__".length).split("__")[0] ?? "";
  return server.replace(/^claude_ai_/, "").replace(/^plugin_[^_]+_/, "").replace(/_/g, " ").trim() || "una herramienta";
}

// Spanish label (≤28) + short target (≤40) for one tool_use block.
export function describeTool(
  name: string,
  input: Json,
  home: string = homedir(),
): { label: string; detail?: string } {
  const s = (k: string): string | null => str(input[k]);
  const file = s("file_path") ?? s("notebook_path");
  const withFile = (verb: string): { label: string; detail?: string } =>
    file
      ? { label: clipHead(`${verb} ${basename(file)}`, LABEL_MAX), detail: shortPath(file, home) }
      : { label: verb };
  const textDetail = (v: string | null): { detail?: string } =>
    v ? { detail: clipHead(v.replace(/\s+/g, " "), DETAIL_MAX) } : {};
  let r: { label: string; detail?: string };
  switch (name) {
    case "Bash": {
      const cmd = s("command");
      r = cmd ? bashStep(cmd, home) : { label: "Corriendo un comando" };
      break;
    }
    case "Read":
    case "NotebookRead":
      r = withFile("Leyendo");
      break;
    case "Edit":
    case "Write":
    case "MultiEdit":
    case "NotebookEdit":
      r = withFile("Editando");
      break;
    case "Glob":
    case "Grep":
    case "LS": {
      const p = s("path");
      r = { label: "Buscando en archivos", ...(p ? { detail: shortPath(p, home) } : textDetail(s("pattern"))) };
      break;
    }
    case "WebFetch": {
      const url = s("url");
      const d = url ? domainOf(url) : null;
      r = { label: "Leyendo una página", ...(d ? { detail: d } : {}) };
      break;
    }
    case "WebSearch":
      r = { label: "Buscando en la web", ...textDetail(s("query")) };
      break;
    case "Task":
    case "Agent":
      r = { label: "Delegando una parte", ...textDetail(s("description")) };
      break;
    case "TodoWrite":
      r = { label: "Organizando el plan" };
      break;
    default:
      r = name.startsWith("mcp__")
        ? { label: clipHead(`Usando ${mcpServer(name)}`, LABEL_MAX) }
        : { label: clipHead(`Usando ${name}`, LABEL_MAX) };
  }
  return r;
}

// Lines that are not Claude narrating its work: echoes of the prompt
// prefix and the TL;DR headline (skipped), the followups block (ends it).
const SKIP_LINE_RE = /^(contexto:|context:|responde así|reply like this|tl;?dr\b)/i;
const END_LINE_RE = /^(sugerencias|followups):/i;

function stripMarkdown(s: string): string {
  return s
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/(\*\*|__|\*|_|~~)(?=\S)([^*_~]*?\S)\1/g, "$2")
    .replace(/^\s*(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)/, "")
    .replace(/\s+/g, " ")
    .trim();
}

// Claude's latest interim sentence: first sentence of the first real line,
// markdown stripped, ≤90 chars. null when the block has nothing usable.
export function intentFromText(text: string): string | null {
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    const plain = line.replace(/[*_#>`]/g, "").trim();
    if (END_LINE_RE.test(plain)) break;
    if (!plain || SKIP_LINE_RE.test(plain)) continue;
    const clean = stripMarkdown(line);
    if (!clean || /^[-|:\s]+$/.test(clean)) continue;
    const m = clean.match(/^.+?[.!?…](?=\s|$)/);
    const sentence = (m ? m[0] : clean).replace(/[:;,]\s*$/, "").trim();
    if (!sentence) continue;
    return clipHead(sentence, INTENT_MAX);
  }
  return null;
}

export interface IngestResult {
  changed: boolean; // the snapshot differs from before
  stepChanged: boolean; // a new tool started (publish right away)
}

export class ProgressTracker {
  private step = 0;
  private label = LABEL_THINKING;
  private detail: string | undefined;
  private intent: string | undefined;
  private stepStartedAt: number;
  private lastEventAt: number;
  private readonly runStartedAt: number;
  private readonly openTools = new Set<string>();
  private readonly home: string;

  constructor(opts: { runStartedAt: number; home?: string }) {
    this.runStartedAt = opts.runStartedAt;
    this.stepStartedAt = opts.runStartedAt;
    this.lastEventAt = opts.runStartedAt;
    this.home = opts.home ?? homedir();
  }

  snapshot(): RunProgress {
    const p: RunProgress = {
      step: this.step,
      label: this.label,
      stepStartedAt: this.stepStartedAt,
      lastEventAt: this.lastEventAt,
      runStartedAt: this.runStartedAt,
    };
    if (this.detail !== undefined) p.detail = this.detail;
    if (this.intent !== undefined) p.intent = this.intent;
    return p;
  }

  // One parsed stream-json event. Every event (any type) bumps lastEventAt.
  ingest(raw: unknown, now: number): IngestResult {
    const before = JSON.stringify(this.snapshot());
    const stepBefore = this.step;
    this.lastEventAt = now;
    const ev = obj(raw);
    const type = ev ? str(ev["type"]) : null;
    const content = (): unknown[] => {
      const msg = obj(ev?.["message"]);
      return Array.isArray(msg?.["content"]) ? (msg["content"] as unknown[]) : [];
    };

    if (ev && type === "assistant") {
      const topLevel = ev["parent_tool_use_id"] === null || ev["parent_tool_use_id"] === undefined;
      for (const b of content()) {
        const block = obj(b);
        if (!block) continue;
        if (block["type"] === "text" && topLevel) {
          const t = typeof block["text"] === "string" ? block["text"] : "";
          const intent = intentFromText(t);
          if (intent) this.intent = intent;
        } else if (block["type"] === "tool_use") {
          const name = str(block["name"]);
          if (!name) continue;
          const d = describeTool(name, obj(block["input"]) ?? {}, this.home);
          this.step += 1;
          this.stepStartedAt = now;
          this.label = d.label;
          this.detail = d.detail;
          const id = str(block["id"]);
          if (id) this.openTools.add(id);
        }
      }
    } else if (ev && type === "user") {
      let finished = false;
      for (const b of content()) {
        const block = obj(b);
        if (block?.["type"] !== "tool_result") continue;
        const id = str(block["tool_use_id"]);
        if (id) this.openTools.delete(id);
        finished = true;
      }
      // The step stays; Claude is now thinking about what comes next.
      if (finished && this.openTools.size === 0 && this.step > 0) {
        this.label = LABEL_NEXT_STEP;
        this.detail = undefined;
      }
    }
    return {
      changed: JSON.stringify(this.snapshot()) !== before,
      stepChanged: this.step !== stepBefore,
    };
  }
}

export interface ProgressPublisherOptions {
  throttleMs?: number; // min gap between ordinary writes (default 1s)
  livenessMs?: number; // re-write after this long without a write (default 10s)
  now?: () => number;
}

// Owns the write cadence for one run. start() → ingest()* → stop().
export class ProgressPublisher {
  private readonly throttleMs: number;
  private readonly livenessMs: number;
  private readonly now: () => number;
  private lastWriteAt = -Infinity;
  private trailing: NodeJS.Timeout | null = null;
  private liveness: NodeJS.Timeout | null = null;
  private stopped = false;

  constructor(
    private readonly tracker: ProgressTracker,
    private readonly write: (p: RunProgress) => void,
    opts: ProgressPublisherOptions = {},
  ) {
    this.throttleMs = opts.throttleMs ?? 1_000;
    this.livenessMs = opts.livenessMs ?? 10_000;
    this.now = opts.now ?? Date.now;
  }

  // First snapshot (step 0, "Pensando") right away.
  start(): void {
    this.flush();
  }

  ingest(raw: unknown): void {
    if (this.stopped) return;
    const r = this.tracker.ingest(raw, this.now());
    if (r.stepChanged) {
      this.flush();
      return;
    }
    // lastEventAt moves on every event; the throttle keeps that to ≤1/s.
    const wait = this.lastWriteAt + this.throttleMs - this.now();
    if (wait <= 0) this.flush();
    else this.trailing ??= setTimeout(() => this.flush(), wait);
  }

  // No writes after this (the caller nulls /progress at run end).
  stop(): void {
    this.stopped = true;
    this.clearTimers();
  }

  private clearTimers(): void {
    if (this.trailing) clearTimeout(this.trailing);
    if (this.liveness) clearTimeout(this.liveness);
    this.trailing = null;
    this.liveness = null;
  }

  private flush(): void {
    if (this.stopped) return;
    this.clearTimers();
    this.lastWriteAt = this.now();
    this.write(this.tracker.snapshot());
    this.liveness = setTimeout(() => this.flush(), this.livenessMs);
    this.liveness.unref?.();
  }
}
