import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resolveSessionCwd } from "./claim-cwd.js";

// claim-cwd reuses sessions-scanner's JSONL reader, which transitively loads
// src/config.ts; give it a dummy DB URL so the suite runs without a .env.
// Nothing here touches Firebase.
vi.hoisted(() => {
  process.env["FIREBASE_DB_URL"] ??= "https://test.invalid";
});

const SID = "550e8400-e29b-41d4-a716-446655440000";

describe("resolveSessionCwd", () => {
  let root: string;
  let projects: string;
  let realCwd: string;

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), "claim-cwd-"));
    projects = join(root, "projects");
    realCwd = join(root, "work", "my-proj");
    mkdirSync(realCwd, { recursive: true });
    mkdirSync(join(projects, "-some-sanitized-dir"), { recursive: true });
  });
  afterEach(() => rmSync(root, { recursive: true, force: true }));

  const writeTranscript = (lines: unknown[]): void => {
    writeFileSync(
      join(projects, "-some-sanitized-dir", `${SID}.jsonl`),
      lines.map((l) => JSON.stringify(l)).join("\n") + "\n",
    );
  };

  it("returns the cwd recorded in the session's own transcript", () => {
    writeTranscript([
      { type: "user", cwd: realCwd, message: { content: "hola" } },
    ]);
    expect(resolveSessionCwd(SID, projects)).toBe(realCwd);
  });

  it("returns null for an unknown session", () => {
    expect(resolveSessionCwd("deadbeef-0000", projects)).toBeNull();
  });

  it("returns null when the transcript has no cwd", () => {
    writeTranscript([{ type: "user", message: { content: "hola" } }]);
    expect(resolveSessionCwd(SID, projects)).toBeNull();
  });

  it("returns null when the recorded cwd no longer exists", () => {
    writeTranscript([{ type: "user", cwd: join(root, "gone") }]);
    expect(resolveSessionCwd(SID, projects)).toBeNull();
  });

  it("rejects ids that could escape the projects dir", () => {
    expect(resolveSessionCwd("../../etc/passwd", projects)).toBeNull();
  });

  it("returns null when the projects dir is missing", () => {
    expect(resolveSessionCwd(SID, join(root, "nope"))).toBeNull();
  });
});
