// Sprint 4n — tap-to-claim handler. Consumes /claimRequest writes from the
// watch and spawns `cc --resume <sessionId>` in a new Mac Terminal via
// osascript. Mirrors the /ccwearos-takeover slash command flow but starts
// from a watch tap instead of CLI invocation.
//
// Kept separate from index.ts so it can be unit-tested in isolation: the
// daemon wires this into runDaemon, but every Firebase / spawn / config
// dependency is injected so vitest can mock them cleanly.
//
// Failure modes — all of them write to /claimResult so the watch can show
// an actionable banner instead of silently hanging:
//   - sessionId malformed → reject upfront (defense in depth vs the regex)
//   - claim.issuedAt > commandMaxAgeSeconds old → drop silently (stale tap)
//   - concurrent claim in-flight (claimBusy=true) → refuse (single-flight)
//   - another wrapper-pty session alive → refuse (would deadlock at `claude --resume`)
//   - session unknown on this Mac → refuse. The watch's `cwd` is NEVER
//     trusted: the spawned cc runs with --permission-mode dontAsk, so the
//     cwd comes from the session's own local transcript (src/claim-cwd.ts).
//   - osascript non-zero / timeout → bubble up stderr

import { execFile, type ExecFileException } from "node:child_process";
import { SESSION_ID_RE } from "./share-args.js";
import { buildShellCommand, pickLauncher } from "./takeover-utils.js";
import type {
  AuditEntry,
  ClaimResult,
  PendingClaim,
  SharedSessionMeta,
} from "./types/schema.js";

// 30s timeout: same as enable-takeover.ts. First-time invocations trigger
// macOS Automation permission prompt; if the user doesn't dismiss it we
// abort and write a clear error to /claimResult.
const OSASCRIPT_TIMEOUT_MS = 30_000;

export interface SpawnResult {
  status: number | null;
  signal: NodeJS.Signals | null;
  stderr: string;
}

export interface ClaimHandlerDeps {
  // RTDB I/O — injected so tests can use in-memory fakes.
  readSharedSession: () => Promise<SharedSessionMeta | null>;
  setClaimResult: (result: ClaimResult | null) => Promise<void>;
  clearClaimRequest: () => Promise<void>;
  appendAuditEntry: (entry: AuditEntry) => Promise<void>;
  isPidAlive: (pid: number) => boolean;
  // Spawn helper — injected so tests can return canned results without
  // actually opening Terminal windows. MUST be async: the daemon's event
  // loop also serves voice prompts and /command, so a blocking spawnSync
  // for up to 30s would freeze the watch. Production uses spawnAsync.
  spawn: (
    cmd: string,
    args: readonly string[],
    options: { timeout: number },
  ) => Promise<SpawnResult>;
  // sessionId → cwd from LOCAL data (resolveSessionCwd in src/claim-cwd.ts).
  // Null = the session isn't known on this Mac → claim refused.
  resolveSessionCwd: (
    sessionId: string,
  ) => string | null | Promise<string | null>;
  // Pure config — `commandMaxAgeSeconds` from src/config.ts and a path
  // hint to forward to `cc`. Both injected so tests don't depend on env.
  commandMaxAgeSeconds: number;
  tsxBin: string;
  shareScript: string;
  // The user's $TERM_PROGRAM at daemon startup — picks Terminal.app vs
  // iTerm. Daemon snapshots this once; tests pass directly.
  termProgram: string | undefined;
  // Single-flight gate: claim-handler must NOT run two osascripts
  // concurrently, so the caller hands us read/write accessors. The daemon
  // owns the actual `let claimBusy = false` so this scope sees fresh
  // values on every call.
  getClaimBusy: () => boolean;
  setClaimBusy: (busy: boolean) => void;
}

// Default async spawn: execFile with a timeout. Never rejects — failures
// map onto SpawnResult so the handler can write an actionable /claimResult.
// A timeout kills the child with SIGTERM (execFile's default killSignal).
export function spawnAsync(
  cmd: string,
  args: readonly string[],
  options: { timeout: number },
): Promise<SpawnResult> {
  return new Promise((resolve) => {
    execFile(
      cmd,
      [...args],
      { timeout: options.timeout, encoding: "utf8" },
      (err: ExecFileException | null, _stdout: string, stderr: string) => {
        if (!err) {
          resolve({ status: 0, signal: null, stderr: String(stderr ?? "") });
          return;
        }
        resolve({
          status: typeof err.code === "number" ? err.code : null,
          signal: (err.signal as NodeJS.Signals | undefined) ?? null,
          stderr: String(stderr ?? "").trim() || err.message,
        });
      },
    );
  });
}

export interface ClaimHandlerOutcome {
  // For tests + caller logging — what we ultimately did.
  decision:
    | "ok"
    | "stale"
    | "malformed"
    | "busy"
    | "locked"
    | "unknown-session"
    | "spawn-failed";
  reason?: string;
}

