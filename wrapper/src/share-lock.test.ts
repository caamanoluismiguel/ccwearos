import { describe, expect, it } from "vitest";
import { claimSharedSession } from "./share-lock.js";
import type { SharedSessionMeta } from "./types/schema.js";

const meta = (pid: number, startedAt = 1000): SharedSessionMeta => ({
  sessionId: "",
  pid,
  cwd: "/x",
  startedAt,
  kind: "wrapper-pty",
});

describe("claimSharedSession", () => {
  const mine = meta(500, 2000);

  it("takes a free lock", () => {
    expect(claimSharedSession(null, mine, () => true)).toBe(mine);
  });

  it("aborts when a live owner holds it", () => {
    expect(claimSharedSession(meta(400), mine, () => true)).toBeUndefined();
  });

  it("takes over a lock whose owner pid is dead", () => {
    expect(claimSharedSession(meta(400), mine, () => false)).toBe(mine);
  });

  it("is re-entrant for our own value", () => {
    expect(claimSharedSession(mine, mine, () => true)).toBe(mine);
  });
});
