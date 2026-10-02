import { mkdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { config as loadDotenv } from "dotenv";

// Resolve everything against the WRAPPER directory, not process.cwd(), so
// scripts invoked from any other directory (notably the `/ccwearos` slash
// command spawned from the user's project dir) still find the .env and the
// firebase-admin key.
const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const WRAPPER_ROOT = resolve(MODULE_DIR, ".."); // src → wrapper

loadDotenv({ path: resolve(WRAPPER_ROOT, ".env") });

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing required env var: ${name}`);
  return v;
}

// ─── Voice cwd ───────────────────────────────────────────────────────────────
// Voice runs (daemon → `claude -p`, src/claude-voice.ts) must NOT run inside
// the wrapper repo: its .claude/settings.local.json pre-approves tools, which
// would let a voice run skip the watch's permission prompts.
//
// A dedicated, empty folder under ~ (not ~ itself) also keeps read-only
// tools "outside the cwd" for almost everything, so they go to the watch
// (voiceToolNeedsWatch in src/voice-run.ts). -p skips the trust dialog.
//
// Continuity note: `claude --continue` resumes the most recent conversation
// OF THE CWD, so changing CCWEAROS_VOICE_CWD starts a fresh voice thread.
// Interactive `npm start` / `cc` keep process.cwd() on purpose.
export const DEFAULT_VOICE_CWD = join(homedir(), ".ccwearos", "voice");

function isDirectory(p: string): boolean {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

// Resolves CCWEAROS_VOICE_CWD. `~` / `~/x` expand to the home directory and
// relative paths resolve against it. A configured path that is missing or not
// a directory falls back to the default (created if needed) with a warning;
// if even that can't be created, the home directory is the last resort.
export function resolveVoiceCwd(
  raw: string | undefined,
  opts: {
    home?: string;
    defaultDir?: string;
    warn?: (msg: string) => void;
  } = {},
): string {
  const home = opts.home ?? homedir();
  const defaultDir = opts.defaultDir ?? DEFAULT_VOICE_CWD;
  const warn = opts.warn ?? ((m: string) => console.warn(m));
  const value = raw?.trim();
  if (value) {
    const expanded =
      value === "~"
        ? home
        : value.startsWith("~/")
          ? join(home, value.slice(2))
          : resolve(home, value);
    if (isDirectory(expanded)) return expanded;
    warn(
      `[ccwearos] CCWEAROS_VOICE_CWD=${value} is not an existing directory; using ${defaultDir}`,
    );
  }
  try {
    mkdirSync(defaultDir, { recursive: true });
  } catch {
    /* checked below */
  }
  if (isDirectory(defaultDir)) return defaultDir;
  warn(`[ccwearos] Could not create ${defaultDir}; voice runs will use ${home}`);
  return home;
}

export const config = {
  firebaseDbUrl: required("FIREBASE_DB_URL"),
  firebaseAdminKeyPath: resolve(
    WRAPPER_ROOT,
    process.env["FIREBASE_ADMIN_KEY_PATH"] ?? "secrets/firebase-admin-key.json",
  ),
  claudeCliCommand: process.env["CLAUDE_CLI_COMMAND"] ?? "claude",
  metricsDebounceMs: Number(process.env["METRICS_DEBOUNCE_MS"] ?? 5000),
  commandMaxAgeSeconds: Number(process.env["COMMAND_MAX_AGE_SECONDS"] ?? 60),
  // Working directory for daemon voice runs — see resolveVoiceCwd above.
  // Resolved lazily so importing config (tests, scripts) has no fs effects.
  get voiceCwd(): string {
    voiceCwdCache ??= resolveVoiceCwd(process.env["CCWEAROS_VOICE_CWD"]);
    return voiceCwdCache;
  },
};

let voiceCwdCache: string | null = null;

export function loadServiceAccount(): unknown {
  const raw = readFileSync(config.firebaseAdminKeyPath, "utf8");
  return JSON.parse(raw);
}
