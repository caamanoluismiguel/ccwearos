import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Fake node-pty: the test drives onData/onExit by hand.
interface FakePty {
  write: ReturnType<typeof vi.fn>;
  kill: ReturnType<typeof vi.fn>;
  onData: (cb: (d: string) => void) => void;
  onExit: (cb: (e: { exitCode: number; signal?: number }) => void) => void;
  emit: (d: string) => void;
  exit: (code: number) => void;
}
let fake: FakePty;
const spawnMock = vi.fn();

vi.mock("node-pty", () => ({
  spawn: (...args: unknown[]) => {
    spawnMock(...args);
    return fake;
  },
}));

function makeFake(): FakePty {
  let dataCb: (d: string) => void = () => {};
  let exitCb: (e: { exitCode: number }) => void = () => {};
  let exited = false;
  const exit = (code: number): void => {
    if (exited) return;
    exited = true;
    exitCb({ exitCode: code });
  };
  return {
    write: vi.fn(),
    kill: vi.fn(() => exit(1)),
    onData: (cb) => {
      dataCb = cb;
    },
    onExit: (cb) => {
      exitCb = cb;
    },
    emit: (d) => dataCb(d),
    exit,
  };
}

const trustFixture = readFileSync(
  join(__dirname, "..", "fixtures", "dialogs", "trust.txt"),
  "utf8",
);

function callbacks() {
  return {
    onStatus: vi.fn(),
    onMetrics: vi.fn(),
    onPermission: vi.fn(),
    onActivity: vi.fn(),
    onTask: vi.fn(),
    onResponse: vi.fn(),
    onClaudeStatus: vi.fn(),
    onToolEvents: vi.fn(),
  };
}

describe("runClaudeForVoice", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    fake = makeFake();
    spawnMock.mockClear();
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("spawns in the voice cwd, not the wrapper directory", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    runClaudeForVoice("hola", callbacks(), { cwd: "/tmp/voice-here" });
    const opts = spawnMock.mock.calls[0]?.[2] as { cwd: string };
    expect(opts.cwd).toBe("/tmp/voice-here");
    fake.exit(0);
  });

  it("blocked by the trust dialog: never answers it, kills, publishes no raw text", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const cb = callbacks();
    const runner = runClaudeForVoice("PROMPT", cb, { cwd: "/tmp/v" });

    for (let i = 0; i < trustFixture.length; i += 200) {
      fake.emit(trustFixture.slice(i, i + 200));
    }
    // Warm-up + submit delay pass: the prompt must never be typed into the
    // dialog (its \r could pick "Yes, I trust this folder").
    await vi.advanceTimersByTimeAsync(10_000);

    const result = await runner.done;
    expect(fake.kill).toHaveBeenCalled();
    expect(fake.write).not.toHaveBeenCalled();
    expect(result.blocked).toEqual({
      kind: "trust",
      detail: "/Users/luismiguelcaamano/projects/CCWEAROS/wrapper",
    });
    expect(cb.onResponse).not.toHaveBeenCalled();
    expect(cb.onPermission).not.toHaveBeenCalled();
    expect(console.warn).toHaveBeenCalled();

    const { finalizeVoiceOutcome } = await import("./voice-outcome.js");
    const o = finalizeVoiceOutcome({
      blocked: result.blocked,
      exitCode: result.exitCode,
      response: "",
      cwd: "/tmp/v",
    });
    expect(o?.headline).toBe(
      "Claude necesita que confíes en la carpeta en tu Mac",
    );
    expect(o?.response).toContain("/tmp/v");
    expect(o?.response).not.toMatch(/\x1b|❯|Esc to cancel/);
  });

  it("stops a run that exceeds its time cap and reports timedOut", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("PROMPT", callbacks(), {
      cwd: "/tmp/v",
      maxRunMs: 60_000,
    });
    // Keep output flowing so idle detection never ends the run first.
    for (let t = 0; t < 70; t++) {
      fake.emit(`tick ${t}\r\n`);
      await vi.advanceTimersByTimeAsync(1_000);
    }
    const result = await runner.done;
    expect(fake.write).toHaveBeenCalledWith("/exit\r");
    expect(fake.kill).toHaveBeenCalled();
    expect(result.timedOut).toBe(true);
    expect(result.blocked).toBeNull();
  });

  it("normal run: types the prompt after warm-up and reports blocked=null", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const cb = callbacks();
    const runner = runClaudeForVoice("PROMPT", cb, { cwd: "/tmp/v" });
    fake.emit("Welcome back!\r\n");
    await vi.advanceTimersByTimeAsync(5_400);
    expect(fake.write).toHaveBeenCalledWith("PROMPT");
    expect(fake.write).toHaveBeenCalledWith("\r");
    fake.emit("⏺ Listo,\x1b[1Cmoví\x1b[1C12\x1b[1Carchivos.\r\n");
    fake.exit(0);
    const result = await runner.done;
    expect(result.blocked).toBeNull();
    expect(result.timedOut).toBe(false);
    expect(cb.onResponse).toHaveBeenLastCalledWith(
      expect.stringContaining("Listo, moví 12 archivos."),
    );
  });
});
