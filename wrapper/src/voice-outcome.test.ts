import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { resolveVoiceCwd } from "./config.js";
import {
  blockedDialogOutcome,
  finalizeVoiceOutcome,
  VOICE_ERROR_HEADLINE,
  VOICE_TRUST_HEADLINE,
} from "./voice-outcome.js";

const HOME = "/Users/me";
const words = (s: string): number => s.trim().split(/\s+/).length;

describe("blockedDialogOutcome", () => {
  it("trust → Spanish headline + exact fix with the folder, no raw text", () => {
    const o = blockedDialogOutcome(
      { kind: "trust", detail: "/Users/me/projects/x" },
      "/Users/me/.ccwearos/voice",
      HOME,
    );
    expect(o.headline).toBe(VOICE_TRUST_HEADLINE);
    expect(words(o.headline)).toBeLessThanOrEqual(18);
    expect(o.taskKind).toBe("info");
    expect(o.followups).toBeNull();
    expect(o.response).toContain("`/Users/me/.ccwearos/voice`");
    expect(o.response).toContain("Yes, I trust this folder");
    expect(o.response).toContain("cd ~/.ccwearos/voice && claude");
    expect(o.response).not.toMatch(/\x1b|❯|Enter to confirm/);
  });

  it("auth and other dialogs get their own short headlines", () => {
    for (const kind of ["auth", "other"] as const) {
      const o = blockedDialogOutcome({ kind, detail: "x" }, "/tmp/v", HOME);
      expect(words(o.headline)).toBeLessThanOrEqual(18);
      expect(o.taskKind).toBe("info");
      expect(o.followups).toBeNull();
    }
    expect(
      blockedDialogOutcome({ kind: "auth", detail: "" }, "/tmp/v", HOME).response,
    ).toContain("/login");
  });

  it("shell-quotes odd folder names in the shortcut", () => {
    const o = blockedDialogOutcome(
      { kind: "trust", detail: "" },
      "/Users/me/My Voice",
      HOME,
    );
    expect(o.response).toContain("cd ~/'My Voice' && claude");
  });
});

describe("finalizeVoiceOutcome", () => {
  const base = { cwd: "/tmp/v", home: HOME };

  it("blocked run → clean dialog outcome even with exit 0", () => {
    const o = finalizeVoiceOutcome({
      ...base,
      blocked: { kind: "trust", detail: "" },
      exitCode: 0,
      response: "",
    });
    expect(o?.headline).toBe(VOICE_TRUST_HEADLINE);
  });

  it("non-zero exit with nothing readable → Spanish error headline", () => {
    for (const response of ["", "(B", " ─ ❯ "]) {
      const o = finalizeVoiceOutcome({
        ...base,
        blocked: null,
        exitCode: 1,
        response,
      });
      expect(o?.headline).toBe(VOICE_ERROR_HEADLINE);
      expect(o?.response).toContain("código 1");
    }
  });

  it("normal path (null) for a real answer or a user Stop", () => {
    expect(
      finalizeVoiceOutcome({
        ...base,
        blocked: null,
        exitCode: 1,
        response: "Listo, moví 12 archivos.",
      }),
    ).toBeNull();
    expect(
      finalizeVoiceOutcome({ ...base, blocked: null, exitCode: 0, response: "" }),
    ).toBeNull();
    expect(
      finalizeVoiceOutcome({
        ...base,
        blocked: null,
        exitCode: 1,
        response: "",
        stopped: true,
      }),
    ).toBeNull();
  });
});

describe("resolveVoiceCwd", () => {
  const dirs: string[] = [];
  const tmp = (): string => {
    const d = mkdtempSync(join(tmpdir(), "ccwearos-cwd-"));
    dirs.push(d);
    return d;
  };
  afterEach(() => {
    for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true });
  });

  it("defaults to a dedicated folder (created), not the home directory", () => {
    const home = tmp();
    const def = join(home, ".ccwearos", "voice");
    const warns: string[] = [];
    const got = resolveVoiceCwd(undefined, {
      home,
      defaultDir: def,
      warn: (m) => warns.push(m),
    });
    expect(got).toBe(def);
    expect(got).not.toBe(home);
    expect(warns).toEqual([]);
  });

  it("accepts an existing directory, expanding ~", () => {
    const home = tmp();
    const got = resolveVoiceCwd("~", { home, defaultDir: join(home, "d") });
    expect(got).toBe(home);
    const abs = tmp();
    expect(resolveVoiceCwd(abs, { home, defaultDir: join(home, "d") })).toBe(abs);
  });

  it("falls back with a warning when the path is missing or a file", () => {
    const home = tmp();
    const def = join(home, "voice");
    const file = join(home, "f.txt");
    writeFileSync(file, "x");
    for (const bad of [join(home, "nope"), file]) {
      const warns: string[] = [];
      const got = resolveVoiceCwd(bad, {
        home,
        defaultDir: def,
        warn: (m) => warns.push(m),
      });
      expect(got).toBe(def);
      expect(warns).toHaveLength(1);
    }
  });
});
