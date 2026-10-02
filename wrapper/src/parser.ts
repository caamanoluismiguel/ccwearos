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
// Charset designators: ESC ( B, ESC ) 0, ESC % G … (3 chars). Claude Code
// emits `ESC(B` + SI (\x0f) when it tears the TUI down; left alone they leak
// into the response as "(B".
const CHARSET_ESC_RE = new RegExp(`${ESC}[()*+\\-./%#][\\x20-\\x7e]`, "g");
// Any other 2-char ESC sequence: ESC 7 / ESC 8 (save/restore cursor), ESC =,
// ESC M, ESC c … — plus a lone ESC. Runs after CSI/OSC/charset stripping.
const SHORT_ESC_RE = new RegExp(`${ESC}[\\x20-\\x7e]?`, "g");
// C0 controls except \t \n \r (callers split lines on \r and \n). SI/SO,
// BEL, backspace and friends are never response text.
const C0_RE = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g;
// Cursor-forward N columns — Claude Code's TUI uses this to space words
// instead of literal spaces. If we strip it raw the spaces vanish and the
// response collapses to "relojdelsistema". An omitted N means 1 (`ESC[C`) —
// that form used to fall through to ANSI_RE and lose the space. Cap N at 16
// so a runaway escape doesn't blow up a line.
const CUF_RE = new RegExp(`${ESC}\\[(\\d*)C`, "g");

function clean(chunk: string): string {
  return chunk
    .replace(CUF_RE, (_, n: string) =>
      " ".repeat(Math.min(n === "" ? 1 : Number(n) || 0, 16)),
    )
    .replace(OSC_RE, "")
    .replace(ANSI_RE, "")
    .replace(CHARSET_ESC_RE, "")
    .replace(SHORT_ESC_RE, "")
    .replace(C0_RE, "");
}

// Public alias for callers outside the parser (and tests): terminal output →
// plain text, cursor-forward restored as spaces, every escape and stray C0
// control removed. \r and \n are kept as line separators.
export const cleanTerminalText = clean;

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

// ─── Permission prompts ──────────────────────────────────────────────────────
// Claude Code 2.x draws a permission box. After ANSI strip it looks like this
// (rows are separated by bare \r + cursor moves, NOT \n):
//
//   ────────────────────────────────────────────  ← rule (box top)
//    Bash command                                  ← header
//      git push --force origin main                ← body: the target, hard-
//      Force-push the rebased branch               ←   or word-wrapped; Claude's
//                                                      description is dimmed
//    Do you want to proceed?                       ← question
//    ❯ 1. Yes                                      ← options (dropped)
//      2. No
//
// "Tool use" boxes put `Name(args)` on the first body line (Web Search, MCP).
// The watch must show the FULL target — the user approves from their wrist.

// Legacy prompts (pre-box formats, `[y/n]` CLIs). Only consulted when no
// Claude Code box is found.
const PERMISSION_PATTERNS: RegExp[] = [
  /Claude wants to .+/i,
  /\[y\/n\]/i,
  /^\s*allow\??\s*$/i,
  /do you (?:want to )?(?:allow|continue|approve)/i,
  /^\s*\(?\s*y\s*\/\s*n\s*\)?\s*$/i,
  /Allow\s+.+\?/i,
];
// A bare "❯ 1. Yes" option with no readable box around it. Still a permission
// signal, but "❯ 1. Yes" must never be forwarded as if it described the action.
const BARE_YES_OPTION_RE = /^\s*❯?\s*\d?\.?\s*Yes\s*$/i;
export const PERMISSION_DETAILS_UNAVAILABLE =
  "Permission requested (details not visible, check the terminal)";
export const PERMISSION_PROMPT_MAX_CHARS = 1500;

const PERMISSION_QUESTION_RE =
  /^\s*(?:Do you want to\s.*\?|Would you like to proceed\?)\s*$/i;
const BOX_RULE_RE = /^\s*─{20,}\s*$/;
// "❯ 1. Yes", "1. Yes, and auto-accept edits" (plan mode), or with the dot
// replaced by a cursor move on partial redraws ("1  Yes").
const YES_OPTION_RE = /^\s*(?:❯\s*)?1\.?\s+Yes\b/;
const BOX_DRAWING_RE = /[│┃║╭╮╰╯┌┐└┘├┤┬┴┼]/g;
// How far above the question we look for the box top. Write/Edit previews
// can be long; Bash bodies are a handful of lines.
const BOX_SCAN_LINES = 400;
// The box body wraps 3 columns short of the rule (80-col rule → 77).
const BOX_RIGHT_MARGIN = 3;

