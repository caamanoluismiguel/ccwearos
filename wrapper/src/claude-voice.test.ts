import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Fake child process: the test feeds stdout lines and ends it by hand.
interface FakeChild extends EventEmitter {
  stdout: EventEmitter;
  stderr: EventEmitter;
  kill: ReturnType<typeof vi.fn>;
  signals: string[];
}
let child: FakeChild;
const spawnMock = vi.fn();

vi.mock("node:child_process", () => ({
  spawn: (...args: unknown[]) => {
    spawnMock(...args);
    return child;
  },
}));
// The real store persists token totals to disk.
vi.mock("./metrics-store.js", () => ({
  createMetricsStore: () => {
    let total = 0;
    return {
      add: (n: number) => {
        total += n;
      },
      persist: () => {},
      snapshot: () => ({
        dailyTokens: total,
        weeklyTokens: total,
        monthlyTokens: total,
        updatedAt: 0,
      }),
    };
  },
}));

function makeChild(): FakeChild {
  const c = new EventEmitter() as FakeChild;
  c.stdout = new EventEmitter();
  c.stderr = new EventEmitter();
  c.signals = [];
  c.kill = vi.fn((sig: string) => {
    c.signals.push(sig);
    return true;
  });
  return c;
}

const fixture = (name: string): string =>
  readFileSync(join(__dirname, "..", "fixtures", "stream-json", name), "utf8");

// Feed a fixture in uneven chunks so JSON lines straddle chunk boundaries.
function feed(text: string): void {
  for (let i = 0; i < text.length; i += 97) {
    child.stdout.emit("data", Buffer.from(text.slice(i, i + 97)));
  }
}

function callbacks() {
  return {
    onMetrics: vi.fn(),
    onActivity: vi.fn(),
    onResponse: vi.fn(),
    onClaudeStatus: vi.fn(),
    onToolEvents: vi.fn(),
  };
}

