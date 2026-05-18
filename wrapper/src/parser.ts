// Parsing helpers for Claude Code stdout. Pure functions — keep side effects
// out of this module.

// Claude Code is a TUI: every chunk is laced with ANSI escape sequences
// (colors, cursor moves, clear-line). Strip those before applying any regex
// or "Tokens used:" gets fragmented across color spans and never matches.
const ESC = String.fromCharCode(0x1b);
// Standard CSI: ESC [ <private-prefix?> <params> <intermediate> <final-byte>.
// Including <=>? as optional private-prefix so sequences like \x1b[>4m and
// \x1b[<u (xterm DECSET / mode-toggle variants Claude emits at exit) parse.
const ANSI_RE = new RegExp(`${ESC}\\[[<=>?]?[0-9;]*[ -\\/]*[@-~]`, "g");
const OSC_RE = new RegExp(`${ESC}\\][^${ESC}\\x07]*(?:${ESC}\\\\|\\x07)`, "g");
// Bare 2-char ESC sequences: ESC 7 / ESC 8 (save/restore cursor), ESC =, etc.
// These don't have a [ — they slip past CSI/OSC strippers and leak into out.
const SHORT_ESC_RE = new RegExp(`${ESC}[78=>]`, "g");
// Cursor-forward N columns — Claude Code's TUI uses this to space words
// instead of literal spaces. If we strip it raw the spaces vanish and the
// response collapses to "relojdelsistema". Cap N at 16 so a runaway escape
// doesn't blow up a line; that's far more than any real word gap.
const CUF_RE = new RegExp(`${ESC}\\[(\\d+)C`, "g");

function clean(chunk: string): string {
  return chunk
    .replace(CUF_RE, (_, n: string) => " ".repeat(Math.min(Number(n) || 0, 16)))
    .replace(OSC_RE, "")
    .replace(ANSI_RE, "")
    .replace(SHORT_ESC_RE, "");
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
//
// Filters out meta-formatting titles like "Setup response template format" or
// "Respond with TL;DR" — those are Claude reflecting on our prompt-prefix
// machinery, not the user's actual task. The watch's initial task (set by
// index.ts as the user's voice text) is more useful.
const META_TASK_PATTERNS: RegExp[] = [
  /\bTL;?DR\b/i,
  /\b(respond|reply)\s+(with|to|using)\b/i,
  /\b(setup|set\s+up|create|format)\s+.*\b(response|template|format)\b/i,
  /\bresponse\s+(template|format)\b/i,
  /\b(follow|use)\s+.*\b(response\s+format|template)\b/i,
  /\bfollowups?\s+(format|template|block)\b/i,
  /\b(provide|give)\s+.*(and|with)\s+follow\b/i,
];

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
  // Meta-formatting titles describe the prompt-prefix machinery, not the
  // user's real task. Drop them so the initial task (user's voice text) wins.
  if (last && META_TASK_PATTERNS.some((re) => re.test(last))) return null;
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
  // ─── Round 2: TUI welcome / chrome that survives line-by-line filter ───────
  /^Welcome back\b/i,
  /\bRun \/init\b/i,
  /^What's new$/i,
  /^Added projected/i,
  /Tips for getting started/i,
  /^❯ Try /,
  /^❯ /, // softer catch-all for any quick-start hint
  /'s Organization\b/i,
  /^~\/[\w./-]+$/, // path footer like "~/projects/CCWEAROS/wrapper"
  /^(Reply like this|Responde así)/i, // echoed PROMPT_PREFIX
  /^Opus 4\.\d+ \(.*context\) · Claude (Max|Pro|Free)/i, // model/plan banner
  // ─── Round 3: post-answer chrome (status footer, resume hint, etc.) ───────
  /^[✽✻✶✳✢·]\s/, // any spinner-prefixed sub-line
  /^[✽✻✶✳✢]\s*[A-Z][a-z]+(?:zz)?ing/i, // "✽ Julienning"
  /^Baked\s+for\s+\d+s/i, // "Baked for 2s"
  /^●+\s*\d+%/, // "●●●●41% resets ..."
  /^\d+\s+resets\s/i, // "4 resets may 24 ..."
  /^Resume\s+this\s+session\s+with/i,
  /^claude\s+--resume\b/i,
  /^\d+\s*MCP\s+server/i, // "1 MCP server needs auth"
  /^[ᗧ–]/, // status bar prefix chars
  /^Opus\s+\d.*?·.*?wrapper/i, // "Opus 4.7 (1M context) · wrapper · ..."
  // Anchor on the literal word "wrapper" anywhere on the line, with a status
  // circle later in the row — Claude Code's status bar pastes the current
  // task title into this same row, so we can't anchor on a specific verb.
  /\bwrapper\b.*[○●]/, // task-title + status carousel
  // Stray 2-3 char fragments left over from spinner letter-by-letter renders
  // ("o n", "k g", "✻u" — once the spinner glyph is stripped). Anchored
  // lengths only, so a real two-letter answer like "OK" still survives because
  // it'll be ≥3 chars after the trim or part of a longer line.
  /^[A-Za-z]{1,2}\s+[A-Za-z]{1,2}$/, // "o n", "ng oo"
  /^[A-Za-z]\s*[…⋯·.]+$/, // "i …", "o ·"
  /^[A-Za-z]{2,12}\s+\d{1,3}$/, // "Roostin 7" partial-verb + counter
  /·\s*out\s+\d+/i, // "· out 53" output-token counter
  /^[\d\s]+·\s*out\s+\d+/, // "8 4 3 · out 53"
  /^[\d\s.]+$/, // pure digits + spaces (counter remnants)
];