interface ScreenLine {
  text: string; // ANSI-stripped, CUF expanded to spaces
  dim: boolean; // first visible glyph drawn in a grey/dim colour
}

// One escape sequence or one visible character at a time. SGR is captured so
// we can tell Claude's dimmed description from the (default-colour) target.
const TOKEN_RE = new RegExp(
  `${ESC}\\[([0-9;]*)m|${ESC}\\[[<=>?]?[0-9;]*[ -\\/]*[@-~]|${ESC}\\][^${ESC}\\x07]*(?:${ESC}\\\\|\\x07)|${ESC}[()*+\\-./%#][\\x20-\\x7e]|${ESC}[\\x20-\\x7e]?|([^${ESC}])`,
  "g",
);

function isGreyRgb(r: number, g: number, b: number): boolean {
  return r === g && g === b && r >= 60 && r <= 200;
}

// Splits on [\r\n]+ and tracks SGR state across rows (the renderer doesn't
// always re-emit a colour that is already active).
function toScreenLines(raw: string): ScreenLine[] {
  const out: ScreenLine[] = [];
  let grey = false;
  let faint = false;
  for (const rawLine of raw.split(/[\r\n]+/)) {
    let dim: boolean | null = null;
    const re = new RegExp(TOKEN_RE.source, TOKEN_RE.flags);
    let m: RegExpExecArray | null;
    while ((m = re.exec(rawLine)) !== null) {
      const sgr = m[1];
      const ch = m[2];
      if (sgr !== undefined) {
        const p = sgr === "" ? [0] : sgr.split(";").map((x) => Number(x) || 0);
        for (let i = 0; i < p.length; i++) {
          const v = p[i] ?? 0;
          if (v === 0) {
            grey = false;
            faint = false;
          } else if (v === 2) faint = true;
          else if (v === 22) faint = false;
          else if (v === 39 || (v >= 30 && v <= 37) || (v >= 91 && v <= 97)) {
            grey = false;
          } else if (v === 90) grey = true;
          else if (v === 38 && p[i + 1] === 2) {
            grey = isGreyRgb(p[i + 2] ?? -1, p[i + 3] ?? -2, p[i + 4] ?? -3);
            i += 4;
          } else if (v === 38 && p[i + 1] === 5) {
            const n = p[i + 2] ?? 0;
            grey = n === 8 || (n >= 240 && n <= 250);
            i += 2;
          } else if (v === 48) {
            i += p[i + 1] === 2 ? 4 : 2; // skip background colour args
          }
        }
      } else if (ch !== undefined && dim === null && ch.trim() !== "") {
        dim = grey || faint;
      }
    }
    out.push({ text: clean(rawLine), dim: dim ?? false });
  }
  return out;
}

type BoxKind = "command" | "path" | "fetch" | "toolUse";

function classifyHeader(header: string): { tool: string; kind: BoxKind } {
  const h = header.trim();
  if (/^bash command$/i.test(h)) return { tool: "Bash", kind: "command" };
  if (/^(?:edit|update) file$/i.test(h)) return { tool: "Edit", kind: "path" };
  if (/^(?:create|write) file$/i.test(h)) return { tool: "Write", kind: "path" };
  if (/^read file$/i.test(h)) return { tool: "Read", kind: "path" };
  if (/^fetch$/i.test(h)) return { tool: "Fetch", kind: "fetch" };
  if (/^tool use$/i.test(h)) return { tool: "Tool", kind: "toolUse" };
  return { tool: h.replace(/\s+(?:command|file)$/i, ""), kind: "command" };
}

const indentOf = (s: string): number => s.length - s.trimStart().length;

