// What the daemon publishes when a voice run ends WITHOUT a usable answer:
// Claude Code got stuck on a dialog we must not answer from the wrist
// (workspace trust, login), or it exited with an error and left nothing
// readable. Pure — index.ts writes the result to RTDB.
//
// TODO(watch-ux): the watch only gets headline/response/taskKind today. A
// dedicated card ("trust this folder on your Mac") would need a structured
// field such as /voiceBlocked {kind, folder}; left out on purpose while the
// watch UX is being redesigned — schema.ts is unchanged.

import { homedir } from "node:os";
import type { BlockingDialog } from "./parser.js";
import { shSingleQuote } from "./sh-escape.js";
import type { TaskKind } from "./types/schema.js";

export interface VoiceOutcome {
  headline: string;
  response: string;
  taskKind: TaskKind;
  followups: null;
}

export const VOICE_TRUST_HEADLINE =
  "Claude necesita que confíes en la carpeta en tu Mac";
export const VOICE_AUTH_HEADLINE =
  "Claude perdió la sesión en tu Mac, vuelve a iniciar sesión";
export const VOICE_DIALOG_HEADLINE =
  "Claude espera una confirmación en tu Mac";
export const VOICE_ERROR_HEADLINE = "Claude terminó con un error, revisa el Mac";

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

function outcome(headline: string, response: string): VoiceOutcome {
  return { headline, response, taskKind: "info", followups: null };
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

// Decides the published result once a voice run is over. Returns null when
// the normal path (response + TL;DR + followups) should run.
export function finalizeVoiceOutcome(args: {
  blocked: BlockingDialog | null;
  exitCode: number | null;
  response: string;
  cwd: string;
  // The user tapped Stop: a non-zero exit is expected, not an error.
  stopped?: boolean;
  home?: string;
}): VoiceOutcome | null {
  if (args.blocked) {
    return blockedDialogOutcome(args.blocked, args.cwd, args.home);
  }
  if (
    !args.stopped &&
    args.exitCode !== 0 &&
    !hasMeaningfulText(args.response)
  ) {
    const code = args.exitCode === null ? "desconocido" : String(args.exitCode);
    return outcome(
      VOICE_ERROR_HEADLINE,
      `Claude Code salió con código ${code} sin una respuesta legible. Mira \`~/Library/Logs/ccwearos.log\` en tu Mac.`,
    );
  }
  return null;
}
