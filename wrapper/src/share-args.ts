// Pure argv parsing for `scripts/share.ts` (the `cc` alias).
//
// Living in src/ so vitest can import it without dragging in the script's
// Firebase / pty side effects.

export interface ShareArgs {
  // Set when `--resume <id>` (or `--resume=<id>`) is present. Triggers the
  // takeover flow: share.ts will spawn `claude --resume <id> --permission-mode dontAsk`
  // so the watch becomes the sole permission gate.
  resumeSessionId: string | null;
}

// Claude session UUIDs look like 8-4-4-4-12 hex groups, but older Claude Code
// versions produced shorter ids and dashes are sometimes substituted. Keep
// the regex permissive on length but strict on charset so we never end up
// shell-injecting arbitrary argv content into the spawned `claude`.
//
// Exported so the daemon's claim-handler (Sprint 4n) can validate watch-
// sourced sessionIds with the SAME regex used by the takeover CLI parser.
// Centralizing the rule means a future change here propagates to both.
export const SESSION_ID_RE = /^[0-9a-fA-F-]{8,64}$/;

export function parseShareArgs(argv: readonly string[]): ShareArgs {
  let resumeSessionId: string | null = null;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--resume") {
      const v = argv[i + 1];
      if (typeof v !== "string" || v.length === 0) {
        throw new Error("--resume requires a sessionId argument");
      }
      if (!SESSION_ID_RE.test(v)) {
        throw new Error(`--resume sessionId malformed: ${v}`);
      }
      resumeSessionId = v;
      i++;
    } else if (a !== undefined && a.startsWith("--resume=")) {
      const v = a.slice("--resume=".length);
      if (!SESSION_ID_RE.test(v)) {
        throw new Error(`--resume sessionId malformed: ${v}`);
      }
      resumeSessionId = v;
    }
  }
  return { resumeSessionId };
}