describe("runClaudeForVoice (claude -p stream-json)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    child = makeChild();
    spawnMock.mockClear();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(process.stderr, "write").mockImplementation(() => true);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("passes the prompt as argv (nothing typed), in the voice cwd, with the voice env", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    runClaudeForVoice("Contexto: x · hola", callbacks(), {
      cwd: "/tmp/voice-here",
      continueSession: true,
      runId: "run-1",
    });
    const [cmd, argv, opts] = spawnMock.mock.calls[0] as [
      string,
      string[],
      { cwd: string; stdio: unknown[]; env: Record<string, string> },
    ];
    expect(cmd).toBe("/bin/sh");
    expect(argv[1]).toMatch(/^exec .+ "\$@"$/);
    const args = argv.slice(3);
    expect(args.slice(0, 4)).toEqual(["-p", "--output-format", "stream-json", "--verbose"]);
    expect(args).toContain("--continue");
    expect(args.at(-1)).toBe("Contexto: x · hola");
    const settings = JSON.parse(args[args.indexOf("--settings") + 1] ?? "{}");
    expect(settings.hooks.PreToolUse[0].hooks[0].command).toMatch(
      /^CCWEAROS_HOOK_ROLE=voice '.+tsx' '.+pre-tool-use\.ts'$/,
    );
    expect(opts.cwd).toBe("/tmp/voice-here");
    expect(opts.stdio[0]).toBe("ignore"); // no stdin: nothing can be typed
    expect(opts.env["CCWEAROS_VOICE_RUN"]).toBe("1");
    expect(opts.env["CCWEAROS_VOICE_RUN_ID"]).toBe("run-1");
    expect(Number(opts.env["CCWEAROS_VOICE_HOOK_WAIT_MS"])).toBeGreaterThan(0);
    child.emit("close", 0, null);
  });

  it("first prompt of a thread omits --continue", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    expect((spawnMock.mock.calls[0]?.[1] as string[])).not.toContain("--continue");
    child.emit("close", 0, null);
  });

  it("info answer: final text comes verbatim from the result event", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const cb = callbacks();
    const runner = runClaudeForVoice("Contexto: x", cb, { cwd: "/tmp/v" });
    feed(fixture("info-answer.jsonl"));
    child.emit("close", 0, null);
    const r = await runner.done;
    expect(r.exitCode).toBe(0);
    expect(r.blocked).toBeNull();
    expect(r.stopped).toBe(false);
    expect(r.toolEvents).toEqual([]);
    expect(r.result?.isError).toBe(false);
    expect(r.result?.numTurns).toBe(1);
    expect(r.result?.text.startsWith("**TL;DR:** Un pty es una terminal falsa")).toBe(true);
    expect(r.result?.text).toContain("Sugerencias:\n- ¿Qué es node-pty?");
    expect(cb.onClaudeStatus).toHaveBeenLastCalledWith(
      expect.objectContaining({ model: "Opus 4.7", contextSize: "1M" }),
    );
    expect(cb.onMetrics).toHaveBeenCalledWith(
      expect.objectContaining({ dailyTokens: 12 + 2048 + 10240 + 64 }),
    );
    expect(cb.onActivity).toHaveBeenLastCalledWith(null);
  });

  it("tool run: Bash + Edit become tool events and Spanish activity, live", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const cb = callbacks();
    const runner = runClaudeForVoice("Contexto: x", cb, { cwd: "/tmp/v" });
    const lines = fixture("tool-run.jsonl").trimEnd().split("\n");
    feed(lines.slice(0, -1).join("\n") + "\n");
    expect(cb.onActivity).toHaveBeenCalledWith("Ejecutando un comando");
    expect(cb.onActivity).toHaveBeenCalledWith("Editando parser.ts");
    // Live preview of the assistant's text (debounced).
    await vi.advanceTimersByTimeAsync(1_000);
    expect(cb.onResponse).toHaveBeenLastCalledWith(
      "Listo, moví 11 archivos a Documentos/Facturas y actualicé parser.ts.",
    );
    feed(`${lines.at(-1)}\n`);
    child.emit("close", 0, null);
    const r = await runner.done;
    expect(r.toolEvents.map((e) => [e.tool, e.arg])).toEqual([
      ["Bash", "ls -la ~/Downloads"],
      ["Edit", "/Users/me/projects/CCWEAROS/wrapper/src/parser.ts"],
    ]);
    expect(cb.onToolEvents).toHaveBeenLastCalledWith(r.toolEvents);
    expect(r.result?.text).toBe(
      "Listo, moví 11 archivos a Documentos/Facturas y actualicé parser.ts.",
    );
    expect(r.result?.numTurns).toBe(3);
  });

  it("publishes /progress: step 0 at spawn, each tool step, nothing after done", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const onProgress = vi.fn();
    const runner = runClaudeForVoice("Contexto: x", { ...callbacks(), onProgress }, { cwd: "/tmp/v" });
    expect(onProgress).toHaveBeenCalledTimes(1);
    expect(onProgress.mock.calls[0]?.[0]).toMatchObject({ step: 0, label: "Pensando" });
    const lines = fixture("tool-run.jsonl").trimEnd().split("\n");
    feed(lines.slice(0, -1).join("\n") + "\n");
    const snaps = onProgress.mock.calls.map((c) => c[0] as { step: number; label: string; intent?: string });
    expect(snaps.find((p) => p.step === 1)).toMatchObject({
      label: "Buscando archivos",
      detail: "~/Downloads",
      intent: "Voy a revisar tu carpeta de descargas.",
    });
    expect(snaps.find((p) => p.step === 2)).toMatchObject({ label: "Editando parser.ts" });
    feed(`${lines.at(-1)}\n`);
    child.emit("close", 0, null);
    await runner.done;
    const n = onProgress.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(onProgress).toHaveBeenCalledTimes(n);
  });

  it("auth error: is_error result → blocked kind auth", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    feed(fixture("auth-error.jsonl"));
    child.emit("close", 1, null);
    const r = await runner.done;
    expect(r.result?.isError).toBe(true);
    expect(r.blocked).toEqual({
      kind: "auth",
      detail: "Invalid API key · Please run /login",
    });
  });

  it("permission denied from the watch: a normal answer, the denial counted", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    feed(fixture("permission-denied.jsonl"));
    child.emit("close", 0, null);
    const r = await runner.done;
    expect(r.blocked).toBeNull();
    expect(r.result?.isError).toBe(false);
    expect(r.result?.permissionDenials).toBe(1);
    expect(r.result?.text).toContain("rechazaste el permiso");
  });

  it("crash with no result event: result null, non-zero exit", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    feed(fixture("crash-no-result.jsonl"));
    child.stderr.emit("data", Buffer.from("Error: something exploded\n"));
    child.emit("close", 1, null);
    const r = await runner.done;
    expect(r.result).toBeNull();
    expect(r.exitCode).toBe(1);
    expect(r.blocked).toBeNull();

    const { voiceBlocker } = await import("./voice-outcome.js");
    expect(
      voiceBlocker({ blocked: r.blocked, exitCode: r.exitCode, response: "", cwd: "/tmp/v" })
        ?.kind,
    ).toBe("crash");
  });

  it("logged out with no result event: auth detected from stderr", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    child.stderr.emit("data", Buffer.from("Not logged in · Please run /login\n"));
    child.emit("close", 1, null);
    expect((await runner.done).blocked?.kind).toBe("auth");
  });

  it("user stop: SIGINT, then SIGTERM after the grace period; stopped=true", async () => {
    const { runClaudeForVoice, STOP_GRACE_MS } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    feed(fixture("user-stop.jsonl"));
    runner.stop();
    expect(child.signals).toEqual(["SIGINT"]);
    await vi.advanceTimersByTimeAsync(STOP_GRACE_MS + 10);
    expect(child.signals).toEqual(["SIGINT", "SIGTERM"]);
    child.emit("close", null, "SIGTERM");
    const r = await runner.done;
    expect(r.stopped).toBe(true);
    expect(r.result).toBeNull();
    expect(r.exitCode).toBe(143); // 128 + SIGTERM

    const { voiceRunOutcome, voiceBlocker } = await import("./voice-outcome.js");
    const facts = {
      blocked: r.blocked,
      exitCode: r.exitCode,
      response: "",
      cwd: "/tmp/v",
      stopped: r.stopped,
    };
    expect(voiceBlocker(facts)).toBeNull(); // a stop is not a crash
    expect(voiceRunOutcome(facts)).toEqual({ ok: false, exitCode: 143, stopped: true });
  });

  it("a stop that exits quickly sends nothing more", async () => {
    const { runClaudeForVoice, STOP_GRACE_MS } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    runner.stop();
    child.emit("close", null, "SIGINT");
    await vi.advanceTimersByTimeAsync(STOP_GRACE_MS * 3);
    expect(child.signals).toEqual(["SIGINT"]);
    expect((await runner.done).exitCode).toBe(130);
  });

  it("time cap: SIGTERM, timedOut=true", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), {
      cwd: "/tmp/v",
      maxRunMs: 60_000,
    });
    await vi.advanceTimersByTimeAsync(60_010);
    expect(child.signals).toEqual(["SIGTERM"]);
    child.emit("close", null, "SIGTERM");
    const r = await runner.done;
    expect(r.timedOut).toBe(true);
    expect(r.stopped).toBe(false);
  });

  it("spawn error resolves with exitCode null", async () => {
    const { runClaudeForVoice } = await import("./claude-voice.js");
    const runner = runClaudeForVoice("Contexto: x", callbacks(), { cwd: "/tmp/v" });
    child.emit("error", new Error("spawn /bin/sh ENOENT"));
    const r = await runner.done;
    expect(r.exitCode).toBeNull();
    expect(r.result).toBeNull();
  });
});
