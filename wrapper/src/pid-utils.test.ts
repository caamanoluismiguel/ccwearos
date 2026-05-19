import { describe, expect, it } from "vitest";
import { isPidAlive } from "./pid-utils.js";

describe("isPidAlive", () => {
  it("returns true for the current process pid", () => {
    expect(isPidAlive(process.pid)).toBe(true);
  });

  it("returns false for pid 0 (process-group probe trap)", () => {
    // process.kill(0, 0) sends signal 0 to the calling pgroup — always succeeds.
    // Without the guard, a stale `pid:0` in a session JSON would look alive.
    expect(isPidAlive(0)).toBe(false);
  });

  it("returns false for pid 1 (init / launchd)", () => {
    // pid 1 is always alive but is never our wrapper.
    expect(isPidAlive(1)).toBe(false);
  });

  it("returns false for negative pids (process-group targeting)", () => {
    expect(isPidAlive(-1)).toBe(false);
    expect(isPidAlive(-99999)).toBe(false);
  });

  it("returns false for non-integers", () => {
    expect(isPidAlive(3.14)).toBe(false);
    expect(isPidAlive(NaN)).toBe(false);
    expect(isPidAlive(Infinity)).toBe(false);
  });

  it("returns false for a definitely-dead pid (very high number)", () => {
    // 2^31-1 is the max pid on most systems; well above any real one.
    expect(isPidAlive(2147483646)).toBe(false);
  });
});
