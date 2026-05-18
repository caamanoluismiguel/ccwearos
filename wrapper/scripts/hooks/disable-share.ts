// Invoked from the `/ccwearos-off` slash command. Clears /sharedSession.

import {
  initFirebase,
  readSharedSession,
  setSharedSession,
} from "../../src/firebase.js";

async function main(): Promise<void> {
  initFirebase();
  const existing = await readSharedSession();
  if (!existing) {
    console.log("[ccwearos] No share active.");
    return;
  }
  if (existing.kind === "wrapper-pty") {
    console.log(
      "[ccwearos] Active share is a wrapper-pty (cc) session — close it from its own Terminal (Ctrl+C).",
    );
    return;
  }
  await setSharedSession(null);
  console.log(
    "[ccwearos] ✓ Share disabled. Permissions will go back to the Terminal.",
  );
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.log(`[ccwearos] error: ${(err as Error).message}`);
    process.exit(0);
  });