// Build the audit entry for a claim — reuses the existing "voice" kind so
// AuditEntry's union doesn't need to grow; `source: "watch"` + the tool
// name "(watch-claim)" are how the audit log viewer distinguishes claims
// from other voice flows.
function auditFor(
  sessionId: string,
  launcherName: string,
  decision: "allow" | "deny",
): AuditEntry {
  return {
    ts: Date.now(),
    kind: "voice",
    tool: "(watch-claim)",
    args: `-> ${launcherName}.app · session=${sessionId.slice(0, 8)}`,
    decision,
    source: "watch",
  };
}

export async function handleClaimRequest(
  claim: PendingClaim,
  deps: ClaimHandlerDeps,
): Promise<ClaimHandlerOutcome> {
  // ALWAYS clear the request at the end — even if we refuse — so a stale
  // entry doesn't get re-processed when the watch reconnects later.
  // Wrapped in try/finally below.
  try {
    // 1. Stale request? Drop silently. The watch should have already
    // retried or the user moved on; surfacing a banner for a 60s-old tap
    // adds confusion.
    const ageSec = (Date.now() - (claim.issuedAt ?? 0)) / 1000;
    if (ageSec > deps.commandMaxAgeSeconds) {
      return { decision: "stale", reason: `stale (age ${ageSec.toFixed(0)}s)` };
    }

    // 2. Malformed sessionId — defense in depth. The watch *should* only
    // send IDs straight from /recentSessions but a corrupted RTDB entry or
    // a manual write shouldn't shell-inject into the spawned `cc`.
    if (
      typeof claim.sessionId !== "string" ||
      !SESSION_ID_RE.test(claim.sessionId)
    ) {
      await deps.setClaimResult({
        ok: false,
        reason: "sessionId inválido",
        sessionId: claim.sessionId ?? "",
        ts: Date.now(),
      });
      return { decision: "malformed", reason: "sessionId failed regex" };
    }

    // 3. Single-flight guard. Two near-simultaneous taps from the watch
    // (or a tap during a still-running osascript) shouldn't spawn two
    // Terminals. Check AND set with no await in between — otherwise a
    // second handler can pass the check while the first is still awaiting
    // readSharedSession / the spawn.
    if (deps.getClaimBusy()) {
      await deps.setClaimResult({
        ok: false,
        reason: "otra claim en curso, espera",
        sessionId: claim.sessionId,
        ts: Date.now(),
      });
      return { decision: "busy" };
    }
    deps.setClaimBusy(true);
    try {
      // 4. Another wrapper-pty alive? `claude --resume` would fail anyway
      // (session locked) — refuse upfront with a clear message.
      const existing = await deps.readSharedSession();
      if (
        existing &&
        existing.kind === "wrapper-pty" &&
        deps.isPidAlive(existing.pid)
      ) {
        await deps.setClaimResult({
          ok: false,
          reason: "otra sesión cc activa, ciérrala primero",
          sessionId: claim.sessionId,
          ts: Date.now(),
        });
        return { decision: "locked", reason: `pid=${existing.pid} alive` };
      }

      // 5. cwd comes from the session's own transcript, never from the
      // watch (claim.cwd is ignored on purpose — see header).
      const cwd = await deps.resolveSessionCwd(claim.sessionId);
      if (cwd === null) {
        await deps.setClaimResult({
          ok: false,
          reason: "sesión no encontrada en este Mac",
          sessionId: claim.sessionId,
          ts: Date.now(),
        });
        return {
          decision: "unknown-session",
          reason: "no local transcript / cwd",
        };
      }

      // 6. All checks passed — spawn the Terminal.
      const launcher = pickLauncher(deps.termProgram);
      const shellCmd = buildShellCommand(
        cwd,
        deps.tsxBin,
        deps.shareScript,
        claim.sessionId,
      );
      const result = await deps.spawn(
        "osascript",
        launcher.appleScriptArgs(shellCmd),
        { timeout: OSASCRIPT_TIMEOUT_MS },
      );

      if (result.status !== 0) {
        const stderr = String(result.stderr ?? "").trim();
        const reason =
          result.signal === "SIGTERM"
            ? "osascript timeout — ¿Automation permission pendiente?"
            : stderr.length > 0
              ? `osascript: ${stderr.slice(0, 80)}`
              : "osascript falló (status != 0)";
        await deps.setClaimResult({
          ok: false,
          reason,
          sessionId: claim.sessionId,
          ts: Date.now(),
        });
        await deps.appendAuditEntry(
          auditFor(claim.sessionId, launcher.name, "deny"),
        );
        return { decision: "spawn-failed", reason };
      }

      // Success — Terminal.app / iTerm.app opened and the shell is now
      // running `exec tsx share.ts --resume <id>`. Result banner will
      // be auto-dismissed by the watch after 4s.
      await deps.setClaimResult({
        ok: true,
        sessionId: claim.sessionId,
        ts: Date.now(),
      });
      await deps.appendAuditEntry(
        auditFor(claim.sessionId, launcher.name, "allow"),
      );
      return { decision: "ok" };
    } finally {
      deps.setClaimBusy(false);
    }
  } finally {
    try {
      await deps.clearClaimRequest();
    } catch {
      // best-effort — listener will see the stale entry and re-fire, the
      // guards above will reject it. Nothing fatal.
    }
  }
}
