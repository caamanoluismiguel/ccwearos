// Parsing helpers for Claude Code stdout. Pure functions — keep side effects
// out of this module.

// Claude Code is a TUI: every chunk is laced with ANSI escape sequences
// (colors, cursor moves, clear-line). Strip those before applying any regex
// or "Tokens used:" gets fragmented across color spans and never matches.
const ESC = String.fromCharCode(0x1b);
const ANSI_RE = new RegExp(`${ESC}\\[[0-9;?]*[ -\\/]*[@-~]`, "g");
const OSC_RE = new RegExp(`${ESC}\\][^${ESC}\\x07]*(?:${ESC}\\\\|\\x07)`, "g");

function clean(chunk: string): string {
  return chunk.replace(ANSI_RE, "").replace(OSC_RE, "");
}

// Per-chunk increments, e.g. Claude Code 2.1.x streaming:
//   "↓ 279 tokens"     "103 tokens · thinking)"     "1,240 input tokens"
// Loose by design — every match is a positive delta we add to the rolling
// window. Sprint 2 hardening can layer in stricter cumulative tracking
// (see extractSessionCumulative below) once we trust the format.
const INCREMENTAL_PATTERNS: RegExp[] = [
  /tokens?\s+used:?\s*(\d[\d,]*)/gi,
  /usage:?\s*(\d[\d,]*)\s*t(?:k|ok)ns?/gi,
  /(\d[\d,]*)\s+input\s+tokens?/gi,
  /(\d[\d,]*)\s+output\s+tokens?/gi,
  /[↓↑⇣⇡]\s*(\d[\d,]*)\s*tokens?/gi,
  /\bout\s+(\d[\d,]*)\b/gi,
];

// Cumulative session count, e.g. "○○○○○○○○ 5% (49k/1.0m)".
// Captures "49k", "1.2m", "850" — convert later.
const CUMULATIVE_PATTERN =
  /\((\d+(?:\.\d+)?[kmKM]?)\s*\/\s*\d+(?:\.\d+)?[kmKM]?\)/g;

// Terminal title (OSC 0): ESC ] 0 ; <text> BEL  (or ESC \).
// Claude updates this every spinner tick with "<spinner-char> <task>".
const OSC_TITLE_RE = new RegExp(
  `${ESC}\\]0;([^\\x07${ESC}]*?)(?:\\x07|${ESC}\\\\)`,
  "g",
);

// Spinner-prefixed activity verbs in the body text:
//   "Crunching…"  "Worked for 33s"  "Incubating…"  "Razzmatazzing…"
// Stripped chunk has no ANSI, so the regular words and ellipses survive.
const ACTIVITY_PATTERNS: RegExp[] = [
  /\b([A-Z][a-z]+(?:zz)?ing)[…⋯]/g,
  /\b(Worked|Brewed|Boiled|Cooked|Razzmatazzed)\s+for\s+\d+s\b/g,
];

// Permission prompts — match anywhere in the chunk, then expand to the line.
const PERMISSION_PATTERNS: RegExp[] = [
  // Most informative: Claude Code 2.1.x friendly prompt.
  /Claude wants to .+/i,
  /\[y\/n\]/i,
  /^\s*allow\??\s*$/im,
  /do you (?:want to )?(?:allow|continue|approve)/i,
  /^\s*\(?\s*y\s*\/\s*n\s*\)?\s*$/im,
  /^\s*❯?\s*\d?\.?\s*Yes\s*$/im,
  /Allow\s+.+\?/i,
];

export function extractTokenCounts(chunk: string): number[] {
  const text = clean(chunk);
  const out: number[] = [];
  for (const re of INCREMENTAL_PATTERNS) {
    const fresh = new RegExp(re.source, re.flags);
    let m: RegExpExecArray | null;
    while ((m = fresh.exec(text)) !== null) {
      const raw = m[1];
      if (!raw) continue;
      const n = Number(raw.replace(/,/g, ""));
      if (Number.isFinite(n) && n > 0) out.push(n);
    }
  }
  return out;
}

// Back-compat: single-match version. Returns first match or null.
export function extractTokenCount(chunk: string): number | null {
  const all = extractTokenCounts(chunk);
  return all[0] ?? null;
}