// Re-joins rows the TUI wrapped at the box width, returning logical lines.
// The renderer moves a word that fits to the next row (word wrap: join with
// " ") and only splits a token longer than the row in place (hard wrap: join
// with ""). Dim/non-dim changes are always real breaks (target → Claude's
// description). Relative indentation of real lines is preserved so
// multi-line scripts stay readable.
function unwrapLines(rows: ScreenLine[], width: number): string[] {
  const lines = rows
    .map((r) => ({
      text: r.text.replace(BOX_DRAWING_RE, " ").replace(/\s+$/, ""),
      dim: r.dim,
    }))
    .filter((r) => r.text.trim().length > 0);
  if (lines.length === 0) return [];
  const w = Math.max(width, ...lines.map((l) => l.text.length));
  // The target's first row sets the left edge; deeper rows keep their extra
  // indent, dimmed description rows are trimmed.
  const base = indentOf(lines[0]?.text ?? "");
  const out: string[] = [];
  let prev: { text: string; dim: boolean } | null = null;
  for (const line of lines) {
    const text = line.text.trim();
    const last = out.length - 1;
    if (prev !== null && last >= 0 && prev.dim === line.dim) {
      const firstTok = text.split(/\s/)[0] ?? "";
      const prevTok = prev.text.trim().split(/\s/).pop() ?? "";
      const room = w - indentOf(line.text);
      if (prev.text.length >= w && prevTok.length + firstTok.length > room) {
        out[last] += text; // token split mid-way
        prev = line;
        continue;
      }
      if (prev.text.length + 1 + firstTok.length > w) {
        out[last] += " " + text; // next word didn't fit
        prev = line;
        continue;
      }
    }
    out.push(
      line.dim ? text : line.text.slice(Math.min(base, indentOf(line.text))),
    );
    prev = line;
  }
  return out;
}

// Never truncate silently, and keep the END visible: the dangerous part of a
// chained command (`… && rm -rf ~`) is usually at the tail.
function capMiddle(s: string, max: number = PERMISSION_PROMPT_MAX_CHARS): string {
  if (s.length <= max) return s;
  const sep = " … ";
  const budget = max - sep.length;
  const head = Math.floor(budget / 2);
  return s.slice(0, head) + sep + s.slice(s.length - (budget - head));
}

function formatBox(
  header: string,
  body: ScreenLine[],
  question: string,
  width: number,
): string {
  const { tool, kind } = classifyHeader(header);
  const logical = unwrapLines(body, width);

  if (kind === "path") {
    // First body row is the path; the rest is a diff/content preview.
    const fromQuestion = question.match(
      /(?:edit to|create|overwrite|write to|read)\s+(.+?)\?\s*$/i,
    )?.[1];
    const target = logical[0]?.trim() ?? fromQuestion ?? "";
    return target ? `${tool}: ${target}` : `${tool}: ${question}`;
  }

  if (kind === "fetch") {
    const joined = logical.join(" ");
    const url =
      joined.match(/url:\s*"([^"]+)"/)?.[1] ?? joined.match(/https?:\/\/\S+/)?.[0];
    const prompt = joined.match(/prompt:\s*"([^"]*)"?/)?.[1]?.trim();
    if (url) return prompt ? `Fetch: ${url}\n${prompt}` : `Fetch: ${url}`;
    return [`Fetch: ${logical[0] ?? ""}`, ...logical.slice(1)].join("\n");
  }

  if (kind === "toolUse") {
    const first = logical[0]?.trim() ?? "";
    // "Claude wants to search the web for: …" just repeats the arguments.
    const rest = logical
      .slice(1)
      .filter((l) => !/^\s*Claude wants to (?:search|fetch)\b/i.test(l));
    const m = first.match(/^(.+?)\((.*)\)(\s*\(MCP\))?$/);
    if (m?.[1]) {
      const label = m[3] ? `${m[1].trim()} (MCP)` : m[1].trim();
      return [`${label}: ${(m[2] ?? "").trim()}`, ...rest].join("\n");
    }
    return [`${tool}: ${first}`, ...rest].join("\n");
  }

  // command: target line(s) first, then Claude's description / warnings.
  if (logical.length === 0) return `${tool}: ${question}`;
  const [first, ...rest] = logical;
  return [`${tool}: ${first}`, ...rest].join("\n");
}

function findLast(
  lines: ScreenLine[],
  re: RegExp,
  from: number,
  to: number,
): number {
  for (let i = from; i >= Math.max(0, to); i--) {
    if (re.test(lines[i]?.text ?? "")) return i;
  }
  return -1;
}

