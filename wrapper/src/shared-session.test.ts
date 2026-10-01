import { describe, expect, it } from "vitest";
import {
  HOOK_STALE_MS,
  PROMPT_MAX_CHARS,
  describeToolCall,
  findClaudeAncestorPid,
  isClaudeComm,
  isSharedSessionStale,
  staleLockRemover,
  truncateMiddle,
  type PsEntry,
} from "./shared-session.js";
import type { SharedSessionMeta } from "./types/schema.js";

const NOW = 1_800_000_000_000;
const alive = (pids: number[]) => (pid: number) => pids.includes(pid);

function hook(over: Partial<SharedSessionMeta> = {}): SharedSessionMeta {
  return {
    sessionId: "abc",
    pid: 100,
    cwd: "/x",
    startedAt: NOW - 1000,
    kind: "hook",
    ...over,
  };
}

describe("isSharedSessionStale", () => {
  it("wrapper-pty: stale only when pid is dead", () => {
    const m = hook({ kind: "wrapper-pty", pid: 42, startedAt: 0 });
    expect(isSharedSessionStale(m, NOW, alive([42]))).toBe(false);
    expect(isSharedSessionStale(m, NOW, alive([]))).toBe(true);
  });

  it("hook: fresh heartbeat + live owner is not stale", () => {
    const m = hook({ ownerPid: 7, heartbeatAt: NOW - 60_000 });
    expect(isSharedSessionStale(m, NOW, alive([7]))).toBe(false);
  });

  it("hook: dead owner pid is stale even with a fresh heartbeat", () => {
    const m = hook({ ownerPid: 7, heartbeatAt: NOW });
    expect(isSharedSessionStale(m, NOW, alive([]))).toBe(true);
  });

  it("hook: heartbeat older than 30 min is stale", () => {
    const m = hook({ ownerPid: 7, heartbeatAt: NOW - HOOK_STALE_MS - 1 });
    expect(isSharedSessionStale(m, NOW, alive([7]))).toBe(true);
  });

  it("hook: ignores the (short-lived) pid field when ownerPid is unknown", () => {
    const m = hook({ pid: 999, heartbeatAt: NOW - 1000 });
    expect(isSharedSessionStale(m, NOW, alive([]))).toBe(false);
  });

  it("hook: legacy record without heartbeat falls back to startedAt", () => {
    expect(isSharedSessionStale(hook({ startedAt: NOW - 1000 }), NOW, alive([]))).toBe(false);
    expect(
      isSharedSessionStale(hook({ startedAt: NOW - HOOK_STALE_MS - 1 }), NOW, alive([])),
    ).toBe(true);
  });
});

describe("isClaudeComm", () => {
  it("matches claude binaries", () => {
    expect(isClaudeComm("claude")).toBe(true);
    expect(isClaudeComm("/opt/homebrew/bin/claude")).toBe(true);
    expect(isClaudeComm("/Users/a/.local/share/claude/versions/2.1.200")).toBe(true);
  });
  it("rejects shells / node / lookalikes", () => {
    expect(isClaudeComm("/bin/zsh")).toBe(false);
    expect(isClaudeComm("node")).toBe(false);
    expect(isClaudeComm("claude-helper")).toBe(false);
  });
});

describe("findClaudeAncestorPid", () => {
  const tree: Record<number, PsEntry> = {
    500: { ppid: 400, comm: "node" },
    400: { ppid: 300, comm: "/bin/zsh" },
    300: { ppid: 200, comm: "claude" },
    200: { ppid: 1, comm: "login" },
  };
  const ps = (pid: number) => tree[pid] ?? null;

  it("walks past tsx + shell to the Claude process", () => {
    expect(findClaudeAncestorPid(500, ps)).toBe(300);
  });
  it("never returns the start pid itself", () => {
    expect(findClaudeAncestorPid(300, ps)).toBeNull();
  });
  it("returns null when no ancestor is Claude", () => {
    expect(findClaudeAncestorPid(500, (pid) => (pid === 300 ? { ppid: 200, comm: "x" } : ps(pid)))).toBeNull();
  });
  it("returns null when ps fails", () => {
    expect(findClaudeAncestorPid(500, () => null)).toBeNull();
  });
  it("terminates on a self-parent loop", () => {
    expect(findClaudeAncestorPid(9, () => ({ ppid: 9, comm: "sh" }))).toBeNull();
  });
});

describe("truncateMiddle", () => {
  it("returns short strings unchanged", () => {
    expect(truncateMiddle("abc", 10)).toBe("abc");
  });
  it("keeps head and tail within max", () => {
    const s = "a".repeat(100) + "END";
    const out = truncateMiddle(s, 20);
    expect(out.length).toBeLessThanOrEqual(20);
    expect(out.endsWith("END")).toBe(true);
    expect(out).toContain(" … ");
  });
});

describe("describeToolCall", () => {
  it("shows the full Bash command (no 77-char cut)", () => {
    const cmd = "cd /some/long/path/that/goes/on && npm run build && npm test -- --reporter verbose && rm -rf dist";
    expect(describeToolCall("Bash", { command: cmd, description: "x" })).toBe(`Bash: ${cmd}`);
  });
  it("caps very long commands but keeps the tail visible", () => {
    const cmd = "echo " + "x".repeat(5000) + " && rm -rf ~/important";
    const out = describeToolCall("Bash", { command: cmd });
    expect(out.length).toBeLessThanOrEqual(PROMPT_MAX_CHARS);
    expect(out.startsWith("Bash: echo ")).toBe(true);
    expect(out.endsWith("&& rm -rf ~/important")).toBe(true);
  });
  it("shows the full file path for Edit/Write", () => {
    const p = "/Users/me/projects/" + "deep/".repeat(30) + "file.ts";
    expect(describeToolCall("Edit", { file_path: p, old_string: "a", new_string: "b" })).toBe(`Edit: ${p}`);
    expect(describeToolCall("Write", { file_path: p, content: "..." })).toBe(`Write: ${p}`);
  });
  it("falls back to key=value fragments for other tools", () => {
    expect(describeToolCall("WebFetch", { url: "https://a.b", prompt: "hi" })).toBe(
      "WebFetch: url=https://a.b · prompt=hi",
    );
  });
});

describe("staleLockRemover", () => {
  const stale = hook({ heartbeatAt: NOW - HOOK_STALE_MS - 1 });

  it("deletes the same lock while it is still stale", () => {
    expect(staleLockRemover(stale, NOW, alive([]))(stale)).toBeNull();
  });

  it("aborts if a different lock took its place", () => {
    const other = hook({ pid: 101, heartbeatAt: NOW - HOOK_STALE_MS - 1 });
    expect(staleLockRemover(stale, NOW, alive([]))(other)).toBeUndefined();
    const restarted = { ...stale, startedAt: NOW };
    expect(staleLockRemover(stale, NOW, alive([]))(restarted)).toBeUndefined();
  });

  it("aborts if the same lock got a fresh heartbeat meanwhile", () => {
    const refreshed = { ...stale, heartbeatAt: NOW - 1000 };
    expect(staleLockRemover(stale, NOW, alive([]))(refreshed)).toBeUndefined();
  });

  it("cold cache / already gone: null (server re-runs with the real value)", () => {
    expect(staleLockRemover(stale, NOW, alive([]))(null)).toBeNull();
  });
});