// Extracts the most recent cumulative session total (in tokens), parsing
// formats like "(49k/1.0m)", "(850/1000)", "(1.2m/1.0m)". Returns null if
// no match.
export function extractSessionCumulative(chunk: string): number | null {
  const text = clean(chunk);
  const fresh = new RegExp(CUMULATIVE_PATTERN.source, CUMULATIVE_PATTERN.flags);
  let m: RegExpExecArray | null;
  let last: number | null = null;
  while ((m = fresh.exec(text)) !== null) {
    const v = parseUnit(m[1]);
    if (v !== null) last = v;
  }
  return last;
}

function parseUnit(raw: string | undefined): number | null {
  if (!raw) return null;
  const s = raw.toLowerCase().trim();
  const mult = s.endsWith("k") ? 1_000 : s.endsWith("m") ? 1_000_000 : 1;
  const numPart = mult === 1 ? s : s.slice(0, -1);
  const n = Number(numPart);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.round(n * mult);
}

export function extractPermissionPrompt(chunk: string): string | null {
  const text = clean(chunk);
  for (const re of PERMISSION_PATTERNS) {
    const m = text.match(re);
    if (!m) continue;
    const matchText = m[0];
    const trimmedMatch = matchText.trim();
    if (re.source.startsWith("^")) {
      if (trimmedMatch.length > 0) return trimmedMatch;
      continue;
    }
    const idx = text.indexOf(matchText);
    if (idx < 0) continue;
    const lineStart = text.lastIndexOf("\n", idx - 1) + 1;
    const newlineAfter = text.indexOf("\n", idx);
    const lineEnd = newlineAfter === -1 ? text.length : newlineAfter;
    const line = text.slice(lineStart, lineEnd).trim();
    return line.length > 0 ? line : trimmedMatch;
  }
  return null;
}

export function isAwaitingPermission(chunk: string): boolean {
  return extractPermissionPrompt(chunk) !== null;
}

// Extract the latest terminal title (OSC 0) — Claude embeds the current task
// description here. Leading spinner glyph is trimmed. Operates on the RAW
// chunk because the OSC sequence is what carries the data.
export function extractCurrentTask(chunk: string): string | null {
  const re = new RegExp(OSC_TITLE_RE.source, OSC_TITLE_RE.flags);
  let m: RegExpExecArray | null;
  let last: string | null = null;
  while ((m = re.exec(chunk)) !== null) {
    const raw = m[1]?.trim();
    if (!raw) continue;
    // Strip a leading non-word "spinner" character + whitespace.
    const stripped = raw.replace(/^[^A-Za-z0-9]+\s*/, "").trim();
    if (stripped) last = stripped;
  }
  // "Claude Code" is the default title when no task is active — treat as none.
  if (last && /^claude code$/i.test(last)) return null;
  return last;
}

// Filter Claude Code's stdout down to the lines a human would think of as
// "the response" — strip the persistent TUI chrome (status bars, input prompt,
// pure-border rows) so the watch can show what Claude actually said.
// IMPORTANT: pure border lines (├─┼─┤) are dropped, but rows that contain │
// with actual content survive — they're tables we want to reformat below.
const NOISE_PATTERNS: RegExp[] = [
  /^\s*[─━╴╵╶╷╭╮╰╯├┤┬┴┼+\-_=]+\s*$/, // pure border row, no content
  /^\s*Opus\s+\d/i,
  /^\s*session\s+[○●]/i,
  /^\s*weekly\s+[○●]/i,
  /^\s*monthly\s+[\$○●]/i,
  /^\s*[⏵⏵◉]\s*accept/i,
  /^\s*[⏵◉]/, // mode toggles, blocky markers
  /^\s*❯\s*$/, // empty input prompt
  /^\s*\d+s\s+elapsed/i,
  /^\s*[\d.]+s\s+api/i,
  /^\s*Tip:/i,
  /^\s*\(\d+s\s*·/, // "(15s · ↓ 378 tokens)"
  /^\s*⎿\s*Tip:/i,
];

// Lines that look like ASCII table rows (│ cell │ cell │) get flattened into
// "cell · cell · cell" — much more legible on a 320dp wide round display
// than trying to render the actual table.
function flattenTableRow(line: string): string {
  if (!line.includes("│")) return line;
  const cells = line
    .split("│")
    .map((c) => c.trim())
    .filter((c) => c.length > 0);
  if (cells.length < 2) return line;
  return cells.join(" · ");
}

// "[label](https://very.long/url?with=params)" → "label" — on a watch the
// URLs are unclickable noise that makes the response unreadable. Also
// strips bare URLs in parens "(https://...)" and excess whitespace.
function cleanForWatch(s: string): string {
  return s
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1") // markdown links → label
    .replace(/\s*\(https?:\/\/[^\s)]+\)\s*/g, " ") // bare (url)
    .replace(/https?:\/\/\S+/g, "") // dangling URLs
    .replace(/[ \t]{2,}/g, " ") // collapse spaces
    .replace(/[ \t]+([,.;:!?])/g, "$1") // tighten before punctuation
    .trim();
}

