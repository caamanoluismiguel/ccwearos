// Transaction updater for acquiring /sharedSession from `cc` (scripts/share.ts).
//
// Used as `db().ref("/sharedSession").transaction(cur => claimSharedSession(cur, mine, isPidAlive))`.
// Firebase re-runs the updater against the server value until it commits, so
// two `cc`s starting at once can't both read "free" and both write — the old
// read-then-write lock could. Returning `undefined` aborts (lock held).

import type { SharedSessionMeta } from "./types/schema.js";

export function claimSharedSession(
  current: SharedSessionMeta | null,
  mine: SharedSessionMeta,
  isPidAlive: (pid: number) => boolean,
): SharedSessionMeta | undefined {
  if (current === null || current === undefined) return mine;
  // Re-entrant: Firebase may call the updater again after our own write.
  if (current.pid === mine.pid && current.startedAt === mine.startedAt) {
    return mine;
  }
  // Owner gone (crash without onDisconnect firing yet) → take over.
  if (!isPidAlive(current.pid)) return mine;
  return undefined;
}
