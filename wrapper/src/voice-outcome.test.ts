import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { resolveVoiceCwd } from "./config.js";
import {
  blockedDialogOutcome,
  clearVoiceRunEnd,
  finalizeVoiceOutcome,
  nextHasPriorSession,
  publishVoiceRunEnd,
  VOICE_ERROR_HEADLINE,
  VOICE_TIMEOUT_HEADLINE,
  VOICE_TRUST_HEADLINE,
  voiceBlocker,
  voiceRunOutcome,
  decideContinuation,
  type VoiceRunSinks,
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

  it("timed-out run with nothing readable → timeout headline", () => {
    const o = finalizeVoiceOutcome({
      ...base,
      blocked: null,
      exitCode: 1,
      response: "",
      timedOut: true,
    });
    expect(o?.headline).toBe(VOICE_TIMEOUT_HEADLINE);
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

describe("voiceBlocker (/blocker contract)", () => {
  const base = { cwd: "/Users/me/.ccwearos/voice", home: HOME, response: "" };

  it("maps dialogs: trust (with cwd), auth → login, other", () => {
    const trust = voiceBlocker({
      ...base,
      blocked: { kind: "trust", detail: "" },
      exitCode: 1,
    });
    expect(trust?.kind).toBe("trust");
    expect(trust?.cwd).toBe("/Users/me/.ccwearos/voice");
    expect(trust?.hint).toContain("Yes, I trust this folder");
    const login = voiceBlocker({
      ...base,
      blocked: { kind: "auth", detail: "Invalid API key" },
      exitCode: 1,
    });
    expect(login).toEqual({ kind: "login", hint: expect.stringContaining("/login") });
    expect(
      voiceBlocker({ ...base, blocked: { kind: "other", detail: "X" }, exitCode: 1 })
        ?.kind,
    ).toBe("other");
  });

  it("timeout beats crash; crash needs non-zero exit and no readable text", () => {
    expect(
      voiceBlocker({ ...base, blocked: null, exitCode: 1, timedOut: true })?.kind,
    ).toBe("timeout");
    expect(voiceBlocker({ ...base, blocked: null, exitCode: 2 })?.kind).toBe(
      "crash",
    );
    expect(
      voiceBlocker({ ...base, blocked: null, exitCode: 2, response: "Hecho." }),
    ).toBeNull();
    expect(
      voiceBlocker({ ...base, blocked: null, exitCode: 1, stopped: true }),
    ).toBeNull();
    expect(voiceBlocker({ ...base, blocked: null, exitCode: 0 })).toBeNull();
  });
});

describe("voiceRunOutcome (/outcome contract)", () => {
  const base = { cwd: "/tmp/v", home: HOME, response: "ok listo" };
  it("ok only for exit 0 without a blocker; null exit → -1", () => {
    expect(voiceRunOutcome({ ...base, blocked: null, exitCode: 0 })).toEqual({
      ok: true,
      exitCode: 0,
    });
    expect(
      voiceRunOutcome({
        ...base,
        blocked: { kind: "trust", detail: "" },
        exitCode: 0,
      }).ok,
    ).toBe(false);
    expect(voiceRunOutcome({ ...base, blocked: null, exitCode: 1 }).ok).toBe(
      false,
    );
    expect(
      voiceRunOutcome({ ...base, blocked: null, exitCode: null }).exitCode,
    ).toBe(-1);
    // No `stopped` key at all unless the user stopped the run.
    expect(voiceRunOutcome({ ...base, blocked: null, exitCode: 1 })).not.toHaveProperty(
      "stopped",
    );
  });

  it("an is_error result is not ok even with exit 0", () => {
    expect(
      voiceRunOutcome({ ...base, blocked: null, exitCode: 0, isError: true }),
    ).toEqual({ ok: false, exitCode: 0 });
  });

  it("user stop: ok:false + stopped:true, whatever the exit code", () => {
    for (const exitCode of [130, 143, 0, null]) {
      expect(
        voiceRunOutcome({ ...base, blocked: null, exitCode, stopped: true }),
      ).toEqual({ ok: false, exitCode: exitCode ?? -1, stopped: true });
    }
  });
});

describe("nextHasPriorSession (/conversationActive)", () => {
  it("a clean run starts or extends a thread", () => {
    expect(
      nextHasPriorSession({ hadPrior: false, reset: false, exitCode: 0, blocked: false }),
    ).toBe(true);
  });
  it("a blocked or failed run keeps the previous state", () => {
    expect(
      nextHasPriorSession({ hadPrior: false, reset: false, exitCode: 0, blocked: true }),
    ).toBe(false);
    expect(
      nextHasPriorSession({ hadPrior: true, reset: false, exitCode: 1, blocked: false }),
    ).toBe(true);
  });
  it("a reset that failed still drops the old thread", () => {
    expect(
      nextHasPriorSession({ hadPrior: true, reset: true, exitCode: 1, blocked: false }),
    ).toBe(false);
  });
});

describe("clearVoiceRunEnd / publishVoiceRunEnd", () => {
  function sinks() {
    const calls: [string, unknown][] = [];
    const rec =
      (name: string) =>
      async (v: unknown): Promise<void> => {
        calls.push([name, v]);
      };
    const s: VoiceRunSinks = {
      setBlocker: rec("blocker"),
      setOutcome: rec("outcome"),
      setResponse: rec("response"),
      setTaskKind: rec("taskKind"),
      setHeadline: rec("headline"),
      setFollowups: rec("followups"),
    };
    return { s, calls };
  }

  it("run start clears /blocker and /outcome", async () => {
    const { s, calls } = sinks();
    await clearVoiceRunEnd(s);
    expect(calls).toEqual([
      ["blocker", null],
      ["outcome", null],
    ]);
  });

  it("blocked run: blocker, fallback text, outcome last; normal path skipped", async () => {
    const { s, calls } = sinks();
    let normal = false;
    const replaced = await publishVoiceRunEnd(
      {
        blocked: { kind: "trust", detail: "" },
        exitCode: 1,
        response: "",
        cwd: "/tmp/v",
        home: HOME,
      },
      s,
      async () => {
        normal = true;
      },
      1234,
    );
    expect(replaced).toBe(true);
    expect(normal).toBe(false);
    expect(calls.map((c) => c[0])).toEqual([
      "blocker",
      "response",
      "taskKind",
      "headline",
      "followups",
      "outcome",
    ]);
    expect(calls[0]?.[1]).toMatchObject({ kind: "trust", cwd: "/tmp/v", ts: 1234 });
    expect(calls.at(-1)?.[1]).toEqual({ ok: false, exitCode: 1, ts: 1234 });
  });

  it("clean run: no blocker, normal path, ok outcome", async () => {
    const { s, calls } = sinks();
    let normal = false;
    const replaced = await publishVoiceRunEnd(
      { blocked: null, exitCode: 0, response: "Listo", cwd: "/tmp/v", home: HOME },
      s,
      async () => {
        normal = true;
      },
      5,
    );
    expect(replaced).toBe(false);
    expect(normal).toBe(true);
    expect(calls).toEqual([["outcome", { ok: true, exitCode: 0, ts: 5 }]]);
  });

  it("stopped run: no blocker, normal path, outcome carries stopped:true", async () => {
    const { s, calls } = sinks();
    let normal = false;
    await publishVoiceRunEnd(
      { blocked: null, exitCode: 130, response: "", cwd: "/tmp/v", home: HOME, stopped: true },
      s,
      async () => {
        normal = true;
      },
      9,
    );
    expect(normal).toBe(true);
    expect(calls).toEqual([
      ["outcome", { ok: false, exitCode: 130, stopped: true, ts: 9 }],
    ]);
  });

  it("crash and timeout publish their blockers", async () => {
    for (const [facts, kind] of [
      [{ exitCode: 1 }, "crash"],
      [{ exitCode: 1, timedOut: true }, "timeout"],
    ] as const) {
      const { s, calls } = sinks();
      await publishVoiceRunEnd(
        { blocked: null, response: "", cwd: "/tmp/v", home: HOME, ...facts },
        s,
        async () => {},
      );
      expect(calls[0]?.[0]).toBe("blocker");
      expect((calls[0]?.[1] as { kind: string }).kind).toBe(kind);
    }
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

describe("decideContinuation", () => {
  it("mode new never continues, even with a prior thread", () => {
    expect(decideContinuation({ hadPrior: true, mode: "new", resetPhrase: false })).toEqual({ shouldContinue: false, reset: true });
    expect(decideContinuation({ hadPrior: false, mode: "new", resetPhrase: false })).toEqual({ shouldContinue: false, reset: false });
  });
  it("mode continue resumes only when there is a thread", () => {
    expect(decideContinuation({ hadPrior: true, mode: "continue", resetPhrase: true })).toEqual({ shouldContinue: true, reset: false });
    expect(decideContinuation({ hadPrior: false, mode: "continue", resetPhrase: false })).toEqual({ shouldContinue: false, reset: false });
  });
  it("no mode keeps the legacy reset-phrase behaviour", () => {
    expect(decideContinuation({ hadPrior: true, mode: undefined, resetPhrase: false })).toEqual({ shouldContinue: true, reset: false });
    expect(decideContinuation({ hadPrior: true, mode: undefined, resetPhrase: true })).toEqual({ shouldContinue: false, reset: true });
  });
});
