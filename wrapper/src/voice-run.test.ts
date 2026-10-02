import { describe, expect, it } from "vitest";
import {
  DEFAULT_VOICE_HOOK_WAIT_MS,
  hookMode,
  voiceHookSettings,
  voiceHookWaitMs,
  voiceToolNeedsWatch,
} from "./voice-run.js";

describe("hookMode", () => {
  it("outside a voice run the hook keeps its /ccwearos share behaviour", () => {
    expect(hookMode({})).toBe("share");
    // The role alone (no voice run) means nothing.
    expect(hookMode({ CCWEAROS_HOOK_ROLE: "voice" })).toBe("share");
  });

  it("inside a voice run only the voice entry acts; the global one passes", () => {
    expect(hookMode({ CCWEAROS_VOICE_RUN: "1", CCWEAROS_HOOK_ROLE: "voice" })).toBe("voice");
    expect(hookMode({ CCWEAROS_VOICE_RUN: "1" })).toBe("voice-duplicate");
  });
});

describe("voiceHookWaitMs", () => {
  it("defaults to 120s and clamps silly values", () => {
    expect(voiceHookWaitMs({})).toBe(DEFAULT_VOICE_HOOK_WAIT_MS);
    expect(DEFAULT_VOICE_HOOK_WAIT_MS).toBe(120_000);
    expect(voiceHookWaitMs({ CCWEAROS_VOICE_HOOK_WAIT_MS: "abc" })).toBe(120_000);
    expect(voiceHookWaitMs({ CCWEAROS_VOICE_HOOK_WAIT_MS: "90000" })).toBe(90_000);
    expect(voiceHookWaitMs({ CCWEAROS_VOICE_HOOK_WAIT_MS: "5" })).toBe(10_000);
    expect(voiceHookWaitMs({ CCWEAROS_VOICE_HOOK_WAIT_MS: "9999999" })).toBe(590_000);
  });
});

describe("voiceHookSettings", () => {
  it("one PreToolUse entry for every tool, role-tagged, quoted, timeout > wait", () => {
    const s = JSON.parse(
      voiceHookSettings({
        tsxBin: "/Users/me/My Repo/node_modules/.bin/tsx",
        hookScript: "/Users/me/My Repo/scripts/hooks/pre-tool-use.ts",
        waitMs: 120_000,
      }),
    );
    const entry = s.hooks.PreToolUse[0];
    expect(entry.matcher).toBe("*");
    expect(entry.hooks[0]).toEqual({
      type: "command",
      command:
        "CCWEAROS_HOOK_ROLE=voice '/Users/me/My Repo/node_modules/.bin/tsx' '/Users/me/My Repo/scripts/hooks/pre-tool-use.ts'",
      timeout: 150,
    });
  });
});

describe("voiceToolNeedsWatch", () => {
  const cwd = "/Users/me/.ccwearos/voice";

  it("anything that changes the Mac or reaches the network asks", () => {
    for (const t of ["Bash", "Edit", "Write", "MultiEdit", "WebFetch", "WebSearch", "mcp__x__y"]) {
      expect(voiceToolNeedsWatch(t, { file_path: `${cwd}/a` }, cwd)).toBe(true);
    }
  });

  it("bookkeeping tools never ask", () => {
    expect(voiceToolNeedsWatch("TodoWrite", {}, cwd)).toBe(false);
    expect(voiceToolNeedsWatch("Task", { description: "x" }, cwd)).toBe(false);
  });

  it("read-only tools defer inside the voice cwd and ask outside it", () => {
    expect(voiceToolNeedsWatch("Read", { file_path: `${cwd}/notes.md` }, cwd)).toBe(false);
    expect(voiceToolNeedsWatch("Grep", { pattern: "x" }, cwd)).toBe(false); // defaults to cwd
    expect(voiceToolNeedsWatch("Glob", { pattern: "*", path: "sub" }, cwd)).toBe(false);
    expect(voiceToolNeedsWatch("Read", { file_path: "/Users/me/.ssh/id_rsa" }, cwd)).toBe(true);
    expect(voiceToolNeedsWatch("Read", { file_path: `${cwd}/../../.ssh/id_rsa` }, cwd)).toBe(true);
    expect(voiceToolNeedsWatch("Glob", { pattern: "*", path: "~/Downloads" }, cwd)).toBe(true);
    expect(voiceToolNeedsWatch("Read", {}, cwd)).toBe(true);
    expect(voiceToolNeedsWatch("Read", { file_path: `${cwd}/a` }, undefined)).toBe(true);
  });
});