function extractPermissionBox(lines: ScreenLine[]): string | null {
  const q = findLast(lines, PERMISSION_QUESTION_RE, lines.length - 1, 0);
  if (q < 0) return null;
  const question = (lines[q]?.text ?? "").trim();
  // Every Claude Code permission box offers "1. Yes…" right under the
  // question. Requiring it keeps prose like "Do you want to deploy now?" in
  // Claude's answer (below an input-box rule) from posing as a prompt.
  const hasOptions = lines
    .slice(q + 1, q + 4)
    .some((l) => YES_OPTION_RE.test(l.text));
  if (!hasOptions) return null;
  const unavailable = `${question} — ${PERMISSION_DETAILS_UNAVAILABLE}`;
  const rule = findLast(lines, BOX_RULE_RE, q - 1, q - BOX_SCAN_LINES);
  if (rule < 0) return unavailable;

  // A question between the rule and q means the latest draw of the box lost
  // its top (scrolled off / partial redraw). Fall back to the earlier full
  // draw ONLY if the newest draw provably shows the same target.
  let end = q;
  const inner = findLast(lines, PERMISSION_QUESTION_RE, q - 1, rule + 1);
  if (inner >= 0) end = inner;

  let h = rule + 1;
  while (h < end && (lines[h]?.text ?? "").trim().length === 0) h++;
  if (h >= end) return unavailable;
  const header = (lines[h]?.text ?? "").replace(BOX_DRAWING_RE, " ").trim();
  // Headers are short titles ("Bash command", "Tool use"). Anything else
  // means the rule we found belongs to other chrome (input box, status bar).
  if (header.length > 40 || /^[❯⏺⎿>]/.test(header)) return unavailable;
  const body = lines.slice(h + 1, end);
  const width = (lines[rule]?.text.trim().length ?? 80) - BOX_RIGHT_MARGIN;

  if (end !== q) {
    const firstRow = body.find((l) => l.text.trim().length > 0)?.text.trim();
    const redraw = lines.slice(end + 1, q).map((l) => l.text.trim());
    if (!firstRow || !redraw.includes(firstRow)) return unavailable;
  }
  return capMiddle(formatBox(header, body, question, width));
}

