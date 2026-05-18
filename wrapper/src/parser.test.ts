import { describe, expect, it } from "vitest";
import {
  extractFollowups,
  extractPermissionPrompt,
  extractResponseLines,
  extractTokenCount,
  extractTokenCounts,
  isAwaitingPermission,
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
