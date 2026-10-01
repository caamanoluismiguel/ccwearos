import type { PendingCommand } from "./types/schema.js";

// The only byte sequences the watch is ever allowed to type into a Claude
// pty via /command. Anything else (a new prompt, "!rm -rf ~\r", escape
// sequences) is dropped and logged — a leaked watch UID must not be able to
// drive the session.
export const PERMISSION_ALLOW = "1\r";
export const PERMISSION_ALLOW_ALWAYS = "2\r";
export const PERMISSION_DENY_EMPTY = ""; // legacy watch deny: wrapper sends ESC
export const PERMISSION_DENY_ESC = "\x1b";
export const STOP = "\x03";

const PERMISSION_ANSWERS: ReadonlySet<string> = new Set([
  PERMISSION_ALLOW,
  PERMISSION_ALLOW_ALWAYS,
  PERMISSION_DENY_EMPTY,
  PERMISSION_DENY_ESC,
]);

export function isPermissionAnswer(text: string): boolean {
  return PERMISSION_ANSWERS.has(text);
}

export function isAllowedCommandText(text: unknown): text is string {
  return typeof text === "string" && (text === STOP || PERMISSION_ANSWERS.has(text));
}

export type CommandVerdict =
  | { ok: true; kind: "answer" | "stop" }
  | { ok: false; reason: "not-allowed" | "no-active-prompt" | "prompt-mismatch" };

// Decide whether a /command write may be applied right now.
//   - STOP is always accepted (no prompt needed).
//   - A permission answer needs an active prompt AND a matching promptId, so
//     an offline-queued, replayed or double tap can't answer a newer prompt.
export function checkCommand(
  cmd: Pick<PendingCommand, "text" | "promptId">,
  activePromptId: string | null,
): CommandVerdict {
  if (!isAllowedCommandText(cmd.text)) return { ok: false, reason: "not-allowed" };
  if (cmd.text === STOP) return { ok: true, kind: "stop" };
  if (activePromptId === null) return { ok: false, reason: "no-active-prompt" };
  if (cmd.promptId !== activePromptId) return { ok: false, reason: "prompt-mismatch" };
  return { ok: true, kind: "answer" };
}
