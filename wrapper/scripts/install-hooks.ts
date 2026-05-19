// One-shot installer for CCWEAROS Camino E (the `/ccwearos` slash-command +
// PreToolUse hook flow). Idempotent — safe to re-run.
//
// What it does:
//   1. Copies wrapper/templates/ccwearos.md → ~/.claude/commands/ccwearos.md
//   2. Copies wrapper/templates/ccwearos-off.md → ~/.claude/commands/ccwearos-off.md
//   3. Merges a PreToolUse hook entry into ~/.claude/settings.json so the
//      hook fires for every Claude Code session you start (it self-skips
//      unless /sharedSession.kind === "hook" matches the current session).
//
// Run once:
//   cd ~/projects/CCWEAROS/wrapper
//   npx tsx scripts/install-hooks.ts
//
// Run with --uninstall to remove.

import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";

const WRAPPER_ROOT = resolve(import.meta.dirname ?? __dirname, "..");
const HOOK_SCRIPT = join(WRAPPER_ROOT, "scripts/hooks/pre-tool-use.ts");
const TEMPLATES_DIR = join(WRAPPER_ROOT, "templates");

const CLAUDE_DIR = join(homedir(), ".claude");
const COMMANDS_DIR = join(CLAUDE_DIR, "commands");
const SETTINGS_PATH = join(CLAUDE_DIR, "settings.json");
const HOOK_ID = "ccwearos-pre-tool-use"; // marker so we can find + replace cleanly

interface ClaudeSettings {
  hooks?: {
    PreToolUse?: HookEntry[];
    [k: string]: unknown;
  };
  [k: string]: unknown;
}

interface HookEntry {
  matcher?: string;
  ccwearosId?: string; // our marker — used to dedupe on re-install
  hooks?: Array<{
    type: "command";
    command: string;
    timeout?: number;
  }>;
  [k: string]: unknown;
}

function readSettings(): ClaudeSettings {
  if (!existsSync(SETTINGS_PATH)) return {};
  try {
    return JSON.parse(readFileSync(SETTINGS_PATH, "utf8")) as ClaudeSettings;
  } catch (e) {
    console.error(
      `[install-hooks] Could not parse ${SETTINGS_PATH}: ${(e as Error).message}`,
    );
    process.exit(1);
  }
}

function writeSettings(s: ClaudeSettings): void {
  mkdirSync(dirname(SETTINGS_PATH), { recursive: true });
  writeFileSync(SETTINGS_PATH, JSON.stringify(s, null, 2) + "\n", "utf8");
}

function tsxBin(): string {
  // The user's Claude Code session might not have tsx on PATH; use the
  // wrapper's local install so the hook is always runnable.
  const local = join(WRAPPER_ROOT, "node_modules/.bin/tsx");
  return existsSync(local) ? local : "tsx";
}

const SLASH_COMMAND_FILES = [
  "ccwearos.md",
  "ccwearos-off.md",
  "ccwearos-takeover.md",
];

function install(): void {
  // 1. Slash commands
  mkdirSync(COMMANDS_DIR, { recursive: true });
  for (const name of SLASH_COMMAND_FILES) {
    const src = join(TEMPLATES_DIR, name);
    const dst = join(COMMANDS_DIR, name);
    copyFileSync(src, dst);
    console.log(`[install-hooks] ✓ ${dst}`);
  }

  // 2. settings.json hook entry
  const settings = readSettings();
  settings.hooks = settings.hooks ?? {};
  const existingArr = (settings.hooks.PreToolUse ?? []) as HookEntry[];
  const filtered = existingArr.filter(
    (e) => (e as HookEntry).ccwearosId !== HOOK_ID,
  );
  const newEntry: HookEntry = {
    matcher: "*",
    ccwearosId: HOOK_ID,
    hooks: [
      {
        type: "command",
        command: `${tsxBin()} ${HOOK_SCRIPT}`,
        timeout: 60,
      },
    ],
  };
  settings.hooks.PreToolUse = [...filtered, newEntry];
  writeSettings(settings);
  console.log(`[install-hooks] ✓ ${SETTINGS_PATH} (PreToolUse entry merged)`);
  console.log("");
  console.log("Next steps:");
  console.log("  1. Start Claude Code (or restart any open session).");
  console.log("  2. In any session, run /ccwearos to bridge it to the watch.");
  console.log("  3. When Claude wants to run a tool, your watch will buzz.");
  console.log("  4. Tap Allow/Deny on the watch.");
  console.log("  5. Run /ccwearos-off to stop sharing.");
  console.log("");
  console.log("Leaving the Mac? /ccwearos-takeover opens a new Terminal");
  console.log("under `cc` with permission-mode=dontAsk (no double-confirm).");
}

function uninstall(): void {
  // 1. Remove slash commands (only if they're ours — match content by header).
  for (const name of SLASH_COMMAND_FILES) {
    const dst = join(COMMANDS_DIR, name);
    if (existsSync(dst)) {
      try {
        const content = readFileSync(dst, "utf8");
        if (content.includes("CCWEAROS") || content.includes("ccwearos")) {
          unlinkSync(dst);
          console.log(`[install-hooks] ✗ removed ${dst}`);
        }
      } catch {
        // best-effort
      }
    }
  }

  // 2. Drop our PreToolUse entry.
  const settings = readSettings();
  if (settings.hooks?.PreToolUse) {
    const before = settings.hooks.PreToolUse.length;
    settings.hooks.PreToolUse = (
      settings.hooks.PreToolUse as HookEntry[]
    ).filter((e) => e.ccwearosId !== HOOK_ID);
    if (settings.hooks.PreToolUse.length !== before) {
      writeSettings(settings);
      console.log(
        `[install-hooks] ✗ removed PreToolUse entry from ${SETTINGS_PATH}`,
      );
    }
  }
}

const arg = process.argv[2];
if (arg === "--uninstall" || arg === "uninstall") {
  uninstall();
} else {
  install();
}
