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

// Token returned by ActivePrompt.consume/release: which prompt was taken and
// the publish counter at that moment, so the caller can later ask "has a
// newer prompt been published since?" (see src/prompt-store.ts).
export interface ConsumedPrompt {
  id: string;
  publishSeq: number;
}

export class ActivePrompt {
  private id: string | null = null;
  private pending: Promise<unknown> = Promise.resolve();
  // Bumped on every publish/clear so a publish that resolves AFTER a newer
  // publish or a clear can't resurrect a stale id.
  private seq = 0;
  // Bumped on publish only — "a newer prompt exists" for supersededSince.
  private publishSeq = 0;

  // `publish` is publishPermissionPrompt bound to the prompt text. The
  // returned promise never rejects (errors go to onError) so callers can
  // fire-and-forget from a synchronous parser callback.
  publish(
    publish: () => Promise<string>,
    onError: (e: unknown) => void = () => {},
  ): Promise<void> {
    const mySeq = ++this.seq;
    this.publishSeq++;
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
    await this.ready();
    return this.id;
  }

  async ready(): Promise<void> {
    await this.pending;
  }

  // Synchronous read — callers pair it with consume() in the same tick.
  peek(): string | null {
    return this.id;
  }

  // Claim-once: takes the prompt iff `id` is still the active one. The
  // first caller wins; a concurrent second caller (double tap, replay) gets
  // null. Synchronous so no await can split check and take.
  consume(id: string): ConsumedPrompt | null {
    if (this.id === null || this.id !== id) return null;
    this.id = null;
    return { id, publishSeq: this.publishSeq };
  }

  // True if a prompt was published (or started publishing) after `token`
  // was taken.
  supersededSince(token: ConsumedPrompt): boolean {
    return this.publishSeq !== token.publishSeq;
  }

  // Takes whatever prompt is active (after an in-flight publish settles),
  // unless a newer publish started while we waited. Used when the user
  // answers in the terminal instead of on the watch.
  async release(): Promise<ConsumedPrompt | null> {
    const startSeq = this.publishSeq;
    await this.ready();
    if (this.publishSeq !== startSeq) return null;
    const id = this.id;
    if (id === null) return null;
    return this.consume(id);
  }
}

export type CommandOutcome =
  | { kind: "rejected"; reason: string }
  | { kind: "stop" }
  | {
      kind: "answer";
      bytes: string;
      decision: "allow" | "deny";
      // Set by consumeCommand: Claude published a newer prompt while we
      // answered, so callers must NOT downgrade status to RUNNING.
      newerPrompt?: boolean;
    };

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

// Conditional clear: /permissionPrompt + /permissionPromptId only if the id
// on RTDB is still `id` and nothing newer was published (`superseded`).
// Production binding: clearPermissionPromptIf (src/prompt-store.ts).
export type ClearPromptIf = (
  id: string,
  superseded: () => boolean,
) => Promise<unknown>;

export interface CommandSink {
  clearCommand: () => Promise<void>;
  clearPrompt: ClearPromptIf;
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
  await prompt.ready();
  // From here to prompt.consume() there is no await: two /command events
  // waiting on the same publish resume one after the other, and the second
  // sees the id already consumed (claim-once, first caller wins).
  const activeId = prompt.peek();
  const outcome = decideCommand(cmd, activeId, opts.now, opts.maxAgeSeconds);
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
  // Consume the id BEFORE writing to the pty so a second tap that races in
  // can't match it too, and so a prompt Claude emits in reply to our bytes
  // counts as newer (supersededSince).
  const token = activeId === null ? null : prompt.consume(activeId);
  if (token === null) {
    sink.warn("/command rejected (already-consumed)");
    await sink.clearCommand();
    return { kind: "rejected", reason: "already-consumed" };
  }
  await sink.answer(outcome.bytes, outcome.decision);
  await sink.clearCommand();
  // Conditional: if Claude already published prompt N+1, leave it alone.
  await sink.clearPrompt(token.id, () => prompt.supersededSince(token));
  return { ...outcome, newerPrompt: prompt.supersededSince(token) };
}

// The user answered in the terminal (runner onPermissionCleared): drop the
// local prompt so a late watch tap can't type into Claude's input, and
// conditionally clear it on RTDB. Returns whether a prompt was released.
export async function releaseActivePrompt(
  prompt: ActivePrompt,
  clearPrompt: ClearPromptIf,
): Promise<boolean> {
  const token = await prompt.release();
  if (token === null) return false;
  await clearPrompt(token.id, () => prompt.supersededSince(token));
  return true;
}
