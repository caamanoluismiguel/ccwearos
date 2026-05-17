import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createMetricsStore } from "./metrics-store.js";

describe("createMetricsStore", () => {
  let tmp: string;
  let storePath: string;

  beforeEach(() => {
    tmp = mkdtempSync(join(tmpdir(), "ccwearos-metrics-"));
    storePath = join(tmp, "state.json");
  });

  afterEach(() => {
    rmSync(tmp, { recursive: true, force: true });
  });

  it("rolls up tokens across daily/weekly/monthly windows", () => {
    const store = createMetricsStore({ storePath });
    const now = Date.now();
    const hour = 60 * 60 * 1000;
    const day = 24 * hour;

    store.add(100, now - 2 * hour); // within day
    store.add(50, now - 2 * day); // outside day, within week
    store.add(25, now - 10 * day); // outside week, within month

    const snap = store.snapshot(now);
    expect(snap.dailyTokens).toBe(100);
    expect(snap.weeklyTokens).toBe(150);
    expect(snap.monthlyTokens).toBe(175);
    expect(snap.updatedAt).toBe(now);
  });

  it("ignores non-positive and non-finite token counts", () => {
    const store = createMetricsStore({ storePath });
    store.add(0);
    store.add(-5);
    store.add(Number.NaN);
    store.add(Number.POSITIVE_INFINITY);
    expect(store.snapshot().dailyTokens).toBe(0);
  });

  it("persists and reloads events across instances", () => {
    const a = createMetricsStore({ storePath });
    a.add(42);
    a.persist();

    const b = createMetricsStore({ storePath });
    expect(b.snapshot().dailyTokens).toBe(42);
  });

  it("prunes events older than 30 days on add", () => {
    const store = createMetricsStore({ storePath });
    const now = Date.now();
    const day = 24 * 60 * 60 * 1000;

    store.add(999, now - 40 * day); // ancient — will be pruned by the next add
    store.add(1, now); // triggers prune

    expect(store.snapshot(now).monthlyTokens).toBe(1);
  });
});
