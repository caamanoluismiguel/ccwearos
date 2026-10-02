// What the daemon publishes when a voice run ends WITHOUT a usable answer:
// Claude Code needs the Mac (login, or trust if a future -p asks for it), it
// exited with an error and left no result, or it ran past its time cap.
// Pure — index.ts writes to RTDB.
//
// Two channels, on purpose:
//   - /blocker {kind, hint, cwd?, ts} + /outcome {ok, exitCode, stopped?, ts}: the
//     structured contract new watch builds render (schema.ts).
//   - headline/response/taskKind: the same message as plain text, kept as a
//     fallback for watch builds that predate /blocker.

import { homedir } from "node:os";
import type { BlockingDialog } from "./parser.js";
import { SHARED_SESSION_BLOCKER_HINT } from "./shared-session.js";
import { shSingleQuote } from "./sh-escape.js";
import type { BlockerKind, RunOutcome, TaskKind } from "./types/schema.js";

export interface VoiceOutcome {
  headline: string;
  response: string;
  taskKind: TaskKind;
  followups: null;
}

// Blocker minus the timestamp (the caller stamps it when writing).
export interface VoiceBlocker {
  kind: BlockerKind;
  hint: string;
  cwd?: string;
}

export const VOICE_TRUST_HEADLINE =
  "Claude necesita que confíes en la carpeta en tu Mac";
export const VOICE_AUTH_HEADLINE =
  "Claude perdió la sesión en tu Mac, vuelve a iniciar sesión";
export const VOICE_DIALOG_HEADLINE =
  "Claude espera una confirmación en tu Mac";
export const VOICE_ERROR_HEADLINE = "Claude terminó con un error, revisa el Mac";
export const VOICE_TIMEOUT_HEADLINE = "Claude tardó demasiado y se detuvo";

export interface VoiceRunFacts {
  blocked: BlockingDialog | null;
  exitCode: number | null;
  response: string; // last response published during the run
  cwd: string; // where the run was spawned
  timedOut?: boolean; // hit the runner's hard time cap
  stopped?: boolean; // the user tapped Stop: a non-zero exit is expected
  isError?: boolean; // the stream's result event said is_error
  home?: string;
}

// "/Users/me/.ccwearos/voice" → "~/.ccwearos/voice" (shorter on the watch,
// and still a valid argument for `cd`). Anything else is shell-quoted.
function shellPath(dir: string, home: string): string {
  if (dir === home) return "~";
  if (dir.startsWith(home + "/")) {
    const rest = dir.slice(home.length + 1);
    if (/^[\w./-]+$/.test(rest)) return `~/${rest}`;
    return `~/${shSingleQuote(rest)}`;
  }
  return /^[\w./-]+$/.test(dir) ? dir : shSingleQuote(dir);
}

const exitLabel = (code: number | null): string =>
  code === null ? "desconocido" : String(code);

function outcome(headline: string, response: string): VoiceOutcome {
  return { headline, response, taskKind: "info", followups: null };
}

// One or two sentences, tuteo: what to do on the Mac.
function dialogHint(d: BlockingDialog, cwd: string, dir: string): string {
  switch (d.kind) {
    case "trust":
      return `En tu Mac, abre Terminal en ${cwd} y corre \`claude\` una vez; elige 'Yes, I trust this folder'.`;
    case "auth":
      return "En tu Mac, abre Terminal, corre `claude` y escribe `/login`.";
    case "other":
      return (
        "Claude espera una confirmación" +
        (d.detail ? ` («${d.detail}»)` : "") +
        `. En tu Mac, corre \`cd ${dir} && claude\` para resolverla.`
      );
  }
}