export function extractResponseLines(buffer: string): string {
  const text = clean(buffer);
  const out: string[] = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line.length < 2) continue;
    if (NOISE_PATTERNS.some((re) => re.test(line))) continue;
    const flattened = flattenTableRow(line);
    const cleaned = cleanForWatch(flattened);
    if (cleaned.length < 2) continue;
    out.push(cleaned);
  }
  const tail = out.slice(-40).join("\n");
  return tail.length > 1500 ? tail.slice(-1500) : tail;
}

// Pull Claude Code's full status line out of a chunk. Returns a partial
// ClaudeStatus — fields that didn't appear stay null. Operates on the
// ANSI-stripped text; preserves bullet glyphs but pure-color spans drop out.
import type { ClaudeStatus } from "./types/schema.js";

const MODEL_RE = /\b(Opus|Sonnet|Haiku)\s+([\d.]+)(?:\s*\(([^)]+)\))?/i;
const CONTEXT_PCT_RE =
  /(\d+(?:\.\d+)?)\s*%\s*\(\s*\d+(?:\.\d+)?[kmKM]?\s*\/\s*(\d+(?:\.\d+)?[kmKM]?)\s*\)/;
const SESSION_RE =
  /\bsession\b[^\n%]*?(\d+(?:\.\d+)?)\s*%(?:[^\n]*?resets?\s+([^·│\n]+?)(?:[·│\n]|$))?/i;
const WEEKLY_RE =
  /\bweekly\b[^\n%]*?(\d+(?:\.\d+)?)\s*%(?:[^\n]*?resets?\s+([^·│\n]+?)(?:[·│\n]|$))?/i;
const MONTHLY_RE =
  /\bmonthly\b[^\n]*?(\$[\d.]+)(?:[^\n]*?resets?\s+([^·│\n]+?)(?:[·│\n]|$))?/i;

export function extractClaudeStatus(chunk: string): Partial<ClaudeStatus> {
  const text = clean(chunk);
  const out: Partial<ClaudeStatus> = {};

  const m = text.match(MODEL_RE);
  if (m) {
    out.model = `${m[1]} ${m[2]}`;
    // "(1M context)" → "1M"; otherwise leave null.
    if (m[3]) {
      const ctxMatch = m[3].match(/([\d.]+\s*[kmKM])/);
      if (ctxMatch) out.contextSize = ctxMatch[1]?.replace(/\s+/g, "") ?? null;
    }
  }

  const c = text.match(CONTEXT_PCT_RE);
  if (c?.[1]) {
    const pct = Number(c[1]);
    if (Number.isFinite(pct)) out.contextPct = pct;
    if (!out.contextSize && c[2]) out.contextSize = c[2];
  }

  const s = text.match(SESSION_RE);
  if (s?.[1]) {
    out.sessionPct = Number(s[1]);
    if (s[2]) out.sessionResets = s[2].trim();
  }

  const w = text.match(WEEKLY_RE);
  if (w?.[1]) {
    out.weeklyPct = Number(w[1]);
    if (w[2]) out.weeklyResets = w[2].trim();
  }

  const mo = text.match(MONTHLY_RE);
  if (mo?.[1]) {
    out.monthlyCost = mo[1];
    if (mo[2]) out.monthlyResets = mo[2].trim();
  }

  return out;
}

// Latest activity verb: "Crunching…", "Razzmatazzing…", "Worked for 33s".
// Returns the most recent occurrence in the chunk, or null.
export function extractActivity(chunk: string): string | null {
  const text = clean(chunk);
  let last: string | null = null;
  for (const re of ACTIVITY_PATTERNS) {
    const fresh = new RegExp(re.source, re.flags);
    let m: RegExpExecArray | null;
    while ((m = fresh.exec(text)) !== null) {
      const v = m[0]?.trim();
      if (v) last = v;
    }
  }
  return last;
}
