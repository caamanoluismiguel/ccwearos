import admin from "firebase-admin";
import { config, loadServiceAccount } from "./config.js";
import type {
  ClaudeStatus,
  Metrics,
  PendingCommand,
  PendingPrompt,
  TaskKind,
  ToolEvent,
  WrapperStatus,
} from "./types/schema.js";

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
