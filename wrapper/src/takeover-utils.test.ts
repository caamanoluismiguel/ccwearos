import { describe, expect, it } from "vitest";
import {
  aplEscape,
  buildShellCommand,
  pickLauncher,
  shSingleQuote,
} from "./takeover-utils.js";

describe("shSingleQuote", () => {
  it("wraps a plain string in single quotes", () => {
    expect(shSingleQuote("hello")).toBe("'hello'");
  });

  it("escapes embedded single quotes with the close-escape-reopen trick", () => {
    expect(shSingleQuote("it's")).toBe("'it'\\''s'");
  });

  it("leaves spaces, dollar signs, and backticks untouched (they're literal inside single quotes)", () => {
    expect(shSingleQuote("$HOME `whoami`")).toBe("'$HOME `whoami`'");
  });

  it("survives a path with spaces", () => {
    const out = shSingleQuote("/Users/luis/My Projects/CCWEAROS");
    expect(out).toBe("'/Users/luis/My Projects/CCWEAROS'");
  });
});

describe("aplEscape", () => {
  it("returns plain strings unchanged", () => {
    expect(aplEscape("hello world")).toBe("hello world");
  });

  it("escapes double quotes for AppleScript double-quoted strings", () => {
    expect(aplEscape('say "hi"')).toBe('say \\"hi\\"');
  });

  it("escapes backslashes BEFORE quotes (order matters)", () => {
    // If we escaped quotes first, the second pass would turn `\"` into `\\\"`
    // — a backslash literal followed by an escaped quote. The correct output
    // is one backslash literal then one escaped quote.
    expect(aplEscape('\\"')).toBe('\\\\\\"');
  });

  it("handles a realistic shell command with both quotes and backslashes", () => {
    const cmd = `cd '/path with"weird'\\'' chars'`;
    const escaped = aplEscape(cmd);
    expect(escaped).toBe(`cd '/path with\\"weird'\\\\'' chars'`);
  });

  it("scrubs LF / CR (would close AppleScript do-script string)", () => {
    // A cwd containing \n could close `do script "..."` and inject script
    // that runs with the user's Automation privileges. macOS allows newlines
    // in HFS+/APFS filenames so this is reachable, not theoretical.
    expect(aplEscape("a\nb")).toBe("a?b");
    expect(aplEscape("a\rb")).toBe("a?b");
    expect(aplEscape("\r\n")).toBe("??");
  });

  it("scrubs U+2028 / U+2029 (AppleScript also treats as statement terminators)", () => {
    expect(aplEscape("a b")).toBe("a?b");
    expect(aplEscape("a b")).toBe("a?b");
  });

  it("scrubs NUL and other ASCII control bytes", () => {
    expect(aplEscape("a\x00b")).toBe("a?b");
    expect(aplEscape("a\x1bb")).toBe("a?b"); // ESC
    expect(aplEscape("a\x7fb")).toBe("a?b"); // DEL
  });

  it("blocks AppleScript injection via newline in input", () => {
    const malicious = `safe"\nactivate\ntell application "Finder" to delete every file\n"rest`;
    const escaped = aplEscape(malicious);
    // The newlines become ?, the quote gets escaped, so the injected
    // AppleScript becomes an inert (quoted, single-line) string.
    expect(escaped).not.toContain("\n");
    expect(escaped).toContain('safe\\"');
    expect(escaped.split('\\"').length - 1).toBeGreaterThanOrEqual(2);
  });
});

describe("pickLauncher", () => {
  it("returns Terminal for Apple_Terminal", () => {
    expect(pickLauncher("Apple_Terminal").name).toBe("Terminal");
  });

  it("returns iTerm for iTerm.app", () => {
    expect(pickLauncher("iTerm.app").name).toBe("iTerm");
  });

  it("falls back to Terminal for unknown/missing $TERM_PROGRAM", () => {
    expect(pickLauncher(undefined).name).toBe("Terminal");
    expect(pickLauncher("").name).toBe("Terminal");
    expect(pickLauncher("WarpTerminal").name).toBe("Terminal");
    expect(pickLauncher("tmux").name).toBe("Terminal");
    expect(pickLauncher("vscode").name).toBe("Terminal");
  });

  it("Terminal launcher emits a valid `tell application` block", () => {
    const args = pickLauncher("Apple_Terminal").appleScriptArgs(`echo hello`);
    // Every `-e` flag is followed by one line of AppleScript.
    expect(args.filter((a) => a === "-e").length).toBe(4);
    expect(args).toContain('tell application "Terminal"');
    expect(args).toContain("activate");
    expect(args).toContain('do script "echo hello"');
    expect(args).toContain("end tell");
  });

  it("iTerm launcher creates a new window and writes the command", () => {
    const args = pickLauncher("iTerm.app").appleScriptArgs("echo hi");
    expect(args).toContain('tell application "iTerm"');
    expect(args).toContain(
      "set newWindow to (create window with default profile)",
    );
    expect(args).toContain('write text "echo hi"');
  });

  it("escapes the shell command before embedding in the AppleScript", () => {
    // A bash command that contains AppleScript-special chars should appear
    // escaped in the final -e args.
    const args =
      pickLauncher("Apple_Terminal").appleScriptArgs(`echo "hi" \\ done`);
    const doScriptLine = args.find((a) => a.startsWith("do script "));
    expect(doScriptLine).toBe('do script "echo \\"hi\\" \\\\ done"');
  });
});

describe("buildShellCommand", () => {
  const SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

  it("composes cd + exec + tsx + share.ts + --resume <id>", () => {
    const cmd = buildShellCommand(
      "/home/me/project",
      "/wrapper/node_modules/.bin/tsx",
      "/wrapper/scripts/share.ts",
      SESSION_ID,
    );
    expect(cmd).toBe(
      `cd '/home/me/project' && exec '/wrapper/node_modules/.bin/tsx' '/wrapper/scripts/share.ts' --resume ${SESSION_ID}`,
    );
  });

  it("survives a cwd with spaces", () => {
    const cmd = buildShellCommand(
      "/Users/luis/My Projects/foo",
      "/wrapper/tsx",
      "/wrapper/share.ts",
      SESSION_ID,
    );
    expect(cmd).toContain("cd '/Users/luis/My Projects/foo'");
  });

  it("survives a cwd with single quotes", () => {
    const cmd = buildShellCommand(
      "/Users/luis/it's-mine",
      "/wrapper/tsx",
      "/wrapper/share.ts",
      SESSION_ID,
    );
    expect(cmd).toContain("cd '/Users/luis/it'\\''s-mine'");
  });
});
