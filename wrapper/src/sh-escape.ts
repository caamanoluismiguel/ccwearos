// POSIX shell escaping. Used wherever we interpolate user-influenced strings
// into a `sh -c "..."` command line — claude-runner.ts's pty exec invocation
// and the takeover flow's new-Terminal shell command.
//
// Lives in its own module so both call sites import the SAME implementation;
// a future fix to one propagates to both.

// Single-quote a string for safe inclusion in a POSIX shell command. Handles
// embedded single quotes via the standard `'\''` close-escape-reopen trick.
// Inside single quotes everything else (spaces, $, `, !, *) is literal.
export function shSingleQuote(s: string): string {
  return `'${s.replace(/'/g, "'\\''")}'`;
}
