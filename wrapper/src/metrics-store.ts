import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import type { Metrics } from "./types/schema.js";

const DAY_MS = 24 * 60 * 60 * 1000;
const WEEK_MS = 7 * DAY_MS;
const MONTH_MS = 30 * DAY_MS;

const STORE_FILE = resolve(process.cwd(), ".metrics-state.json");

interface TokenEvent {
  tokens: number;
  at: number; // unix epoch ms
}

interface StoredState {
  events: TokenEvent[];
}

export interface MetricsStore {
  add(tokens: number, now?: number): void;
  snapshot(now?: number): Metrics;
  persist(): void;
  reset(): void;
}

export function createMetricsStore(
  opts: { storePath?: string } = {},
): MetricsStore {
  const path = opts.storePath ?? STORE_FILE;
  let events: TokenEvent[] = loadFromDisk(path);

  const prune = (now: number): void => {
    events = events.filter((e) => now - e.at <= MONTH_MS);
  };

  const sumWithin = (now: number, windowMs: number): number =>
    events
      .filter((e) => now - e.at <= windowMs)
      .reduce((s, e) => s + e.tokens, 0);

  return {
    add(tokens, now = Date.now()) {
      if (!Number.isFinite(tokens) || tokens <= 0) return;
      events.push({ tokens, at: now });
      prune(now);
    },
    snapshot(now = Date.now()) {
      return {
        dailyTokens: sumWithin(now, DAY_MS),
        weeklyTokens: sumWithin(now, WEEK_MS),
        monthlyTokens: sumWithin(now, MONTH_MS),
        updatedAt: now,
      };
    },
    persist() {
      try {
        const state: StoredState = { events };
        writeFileSync(path, JSON.stringify(state));
      } catch (e) {
        console.error("[metrics-store] persist failed:", e);
      }
    },
    reset() {
      events = [];
    },
  };
}

function loadFromDisk(path: string): TokenEvent[] {
  if (!existsSync(path)) return [];
  try {
    const raw = readFileSync(path, "utf8");
    if (raw.trim().length === 0) return [];
    const parsed = JSON.parse(raw) as Partial<StoredState>;
    if (!Array.isArray(parsed.events)) return [];
    return parsed.events.filter(
      (e): e is TokenEvent =>
        typeof e === "object" &&
        e !== null &&
        typeof (e as TokenEvent).tokens === "number" &&
        typeof (e as TokenEvent).at === "number" &&
        Number.isFinite((e as TokenEvent).tokens) &&
        Number.isFinite((e as TokenEvent).at),
    );
  } catch (e) {
    console.error("[metrics-store] load failed:", e);
    return [];
  }
}
