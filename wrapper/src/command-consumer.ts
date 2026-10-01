// Shared /command consumption for every wrapper entry point that owns a pty
// (interactive runner, daemon voice runner, `cc` / scripts/share.ts).
//
// Two pieces:
//   - ActivePrompt: remembers the one-time /permissionPromptId minted by
//     publishPermissionPrompt (src/firebase.ts) for the prompt currently on
//     the watch.
//   - consumeCommand: age check + checkCommand allowlist (src/command-guard.ts)
//     + the side effects in a fixed order. Rejected commands NEVER reach the
//     pty; an accepted answer consumes the prompt id so a replayed or double
//     tap can't answer the next prompt.
//
// Every Firebase / pty effect is injected so this stays unit-testable.

import {
  checkCommand,
  PERMISSION_DENY_EMPTY,
  PERMISSION_DENY_ESC,
} from "./command-guard.js";
import type { PendingCommand } from "./types/schema.js";

export class ActivePrompt {
  private id: string | null = null;
  private pending: Promise<unknown> = Promise.resolve();
  // Bumped on every publish/clear so a publish that resolves AFTER a newer
  // publish or a clear can't resurrect a stale id.
  private seq = 0;

  // `publish` is publishPermissionPrompt bound to the prompt text. The
  // returned promise never rejects (errors go to onError) so callers can
  // fire-and-forget from a synchronous parser callback.
  publish(
    publish: () => Promise<string>,
    onError: (e: unknown) => void = () => {},
  ): Promise<void> {
    const mySeq = ++this.seq;
    const p = publish().then(
      (id) => {
        if (mySeq === this.seq) this.id = id;
      },
      (e: unknown) => {
        if (mySeq === this.seq) this.id = null;
        onError(e);
      },
    );
    this.pending = p;
    return p;
  }

  clear(): void {
    this.seq++;
    this.id = null;
  }

  // Waits for an in-flight publish first: the watch can only echo an id it
  // has read from RTDB, but the server ack for our own write can arrive
  // after the watch's /command lands in our listener.
  async current(): Promise<string | null> {
    await this.pending;
    return this.id;
  }
}

export type CommandOutcome =
  | { kind: "rejected"; reason: string }
  | { kind: "stop" }
  | { kind: "answer"; bytes: string; decision: "allow" | "deny" };

// Pure decision: what to do with this /command right now.
export function decideCommand(
  cmd: Pick<PendingCommand, "text" | "issuedAt" | "promptId">,
  activePromptId: string | null,
  now: number,
  maxAgeSeconds: number,
): CommandOutcome {
  const ageSec = (now - (typeof cmd.issuedAt === "number" ? cmd.issuedAt : 0)) / 1000;
  if (ageSec > maxAgeSeconds) {
    return { kind: "rejected", reason: `stale (age ${ageSec.toFixed(1)}s)` };
  }
  const verdict = checkCommand(cmd, activePromptId);
  if (!verdict.ok) return { kind: "rejected", reason: verdict.reason };
  if (verdict.kind === "stop") return { kind: "stop" };
  // Legacy watch deny is "" — writing nothing to the pty would leave Claude
  // waiting, so both deny forms become ESC.
  const isDeny = cmd.text === PERMISSION_DENY_EMPTY || cmd.text === PERMISSION_DENY_ESC;
  return {
    kind: "answer",
    bytes: isDeny ? PERMISSION_DENY_ESC : cmd.text,
    decision: isDeny ? "deny" : "allow",
  };
}

export interface CommandSink {
  clearCommand: () => Promise<void>;
  // setPermissionPrompt(null) — clears /permissionPrompt + /permissionPromptId.
  clearPrompt: () => Promise<void>;
  // Write the (already allowlisted) answer bytes to the pty, plus any
  // per-entry-point bookkeeping (audit, status).
  answer: (bytes: string, decision: "allow" | "deny") => void | Promise<void>;
  // Entry-point specific STOP behaviour (SIGINT / kill / teardown).
  stop: () => void | Promise<void>;
  warn: (msg: string) => void;
}

export async function consumeCommand(
  cmd: PendingCommand,
  prompt: ActivePrompt,
  opts: { now: number; maxAgeSeconds: number },
  sink: CommandSink,
): Promise<CommandOutcome> {
  const outcome = decideCommand(
    cmd,
    await prompt.current(),
    opts.now,
    opts.maxAgeSeconds,
  );
  if (outcome.kind === "rejected") {
    sink.warn(
      `/command rejected (${outcome.reason}): ${JSON.stringify(String(cmd.text).slice(0, 40))}`,
    );
    await sink.clearCommand();
    return outcome;
  }
  if (outcome.kind === "stop") {
    prompt.clear();
    await sink.clearCommand();
    await sink.stop();
    return outcome;
  }
  // Consume the id BEFORE the await so a second tap that races in while we
  // write can't match it too.
  prompt.clear();
  await sink.answer(outcome.bytes, outcome.decision);
  await sink.clearCommand();
  await sink.clearPrompt();
  return outcome;
}