export function blockedDialogOutcome(
  dialog: BlockingDialog,
  cwd: string,
  home: string = homedir(),
): VoiceOutcome {
  const dir = shellPath(cwd, home);
  switch (dialog.kind) {
    case "trust":
      return outcome(
        VOICE_TRUST_HEADLINE,
        [
          `Claude Code pidió confiar en la carpeta \`${cwd}\` y eso no se acepta desde el reloj.`,
          "",
          `En tu Mac, abre Terminal en \`${cwd}\` y corre \`claude\` una vez; elige 'Yes, I trust this folder'.`,
          "",
          `Atajo: \`cd ${dir} && claude\`. Luego vuelve a preguntar desde el reloj.`,
        ].join("\n"),
      );
    case "auth":
      return outcome(
        VOICE_AUTH_HEADLINE,
        [
          "Claude Code no tiene una sesión válida en tu Mac.",
          "",
          "En tu Mac, abre Terminal, corre `claude` y escribe `/login`. Luego vuelve a preguntar desde el reloj.",
          ...(dialog.detail ? ["", `Detalle: \`${dialog.detail}\``] : []),
        ].join("\n"),
      );
    case "other":
      return outcome(
        VOICE_DIALOG_HEADLINE,
        [
          "Claude Code mostró un diálogo que no se responde desde el reloj" +
            (dialog.detail ? `: «${dialog.detail}».` : "."),
          "",
          `En tu Mac, corre \`cd ${dir} && claude\` para verlo y resolverlo. Luego vuelve a preguntar desde el reloj.`,
        ].join("\n"),
      );
  }
}

// At least two letters/digits in a row — "(B", "─", "❯" don't count.
export function hasMeaningfulText(s: string): boolean {
  return /[\p{L}\p{N}]{2,}/u.test(s);
}

const crashed = (f: VoiceRunFacts): boolean =>
  !f.stopped && f.exitCode !== 0 && !hasMeaningfulText(f.response);

// /blocker for this run, or null. Precedence: dialog > timeout > crash.
export function voiceBlocker(f: VoiceRunFacts): VoiceBlocker | null {
  const home = f.home ?? homedir();
  if (f.blocked) {
    const kind: BlockerKind =
      f.blocked.kind === "auth" ? "login" : f.blocked.kind;
    const hint = dialogHint(f.blocked, f.cwd, shellPath(f.cwd, home));
    return kind === "trust" || kind === "other"
      ? { kind, hint, cwd: f.cwd }
      : { kind, hint };
  }
  if (f.timedOut) {
    return {
      kind: "timeout",
      hint: "La tarea tardó demasiado y Claude la detuvo. Revísala en tu Mac o pide algo más corto.",
    };
  }
  if (crashed(f)) {
    return {
      kind: "crash",
      hint: `Claude terminó con código ${exitLabel(f.exitCode)} sin respuesta. Mira ~/Library/Logs/ccwearos.log en tu Mac.`,
    };
  }
  return null;
}

// /outcome for this run (minus ts). ok only for a clean exit, no is_error
// result, no blocker and no user stop. RunOutcome.exitCode is a number: a missing code (spawn
// failure) is -1. A user stop adds `stopped: true` (ok stays false) so the
// watch can show a soft tick instead of an error.
export function voiceRunOutcome(f: VoiceRunFacts): Omit<RunOutcome, "ts"> {
  const exitCode = f.exitCode ?? -1;
  if (f.stopped) return { ok: false, exitCode, stopped: true };
  return {
    ok: exitCode === 0 && f.isError !== true && voiceBlocker(f) === null,
    exitCode,
  };
}

// Whether the NEXT voice prompt should use --continue (= /conversationActive).
// A clean run starts/extends a thread; a reset prompt that didn't complete
// still drops the old thread (the user asked for a fresh one).
// Whether a voice prompt continues the previous thread. An explicit mode
// from the watch wins; without one, continue unless a reset phrase matched.
export function decideContinuation(a: {
  hadPrior: boolean;
  mode: "new" | "continue" | undefined;
  resetPhrase: boolean;
}): { shouldContinue: boolean; reset: boolean } {
  if (a.mode === "new") return { shouldContinue: false, reset: a.hadPrior };
  if (a.mode === "continue") return { shouldContinue: a.hadPrior, reset: false };
  const reset = a.hadPrior && a.resetPhrase;
  return { shouldContinue: a.hadPrior && !reset, reset };
}

