// Scans Claude Code's on-disk session storage to give the watch a glanceable
// list of "what sessions exist on this Mac". Two sources of truth:
//   1. ~/.claude/sessions/<pid>.json — one file per running Claude process,
//      tracks { pid, sessionId, cwd, status }. Existence here means the PID
//      was alive at last check; we re-verify with `process.kill(pid, 0)`.
//   2. ~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl — turn-by-turn
//      transcript. mtime tells us recency.
//
// We merge: every session in (2), with `active=true` when its sessionId
// appears in (1) AND the PID is alive. `shared=false` is filled by the
// caller from the live /sharedSession value.
//
// Output is published to /recentSessions every SCAN_INTERVAL_MS, capped at
// SCAN_LIMIT entries (most-recent by mtime first).

import {
  existsSync,
  openSync,
  readFileSync,
  readSync,
  closeSync,
  readdirSync,
  statSync,
} from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { setRecentSessions } from "./firebase.js";
import { isPidAlive } from "./pid-utils.js";
import type { RecentSession } from "./types/schema.js";

const SESSIONS_DIR = join(homedir(), ".claude/sessions");
const PROJECTS_DIR = join(homedir(), ".claude/projects");
const SCAN_INTERVAL_MS = 15_000;
const SCAN_LIMIT = 15;
// Only read the tail of large JSONL files when fishing for the last user
// message — older sessions can be 20+ MB and we don't need any of that.
// 256KB comfortably covers ~10-20 turns of Claude streaming + tool I/O
// without keeping multi-MB strings around per scan tick.
const TAIL_BYTES = 256 * 1024;
// JSONL "user" entries that are actually wrappers around local command output
// (eg the output of /exit, /init, custom slash commands) or session-level
// caveats match this pattern and aren't useful as previews — skip and keep
// walking backwards. Matches `<local-command-X>` and `<command-X>` openers.
const SYSTEM_WRAPPER_TAG_RE = /^<(?:local-command-|command-)[a-z-]+>/;
// Preview cap matches what the watch can render without truncation pressure.
const PREVIEW_MAX_CHARS = 60;

interface ActivePidInfo {
  pid: number;
  sessionId: string;
  cwd: string;
}

interface JsonlMeta {
  sessionId: string;
  cwd: string | null;
  lastUserMessage: string | null;
}

function readActivePids(): Map<string, ActivePidInfo> {
  // Map keyed by sessionId so we can O(1) look up "is this session active?".
  const map = new Map<string, ActivePidInfo>();
  if (!existsSync(SESSIONS_DIR)) return map;
  let entries: string[];
  try {
    entries = readdirSync(SESSIONS_DIR);
  } catch {
    return map;
  }
  for (const entry of entries) {
    if (!entry.endsWith(".json")) continue;
    const full = join(SESSIONS_DIR, entry);
    let raw: string;
    try {
      raw = readFileSync(full, "utf8");
    } catch {
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      continue;
    }
    if (typeof parsed !== "object" || parsed === null) continue;
    const obj = parsed as Record<string, unknown>;
    const pid = typeof obj["pid"] === "number" ? obj["pid"] : null;
    const sessionId =
      typeof obj["sessionId"] === "string" ? obj["sessionId"] : null;
    const cwd = typeof obj["cwd"] === "string" ? obj["cwd"] : "";
    if (!pid || !sessionId) continue;
    // Re-verify the PID is alive — stale pid.json files survive crashes.
    if (!isPidAlive(pid)) continue;
    map.set(sessionId, { pid, sessionId, cwd });
  }
  return map;
}

// Read the LAST `lastBytes` of a file into a string. Cheap version of `tail -c`
// that avoids loading 20MB transcripts into memory.
function readFileTail(path: string, lastBytes: number): string {
  const stat = statSync(path);
  const size = stat.size;
  const offset = Math.max(0, size - lastBytes);
  const length = size - offset;
  const buf = Buffer.alloc(length);
  const fd = openSync(path, "r");
  try {
    readSync(fd, buf, 0, length, offset);
  } finally {
    closeSync(fd);
  }
  return buf.toString("utf8");
}

function isLocalCommandWrapper(text: string): boolean {
  return SYSTEM_WRAPPER_TAG_RE.test(text.trimStart());
}

// Pull message.content out of a JSONL "user" turn. content can be a plain
// string OR an array of typed content blocks (text/tool_use/tool_result).
// Returns null for local-command-output wrappers (those aren't useful as
// previews of "what the user said") so the caller keeps walking backwards.
function extractUserText(entry: unknown): string | null {
  if (typeof entry !== "object" || entry === null) return null;
  const obj = entry as Record<string, unknown>;
  if (obj["type"] !== "user") return null;
  const msg = obj["message"];
  if (typeof msg !== "object" || msg === null) return null;
  const content = (msg as Record<string, unknown>)["content"];
  if (typeof content === "string") {
    const trimmed = content.trim();
    if (isLocalCommandWrapper(trimmed)) return null;
    return trimmed;
  }
  if (Array.isArray(content)) {
    for (const block of content) {
      if (typeof block !== "object" || block === null) continue;
      const b = block as Record<string, unknown>;
      if (b["type"] === "text" && typeof b["text"] === "string") {
        const trimmed = (b["text"] as string).trim();
        if (isLocalCommandWrapper(trimmed)) continue;
        return trimmed;
      }
    }
  }
  return null;
}

