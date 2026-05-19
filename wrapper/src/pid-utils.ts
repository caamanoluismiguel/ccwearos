// PID liveness helpers. Lives in src/ so it's importable from both wrapper
// internals (sessions-scanner) and scripts (share, hooks).

// Returns true if the given pid is a positive integer AND a live process
// owned by the current user.
//
// Why `pid > 1` is the right floor:
//   - process.kill(0, 0) sends signal 0 to the entire process group of the
//     calling process — it always returns true. A stale `pid:0` from a
//     malformed session JSON would look "alive" forever.
//   - process.kill(-N, 0) sends to process group N. Same trap.
//   - process.kill(1, 0) succeeds (init/launchd is always alive) and would
//     give a false positive — pid 1 is never our wrapper / cc.
//
// Returns false on any throw — caller can't distinguish "doesn't exist"
// from "no permission to signal" but neither case is interesting to us:
// either way the wrapper is not alive.
export function isPidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 1) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
