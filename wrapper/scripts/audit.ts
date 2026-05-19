// View the rolling audit log of permission decisions. Reads /auditLog
// from RTDB (max 20 most-recent entries) and prints a compact human table.
//
// Usage:
//   npx tsx scripts/audit.ts          # plain table
//   npx tsx scripts/audit.ts --json   # raw JSON dump

import { db, initFirebase } from "../src/firebase.js";
import type { AuditEntry } from "../src/types/schema.js";

function fmtTs(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number): string => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function fmtDecision(d: AuditEntry["decision"]): string {
  switch (d) {
    case "allow":
      return "\x1b[32m✓ allow\x1b[0m";
    case "deny":
      return "\x1b[31m✗ deny\x1b[0m";
    case "timeout":
      return "\x1b[33m⏱ timeout\x1b[0m";
    case "pre-approved":
      return "\x1b[36m⚡ auto\x1b[0m";
  }
}

(async () => {
  initFirebase();
  const snap = await db().ref("/auditLog").once("value");
  const entries = (snap.val() as AuditEntry[] | null) ?? [];
  if (process.argv.includes("--json")) {
    console.log(JSON.stringify(entries, null, 2));
    process.exit(0);
  }
  if (entries.length === 0) {
    console.log("(audit log empty — no decisions recorded yet)");
    process.exit(0);
  }
  console.log(
    `\nAudit log — ${entries.length} most-recent decision${entries.length === 1 ? "" : "s"}\n`,
  );
  for (const e of entries) {
    const decision = fmtDecision(e.decision);
    const kind = `[${e.kind}]`.padEnd(7);
    const src = `(${e.source})`.padEnd(11);
    const tool = e.tool.padEnd(20);
    const args = e.args ? ` · ${e.args}` : "";
    console.log(`${fmtTs(e.ts)}  ${kind} ${src} ${decision}  ${tool}${args}`);
  }
  console.log("");
  process.exit(0);
})();
