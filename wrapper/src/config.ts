import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
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

export const config = {
  firebaseDbUrl: required("FIREBASE_DB_URL"),
  firebaseAdminKeyPath: resolve(
    WRAPPER_ROOT,
    process.env["FIREBASE_ADMIN_KEY_PATH"] ?? "secrets/firebase-admin-key.json",
  ),
  claudeCliCommand: process.env["CLAUDE_CLI_COMMAND"] ?? "claude",
  metricsDebounceMs: Number(process.env["METRICS_DEBOUNCE_MS"] ?? 5000),
  commandMaxAgeSeconds: Number(process.env["COMMAND_MAX_AGE_SECONDS"] ?? 60),
} as const;

export function loadServiceAccount(): unknown {
  const raw = readFileSync(config.firebaseAdminKeyPath, "utf8");
  return JSON.parse(raw);
}
