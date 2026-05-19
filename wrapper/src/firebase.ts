import admin from "firebase-admin";
import { config, loadServiceAccount } from "./config.js";
import type {
  AuditEntry,
  ClaudeStatus,
  Metrics,
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
export async function appendAuditEntry(entry: AuditEntry): Promise<void> {
  try {
    const ref = db().ref("/auditLog");
    const snap = await ref.once("value");
    const existing = (snap.val() as AuditEntry[] | null) ?? [];
    const next = [...existing, entry].slice(-AUDIT_LOG_MAX);
    await ref.set(next);
  } catch (e) {
    console.error("[audit] append failed:", (e as Error).message);
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