// Read JSONL tail, parse lines from the end, return the most-recent user
// message text AND the session's cwd if we can extract it. The cwd field is
// embedded in most turns — preferring it over un-sanitizing the directory
// name avoids loss for paths with embedded hyphens.
function readJsonlMeta(path: string, sessionId: string): JsonlMeta {
  const meta: JsonlMeta = { sessionId, cwd: null, lastUserMessage: null };
  let tail: string;
  try {
    tail = readFileTail(path, TAIL_BYTES);
  } catch {
    return meta;
  }
  const lines = tail.split("\n");
  // Walk backwards so we hit the most recent user turn first.
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i];
    if (!line || !line.startsWith("{")) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(line);
    } catch {
      continue;
    }
    if (typeof parsed === "object" && parsed !== null) {
      const obj = parsed as Record<string, unknown>;
      if (!meta.cwd && typeof obj["cwd"] === "string") meta.cwd = obj["cwd"];
      if (!meta.lastUserMessage) {
        const txt = extractUserText(parsed);
        if (txt) {
          meta.lastUserMessage =
            txt.length > PREVIEW_MAX_CHARS
              ? txt.slice(0, PREVIEW_MAX_CHARS - 1).trimEnd() + "…"
              : txt;
        }
      }
    }
    if (meta.cwd && meta.lastUserMessage) break;
  }
  return meta;
}

// Fall-back un-sanitization when the JSONL has no cwd field (rare). Claude
// Code encodes "/a/b/c" as "-a-b-c". This is lossy when the original path
// contained hyphens. Acceptable for V1; the JSONL cwd field handles correct
// cases.
function unsanitizeCwd(dirName: string): string {
  if (!dirName.startsWith("-")) return dirName;
  return "/" + dirName.slice(1).replace(/-/g, "/");
}

export function scanRecentSessions(
  limit: number = SCAN_LIMIT,
): RecentSession[] {
  if (!existsSync(PROJECTS_DIR)) return [];
  const activeMap = readActivePids();
  const candidates: { path: string; mtime: number; dirName: string }[] = [];

  let projDirs: string[];
  try {
    projDirs = readdirSync(PROJECTS_DIR);
  } catch {
    return [];
  }
  for (const projDir of projDirs) {
    const fullProjDir = join(PROJECTS_DIR, projDir);
    let entries: string[];
    try {
      entries = readdirSync(fullProjDir);
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!entry.endsWith(".jsonl")) continue;
      const fp = join(fullProjDir, entry);
      let mtime: number;
      try {
        mtime = statSync(fp).mtimeMs;
      } catch {
        continue;
      }
      candidates.push({ path: fp, mtime, dirName: projDir });
    }
  }

  candidates.sort((a, b) => b.mtime - a.mtime);
  const top = candidates.slice(0, limit);

  return top.map((c) => {
    const sessionId = basename(c.path, ".jsonl");
    const meta = readJsonlMeta(c.path, sessionId);
    // cwd MUST be the path under which Claude Code stores this session's
    // .jsonl — that's where `claude --resume <id>` looks. Claude Code uses
    // the cwd of the FIRST turn as the storage path; if the user later
    // `cd`-s mid-session the per-turn meta.cwd diverges from the storage
    // dir, and a resume at meta.cwd would 404 ("No conversation found").
    // Always use the dir-derived cwd for resumability. Bug observed
    // 2026-05-20 with sessionId 926bea49 stored under ~ but with
    // meta.cwd = ~/projects/isthmus-norte due to a mid-session cd.
    const cwd = unsanitizeCwd(c.dirName);
    // projectName is purely visual — prefer the last per-turn cwd's
    // basename ("isthmus-norte" reads nicer than "luismiguelcaamano"
    // for a session that ended in a project subdir). Falls back to the
    // resume cwd's basename when meta has no cwd field.
    const projectName = basename(meta.cwd ?? cwd);
    return {
      sessionId,
      cwd,
      projectName,
      mtime: Math.round(c.mtime),
      active: activeMap.has(sessionId),
      shared: false, // filled by the caller once it knows /sharedSession
      lastUserMessage: meta.lastUserMessage,
    };
  });
}

export function startSessionScanner(
  opts: {
    intervalMs?: number;
    getSharedSessionId?: () => string | null;
  } = {},
): () => void {
  const interval = opts.intervalMs ?? SCAN_INTERVAL_MS;
  const tick = (): void => {
    try {
      const list = scanRecentSessions();
      const sharedId = opts.getSharedSessionId?.() ?? null;
      if (sharedId) {
        for (const s of list) {
          if (s.sessionId === sharedId) s.shared = true;
        }
      }
      void setRecentSessions(list);
    } catch (e) {
      console.error("[sessions-scanner] scan failed:", (e as Error).message);
    }
  };
  tick();
  const handle: NodeJS.Timeout = setInterval(tick, interval);
  return () => clearInterval(handle);
}

// Exported for unit testing.
export const __internal = {
  isPidAlive,
  readFileTail,
  extractUserText,
  unsanitizeCwd,
  readJsonlMeta,
  readActivePids,
};
