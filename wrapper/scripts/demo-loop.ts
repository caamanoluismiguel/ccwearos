// End-to-end live demo: drives /status, /metrics, /permissionPrompt from the
// wrapper while the watch app is running. Proves Sprints 2 + 3 over a real
// Firebase RTDB without needing a real Claude CLI session.
//
//   tsx scripts/demo-loop.ts

import {
  clearCommand,
  initFirebase,
  setPermissionPrompt,
  setStatus,
  watchCommands,
  writeMetrics,
} from "../src/firebase.js";

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

async function main(): Promise<void> {
  initFirebase();

  console.log(
    "[demo] Step 1/3 → RUNNING + fake metrics (watch should show green dot)",
  );
  await setStatus("RUNNING");
  await writeMetrics({
    dailyTokens: 12_340,
    weeklyTokens: 45_678,
    monthlyTokens: 123_456,
    updatedAt: Date.now(),
  });
  await sleep(3000);

  console.log(
    "[demo] Step 2/3 → AWAITING_PERMISSION (watch routes to permission screen + haptic)",
  );
  await setPermissionPrompt("Allow web fetch to api.example.com? [Y/n]");
  await setStatus("AWAITING_PERMISSION");

  console.log(
    "[demo]            Waiting up to 30s for watch to tap Allow/Deny…",
  );
  let received: { text: string; issuedAt: number } | null = null;
  const stop = watchCommands((cmd) => {
    received = cmd;
  });

  const deadline = Date.now() + 90_000;
  while (!received && Date.now() < deadline) {
    await sleep(200);
  }
  stop();

  if (received) {
    const r = received as { text: string; issuedAt: number };
    console.log(
      `[demo]            ✅ Watch sent: ${JSON.stringify(r.text)} (age ${(Date.now() - r.issuedAt) / 1000}s)`,
    );
    await clearCommand();
    await setPermissionPrompt(null);
  } else {
    console.log(
      "[demo]            ⏰ Timed out (no command from watch). Resetting state.",
    );
    await clearCommand();
    await setPermissionPrompt(null);
  }

  console.log("[demo] Step 3/3 → IDLE");
  await setStatus("IDLE");
  console.log("[demo] Done.");
  process.exit(0);
}

main().catch((err) => {
  console.error("[demo] Fatal:", err);
  process.exit(1);
});
