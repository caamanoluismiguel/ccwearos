// Simulates the watch's voice input by writing a prompt to /prompt.
// Useful for testing daemon mode from a Mac without a microphone.
//
//   npx tsx scripts/send-prompt.ts "explain this project in one sentence"

import { db, initFirebase } from "../src/firebase.js";

async function main(): Promise<void> {
  const text = process.argv.slice(2).join(" ").trim();
  if (!text) {
    console.error('usage: npx tsx scripts/send-prompt.ts "your prompt"');
    process.exit(2);
  }
  initFirebase();
  await db().ref("/prompt").set({
    text,
    issuedAt: Date.now(),
  });
  console.log(`[send-prompt] Sent to /prompt: ${JSON.stringify(text)}`);
  process.exit(0);
}

main().catch((err) => {
  console.error("[send-prompt] Fatal:", err);
  process.exit(1);
});
