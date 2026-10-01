import { describe, expect, it } from "vitest";
import {
  ActivePrompt,
  consumeCommand,
  decideCommand,
  releaseActivePrompt,
  type CommandSink,
} from "./command-consumer.js";
import { clearPromptIf, type PromptStoreBackend } from "./prompt-store.js";
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
    clearPrompt: async (id) => {
      log.push(`clearPrompt:${id}`);
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
    expect(sink.log).toEqual(["answer", "clearCommand", "clearPrompt:a"]);
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

  it("two commands awaiting the same pending publish: first wins (claim-once)", async () => {
    const p = new ActivePrompt();
    let resolveId!: (id: string) => void;
    void p.publish(() => new Promise<string>((r) => (resolveId = r)));
    const s1 = fakeSink();
    const s2 = fakeSink();
    const a = consumeCommand(cmd({ promptId: "x" }), p, OPTS, s1);
    const b = consumeCommand(cmd({ promptId: "x" }), p, OPTS, s2);
    resolveId("x");
    const [o1, o2] = await Promise.all([a, b]);
    expect(o1.kind).toBe("answer");
    expect(o2.kind).toBe("rejected");
    expect([...s1.written, ...s2.written]).toEqual(["1\r"]);
  });

  it("consumes the local id before writing to the pty", async () => {
    const p = await promptWithId("a");
    const sink = fakeSink();
    let idDuringWrite: string | null = "unset";
    sink.answer = () => {
      idDuringWrite = p.peek();
    };
    await consumeCommand(cmd({ promptId: "a" }), p, OPTS, sink);
    expect(idDuringWrite).toBeNull();
  });
});

// Fake RTDB with just the two prompt paths and in-order writes.
function fakeStore() {
  let n = 0;
  const state: { id: string | null; text: string | null } = { id: null, text: null };
  const backend: PromptStoreBackend = {
    casClearId: async (expected) => {
      if (state.id !== expected && state.id !== null) return false;
      state.id = null;
      return true;
    },
    clearText: async () => {
      state.text = null;
    },
  };
  return {
    state,
    backend,
    publish: async (text: string) => {
      const id = `id${++n}`;
      state.id = id;
      state.text = text;
      return id;
    },
  };
}

describe("answering prompt N never wipes prompt N+1", () => {
  it("N+1 published while answering N (before the clear) survives", async () => {
    const store = fakeStore();
    const p = new ActivePrompt();
    await p.publish(() => store.publish("N"));
    const sink = fakeSink();
    sink.clearPrompt = (id, superseded) => clearPromptIf(store.backend, id, superseded);
    // Claude reacts to our bytes by raising the next dialog immediately.
    sink.answer = async () => {
      await p.publish(() => store.publish("N+1"));
    };
    const o = await consumeCommand(cmd({ promptId: "id1" }), p, OPTS, sink);
    expect(o).toMatchObject({ kind: "answer", newerPrompt: true });
    expect(store.state).toEqual({ id: "id2", text: "N+1" });
    expect(await p.current()).toBe("id2");
  });

  it("N+1 published between the id clear and the text clear survives", async () => {
    const store = fakeStore();
    const p = new ActivePrompt();
    await p.publish(() => store.publish("N"));
    const backend: PromptStoreBackend = {
      casClearId: async (expected) => {
        const ok = await store.backend.casClearId(expected);
        await p.publish(() => store.publish("N+1"));
        return ok;
      },
      clearText: store.backend.clearText,
    };
    const sink = fakeSink();
    sink.clearPrompt = (id, superseded) => clearPromptIf(backend, id, superseded);
    await consumeCommand(cmd({ promptId: "id1" }), p, OPTS, sink);
    expect(store.state).toEqual({ id: "id2", text: "N+1" });
  });

  it("without a newer prompt, answering N clears both paths", async () => {
    const store = fakeStore();
    const p = new ActivePrompt();
    await p.publish(() => store.publish("N"));
    const sink = fakeSink();
    sink.clearPrompt = (id, superseded) => clearPromptIf(store.backend, id, superseded);
    const o = await consumeCommand(cmd({ promptId: "id1" }), p, OPTS, sink);
    expect(o).toMatchObject({ kind: "answer", newerPrompt: false });
    expect(store.state).toEqual({ id: null, text: null });
  });
});

describe("releaseActivePrompt (answered in the terminal)", () => {
  it("drops the local prompt and clears RTDB; a late watch tap is rejected", async () => {
    const store = fakeStore();
    const p = new ActivePrompt();
    await p.publish(() => store.publish("N"));
    const released = await releaseActivePrompt(p, (id, s) =>
      clearPromptIf(store.backend, id, s),
    );
    expect(released).toBe(true);
    expect(store.state).toEqual({ id: null, text: null });
    const late = fakeSink();
    const o = await consumeCommand(cmd({ promptId: "id1" }), p, OPTS, late);
    expect(o.kind).toBe("rejected");
    expect(late.written).toHaveLength(0);
  });

  it("waits for an in-flight publish, then releases it", async () => {
    const store = fakeStore();
    const p = new ActivePrompt();
    let go!: () => void;
    void p.publish(async () => {
      await new Promise<void>((r) => (go = r));
      return store.publish("N");
    });
    const rel = releaseActivePrompt(p, (id, s) => clearPromptIf(store.backend, id, s));
    go();
    expect(await rel).toBe(true);
    expect(store.state).toEqual({ id: null, text: null });
  });

  it("no active prompt: no-op", async () => {
    const calls: string[] = [];
    const released = await releaseActivePrompt(new ActivePrompt(), async (id) => {
      calls.push(id);
    });
    expect(released).toBe(false);
    expect(calls).toHaveLength(0);
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
