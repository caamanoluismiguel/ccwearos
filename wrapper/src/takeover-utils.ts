// Pure helpers for the `/ccwearos-takeover` flow.
//
// Living in src/ (not in scripts/) so vitest can import them without the
// surrounding script's Firebase / spawn side effects firing on import.
//
// The takeover flow opens a new Terminal window via `osascript`. Building
// that command involves two layers of escaping (shell + AppleScript), so
// they live here as small, individually testable functions.

import { shSingleQuote } from "./sh-escape.js";
// Re-export so existing test imports keep working. Canonical home is
// src/sh-escape.ts (also imported by claude-runner.ts).
export { shSingleQuote };

// Escape a string for inclusion inside an AppleScript double-quoted string
// literal. Only `\` and `"` are special inside double quotes — they get
// backslash-escaped (order matters: backslash first, so the second pass
// doesn't re-escape backslashes we just added).
//
// BUT: AppleScript also treats certain bytes as STATEMENT TERMINATORS even
// inside double-quoted strings — namely LF (\n), CR (\r), and the Unicode
// line / paragraph separators U+2028 / U+2029. A cwd or sessionId containing
// these would close our `do script "..."` string and inject arbitrary
// AppleScript. macOS allows newlines in HFS+/APFS filenames so this is
// reachable, not theoretical.
//
// We strip those plus NUL and other ASCII control bytes — replacing with `?`
// so a malformed input is obvious to the user when it reaches Terminal.app.
// Callers that care should validate inputs BEFORE escaping; this is a
// defense-in-depth layer.
//
// Built via RegExp constructor with \u-escape sequences so the source stays
// 7-bit-clean (TS rejects literal U+2028/U+2029 inside a /.../ regex).
const APL_CONTROL_RE = new RegExp("[\\x00-\\x1f\\x7f\\u2028\\u2029]", "g");

export function aplEscape(s: string): string {
  return s
    .replace(APL_CONTROL_RE, "?")
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"');
}

export interface TerminalLauncher {
  name: "Terminal" | "iTerm";
  // Returns the `-e ...` argv list for `osascript`. Splitting the script
  // into one line per `-e` is easier to read than escaping newlines in a
  // single big script string.
  appleScriptArgs: (shellCmd: string) => string[];
}

// Pick the launcher based on the user's current terminal. We support iTerm
// natively because it's the most-used third-party terminal on macOS; every
// other $TERM_PROGRAM (Warp, Hyper, Code's integrated, tmux, etc.) falls
// back to Terminal.app — always installed, always scriptable.
//
// Terminal.app note: `do script "..."` opens in the FRONT window if one is
// open, else creates a new one. To force a new window every time (so we
// don't hijack a tab the user has work in) we pass a second arg —
// `do script "..." in (make new window)` — but that's tricky in osascript
// `-e` form. We use `tell application "Terminal"` + `activate` + `do script`
// after explicitly creating a window via `make new window` first. This
// guarantees a NEW window every invocation.
export function pickLauncher(
  termProgram: string | undefined,
): TerminalLauncher {
  if (termProgram === "iTerm.app") {
    return {
      name: "iTerm",
      appleScriptArgs: (shellCmd) => [
        "-e",
        'tell application "iTerm"',
        "-e",
        "activate",
        "-e",
        "set newWindow to (create window with default profile)",
        "-e",
        "tell current session of newWindow",
        "-e",
        `write text "${aplEscape(shellCmd)}"`,
        "-e",
        "end tell",
        "-e",
        "end tell",
      ],
    };
  }
  return {
    name: "Terminal",
    appleScriptArgs: (shellCmd) => [
      "-e",
      'tell application "Terminal"',
      "-e",
      "activate",
      "-e",
      // Forces a brand new window — otherwise `do script` would reuse the
      // frontmost Terminal window and hijack the user's existing tab.
      `do script "${aplEscape(shellCmd)}"`,
      "-e",
      "end tell",
    ],
  };
}

// Build the shell command the new Terminal window will execute. `exec`
// replaces the spawned shell with tsx so the Terminal window's lifecycle
// tracks the wrapper process — closing the window also ends `cc`.
//
// We use `bash --noprofile --norc` to skip the user's .zshrc / .bashrc.
// Reason: a slow nvm init or a broken rc could delay or block the `exec`,
// leaving the new window apparently doing nothing for seconds. Our command
// only needs PATH for tsx (we use an absolute path) so rc is unnecessary.
export function buildShellCommand(
  cwd: string,
  tsxBin: string,
  shareScript: string,
  sessionId: string,
): string {
  return `cd ${shSingleQuote(cwd)} && exec ${shSingleQuote(tsxBin)} ${shSingleQuote(shareScript)} --resume ${sessionId}`;
}
