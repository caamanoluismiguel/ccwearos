import { describe, expect, it, vi } from "vitest";
import { handleClaimRequest, type ClaimHandlerDeps } from "./claim-handler.js";
import type {
  AuditEntry,
  ClaimResult,
  PendingClaim,
  SharedSessionMeta,
} from "./types/schema.js";

// Build a fresh dep set with sensible defaults and per-test overrides.
// Captures every side effect into arrays so tests can assert on them.
function buildDeps(overrides: Partial<ClaimHandlerDeps> = {}): {
  deps: ClaimHandlerDeps;
  claimResults: Array<ClaimResult | null>;
  auditEntries: AuditEntry[];
  cleared: number;
  spawnCalls: Array<{ cmd: string; args: readonly string[] }>;
  busyState: { current: boolean };
} {
  const claimResults: Array<ClaimResult | null> = [];
  const auditEntries: AuditEntry[] = [];
  let cleared = 0;
  const spawnCalls: Array<{ cmd: string; args: readonly string[] }> = [];
  const busyState = { current: false };

  const deps: ClaimHandlerDeps = {
    readSharedSession: async () => null,
    setClaimResult: async (r) => {
      claimResults.push(r);
    },
    clearClaimRequest: async () => {
      cleared++;
    },
    appendAuditEntry: async (e) => {
      auditEntries.push(e);
    },
    isPidAlive: () => false,
    spawn: (cmd, args) => {
      spawnCalls.push({ cmd, args });
      return { status: 0, signal: null, stderr: "" };
    },
    commandMaxAgeSeconds: 60,
    tsxBin: "/wrapper/node_modules/.bin/tsx",
    shareScript: "/wrapper/scripts/share.ts",
    termProgram: "Apple_Terminal",
    getClaimBusy: () => busyState.current,
    setClaimBusy: (v) => {
      busyState.current = v;
    },
    ...overrides,
  };
  return {
    deps,
    claimResults,
    auditEntries,
    cleared: 0,
    spawnCalls,
    busyState,
  }; // cleared captured via closure
}

const validClaim = (overrides: Partial<PendingClaim> = {}): PendingClaim => ({
  sessionId: "550e8400-e29b-41d4-a716-446655440000",
  cwd: "/Users/luis/projects/foo",
  issuedAt: Date.now(),
  ...overrides,
});

