import "dotenv/config";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing required env var: ${name}`);
  return v;
}

export const config = {
  firebaseDbUrl: required("FIREBASE_DB_URL"),
  firebaseAdminKeyPath: resolve(
    process.cwd(),
    process.env["FIREBASE_ADMIN_KEY_PATH"] ??
      "./secrets/firebase-admin-key.json",
  ),
  claudeCliCommand: process.env["CLAUDE_CLI_COMMAND"] ?? "claude",
  metricsDebounceMs: Number(process.env["METRICS_DEBOUNCE_MS"] ?? 5000),
  commandMaxAgeSeconds: Number(process.env["COMMAND_MAX_AGE_SECONDS"] ?? 60),
} as const;

export function loadServiceAccount(): unknown {
  const raw = readFileSync(config.firebaseAdminKeyPath, "utf8");
  return JSON.parse(raw);
}
