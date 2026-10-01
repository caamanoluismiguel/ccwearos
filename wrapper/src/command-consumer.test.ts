import { describe, expect, it } from "vitest";
import {
  ActivePrompt,
  consumeCommand,
  decideCommand,
  type CommandSink,
} from "./command-consumer.js";
import type { PendingCommand } from "./types/schema.js";

const NOW = 1_000_000;
const OPTS = { now: NOW, maxAgeSeconds: 60 };

function fakeSink(): CommandSink & {
  log: string[];
  written: string[];
  warnings: string[];
} {
  const log: string[] = [];
  const written: string[] = [];
  const warnings: string[] = [];
  return {
    log,
    written,
    warnings,
    clearCommand: async () => {
      log.push("clearCommand");
    },
    clearPrompt: async () => {
      log.push("clearPrompt");
    },
    answer: (bytes) => {
      written.push(bytes);
      log.push("answer");
    },
    stop: () => {
      log.push("stop");
    },
    warn: (m) => {
      warnings.push(m);
    },
  };
}

async function promptWithId(id: string): Promise<ActivePrompt> {
  const p = new ActivePrompt();
  await p.publish(async () => id);
  return p;
}

const cmd = (over: Partial<PendingCommand>): PendingCommand => ({
  text: "1\r",
  issuedAt: NOW,
  ...over,
});

describe("decideCommand", () => {
  it("rejects stale commands before anything else", () => {
    const o = decideCommand(cmd({ issuedAt: NOW - 61_000, promptId: "a" }), "a", NOW, 60);
    expect(o.kind).toBe("rejected");
  });
  it("maps both deny forms to ESC", () => {
    for (const text of ["", "\x1b"]) {
      expect(decideCommand(cmd({ text, promptId: "a" }), "a", NOW, 60)).toEqual({
        kind: "answer",
        bytes: "\x1b",
        decision: "deny",
      });
    }
  });
  it("passes allow bytes through", () => {
    expect(decideCommand(cmd({ promptId: "a" }), "a", NOW, 60)).toEqual({
      kind: "answer",
      bytes: "1\r",
      decision: "allow",
    });
  });
});

describe("consumeCommand", () => {
  it("rejects arbitrary text: never written, command cleared, warned", async () => {
    const sink = fakeSink();
    const p = await promptWithId("a");
    const o = await consumeCommand(cmd({ text: "!rm -rf ~\r", promptId: "a" }), p, OPTS, sink);
    expect(o).toEqual({ kind: "rejected", reason: "not-allowed" });
    expect(sink.written).toHaveLength(0);
    expect(sink.log).toEqual(["clearCommand"]);
    expect(sink.warnings[0]).toMatch(/not-allowed/);
    // The prompt is still answerable afterwards.
    expect(await p.current()).toBe("a");
  });

  it("rejects an answer with a mismatched prompt id", async () => {
    const sink = fakeSink();
    const o = await consumeCommand(cmd({ promptId: "old" }), await promptWithId("new"), OPTS, sink);
    expect(o).toEqual({ kind: "rejected", reason: "prompt-mismatch" });
    expect(sink.written).toHaveLength(0);
  });

  it("rejects an answer when no prompt is active", async () => {
    const sink = fakeSink();
    const o = await consumeCommand(cmd({ promptId: "a" }), new ActivePrompt(), OPTS, sink);
    expect(o).toEqual({ kind: "rejected", reason: "no-active-prompt" });
    expect(sink.written).toHaveLength(0);
  });

  it("rejects a stale answer even with the right id", async () => {
    const sink = fakeSink();
    const o = await consumeCommand(
      cmd({ promptId: "a", issuedAt: NOW - 120_000 }),
      await promptWithId("a"),
      OPTS,
      sink,
    );
    expect(o.kind).toBe("rejected");
    expect(sink.written).toHaveLength(0);
  });

  it("accepted answer: writes, clears command + prompt, consumes the id", async () => {
    const sink = fakeSink();
    const p = await promptWithId("a");
    const o = await consumeCommand(cmd({ promptId: "a" }), p, OPTS, sink);
    expect(o.kind).toBe("answer");
    expect(sink.written).toEqual(["1\r"]);
    expect(sink.log).toEqual(["answer", "clearCommand", "clearPrompt"]);
    expect(await p.current()).toBeNull();
    // A replay of the same tap is now rejected.
    const replay = fakeSink();
    const o2 = await consumeCommand(cmd({ promptId: "a" }), p, OPTS, replay);
    expect(o2.kind).toBe("rejected");
    expect(replay.written).toHaveLength(0);
  });

  it("stop passes without a prompt and runs the stop hook", async () => {
    const sink = fakeSink();
    const o = await consumeCommand(cmd({ text: "\x03" }), new ActivePrompt(), OPTS, sink);
    expect(o.kind).toBe("stop");
    expect(sink.log).toEqual(["clearCommand", "stop"]);
    expect(sink.written).toHaveLength(0);
  });

  it("waits for an in-flight publish before checking the id", async () => {
    const p = new ActivePrompt();
    let resolveId!: (id: string) => void;
    void p.publish(() => new Promise<string>((r) => (resolveId = r)));
    const sink = fakeSink();
    const pending = consumeCommand(cmd({ promptId: "late" }), p, OPTS, sink);
    resolveId("late");
    expect((await pending).kind).toBe("answer");
  });
});

describe("ActivePrompt", () => {
  it("a publish resolving after clear() does not resurrect the id", async () => {
    const p = new ActivePrompt();
    let resolveId!: (id: string) => void;
    const pub = p.publish(() => new Promise<string>((r) => (resolveId = r)));
    p.clear();
    resolveId("x");
    await pub;
    expect(await p.current()).toBeNull();
  });
  it("publish errors leave no active id and are reported", async () => {
    const p = new ActivePrompt();
    const errors: unknown[] = [];
    await p.publish(async () => {
      throw new Error("net");
    }, (e) => errors.push(e));
    expect(await p.current()).toBeNull();
    expect(errors).toHaveLength(1);
  });
});
