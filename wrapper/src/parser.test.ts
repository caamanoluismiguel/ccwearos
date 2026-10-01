import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  extractFollowups,
  extractPermissionPrompt,
  extractResponseLines,
  extractTokenCount,
  extractTokenCounts,
  isAwaitingPermission,
  PERMISSION_DETAILS_UNAVAILABLE,
  PERMISSION_PROMPT_MAX_CHARS,
  PROMPT_END_MARKER,
} from "./parser.js";

describe("extractTokenCount", () => {
  it("parses 'Tokens used: 1234'", () => {
    expect(extractTokenCount("Tokens used: 1234")).toBe(1234);
  });

  it("parses 'Usage: 50 tkns' (the variant the blueprint warns about)", () => {
    expect(extractTokenCount("Usage: 50 tkns")).toBe(50);
  });

  it("handles comma-grouped numbers", () => {
    expect(extractTokenCount("tokens used: 12,345")).toBe(12345);
  });

  it("returns null when nothing matches", () => {
    expect(extractTokenCount("hello world")).toBeNull();
  });
});

describe("extractTokenCounts", () => {
  it("returns every match in a chunk, in pattern order", () => {
    const chunk = "Tokens used: 100\nUsage: 200 tkns\nTokens used: 300";
    const got = extractTokenCounts(chunk);
    // both `tokens used` matches first, then `usage` match
    expect(got.sort((a, b) => a - b)).toEqual([100, 200, 300]);
  });

  it("returns an empty array for non-matching input", () => {
    expect(extractTokenCounts("no numbers here")).toEqual([]);
  });

  it("skips zero and negative-looking values", () => {
    // "-50" never matches because the patterns expect a leading digit.
    expect(extractTokenCounts("Tokens used: 0")).toEqual([]);
  });

  it("captures input/output token splits", () => {
    expect(
      extractTokenCounts("Sent 1,200 input tokens, got 350 output tokens"),
    ).toEqual(expect.arrayContaining([1200, 350]));
  });
});

describe("isAwaitingPermission", () => {
  it("detects [Y/n]", () => {
    expect(isAwaitingPermission("Continue? [Y/n]")).toBe(true);
  });

  it("detects 'Do you want to allow'", () => {
    expect(isAwaitingPermission("Do you want to allow this action?")).toBe(
      true,
    );
  });

  it("detects (y/n)", () => {
    expect(isAwaitingPermission("Proceed?\n(y/n)")).toBe(true);
  });

  it("ignores plain output", () => {
    expect(isAwaitingPermission("Tokens used: 100")).toBe(false);
  });
});

describe("extractPermissionPrompt", () => {
  it("returns the full prompt line surrounding the match", () => {
    const chunk =
      "Reading foo.ts\nDo you want to allow web fetch? [Y/n]\nWaiting...";
    expect(extractPermissionPrompt(chunk)).toBe(
      "Do you want to allow web fetch? [Y/n]",
    );
  });

  it("returns null when no permission pattern is present", () => {
    expect(extractPermissionPrompt("Just running normally")).toBeNull();
  });

  it("trims whitespace around the matched line", () => {
    expect(extractPermissionPrompt("\n   Allow?   \n")).toBe("Allow?");
  });
});

