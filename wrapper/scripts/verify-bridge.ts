// Sprint 1 — proves the wrapper can reach Firebase without needing the Claude
// CLI installed. Writes "IDLE" to /status, reads it back, then exits.
//
// Run: `npm run verify` (from wrapper/)

import { db, initFirebase, setStatus } from "../src/firebase.js";

async function main(): Promise<void> {
  initFirebase();
  console.log("[verify-bridge] Connected. Writing status=IDLE…");
  await setStatus("IDLE");

  const snap = await db().ref("/status").get();
  const value = snap.val();
  console.log("[verify-bridge] Read back:", JSON.stringify(value));

  if (value !== "IDLE") {
    console.error("[verify-bridge] FAIL — expected 'IDLE', got:", value);
    process.exit(1);
  }
  console.log("[verify-bridge] OK — bridge is live.");
  process.exit(0);
}

main().catch((err) => {
  console.error("[verify-bridge] Fatal:", err);
  process.exit(1);
});