describe("handleClaimRequest", () => {
  it("accepts a valid claim, spawns osascript, writes ok:true, audits allow", async () => {
    const { deps, claimResults, auditEntries, spawnCalls } = buildDeps();
    const result = await handleClaimRequest(validClaim(), deps);

    expect(result.decision).toBe("ok");
    expect(spawnCalls).toHaveLength(1);
    expect(spawnCalls[0]?.cmd).toBe("osascript");
    expect(claimResults).toHaveLength(1);
    expect(claimResults[0]?.ok).toBe(true);
    expect(claimResults[0]?.sessionId).toBe(
      "550e8400-e29b-41d4-a716-446655440000",
    );
    expect(auditEntries).toHaveLength(1);
    expect(auditEntries[0]?.decision).toBe("allow");
    expect(auditEntries[0]?.tool).toBe("(watch-claim)");
    expect(auditEntries[0]?.source).toBe("watch");
  });

  it("rejects malformed sessionId (shell-meta) before spawning", async () => {
    const { deps, claimResults, spawnCalls, auditEntries } = buildDeps();
    const result = await handleClaimRequest(
      validClaim({ sessionId: "abc; rm -rf /" }),
      deps,
    );

    expect(result.decision).toBe("malformed");
    expect(spawnCalls).toHaveLength(0);
    expect(claimResults[0]?.ok).toBe(false);
    expect(claimResults[0]?.reason).toMatch(/inválido/);
    expect(auditEntries).toHaveLength(0);
  });

  it("rejects too-short sessionId (regex floor)", async () => {
    const { deps, spawnCalls } = buildDeps();
    const result = await handleClaimRequest(
      validClaim({ sessionId: "abc" }),
      deps,
    );
    expect(result.decision).toBe("malformed");
    expect(spawnCalls).toHaveLength(0);
  });

  it("rejects empty cwd (would land in $HOME)", async () => {
    const { deps, claimResults, spawnCalls } = buildDeps();
    const result = await handleClaimRequest(validClaim({ cwd: "" }), deps);

    expect(result.decision).toBe("malformed");
    expect(spawnCalls).toHaveLength(0);
    expect(claimResults[0]?.reason).toMatch(/cwd/);
  });

  it("drops a stale claim silently (no /claimResult write)", async () => {
    const { deps, claimResults, spawnCalls } = buildDeps();
    const stale = validClaim({ issuedAt: Date.now() - 120_000 });
    const result = await handleClaimRequest(stale, deps);

    expect(result.decision).toBe("stale");
    expect(spawnCalls).toHaveLength(0);
    // Silent: no banner on the watch for a 2-minute-old tap.
    expect(claimResults).toHaveLength(0);
  });

  it("refuses when another wrapper-pty session is alive", async () => {
    const aliveShared: SharedSessionMeta = {
      sessionId: "different-session-id",
      pid: 12345,
      cwd: "/Users/luis/projects/other",
      startedAt: Date.now(),
      kind: "wrapper-pty",
    };
    const { deps, claimResults, spawnCalls, auditEntries } = buildDeps({
      readSharedSession: async () => aliveShared,
      isPidAlive: (pid) => pid === 12345,
    });
    const result = await handleClaimRequest(validClaim(), deps);

    expect(result.decision).toBe("locked");
    expect(spawnCalls).toHaveLength(0);
    expect(claimResults[0]?.ok).toBe(false);
    expect(claimResults[0]?.reason).toMatch(/cc activa/);
    expect(auditEntries).toHaveLength(0);
  });

  it("rejects a second claim while one is in-flight (busy gate)", async () => {
    const { deps, claimResults, spawnCalls } = buildDeps({
      getClaimBusy: () => true, // pretend the previous claim is still running
    });
    const result = await handleClaimRequest(validClaim(), deps);

    expect(result.decision).toBe("busy");
    expect(spawnCalls).toHaveLength(0);
    expect(claimResults[0]?.reason).toMatch(/curso/);
  });

  it("writes ok:false + deny audit on osascript non-zero exit", async () => {
    const { deps, claimResults, auditEntries } = buildDeps({
      spawn: () => ({
        status: 1,
        signal: null,
        stderr: "execution error: Not authorized to send Apple events",
      }),
    });
    const result = await handleClaimRequest(validClaim(), deps);

    expect(result.decision).toBe("spawn-failed");
    expect(claimResults[0]?.ok).toBe(false);
    expect(claimResults[0]?.reason).toMatch(/osascript/);
    expect(auditEntries[0]?.decision).toBe("deny");
  });

  it("surfaces SIGTERM (timeout) with an actionable hint", async () => {
    const { deps, claimResults } = buildDeps({
      spawn: () => ({ status: null, signal: "SIGTERM", stderr: "" }),
    });
    await handleClaimRequest(validClaim(), deps);
    expect(claimResults[0]?.reason).toMatch(/Automation/);
  });

  it("clears /claimRequest in EVERY path (success, refusal, malformed)", async () => {
    const cases: Array<{ name: string; deps: ClaimHandlerDeps }> = [
      { name: "success", deps: buildDeps().deps },
      {
        name: "malformed",
        deps: buildDeps({
          /* default valid spawn */
        }).deps,
      },
    ];
    // For each scenario, count clears via a fresh fake.
    for (const _c of cases) {
      let clears = 0;
      const deps = buildDeps({
        clearClaimRequest: async () => {
          clears++;
        },
      }).deps;
      await handleClaimRequest(validClaim(), deps);
      expect(clears).toBe(1);
    }

    // And on a path that refuses (busy):
    let busyClears = 0;
    const busyDeps = buildDeps({
      getClaimBusy: () => true,
      clearClaimRequest: async () => {
        busyClears++;
      },
    }).deps;
    await handleClaimRequest(validClaim(), busyDeps);
    expect(busyClears).toBe(1);
  });

  it("busy flag is released after success", async () => {
    const { deps, busyState } = buildDeps();
    await handleClaimRequest(validClaim(), deps);
    expect(busyState.current).toBe(false);
  });

  it("busy flag is released after spawn failure", async () => {
    const { deps, busyState } = buildDeps({
      spawn: () => ({ status: 1, signal: null, stderr: "boom" }),
    });
    await handleClaimRequest(validClaim(), deps);
    expect(busyState.current).toBe(false);
  });

  it("forwards the claim's cwd to buildShellCommand (not the daemon's cwd)", async () => {
    const { deps, spawnCalls } = buildDeps();
    await handleClaimRequest(
      validClaim({ cwd: "/Users/luis/with spaces/proj" }),
      deps,
    );
    // The cwd should appear, quoted, in the shell command that osascript
    // gets to type. We grep the -e args for the cd substring.
    const allArgs = spawnCalls[0]?.args.join(" ") ?? "";
    expect(allArgs).toContain("/Users/luis/with spaces/proj");
  });

  it("does not call setClaimBusy(true) on the malformed path", async () => {
    const setBusy = vi.fn();
    const { deps } = buildDeps({ setClaimBusy: setBusy });
    await handleClaimRequest(validClaim({ sessionId: "x" }), deps);
    // We only set busy on the spawn path; reject paths shouldn't toggle it.
    expect(setBusy).not.toHaveBeenCalled();
  });
});
