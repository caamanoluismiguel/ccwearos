import admin from "firebase-admin";
import { config, loadServiceAccount } from "./config.js";
import type {
  AuditEntry,
  ClaimResult,
  ClaudeStatus,
  Metrics,
  PendingClaim,
  PendingCommand,
  PendingPrompt,
  RecentSession,
  SharedSessionMeta,
  TaskKind,
  ToolEvent,
  WrapperStatus,
} from "./types/schema.js";

const AUDIT_LOG_MAX = 20;

let app: admin.app.App | null = null;

export function initFirebase(): admin.app.App {
  if (app) return app;
  app = admin.initializeApp({
    credential: admin.credential.cert(
      loadServiceAccount() as admin.ServiceAccount,
    ),
    databaseURL: config.firebaseDbUrl,
  });
  return app;
}

export function db(): admin.database.Database {
  return initFirebase().database();
}

export async function setStatus(status: WrapperStatus): Promise<void> {
  await db().ref("/status").set(status);
}

export async function writeMetrics(metrics: Metrics): Promise<void> {
  await db().ref("/metrics").set(metrics);
}

export async function setPermissionPrompt(text: string | null): Promise<void> {
  await db().ref("/permissionPrompt").set(text);
}

export async function setActivity(text: string | null): Promise<void> {
  await db().ref("/activity").set(text);
}

export async function setTask(text: string | null): Promise<void> {
  await db().ref("/task").set(text);
}

export async function setResponse(text: string | null): Promise<void> {
  await db().ref("/response").set(text);
}

export async function setClaudeStatus(s: ClaudeStatus | null): Promise<void> {
  await db().ref("/claudeStatus").set(s);
}

export async function setTaskKind(kind: TaskKind | null): Promise<void> {
  await db().ref("/taskKind").set(kind);
}

export async function setToolEvents(events: ToolEvent[] | null): Promise<void> {
  await db().ref("/toolEvents").set(events);
}

export async function setHeadline(text: string | null): Promise<void> {
  await db().ref("/headline").set(text);
}

export async function setFollowups(items: string[] | null): Promise<void> {
  await db()
    .ref("/followups")
    .set(items && items.length > 0 ? items : null);
}

export async function setSharedSession(
  meta: SharedSessionMeta | null,
): Promise<void> {
  await db().ref("/sharedSession").set(meta);
}

// One-shot read of /sharedSession — used by share.ts to refuse a 2nd shared
// session start, and to detect stale locks (PID no longer alive).
export async function readSharedSession(): Promise<SharedSessionMeta | null> {
  const snap = await db().ref("/sharedSession").get();
  return (snap.val() as SharedSessionMeta | null) ?? null;
}

export async function setRecentSessions(
  list: RecentSession[] | null,
): Promise<void> {
  await db()
    .ref("/recentSessions")
    .set(list && list.length > 0 ? list : null);
}

export function watchSharedSession(
  onChange: (meta: SharedSessionMeta | null) => void,
): () => void {
  const ref = db().ref("/sharedSession");
  const handler = (snap: admin.database.DataSnapshot) => {
    const val = snap.val() as SharedSessionMeta | null;
    onChange(val);
  };
  ref.on("value", handler);
  return () => ref.off("value", handler);
}

// Append one entry to the rolling audit log. Caps at AUDIT_LOG_MAX
// most-recent entries. Best-effort — never throws (audit is observability,
// not load-bearing).
//
// Uses ref.transaction() so concurrent writes from different flows
// (voice daemon + cc + pre-tool-use hook) don't lose entries via
// read-modify-write races. Without the transaction, two near-simultaneous
// decisions read the same `existing`, both append, last writer wins → one
// entry silently dropped.
export async function appendAuditEntry(entry: AuditEntry): Promise<void> {
  try {
    const ref = db().ref("/auditLog");
    await ref.transaction((current: AuditEntry[] | null) => {
      const existing = current ?? [];
      return [...existing, entry].slice(-AUDIT_LOG_MAX);
    });
  } catch (e) {
    console.error("[audit] append failed:", (e as Error).message);
  }
}

// Atomically clear every RTDB path that represents "wrapper has live UI
// state" — status banner, current task, permission prompt, response, etc.
//
// `finalStatus` lets callers choose the terminal state:
//   - "IDLE" on startup (wrapper coming online, no work in flight)
//   - "OFFLINE" on shutdown (wrapper exiting)
//
// Called from every shutdown path (clean exit AND signal handlers) so that
// a crashed wrapper doesn't leave the watch staring at phantom data hours
// later — this is the bug the user observed after a self-takeover crash.
//
// Does NOT clear /metrics (cumulative, want to preserve), /fcmToken (watch
// owns this), /recentSessions (separately maintained by the scanner),
// /auditLog (history is the point), /sharedSession (caller controls — set
// it explicitly if they own the lock).
//
// Best-effort: a single .update() is atomic at the path level. Individual
// failures don't throw; we log and move on so callers can continue with
// their own teardown.
export async function clearStaleState(
  finalStatus: WrapperStatus = "OFFLINE",
): Promise<void> {
  try {
    await db().ref("/").update({
      status: finalStatus,
      permissionPrompt: null,
      activity: null,
      task: null,
      response: null,
      headline: null,
      taskKind: null,
      toolEvents: null,
      followups: null,
      claudeStatus: null,
      command: null,
      prompt: null,
    });
  } catch (e) {
    console.error("[firebase] clearStaleState failed:", (e as Error).message);
  }
}