// ─── Marker-based response slicing ────────────────────────────────────────────
// The daemon appends PROMPT_END_MARKER to the end of every wrapped voice
// prompt before piping it into Claude's TUI. The TUI echoes the marker as the
// trailing line of the input area, so the parser can slice on the LAST
// occurrence to discard ALL pre-response chrome (welcome banner, prompt
// prefix, user-text echo). Fallback: legacy line-by-line filter when the
// marker is absent (Claude crashed before echoing, cold start, etc.).
//
// Plain ASCII. We tried zero-width joiner flanks but Claude Code's TUI input
// editor strips the leading joiner, breaking lastIndexOf. The bare ASCII
// token is astronomically unlikely to appear in any natural Claude response.
export const PROMPT_END_MARKER = "__CCWEAROS_PROMPT_END__";

export function extractResponseAfterMarker(
  buffer: string,
  marker: string = PROMPT_END_MARKER,
): string | null {
  const idx = buffer.lastIndexOf(marker);
  if (idx < 0) return null;
  return buffer.slice(idx + marker.length);
}

// Normalize for echo comparison: lowercase, strip everything that isn't a
// letter or digit. So "Qué hora es" and "qué hora es?" both collapse to
// "quéhoraes" — coincidental punctuation/whitespace differences don't matter.
function normalizeEcho(s: string): string {
  return s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
}

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

