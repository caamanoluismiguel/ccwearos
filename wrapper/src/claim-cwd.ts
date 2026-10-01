// Resolve a claimed sessionId to its working directory from LOCAL data only.
//
// The watch's /claimRequest carries a `cwd`, but the claim spawns
// `cc --resume <id>` with `--permission-mode default` — trusting a cwd from
// RTDB would let anyone who can write /claimRequest start an unattended
// Claude in an arbitrary directory. Instead we look up the session's own
// transcript (~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl), whose
// lines carry the real `cwd`, and ignore the watch's value entirely.

import { existsSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import { SESSION_ID_RE } from "./share-args.js";
import { __internal as scanner } from "./sessions-scanner.js";

export const DEFAULT_PROJECTS_DIR = join(homedir(), ".claude/projects");

// Returns the session's cwd, or null when the session is unknown, its
// transcript carries no cwd, or that directory no longer exists.
export function resolveSessionCwd(
  sessionId: string,
  projectsDir: string = DEFAULT_PROJECTS_DIR,
): string | null {
  // The regex (hex + dashes only) is also what keeps `${sessionId}.jsonl`
  // from escaping projectsDir.
  if (!SESSION_ID_RE.test(sessionId)) return null;
  let dirs: string[];
  try {
    dirs = readdirSync(projectsDir);
  } catch {
    return null;
  }
  for (const dir of dirs) {
    const file = join(projectsDir, dir, `${sessionId}.jsonl`);
    if (!existsSync(file)) continue;
    const { cwd } = scanner.readJsonlMeta(file, sessionId);
    if (!cwd || !isAbsolute(cwd)) return null;
    try {
      if (!statSync(cwd).isDirectory()) return null;
    } catch {
      return null;
    }
    return cwd;
  }
  return null;
}