// Returns a human-readable description of the pending permission request —
// "Bash: <full command>\n<Claude's description>", "Edit: <path>",
// "Fetch: <url>\n<prompt>", "Web Search: \"<query>\"" — or null when the text
// holds no permission prompt. When the newest box is only partly visible it
// returns a string containing PERMISSION_DETAILS_UNAVAILABLE instead of a
// misleading fragment like "❯ 1. Yes". Output is capped at
// PERMISSION_PROMPT_MAX_CHARS with the middle elided (head … tail).
// Pass as much recent output as possible: a box split across chunks can only
// be read once both halves are in the input.
export function extractPermissionPrompt(chunk: string): string | null {
  const lines = toScreenLines(chunk);

  const box = extractPermissionBox(lines);
  if (box !== null) return box;

  // The workspace-trust dialog is not a tool permission: answering it from
  // the wrist would trust a whole folder. Its "Yes, I trust this folder"
  // option must not reach the loose legacy matchers below.
  if (isTrustDialog(lines)) return null;
  // Any other select-dialog footer without a readable permission box (a
  // reworded trust dialog, an onboarding/MCP prompt) is NOT a permission
  // prompt: its "1. Yes, …" option would otherwise hit the loose matchers
  // below and the watch's Allow ("1\r") would answer the dialog. The
  // blocking-dialog path reports it as kind "other" instead.
  if (hasDialogFooter(lines)) return null;

  for (const re of PERMISSION_PATTERNS) {
    const line = lines.find((l) => re.test(l.text));
    const trimmed = line?.text.trim();
    if (trimmed) return capMiddle(trimmed);
  }
  if (lines.some((l) => BARE_YES_OPTION_RE.test(l.text))) {
    return PERMISSION_DETAILS_UNAVAILABLE;
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
  // ─── Round 4: dialog chrome + prompt echo (2026-10-01 trust-dialog leak) ──
  /^[\s─━│┃║╭╮╰╯┌┐└┘├┤┬┴┼╴╵╶╷]+$/, // box borders, incl. vertical-only rows
  /Enter\s*to\s*confirm/i,
  /Esc\s*to\s*(?:cancel|exit|interrupt)/i,
  // Select-dialog options ("2. No, exit", "1. Yes, I trust this folder",
  // "1. Yes, and don't ask again"). Bare prose like "1. Yes, you can" stays.
  /^\d+\.\s+(?:Yes|No)(?:,\s*(?:and\b|exit\b|I\s*trust\b|allow\b|don't\s+ask\b|tell\s+Claude\b).*)?$/i,
  /^(?:Yes|No),\s*(?:and\b|exit\b|I\s*trust\b|allow\b|don't\s+ask\b|tell\s+Claude\b)/i,
  // Echoed prompt prefix (buildPromptPrefix in index.ts) + the end marker,
  // when the marker slice missed (prompt never submitted, run crashed).
  /^(?:>\s*)?(?:Contexto:\s*estás\s*corriendo|Context:\s*you\s*are\s*running)/i,
  /__CCWEAROS_PROMPT_END__/,
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
// Claude's permission box often arrives split across several pty chunks, so
// extracting from one chunk at a time mostly yields the "details not visible"
// marker. The tracker keeps a rolling window of recent output and emits each
// distinct prompt once; as more of a box arrives the emitted text can be
// refined (partial -> full), and the newest box in the window wins. Call
// reset() when the prompt is answered (watch tap or terminal keypress) so the
// answered box isn't picked up again.
export class PermissionPromptTracker {
  private buf = "";
  private last: string | null = null;

  constructor(private readonly maxBuffer: number = 16_384) {}

  get lastEmitted(): string | null {
    return this.last;
  }

  feed(chunk: string): string | null {
    this.buf = (this.buf + chunk).slice(-this.maxBuffer);
    const prompt = extractPermissionPrompt(this.buf);
    if (!prompt || prompt === this.last) return null;
    this.last = prompt;
    return prompt;
  }

  reset(): void {
    this.buf = "";
    this.last = null;
  }
}

// ─── Blocking dialogs ────────────────────────────────────────────────────────
// Claude Code TUI dialogs that are NOT tool permission prompts and must never
// be answered remotely: workspace trust ("Quick safety check … Yes, I trust
// this folder"), login/auth failures ("Invalid API key · Please run /login",
// "OAuth token has expired"), and any other "Enter to confirm · Esc to cancel"
// select box. A voice run that hits one can't make progress on its own.
//
// Matching is whitespace-insensitive (squash) so it still works when the TUI's
// cursor-forward spacing was lost somewhere upstream ("Yes,Itrustthisfolder").

export type BlockingDialogKind = "trust" | "auth" | "other";

export interface BlockingDialog {
  kind: BlockingDialogKind;
  // trust: the folder path when visible ("" otherwise); auth: the error line;
  // other: the dialog's title line.
  detail: string;
}

const squash = (s: string): string => s.toLowerCase().replace(/\s+/g, "");
const TRUST_MARKERS = [
  "doyoutrustthefilesinthisfolder",
  "quicksafetycheck",
  "itrustthisfolder",
];
// Select-dialog chrome. Required next to a trust marker so a re-rendered
// conversation (`--continue`) that merely MENTIONS the dialog doesn't count.
const DIALOG_CHROME = ["entertoconfirm", "esctocancel", "esctoexit"];
const AUTH_PREFIXES = ["invalidapikey", "pleaserun/login"];
const AUTH_ANYWHERE = ["oauthtokenhasexpired", "pleaserun/login"];
const LEADING_MARKS_RE = /^[\s⎿⏺●>│┃║]+/;

// A footer ROW of a Claude Code select dialog ("Enter to confirm · Esc to
// cancel", or a permission box's "Esc to cancel · Tab to amend"). Anchored at
// the start of the row so prose like "press Esc to cancel" doesn't count.
const DIALOG_FOOTER_PREFIXES = ["entertoconfirm", "esctocancel", "esctoexit"];
function hasDialogFooter(lines: ScreenLine[]): boolean {
  return lines.some((l) => {
    const sq = squash(l.text.replace(BOX_DRAWING_RE, " "));
    return DIALOG_FOOTER_PREFIXES.some((p) => sq.startsWith(p));
  });
}

function isTrustDialog(lines: ScreenLine[]): boolean {
  const sq = lines.map((l) => squash(l.text));
  return (
    sq.some((l) => TRUST_MARKERS.some((m) => l.includes(m))) &&
    sq.some((l) => DIALOG_CHROME.some((m) => l.includes(m)))
  );
}

function trustFolder(lines: ScreenLine[]): string {
  const i = lines.findIndex((l) => /accessingworkspace:?/.test(squash(l.text)));
  if (i >= 0) {
    // Path is either on the same line ("Accessing workspace: /x") or below.
    const same = (lines[i]?.text ?? "").replace(/.*?workspace:?/i, "").trim();
    if (same.startsWith("/") || same.startsWith("~")) return same;
    const next = lines
      .slice(i + 1, i + 4)
      .map((l) => l.text.replace(BOX_DRAWING_RE, " ").trim())
      .find((t) => t.length > 0);
    if (next && (next.startsWith("/") || next.startsWith("~"))) return next;
  }
  return "";
}

function authLine(lines: ScreenLine[]): string | null {
  for (let i = lines.length - 1; i >= 0; i--) {
    const text = (lines[i]?.text ?? "").replace(LEADING_MARKS_RE, "").trim();
    const sq = squash(text);
    if (
      AUTH_PREFIXES.some((p) => sq.startsWith(p)) ||
      (sq.startsWith("apierror") && AUTH_ANYWHERE.some((p) => sq.includes(p))) ||
      AUTH_ANYWHERE.some((p) => sq.includes(p) && sq.length <= p.length + 40)
    ) {
      return text.replace(BOX_DRAWING_RE, " ").trim().slice(0, 200);
    }
  }
  return null;
}

function otherDialogTitle(lines: ScreenLine[]): string | null {
  const confirm = findLast(
    lines,
    /enter\s*to\s*confirm/i,
    lines.length - 1,
    0,
  );
  if (confirm < 0) return null;
  const hasCancel = lines
    .slice(Math.max(0, confirm - 1), confirm + 2)
    .some((l) => /esc\s*to\s*(?:cancel|exit)/i.test(l.text));
  if (!hasCancel) return null;
  // Title = first content row under the box top (or within 12 rows up).
  const rule = findLast(lines, BOX_RULE_RE, confirm - 1, confirm - 12);
  const from = rule >= 0 ? rule + 1 : Math.max(0, confirm - 12);
  for (let i = from; i < confirm; i++) {
    const t = (lines[i]?.text ?? "").replace(BOX_DRAWING_RE, " ").trim();
    if (t.length === 0 || /^❯|^\d+\.\s/.test(t)) continue;
    return t.slice(0, 200);
  }
  return "";
}

// Returns the blocking dialog visible in `buffer` (raw pty output — pass a
// rolling window, see BlockingDialogTracker), or null. Precedence: trust >
// auth > other. A regular tool-permission box is never reported here.
export function extractBlockingDialog(buffer: string): BlockingDialog | null {
  const lines = toScreenLines(buffer);
  if (isTrustDialog(lines)) return { kind: "trust", detail: trustFolder(lines) };
  const auth = authLine(lines);
  if (auth !== null) return { kind: "auth", detail: auth };
  // A readable permission box is the only select box the watch may answer.
  if (extractPermissionBox(lines) !== null) return null;
  const title = otherDialogTitle(lines);
  if (title !== null) return { kind: "other", detail: title };
  // Footer without "Enter to confirm" next to "Esc" (reworded dialog, partial
  // box): extractPermissionPrompt refuses it, so it must surface here.
  if (hasDialogFooter(lines)) return { kind: "other", detail: "" };
  return null;
}

// Same rolling-window approach as PermissionPromptTracker: dialogs arrive
// split across pty chunks. Emits each distinct dialog once.
export class BlockingDialogTracker {
  private buf = "";
  private last: string | null = null;

  constructor(private readonly maxBuffer: number = 16_384) {}

  feed(chunk: string): BlockingDialog | null {
    this.buf = (this.buf + chunk).slice(-this.maxBuffer);
    const d = extractBlockingDialog(this.buf);
    if (!d) return null;
    const key = `${d.kind}\u0000${d.detail}`;
    if (key === this.last) return null;
    this.last = key;
    return d;
  }

  reset(): void {
    this.buf = "";
    this.last = null;
  }
}

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
  // One cell = a dialog/box row ("│ text │"): keep the text, drop borders.
  if (cells.length < 2) return cells[0] ?? line;
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
