// Sprint 2 prep — feeds a captured Claude Code stdout file through the parser
// and reports what was extracted, without touching Firebase or the real CLI.
//
// Usage:
//   tsx scripts/replay-fixture.ts fixtures/sample-claude-output.txt
//
// Drop your real captured sessions into fixtures/captures/ (gitignored).

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { extractPermissionPrompt, extractTokenCounts } from "../src/parser.js";
import { createMetricsStore } from "../src/metrics-store.js";

function main(): void {
  const arg = process.argv[2];
  if (!arg) {
    console.error("usage: tsx scripts/replay-fixture.ts <path-to-fixture>");
    process.exit(2);
  }

  const path = resolve(process.cwd(), arg);
  const raw = readFileSync(path, "utf8");

  // Simulate streaming: 256-char chunks, like a real stdout pipe.
  const chunkSize = 256;
  const chunks: string[] = [];
  for (let i = 0; i < raw.length; i += chunkSize) {
    chunks.push(raw.slice(i, i + chunkSize));
  }

  const store = createMetricsStore({ storePath: "/dev/null" });
  const permissions: string[] = [];

  for (const chunk of chunks) {
    for (const t of extractTokenCounts(chunk)) store.add(t);
    const prompt = extractPermissionPrompt(chunk);
    if (prompt) permissions.push(prompt);
  }

  const snap = store.snapshot();
  console.log("--- Replay report ---");
  console.log(`Input length:    ${raw.length} chars (${chunks.length} chunks)`);
  console.log(`Tokens (daily):  ${snap.dailyTokens}`);
  console.log(`Tokens (weekly): ${snap.weeklyTokens}`);
  console.log(`Tokens (month):  ${snap.monthlyTokens}`);
  console.log(`Permission prompts detected: ${permissions.length}`);
  for (const p of permissions) console.log(`  • ${p}`);
}

main();