// Fixtures in fixtures/permission/ are sanitized slices of real Claude Code
// 2.1.x pty output (same-length substitutions so wrap columns stay exact),
// plus *-synthetic.txt boxes built in the same escape style.
describe("extractPermissionPrompt — Claude Code permission box", () => {
  const fixture = (name: string): string =>
    readFileSync(
      fileURLToPath(
        new URL(`../fixtures/permission/${name}.txt`, import.meta.url),
      ),
      "utf8",
    );

  it("Bash: shows the command and Claude's description, not '❯ 1. Yes'", () => {
    expect(extractPermissionPrompt(fixture("bash-simple"))).toBe(
      "Bash: ls -la /Users/jdoe-example-user/projects/\n" +
        "List all projects in the projects directory",
    );
  });

  it("Bash: rows are separated by bare \\r, not \\n", () => {
    const raw = fixture("bash-simple");
    const box = raw.slice(raw.lastIndexOf("─".repeat(80)));
    expect(box).not.toMatch(/\n[^\r\n]*Do you want/);
    expect(box).toMatch(/\r[^\r\n]*Do you want to proceed\?/);
    expect(extractPermissionPrompt(box)).toMatch(/^Bash: ls -la /);
  });

  it("Bash: re-joins a long command hard-wrapped mid-path and word-wrapped", () => {
    expect(extractPermissionPrompt(fixture("bash-wrapped"))).toBe(
      "Bash: ls /Users/jdoe-example-user/.claude/projects/" +
        "-Users-jdoe-example-user-projects-CCWEAROS/memory/ 2>/dev/null" +
        ' && echo "---" && ls /Users/jdoe-example-user/projects/ 2>/dev/null\n' +
        "Check memory directory and projects folder",
    );
  });

  it("Bash: multi-line script keeps its lines and relative indent", () => {
    const got = extractPermissionPrompt(fixture("bash-heredoc-redraw"));
    expect(got).toMatch(
      /^Bash: for dir in \/Users\/jdoe-example-user\/projects\/\*\/; do\n {2}name=/,
    );
    expect(got).toContain('\n    grep -E \'"(name|description)"\' "$dir/package.json" | head -3\n  else\n');
    expect(got).toContain("\ndone 2>/dev/null\nGet a brief overview of each project");
    expect(got).not.toContain("Do you want");
    expect(got).not.toContain("1. Yes");
  });

  it("Bash: caps at the max length but keeps the END of a chained command", () => {
    const got = extractPermissionPrompt(fixture("bash-long-chain-synthetic"));
    expect(got).not.toBeNull();
    expect(got!.length).toBeLessThanOrEqual(PERMISSION_PROMPT_MAX_CHARS);
    expect(got).toMatch(/^Bash: echo step-0 && echo step-1 /);
    expect(got).toContain(" … ");
    expect(got).toContain(
      "echo step-119 && rm -rf ./build\nRun every build step then clean the build folder",
    );
  });

  it("Edit: shows the file path, not the diff", () => {
    expect(extractPermissionPrompt(fixture("edit-synthetic"))).toBe(
      "Edit: src/services/billing.ts",
    );
  });

  it("Write: shows the file path, not the content preview", () => {
    expect(extractPermissionPrompt(fixture("write-synthetic"))).toBe(
      "Write: scripts/deploy.sh",
    );
  });

  it("Fetch: shows the URL and the fetch prompt", () => {
    expect(extractPermissionPrompt(fixture("fetch"))).toBe(
      "Fetch: https://www.example-site.io\n" +
        "Summarize what this site is about, what it offers, and any key " +
        "sections/products visible on the homepage.",
    );
  });

  it("Web Search: shows the full (wrapped) query", () => {
    expect(extractPermissionPrompt(fixture("web-search"))).toBe(
      'Web Search: "mejores restaurantes asiaticos Bogota virales Instagram TikTok 2026"',
    );
  });

  it("returns null for a normal TUI screen (spinner, input box, status bar)", () => {
    expect(extractPermissionPrompt(fixture("no-permission-status"))).toBeNull();
  });

  it("flags a box whose top is not in the chunk instead of guessing", () => {
    const got = extractPermissionPrompt(
      "\r Do you want to proceed?\r ❯ 1. Yes\r   2. No\r",
    );
    expect(got).toContain(PERMISSION_DETAILS_UNAVAILABLE);
  });

  it("ignores 'Do you want to …?' in Claude's prose (no option list)", () => {
    const rule = "─".repeat(80);
    const chunk = `\r${rule}\r❯ \r${rule}\r⏺ Built it.\rDo you want to deploy now?\r`;
    expect(extractPermissionPrompt(chunk)).toBeNull();
  });

  it("never forwards a bare '❯ 1. Yes' as the prompt text", () => {
    expect(extractPermissionPrompt("\r❯ 1. Yes\r  2. No\r")).toBe(
      PERMISSION_DETAILS_UNAVAILABLE,
    );
  });

  it("uses the newest box when several are in the buffer", () => {
    const got = extractPermissionPrompt(
      fixture("bash-simple") + fixture("web-search"),
    );
    expect(got).toMatch(/^Web Search: /);
  });
});

describe("extractResponseLines — marker-based slicing", () => {
  it("discards everything before the last PROMPT_END_MARKER", () => {
    const buf =
      `Welcome back Luis!\n` +
      `▐▛███▜▌ · What's new\n` +
      `Reply like this: **TL;DR:** ...\n` +
      `Qué hora es\n` +
      `${PROMPT_END_MARKER}\n` +
      `**TL;DR:** Son las 9 y media de la noche.\n` +
      `(Hora local en tu Mac.)`;
    const out = extractResponseLines(buf, { userEcho: "Qué hora es" });
    expect(out).toContain("TL;DR");
    expect(out).not.toContain("Welcome back");
    expect(out).not.toContain("What's new");
    expect(out).not.toContain("Reply like this");
    expect(out).not.toMatch(/^Qué hora es$/m);
  });

  it("falls back to legacy line filter when the marker is absent", () => {
    const buf = `Welcome back\nReal answer line 1\nReal answer line 2`;
    const out = extractResponseLines(buf);
    expect(out).not.toContain("Welcome back");
    expect(out).toContain("Real answer line 1");
    expect(out).toContain("Real answer line 2");
  });

  it("does not eat a response that merely shares words with the echo", () => {
    const buf = `${PROMPT_END_MARKER}\nThe time is 9:30 PM.`;
    const out = extractResponseLines(buf, { userEcho: "what time is it" });
    expect(out).toContain("The time is 9:30 PM.");
  });
});

describe("extractFollowups", () => {
  it("extracts 3 bullets after an English 'Followups:' header", () => {
    const buf = [
      "⏺ TL;DR: Son las 9 PM.",
      "Some detail line here.",
      "",
      "Followups:",
      "- Set an alarm",
      "- Time in another city",
      "- More details",
    ].join("\n");
    expect(extractFollowups(buf)).toEqual([
      "Set an alarm",
      "Time in another city",
      "More details",
    ]);
  });

  it("handles bold/markdown header + Spanish 'Sugerencias' header", () => {
    const buf = [
      "Aquí está la respuesta.",
      "",
      "**Sugerencias:**",
      "* ¿Pongo una alarma?",
      "* ¿Hora en Bogotá?",
    ].join("\n");
    expect(extractFollowups(buf)).toEqual([
      "¿Pongo una alarma?",
      "¿Hora en Bogotá?",
    ]);
  });

  it("returns [] when no Followups block is present", () => {
    const buf =
      "Just a plain answer with a list:\n- not a followup\n- still not";
    expect(extractFollowups(buf)).toEqual([]);
  });

  it("truncates items longer than 40 chars with ellipsis", () => {
    const long = "x".repeat(60);
    const buf = `Followups:\n- ${long}`;
    const out = extractFollowups(buf);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatch(/x{30,40}…$/);
    expect(out[0]!.length).toBeLessThanOrEqual(41);
  });

  it("strips wrapping markdown emphasis on items", () => {
    const buf = "Followups:\n- **¿Más detalles?**\n- *Otra opción*";
    expect(extractFollowups(buf)).toEqual(["¿Más detalles?", "Otra opción"]);
  });
});
