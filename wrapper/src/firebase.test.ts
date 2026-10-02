import { beforeEach, describe, expect, it, vi } from "vitest";

// In-memory stand-in for the Admin SDK: records every write, no network.
const writes: { op: string; path: string; value: unknown }[] = [];
const fakeDb = {
  ref: (path = "/") => ({
    set: async (value: unknown) => {
      writes.push({ op: "set", path, value });
    },
    update: async (value: unknown) => {
      writes.push({ op: "update", path, value });
    },
    onDisconnect: () => ({
      update: async (value: unknown) => {
        writes.push({ op: "onDisconnect.update", path, value });
      },
      set: async (value: unknown) => {
        writes.push({ op: "onDisconnect.set", path, value });
      },
    }),
  }),
};

vi.mock("firebase-admin", () => ({
  default: {
    initializeApp: () => ({ database: () => fakeDb }),
    credential: { cert: () => ({}) },
  },
}));
vi.mock("./config.js", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./config.js")>()),
  loadServiceAccount: () => ({}),
}));

const fb = await import("./firebase.js");

describe("firebase voice-run paths", () => {
  beforeEach(() => {
    writes.length = 0;
  });

  it("setBlocker / setOutcome / setConversationActive write their paths", async () => {
    await fb.setBlocker({ kind: "trust", hint: "h", cwd: "/x", ts: 1 });
    await fb.setOutcome({ ok: false, exitCode: 1, ts: 2 });
    await fb.setConversationActive(true);
    await fb.setBlocker(null);
    expect(writes).toEqual([
      { op: "set", path: "/blocker", value: { kind: "trust", hint: "h", cwd: "/x", ts: 1 } },
      { op: "set", path: "/outcome", value: { ok: false, exitCode: 1, ts: 2 } },
      { op: "set", path: "/conversationActive", value: true },
      { op: "set", path: "/blocker", value: null },
    ]);
  });

  it("clearStaleState clears live state but keeps the last result + conversationActive", async () => {
    await fb.clearStaleState("IDLE");
    const v = writes[0]?.value as Record<string, unknown>;
    expect(v).toMatchObject({ status: "IDLE", blocker: null, permissionPrompt: null });
    for (const k of ["conversationActive", "response", "headline", "followups", "outcome", "toolEvents", "taskKind"])
      expect(v).not.toHaveProperty(k);
  });

  it("crash cleanup nulls blocker/outcome but keeps conversationActive", async () => {
    await fb.registerCrashCleanup({ uiSurfaces: true });
    const v = writes.find((w) => w.op === "onDisconnect.update")?.value as Record<
      string,
      unknown
    >;
    expect(v).toMatchObject({ status: "OFFLINE", blocker: null });
    for (const k of ["conversationActive", "response", "headline", "followups", "outcome"])
      expect(v).not.toHaveProperty(k);
  });
});