export function nextHasPriorSession(a: {
  hadPrior: boolean;
  reset: boolean;
  exitCode: number | null;
  blocked: boolean;
}): boolean {
  if (a.exitCode === 0 && !a.blocked) return true;
  return a.reset ? false : a.hadPrior;
}

// RTDB writers used at the start / end of a voice run (firebase.ts in prod,
// fakes in tests).
export interface VoiceRunSinks {
  setBlocker: (b: (VoiceBlocker & { ts: number }) | null) => Promise<void>;
  setOutcome: (o: RunOutcome | null) => Promise<void>;
  setResponse: (r: string | null) => Promise<void>;
  setTaskKind: (k: TaskKind | null) => Promise<void>;
  setHeadline: (h: string | null) => Promise<void>;
  setFollowups: (f: string[] | null) => Promise<void>;
}

// A live shared session (cc / /ccwearos) owns Claude on the Mac, so the
// daemon refuses the voice prompt. Say so on /blocker — otherwise the watch
// only sees its prompt vanish and times out ("Tu Mac no tomó la pregunta").
// The previous run's /outcome is cleared so it can't be read as this one's.
export async function publishSharedSessionDrop(
  sinks: Pick<VoiceRunSinks, "setBlocker" | "setOutcome">,
  now: number = Date.now(),
): Promise<void> {
  await sinks.setOutcome(null);
  await sinks.setBlocker({
    kind: "other",
    hint: SHARED_SESSION_BLOCKER_HINT,
    ts: now,
  });
}

// Run start: the previous run's blocker/outcome must not linger.
export async function clearVoiceRunEnd(sinks: VoiceRunSinks): Promise<void> {
  await sinks.setBlocker(null);
  await sinks.setOutcome(null);
}

// Run end: /blocker (if any), then either the plain-text fallback or the
// caller's normal path (TL;DR / taskKind / followups), and /outcome LAST so
// a watch reacting to it already sees everything else. Returns true when the
// fallback replaced the normal path.
export async function publishVoiceRunEnd(
  f: VoiceRunFacts,
  sinks: VoiceRunSinks,
  normalPath: () => Promise<void>,
  now: number = Date.now(),
): Promise<boolean> {
  const blocker = voiceBlocker(f);
  if (blocker) await sinks.setBlocker({ ...blocker, ts: now });
  const special = finalizeVoiceOutcome(f);
  if (special) {
    await sinks.setResponse(special.response);
    await sinks.setTaskKind(special.taskKind);
    await sinks.setHeadline(special.headline);
    await sinks.setFollowups(special.followups);
  } else {
    await normalPath();
  }
  await sinks.setOutcome({ ...voiceRunOutcome(f), ts: now });
  return special !== null;
}

// Plain-text fallback (headline/response) for old watch builds. Returns null
// when the normal path (response + TL;DR + followups) should run.
export function finalizeVoiceOutcome(f: VoiceRunFacts): VoiceOutcome | null {
  if (f.blocked) return blockedDialogOutcome(f.blocked, f.cwd, f.home);
  if (f.timedOut && !hasMeaningfulText(f.response)) {
    return outcome(
      VOICE_TIMEOUT_HEADLINE,
      "La tarea tardó demasiado y Claude la detuvo. Revísala en tu Mac o pide algo más corto.",
    );
  }
  if (crashed(f)) {
    return outcome(
      VOICE_ERROR_HEADLINE,
      `Claude Code salió con código ${exitLabel(f.exitCode)} sin una respuesta legible. Mira \`~/Library/Logs/ccwearos.log\` en tu Mac.`,
    );
  }
  return null;
}