// Register an onDisconnect() handler so the Firebase server clears the
// given paths when our TCP connection drops — covers SIGKILL, OOM, parent
// death, sudden power loss. This is the single most reliable cleanup
// mechanism the Admin SDK exposes; nothing else fires on those exit modes.
//
// IMPORTANT: returns a Promise. The caller MUST await it before relying on
// the server-side state — Firebase queues the disconnect handler with the
// server, and a SIGKILL between the call and the server ack would skip it.
//
// Per-process call this ONCE at startup with the set of paths the process
// "owns" while alive. share.ts owns /sharedSession + /status + all UI
// surfaces. The daemon owns /status + UI surfaces (NOT /sharedSession —
// daemon doesn't claim it).
export async function registerCrashCleanup(paths: {
  sharedSession?: boolean;
  uiSurfaces?: boolean;
}): Promise<void> {
  const tasks: Promise<unknown>[] = [];
  if (paths.uiSurfaces) {
    tasks.push(
      db().ref("/").onDisconnect().update({
        status: "OFFLINE",
        permissionPrompt: null,
        activity: null,
        task: null,
        headline: null,
        taskKind: null,
        toolEvents: null,
        followups: null,
        command: null,
      }),
    );
  }
  if (paths.sharedSession) {
    tasks.push(db().ref("/sharedSession").onDisconnect().set(null));
  }
  await Promise.all(tasks);
}

// Cancel previously-registered onDisconnect handlers — call from clean
// shutdown paths so we don't race ourselves (clean cleanup wrote IDLE, then
// onDisconnect fires on the closing TCP and overwrites with OFFLINE).
export async function clearCrashCleanup(): Promise<void> {
  try {
    await Promise.all([
      db().ref("/").onDisconnect().cancel(),
      db().ref("/sharedSession").onDisconnect().cancel(),
    ]);
  } catch (e) {
    console.error("[firebase] clearCrashCleanup failed:", (e as Error).message);
  }
}

// FCM wake-up: when the wrapper needs the watch out of ambient (e.g. a
// permission prompt just appeared in an interactive Claude session), send a
// high-priority data message to the watch's registered FCM token.
export async function sendFcmWake(payloadType: string): Promise<void> {
  const snap = await db().ref("/fcmToken").get();
  const token = snap.val() as string | null;
  if (!token) {
    console.warn("[fcm] no token registered yet — watch hasn't checked in");
    return;
  }
  try {
    await initFirebase()
      .messaging()
      .send({
        token,
        data: { type: payloadType, ts: String(Date.now()) },
        android: {
          priority: "high",
          ttl: 60_000,
        },
      });
    console.log(`[fcm] wake sent (type=${payloadType})`);
  } catch (e) {
    console.error("[fcm] send failed:", (e as Error).message);
  }
}

export async function clearCommand(): Promise<void> {
  await db().ref("/command").set(null);
}

export async function clearPrompt(): Promise<void> {
  await db().ref("/prompt").set(null);
}

export function watchPrompts(
  onPrompt: (p: PendingPrompt) => void | Promise<void>,
): () => void {
  const ref = db().ref("/prompt");
  const handler = (snap: admin.database.DataSnapshot) => {
    const val = snap.val() as PendingPrompt | null;
    if (val) void onPrompt(val);
  };
  ref.on("value", handler);
  return () => ref.off("value", handler);
}

export function watchCommands(
  onCommand: (cmd: PendingCommand) => void | Promise<void>,
): () => void {
  const ref = db().ref("/command");
  const handler = (snap: admin.database.DataSnapshot) => {
    const val = snap.val() as PendingCommand | null;
    if (val) void onCommand(val);
  };
  ref.on("value", handler);
  return () => ref.off("value", handler);
}

// Sprint 4n — tap-to-claim watcher. Mirrors watchPrompts / watchCommands.
// Fires whenever the watch writes a /claimRequest. Handler is responsible
// for clearing the path (via clearClaimRequest) after consuming it so
// we don't re-process on listener reconnect.
export function watchClaimRequest(
  onClaim: (claim: PendingClaim) => void | Promise<void>,
): () => void {
  const ref = db().ref("/claimRequest");
  const handler = (snap: admin.database.DataSnapshot) => {
    const val = snap.val() as PendingClaim | null;
    if (val) void onClaim(val);
  };
  ref.on("value", handler);
  return () => ref.off("value", handler);
}

export async function clearClaimRequest(): Promise<void> {
  await db().ref("/claimRequest").set(null);
}

export async function setClaimResult(
  result: ClaimResult | null,
): Promise<void> {
  await db().ref("/claimResult").set(result);
}
