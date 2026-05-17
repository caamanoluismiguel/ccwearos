// One-shot: clear /command, /permissionPrompt, /activity, /task, /response;
// set /status = IDLE. Use when state gets stuck between sessions.

import {
  clearCommand,
  initFirebase,
  setActivity,
  setPermissionPrompt,
  setResponse,
  setStatus,
  setTask,
} from "../src/firebase.js";

async function main(): Promise<void> {
  initFirebase();
  await Promise.all([
    clearCommand(),
    setPermissionPrompt(null),
    setActivity(null),
    setTask(null),
    setResponse(null),
  ]);
  await setStatus("IDLE");
  console.log(
    "[reset-rtdb] command/permissionPrompt/activity/task/response cleared; status=IDLE",
  );
  process.exit(0);
}

main().catch((err) => {
  console.error("[reset-rtdb] Fatal:", err);
  process.exit(1);
});