export function extractResponseLines(
  buffer: string,
  opts: { userEcho?: string } = {},
): string {
  // Marker slice first: when the daemon appended PROMPT_END_MARKER to the
  // wrapped prompt, everything before the LAST occurrence is TUI chrome +
  // user-input echo and can be thrown away wholesale.
  const sliced = extractResponseAfterMarker(buffer);
  const source = sliced ?? buffer;

  const text = clean(source);
  const out: string[] = [];
  // Split on either \n or \r — Claude Code's TUI uses bare \r to overwrite
  // status-bar lines, and if we only split on \n the whole status carousel
  // collapses into one long mega-line that no NOISE_PATTERN can match.
  for (const rawLine of text.split(/[\r\n]+/)) {
    const line = rawLine.trim();
    if (line.length < 2) continue;
    if (NOISE_PATTERNS.some((re) => re.test(line))) continue;
    const flattened = flattenTableRow(line);
    const cleaned = cleanForWatch(flattened);
    if (cleaned.length < 2) continue;
    out.push(cleaned);
  }

  // Drop user-text echo as the first non-empty line — ONLY when the marker
  // hit (so the fallback path never eats a Claude answer that coincidentally
  // shares a word with the prompt).
  if (sliced !== null && opts.userEcho && out.length > 0) {
    const echo = normalizeEcho(opts.userEcho);
    if (echo.length > 0 && normalizeEcho(out[0]!) === echo) {
      out.shift();
    }
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

// ─── Tool invocation tracking ────────────────────────────────────────────────
// Claude Code TUI emits "⏺ ToolName(args)" lines when invoking tools. After
// ANSI strip, these survive in the cleaned chunk text. We surface them so the
// watch can classify the run (action vs info) and show real progress copy
// ("Editing parser.ts") instead of the whimsical generic verb.

export interface ToolEvent {
  tool: string; // "Bash" | "Edit" | "Read" | "Write" | "WebFetch" | "WebSearch" | "Grep" | "Glob" | "Task" | string
  arg: string | null; // first-line argument summary, capped at 60 chars
  ts: number; // unix epoch ms when observed
}

// "⏺ Bash(for dir in ...)" / "⏺ Web Search(query)" / "⏺ Edit(path)".
// The tool name allows a single internal space (e.g. "Web Search") which
// Claude renders for compound tool names.
const TOOL_LINE_RE =
  /⏺\s+([A-Z][A-Za-z]+(?:\s[A-Z][a-z]+)?)\s*\(([^)\n]{0,200})/g;

export function extractToolEvents(chunk: string): ToolEvent[] {
  const text = clean(chunk);
  const out: ToolEvent[] = [];
  const fresh = new RegExp(TOOL_LINE_RE.source, TOOL_LINE_RE.flags);
  let m: RegExpExecArray | null;
  const now = Date.now();
  while ((m = fresh.exec(text)) !== null) {
    const tool = m[1]?.trim();
    if (!tool) continue;
    const rawArg = m[2]?.trim() ?? "";
    const arg = rawArg.length > 0 ? rawArg.slice(0, 60) : null;
    // Dedupe consecutive identical (tool, arg) — the TUI repeats on redraws.
    const prev = out[out.length - 1];
    if (prev && prev.tool === tool && prev.arg === arg) continue;
    out.push({ tool, arg, ts: now });
  }
  return out;
}

export function extractLatestToolEvent(chunk: string): ToolEvent | null {
  const events = extractToolEvents(chunk);
  return events.length > 0 ? (events[events.length - 1] ?? null) : null;
}

// Extracts a TL;DR line written by Claude in response to the daemon's prompt
// prefix. Matches Markdown variants: "**TL;DR:** ...", "TL;DR: ...",
// "*TL;DR* ...", with or without the trailing colon, case-insensitive,
// possibly leading whitespace. Returns the captured text trimmed to 120 chars.
// Allow a leading non-letter prefix (Claude Code TUI uses "⏺ " before its
// own response lines, and emoji/quote-style intros are common).
const TLDR_RE = /^[^A-Za-z\n]*\*{0,2}TL;?DR:?\*{0,2}\s*[:—-]?\s*(.+?)\s*$/im;

export function extractTldr(buffer: string): string | null {
  const text = clean(buffer);
  const m = text.match(TLDR_RE);
  const raw = m?.[1]?.trim();
  if (!raw || raw.length === 0) return null;
  return raw.length > 120 ? raw.slice(0, 120).trimEnd() + "…" : raw;
}

// ─── Followups: extracted from a "Followups:"/"Sugerencias:" bullet block ────
// The wrapper asks Claude (via the prompt prefix) to end every response with
// 2-3 short suggestions for what to ask/do next. The watch renders them as
// tappable chips on Page 4. Pattern is loose: header line (case-insensitive,
// bilingual) followed by bullet/numbered list items. Stops at first 3 hits or
// at the first blank line after seeing at least one bullet.
const FOLLOWUPS_HEADER_RE =
  /(?:^|\n)[^\S\n]*\*{0,2}(?:Followups?|Sugerencias|Sigamos|What\s+next)[\s*:]*(?:\n|$)/i;
const BULLET_LINE_RE = /^[^\S\n]*(?:[-*•·]|\d+[.)])\s+(.+?)\s*$/gm;

export function extractFollowups(buffer: string): string[] {
  const text = clean(buffer);
  const headerMatch = FOLLOWUPS_HEADER_RE.exec(text);
  if (!headerMatch || headerMatch.index === undefined) return [];
  const after = text.slice(headerMatch.index + headerMatch[0].length);
  const out: string[] = [];
  const seen = new Set<string>();
  const fresh = new RegExp(BULLET_LINE_RE.source, BULLET_LINE_RE.flags);
  let m: RegExpExecArray | null;
  let seenBullet = false;
  while ((m = fresh.exec(after)) !== null && out.length < 3) {
    const raw = m[1]?.trim();
    if (!raw) continue;
    // Strip surrounding emphasis (`**item**`, `*item*`) and stray quotes.
    const item = raw
      .replace(/^\*+/, "")
      .replace(/\*+$/, "")
      .replace(/^["'`]+|["'`]+$/g, "")
      .trim();
    if (!item) continue;
    // Dedupe (case-insensitive) — Claude sometimes repeats a suggestion.
    const key = item.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item.length > 40 ? item.slice(0, 40).trimEnd() + "…" : item);
    seenBullet = true;
    // Bail on the first blank-line gap after seeing at least one bullet — keeps
    // the parser from sweeping into a later unrelated bullet list (footer).
    const next = after.slice(fresh.lastIndex, fresh.lastIndex + 200);
    if (seenBullet && /^\s*\n\s*\n/.test(next)) break;
  }
  return out;
}
